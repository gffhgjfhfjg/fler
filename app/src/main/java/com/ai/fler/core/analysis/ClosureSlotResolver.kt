package com.ai.fler.core.analysis

import com.ai.fler.data.entity.PpEntry

/**
 * Dart 对象池「匿名闭包槽」解析器。
 *
 * Blutter pp_entries 里 type=AnonymousClosure 的槽 value 形如：
 *   (0x90f900), of [zuq] Ofa          → 闭包 vaddr=0x90f900，库 [zuq]，归属类 Ofa
 *   static (0x6a93f0), of [Qpq]        → 静态闭包，库 [Qpq]，类名缺失（回退用库名）
 *   (0x11f10dc), in [path] sma::<anonymous closure> (0x11f0f6c)  → 内联闭包，父方法 vaddr=0x11f0f6c
 *
 * 它把匿名方法 vaddr 与「归属类 + 库 + 父方法」精确关联，是给混淆包
 * sub_<vaddr> 匿名方法做语义标注的最准确数据源（89% 槽 vaddr 能命中 methods.address）。
 */
object ClosureSlotResolver {

    /** 解析后的闭包槽结构。 */
    data class ClosureSlot(
        val vmOffset: Long,
        val closureVaddr: Long,
        val isStatic: Boolean,
        val owner: String,
        val library: String?,
        val parentVaddr: Long?,
        val rawValue: String,
    ) {
        /** 语义名：`owner::<closure>`（类名缺失时用库名）。 */
        val displayName: String
            get() = "${owner.ifBlank { library.orEmpty() }}::<closure>"
    }

        private val OF_RE = Regex("""(static\s+)?\(0x([0-9a-fA-F]+)\)\s*,\s*of\s+\[([^\]]+)\]\s*(\S*)""")
    private val IN_RE = Regex(""",\s*in\s+\[([^\]]+)\]\s*([^:]+?)::(.+?)\s*\(0x([0-9a-fA-F]+)\)""")

    /** 解析单条 pp 条目为闭包槽；非 AnonymousClosure 或解析失败返回 null。
     *  Blutter 的字符串 value 在 Room 里落到了 description 列。
     *  of 形态 value=`(0x90f900), of [zuq] Ofa`：`[zuq]` 是库名，`Ofa` 才是归属类。 */
    fun parse(e: PpEntry): ClosureSlot? {
        val type = e.type?.trim().orEmpty()
        if (!type.contains("AnonymousClosure")) return null
        val value = e.description?.trim().orEmpty()
        if (value.isEmpty()) return null
        val of = OF_RE.find(value)
        if (of != null) {
            val isStatic = of.groupValues[1].contains("static")
            val vaddr = of.groupValues[2].toLongOrNull(16) ?: return null
            val library = of.groupValues[3]
            val klass = of.groupValues[4].ifBlank { null }
            return ClosureSlot(
                vmOffset = e.vmOffset,
                closureVaddr = vaddr,
                isStatic = isStatic,
                owner = klass ?: "",
                library = library,
                parentVaddr = null,
                rawValue = value,
            )
        }
        val inM = IN_RE.find(value)
        if (inM != null) {
            // in 形态也带 (0x..) 前缀（在 of 正则没匹配时才有 in）
            val vaddrMatch = Regex("""\(0x([0-9a-fA-F]+)\)""").find(value)
            val vaddr = vaddrMatch?.groupValues?.get(1)?.toLongOrNull(16) ?: return null
            val isStatic = value.startsWith("static ")
            return ClosureSlot(
                vmOffset = e.vmOffset,
                closureVaddr = vaddr,
                isStatic = isStatic,
                owner = inM.groupValues[2].trim(),
                library = inM.groupValues[1],
                parentVaddr = inM.groupValues[4].toLongOrNull(16),
                rawValue = value,
            )
        }
        return null
    }

    /** 从 pp_entries 全量里解析所有闭包槽。 */
    fun parseAll(entries: List<PpEntry>): List<ClosureSlot> =
        entries.mapNotNull { parse(it) }

    /**
     * 建立「闭包 vaddr → 显示名」映射（去重：同 vaddr 多槽时取第一个）。
     * 调用方应只传入已按 analysisId 过滤的 pp_entries。
     */
    fun buildVaddrMap(slots: List<ClosureSlot>): Map<Long, ClosureSlot> =
        slots.associateBy { it.closureVaddr }
}