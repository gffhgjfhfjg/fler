package com.ai.fler.core.jni

/**
 * Keystone 汇编器绑定（完整 AArch64 指令编码）。
 *
 * 对应 keystone_jni.cpp：keystone 静态链接进 libfler.so，直接调用 ks_asm。
 * 比 capstone 的 cs_asm（不支持 AArch64）更完整。
 */
object KeystoneBindings {

    /**
     * 汇编一条 ARM64 指令。
     *
     * @param assembly 完整指令文本（如 "mov w0, #1" / "stp x29, x30, [sp, #-0x10]!"）
     * @param address 指令所在地址（分支指令偏移量计算依赖它）
     * @return 机器码字节；Keystone 失败返回 null
     */
    fun asm(assembly: String, address: Long): ByteArray? {
        if (assembly.isBlank()) return null
        return nativeAsm(assembly, address)
    }

    /** JNI 方法：keystone_jni.cpp 中实现（静态链接 keystone）。 */
    @JvmStatic
    private external fun nativeAsm(assembly: String, address: Long): ByteArray?
}
