package com.ai.fler.core.analysis

import com.ai.fler.data.entity.PpEntry

/**
 * Dart 对象池「字段槽」解析器（反混淆补强）。
 *
 * Blutter 的 pp_entries 里 type 列除 String 外还有 Field/Closure/AnonymousClosure/
 * Function/Type/TypeArguments/IMM 等结构化对象。其中 Field 槽形如：
 *   type:        [pp+0x91e78] Field <Ofa._bvf@401245603>
 *   description: late (offset: 0x120)
 *
 * 它直接给出「字段名（_bvf）+ owner 类（Ofa）+ 对象内偏移（0x120）+ 修饰符（late/static/final）」，
 * 是恢复混淆类字段面（如 is_premium/premiumUser/premiumExpiresIn）最准确的数据源——
 * 比「方法引用字符串」推断更直接。本类负责从 pp_entries 全量里筛出 Field 槽并解析结构。
 */
object DartFieldSlots {

    /** 解析后的字段槽结构。 */
    data class FieldSlot(
        val vmOffset: Long,
        val owner: String,
        val ownerPlain: String,
        val fieldName: String,
        val isStatic: Boolean,
        val isFinal: Boolean,
        val isLate: Boolean,
        val offset: Long?,
        val description: String?,
        val rawType: String,
    ) {
        /** 展示名：`owner.fieldName`，如 `Ofa._bvf`（去 @hash 后缀）。 */
        val displayName: String
            get() = "$ownerPlain.$fieldName"

        /** 修饰符摘要：static / late final / late 等。 */
        val modifierText: String
            get() = buildString {
                if (isStatic) append("static ")
                if (isFinal) append("final ")
                if (isLate) append("late")
                if (isBlank()) append("plain")
            }.trim()
    }

    /** 从 pp_entries 全量里筛出所有 Field 槽并解析（调用方负责过滤 analysisId）。 */
    fun parseAll(entries: List<PpEntry>): List<FieldSlot> =
        entries.mapNotNull { parse(it) }

    /**
     * 解析单条 pp 条目为字段槽；非 Field 槽返回 null。
     * 兼容两种 type 形态：
     *   1) type 整列就是 Blutter 的可读描述：`[pp+0x91e78] Field <Ofa._bvf@401245603>`
     *   2) type 只含 `Field <...>`（无 [pp+..] 前缀）
     */
    fun parse(e: PpEntry): FieldSlot? {
        val type = e.type?.trim().orEmpty()
        if (!type.contains("Field <")) return null
        val open = type.indexOf("Field <")
        val close = type.indexOf('>', open)
        if (close < 0) return null
        val inner = type.substring(open + "Field <".length, close).trim()
        // inner 形如 Owner.field@hash 或 Owner@hash.field@hash 或 Owner.field
        val dot = inner.lastIndexOf('.')
        if (dot < 0) return null
        val owner = inner.substring(0, dot).trim()
        var fieldName = inner.substring(dot + 1).trim()
        // 去掉字段名的 @hash 后缀（如 `_bvf@401245603` → `_bvf`）
        val at = fieldName.indexOf('@')
        if (at >= 0) fieldName = fieldName.substring(0, at)
        if (fieldName.isEmpty()) return null
        // owner 的 @hash 后缀（如 `_FV@599019562` → `_FV`）
        val ownerAt = owner.indexOf('@')
        val ownerPlain = if (ownerAt >= 0) owner.substring(0, ownerAt) else owner
        // 描述解析修饰符 + 偏移
        val desc = e.description.orEmpty()
        val isStatic = desc.contains("static")
        val isFinal = desc.contains("final")
        val isLate = desc.contains("late")
        val offset = Regex("offset:\\s*(0x[0-9a-fA-F]+|\\d+)")
            .find(desc)?.groupValues?.get(1)?.let { parseOffset(it) }
        return FieldSlot(
            vmOffset = e.vmOffset,
            owner = owner,
            ownerPlain = ownerPlain,
            fieldName = fieldName,
            isStatic = isStatic,
            isFinal = isFinal,
            isLate = isLate,
            offset = offset,
            description = desc,
            rawType = type,
        )
    }

    private fun parseOffset(s: String): Long? =
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) s.substring(2).toLong(16)
            else s.toLong()
        } catch (_: NumberFormatException) {
            null
        }

    /** owner 是否匹配目标类名（忽略大小写 + 忽略 @hash 后缀）。 */
    fun ownerMatches(field: FieldSlot, className: String): Boolean =
        field.ownerPlain.equals(className, ignoreCase = true)
}