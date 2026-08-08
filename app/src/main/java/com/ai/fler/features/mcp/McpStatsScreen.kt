package com.ai.fler.features.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.mcp.McpCallStatsState
import com.ai.fler.data.entity.McpToolStat

private val CATEGORIES = listOf(
    "全部" to null,
    "分析/ELF" to "core",
    "引擎" to "engine",
    "仿真" to "emu",
    "补丁" to "patch",
)

/** 按工具名前缀归类（用于统计页过滤）。 */
private fun categoryOf(tool: String): String = when {
    tool.startsWith("engine_") -> "engine"
    tool.startsWith("emu_") -> "emu"
    tool == "patch_bytes" || tool == "patch_instruction" ||
        tool == "undo_patch" || tool == "list_patches" || tool == "export_patched_so" -> "patch"
    else -> "core"
}

/**
 * MCP 调用统计页：汇总工具/错误/耗时 + 按工具聚合明细。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpStatsScreen(
    onBack: () -> Unit = {},
    viewModel: McpStatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf("全部") }
    var showClearConfirm by rememberSaveable { mutableStateOf(false) }

    val tools = remember(state, selected) {
        if (selected == "全部") state.perTool
        else state.perTool.filter { categoryOf(it.tool) == selected }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 调用统计") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.totalCalls > 0) {
                        IconButton(onClick = { showClearConfirm = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "清零统计")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SummaryCard(state) }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CATEGORIES.forEach { (label, cat) ->
                        FilterChip(
                            selected = selected == label,
                            onClick = { selected = label },
                            label = { Text(label) },
                        )
                    }
                }
            }

            if (tools.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(top = 64.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (state.totalCalls == 0L) "暂无工具调用记录" else "该分类暂无记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(tools, key = { it.tool }) { tool ->
                    ToolStatRow(tool)
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("清零调用统计") },
            text = { Text("将清空所有工具的调用次数、错误数与耗时记录（内存 + 数据库），确定？") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    showClearConfirm = false
                }) { Text("清零") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SummaryCard(state: McpCallStatsState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "调用汇总",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                StatCell("总调用", state.totalCalls.toString(), Modifier.weight(1f))
                StatCell("错误", state.totalErrors.toString(), Modifier.weight(1f))
                StatCell("成功率", "${(state.successRate * 100).toInt()}%", Modifier.weight(1f))
            }
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                StatCell("平均耗时", "${state.avgMs} ms", Modifier.weight(1f))
                StatCell("最长耗时", "${state.maxMs} ms", Modifier.weight(1f))
                StatCell("工具数", state.perTool.size.toString(), Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ToolStatRow(tool: McpToolStat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tool.tool,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "错误 ${tool.errors} · 平均 ${if (tool.calls > 0) tool.totalMs / tool.calls else 0} ms · 最长 ${tool.maxMs} ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${tool.calls} 次",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}