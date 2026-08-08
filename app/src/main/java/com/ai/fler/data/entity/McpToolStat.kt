package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * MCP 工具调用统计（按工具一行聚合）。
 *
 * 每完成一次工具调用即 upsert 一行（写入较轻），供「MCP 调用统计」页面
 * 展示各工具调用次数/错误数/耗时，并在重启后从 Room 恢复。
 */
@Entity(tableName = "mcp_tool_stats")
data class McpToolStat(
    @PrimaryKey
    @ColumnInfo(name = "tool")
    val tool: String,

    @ColumnInfo(name = "calls")
    val calls: Long,

    @ColumnInfo(name = "errors")
    val errors: Long,

    @ColumnInfo(name = "total_ms")
    val totalMs: Long,

    @ColumnInfo(name = "max_ms")
    val maxMs: Long,

    @ColumnInfo(name = "last_at")
    val lastAt: Long
)