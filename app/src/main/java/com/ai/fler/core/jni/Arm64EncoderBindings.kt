package com.ai.fler.core.jni

/**
 * ARM64 指令编码器绑定。
 *
 * 支持 50+ 条 ARM64 指令编码，采用注册式架构，
 * 可动态扩展新指令。主要用于 inline hook 代码的生成。
 *
 * 使用示例:
 * ```
 * val encoder = Arm64EncoderBindings()
 * val code = encoder.encode("MOV", "x0, #42")
 * // code = 0xD2800540
 * ```
 */
class Arm64EncoderBindings {

    /**
     * 编码单条指令。
     *
     * @param instruction 指令名称（如 "ADD", "BL", "MOV"）
     * @param args 操作数（如 "x0, x1, #4" 或 "0x1234"）
     * @return 32位机器码，0 表示编码失败
     */
    fun encode(instruction: String, args: String): Long {
        return nativeEncode(instruction, args)
    }

    /**
     * 编码指令并返回字节数组。
     * 适用于需要写入二进制文件的场景。
     */
    fun encodeToBytes(instruction: String, args: String): ByteArray {
        val code = encode(instruction, args)
        return byteArrayOf(
            (code and 0xFF).toByte(),
            ((code shr 8) and 0xFF).toByte(),
            ((code shr 16) and 0xFF).toByte(),
            ((code shr 24) and 0xFF).toByte()
        )
    }

    /**
     * 编码多条指令为连续字节流。
     *
     * @param instructions 指令-操作数对列表
     * @return 连续的字节数组
     */
    fun encodeAll(instructions: List<Pair<String, String>>): ByteArray {
        val result = ByteArray(instructions.size * 4)
        for ((i, pair) in instructions.withIndex()) {
            val code = encode(pair.first, pair.second)
            val offset = i * 4
            result[offset] = (code and 0xFF).toByte()
            result[offset + 1] = ((code shr 8) and 0xFF).toByte()
            result[offset + 2] = ((code shr 16) and 0xFF).toByte()
            result[offset + 3] = ((code shr 24) and 0xFF).toByte()
        }
        return result
    }

    /**
     * 列出所有已支持的指令名称。
     */
    fun listInstructions(): List<String> {
        return nativeListInstructions()?.toList() ?: emptyList()
    }

    /**
     * 反汇编一段 ARM64 代码。
     *
     * 由原生轻量解码器（decoder.cpp）实现，覆盖编码器支持的常用指令集；
     * 未识别指令以 ".word 0xXXXXXXXX" 输出，保证不丢指令。
     *
     * @param code 机器码字节
     * @param baseAddress 首条指令的虚拟地址
     * @return 指令列表
     */
    fun disassemble(code: ByteArray, baseAddress: Long): List<DisasmInstruction> {
        if (code.isEmpty()) return emptyList()
        return nativeDisasm(code, baseAddress)?.toList() ?: emptyList()
    }

    // ========== 预设指令序列 ==========

    /**
     * 返回 x0 的指令序列: MOV X0, #0; RET
     */
    fun getReturnZeroSequence(): List<Pair<String, String>> {
        return listOf(
            "MOV" to "x0, #0",
            "RET" to ""
        )
    }

    /**
     * 加载地址并跳转: ADRP + ADD + BR
     * 用于构造 inline hook 的跳板。
     */
    fun getTrampolineSequence(targetAddress: Long): List<Pair<String, String>> {
        return listOf(
            "ADRP" to "x16, ${targetAddress and 0xFFFFFFFFL}",
            "ADD" to "x16, x16, #0",
            "BR" to "x16"
        )
    }

    // ========== JNI 方法声明 ==========

    private external fun nativeEncode(instruction: String, args: String): Long
    private external fun nativeListInstructions(): Array<String>?
    private external fun nativeDisasm(code: ByteArray, baseAddress: Long): Array<DisasmInstruction>?
}
