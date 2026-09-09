package com.ai.fler.core.jni

/**
 * Unicorn 仿真引擎绑定。
 *
 * unicorn 打包在 libfler_unicorn.so（unicorn_jni.cpp 直接调用 uc_* API，
 * 首次使用时经 [NativeLoader] 懒加载），不依赖引擎包的 libunicorn.so。
 *
 * 编译期可用 ENABLE_UNICORN=OFF 关闭（JNI 退化为安全 stub，isAvailable=false）。
 *
 * 会话模型：[open] 返回 native handle（0=失败），用毕必须 [close]。
 * 地址空间布局（open 时固定）：
 * - 栈 0x40000000 + 1MB（SP 自动指向栈顶）
 * - heap 0x50000000 + 8MB
 * - 哨兵页 0xDEADBEEF0000：把 LR 设为该地址后 run，执行到此即 FUNCTION_RETURN
 */
object UnicornBindings {

    /** so 未加载（懒加载失败）时各方法返回降级默认值（与引擎不可用语义一致）。 */
    private fun ready(): Boolean = NativeLoader.tryLoadComponent(NativeLoader.Component.UNICORN)

    /**
     * 引擎是否可用（aarch64 后端已编译进来）。
     * 关闭编译开关或链接缺失时返回 false，UI/引擎注册据此降级。
     */
    val isAvailable: Boolean by lazy { ready() && nativeIsAvailable() }

    /**
     * Unicorn 版本字符串（如 "2.0"）；编译禁用时为 "disabled"。
     */
    val version: String by lazy { if (ready()) nativeGetVersion() else "disabled" }

    // 停止原因码（与 unicorn_jni.cpp StopCode / Kotlin StopReason ordinal 对齐）
    const val STOP_NONE = 0
    const val STOP_BREAKPOINT = 1
    const val STOP_SINGLE_STEP = 2
    const val STOP_TIMEOUT = 3
    const val STOP_ERROR = 4
    const val STOP_INTERRUPTED = 5
    const val STOP_FUNCTION_RETURN = 6

    /** 哨兵返回地址：写入 LR 后执行到此地址视为函数返回。 */
    const val SENTINEL_ADDR: Long = 0xDEADBEEF0000L

    /** 寄存器名-值对（nativeReadAllRegisters 的元素类型）。 */
    data class RegEntry(val name: String, val value: Long)

    /** 单次运行结果：stopReason 码 + 停止时 PC + 执行指令数。 */
    data class RunResult(val stopReason: Int, val pc: Long, val instructionCount: Long)

    /**
     * 打开仿真会话（uc_open ARM64 + 栈/heap/哨兵预映射 + 指令/内存钩子）。
     * @return native handle；0 表示失败
     */
    fun open(): Long = if (ready()) nativeOpen() else 0L

    /** 关闭并释放会话。幂等（handle=0 直接返回）。 */
    fun close(handle: Long) {
        if (handle != 0L && ready()) nativeClose(handle)
    }

    /** 映射内存页。perms：1=R 2=W 4=X（与 ELF p_flags 同义）。 */
    fun mapMemory(handle: Long, address: Long, size: Long, perms: Int): Boolean =
        if (ready()) nativeMapMemory(handle, address, size, perms) else false

    /** 读内存；未映射或越界返回 null。 */
    fun readMemory(handle: Long, address: Long, size: Long): ByteArray? =
        if (ready()) nativeReadMemory(handle, address, size) else null

    fun writeMemory(handle: Long, address: Long, data: ByteArray): Boolean =
        if (ready()) nativeWriteMemory(handle, address, data) else false

    /** 读寄存器（"x0".."x30"/"fp"/"lr"/"sp"/"pc"/"nzcv"）；未知名返回 null。 */
    fun readRegister(handle: Long, name: String): Long? {
        // native 对未知寄存器名返回 0，与真实值 0 不可区分，先做名字合法性判断
        if (name !in REG_NAMES) return null
        return if (ready()) nativeReadRegister(handle, name) else null
    }

