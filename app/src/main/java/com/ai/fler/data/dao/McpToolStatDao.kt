package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.McpToolStat

/**
 * MCP 工具调用统计 DAO。
 */
@Dao
interface McpToolStatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(stat: McpToolStat)

    @Query("SELECT * FROM mcp_tool_stats ORDER BY calls DESC")
    suspend fun getAll(): List<McpToolStat>

    @Query("DELETE FROM mcp_tool_stats")
    suspend fun clear()
}