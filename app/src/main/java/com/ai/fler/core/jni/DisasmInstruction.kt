package com.ai.fler.core.jni

/**
 * 一条反汇编指令（数据模型，供 SO 编辑器反汇编/汇编 Tab 展示）。
 *
 * 由 CapstoneBindings（静态链接的 Capstone）生成。
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
