package com.ai.fler.core.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内嵌 MCP HTTP 服务器（自实现，基于 ServerSocket + Coroutine）。
 *
 * 路由：
 * - `GET  /sse`             legacy HTTP+SSE（Claude Desktop）：建立会话、发 endpoint 事件、保持流
 * - `POST /message`         legacy 消息端点（响应经对应 SSE 流回发）
 * - `POST /mcp`             MCP Streamable HTTP：JSON-RPC（内联或 SSE 响应，支持 Mcp-Session-Id）
 * - `GET  /mcp`             MCP Streamable HTTP：服务器→客户端事件流（Session-Id 握手）
 * - `GET  /export`          列出 so_export 导出目录内的文件
 * - `GET  /export/<file>`   下载导出目录内的文件（流式，防路径穿越，仅限该目录）
 *
 * 下载根目录 [fileRoot] 由调用方注入（默认 cacheDir/so_export），
 * 与 export_patched_so 工具的兜底导出目录一致：不传 destDir 且未配置 SAF 目录时
 * 导出的 so 会落在这里，即可用 `curl http://<host>:<port>/export/<文件名>` 直接拉取。
 *
 * 并发模型：每连接用 [scope] 启动协程执行 [McpProtocol.handle]（挂起、可超时、可取消），
 * 不再占用请求线程池等待长任务；线程池仅用于读请求字节。长工具（仿真/建图/导出）
 * 运行时其余请求仍可并行处理。
 */
