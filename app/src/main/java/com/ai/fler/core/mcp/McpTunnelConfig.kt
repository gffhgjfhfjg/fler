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
 * MCP 外网隧道配置（SharedPreferences 持久化）。
 *
 * - [enabled] 隧道开关：开启后随 MCP 服务器启动/停止自动建/断隧道
 * - [provider] PUBLIC=免费公网中继（localhost.run）/ CUSTOM=自建 SSH 服务器反向转发
 * - 自建参数：[host] / [sshPort] / [username] / [password] / [remotePort]（0=由服务器随机分配）
 */
@Singleton
class McpTunnelConfig @Inject constructor(
    @ApplicationContext context: Context,
) {
    enum class Provider { PUBLIC, CUSTOM }

    private val prefs: SharedPreferences =
        context.getSharedPreferences("mcp_tunnel", Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _provider = MutableStateFlow(
        if (prefs.getString(KEY_PROVIDER, VALUE_PUBLIC) == VALUE_CUSTOM) {
            Provider.CUSTOM
        } else {
            Provider.PUBLIC
        }
    )
    val provider: StateFlow<Provider> = _provider.asStateFlow()

    private val _host = MutableStateFlow(prefs.getString(KEY_HOST, "") ?: "")
    val host: StateFlow<String> = _host.asStateFlow()

    private val _sshPort = MutableStateFlow(prefs.getInt(KEY_SSH_PORT, DEFAULT_SSH_PORT))
    val sshPort: StateFlow<Int> = _sshPort.asStateFlow()

    private val _username = MutableStateFlow(prefs.getString(KEY_USERNAME, "root") ?: "root")
    val username: StateFlow<String> = _username.asStateFlow()

    private val _password = MutableStateFlow(prefs.getString(KEY_PASSWORD, "") ?: "")
    val password: StateFlow<String> = _password.asStateFlow()

    private val _remotePort = MutableStateFlow(prefs.getInt(KEY_REMOTE_PORT, DEFAULT_REMOTE_PORT))
    val remotePort: StateFlow<Int> = _remotePort.asStateFlow()

    /**
     * 连接参数修订号：除 [enabled] 外任一参数变更时自增。
     * 隧道管理器据此在运行中热重载连接参数（断开重连）。
     */
    private val _revision = MutableStateFlow(prefs.getInt(KEY_REVISION, 0))
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun setEnabled(value: Boolean) {
        _enabled.value = value
        prefs.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun setProvider(value: Provider) {
        _provider.value = value
        prefs.edit()
            .putString(KEY_PROVIDER, if (value == Provider.CUSTOM) VALUE_CUSTOM else VALUE_PUBLIC)
            .apply()
        bumpRevision()
    }

    fun setHost(value: String) {
        _host.value = value
        prefs.edit().putString(KEY_HOST, value).apply()
        bumpRevision()
    }

    fun setSshPort(value: Int) {
        _sshPort.value = value
        prefs.edit().putInt(KEY_SSH_PORT, value).apply()
        bumpRevision()
    }

    fun setUsername(value: String) {
        _username.value = value
        prefs.edit().putString(KEY_USERNAME, value).apply()
        bumpRevision()
    }

    fun setPassword(value: String) {
        _password.value = value
        prefs.edit().putString(KEY_PASSWORD, value).apply()
        bumpRevision()
    }

    fun setRemotePort(value: Int) {
        _remotePort.value = value
        prefs.edit().putInt(KEY_REMOTE_PORT, value).apply()
        bumpRevision()
    }

    private fun bumpRevision() {
        _revision.value += 1
        prefs.edit().putInt(KEY_REVISION, _revision.value).apply()
    }

    companion object {
        const val DEFAULT_SSH_PORT = 22
        const val DEFAULT_REMOTE_PORT = 8765
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PROVIDER = "provider"
        private const val KEY_HOST = "host"
        private const val KEY_SSH_PORT = "ssh_port"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_REMOTE_PORT = "remote_port"
        private const val KEY_REVISION = "revision"
        private const val VALUE_PUBLIC = "public"
        private const val VALUE_CUSTOM = "custom"
    }
}
