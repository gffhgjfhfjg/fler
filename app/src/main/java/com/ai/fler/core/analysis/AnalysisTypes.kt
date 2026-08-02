package com.ai.fler.core.analysis

/** 分析能力枚举。[BinaryAnalysisEngine.capabilities] 声明自身支持哪些能力，
 *  调用方按能力查询，避免硬编码 Rizin 专有判断。 */
enum class AnalysisCapability {
    ELF_PARSING,            // ELF 结构解析（sections/symbols/imports/...）
    DISASSEMBLY,            // 反汇编
    ASSEMBLY,               // 汇编（指令→机器码）
    FUNCTION_ANALYSIS,      // 函数识别
    XREF,                   // 交叉引用查询
    CFG,                    // 控制流图（基本块/边）
    STRING_SCAN,            // 字符串扫描
    DEMANGLE,               // 符号 demangle（C++/Rust/...）
    BYTE_EDIT,              // 字节级编辑
    ADDRESS_TRANSLATION,    // vaddr↔paddr 转换
    BINARY_HASH,            // 二进制哈希（MD5/SHA/CRC）
    SIGNATURE_MATCH,        // FLIRT 签名匹配
    PDB_DWARF               // 调试符号解析
}

/** 分析级别。 */
enum class AnalysisLevel {
    QUICK,    // 仅 ELF 解析，不做函数识别/xref
    STANDARD, // ELF + 函数识别（aaa）
    DEEP      // 深度分析（含 xref 全量计算）
}

/** 打开文件选项。 */
data class OpenOptions(
    val autoAnalyze: Boolean = true,
    val analysisLevel: AnalysisLevel = AnalysisLevel.STANDARD
)

/** 打开结果。封装成功/失败状态，供 [AnalysisSession] 和 MCP 工具返回。 */
sealed class OpenResult {
    data class Success(
        val handle: AnalysisHandle,
        val filePath: String,
        val engineId: String
    ) : OpenResult()

    data class Failure(
        val reason: String,
        val cause: Throwable? = null
    ) : OpenResult()
}

/** 会话句柄（包装 Long，避免裸 jlong 在调用方间滥用）。 */
@JvmInline
value class AnalysisHandle(val value: Long) {
    val isValid: Boolean get() = value != 0L
    companion object {
        val INVALID = AnalysisHandle(0L)
    }
}

/** 字符串扫描选项。 */
data class StringScanOptions(
    val minLen: Int = 4,
    val maxLen: Int = 4096,
    val scanSections: List<String> = emptyList()  // 空=全二进制扫描
)
