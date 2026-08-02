package com.ai.fler.core.analysis

/**
 * 函数分析信息（Rizin aaa 识别的函数）。
 *
 * 对应 Rizin afij / aflj。新增能力（旧自研引擎不支持）。
 */
data class FunctionInfo(
    val name: String,
    val offset: Long,                  // 文件偏移（paddr）或虚拟地址，由实现保证与反汇编 offset 对齐
    val vaddr: Long = offset,         // 虚拟地址
    val size: Long = 0,
    val nargs: Int = 0,                // 参数个数（启发式）
    val nlocals: Int = 0,              // 局部变量数
    val nbbs: Int = 0,                 // 基本块数
    val callType: String = "",         // reg / stack / ...
    val edges: Int = 0,                // CFG 边数（nbbs-1 或更多）
    val signature: String = "",        // 函数签名（Rizin afi 输出）
    val callConvention: String = ""    // 调用约定（aapcs / sysv / ...）
)

/**
 * 基本块信息（Rizin afbj）。
 */
data class BasicBlock(
    val addr: Long,
    val size: Long = 0,
    val nInstr: Int = 0,
    val succs: List<Long> = emptyList(),  // 后继基本块地址
    val preds: List<Long> = emptyList()   // 前驱基本块地址
)

/**
 * 交叉引用信息（Rizin ax/axfj/axtj）。
 */
data class Xref(
    val from: Long,
    val to: Long,
    val type: XrefType,
    val perm: String = ""      // 发生处节区权限，如 "r-x" / ".text"
)

/** 交叉引用类型。 */
enum class XrefType {
    CALL,      // 函数调用 (bl/blr)
    JUMP,      // 无条件跳转 (b/bx)
    DATA,      // 数据引用 (ldr/str)
    STRING,    // 字符串引用
    CODE,      // 未知方向的代码访问
    UNKNOWN;
    companion object {
        fun fromString(s: String): XrefType = when (s.lowercase()) {
            "call" -> CALL
            "ucall" -> CALL
            "jump" -> JUMP
            "ujump" -> JUMP
            "data" -> DATA
            "string" -> STRING
            "code" -> CODE
            else -> UNKNOWN
        }
    }
}
