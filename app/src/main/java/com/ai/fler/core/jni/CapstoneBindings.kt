package com.ai.fler.core.jni

/**
 * Capstone 反汇编引擎绑定。
 *
 * 统一使用引擎包中已加载的 libcapstone.so（capstone_jni.cpp，经 dlopen/dlsym）：
 * - [disassembleWithCapstone]：cs_disasm_iter 反汇编 ARM64 代码（不可解码字显示为 .word，不截断）
 *
 * 说明：指令编码用 Keystone（[KeystoneBindings]）；本类仅提供反汇编。
 */
object CapstoneBindings {

    /**
     * 用引擎包中已加载的 libcapstone.so 反汇编（capstone_jni.cpp，经 dlopen/dlsym 调用真实 Capstone）。
     *
     * 覆盖完整 ARM64 指令集（SIMD/NEON/浮点等）。libcapstone.so 不可用（未下载引擎包或符号缺失）
     * 时返回 null。
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

    /** JNI 方法：capstone_jni.cpp 中实现，经 dlopen 复用引擎包的 libcapstone.so。 */
    @JvmStatic
    private external fun nativeDisasm(
        capstonePath: String,
        code: ByteArray,
        baseAddress: Long
    ): Array<DisasmInstruction>?
}
