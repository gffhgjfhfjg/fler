package com.ai.fler.core.mcp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 服务器运行日志（有界内存，供设置页日志页展示）。
 *
 * 级别：I/D/W/E。最多保留 [MAX_ENTRIES] 条，超出丢弃最旧。
 */
@Singleton
class McpLogger @Inject constructor() {

    private val _entries = MutableStateFlow<List<McpLogEntry>>(emptyList())
    val entries: StateFlow<List<McpLogEntry>> = _entries.asStateFlow()

    // MCP 请求线程池并发写日志，seq 自增与 list 更新必须原子化，否则会丢更新/seq 重复
    private val lock = Any()
    private var seqCounter = 0L

    fun info(message: String) = log("I", message)
    fun debug(message: String) = log("D", message)
    fun warn(message: String) = log("W", message)
    fun error(message: String) = log("E", message)

    fun log(level: String, message: String) {
        synchronized(lock) {
            val entry = McpLogEntry(
                seq = seqCounter++,
                timestamp = System.currentTimeMillis(),
                level = level,
                message = message,
            )
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
    }

    /**
     * 记录一条带结构化请求信息的日志（method + params JSON + 客户端地址）。
     * 用于 MCP 日志页直接展示请求参数。
     */
    fun logRequest(
        method: String,
        paramsJson: String?,
        remote: String?,
        level: String = "I",
    ) {
        synchronized(lock) {
            val entry = McpLogEntry(
                seq = seqCounter++,
                timestamp = System.currentTimeMillis(),
                level = level,
                message = "请求: $method",
                method = method,
                paramsJson = paramsJson,
                remote = remote,
            )
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
    }

    /**
     * 记录一条工具调用的结果（含耗时与是否出错），供日志页展示。
     * 与 [logRequest] 分开：前者是 HTTP 层出入请求，后者是协议层工具执行结果。
     */
    fun logToolResult(
        method: String,
        message: String,
        durationMs: Long? = null,
        isError: Boolean = false,
        paramsJson: String? = null,
        remote: String? = null,
    ) {
        synchronized(lock) {
            val entry = McpLogEntry(
                seq = seqCounter++,
                timestamp = System.currentTimeMillis(),
                level = if (isError) "E" else "I",
                message = message,
                method = method,
                paramsJson = paramsJson,
                remote = remote,
                durationMs = durationMs,
                isError = isError,
            )
            _entries.value = (_entries.value + entry).takeLast(MAX_ENTRIES)
        }
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}

/** MCP 日志条目。 */
data class McpLogEntry(
    val seq: Long,
    val timestamp: Long,
    val level: String,
    val message: String,
    /** JSON-RPC 方法名（如 tools/call），非请求日志为 null。 */
    val method: String? = null,
    /** 请求参数 JSON 字符串（解析后的 params），非请求日志为 null。 */
    val paramsJson: String? = null,
    /** 客户端地址，非请求日志为 null。 */
    val remote: String? = null,
    /** 工具执行耗时（ms），非工具结果日志为 null。 */
    val durationMs: Long? = null,
    /** 工具调用是否出错（isError），非工具结果日志为 null。 */
    val isError: Boolean? = null,
)
