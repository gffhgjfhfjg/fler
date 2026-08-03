package com.ai.fler.core.analysis

import android.util.Log
import com.ai.fler.core.jni.ElfParserBindings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 高层函数调用结果。
 *
 * @param returnValue x0 返回值（有符号解释由调用方决定）
 * @param returnValueUnsigned x0 无符号十六进制展示用
 */
data class CallResult(
    val returnValue: Long,
    val returnValueUnsigned: String,
    val stopReason: StopReason,
    val instructionCount: Long,
    val pc: Long,
    val functionName: String,
    val functionAddress: Long
)

/**
 * 仿真会话门面（UI / MCP 统一入口，仿 [AnalysisSession] 设计）。
 *
 * 职责：
 * 1. 按 so 路径管理 [EmulationEngine] 会话（同路径复用，close 显式释放）
 * 2. 函数名 → 地址解析（优先 SoEditorCache 元数据，回退 ElfParser 符号表）
 * 3. [callFunction] 高层调用：写参 x0-x7 → LR=哨兵 → PC=函数 → run → 读 x0
 *
 * 线程安全：mutex 保护会话表；引擎内部另有会话级互斥。
 */
@Singleton
class EmulationSession @Inject constructor(
    private val registry: EngineRegistry,
    private val soEditorCache: SoEditorCache
) {
    companion object {
        private const val TAG = "EmulationSession"

        /** callFunction 默认超时（防止死循环阻塞 UI/MCP）。 */
        const val DEFAULT_CALL_TIMEOUT_MS = 30_000L

        /** callFunction 防失控指令上限。 */
        const val DEFAULT_CALL_MAX_INSTRS = 20_000_000L
    }

    private data class Entry(
        val handle: EmulationHandle,
        val engine: EmulationEngine,
        val filePath: String
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<String, Entry>() // 绝对路径 -> entry

    /** 引擎是否可用（任一仿真引擎 isAvailable）。 */
    val isAvailable: Boolean
        get() = registry.pickEmulationFor(EmulationCapability.INSTRUCTION_EMU) != null

    // ------------------------------------------------------------------
    // 会话生命周期
    // ------------------------------------------------------------------

    /** 打开（或复用）某 so 的仿真会话。返回句柄；无可用引擎/打开失败返回 null。 */
    suspend fun open(filePath: String): EmulationHandle? = mutex.withLock {
        sessions[filePath]?.let { return@withLock it.handle }
        val engine = registry.pickEmulationFor(EmulationCapability.INSTRUCTION_EMU)
            ?: return@withLock null.also { Log.w(TAG, "no emulation engine available") }
        val handle = engine.open(filePath)
        if (!handle.isValid) {
            Log.e(TAG, "engine.open failed: $filePath")
            return@withLock null
        }
        sessions[filePath] = Entry(handle, engine, filePath)
        Log.i(TAG, "session opened: $filePath (engine=${engine.engineId})")
        handle
    }

    suspend fun close(filePath: String) = mutex.withLock {
        sessions.remove(filePath)?.let { entry ->
            entry.engine.close(entry.handle)
            Log.i(TAG, "session closed: $filePath")
        }
        Unit
    }

    suspend fun isOpen(filePath: String): Boolean = mutex.withLock { filePath in sessions }

    /** 当前所有会话路径（MCP 状态查询用）。 */
    suspend fun openPaths(): List<String> = mutex.withLock { sessions.keys.toList() }

    private suspend fun resolve(filePath: String): Entry? = mutex.withLock { sessions[filePath] }

    // ------------------------------------------------------------------
    // 函数解析 + 高层调用
    // ------------------------------------------------------------------

    /**
     * 按名字解析函数虚拟地址。
     *
     * 查找顺序：
     * 1. SoEditorCache 的 Rizin 函数表（已做过 aaa 分析时最全）
     * 2. SoEditorCache 的符号表
     * 3. ElfParser 直接解析 .symtab/.dynsym（无分析缓存时的兜底，支持 hex 地址直查）
     */
    suspend fun resolveFunction(filePath: String, name: String): Long? {
        val asAddress = parseAddress(name)
        val meta = soEditorCache.getMetadata(filePath)
        if (meta != null) {
            meta.functions.firstOrNull { it.name == name }?.let { return it.vaddr }
            (meta.staticSymbols + meta.dynamicSymbols)
                .firstOrNull { it.name == name }
                ?.let { return it.address }
        }
        // 兜底：直接解析 ELF 符号表
        ElfParserBindings().use { elf ->
            if (!elf.open(filePath)) return asAddress
            val syms = elf.getSymbols() + elf.getDynamicSymbols()
            syms.firstOrNull { it.name == name && it.address != 0L }
                ?.let { return it.address }
        }
        return asAddress
    }

    /**
     * 高层函数调用：参数写 x0-x7（最多 8 个），LR=哨兵，PC=函数地址，
     * run 到哨兵/断点/超时/指令上限，返回 x0。
     */
    suspend fun callFunction(
        filePath: String,
        functionName: String,
        args: List<Long> = emptyList(),
        timeoutMs: Long = DEFAULT_CALL_TIMEOUT_MS,
        maxInstrs: Long = DEFAULT_CALL_MAX_INSTRS
    ): CallResult? {
        val handle = open(filePath) ?: return null
        val entry = resolve(filePath) ?: return null
        val engine = entry.engine

        val addr = resolveFunction(filePath, functionName) ?: run {
            Log.w(TAG, "function not found: $functionName in $filePath")
            return null
        }

        // 准备调用状态：参数 → x0-x7，LR → 哨兵，PC → 函数入口
        args.take(8).forEachIndexed { i, v ->
            engine.writeRegister(handle, "x$i", v)
        }
        engine.writeRegister(handle, "lr", com.ai.fler.core.jni.UnicornBindings.SENTINEL_ADDR)
        engine.setPc(handle, addr)

        val result = engine.run(handle, instrCount = maxInstrs, timeoutMs = timeoutMs)
        val x0 = engine.readRegister(handle, "x0") ?: 0L
        return CallResult(
            returnValue = x0,
            returnValueUnsigned = "0x${java.lang.Long.toUnsignedString(x0, 16)}",
            stopReason = result.stoppedBy,
            instructionCount = result.instructionCount,
            pc = result.pc,
            functionName = functionName,
            functionAddress = addr
        )
    }

    // ------------------------------------------------------------------
    // 直通操作（run/step/寄存器/内存/断点）
    // ------------------------------------------------------------------

    suspend fun run(filePath: String, instrCount: Long = 0L, timeoutMs: Long = 0L): EmuStepResult? {
        val entry = resolve(filePath) ?: return null
        return entry.engine.run(entry.handle, instrCount, timeoutMs)
    }

    suspend fun step(filePath: String): EmuStepResult? {
        val entry = resolve(filePath) ?: return null
        return entry.engine.step(entry.handle)
    }

    /** 中断正在运行的会话（非挂起，跨线程安全）。 */
    fun requestStop(filePath: String) {
        // 不加锁读取：sessions 只在协程内增删，读旧值最坏是 no-op
        val entry = sessions[filePath] ?: return
        entry.engine.requestStop(entry.handle)
    }

    suspend fun setPc(filePath: String, pc: Long) {
        val entry = resolve(filePath) ?: return
        entry.engine.setPc(entry.handle, pc)
    }

    suspend fun readRegisters(filePath: String): RegisterSnapshot {
        val entry = resolve(filePath) ?: return RegisterSnapshot()
        return entry.engine.readAllRegisters(entry.handle)
    }

    suspend fun readRegister(filePath: String, name: String): Long? {
        val entry = resolve(filePath) ?: return null
        return entry.engine.readRegister(entry.handle, name)
    }

    suspend fun writeRegister(filePath: String, name: String, value: Long): Boolean {
        val entry = resolve(filePath) ?: return false
        return entry.engine.writeRegister(entry.handle, name, value)
    }

    suspend fun readMemory(filePath: String, address: Long, size: Long): ByteArray {
        val entry = resolve(filePath) ?: return ByteArray(0)
        return entry.engine.readMemory(entry.handle, address, size)
    }

    suspend fun writeMemory(filePath: String, address: Long, data: ByteArray): Boolean {
        val entry = resolve(filePath) ?: return false
        return entry.engine.writeMemory(entry.handle, address, data)
    }

    suspend fun mapMemory(filePath: String, address: Long, size: Long, perms: Int = 0b111): Boolean {
        val entry = resolve(filePath) ?: return false
        return entry.engine.mapMemory(entry.handle, address, size, perms)
    }

    suspend fun addBreakpoint(filePath: String, address: Long): Boolean {
        val entry = resolve(filePath) ?: return false
        return entry.engine.addBreakpoint(entry.handle, address)
    }

    suspend fun removeBreakpoint(filePath: String, address: Long): Boolean {
        val entry = resolve(filePath) ?: return false
        return entry.engine.removeBreakpoint(entry.handle, address)
    }

    suspend fun listBreakpoints(filePath: String): List<Long> {
        val entry = resolve(filePath) ?: return emptyList()
        return entry.engine.listBreakpoints(entry.handle)
    }

    // ------------------------------------------------------------------

    /** 解析 hex（0x 前缀）或十进制地址文本；非法返回 null。 */
    private fun parseAddress(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return if (t.startsWith("0x", ignoreCase = true)) {
            t.substring(2).toLongOrNull(16)
        } else {
            t.toLongOrNull(16) ?: t.toLongOrNull(10)
        }
    }
}
