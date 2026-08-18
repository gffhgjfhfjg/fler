package com.ai.fler.features.mcp

import android.content.Context
import com.ai.fler.core.mcp.ExportRoot
import com.ai.fler.core.mcp.FileExportRoot
import com.ai.fler.core.mcp.McpCallStats
import com.ai.fler.core.mcp.McpConfig
import com.ai.fler.core.mcp.McpHttpServer
import com.ai.fler.core.mcp.McpLogger
import com.ai.fler.core.mcp.McpProtocol
import com.ai.fler.core.mcp.McpSessions
import com.ai.fler.core.mcp.McpToolHandlers
import com.ai.fler.core.mcp.SafExportRoot
import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.service.WorkDirectory
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
    private val appLogger: AppLogger,
    private val stats: McpCallStats,
    private val workDirectory: WorkDirectory,
    @ApplicationContext private val context: Context,
) {
    private val sessions = McpSessions()
    private val protocol by lazy {
        McpProtocol(toolHandlers, logger, sessions, stats, toolHandlers)
    }
    // /export 下载根每请求解析：已设置工作目录 → SAF 实现；否则回退 cacheDir/so_export。
    // 工作目录变更后无需重启服务器即生效。
    private val exportRootProvider: () -> ExportRoot = {
        val doc = workDirectory.asDocumentFile()
        if (doc != null && doc.canWrite()) {
            SafExportRoot(context, android.net.Uri.parse(workDirectory.treeUri.value))
        } else {
            FileExportRoot(java.io.File(context.cacheDir, "so_export"))
        }
    }
    private val httpServer by lazy {
        McpHttpServer(protocol, config, sessions, logger, exportRootProvider)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow(McpStatus())
    val status: StateFlow<McpStatus> = _status.asStateFlow()

    init {
        // 运行期间周期性刷新活动连接数 + 局域网地址（WiFi 重连/IP 变化后自动更新）
        scope.launch {
            while (isActive) {
                if (httpServer.isRunning()) {
                    _status.value = _status.value.copy(activeSessions = sessions.size())
                    if (config.bindMode.value == McpConfig.BindMode.LAN) {
                        refreshLanUrls()
                    }
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
            val lanSuffix = lanIp?.let { "，局域网 $it:$port" } ?: if (config.bindMode.value == McpConfig.BindMode.LAN) "，未获取到局域网 IP" else ""
            logger.info("MCP 服务器已启动: 127.0.0.1:$port（${config.bindMode.value}$lanSuffix）")
            appLogger.info("McpServer", "MCP 服务器已启动: 127.0.0.1:$port（${config.bindMode.value}$lanSuffix）")
            return true
        }
        _status.value = McpStatus(errorMessage = "端口 ${base}..${base + MAX_PORT_ATTEMPTS - 1} 均被占用")
        logger.error("MCP 服务器启动失败: 端口 ${base}..${base + MAX_PORT_ATTEMPTS - 1} 均被占用")
        appLogger.error("McpServer", "MCP 服务器启动失败: 端口 ${base}..${base + MAX_PORT_ATTEMPTS - 1} 均被占用")
        return false
    }

    fun stop() {
        logger.info("MCP 服务器已停止")
        appLogger.info("McpServer", "MCP 服务器已停止")
        httpServer.stop()
        _status.value = McpStatus()
    }

    fun isRunning(): Boolean = httpServer.isRunning()

    /**
     * 关键配置（绑定模式）变更后的热重启。
     *
     * 仅运行中生效：按最新配置重新绑定 socket（127.0.0.1 ↔ 0.0.0.0）
     * 并刷新连接 URL；未运行时 no-op。前台服务不受影响。
     */
    fun restart() {
        if (!httpServer.isRunning()) return
        httpServer.stop()
        start()
    }

    /** 依据当前网络重算 LAN URL（IP 变化后自动更新显示；无变化不发射）。 */
    private fun refreshLanUrls() {
        val current = _status.value
        if (!current.isRunning) return
        val lanIp = localIpv4()
        val lanUrl = if (lanIp != null) "http://$lanIp:${current.port}/mcp" else ""
        val sseLanUrl = if (lanIp != null) "http://$lanIp:${current.port}/sse" else ""
        if (lanUrl != current.lanUrl || sseLanUrl != current.sseLanUrl) {
            _status.value = current.copy(lanUrl = lanUrl, sseLanUrl = sseLanUrl)
            if (lanIp != null) {
                logger.info("局域网地址已更新: $lanIp:${current.port}")
                appLogger.info("McpServer", "局域网地址已更新: $lanIp:${current.port}")
            }
        }
    }

    /**
     * 获取局域网 IPv4（优先 WiFi/以太网接口的私网地址）。
     *
     * 排除蜂窝（rmnet/ccmni）、VPN（tun/tap）、dummy 接口——其地址在局域网内
     * 不可达，作为「局域网地址」展示会误导。选择优先级：
     * 1. wlan*/eth*/ap* 接口的 RFC1918 私网地址（典型 WiFi/热点/以太网）
     * 2. wlan*/eth*/ap* 接口的任意 IPv4
     * 3. 其他接口的私网地址
     * 无合适地址返回 null（UI 显示「未获取到局域网 IP」提示）。
     */
    private fun localIpv4(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            var wlanPrivate: String? = null
            var wlanAny: String? = null
            var otherPrivate: String? = null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                if (name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("tun") || name.startsWith("tap") ||
                    name.startsWith("dummy")
                ) continue
                val isWlanLike = name.startsWith("wlan") || name.startsWith("eth") ||
                    name.startsWith("ap") || name.startsWith("swlan")
                val addrs = ni.inetAddresses ?: continue
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (isWlanLike) {
                        if (isPrivateLanAddress(ip)) wlanPrivate = wlanPrivate ?: ip
                        wlanAny = wlanAny ?: ip
                    } else if (isPrivateLanAddress(ip)) {
                        otherPrivate = otherPrivate ?: ip
                    }
                }
            }
            wlanPrivate ?: wlanAny ?: otherPrivate
        } catch (e: Exception) {
            null
        }
    }

    /** 是否 RFC1918 私网地址（10/8、172.16/12、192.168/16）。 */
    private fun isPrivateLanAddress(ip: String): Boolean {
        val first = ip.substringBefore('.').toIntOrNull() ?: return false
        val second = ip.substringAfter('.', "").substringBefore('.').toIntOrNull() ?: return false
        return ip.startsWith("192.168.") || ip.startsWith("10.") ||
            (first == 172 && second in 16..31)
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
