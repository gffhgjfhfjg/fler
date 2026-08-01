package com.ai.fler.features.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.mcp.McpLogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MCP 日志页：展示服务器连接 / 工具调用 / 错误日志。
 *
 * - 新日志自动滚底
 * - 级别过滤（全部 / 信息 / 警告 / 错误）
 * - 清空按钮
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpLogScreen(
    onBack: () -> Unit,
    viewModel: McpLogViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val filtered = remember(entries, filter) {
        if (filter == "ALL") entries else entries.filter { it.level == filter }
    }

    // 新日志自动滚底
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 日志") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.clear() }) {
                        Icon(Icons.Default.Delete, contentDescription = "清空")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("ALL" to "全部", "I" to "信息", "W" to "警告", "E" to "错误").forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { viewModel.setFilter(value) },
                        label = { Text(label) }
                    )
                }
            }

            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "暂无日志",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(filtered, key = { it.seq }) { entry ->
                        McpLogRow(entry)
                    }
                }
            }
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
private fun McpLogRow(entry: McpLogEntry) {
    val levelColor = when (entry.level) {
        "E" -> Color(0xFFD32F2F)
        "W" -> Color(0xFFF9A825)
        "I" -> Color(0xFF1976D2)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "[${entry.level}]",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = levelColor,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = timeFormat.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
