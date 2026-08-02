package com.ai.fler.core.jni

/**
 * Capstone 反汇编引擎绑定。
 *
 * capstone 已静态链接进 fler_jni.so（capstone_jni.cpp 直接调用 Capstone API），
 * 不再依赖引擎包的 libcapstone.so，SO 编辑器反汇编零引擎依赖。
 */
object CapstoneBindings {

    /**
     * 用静态链接的 Capstone 反汇编（capstone_jni.cpp 直接调用 cs_disasm_iter）。
     *
     * 覆盖完整 ARM64 指令集（SIMD/NEON/浮点等）。不可解码字显示为 .word，不截断。
     *
     * @param code 机器码字节数组
     * @param baseAddress 虚拟基地址
     * @return 指令列表；Capstone 不可用返回 null
     */
    fun disassembleWithCapstone(
        code: ByteArray,
        baseAddress: Long
    ): List<DisasmInstruction>? {
        if (code.isEmpty()) return emptyList()
        return nativeDisasm(code, baseAddress)?.toList()
    }

    /** JNI 方法：capstone_jni.cpp 中实现，直接调用静态链接的 Capstone API。 */
    @JvmStatic
    private external fun nativeDisasm(
        code: ByteArray,
        baseAddress: Long
    ): Array<DisasmInstruction>?
}
