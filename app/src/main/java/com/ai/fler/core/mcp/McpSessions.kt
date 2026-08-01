package com.ai.fler.core.mcp

import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * SSE 会话注册表（服务器→客户端事件推送）。
 *
 * - 每个 SSE 连接一个会话，持有其 [OutputStream]
 * - POST 消息的响应经会话写回对应 SSE 流（`event: message`）
 * - 写入失败视为断线，自动清理
 */
class McpSessions {

    class Session(val id: String, val output: OutputStream)

    private val sessions = ConcurrentHashMap<String, Session>()

    fun create(output: OutputStream): Session {
        val id = UUID.randomUUID().toString()
        val session = Session(id, output)
        sessions[id] = session
        return session
    }

    fun get(id: String?): Session? = if (id == null) null else sessions[id]

    fun remove(id: String) {
        sessions.remove(id)
    }

    fun size(): Int = sessions.size

    /**
     * 向会话写一条 `event: message` 事件。返回是否成功（失败即断线并清理）。
     */
    fun writeMessage(id: String, jsonText: String): Boolean {
        val session = sessions[id] ?: return false
        return synchronized(session) {
            try {
                session.output.write("event: message\ndata: $jsonText\n\n".toByteArray(Charsets.UTF_8))
                session.output.flush()
                true
            } catch (e: Exception) {
                remove(id)
                false
            }
        }
    }

    /** 向会话写一条 `event: endpoint` 事件（legacy SSE 握手）。 */
    fun writeEndpoint(id: String, endpointPath: String): Boolean {
        val session = sessions[id] ?: return false
        return synchronized(session) {
            try {
                session.output.write("event: endpoint\ndata: $endpointPath\n\n".toByteArray(Charsets.UTF_8))
                session.output.flush()
                true
            } catch (e: Exception) {
                remove(id)
                false
            }
        }
    }

    fun writeHeartbeat(id: String): Boolean {
        val session = sessions[id] ?: return false
        return synchronized(session) {
            try {
                session.output.write(": ping\n\n".toByteArray(Charsets.UTF_8))
                session.output.flush()
                true
            } catch (e: Exception) {
                remove(id)
                false
            }
        }
    }
}
