package com.ai.fler.core.analysis

/**
 * 一条反汇编指令（Engine 抽象层数据模型）。
 *
 * 与 [com.ai.fler.core.jni.DisasmInstruction] 字段保持一致，
 * 便于 [SelfAnalysisEngine] 零开销桥接。Rizin / Capstone 引擎各自把解码结果
 * 转换为本类；UI 层依赖本包的 `DisasmInstruction`，不再依赖 core.jni 的。
 */
data class DisasmInstruction(
    val address: Long,
    val size: Int,
    val mnemonic: String,
    val opStr: String,
    val bytes: ByteArray
) {
    val assembly: String get() = if (opStr.isBlank()) mnemonic else "$mnemonic $opStr"
    override fun toString(): String =
        "0x${address.toString(16)}: $mnemonic $opStr"
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DisasmInstruction) return false
        return address == other.address &&
                size == other.size &&
                mnemonic == other.mnemonic &&
                opStr == other.opStr &&
                bytes.contentEquals(other.bytes)
    }
    override fun hashCode(): Int {
        var result = address.hashCode()
        result = 31 * result + size
        result = 31 * result + mnemonic.hashCode()
        result = 31 * result + opStr.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
    companion object {
        @JvmStatic
        fun fromJni(i: com.ai.fler.core.jni.DisasmInstruction): DisasmInstruction =
            DisasmInstruction(
                address = i.address,
                size = i.size,
                mnemonic = i.mnemonic,
                opStr = i.opStr,
                bytes = i.bytes
            )
    }
}
