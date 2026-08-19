package com.ai.fler.core.mcp

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语义搜索 embedding 配置（SharedPreferences 持久化）。
 *
 * - [apiKey] SiliconFlow API Key（必填才可用语义搜索）
 * - [model] embedding 模型名（默认 BAAI/bge-m3，1024 维，8192 token 上限）
 * - [baseUrl] API 根地址（默认 https://api.siliconflow.cn，兼容自建代理）
 *
 * @see <a href="https://api-docs.siliconflow.cn/docs/api/embeddings-post">SiliconFlow Embeddings API</a>
 */
@Singleton
class EmbeddingConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("embedding_config", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "") ?: "")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _model = MutableStateFlow(prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL)
    val model: StateFlow<String> = _model.asStateFlow()

    private val _baseUrl = MutableStateFlow(
        prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL)?.trimEnd('/') ?: DEFAULT_BASE_URL
    )
    val baseUrl: StateFlow<String> = _baseUrl.asStateFlow()

    fun setApiKey(value: String) {
        _apiKey.value = value
        prefs.edit().putString(KEY_API_KEY, value).apply()
    }

    fun setModel(value: String) {
        val v = value.trim().ifEmpty { DEFAULT_MODEL }
        _model.value = v
        prefs.edit().putString(KEY_MODEL, v).apply()
    }

    fun setBaseUrl(value: String) {
        val v = value.trim().ifEmpty { DEFAULT_BASE_URL }.trimEnd('/')
        _baseUrl.value = v
        prefs.edit().putString(KEY_BASE_URL, v).apply()
    }

    /** 是否已配置可用（API Key 非空）。 */
    fun isConfigured(): Boolean = _apiKey.value.isNotBlank()

    companion object {
        const val DEFAULT_MODEL = "BAAI/bge-m3"
        const val DEFAULT_BASE_URL = "https://api.siliconflow.cn"
        private const val KEY_API_KEY = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_BASE_URL = "base_url"
    }
}
