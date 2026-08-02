package com.ai.fler.core.analysis

/** 仿真引擎能力（给后续 Unicorn / Unidbg 预留）。 */
enum class EmulationCapability {
    /** 纯 CPU 指令级仿真（Unicorn）。 */
    INSTRUCTION_EMU,
    /** Android native 层加载运行（Unidbg）。 */
    ANDROID_NATIVE_EMU,
    /** Syscall Hook。 */
    SYSCALL_HOOK,
    /** 代码 Hook（指令级）。 */
    CODE_HOOK,
    /** 内存 Hook（读/写）。 */
    MEMORY_HOOK,
    /** JNI 模拟。 */
    JNI_EMU,
    /** 单步跟踪。 */
    SINGLE_STEP,
    /** GDB stub 远程调试。 */
    GDB_STUB
}

/** 仿真架构。 */
enum class EmuArch {
    ARM64, ARM, X86_64, X86, MIPS64, MIPS
}

/** 仿真运行模式（给 Unidbg 用的 API level / process name 之类；Unicorn 多数字段留空）。 */
data class EmulationOptions(
    val arch: EmuArch = EmuArch.ARM64,
    val androidApiLevel: Int? = null,        // Unidbg 专有
    val processName: String? = null,         // Unidbg 专有
    val dynLoader: String = "android",       // Unidbg: android / systemv
    val memorySizeMb: Long = 256,
    val enableLogcat: Boolean = false
)

/** 寄存器读写快照。 */
data class RegisterSnapshot(
    val values: Map<String, Long> = emptyMap()  // "x0" .. "x30", "sp", "pc", ...
)

/** 单次 Step/Run 结果。 */
data class EmuStepResult(
    val pc: Long,
    val instructionCount: Long,
    val registers: RegisterSnapshot? = null,
    val stoppedBy: StopReason = StopReason.NONE
)

/** 停止原因。 */
enum class StopReason {
    NONE, BREAKPOINT, SINGLE_STEP, TIMEOUT, ERROR, INTERRUPTED
}

/** 仿真会话句柄（独立于 [AnalysisHandle]）。 */
@JvmInline
value class EmulationHandle(val value: Long) {
    val isValid: Boolean get() = value != 0L
    companion object {
        val INVALID = EmulationHandle(0L)
    }
}

/**
 * 仿真引擎抽象接口（给 Unicorn / Unidbg 预留骨架）。
 *
 * 现阶段没有实现类，[UnicornEnginePlaceholder] 与 [UnidbgEnginePlaceholder]
 * 仅声明骨架，让后续开发者直接实现即可；所有方法默认抛
 * [NotImplementedError]，避免 UI 层误调。
 *
 * **与 BinaryAnalysisEngine 的关系**：
 * 分析阶段可以先由 RizinEngine 拿到函数偏移、符号信息，
 * 再把相同的 so 路径给 EmulationEngine 加载到模拟地址空间执行。
 */
interface EmulationEngine {

    val engineId: String
    val displayName: String
    val isAvailable: Boolean
    val capabilities: Set<EmulationCapability>

    fun supports(cap: EmulationCapability): Boolean = capabilities.contains(cap) && isAvailable

    suspend fun open(filePath: String, options: EmulationOptions = EmulationOptions()): EmulationHandle

    suspend fun close(handle: EmulationHandle)

    /**
     * 把 elf 里的某个 so 加载到模拟内存（Unidbg 专有；Unicorn 用 mapMemory 手工）。
     */
    suspend fun loadLibrary(handle: EmulationHandle, libraryPath: String, baseAddress: Long = 0L): Long?

    /** 手动映射内存页。 */
    suspend fun mapMemory(
        handle: EmulationHandle,
        baseAddress: Long,
        size: Long,
        perms: Int = 0b111    // r=1 w=2 x=4
    ): Boolean

    suspend fun readMemory(handle: EmulationHandle, address: Long, size: Long): ByteArray
    suspend fun writeMemory(handle: EmulationHandle, address: Long, data: ByteArray): Boolean

    suspend fun readRegister(handle: EmulationHandle, name: String): Long?
    suspend fun writeRegister(handle: EmulationHandle, name: String, value: Long): Boolean

    suspend fun readAllRegisters(handle: EmulationHandle): RegisterSnapshot

    /**
     * 从 current PC 开始跑最多 instrCount 条指令。
     */
    suspend fun run(handle: EmulationHandle, instrCount: Long = 0L, timeoutMs: Long = 0L): EmuStepResult

    /** 单步一条指令。 */
    suspend fun step(handle: EmulationHandle): EmuStepResult

    /** 设置 PC 指针。 */
    suspend fun setPc(handle: EmulationHandle, pc: Long)

    /** 断点管理。 */
    suspend fun addBreakpoint(handle: EmulationHandle, address: Long): Boolean
    suspend fun removeBreakpoint(handle: EmulationHandle, address: Long): Boolean
    suspend fun listBreakpoints(handle: EmulationHandle): List<Long>
}
