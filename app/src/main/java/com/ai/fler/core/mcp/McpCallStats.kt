package com.ai.fler.core.mcp

import com.ai.fler.data.dao.McpToolStatDao
import com.ai.fler.data.entity.McpToolStat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 工具调用统计聚合器（内存热点 + Room 持久化）。
 *
 * - [record] 在 McpProtocol 每次工具调用完成/异常时调用，内存内聚合后异步 upsert 单行；
 * - 构造时从 Room 恢复历史聚合，重启不清零；
 * - [clear] 提供手动清零（清内存 + 清表）。
 */
@Singleton
class McpCallStats @Inject constructor(
    private val dao: McpToolStatDao,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val map = HashMap<String, LongArray>()

    private val _state = MutableStateFlow(McpCallStatsState())
    val state: StateFlow<McpCallStatsState> = _state.asStateFlow()

    init {
        // 从 Room 恢复历史聚合（表极小，单次阻塞可接受；McpCallStats 仅在服务启动/统计页打开时实例化）
        val restored = runBlocking(Dispatchers.IO) { dao.getAll() }
        synchronized(lock) {
            restored.forEach { row ->
                map[row.tool] = longArrayOf(row.calls, row.errors, row.totalMs, row.maxMs, row.lastAt)
            }
            publishLocked()
        }
    }

    /** 记录一次工具调用。 */
    fun record(tool: String, isError: Boolean, elapsedMs: Long) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val a = map.getOrPut(tool) { longArrayOf(0, 0, 0, 0, now) }
            a[0] += 1
            if (isError) a[1] += 1
            a[2] += elapsedMs
            if (elapsedMs > a[3]) a[3] = elapsedMs
            a[4] = now
            publishLocked()
            val snap = McpToolStat(tool, a[0], a[1], a[2], a[3], a[4])
            // 落盘（消息队列，立即返回）
            scope.launch {
                runCatching { dao.upsert(snap) }
            }
        }
    }

    /** 清零全部统计（内存 + Room）。 */
    fun clear() {
        synchronized(lock) {
            map.clear()
            publishLocked()
        }
        scope.launch {
            runCatching { dao.clear() }
        }
    }

    /** 必须在持锁或安全单线程下调用。 */
    private fun publishLocked() {
        val tools = map.entries
            .map { (name, a) -> McpToolStat(name, a[0], a[1], a[2], a[3], a[4]) }
            .sortedByDescending { it.calls }
        val totalCalls = tools.sumOf { it.calls }
        val totalErrors = tools.sumOf { it.errors }
        val totalMs = tools.sumOf { it.totalMs }
        val maxMs = tools.maxOfOrNull { it.maxMs } ?: 0L
        _state.value = McpCallStatsState(
            totalCalls = totalCalls,
            totalErrors = totalErrors,
            totalMs = totalMs,
            maxMs = maxMs,
            perTool = tools,
        )
    }
}

/** MCP 工具调用统计快照。 */
data class McpCallStatsState(
    val totalCalls: Long = 0,
    val totalErrors: Long = 0,
    val totalMs: Long = 0,
    val maxMs: Long = 0,
    val perTool: List<McpToolStat> = emptyList(),
) {
    val successRate: Float
        get() = if (totalCalls > 0) 1f - totalErrors.toFloat() / totalCalls else 1f
    val avgMs: Long
        get() = if (totalCalls > 0) totalMs / totalCalls else 0
}