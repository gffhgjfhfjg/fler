package com.ai.fler.core.mcp

import android.content.Context
import com.ai.fler.core.analysis.DartNameDisplay
import com.ai.fler.core.log.AppLogger
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.entity.PpEntry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.zip.CRC32
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语义搜索本地向量索引（端侧存储 + 外部 embedding API）。
 *
 * 对一次分析的三类文本建余弦相似度索引：
 * - `string`：字符串常量（pp_entries，含混淆包回退策略）
 * - `method`：方法签名（`ClassName.methodName`）
 * - `code`  ：方法伪代码（Blutter 恢复的 src_code，截断）
 *
 * 存储布局（filesDir/semantic/&lt;analysisId&gt;/）：
 * - `docs.jsonl`  每行一个文档（确定性顺序：strings → methods → code），含类型/引用元数据
 * - `meta.json`   {model, dim, total, done, docsCrc}（docsCrc 不变则支持断点续建）
 * - `vectors.bin` done × dim 个 float（大端序，顺序与 docs.jsonl 行号一致）
 *
 * 构建分批调用 embedding API（每批 [EmbeddingClient] 内部分片），失败保留进度可续建；
 * 检索为流式暴力余弦（逐向量读盘打分），数万文档量级端侧毫秒~百毫秒级。
 */
