package com.ai.fler.core.analysis

/**
 * Dart 方法显示名工具：混淆包中 Blutter 恢复的方法名常为 `<anonymous closure>`/`<unknown>`/空，
 * 无法区分、无法按名定位。此工具在「显示层」为这类方法生成确定性名称 `sub_<vaddr hex>`
 * （与 IDA 的 sub_ 命名惯例一致），同一方法永远同名、可经 [parseSubName] 反查回 vaddr。
 * DB 中的原始 method_name 保持不变。
 */
object DartNameDisplay {

    /** Blutter 对匿名闭包方法的占位名。 */
    private const val ANONYMOUS_CLOSURE = "<anonymous closure>"

    /** 未知类/方法的占位名。 */
    private const val UNKNOWN = "<unknown>"

    /** 应被替换为 sub_ 名的占位方法名集合。 */
    private val PLACEHOLDER_METHOD_NAMES = setOf(ANONYMOUS_CLOSURE, UNKNOWN, "")

    /** 应视为「无有效类名」的占位类名集合（fullName 时省略类前缀）。 */
    private val PLACEHOLDER_CLASS_NAMES = setOf(UNKNOWN, "")

    /** 是否为需替换为 sub_ 名的占位方法名。 */
    fun isPlaceholder(methodName: String?): Boolean =
        methodName in PLACEHOLDER_METHOD_NAMES

    /** 类名是否「无有效名」（省略类前缀）。 */
    fun hasUsableClassName(className: String?): Boolean =
        className != null && className !in PLACEHOLDER_CLASS_NAMES

    /**
     * 方法显示名：占位方法名 → `sub_<vaddr hex>`；否则原样返回。
     * vaddr 缺失或为 0 时退化保留原名（不生成 sub_0）。
     */
    fun displayMethodName(methodName: String?, vaddr: Long?): String {
        if (!isPlaceholder(methodName)) return methodName.orEmpty()
        val v = vaddr ?: return methodName.orEmpty()
        if (v <= 0) return methodName.orEmpty()
        return "sub_${v.toString(16)}"
    }

    /**
     * 完整显示名 `类.方法`：方法名占位 → `sub_<vaddr>`（类名有效时 `类.sub_<vaddr>`；
     * 类名也占位则省略类前缀）。方法名有效时与原始 `类.方法` 一致。
     */
    fun displayFullName(className: String?, methodName: String?, vaddr: Long?): String {
        val mn = displayMethodName(methodName, vaddr)
        if (!hasUsableClassName(className)) return mn
        return "$className.$mn"
    }

    /**
     * 解析 `sub_<hex>`（可带 `0x` 前缀、可带 `类.` 前缀，大小写不敏感）→ vaddr。
     * 非 sub_ 形态返回 null（调用方按原方法名继续处理）。
     */
    fun parseSubName(name: String?): Long? {
        val n = name?.trim() ?: return null
        val idx = n.indexOf("sub_", ignoreCase = true)
        if (idx < 0) return null
        // 只接受独立词：sub_ 前只能是 `类.`（以 . 结尾）或开头
        if (idx > 0 && n[idx - 1] != '.') return null
        var hex = n.substring(idx + 4)
        if (hex.startsWith("0x") || hex.startsWith("0X")) hex = hex.substring(2)
        if (hex.isEmpty()) return null
        return hex.toLongOrNull(16)
    }
}