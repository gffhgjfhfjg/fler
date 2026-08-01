package com.ai.fler.core.jni

/**
 * Capstone 引擎绑定。
 *
 * 统一使用引擎包中已加载的 libcapstone.so（capstone_jni.cpp，经 dlopen/dlsym）：
 * - [disassembleWithCapstone]：cs_disasm 反汇编 ARM64 代码（SKIPDATA 开启，遇不可解码字节不截断）
 * - [assembleWithCapstone]：cs_asm 汇编 ARM64 指令（指令编辑补丁用）
 *
 * libcapstone.so 不可用（未下载引擎包或符号缺失）时两者都返回 null。
 */
object CapstoneBindings {

    /**
     * 用引擎包中已加载的 libcapstone.so 反汇编（capstone_jni.cpp，经 dlopen/dlsym 调用真实 Capstone）。
     *
     * 覆盖完整 ARM64 指令集（SIMD/NEON/浮点等）。libcapstone.so 不可用（未下载引擎包或符号缺失）
     * 时返回 null，调用方应回退到自研解码器 [Arm64EncoderBindings.disassemble]。
     *
     * @param capstonePath lib/libcapstone.so 的绝对路径
     * @param code 机器码字节数组
     * @param baseAddress 虚拟基地址
     * @return 指令列表；Capstone 不可用返回 null
     */
    fun disassembleWithCapstone(
        capstonePath: String,
        code: ByteArray,
        baseAddress: Long
    ): List<DisasmInstruction>? {
        if (code.isEmpty()) return emptyList()
        return nativeDisasm(capstonePath, code, baseAddress)?.toList()
    }

    /**
     * 用 Capstone 汇编 ARM64 指令（cs_asm），返回编码后的机器码。
     *
     * @param capstonePath lib/libcapstone.so 的绝对路径
     * @param assembly 指令文本，如 "mov w0, #1"；多条指令可用 ';' 分隔
     * @param address 指令所在地址（分支指令的偏移量计算依赖它）
     * @return 编码后的机器码字节；Capstone 不可用或汇编失败返回 null
     */
    fun assembleWithCapstone(
        capstonePath: String,
        assembly: String,
        address: Long
    ): ByteArray? {
        if (assembly.isBlank()) return null
        return nativeAssemble(capstonePath, assembly, address)
    }

    /**
     * 反汇编指定内存范围（自研轻量解码器）。
     */
    fun disassemble(code: ByteArray, baseAddress: Long): List<DisasmInstruction> {
        if (code.isEmpty()) return emptyList()
        return Arm64EncoderBindings().disassemble(code, baseAddress)
    }

    /**
     * 反汇编单条指令。
     */
    fun disassembleOne(code: ByteArray, baseAddress: Long): DisasmInstruction? {
        val result = disassemble(code, baseAddress)
        return result.firstOrNull()
    }

    /**
     * 将反汇编指令格式化为可读字符串。
     */
    fun formatInstruction(instr: DisasmInstruction): String {
        return "0x${instr.address.toString(16).padStart(16, '0')}: " +
               "${instr.mnemonic} ${instr.opStr}"
    }

    /** JNI 方法：capstone_jni.cpp 中实现，经 dlopen 复用引擎包的 libcapstone.so。 */
    @JvmStatic
    private external fun nativeDisasm(
        capstonePath: String,
        code: ByteArray,
        baseAddress: Long
    ): Array<DisasmInstruction>?

    /** JNI 方法：capstone_jni.cpp 中实现，经 dlopen 复用引擎包的 libcapstone.so（cs_asm）。 */
    @JvmStatic
    private external fun nativeAssemble(
        capstonePath: String,
        assembly: String,
        address: Long
    ): ByteArray?
}

/**
 * 一条反汇编指令。
 */
data class DisasmInstruction(
    val address: Long,
    val size: Int,
    val mnemonic: String,
    val opStr: String,
    val bytes: ByteArray
) {
    override fun toString(): String =
        "0x${address.toString(16)}: $mnemonic $opStr"

    override fun equals(other: Any?): Boolean =
        other is DisasmInstruction &&
        address == other.address &&
        mnemonic == other.mnemonic &&
        opStr == other.opStr

    override fun hashCode(): Int =
        (address and 0xFFFFFFFFL).toInt() xor mnemonic.hashCode() xor opStr.hashCode()
}
