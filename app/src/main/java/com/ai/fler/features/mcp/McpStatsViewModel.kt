package com.ai.fler.features.mcp

import androidx.lifecycle.ViewModel
import com.ai.fler.core.mcp.McpCallStats
import com.ai.fler.core.mcp.McpCallStatsState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * MCP 调用统计页 ViewModel。
 *
 * 直接暴露 [McpCallStats.state]，并提供清零入口。
 */
@HiltViewModel
class McpStatsViewModel @Inject constructor(
    private val stats: McpCallStats,
) : ViewModel() {

    val state: StateFlow<McpCallStatsState> = stats.state

    fun clear() {
        stats.clear()
    }
}