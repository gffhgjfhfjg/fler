package com.ai.fler.core.log

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用级日志条目。
 */
data class AppLogEntry(
    val seq: Long,
    val timestamp: Long,
    val level: String,
    val tag: String,
    val message: String,
)

/**
 * 应用内部日志收集器，与 MCP 日志 [com.ai.fler.core.mcp.McpLogger] 对等。
 *
 * 各模块通过 Hilt 注入后调用 [info]/[debug]/[warn]/[error] 记录日志，
 * 日志 Tab 通过 [AppLogViewModel] 观察 [entries] 展示。
 *
 * 替换旧方案：不再从 logcat 读取，避免 READ_LOGS 权限与进程残留问题。
 */
@Singleton
class AppLogger @Inject constructor() {

    private val _entries = MutableStateFlow<List<AppLogEntry>>(emptyList())
    val entries: StateFlow<List<AppLogEntry>> = _entries.asStateFlow()

    private val lock = Any()
    private var seqCounter = 0L

    fun info(tag: String, message: String) = log("I", tag, message)
    fun debug(tag: String, message: String) = log("D", tag, message)
    fun warn(tag: String, message: String) = log("W", tag, message)
    fun error(tag: String, message: String) = log("E", tag, message)

    fun log(level: String, tag: String, message: String) {
        synchronized(lock) {
            _entries.value = (_entries.value + AppLogEntry(
                seq = seqCounter++,
                timestamp = System.currentTimeMillis(),
                level = level,
                tag = tag,
                message = message,
            )).takeLast(MAX_ENTRIES)
        }
    }

    fun clear() {
        synchronized(lock) { _entries.value = emptyList() }
    }

    companion object {
        private const val MAX_ENTRIES = 3000
    }
}