@Singleton
class SemanticIndexManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val analysisDao: AnalysisDao,
    private val dartClassDao: DartClassDao,
    private val ppEntryDao: PpEntryDao,
    private val dartMethodDao: DartMethodDao,
    private val embeddingClient: EmbeddingClient,
    private val embeddingConfig: EmbeddingConfig,
    private val appLogger: AppLogger,
) {
    /** 索引文档（docs.jsonl 行）。 */
    class IndexDoc(
        val type: String,          // string / method / code
        val text: String,          // 参与 embedding 的文本（已截断）
        val className: String,
        val methodName: String,
        val vaddr: Long,           // dart_methods.function_offset（string/code 类型为 0）
        val ppOffset: Long,        // pp_entries.vm_offset（method/code 类型为 0）
        val description: String,   // string 原文 / 其他类型留空
    )

    /** 索引状态。 */
    data class IndexStatus(
        val exists: Boolean,
        val model: String = "",
        val dim: Int = 0,
        val total: Int = 0,
        val done: Int = 0,
        val complete: Boolean = false,
    )

    /** 检索命中。 */
    data class SearchHit(
        val doc: IndexDoc,
        val score: Float,
    )

    private val rootDir: File get() = File(context.filesDir, "semantic")
    private fun dirOf(analysisId: Long) = File(rootDir, analysisId.toString())

    /** 防并发构建（同进程内）。 */
    private val buildMutex = Mutex()

    // ========== 状态 ==========

    /** 读取某分析的索引状态；不存在返回 exists=false。 */
    fun status(analysisId: Long): IndexStatus {
        val dir = dirOf(analysisId)
        val meta = File(dir, "meta.json")
        if (!meta.isFile) return IndexStatus(exists = false)
        return try {
            val m = JSONObject(meta.readText())
            val total = m.optInt("total", 0)
            val done = m.optInt("done", 0)
            IndexStatus(
                exists = true,
                model = m.optString("model", ""),
                dim = m.optInt("dim", 0),
                total = total,
                done = done,
                complete = total > 0 && done >= total,
            )
        } catch (_: Exception) {
            IndexStatus(exists = false)
        }
    }

    /** 删除某分析的索引（分析删除时清理）。 */
    fun deleteIndex(analysisId: Long) {
        dirOf(analysisId).deleteRecursively()
    }

    // ========== 构建 ==========

    /**
     * 构建（或续建）某分析的语义索引。
     *
     * 文档顺序确定性（strings 按 vm_offset → methods 按 id → code 按 id），
     * docs.jsonl 的 CRC 与 meta 一致时从 done 处续建；模型变更自动重建。
     *
     * @param maxNewDocs 本次调用最多新嵌入的文档数（限流/超时保护，默认 2000）
     * @return 最新状态
     */
    suspend fun build(analysisId: Long, maxNewDocs: Int = 2000): IndexStatus =
        buildMutex.withLock {
            withContext(Dispatchers.IO) {
                analysisDao.getById(analysisId)
                    ?: return@withContext IndexStatus(exists = false)

                if (!embeddingConfig.isConfigured()) {
                    throw EmbeddingClient.EmbeddingException(
                        "未配置 embedding API Key（设置页 → MCP 服务器 → 语义搜索），或用 semantic_index_status 查看状态"
                    )
                }

                val dir = dirOf(analysisId).apply { mkdirs() }
                val docsFile = File(dir, "docs.jsonl")
                val metaFile = File(dir, "meta.json")
                val vecFile = File(dir, "vectors.bin")

                // 1. 采集文档（docs.jsonl 不存在或行数对不上时重建）
                val docs = collectDocs(analysisId)
                var meta = readMeta(metaFile)
                val docsCrc = crcOf(docs)
                val needRebuild = meta == null || meta.optLong("docsCrc") != docsCrc ||
                    meta.optString("model") != embeddingConfig.model.value
                if (needRebuild) {
                    writeDocs(docsFile, docs)
                    vecFile.delete()
                    meta = writeMeta(metaFile, embeddingConfig.model.value, 0, 0, docs.size, docsCrc)
                }
                val total = docs.size
                var done = meta!!.optInt("done", 0)
                var dim = meta.optInt("dim", 0)
                if (total == 0 || done >= total) {
                    return@withContext status(analysisId)
                }

                // 2. 分批 embedding 续写 vectors.bin
                val budget = maxNewDocs.coerceAtLeast(1)
                var embeddedThisCall = 0
                DataOutputStream(FileOutputStream(vecFile, true).buffered()).use { out ->
                    while (done < total && embeddedThisCall < budget) {
                        val batchEnd = minOf(done + BATCH, total, done + (budget - embeddedThisCall))
                        val texts = docs.subList(done, batchEnd).map { it.text }
                        val vectors = try {
                            embeddingClient.embed(texts)
                        } catch (e: Exception) {
                            // 中断保留进度（done 已写入 meta 的部分仍可检索）
                            appLogger.error(TAG, "索引构建中断于 $done/$total: ${e.message}")
                            throw e
                        }
                        for (v in vectors) {
                            if (dim == 0) dim = v.size
                            require(v.size == dim) { "embedding 维度不一致（${v.size} != $dim），可能更换了模型，请重建索引" }
                            for (f in v) out.writeFloat(f)
                        }
                        done = batchEnd
                        embeddedThisCall += vectors.size
                        writeMeta(metaFile, embeddingConfig.model.value, dim, done, total, docsCrc)
                    }
                }
                appLogger.info(TAG, "语义索引 analysis=$analysisId: $done/$total (dim=$dim)")
                status(analysisId)
            }
        }

    // ========== 检索 ==========

    /**
     * 语义检索：query 向量化后与已建索引做余弦相似度，返回 Top-K。
     *
     * @param types 过滤文档类型（string/method/code 子集；空 = 全部）
     */
    suspend fun search(
        analysisId: Long,
        query: String,
        topK: Int = 10,
        types: Set<String> = emptySet(),
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        val st = status(analysisId)
        if (!st.exists || st.done == 0) {
            throw EmbeddingClient.EmbeddingException(
                "分析 $analysisId 无可用语义索引（done=0）。请先调用 semantic_index_build(analysisId=$analysisId)"
            )
        }
        val dir = dirOf(analysisId)
        val docs = readDocs(File(dir, "docs.jsonl"), st.done)
        val queryVec = embeddingClient.embedQuery(query)

        // Top-K 堆（小顶堆按 score，容量 topK）
        val heap = java.util.PriorityQueue<Pair<Float, Int>>(topK, compareBy { it.first })
        DataInputStream(FileInputStream(File(dir, "vectors.bin")).buffered()).use { input ->
            val buf = FloatArray(st.dim)
            var idx = 0
            while (idx < st.done) {
                var ok = true
                for (j in 0 until st.dim) {
                    val f = input.readFloat()
                    if (f.isNaN()) { ok = false; break }
                    buf[j] = f
                }
                if (!ok) break
                val doc = docs.getOrNull(idx)
                if (doc != null && (types.isEmpty() || doc.type in types)) {
                    val score = cosine(queryVec, buf)
                    if (heap.size < topK) heap.add(score to idx)
                    else if (heap.peek().first < score) {
                        heap.poll(); heap.add(score to idx)
                    }
                }
                idx++
            }
        }
        heap.toList().sortedByDescending { it.first }
            .mapNotNull { (score, idx) -> docs.getOrNull(idx)?.let { SearchHit(it, score) } }
    }

    // ========== 文档采集 ==========

    /** 采集三类文档（确定性顺序）。上限见各 [MAX_*] 常量。 */
    private suspend fun collectDocs(analysisId: Long): List<IndexDoc> {
        val docs = mutableListOf<IndexDoc>()

        // 1) 字符串常量（含混淆包回退：无 type='String' 时取 description 带引号的槽）
        val typedCount = ppEntryDao.countStringsByAnalysisId(analysisId)
        val strings: List<PpEntry> = if (typedCount > 0) {
            val out = mutableListOf<PpEntry>()
            var offset = 0
            while (out.size < MAX_STRINGS) {
                val page = ppEntryDao.getStringsByAnalysisIdPaged(analysisId, 1000, offset)
                out += page
                if (page.size < 1000) break
                offset += 1000
            }
            out
        } else {
            ppEntryDao.getByAnalysisIdList(analysisId).filter {
                it.description?.contains('"') == true || it.type?.contains('"') == true
            }
        }
        strings.take(MAX_STRINGS).forEach { e ->
            val desc = e.description.orEmpty()
            if (desc.isBlank()) return@forEach
            docs += IndexDoc(
                type = "string",
                text = desc.take(300),
                className = "",
                methodName = "",
                vaddr = 0,
                ppOffset = e.vmOffset,
                description = desc,
            )
        }

        // 2) 方法签名 + 3) 伪代码（同一轮分页拉取，避免两遍全表扫描）
        val methods = dartMethodDao.searchMethodsWithClass(analysisId, "", null, Int.MAX_VALUE, 0)
        val methodsCapped = methods.take(MAX_METHODS + MAX_CODE_DOCS)
        var methodDocs = 0
        var codeDocs = 0
        for (m in methodsCapped) {
            val display = DartNameDisplay.displayMethodName(m.method.methodName, m.method.functionOffset)
            val fqName = "${m._className}.$display"
            if (methodDocs < MAX_METHODS) {
                docs += IndexDoc(
                    type = "method",
                    text = fqName.take(200),
                    className = m._className,
                    methodName = display,
                    vaddr = m.method.functionOffset ?: 0,
                    ppOffset = 0,
                    description = "",
                )
                methodDocs++
            }
            val src = m.method.srcCode
            if (codeDocs < MAX_CODE_DOCS && !src.isNullOrBlank()) {
                docs += IndexDoc(
                    type = "code",
                    text = "$fqName\n${src.take(1500)}",
                    className = m._className,
                    methodName = display,
                    vaddr = m.method.functionOffset ?: 0,
                    ppOffset = 0,
                    description = "",
                )
                codeDocs++
            }
        }
        return docs
    }

    // ========== 序列化 ==========

    private fun docToJson(d: IndexDoc): JSONObject = JSONObject()
        .put("type", d.type)
        .put("text", d.text)
        .put("className", d.className)
        .put("methodName", d.methodName)
        .put("vaddr", d.vaddr)
        .put("ppOffset", d.ppOffset)
        .put("description", d.description.take(2000))

    private fun jsonToDoc(o: JSONObject) = IndexDoc(
        type = o.optString("type"),
        text = o.optString("text"),
        className = o.optString("className"),
        methodName = o.optString("methodName"),
        vaddr = o.optLong("vaddr", 0),
        ppOffset = o.optLong("ppOffset", 0),
        description = o.optString("description"),
    )

    private fun writeDocs(file: File, docs: List<IndexDoc>) {
        BufferedWriter(OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8)).use { w ->
            docs.forEach { w.write(docToJson(it).toString()); w.write("\n") }
        }
    }

    private fun readDocs(file: File, limit: Int): List<IndexDoc> {
        val out = ArrayList<IndexDoc>(limit)
        BufferedReader(InputStreamReader(FileInputStream(file), Charsets.UTF_8)).use { r ->
            var line = r.readLine()
            while (line != null && out.size < limit) {
                if (line.isNotBlank()) {
                    runCatching { out += jsonToDoc(JSONObject(line)) }
                }
                line = r.readLine()
            }
        }
        return out
    }

    private fun readMeta(file: File): JSONObject? =
        runCatching { if (file.isFile) JSONObject(file.readText()) else null }.getOrNull()

    private fun writeMeta(file: File, model: String, dim: Int, done: Int, total: Int, crc: Long): JSONObject =
        JSONObject()
            .put("model", model)
            .put("dim", dim)
            .put("done", done)
            .put("total", total)
            .put("docsCrc", crc)
            .also { file.writeText(it.toString()) }

    private fun crcOf(docs: List<IndexDoc>): Long {
        val crc = CRC32()
        docs.forEach { crc.update((it.type + "|" + it.text).toByteArray(Charsets.UTF_8)) }
        return crc.value
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]
        }
        if (na == 0f || nb == 0f) return 0f
        return dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))
    }

    companion object {
        private const val TAG = "SemanticIndex"

        /** 单次 embedding 请求的文档批大小。 */
        private const val BATCH = 16

        /** 各类文档上限（内存/请求量保护）。 */
        private const val MAX_STRINGS = 8000
        private const val MAX_METHODS = 12000
        private const val MAX_CODE_DOCS = 8000
    }
}
