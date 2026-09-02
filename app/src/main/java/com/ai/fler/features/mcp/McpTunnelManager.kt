package com.ai.fler.features.mcp

import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.mcp.McpLogger
import com.ai.fler.core.mcp.McpTunnelConfig
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 外网隧道管理器（SSH 反向端口转发，JSch 实现）。
 *
 * 两种模式：
 * - PUBLIC：免费公网中继 localhost.run（nokey@localhost.run:22，无需注册），
 *   远端 80 端口反向转发到本机 MCP；中继侧终止 TLS 并分配随机的
 *   lhr.life 子域名地址（形如 https://xxx.lhr.life，从 SSH 会话输出/横幅解析）。
 * - CUSTOM：自建 SSH 服务器，0.0.0.0:remotePort 反向转发到本机 MCP，
 *   外网地址 http://host:remotePort（服务器需开启 GatewayPorts 才能从外网访问）。
 *
 * 生命周期完全联动 MCP 服务器：服务器运行且开关开启时自动建隧道，断线指数退避重连，
 * 服务器停止 / 开关关闭 / 连接参数变更时自动断开或重建。无需手动启停接口。
 *
 * 隧道转发目标是 127.0.0.1:MCP端口，因此「仅本机 / 局域网」两种绑定模式均可用，
 * 本机模式也能安全上隧道（服务器仍只监听回环，只有隧道出口暴露公网）。
 */
