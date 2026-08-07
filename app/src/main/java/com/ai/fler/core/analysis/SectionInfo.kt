package com.ai.fler.core.analysis

import androidx.compose.runtime.Immutable

/**
 * ELF 节区信息（Engine 抽象层数据模型）。
 *
 * 与 [com.ai.fler.core.jni.ElfSection] 等价，但定义在 Engine 抽象层，
 * 避免依赖具体的 JNI 数据类。RizinEngine 把 librz_bin 的 RzBinSection
 * 转换为本类；SelfAnalysisEngine 把旧 ElfSection 包装为本类。
 */
@Immutable
data class SectionInfo(
    val name: String,
    val type: String = "",          // Rizin: prog_bits/symtab/strtab/dynsym/nobits/rela 等
    val typeInt: Int = 0,           // 兼容旧 ElfSection.type
    val offset: Long,
    val size: Long,
    val address: Long,              // 虚拟地址（vaddr）
    val paddr: Long = 0,            // 文件物理地址（Rizin 专有）
    val flags: Long = 0,            // 兼容旧 ElfSection.flags
    val perm: String = ""           // 权限字符串，如 "r-x" / "rw-"（Rizin 专有）
) {
    companion object {
        private const val SHF_WRITE = 0x1L
        private const val SHF_ALLOC = 0x2L
        private const val SHF_EXECINSTR = 0x4L

        /** 把旧版 [com.ai.fler.core.jni.ElfSection] 转换为抽象层 SectionInfo。 */
        @JvmStatic
        fun fromElfSection(s: com.ai.fler.core.jni.ElfSection): SectionInfo {
            val sb = StringBuilder(3)
            // 读位：通常 SHF_ALLOC 的节区对应"在内存中存在"，因此我们按惯例视为 r 的条件之一。
            val hasAlloc = (s.flags and SHF_ALLOC) != 0L
            if (hasAlloc) sb.append('r') else sb.append('-')
            if ((s.flags and SHF_WRITE) != 0L) sb.append('w') else sb.append('-')
            if ((s.flags and SHF_EXECINSTR) != 0L) sb.append('x') else sb.append('-')
            return SectionInfo(
                name = s.name,
                typeInt = s.type,
                offset = s.offset,
                size = s.size,
                address = s.address,
                paddr = s.offset,
                flags = s.flags,
                perm = sb.toString()
            )
        }
    }
}
