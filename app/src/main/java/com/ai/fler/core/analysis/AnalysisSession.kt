package com.ai.fler.core.analysis

import android.util.Log
import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.service.BackupManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * 线程安全：所有 open/close 与引擎操作通过同一把 Mutex 串行化。
 * 引擎底层（RzCore 命令）非线程安全，同一 RzCore 并发执行命令会破坏 Rizin 内部状态，
 * 因此转发方法同样持有 Mutex；sessions/pathToHandle 的读写也全部在 Mutex 内完成。
 * 会话数超过 [MAX_SESSIONS] 时按最近使用时间淘汰最旧会话，避免长期运行泄漏。
 */
@Singleton
class AnalysisSession @Inject constructor(
    private val registry: EngineRegistry,
    private val backupManager: BackupManager,
    private val soEditorCache: SoEditorCache,
    private val appLogger: AppLogger,
) {
    private data class SessionEntry(
        val handle: AnalysisHandle,
        val engineId: String,
        val filePath: String,
        var lastAccess: Long
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<Long, SessionEntry>()    // handle.value -> entry
    private val pathToHandle = mutableMapOf<String, Long>()       // 绝对路径 -> handle.value
    private var nextHandleSeq = 1L

    // 当前打开的默认会话（UI 层打开某文件时默认绑定）。
    // 三个字段均为 @Volatile：纯内存读方法（currentHandle/currentEngine/currentFilePath）
    // 直接读这三个字段，不走 Mutex，避免 UI 状态查询被引擎查询阻塞。
    // 写入全部在 mutex.withLock 内完成（open/openWithEngine/closeAll/evictIfNeeded）。
    // 字段名加 Value 后缀避免与方法名同名（Kotlin 属性 getter 与方法签名冲突）。
    @Volatile private var currentHandleValue: AnalysisHandle = AnalysisHandle.INVALID
    @Volatile private var currentEngineValue: BinaryAnalysisEngine? = null
    @Volatile private var currentFilePathValue: String? = null

    /** 当前已打开会话对应的 handle。未 open 返回 INVALID。纯内存读，零阻塞。 */
    fun currentHandle(): AnalysisHandle = currentHandleValue

    /** 当前已打开会话对应的引擎。纯内存读，零阻塞。 */
    fun currentEngine(): BinaryAnalysisEngine? = currentEngineValue

    /** 当前已打开会话对应的文件路径（用于坐标轴换算等）。未 open 返回 null。纯内存读，零阻塞。 */
    fun currentFilePath(): String? = currentFilePathValue

    // ------------------------------------------------------------------
    // 会话生命周期
    // ------------------------------------------------------------------

    /**
     * 打开 so 会话。
     *
     * 策略：按优先级挑选支持能力组合的引擎；若已按同路径 open 过则直接复用旧会话
     * （不重新 open / 不重新 aaa 分析，避免重复分析开销）。
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
                        currentHandleValue = AnalysisHandle(existingH)
                        currentEngineValue = engine
                        currentFilePathValue = filePath
                        backupManager.setCurrentFile(filePath)
                        // 直接返回复用会话；若用 return@withLock 只会退出 lambda，
                        // open 会继续落到下方引擎循环并重复 open 造成会话泄漏。
                        return OpenResult.Success(
                            AnalysisHandle(existingH), filePath, entry.engineId
                        )
                    }
                    // handle 已失效，清理残留
                    sessions.remove(existingH)
                    pathToHandle.remove(filePath)
                    soEditorCache.invalidate(filePath)
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
                    sessions[r.handle.value] = SessionEntry(r.handle, engine.engineId, filePath, now())
                    pathToHandle[filePath] = r.handle.value
                    currentHandleValue = r.handle
                    currentEngineValue = engine
                    currentFilePathValue = filePath
                    backupManager.setCurrentFile(filePath)
                    evictIfNeeded()
                    OpenResult.Success(r.handle, filePath, engine.engineId)
                }
                is OpenResult.Failure -> {
                    lastReason = r.reason
                    // 继续尝试下一个引擎
                }
            }
        }
        appLogger.info("AnalysisSession", "会话打开成功: $filePath, engine=${(engines.firstOrNull { true })?.engineId ?: "N/A"}")
        return OpenResult.Failure(lastReason ?: "所有引擎均无法打开文件")
    }

    /**
     * 会话数超过 [MAX_SESSIONS] 时，关闭并淘汰最近最久未使用的会话。
     * 必须在持有 mutex 时调用。
     */
    private suspend fun evictIfNeeded() {
        if (sessions.size <= MAX_SESSIONS) return
        val victim = sessions.minByOrNull { it.value.lastAccess } ?: return
        val entry = victim.value
        try { registry.getAnalysis(entry.engineId)?.close(entry.handle) } catch (_: Throwable) { /* noop */ }
        sessions.remove(victim.key)
        if (pathToHandle[entry.filePath] == victim.key) pathToHandle.remove(entry.filePath)
        // RzCore 已关闭：注入标记/元数据缓存失效，下次打开需重新查询与注入
        soEditorCache.invalidate(entry.filePath)
        if (currentHandleValue == entry.handle) {
            currentHandleValue = AnalysisHandle.INVALID
            currentEngineValue = null
            currentFilePathValue = null
        }
        Log.i(TAG, "会话数超限(${MAX_SESSIONS})，淘汰最久未使用: ${entry.filePath}")
    }

    /** 显式指定 engineId 打开（MCP 层或用户切换引擎时使用）。 */
    suspend fun openWithEngine(
        filePath: String,
        engineId: String,
        options: OpenOptions = OpenOptions()
    ): OpenResult {
        val engine = registry.getAnalysis(engineId)
            ?: return OpenResult.Failure("未注册引擎: $engineId")
        if (!File(filePath).exists()) {
            return OpenResult.Failure("文件不存在: $filePath")
        }
        return mutex.withLock {
            // 同路径且同引擎已有会话 → 直接复用
            val existingH = pathToHandle[filePath]
            if (existingH != null) {
                val entry = sessions[existingH]
                if (entry != null && entry.engineId == engineId) {
                    val eng = registry.getAnalysis(entry.engineId)
                    if (eng != null && eng.isHandleValid(AnalysisHandle(existingH))) {
                        currentHandleValue = AnalysisHandle(existingH)
                        currentEngineValue = eng
                        currentFilePathValue = filePath
                        backupManager.setCurrentFile(filePath)
                        return@withLock OpenResult.Success(
                            AnalysisHandle(existingH), filePath, engineId
                        )
                    }
                }
            }
            // 用指定引擎打开（不按能力优先级挑选——否则 self 的子能力集永远被 rizin 抢走）
            when (val r = engine.open(filePath, options)) {
                is OpenResult.Success -> {
                    sessions[r.handle.value] = SessionEntry(r.handle, engine.engineId, filePath, now())
                    pathToHandle[filePath] = r.handle.value
                    currentHandleValue = r.handle
                    currentEngineValue = engine
                    currentFilePathValue = filePath
                    backupManager.setCurrentFile(filePath)
                    evictIfNeeded()
                    OpenResult.Success(r.handle, filePath, engine.engineId)
                }
                is OpenResult.Failure -> OpenResult.Failure(r.reason)
            }
        }
    }

    suspend fun closeAll() {
        appLogger.info("AnalysisSession", "关闭所有会话")
        mutex.withLock {
            for ((hv, entry) in sessions) {
                try { registry.getAnalysis(entry.engineId)?.close(AnalysisHandle(hv)) } catch (_: Throwable) { /* noop */ }
            }
            sessions.clear()
            pathToHandle.clear()
            currentHandleValue = AnalysisHandle.INVALID
            currentEngineValue = null
            currentFilePathValue = null
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
    ): R? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val actual = handle.takeIf { it != null && it.isValid } ?: currentHandleValue
            val pair = resolve(actual) ?: return@withLock null
            pair.second.lastAccess = now()
            block(pair.first, actual)
        }
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

    /** 对当前会话执行全量分析（Rizin aaa）。非 Rizin 引擎 no-op 返回 false。 */
    suspend fun analyze(): Boolean = withEngine { e, h ->
        (e as? com.ai.fler.core.analysis.engine.RizinEngine)?.autoAnalyze(h) ?: false
    } ?: false

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
    suspend fun defineFunctions(functions: List<Pair<Long, String>>): Int =
        withEngine { e, h -> e.defineFunctions(h, functions) } ?: 0

    /**
     * 重新分析交叉引用（补充 xref 表）。
     *
     * defineFunction 只设 flag 不调 af，不会破坏 xref 表。
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
            val path = resolve(h)?.second?.filePath
            val old = e.readBytes(h, offset, data.size.toLong())
            val ok = e.writeBytes(h, offset, data)
            Triple(ok, old, soNameHint.ifBlank { path?.substringAfterLast('/') ?: "" })
        } ?: return false
        // 落盘校验由 native 层 readback 负责（rizin_jni.cpp nativeWriteBytes 已做读回比对），
        // 此处不再重复 RandomAccessFile 读盘，避免单次写产生 4 次 I/O。
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

    // ------------------------------------------------------------------
    // 诊断工具
    // ------------------------------------------------------------------

    /** 查询引擎配置项的值。 */
    suspend fun getConfig(key: String): String? =
        withEngine { e, h -> e.getConfig(h, key) }

    /**
     * 诊断地址空间状态，确认 Blutter 的 vaddr 与 Rizin 地址空间是否一致。
     * 打印日志供 logcat 分析。
     */
    suspend fun checkAddressSpace(testAddr: Long) {
        withEngine { e, h -> e.checkAddressSpace(h, testAddr) }
    }

    /**
     * 注入后诊断：确认 defineFunction + reanalyzeXrefs 后 xref 是否重建成功。
     */
    suspend fun diagnosticAfterInjection(testAddr: Long) {
        withEngine { e, h -> e.diagnosticAfterInjection(h, testAddr) }
    }

    companion object {
        const val TAG = "AnalysisSession"

        /** 同时保持打开的分析会话上限，超出后淘汰最久未使用的会话。 */
        private const val MAX_SESSIONS = 3

        private fun now(): Long = System.currentTimeMillis()
    }
}
