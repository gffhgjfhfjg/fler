package com.ai.fler.core.analysis

import androidx.compose.runtime.Immutable

/**
 * ELF 符号信息（Engine 抽象层数据模型）。
 *
 * 合并了旧版 [com.ai.fler.core.jni.ElfSymbol] 与 Rizin librz_bin 的符号字段
 * （demangle、bind 字符串形式等）。
 */
@Immutable
data class SymbolInfo(
    val name: String,
    val demangledName: String? = null,   // Rizin 专有：demangle 后的可读名称
    val address: Long,
    val size: Long,
    val type: SymbolType = SymbolType.NOTYPE,
    val bind: SymbolBind = SymbolBind.LOCAL,
    val shndx: Int = 0,                  // 兼容旧 ElfSymbol.shndx
    val sectionName: String = "",        // Rizin 专有：所在节区名
    val paddr: Long = address            // 文件偏移（跳转反汇编/十六进制用；默认回退 vaddr）
) {
    companion object {
        @JvmStatic
        fun fromElfSymbol(s: com.ai.fler.core.jni.ElfSymbol): SymbolInfo {
            return SymbolInfo(
                name = s.name,
                address = s.address,
                size = s.size,
                type = SymbolType.fromByte(s.type),
                bind = SymbolBind.fromByte(s.binding),
                shndx = s.shndx.toInt()
            )
        }
    }
}

/** 符号类型（与 ELF STT_* 对应，兼容旧 ElfSymbol.type）。 */
enum class SymbolType(val code: Byte) {
    NOTYPE(0), OBJECT(1), FUNC(2), SECTION(3),
    FILE(4), COMMON(5), TLS(6), UNKNOWN(-1);
    companion object {
        fun fromByte(b: Byte): SymbolType = entries.firstOrNull { it.code == b } ?: UNKNOWN
    }
}

/** 符号绑定类型（与 ELF STB_* 对应，兼容旧 ElfSymbol.binding）。 */
enum class SymbolBind(val code: Byte) {
    LOCAL(0), GLOBAL(1), WEAK(2), UNKNOWN(-1);
    companion object {
        fun fromByte(b: Byte): SymbolBind = entries.firstOrNull { it.code == b } ?: UNKNOWN
    }
}

/** 导入符号信息（Rizin iij）。 */
@Immutable
data class ImportInfo(
    val name: String,
    val type: String = "",   // FUNC / OBJECT
    val address: Long = 0,
    val bind: String = "GLOBAL"
)

/** 重定位信息（Rizin irj）。 */
@Immutable
data class RelocInfo(
    val name: String = "",
    val address: Long = 0,
    val type: String = ""    // R_AARCH64_ABS64 等
)

/** 字符串扫描结果（Rizin izzj）。 */
@Immutable
data class StringInfo(
    val string: String,
    val address: Long,           // 虚拟地址
    val paddr: Long = 0,         // 文件偏移
    val size: Int = 0,
    val section: String = ""     // 所在节区名，如 ".rodata"
)

/** 文件基本信息（Rizin ij bin 部分）。 */
@Immutable
data class FileInfo(
    val arch: String = "",      // arm / x86 / mips ...
    val bits: Int = 0,          // 32 / 64
    val endian: String = "",    // little / big
    val machine: String = "",   // AArch64 / ...
    val classType: String = "", // ELFCLASS32 / ELFCLASS64
    val os: String = "",        // linux / darwin / windows
    val canary: Boolean = false,    // 栈保护
    val nx: Boolean = false,        // 不可执行内存
    val pie: Boolean = false,       // PIE
    val relro: String = "none",     // partial / full / none
    val stripped: Boolean = false,
    val fileSize: Long = 0L         // 文件原始字节数
)
