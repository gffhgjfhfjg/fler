package com.ai.fler.features.mcp

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.frida.FridaEngine
import com.ai.fler.core.frida.TargetLogCollector
import com.ai.fler.core.log.AppLogEntry
import com.ai.fler.core.mcp.McpLogEntry
import org.json.JSONObject

/**
 * 日志页：四 Tab。
 *
 * 1. MCP 日志：MCP 服务器连接 / 工具调用 / 错误（[McpLogger] 内存队列）
 * 2. 应用日志：fler 自身各模块输出（引擎下载、Flutter 解析、SO 分析等，
 *    [AppLogger] 内存队列）
 * 3. Frida：hook 命中 / 脚本 send() 事件（[FridaEngine] 事件环形缓冲）
 * 4. 目标应用：被 hook 进程的 logcat（attach/spawn 后自动采集，
 *    [TargetLogCollector] 环形缓冲）
 *
 * 共通交互：级别过滤（按 Tab）、新日志自动滚底、顶栏清空按钮（作用于当前 Tab）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpLogScreen(
    onBack: (() -> Unit)? = null,
    viewModel: McpLogViewModel = hiltViewModel(),
    appLogViewModel: AppLogViewModel = hiltViewModel(),
    fridaLogViewModel: FridaLogViewModel = hiltViewModel(),
    targetLogViewModel: TargetLogViewModel = hiltViewModel(),
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        when (selectedTab) {
                            0 -> viewModel.clear()
                            1 -> appLogViewModel.clear()
                            2 -> fridaLogViewModel.clear()
                            else -> targetLogViewModel.clear()
                        }
                    }) {
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
            TabRow(selectedTabIndex = selectedTab) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("MCP 日志") }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("应用日志") }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text("Frida") }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text("目标应用") }
                )
            }

            when (selectedTab) {
                0 -> McpLogContent(viewModel)
                1 -> AppLogContent(appLogViewModel)
                2 -> FridaLogContent(fridaLogViewModel)
                else -> TargetLogContent(targetLogViewModel)
            }
        }
    }
}

// ==================================================================
// Tab 1：MCP 日志
// ==================================================================

@Composable
private fun McpLogContent(viewModel: McpLogViewModel) {
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

    Column(modifier = Modifier.fillMaxSize()) {
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

// ==================================================================
// Tab 2：应用日志（fler 自身模块输出）
// ==================================================================

@Composable
private fun AppLogContent(viewModel: AppLogViewModel) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val filtered = remember(entries, filter, query) {
        var list = if (filter == "ALL") entries else entries.filter { it.level == filter }
        if (query.isNotBlank()) {
            list = list.filter {
                it.tag.contains(query, true) || it.message.contains(query, true)
            }
        }
        list
    }

    // 新日志自动滚底
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL" to "全部", "D" to "调试", "I" to "信息", "W" to "警告", "E" to "错误")
                .forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { viewModel.setFilter(value) },
                        label = { Text(label) }
                    )
                }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(52.dp),
            placeholder = { Text("搜索 tag 或内容…", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (entries.isEmpty()) "暂无日志" else "无匹配日志",
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
                    AppLogRow(entry)
                }
            }
        }
    }
}

// ==================================================================
// Tab 3：Frida 事件日志（hook 命中 / 脚本 send()）
// ==================================================================

@Composable
private fun FridaLogContent(viewModel: FridaLogViewModel) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // 新事件自动滚底
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.scrollToItem(entries.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "暂无 Frida 事件\nattach 目标并加载 hook 脚本后，命中/脚本输出在此显示",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(entries, key = { it.ts.toString() + it.scriptHandle + it.json.hashCode() }) { entry ->
                    FridaLogRow(entry)
                }
            }
        }
    }
}

// ==================================================================
// Tab 4：目标应用日志（被 hook 进程的 logcat）
// ==================================================================

@Composable
private fun TargetLogContent(viewModel: TargetLogViewModel) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val activePid by viewModel.activePid.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val filtered = remember(entries, filter, query) {
        var list = if (filter == "ALL") entries else entries.filter { it.level == filter }
        if (query.isNotBlank()) {
            list = list.filter {
                it.tag.contains(query, true) || it.message.contains(query, true)
            }
        }
        list
    }

    // 新日志自动滚底
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) {
            listState.scrollToItem(filtered.size - 1)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 采集状态条
        Text(
            text = if (activePid > 0) "采集中 · pid=$activePid" else "未采集：attach/spawn 目标后自动开始",
            style = MaterialTheme.typography.labelSmall,
            color = if (activePid > 0) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("ALL" to "全部", "E" to "错误", "W" to "警告", "I" to "信息", "D" to "调试", "V" to "Verbose")
                .forEach { (value, label) ->
                    FilterChip(
                        selected = filter == value,
                        onClick = { viewModel.setFilter(value) },
                        label = { Text(label) }
                    )
                }
        }

        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.setQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(52.dp),
            placeholder = { Text("搜索 tag 或内容…", style = MaterialTheme.typography.bodySmall) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
        )

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (entries.isEmpty()) "暂无目标应用日志" else "无匹配日志",
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
                    TargetLogRow(entry)
                }
            }
        }
    }
}

// ==================================================================
// 日志行
// ==================================================================

private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun levelColor(level: String): Color = when (level) {
    "E", "F" -> Color(0xFFD32F2F)
    "W" -> Color(0xFFF9A825)
    "I" -> Color(0xFF1976D2)
    "D" -> Color(0xFF388E3C)
    else -> Color(0xFF9E9E9E)
}

@Composable
private fun McpLogRow(entry: McpLogEntry) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Text(
                text = "[${entry.level}]",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = levelColor(entry.level),
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
        // 请求日志：第二行展示方法名 + 解析后的参数 JSON
        if (entry.method != null) {
            val paramsText = entry.paramsJson?.takeIf { it.isNotBlank() } ?: "{}"
            Text(
                text = "  → $paramsText",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, top = 1.dp)
            )
        }
    }
}

@Composable
private fun AppLogRow(entry: AppLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.level,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = levelColor(entry.level),
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = remember(entry.timestamp) {
                SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                    .format(Date(entry.timestamp))
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = entry.tag,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun FridaLogRow(entry: FridaEngine.FridaEvent) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            // 事件类型（enter/leave/hook/system/…），未知则显示 script 句柄
            val type = remember(entry.json) {
                runCatching { JSONObject(entry.json).optString("type") }.getOrNull().orEmpty()
                    .ifBlank { "msg" }
            }
            Text(
                text = type,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = levelColor(when (type) {
                    "error", "leave" -> "E"
                    "enter", "hook" -> "I"
                    else -> "D"
                }),
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = timeFormat.format(Date(entry.ts)),
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(
                text = "s=${entry.sessionHandle} scr=${entry.scriptHandle}",
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Text(
            text = entry.json,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp, top = 1.dp)
        )
    }
}

@Composable
private fun TargetLogRow(entry: TargetLogCollector.TargetLogEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = entry.level,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = levelColor(entry.level),
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = remember(entry.timestamp) {
                SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
                    .format(Date(entry.timestamp))
            },
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = entry.tag,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 6.dp)
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
