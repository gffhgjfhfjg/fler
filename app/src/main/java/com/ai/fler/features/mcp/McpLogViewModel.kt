package com.ai.fler.features.mcp

import androidx.lifecycle.ViewModel
import com.ai.fler.core.mcp.McpLogEntry
import com.ai.fler.core.mcp.McpLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * MCP 日志页 ViewModel。
 *
 * 直接暴露 [McpLogger.entries]，并提供级别过滤与清空。
 */
@HiltViewModel
class McpLogViewModel @Inject constructor(
    private val logger: McpLogger,
) : ViewModel() {

    val entries: StateFlow<List<McpLogEntry>> = logger.entries

    private val _filter = MutableStateFlow("ALL")
    val filter: StateFlow<String> = _filter.asStateFlow()

    fun setFilter(value: String) {
        _filter.value = value
    }

    fun clear() {
        logger.clear()
    }
}
