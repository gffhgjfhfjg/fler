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
 * MCP Server 配置（SharedPreferences 持久化）。
 *
 * - [enabled] 是否启用
 * - [bindMode] LOCAL=仅本机 / LAN=局域网（需显式开启）
 * - [port] 端口（自动回退）
 * - [token] 可选 Bearer Token（设置后所有请求校验）
 * - [patchEnabled] 指令补丁工具是否开启（默认关闭，客户端决定）
 * - [exportTreeUri] 用户选择的导出文件夹 SAF tree URI（持久化授权，补丁后 SO 导出目标）
 */
@Singleton
class McpConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    enum class BindMode { LOCAL, LAN }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mcp_server", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, true))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _bindMode = MutableStateFlow(
        if (prefs.getString(KEY_BIND_MODE, "local") == "lan") BindMode.LAN else BindMode.LOCAL
    )
    val bindMode: StateFlow<BindMode> = _bindMode.asStateFlow()

    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, DEFAULT_PORT))
    val port: StateFlow<Int> = _port.asStateFlow()

    private val _token = MutableStateFlow(prefs.getString(KEY_TOKEN, "") ?: "")
    val token: StateFlow<String> = _token.asStateFlow()

    private val _patchEnabled = MutableStateFlow(prefs.getBoolean(KEY_PATCH_ENABLED, false))
    val patchEnabled: StateFlow<Boolean> = _patchEnabled.asStateFlow()

    private val _exportTreeUri = MutableStateFlow(prefs.getString(KEY_EXPORT_TREE_URI, "") ?: "")
    val exportTreeUri: StateFlow<String> = _exportTreeUri.asStateFlow()

    private val _emuToolsEnabled = MutableStateFlow(prefs.getBoolean(KEY_EMU_TOOLS_ENABLED, false))
    val emuToolsEnabled: StateFlow<Boolean> = _emuToolsEnabled.asStateFlow()

    fun setEnabled(value: Boolean) { _enabled.value = value; prefs.edit().putBoolean(KEY_ENABLED, value).apply() }
    fun setBindMode(value: BindMode) { _bindMode.value = value; prefs.edit().putString(KEY_BIND_MODE, if (value == BindMode.LAN) "lan" else "local").apply() }
    fun setPort(value: Int) { _port.value = value; prefs.edit().putInt(KEY_PORT, value).apply() }
    fun setToken(value: String) { _token.value = value; prefs.edit().putString(KEY_TOKEN, value).apply() }
    fun setPatchEnabled(value: Boolean) { _patchEnabled.value = value; prefs.edit().putBoolean(KEY_PATCH_ENABLED, value).apply() }
    fun setExportTreeUri(value: String) { _exportTreeUri.value = value; prefs.edit().putString(KEY_EXPORT_TREE_URI, value).apply() }
    fun setEmuToolsEnabled(value: Boolean) { _emuToolsEnabled.value = value; prefs.edit().putBoolean(KEY_EMU_TOOLS_ENABLED, value).apply() }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BIND_MODE = "bind_mode"
        private const val KEY_PORT = "port"
        private const val KEY_TOKEN = "token"
        private const val KEY_PATCH_ENABLED = "patch_enabled"
        private const val KEY_EXPORT_TREE_URI = "export_tree_uri"
        private const val KEY_EMU_TOOLS_ENABLED = "emu_tools_enabled"
    }
}
