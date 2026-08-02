package com.ai.fler.core.analysis.engine

import com.ai.fler.core.analysis.*

/**
 * Unicorn 仿真引擎占位符骨架。未实现，预留位置。
 *
 * 后续集成 Unicorn 时：
 * 1. 新建 `cpp/jni_bridge/unicorn_jni.cpp` + `RizinBindings` 同级的 `UnicornBindings.kt`
 * 2. 把本类重命名为 `UnicornEngine`，替换方法体为真实 JNI 调用
 * 3. 在 `AnalysisModule` 中注册（已有，改 isAvailable=true 即可）
 * 4. 不需要改 ViewModel / MCP / UI 任何代码
 */
class UnicornEnginePlaceholder : EmulationEngine {
    override val engineId: String = "unicorn"
    override val displayName: String = "Unicorn"
    override val isAvailable: Boolean = false
    override val capabilities: Set<EmulationCapability> = setOf(
        EmulationCapability.INSTRUCTION_EMU,
        EmulationCapability.CODE_HOOK,
        EmulationCapability.MEMORY_HOOK,
        EmulationCapability.SINGLE_STEP
    )
    override suspend fun open(filePath: String, options: EmulationOptions): EmulationHandle = notImpl()
    override suspend fun close(handle: EmulationHandle) {}
    override suspend fun loadLibrary(handle: EmulationHandle, libraryPath: String, baseAddress: Long): Long? = null
    override suspend fun mapMemory(handle: EmulationHandle, baseAddress: Long, size: Long, perms: Int): Boolean = false
    override suspend fun readMemory(handle: EmulationHandle, address: Long, size: Long): ByteArray = notImpl()
    override suspend fun writeMemory(handle: EmulationHandle, address: Long, data: ByteArray): Boolean = false
    override suspend fun readRegister(handle: EmulationHandle, name: String): Long? = null
    override suspend fun writeRegister(handle: EmulationHandle, name: String, value: Long): Boolean = false
    override suspend fun readAllRegisters(handle: EmulationHandle): RegisterSnapshot = notImpl()
    override suspend fun run(handle: EmulationHandle, instrCount: Long, timeoutMs: Long): EmuStepResult = notImpl()
    override suspend fun step(handle: EmulationHandle): EmuStepResult = notImpl()
    override suspend fun setPc(handle: EmulationHandle, pc: Long) {}
    override suspend fun addBreakpoint(handle: EmulationHandle, address: Long): Boolean = false
    override suspend fun removeBreakpoint(handle: EmulationHandle, address: Long): Boolean = false
    override suspend fun listBreakpoints(handle: EmulationHandle): List<Long> = emptyList()
    private fun <T> notImpl(): T =
        throw NotImplementedError("Unicorn 尚未集成（架构占位）。")
}

/**
 * Unidbg 仿真引擎占位符骨架。未实现，预留位置。
 *
 * 后续集成 Unidbg 时同 [UnicornEnginePlaceholder] 的步骤。
 */
class UnidbgEnginePlaceholder : EmulationEngine {
    override val engineId: String = "unidbg"
    override val displayName: String = "Unidbg"
    override val isAvailable: Boolean = false
    override val capabilities: Set<EmulationCapability> = setOf(
        EmulationCapability.ANDROID_NATIVE_EMU,
        EmulationCapability.SYSCALL_HOOK,
        EmulationCapability.CODE_HOOK,
        EmulationCapability.JNI_EMU,
        EmulationCapability.SINGLE_STEP,
        EmulationCapability.GDB_STUB
    )
    override suspend fun open(filePath: String, options: EmulationOptions): EmulationHandle = notImpl()
    override suspend fun close(handle: EmulationHandle) {}
    override suspend fun loadLibrary(handle: EmulationHandle, libraryPath: String, baseAddress: Long): Long? = null
    override suspend fun mapMemory(handle: EmulationHandle, baseAddress: Long, size: Long, perms: Int): Boolean = false
    override suspend fun readMemory(handle: EmulationHandle, address: Long, size: Long): ByteArray = notImpl()
    override suspend fun writeMemory(handle: EmulationHandle, address: Long, data: ByteArray): Boolean = false
    override suspend fun readRegister(handle: EmulationHandle, name: String): Long? = null
    override suspend fun writeRegister(handle: EmulationHandle, name: String, value: Long): Boolean = false
    override suspend fun readAllRegisters(handle: EmulationHandle): RegisterSnapshot = notImpl()
    override suspend fun run(handle: EmulationHandle, instrCount: Long, timeoutMs: Long): EmuStepResult = notImpl()
    override suspend fun step(handle: EmulationHandle): EmuStepResult = notImpl()
    override suspend fun setPc(handle: EmulationHandle, pc: Long) {}
    override suspend fun addBreakpoint(handle: EmulationHandle, address: Long): Boolean = false
    override suspend fun removeBreakpoint(handle: EmulationHandle, address: Long): Boolean = false
    override suspend fun listBreakpoints(handle: EmulationHandle): List<Long> = emptyList()
    private fun <T> notImpl(): T =
        throw NotImplementedError("Unidbg 尚未集成（架构占位）。")
}
