package com.ai.fler.core.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SiliconFlow 兼容 embedding 客户端。
 *
 * POST {baseUrl}/v1/embeddings
 *   Authorization: Bearer <apiKey>
 *   { "model": "...", "input": ["s1", "s2", ...], "encoding_format": "float" }
 * → { "data": [ { "embedding": [..], "index": 0 }, ... ], "model": ..., "usage": ... }
 *
 * 批量条目按调用方给的上限自动分片；任一批失败即抛错（带 API 返回的错误信息）。
 *
 * @see <a href="https://api-docs.siliconflow.cn/docs/api/embeddings-post">SiliconFlow Embeddings API</a>
 */
@Singleton
class EmbeddingClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val config: EmbeddingConfig,
) {

    class EmbeddingException(message: String) : Exception(message)

    /**
     * 批量向量化一组文本（自动按 [MAX_BATCH] 分片请求，保序合并）。
     *
     * @param texts 文本列表（调用方负责截断到模型 token 上限内）
     * @return 与输入等长的向量列表
     * @throws EmbeddingException 未配置 API Key / 网络 / API 错误
     */
    suspend fun embed(texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyList()
        if (!config.isConfigured()) {
            throw EmbeddingException("未配置 embedding API Key（设置页 → MCP 服务器 → 语义搜索）")
        }
        val out = ArrayList<FloatArray>(texts.size)
        for (start in texts.indices step MAX_BATCH) {
            val chunk = texts.subList(start, minOf(start + MAX_BATCH, texts.size))
            out += embedChunk(chunk)
        }
        out
    }

    /** 单条文本向量化（查询用）。 */
    suspend fun embedQuery(text: String): FloatArray = embed(listOf(text)).first()

    private fun embedChunk(chunk: List<String>): List<FloatArray> {
        val body = JSONObject()
            .put("model", config.model.value)
            .put("input", JSONArray(chunk))
            .put("encoding_format", "float")
            .toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${config.baseUrl.value}/v1/embeddings")
            .header("Authorization", "Bearer ${config.apiKey.value}")
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw EmbeddingException("embedding API HTTP ${resp.code}: ${text.take(300)}")
            }
            val json = JSONObject(text)
            val data = json.optJSONArray("data")
                ?: throw EmbeddingException("embedding API 响应缺少 data 字段: ${text.take(200)}")
            // 按 index 归位（API 保证 index 对应输入下标，此处仍防御性排序）
            val result = arrayOfNulls<FloatArray>(chunk.size)
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val idx = item.optInt("index", i)
                if (idx < 0 || idx >= chunk.size) continue
                val arr = item.optJSONArray("embedding")
                    ?: throw EmbeddingException("embedding API 响应缺少 embedding 字段")
                val vec = FloatArray(arr.length())
                for (j in 0 until arr.length()) vec[j] = arr.getDouble(j).toFloat()
                result[idx] = vec
            }
            return result.map { it ?: throw EmbeddingException("embedding API 响应不完整（index 缺失）") }
        }
    }

    companion object {
        /** 单请求最大条目（SiliconFlow 数组输入上限内的稳妥值）。 */
        private const val MAX_BATCH = 16
    }
}