class McpHttpServer(
    private val protocol: McpProtocol,
    private val config: McpConfig,
    private val sessions: McpSessions,
    private val logger: McpLogger,
    private val fileRoot: File,
) {
    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var executor: ExecutorService = Executors.newFixedThreadPool(THREADS)

    /** 启动服务器，返回实际监听端口（端口冲突自动回退由调用方处理）。 */
    fun start(bindHost: String, port: Int): Int {
        stop()
        executor = Executors.newFixedThreadPool(THREADS)
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindHost, port))
        serverSocket = ss
        running.set(true)
        runCatching { fileRoot.mkdirs() }
        Thread({ acceptLoop(ss) }, "mcp-accept").start()
        return ss.localPort
    }

    fun isRunning(): Boolean = running.get() && serverSocket != null

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        executor.shutdownNow()
    }

    // ========== 连接处理 ==========

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            try {
                val socket = ss.accept()
                // 读入站由小线程池处理（阻塞读）；协议执行经协程（不占池）。
                executor.execute { handleConnection(socket) }
            } catch (e: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        var sseMode = false
        var asyncOwned = false
        try {
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val req = readRequest(input) ?: return
            val remote = runCatching { socket.inetAddress?.hostAddress ?: "?" }.getOrDefault("?")
            if (!authorized(req)) {
                logger.warn("未授权连接: $remote ${req.method} ${req.path}")
                writeResponse(output, 401, "text/plain", "unauthorized")
                return
            }
            logger.debug("$remote ${req.method} ${req.path}")
            when {
                req.method == "GET" && req.path == "/sse" -> { sseMode = true; handleLegacySse(socket, output) }
                req.method == "GET" && req.path == "/mcp" -> { sseMode = true; handleSse(socket, output) }
                req.method == "POST" && req.path == "/message" -> { asyncOwned = true; closeAfterAsync(socket) { handleMessage(req, remote, output) } }
                req.method == "POST" && req.path == "/mcp" -> { asyncOwned = true; closeAfterAsync(socket) { handleStreamable(req, remote, output) } }
                req.method == "GET" && (req.path == "/export" || req.path == "/export/") ->
                    writeResponse(output, 200, "text/plain; charset=utf-8", listExportFiles())
                req.method == "GET" && req.path.startsWith("/export/") -> handleFileDownload(req, output)
                else -> writeResponse(output, 404, "text/plain", "not found")
            }
        } catch (_: Exception) {
            // 客户端断开/异常：忽略
        } finally {
            // SSE 连接的生命周期由心跳线程管理；异步 POST 由协程负责关闭
            if (!sseMode && !asyncOwned) try { socket.close() } catch (_: Exception) {}
        }
    }

    /** POST 响应在协程中异步写出；协程完成后再关 socket，避免提前关闭导致响应丢失。 */
    private inline fun closeAfterAsync(socket: Socket, crossinline body: suspend () -> Unit) {
        scope.launch {
            try {
                body()
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }
    }

    // ========== 路由 ==========

    private fun handleLegacySse(socket: Socket, output: OutputStream) {
        writeSseHeaders(output)
        val session = sessions.create(output)
        sessions.writeEndpoint(session.id, "/message?sessionId=${session.id}")
        logger.info("SSE 会话建立: ${session.id.take(8)}（Claude Desktop legacy）")
        startHeartbeat(socket, session)
    }

    private fun handleSse(socket: Socket, output: OutputStream) {
        val session = sessions.create(output)
        writeSseHeaders(output, session.id)
        logger.info("SSE 会话建立: ${session.id.take(8)}（Streamable HTTP）")
        startHeartbeat(socket, session)
    }

    /**
     * 心跳与断线检测放入专用线程，避免长期占住请求线程池的线程
     * （SSE 连接可能保持数小时，8 线程池被 SSE 占满后普通请求将无线程可用）。
     */
    private fun startHeartbeat(socket: Socket, session: McpSessions.Session) {
        val thread = Thread({
            try {
                while (!socket.isClosed && running.get()) {
                    Thread.sleep(HEARTBEAT_MS)
                    if (!sessions.writeHeartbeat(session.id)) break
                }
            } catch (_: InterruptedException) {
                // 服务器停止时中断，正常退出
            } finally {
                sessions.remove(session.id)
                logger.info("SSE 会话断开: ${session.id.take(8)}")
                try { socket.close() } catch (_: Exception) {}
            }
        }, "mcp-sse-${session.id.take(8)}")
        thread.isDaemon = true
        thread.start()
    }

        // ========== HTTP 文件下载 ==========

    /** 列出导出目录内的文件（供用户挑选可下载的 so）。 */
    private fun listExportFiles(): String {
        val files = runCatching {
            fileRoot.listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        }.getOrDefault(emptyList())
        if (files.isEmpty()) return "(so_export 目录为空，先用 export_patched_so 导出 so)"
        return files.joinToString("\n") { "${it.name}\t${it.length()} bytes" }
    }

    /**
     * `GET /export/<file>`：流式下载导出目录内的文件。
     * 仅允许 so_export 目录内的普通文件；拒绝路径穿越（../、/、\）。
     */
    private fun handleFileDownload(req: Request, output: OutputStream) {
        val name = req.path.substring("/export/".length)
        if (name.isBlank() || name.contains("..") || name.contains('/') || name.contains('\\')) {
            writeResponse(output, 400, "text/plain", "invalid filename: $name")
            return
        }
        val safeRoot = runCatching { fileRoot.canonicalPath }.getOrDefault(fileRoot.absolutePath)
        val file = runCatching { File(fileRoot, name).canonicalFile }.getOrNull()
        if (file == null ||
            !file.path.startsWith(safeRoot + File.separator) ||
            !file.exists() || !file.isFile
        ) {
            writeResponse(output, 404, "text/plain", "not found")
            return
        }
        val safeName = name.replace("\"", "'")
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Content-Disposition: attachment; filename=\"$safeName\"\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        file.inputStream().use { input ->
            input.copyTo(output, 64 * 1024)
            output.flush()
        }
    }

    private suspend fun handleMessage(req: Request, remote: String, output: OutputStream) {
        val sessionId = req.query["sessionId"]
        val response = dispatch(req.body, remote, sessionId)
        if (response == null) {
            writeResponse(output, 202, "text/plain", "")
        } else {
            val session = sessions.get(sessionId)
            if (session != null && sessionId != null && sessions.writeMessage(sessionId, response.toString())) {
                writeResponse(output, 202, "text/plain", "")
            } else {
                // 会话不存在或已断线：直接内联回发
                writeResponse(output, 200, "application/json", response.toString())
            }
        }
    }

    private suspend fun handleStreamable(req: Request, remote: String, output: OutputStream) {
        val accept = req.headers["accept"] ?: ""
        // Streamable HTTP 会话复用：读取 Mcp-Session-Id 头；若省略则本请求走内联。
        val sessionIdHeader = req.headers["mcp-session-id"]
        val response = dispatch(req.body, remote, sessionIdHeader)
        if (response == null) {
            writeResponse(output, 202, "text/plain", "")
        } else if (accept.contains("text/event-stream")) {
            writeSseHeaders(output, sessionIdHeader)
            output.write("event: message\ndata: ${response}\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
        } else {
            writeResponse(output, 200, "application/json", response.toString(), sessionIdHeader)
        }
    }

    /**
     * 解析 JSON-RPC 消息并调用协议分发（在协程中执行可挂起的工具处理）。
     * @param sessionId MCP 会话 ID（legacy ?sessionId= 或 Streamable Mcp-Session-Id），可空。
     */
    private suspend fun dispatch(body: String, remote: String = "?", sessionId: String? = null): JsonObject? {
        if (body.isBlank()) return McpErrors.errorJson(null, McpErrors.INVALID_REQUEST, "空请求")
        val request = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            logger.error("JSON-RPC 解析失败: ${e.message}")
            return McpErrors.errorJson(null, McpErrors.PARSE_ERROR, "JSON 解析失败: ${e.message}")
        }
        // 结构化记录请求（方法名 + 参数 JSON + 客户端地址）
        val method = request["method"]?.let {
            runCatching { it.jsonPrimitive.content }.getOrNull()
        }
        val paramsJson = request["params"]?.let {
            runCatching { it.toString() }.getOrNull()
        }
        logger.logRequest(method ?: "?", paramsJson, remote)
        return try {
            withTimeout(REQUEST_TIMEOUT_MS) { protocol.handle(request, sessionId) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn("JSON-RPC 超时: $method")
            McpErrors.errorJson(request["id"], McpErrors.SERVER_ERROR, "工具执行超时")
        }
    }
    // ========== HTTP 原语 ==========

    private fun readRequest(input: InputStream): Request? {
        val lineBuf = ByteArray(8192)
        val requestLine = readLine(input, lineBuf) ?: return null
        val parts = requestLine.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        var path = parts[1]
        val query = LinkedHashMap<String, String>()
        val qIdx = path.indexOf('?')
        if (qIdx >= 0) {
            path.substring(qIdx + 1).split("&").forEach { kv ->
                val eq = kv.indexOf('=')
                if (eq > 0) query[kv.substring(0, eq)] = kv.substring(eq + 1)
            }
            path = path.substring(0, qIdx)
        }
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input, lineBuf) ?: break
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
        }
        val cl = headers["content-length"]?.toIntOrNull() ?: 0
        if (cl > MAX_BODY_BYTES) {
            logger.warn("拒绝超大请求 body=$cl")
            return null
        }
        var body = ""
        if (cl > 0 && method == "POST") {
            val bytes = ByteArray(cl)
            var read = 0
            while (read < cl) {
                val n = input.read(bytes, read, cl - read)
                if (n < 0) break
                read += n
            }
            body = String(bytes, 0, read, Charsets.UTF_8)
        }
        return Request(method, path, query, headers, body)
    }

    private fun readLine(input: InputStream, buf: ByteArray): String? {
        var n = 0
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (n == 0) null else String(buf, 0, n, Charsets.ISO_8859_1)
            }
            if (b == '\n'.code) {
                var len = n
                if (len > 0 && (buf[len - 1].toInt() and 0xFF) == '\r'.code) len--
                return String(buf, 0, len, Charsets.ISO_8859_1)
            }
            if (n < buf.size) buf[n++] = b.toByte()
        }
    }

    private fun writeResponse(output: OutputStream, status: Int, contentType: String, body: String, sessionId: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n" +
            (sessionId?.let { "Mcp-Session-Id: $it\r\n" } ?: "") +
            "\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun writeSseHeaders(output: OutputStream, sessionId: String? = null) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            (sessionId?.let { "Mcp-Session-Id: $it\r\n" } ?: "") +
            "\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun authorized(req: Request): Boolean {
        val token = config.token.value
        if (token.isBlank()) return true
        val auth = req.headers["authorization"] ?: return false
        // 恒定时间比较，避免 Token 校验的时序侧信道
        return MessageDigest.isEqual(
            auth.toByteArray(Charsets.UTF_8),
            "Bearer $token".toByteArray(Charsets.UTF_8)
        )
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "Unknown"
    }

    companion object {
        private const val THREADS = 8
        private const val HEARTBEAT_MS = 5000L
private const val SOCKET_TIMEOUT_MS = 30_000
    private const val REQUEST_TIMEOUT_MS = 120_000L
        private const val MAX_BODY_BYTES = 8 * 1024 * 1024
    }
}