@Singleton
class McpTunnelManager @Inject constructor(
    private val config: McpTunnelConfig,
    private val serverManager: McpServerManager,
    private val logger: McpLogger,
    private val appLogger: AppLogger,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow(McpTunnelStatus())
    val status: StateFlow<McpTunnelStatus> = _status.asStateFlow()

    private var engineJob: Job? = null
    private var enginePort = 0
    private var engineRevision = -1

    init {
        // 期望状态联动：MCP 服务器启停 / 隧道开关 / 连接参数变更 → 自动建/断/重建
        scope.launch {
            combine(
                serverManager.status,
                config.enabled,
                config.revision,
            ) { server, enabled, revision ->
                Triple(server.isRunning, server.port, enabled to revision)
            }.distinctUntilChanged().collect { (serverRunning, serverPort, desired) ->
                val (enabled, revision) = desired
                val shouldRun = enabled && serverRunning && serverPort > 0
                when {
                    !shouldRun -> engineJob?.takeIf { it.isActive }?.cancel()
                    engineJob?.isActive != true ||
                        serverPort != enginePort ||
                        revision != engineRevision -> {
                        engineJob?.cancel()
                        enginePort = serverPort
                        engineRevision = revision
                        engineJob = scope.launch { runEngine(serverPort) }
                    }
                }
            }
        }
    }

    /**
     * 隧道引擎：建连 → 看护（断线重连 / 端口变化重建），直到期望失效或被取消。
     *
     * 每次迭代的 session/channel 均为局部持有，取消时在 finally 中关闭，
     * 避免新旧引擎交替期间互相干扰。
     */
    private suspend fun runEngine(localPort: Int) {
        var backoffMs = INITIAL_RETRY_MS
        try {
            outer@ while (currentCoroutineContext().isActive) {
                val st = serverManager.status.value
                if (!config.enabled.value || !st.isRunning) break
                var tunnel: TunnelHandle? = null
                try {
                    _status.value = _status.value.copy(isConnecting = true, errorMessage = null)
                    tunnel = openTunnel(localPort)
                    _status.value = McpTunnelStatus(
                        isRunning = true,
                        isConnecting = false,
                        publicUrl = tunnel.url,
                    )
                    logger.info("外网隧道已建立: ${tunnel.url}")
                    appLogger.info("McpTunnel", "外网隧道已建立: ${tunnel.url}")
                    backoffMs = INITIAL_RETRY_MS
                    while (currentCoroutineContext().isActive) {
                        delay(WATCH_INTERVAL_MS)
                        val cur = serverManager.status.value
                        if (!config.enabled.value || !cur.isRunning) break
                        // 服务器重启后端口变化：整体退出，由联动层用新端口重建
                        if (cur.port != localPort) break@outer
                        if (!tunnel.session.isConnected()) {
                            logger.warn("外网隧道连接断开，正在重连")
                            appLogger.warn("McpTunnel", "外网隧道连接断开，正在重连")
                            _status.value = _status.value.copy(isRunning = false, publicUrl = "")
                            break
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val msg = "外网隧道连接失败: ${e.message ?: e.javaClass.simpleName}"
                    _status.value = McpTunnelStatus(errorMessage = msg)
                    logger.error(msg)
                    appLogger.error("McpTunnel", msg)
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(MAX_RETRY_MS)
                } finally {
                    tunnel?.let { t ->
                        withContext(NonCancellable) { t.close() }
                    }
                }
            }
        } finally {
            // 引擎退出（被取消/期望失效）→ 状态复位
            if (_status.value.isRunning || _status.value.isConnecting) {
                _status.value = McpTunnelStatus()
            }
        }
    }

    /** 建立一条 SSH 会话并注册反向转发，返回句柄（含外网访问 URL）。 */
    private suspend fun openTunnel(localPort: Int): TunnelHandle {
        val provider = config.provider.value
        val jsch = JSch()
        val session: Session = when (provider) {
            McpTunnelConfig.Provider.PUBLIC ->
                jsch.getSession(RELAY_USER, RELAY_HOST, RELAY_PORT)
            McpTunnelConfig.Provider.CUSTOM -> {
                val host = config.host.value.trim()
                if (host.isEmpty()) throw JSchException("未配置 SSH 服务器地址")
                jsch.getSession(config.username.value.trim().ifEmpty { "root" }, host, config.sshPort.value)
            }
        }
        val password = config.password.value
        if (password.isNotEmpty()) session.setPassword(password)
        // localhost.run 的 nokey 走无认证（JSch 会最先尝试 none），自建服务器走密码认证
        session.setConfig("PreferredAuthentications", "password,keyboard-interactive,publickey")
        session.setConfig("StrictHostKeyChecking", "no")
        val bannerUrl = AtomicReference<String>()
        session.setUserInfo(RelayUserInfo(password) { message ->
            extractRelayUrl(message)?.let { bannerUrl.compareAndSet(null, it) }
        })
        session.setServerAliveInterval(SERVER_ALIVE_INTERVAL_MS)
        session.setServerAliveCountMax(SERVER_ALIVE_COUNT_MAX)
        session.connect(CONNECT_TIMEOUT_MS)

        try {
            when (provider) {
                McpTunnelConfig.Provider.PUBLIC -> {
                    // 远端 80 端口（中继侧 TLS 终止）→ 本机 MCP；地址从会话输出解析
                    session.setPortForwardingR("", RELAY_REMOTE_PORT, "127.0.0.1", localPort)
                    val relay = readRelayUrl(session)
                    val url = relay?.url
                        ?: bannerUrl.get()
                        ?: throw JSchException(
                            "未能从 localhost.run 获取外网地址（请确认设备网络可访问 localhost.run）"
                        )
                    return TunnelHandle(session, relay?.channel, url)
                }
                McpTunnelConfig.Provider.CUSTOM -> {
                    val requested = config.remotePort.value
                    session.setPortForwardingR("0.0.0.0", requested, "127.0.0.1", localPort)
                    val bound = if (requested == 0) {
                        // 0 = 服务器随机分配：从已注册转发列表取实际端口
                        session.getPortForwardingR().firstOrNull()
                            ?.substringBefore(':')?.toIntOrNull()
                            ?: throw JSchException("未能获取服务器分配的远端端口")
                    } else {
                        requested
                    }
                    return TunnelHandle(session, null, "http://${config.host.value.trim()}:$bound")
                }
            }
        } catch (e: Exception) {
            // 建连成功但转发注册/地址解析失败：断开会话，避免泄漏已连接的 SSH 线程
            runCatching { session.disconnect() }
            throw e
        }
    }

    /**
     * 打开 shell 通道读取 localhost.run 分配的外网地址。
     *
     * 中继在通道输出中打印 Connect to https://xxx.lhr.life；
     * 解析成功后通道保持打开（中继在会话存活期间维持隧道），由句柄统一关闭。
     */
    private suspend fun readRelayUrl(session: Session): RelayChannel? {
        var channel: ChannelShell? = null
        var handedOff = false
        try {
            val ch = session.openChannel("shell") as ChannelShell
            channel = ch
            // 模拟 ssh -T：不申请 pty，避免终端转义序列干扰 URL 解析
            ch.setPty(false)
            ch.connect(CHANNEL_CONNECT_TIMEOUT_MS)
            val input = ch.getInputStream()
            val text = StringBuilder()
            val deadline = System.currentTimeMillis() + URL_READ_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (input.available() > 0) {
                    val buf = ByteArray(4096)
                    val n = input.read(buf)
                    if (n < 0) break
                    text.append(String(buf, 0, n, Charsets.UTF_8))
                    extractRelayUrl(text)?.let {
                        handedOff = true
                        return RelayChannel(ch, it)
                    }
                } else {
                    if (ch.isClosed || ch.isEOF) {
                        extractRelayUrl(text)?.let {
                            handedOff = true
                            return RelayChannel(ch, it)
                        }
                        break
                    }
                    delay(URL_POLL_INTERVAL_MS)
                }
            }
            extractRelayUrl(text)?.let {
                handedOff = true
                return RelayChannel(ch, it)
            }
            return null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return null
        } finally {
            // 未成功移交（未解析到地址/异常/取消）时关闭通道；成功路径由 TunnelHandle 持有
            if (!handedOff) runCatching { channel?.disconnect() }
        }
    }

    /** 从中继输出/横幅文本提取外网 URL（优先 lhr.life / localhost.run 域名，兜底任意 https）。 */
    private fun extractRelayUrl(text: CharSequence): String? {
        if (!text.contains("http")) return null
        val urls = RELAY_URL_REGEX.findAll(text).map { it.value }.toList()
        return urls.firstOrNull { u ->
            val host = u.removePrefix("https://").removePrefix("http://").substringBefore('/')
            host.endsWith(".lhr.life") || host.endsWith(".localhost.run") || host == "localhost.run"
        } ?: urls.firstOrNull { it.startsWith("https://") }
    }

    /** 隧道句柄：一条 SSH 会话 +（公网中继模式下）URL 读取用的 shell 通道。 */
    private class TunnelHandle(
        val session: Session,
        val shellChannel: ChannelShell?,
        val url: String,
    ) {
        fun close() {
            runCatching { shellChannel?.disconnect() }
            runCatching { session.disconnect() }
        }
    }

    /** 公网中继已解析地址 + 对应 shell 通道。 */
    private class RelayChannel(val channel: ChannelShell, val url: String)

    /**
     * SSH 用户信息回调：密码来自配置；showMessage 接收认证横幅
     * （localhost.run 可能经横幅下发地址，作为解析兜底）。
     */
    private class RelayUserInfo(
        private val password: String,
        private val onBanner: (String) -> Unit,
    ) : UserInfo {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptYesNo(message: String?): Boolean = true
        override fun showMessage(message: String?) {
            message?.let(onBanner)
        }
        override fun promptPassword(message: String?): Boolean = true
        override fun promptPassphrase(message: String?): Boolean = false
    }

    companion object {
        // localhost.run 免费中继
        private const val RELAY_HOST = "localhost.run"
        private const val RELAY_PORT = 22
        private const val RELAY_USER = "nokey"
        private const val RELAY_REMOTE_PORT = 80

        private const val CONNECT_TIMEOUT_MS = 25_000
        private const val CHANNEL_CONNECT_TIMEOUT_MS = 10_000
        private const val URL_READ_TIMEOUT_MS = 20_000
        private const val URL_POLL_INTERVAL_MS = 250L
        private const val WATCH_INTERVAL_MS = 5_000L
        private const val SERVER_ALIVE_INTERVAL_MS = 15
        private const val SERVER_ALIVE_COUNT_MAX = 4
        private const val INITIAL_RETRY_MS = 5_000L
        private const val MAX_RETRY_MS = 60_000L

        private val RELAY_URL_REGEX = Regex("""https?://[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)+""")
    }
}

/** MCP 外网隧道运行状态。 */
data class McpTunnelStatus(
    val isRunning: Boolean = false,
    val isConnecting: Boolean = false,
    /** 外网基础 URL（https://xxx.lhr.life 或 http://host:port），空 = 未建立。 */
    val publicUrl: String = "",
    val errorMessage: String? = null,
)
