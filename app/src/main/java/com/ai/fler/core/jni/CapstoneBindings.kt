package com.ai.fler.core.jni

/**
 * Capstone 引擎绑定。
 *
 * 统一使用引擎包中已加载的 libcapstone.so（capstone_jni.cpp，经 dlopen/dlsym）：
 * - [disassembleWithCapstone]：cs_disasm 反汇编 ARM64 代码（SKIPDATA，遇不可解码字节不截断）
 *
 * 说明：汇编（指令编辑）走 Keystone（[KeystoneBindings]）+ 自研编码器；
 * capstone 的 cs_asm 不支持 AArch64。
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
