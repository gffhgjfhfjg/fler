package com.ai.fler.core.analysis

import com.ai.fler.core.service.BackupManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 统一的分析会话层。
 *
 * 这是 UI / MCP 调用引擎的**唯一官方入口**，职责：
 * 1. 封装 [EngineRegistry] 挑选引擎的流程，调用方不用关心使用哪个具体引擎。
 * 2. 维护「文件路径 -> 会话 handle -> 所属 engineId」的映射，保证 SoEditorViewModel
 *    打开同一个 so 时多次调用，都能稳定拿到同一 handle。
 * 3. 在会话层集中注入 BackupManager，实现 ByteEdit 级别的 patch 栈，
 *    让 RizinEngine/SelfAnalysisEngine 共享一致的撤销行为。
 *
 * 线程安全：所有 open/close 用 Mutex 串行化；内部 map 非并发（Kotlin Map 非线程安全），
 * 但所有访问都在 Mutex 内执行。
 */
@Singleton
class AnalysisSession @Inject constructor(
    private val registry: EngineRegistry,
    private val backupManager: BackupManager
) {
    private data class SessionEntry(
        val handle: AnalysisHandle,
        val engineId: String,
        val filePath: String
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<Long, SessionEntry>()    // handle.value -> entry
    private val pathToHandle = mutableMapOf<String, Long>()       // 绝对路径 -> handle.value
    private var nextHandleSeq = 1L

    // 当前打开的默认会话（UI 层打开某文件时默认绑定）
    private var currentHandle: AnalysisHandle = AnalysisHandle.INVALID
    private var currentEngine: BinaryAnalysisEngine? = null

    /** 当前已打开会话对应的 handle。未 open 返回 INVALID。 */
    suspend fun currentHandle(): AnalysisHandle = mutex.withLock { currentHandle }

    /** 当前已打开会话对应的引擎。 */
    suspend fun currentEngine(): BinaryAnalysisEngine? = mutex.withLock { currentEngine }

    // ------------------------------------------------------------------
    // 会话生命周期
    // ------------------------------------------------------------------

    /**
     * 打开 so 会话。
     *
     * 策略：按优先级挑选支持能力组合的引擎，若已按同路径 open 过则复用旧 handle
     * （但会用新 engine 重新 open，保证用户切换引擎时生效）。
     */
    suspend fun open(
        filePath: String,
        options: OpenOptions = OpenOptions(),
        requireCaps: List<AnalysisCapability> = emptyList()
    ): OpenResult {
        if (!File(filePath).exists()) {
            return OpenResult.Failure("文件不存在: $filePath")
        }

        mutex.withLock {
            // 相同路径已有会话则直接复用（不重新 open / 不重新 aaa 分析）
            val existingH = pathToHandle[filePath]
            if (existingH != null) {
                val entry = sessions[existingH]
                if (entry != null) {
                    val engine = registry.getAnalysis(entry.engineId)
                    if (engine != null && engine.isHandleValid(AnalysisHandle(existingH))) {
                        currentHandle = AnalysisHandle(existingH)
                        currentEngine = engine
                        backupManager.setCurrentFile(filePath)
                        return@withLock OpenResult.Success(
                            AnalysisHandle(existingH), filePath, entry.engineId
                        )
                    }
                    // handle 已失效，清理残留
                    sessions.remove(existingH)
                    pathToHandle.remove(filePath)
                }
            }
        }

        // 按优先级获取所有匹配能力的引擎，逐个尝试直到成功
        val engines = registry.listAnalysisSupporting(*requireCaps.toTypedArray())
        if (engines.isEmpty()) {
            return OpenResult.Failure("没有可用的分析引擎（需要能力: $requireCaps）")
        }

        // 逐个尝试引擎，高优先级失败则降级到下一个
        var lastReason: String? = null
        for (engine in engines) {
            when (val r = engine.open(filePath, options)) {
                is OpenResult.Success -> return mutex.withLock {
                    sessions[r.handle.value] = SessionEntry(r.handle, engine.engineId, filePath)
                    pathToHandle[filePath] = r.handle.value
                    currentHandle = r.handle
                    currentEngine = engine
                    backupManager.setCurrentFile(filePath)
                    OpenResult.Success(r.handle, filePath, engine.engineId)
                }
                is OpenResult.Failure -> {
                    lastReason = r.reason
                    // 继续尝试下一个引擎
                }
            }
        }
        return OpenResult.Failure(lastReason ?: "所有引擎均无法打开文件")
    }

    /** 显式指定 engineId 打开（MCP 层或用户切换引擎时使用）。 */
    suspend fun openWithEngine(
        filePath: String,
        engineId: String,
        options: OpenOptions = OpenOptions()
    ): OpenResult {
        val engine = registry.getAnalysis(engineId)
            ?: return OpenResult.Failure("未注册引擎: $engineId")
        return open(filePath, options, requireCaps = engine.capabilities.toList())
    }

    suspend fun closeAll() {
        mutex.withLock {
            for ((hv, entry) in sessions) {
                try { registry.getAnalysis(entry.engineId)?.close(AnalysisHandle(hv)) } catch (_: Throwable) { /* noop */ }
            }
            sessions.clear()
            pathToHandle.clear()
            currentHandle = AnalysisHandle.INVALID
            currentEngine = null
        }
    }

    // ------------------------------------------------------------------
    // 内部工具：按 handle 找到 engine
    // ------------------------------------------------------------------

    private fun resolve(handle: AnalysisHandle): Pair<BinaryAnalysisEngine, SessionEntry>? {
        val entry = sessions[handle.value] ?: return null
        val engine = registry.getAnalysis(entry.engineId) ?: return null
        return engine to entry
    }

    private suspend fun <R> withEngine(
        handle: AnalysisHandle? = null,
        block: suspend (BinaryAnalysisEngine, AnalysisHandle) -> R
    ): R? {
        val actual = handle.takeIf { it != null && it.isValid } ?: currentHandle
        val (e, _) = resolve(actual) ?: return null
        return block(e, actual)
    }

    // ------------------------------------------------------------------
    // 转发方法（全部转发到已绑定的 engine，供 ViewModel / MCP 调用）
    // ------------------------------------------------------------------

    suspend fun getFileInfo(): FileInfo? = withEngine { e, h -> e.getFileInfo(h) }
    suspend fun getSections(): List<SectionInfo> = withEngine { e, h -> e.getSections(h) }.orEmpty()

    suspend fun getSymbols(includeDynamic: Boolean = true): List<SymbolInfo> =
        withEngine { e, h -> e.getSymbols(h, includeDynamic) }.orEmpty()

    suspend fun getImports(): List<ImportInfo> = withEngine { e, h -> e.getImports(h) }.orEmpty()
    suspend fun getRelocs(): List<RelocInfo> = withEngine { e, h -> e.getRelocs(h) }.orEmpty()

    suspend fun scanStrings(options: StringScanOptions = StringScanOptions()): List<StringInfo> =
        withEngine { e, h -> e.scanStrings(h, options) }.orEmpty()

    suspend fun listFunctions(): List<FunctionInfo> = withEngine { e, h -> e.listFunctions(h) }.orEmpty()
    suspend fun findFunctionContaining(address: Long): FunctionInfo? =
        withEngine { e, h -> e.findFunctionContaining(h, address) }
    suspend fun findFunctionsByName(query: String): List<FunctionInfo> =
        withEngine { e, h -> e.findFunctionsByName(h, query) }.orEmpty()
    suspend fun getFunctionCfg(functionOffset: Long): List<BasicBlock> =
        withEngine { e, h -> e.getFunctionCfg(h, functionOffset) }.orEmpty()

    /** 在指定地址定义函数并命名（注入 Blutter Dart 方法名等外部分析结果）。 */
    suspend fun defineFunction(address: Long, name: String): Boolean =
        withEngine { e, h -> e.defineFunction(h, address, name) } ?: false

    /** 批量定义函数。返回成功定义的数量。 */
    suspend fun defineFunctions(functions: List<Pair<Long, String>>): Int {
        var count = 0
        for ((addr, name) in functions) {
            if (defineFunction(addr, name)) count++
        }
        return count
    }

    /**
     * 重新分析交叉引用（补充 xref 表）。
     *
     * defineFunction 已改为只设 flag 不调 af，不会破坏 xref 表。
     * 本方法主要用于其他场景下的 xref 重建。
     * 仅对支持 FUNCTION_ANALYSIS 的引擎有效，其他引擎 no-op。
     */
    suspend fun reanalyzeXrefs(): Boolean =
        withEngine { e, h -> e.reanalyzeXrefs(h) } ?: false

    suspend fun disassemble(offset: Long, size: Long): List<DisasmInstruction> =
        withEngine { e, h -> e.disassemble(h, offset, size) }.orEmpty()

    suspend fun assemble(assembly: String, address: Long = 0L): ByteArray? =
        withEngine { e, h -> e.assemble(h, assembly, address) }

    suspend fun xrefsTo(target: Long): List<Xref> =
        withEngine { e, h -> e.xrefsTo(h, target) }.orEmpty()
    suspend fun xrefsFrom(from: Long): List<Xref> =
        withEngine { e, h -> e.xrefsFrom(h, from) }.orEmpty()

    suspend fun readBytes(offset: Long, size: Long): ByteArray =
        withEngine { e, h -> e.readBytes(h, offset, size) } ?: ByteArray(0)

    /**
     * 写字节并记录补丁（可撤销）。
     *
     * 引擎层写盘 + 本层统一记录 patch，Rizin/Self 都走同一套撤销栈。
     */
    suspend fun writeBytes(offset: Long, data: ByteArray, soNameHint: String = ""): Boolean {
        val pair = withEngine { e, h ->
            val old = e.readBytes(h, offset, data.size.toLong())
            val ok = e.writeBytes(h, offset, data)
            Triple(ok, old, soNameHint.ifBlank { resolve(h)?.second?.filePath?.substringAfterLast('/') ?: "" })
        } ?: return false
        if (pair.first && pair.second.size == data.size) {
            backupManager.recordPatch(offset, pair.second, data, pair.third)
        }
        return pair.first
    }

    /**
     * 直接写盘不记录补丁（用于撤销操作本身，避免撤回 → 记录新补丁 → 死循环）。
     */
    suspend fun writeRawBytes(offset: Long, data: ByteArray): Boolean =
        withEngine { e, h -> e.writeBytes(h, offset, data) } ?: false

    suspend fun paddrToVaddr(paddr: Long): Long =
        withEngine { e, h -> e.paddrToVaddr(h, paddr) } ?: paddr
    suspend fun vaddrToPaddr(vaddr: Long): Long =
        withEngine { e, h -> e.vaddrToPaddr(h, vaddr) } ?: vaddr

    suspend fun md5(): String? = withEngine { e, h -> e.md5(h) }
    suspend fun sha256(): String? = withEngine { e, h -> e.sha256(h) }
    suspend fun crc32(offset: Long? = null, size: Long? = null): Long? =
        withEngine { e, h -> e.crc32(h, offset, size) }

    companion object {
        const val TAG = "AnalysisSession"
    }
}
