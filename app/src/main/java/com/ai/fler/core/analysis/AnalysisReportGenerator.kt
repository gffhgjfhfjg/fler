package com.ai.fler.core.analysis

import com.ai.fler.core.log.AppLogger
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.data.entity.PpEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分析报告生成器：把一次 Blutter 分析快照产出为 Markdown 报告。
 *
 * 三大板块：
 * 1. 类统计 —— 总量 / 抽象与 final 占比 / 方法数 Top 类 / 类名前缀（库）分布
 * 2. 可疑字符串 —— 按敏感模式（URL/密钥/鉴权/支付会员/API）扫描字符串常量
 * 3. 加解密函数定位 —— 按命名特征（aes/des/rc4/md5/sha/hmac/rsa/sign/cipher…）
 *    定位方法与其虚拟地址，供后续 analyze_method/disassemble_range 深挖
 *
 * 数据全部来自 Room（Blutter 导入结果），无引擎依赖，可在任意时刻离线生成。
 */
@Singleton
class AnalysisReportGenerator @Inject constructor(
    private val analysisDao: AnalysisDao,
    private val projectDao: ProjectDao,
    private val dartClassDao: DartClassDao,
    private val dartMethodDao: DartMethodDao,
    private val ppEntryDao: PpEntryDao,
    private val appLogger: AppLogger,
) {

    /** 敏感字符串模式（不区分大小写，命中任一关键词即列入）。 */
    private val suspiciousPatterns = listOf(
        // 网络/接口
        "http://", "https://", "ws://", "wss://", "://api", "/api/", "gateway",
        // 鉴权/密钥
        "token", "secret", "apikey", "api_key", "password", "passwd", "pwd",
        "license", "auth", "session", "cookie", "cert", "credential",
        // 支付/会员/商业化
        "vip", "premium", "pay", "price", "order", "subscribe", "trial",
        "expire", "unlock", "purchase", "charge", "billing", "refund",
        // 调试/后门痕迹
        "debug", "test_mode", "backdoor", "root", "hook", "frida", "xposed",
    )

    /** 加解密/签名相关命名特征（匹配类名或方法名，不区分大小写）。 */
    private val cryptoPatterns = listOf(
        "aes", "des3", "3des", "rc4", "rsa", "md5", "sha1", "sha256", "sha512",
        "hmac", "cipher", "crypt", "encrypt", "decrypt", "sign", "base64",
        "digest", "hash", "xor", "pbkdf", "pkcs", "iv_", "salt",
    )

    /**
     * 生成 Markdown 报告。
     *
     * @param analysisId 分析记录 ID
     * @param maxSuspicious 可疑字符串条目上限（默认 200）
     * @param maxCrypto 加解密函数条目上限（默认 100）
     * @return 报告全文（Markdown）；分析不存在返回 null
     */
    suspend fun generate(
        analysisId: Long,
        maxSuspicious: Int = 200,
        maxCrypto: Int = 100,
    ): String? = withContext(Dispatchers.IO) {
        val analysis = analysisDao.getById(analysisId) ?: return@withContext null
        val project = analysis.projectId.let { projectDao.getById(it) }
        val classes = dartClassDao.getByAnalysisIdList(analysisId)
        val strings = loadStrings(analysisId)

        val sb = StringBuilder()
        sb.append("# fler 逆向分析报告\n\n")
        sb.append("> 生成时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}\n\n")

        // ========== 概览 ==========
        sb.append("## 1. 分析概览\n\n")
        sb.append("| 项目 | 值 |\n|---|---|\n")
        sb.append("| 应用名 | ${project?.name ?: "N/A"} |\n")
        sb.append("| 包名 | ${project?.packageName ?: "N/A"} |\n")
        sb.append("| 版本 | ${project?.apkVersion ?: "N/A"} |\n")
        sb.append("| Dart 版本 | ${project?.dartVersion ?: "N/A"} |\n")
        sb.append("| libapp.so | `${analysis.libappPath ?: "N/A"}` |\n")
        sb.append("| libflutter.so | `${analysis.libflutterPath ?: "N/A"}` |\n")
        sb.append("| 类数量 | ${analysis.classesCount} |\n")
        sb.append("| 方法数量 | ${analysis.methodsCount} |\n")
        sb.append("| 对象池条目 | ${analysis.ppEntriesCount} |\n")
        sb.append("| 字符串常量 | ${strings.size} |\n\n")

        // ========== 类统计 ==========
        appendClassStats(sb, classes)

        // ========== 可疑字符串 ==========
        appendSuspiciousStrings(sb, strings, maxSuspicious)

        // ========== 加解密函数定位 ==========
        appendCryptoFunctions(sb, analysisId, maxCrypto)

        sb.append("\n---\n\n*由 fler 自动生成：可疑字符串与加解密定位基于模式匹配，需人工复核。*\n")
        appLogger.info(TAG, "分析报告已生成: analysis=$analysisId, ${sb.length} chars")
        sb.toString()
    }

    // ========== 类统计 ==========

    private fun appendClassStats(sb: StringBuilder, classes: List<com.ai.fler.data.entity.DartClass>) {
        sb.append("## 2. 类统计\n\n")
        if (classes.isEmpty()) {
            sb.append("无类数据（非 Flutter 应用或分析未完成）。\n\n")
            return
        }
        val totalMethods = classes.sumOf { it.methodCount }
        val abstractCount = classes.count { it.isAbstract }
        val finalCount = classes.count { it.isFinal }

        sb.append("- 总类数：**${classes.size}**，总方法数：**$totalMethods**，平均每类 %.1f 个方法\n".format(
            if (classes.isEmpty()) 0.0 else totalMethods.toDouble() / classes.size
        ))
        sb.append("- 抽象类：$abstractCount（%.1f%%），final 类：$finalCount（%.1f%%）\n\n".format(
            abstractCount * 100.0 / classes.size, finalCount * 100.0 / classes.size
        ))

        // 方法数 Top 20 类
        sb.append("### 2.1 方法数 Top 20 类\n\n")
        sb.append("| # | 类名 | 父类 | 方法数 | 修饰 |\n|---|---|---|---|---|\n")
        classes.sortedByDescending { it.methodCount }.take(20).forEachIndexed { i, c ->
            val mods = buildList {
                if (c.isAbstract) add("abstract")
                if (c.isFinal) add("final")
            }.joinToString(",").ifEmpty { "-" }
            sb.append("| ${i + 1} | `${c.className}` | ${c.superClass ?: "-"} | ${c.methodCount} | $mods |\n")
        }
        sb.append("\n")

        // 类名前缀（库分布）
        sb.append("### 2.2 类名前缀分布 Top 15（库/组件分布）\n\n")
        sb.append("| 前缀 | 类数 | 方法数 |\n|---|---|---|\n")
        classes.groupBy { classPrefix(it.className) }
            .map { (prefix, group) -> Triple(prefix, group.size, group.sumOf { it.methodCount }) }
            .sortedByDescending { it.second }
            .take(15)
            .forEach { (prefix, cnt, methods) -> sb.append("| $prefix | $cnt | $methods |\n") }
        sb.append("\n")
    }

    /** 取类名首段（package/库边界）作为前缀：`com.foo.Bar` → `com`；`Foo` → `Foo`。 */
    private fun classPrefix(className: String): String {
        val dot = className.indexOf('.')
        return if (dot > 0) className.substring(0, dot) else className
    }

    // ========== 可疑字符串 ==========

    private fun appendSuspiciousStrings(sb: StringBuilder, strings: List<PpEntry>, max: Int) {
        sb.append("## 3. 可疑字符串\n\n")
        if (strings.isEmpty()) {
            sb.append("无字符串常量数据（混淆包可能无 type='String' 条目）。\n\n")
            return
        }
        val hits = strings
            .mapNotNull { e -> e.description?.let { e to it } }
            .filter { (_, desc) ->
                val lower = desc.lowercase()
                suspiciousPatterns.any { lower.contains(it) }
            }

        // 按类别分组展示
        val categories = linkedMapOf(
            "URL / 接口地址" to listOf("http://", "https://", "ws://", "wss://", "://api", "/api/", "gateway"),
            "密钥 / 鉴权" to listOf("token", "secret", "apikey", "api_key", "password", "passwd", "pwd", "license", "auth", "session", "cookie", "cert", "credential"),
            "支付 / 会员 / 商业化" to listOf("vip", "premium", "pay", "price", "order", "subscribe", "trial", "expire", "unlock", "purchase", "charge", "billing", "refund"),
            "调试 / 逆向对抗痕迹" to listOf("debug", "test_mode", "backdoor", "root", "hook", "frida", "xposed"),
        )

        sb.append("共扫描 ${strings.size} 条字符串常量，命中敏感模式 ${hits.size} 条（上限 $max）。\n\n")
        var emitted = 0
        for ((catName, patterns) in categories) {
            val catHits = hits.filter { (_, desc) ->
                val lower = desc.lowercase()
                patterns.any { lower.contains(it) }
            }.take((max - emitted).coerceAtLeast(0))
            if (catHits.isEmpty()) continue
            if (emitted >= max) break
            sb.append("### 3.${categories.keys.indexOf(catName) + 1} $catName（${catHits.size} 条）\n\n")
            sb.append("| ppOffset | 内容 |\n|---|---|\n")
            for ((e, desc) in catHits) {
                val escaped = desc.replace("|", "\\|").replace("\n", " ").let { if (it.length > 120) it.take(120) + "…" else it }
                sb.append("| 0x${e.vmOffset.toString(16)} | `$escaped` |\n")
                emitted++
            }
            sb.append("\n")
        }
        if (emitted == 0) {
            sb.append("未命中任何敏感模式。\n\n")
        }
    }

    // ========== 加解密函数定位 ==========

    private suspend fun appendCryptoFunctions(sb: StringBuilder, analysisId: Long, max: Int) {
        sb.append("## 4. 加解密函数定位\n\n")
        sb.append("按命名特征（aes/des/rc4/rsa/md5/sha/hmac/sign/cipher/crypt/base64…）扫描类名与方法名。\n\n")

        // 用已有的 SQL LIKE 搜索逐个模式查（复用索引与下推，避免全量载入方法表）
        val seen = LinkedHashMap<Long, DartMethodDao.MethodWithClass>()
        for (pattern in cryptoPatterns) {
            val rows = dartMethodDao.searchMethodsWithClass(analysisId, pattern, null, 100, 0)
            rows.forEach { if (seen.size < max * 2) seen.putIfAbsent(it.method.id, it) }
            if (seen.size >= max) break
        }
        if (seen.isEmpty()) {
            sb.append("未定位到加解密相关命名的方法。\n\n")
            return
        }

        val matched = seen.values.take(max)
        sb.append("命中 ${seen.size} 个方法（展示前 ${matched.size} 个，混淆包命名可能失效需结合字符串/调用图分析）。\n\n")
        sb.append("| # | 类.方法 | vaddr | 大小 |\n|---|---|---|---|\n")
        matched.forEachIndexed { i, m ->
            val display = DartNameDisplay.displayMethodName(m.method.methodName, m.method.functionOffset)
            sb.append("| ${i + 1} | `${m._className}.$display` | 0x${(m.method.functionOffset ?: 0).toString(16)} | ${m.method.functionSize ?: 0} |\n")
        }
        sb.append("\n> 后续动作：`get_method` 看源码 → `string_xrefs` 查关联字符串 → `analyze_method`/`disassemble_range` 反汇编验证 → `method_cfg` 看控制流。\n\n")
    }

    // ========== 字符串加载（与 list_strings 同回退策略） ==========

    private suspend fun loadStrings(analysisId: Long): List<PpEntry> {
        val typed = ppEntryDao.countStringsByAnalysisId(analysisId)
        if (typed > 0) {
            // 分页拉取全量（SQL 下推，单页 1000）
            val out = mutableListOf<PpEntry>()
            var offset = 0
            while (true) {
                val page = ppEntryDao.getStringsByAnalysisIdPaged(analysisId, 1000, offset)
                out += page
                if (page.size < 1000) break
                offset += 1000
            }
            return out
        }
        // 混淆包回退：description/type 含引号字符串的槽
        return ppEntryDao.getByAnalysisIdList(analysisId).filter {
            it.description?.contains('"') == true || it.type?.contains('"') == true
        }
    }

    companion object {
        private const val TAG = "ReportGen"
    }
}
