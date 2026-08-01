package com.ai.fler.features.mcp

import android.content.Context
import com.ai.fler.core.mcp.McpConfig
import com.ai.fler.core.mcp.McpHttpServer
import com.ai.fler.core.mcp.McpLogger
import com.ai.fler.core.mcp.McpProtocol
import com.ai.fler.core.mcp.McpSessions
import com.ai.fler.core.mcp.McpToolHandlers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 服务器生命周期管理。
 *
 * - 按配置决定绑定 127.0.0.1（本机）或 0.0.0.0（局域网）
 * - 端口冲突自动回退（base..base+9）并回显实际端口
 * - 暴露运行状态 StateFlow（端口 / 连接数 / 连接 URL：/mcp 与 /sse）
 */
@Singleton
class McpServerManager @Inject constructor(
    private val config: McpConfig,
    private val toolHandlers: McpToolHandlers,
    private val logger: McpLogger,
    @ApplicationContext private val context: Context,
) {
    private val sessions = McpSessions()
    private val protocol by lazy { McpProtocol(toolHandlers, logger) }
    private val httpServer by lazy { McpHttpServer(protocol, config, sessions, logger) }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(McpStatus())
    val status: StateFlow<McpStatus> = _status.asStateFlow()

    init {
        // 运行期间周期性刷新活动连接数
        scope.launch {
            while (isActive) {
                if (httpServer.isRunning()) {
                    _status.value = _status.value.copy(activeSessions = sessions.size())
                }
                delay(3000)
            }
        }
    }

    fun start(): Boolean {
        if (httpServer.isRunning()) return true
        val host = if (config.bindMode.value == McpConfig.BindMode.LAN) "0.0.0.0" else "127.0.0.1"
        val base = config.port.value
        var port = base
        var started = false
        for (attempt in 0 until MAX_PORT_ATTEMPTS) {
            val candidate = base + attempt
            try {
                port = httpServer.start(host, candidate)
                started = true
                break
            } catch (e: Exception) {
                // 端口占用，尝试下一个
            }
        }
        if (started) {
            val lanIp = if (config.bindMode.value == McpConfig.BindMode.LAN) localIpv4() else null
            _status.value = McpStatus(
                isRunning = true,
                port = port,
                activeSessions = sessions.size(),
                localUrl = "http://127.0.0.1:$port/mcp",
                lanUrl = if (lanIp != null) "http://$lanIp:$port/mcp" else "",
                sseLocalUrl = "http://127.0.0.1:$port/sse",
                sseLanUrl = if (lanIp != null) "http://$lanIp:$port/sse" else "",
            )
            logger.info("MCP 服务器已启动: 127.0.0.1:$port（${config.bindMode.value}）")
            return true
        }
        _status.value = McpStatus(errorMessage = "端口 ${base}..${base + MAX_PORT_ATTEMPTS - 1} 均被占用")
        logger.error("MCP 服务器启动失败: 端口 ${base}..${base + MAX_PORT_ATTEMPTS - 1} 均被占用")
        return false
    }

    fun stop() {
        logger.info("MCP 服务器已停止")
        httpServer.stop()
        _status.value = McpStatus()
    }

    fun isRunning(): Boolean = httpServer.isRunning()

    private fun localIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MAX_PORT_ATTEMPTS = 10
    }
}

/** MCP 服务器运行状态。 */
data class McpStatus(
    val isRunning: Boolean = false,
    val port: Int = 0,
    val activeSessions: Int = 0,
    /** MCP Streamable HTTP 端点（本机）：http://127.0.0.1:PORT/mcp */
    val localUrl: String = "",
    /** MCP Streamable HTTP 端点（局域网） */
    val lanUrl: String = "",
    /** Claude Desktop SSE 端点（本机）：http://127.0.0.1:PORT/sse */
    val sseLocalUrl: String = "",
    /** Claude Desktop SSE 端点（局域网） */
    val sseLanUrl: String = "",
    val errorMessage: String? = null,
)
