package com.ai.fler.core.mcp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 内嵌 MCP HTTP 服务器（自实现，基于 ServerSocket，无第三方依赖）。
 *
 * 路由：
 * - `GET  /sse`             legacy HTTP+SSE（Claude Desktop）：建立会话、发 endpoint 事件、保持流
 * - `POST /message`         legacy 消息端点（响应经对应 SSE 流回发）
 * - `POST /mcp`             MCP Streamable HTTP：JSON-RPC（内联或 SSE 响应）
 * - `GET  /mcp`             MCP Streamable HTTP：服务器→客户端事件流
 *
 * 安全：设置 Token 后所有请求校验 `Authorization: Bearer <token>`。
 */
class McpHttpServer(
    private val protocol: McpProtocol,
    private val config: McpConfig,
    private val sessions: McpSessions,
    private val logger: McpLogger,
) {
    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: String,
    )

    private val json = Json { ignoreUnknownKeys = true }
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
                executor.execute { handleConnection(socket) }
            } catch (e: Exception) {
                if (!running.get()) break
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            val input = socket.getInputStream()
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
                req.method == "GET" && req.path == "/sse" -> handleLegacySse(socket, output)
                req.method == "GET" && req.path == "/mcp" -> handleSse(socket, output)
                req.method == "POST" && req.path == "/message" -> handleMessage(req, output)
                req.method == "POST" && req.path == "/mcp" -> handleStreamable(req, output)
                else -> writeResponse(output, 404, "text/plain", "not found")
            }
        } catch (_: Exception) {
            // 客户端断开/异常：忽略
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    // ========== 路由 ==========

    private fun handleLegacySse(socket: Socket, output: OutputStream) {
        writeSseHeaders(output)
        val session = sessions.create(output)
        sessions.writeEndpoint(session.id, "/message?sessionId=${session.id}")
        logger.info("SSE 会话建立: ${session.id.take(8)}（Claude Desktop legacy）")
        while (socket.isConnected && !socket.isClosed && running.get()) {
            try { Thread.sleep(HEARTBEAT_MS) } catch (e: InterruptedException) { break }
            if (!sessions.writeHeartbeat(session.id)) break
        }
        sessions.remove(session.id)
        logger.info("SSE 会话断开: ${session.id.take(8)}")
    }

    private fun handleSse(socket: Socket, output: OutputStream) {
        writeSseHeaders(output)
        val session = sessions.create(output)
        logger.info("SSE 会话建立: ${session.id.take(8)}（Streamable HTTP）")
        while (socket.isConnected && !socket.isClosed && running.get()) {
            try { Thread.sleep(HEARTBEAT_MS) } catch (e: InterruptedException) { break }
            if (!sessions.writeHeartbeat(session.id)) break
        }
        sessions.remove(session.id)
        logger.info("SSE 会话断开: ${session.id.take(8)}")
    }

    private fun handleMessage(req: Request, output: OutputStream) {
        val sessionId = req.query["sessionId"]
        val response = dispatch(req.body)
        if (response == null) {
            writeResponse(output, 202, "text/plain", "")
            return
        }
        val session = sessions.get(sessionId)
        if (session != null && sessionId != null && sessions.writeMessage(sessionId, response.toString())) {
            writeResponse(output, 202, "text/plain", "")
        } else {
            // 会话不存在或已断线：直接内联回发
            writeResponse(output, 200, "application/json", response.toString())
        }
    }

    private fun handleStreamable(req: Request, output: OutputStream) {
        val accept = req.headers["accept"] ?: ""
        val response = dispatch(req.body)
        if (response == null) {
            writeResponse(output, 202, "text/plain", "")
            return
        }
        if (accept.contains("text/event-stream")) {
            writeSseHeaders(output)
            output.write("event: message\ndata: ${response}\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
        } else {
            writeResponse(output, 200, "application/json", response.toString())
        }
    }

    /** 解析 JSON-RPC 消息并调用协议分发（在 IO 调度执行 suspend 工具）。 */
    private fun dispatch(body: String): kotlinx.serialization.json.JsonObject? {
        if (body.isBlank()) return McpErrors.errorJson(null, McpErrors.INVALID_REQUEST, "空请求")
        val request = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            logger.error("JSON-RPC 解析失败: ${e.message}")
            return McpErrors.errorJson(null, McpErrors.PARSE_ERROR, "JSON 解析失败: ${e.message}")
        }
        val response = runBlocking(Dispatchers.IO) { protocol.handle(request) }
        if (response != null && response["error"] != null) {
            logger.warn("JSON-RPC 错误: ${response["error"]}")
        }
        return response
    }

    // ========== HTTP 原语 ==========

    private fun readRequest(input: InputStream): Request? {
        val bis = BufferedInputStream(input)
        val lineBuf = ByteArray(8192)
        val requestLine = readLine(bis, lineBuf) ?: return null
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
            val line = readLine(bis, lineBuf) ?: break
            if (line.isEmpty()) break
            val c = line.indexOf(':')
            if (c > 0) headers[line.substring(0, c).trim().lowercase()] = line.substring(c + 1).trim()
        }
        val cl = headers["content-length"]?.toIntOrNull() ?: 0
        var body = ""
        if (cl > 0 && method == "POST") {
            val bytes = ByteArray(cl)
            var read = 0
            while (read < cl) {
                val n = bis.read(bytes, read, cl - read)
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

    private fun writeResponse(output: OutputStream, status: Int, contentType: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${reason(status)}\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Connection: close\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.write(bytes)
        output.flush()
    }

    private fun writeSseHeaders(output: OutputStream) {
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n\r\n"
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        output.flush()
    }

    private fun authorized(req: Request): Boolean {
        val token = config.token.value
        if (token.isBlank()) return true
        val auth = req.headers["authorization"] ?: return false
        return auth == "Bearer $token"
    }

    private fun reason(status: Int): String = when (status) {
        200 -> "OK"; 202 -> "Accepted"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        404 -> "Not Found"; 500 -> "Internal Server Error"; else -> "Unknown"
    }

    companion object {
        private const val THREADS = 8
        private const val HEARTBEAT_MS = 5000L
    }
}
