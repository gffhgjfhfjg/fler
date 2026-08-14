package com.ai.fler.core.service

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 工作目录配置（App 级，非 MCP 专属）。
 *
 * 用户通过 SAF 选定一个可读写的目录作为 fler 的统一产物输出位置：
 * - 补丁后的 so（export_patched_so 工具、SO 编辑器导出）
 * - 补丁 .patch 文件
 * - MCP 服务器 `GET /export` 的下载根（[McpHttpServer] 每请求动态解析）
 *
 * 未设置时各消费方回退到 App 缓存（cacheDir/so_export 等）。
 *
 * 首次读取时把旧版 McpConfig 的 `export_tree_uri`（mcp_server prefs）迁移过来，
 * 老用户升级后不丢已选目录。
 */
@Singleton
class WorkDirectory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _treeUri = MutableStateFlow(prefs.getString(KEY_TREE_URI, "") ?: "")

    /** 用户选定的工作目录 SAF tree URI；空 = 未设置。 */
    val treeUri: StateFlow<String> = _treeUri.asStateFlow()

    init {
        // 迁移旧配置：新 prefs 未设置时，读取旧 McpConfig 的 export_tree_uri（mcp_server prefs）
        if (_treeUri.value.isBlank()) {
            val legacy = context.getSharedPreferences(
                LEGACY_PREFS_NAME, Context.MODE_PRIVATE
            ).getString(LEGACY_KEY_EXPORT_TREE_URI, "")
            if (!legacy.isNullOrBlank()) {
                _treeUri.value = legacy
                prefs.edit().putString(KEY_TREE_URI, legacy).apply()
            }
        }
    }

    /** 是否已设置工作目录。 */
    fun isSet(): Boolean = _treeUri.value.isNotBlank()

    /** 设置工作目录（SAF tree URI，调用方需先 takePersistableUriPermission）。 */
    fun setTreeUri(value: String) {
        _treeUri.value = value
        prefs.edit().putString(KEY_TREE_URI, value).apply()
    }

    /** 清除工作目录，回退到默认 App 缓存。 */
    fun clear() {
        _treeUri.value = ""
        prefs.edit().remove(KEY_TREE_URI).apply()
    }

    /**
     * 把工作目录解析为 SAF [DocumentFile]（未设置或 URI 无效时返回 null）。
     */
    fun asDocumentFile(): DocumentFile? {
        val uri = _treeUri.value
        if (uri.isBlank()) return null
        return runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(uri))
        }.getOrNull()
    }

    /**
     * 目录显示名（UI 副标题用）：优先取 SAF 目录名；无法解析时回退 URI 尾部；
     * 未设置返回 null。
     */
    fun displayName(): String? {
        val uri = _treeUri.value
        if (uri.isBlank()) return null
        asDocumentFile()?.name?.takeIf { it.isNotBlank() }?.let { return it }
        return uri.substringAfterLast('/').takeIf { it.isNotBlank() } ?: uri
    }

    companion object {
        private const val PREFS_NAME = "work_directory"
        private const val KEY_TREE_URI = "work_tree_uri"

        // 旧版 McpConfig 存储位置（migrate 用）
        private const val LEGACY_PREFS_NAME = "mcp_server"
        private const val LEGACY_KEY_EXPORT_TREE_URI = "export_tree_uri"
    }
}