    fun writeRegister(handle: Long, name: String, value: Long): Boolean =
        if (ready()) nativeWriteRegister(handle, name, value) else false

    /** 批量读全部通用寄存器（一次 JNI 往返）。 */
    fun readAllRegisters(handle: Long): Map<String, Long> {
        if (!ready()) return emptyMap()
        val entries = nativeReadAllRegisters(handle) ?: return emptyMap()
        return entries.associate { it.name to it.value }
    }

    /**
     * 从当前 PC 开始执行。
     * @param instrCount 最多执行指令数，0=不限
     * @param timeoutMs 超时毫秒，0=不限
     */
    fun run(handle: Long, instrCount: Long = 0L, timeoutMs: Long = 0L): RunResult? {
        if (!ready()) return null
        val arr = nativeRun(handle, instrCount, timeoutMs) ?: return null
        return RunResult(arr[0].toInt(), arr[1], arr[2])
    }

    /** 单步一条指令。 */
    fun step(handle: Long): RunResult? {
        if (!ready()) return null
        val arr = nativeStep(handle) ?: return null
        return RunResult(arr[0].toInt(), arr[1], arr[2])
    }

    /** 请求停止正在运行的会话（跨线程安全，下一指令边界生效）。 */
    fun requestStop(handle: Long) {
        if (ready()) nativeRequestStop(handle)
    }

    fun setPc(handle: Long, pc: Long): Boolean =
        if (ready()) nativeSetPc(handle, pc) else false

    fun addBreakpoint(handle: Long, address: Long): Boolean =
        if (ready()) nativeAddBreakpoint(handle, address) else false

    fun removeBreakpoint(handle: Long, address: Long): Boolean =
        if (ready()) nativeRemoveBreakpoint(handle, address) else false

    fun listBreakpoints(handle: Long): List<Long> {
        if (!ready()) return emptyList()
        return nativeListBreakpoints(handle)?.toList() ?: emptyList()
    }

    private val REG_NAMES = setOf(
        "x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7",
        "x8", "x9", "x10", "x11", "x12", "x13", "x14", "x15",
        "x16", "x17", "x18", "x19", "x20", "x21", "x22", "x23",
        "x24", "x25", "x26", "x27", "x28", "fp", "lr", "sp", "pc", "nzcv"
    )

    // ========== JNI 方法声明 ==========

    @JvmStatic private external fun nativeIsAvailable(): Boolean
    @JvmStatic private external fun nativeGetVersion(): String
    @JvmStatic private external fun nativeOpen(): Long
    @JvmStatic private external fun nativeClose(handle: Long)
    @JvmStatic private external fun nativeMapMemory(handle: Long, address: Long, size: Long, perms: Int): Boolean
    @JvmStatic private external fun nativeReadMemory(handle: Long, address: Long, size: Long): ByteArray?
    @JvmStatic private external fun nativeWriteMemory(handle: Long, address: Long, data: ByteArray): Boolean
    @JvmStatic private external fun nativeReadRegister(handle: Long, name: String): Long
    @JvmStatic private external fun nativeWriteRegister(handle: Long, name: String, value: Long): Boolean
    @JvmStatic private external fun nativeReadAllRegisters(handle: Long): Array<RegEntry>?
    @JvmStatic private external fun nativeRun(handle: Long, instrCount: Long, timeoutMs: Long): LongArray?
    @JvmStatic private external fun nativeStep(handle: Long): LongArray?
    @JvmStatic private external fun nativeRequestStop(handle: Long)
    @JvmStatic private external fun nativeSetPc(handle: Long, pc: Long): Boolean
    @JvmStatic private external fun nativeAddBreakpoint(handle: Long, address: Long): Boolean
    @JvmStatic private external fun nativeRemoveBreakpoint(handle: Long, address: Long): Boolean
    @JvmStatic private external fun nativeListBreakpoints(handle: Long): LongArray?
}
