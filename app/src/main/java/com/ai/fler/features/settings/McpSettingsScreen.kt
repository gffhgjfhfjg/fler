package com.ai.fler.features.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.mcp.McpToolHandlers
import com.ai.fler.feature.settings.SettingsViewModel
import com.ai.fler.ui.components.CardListTile

/**
 * MCP 服务器配置页（二级 Screen，从设置页进入）。
 *
 * 复用 [McpSettingsCard] 的全部配置项：绑定模式 / 端口 / Token / 补丁开关 / 启停 / URL / 日志入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpSettingsScreen(
    onBack: () -> Unit = {},
    onOpenLog: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val mcpState by viewModel.mcpState.collectAsStateWithLifecycle()
    var showToolsDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
            }
            viewModel.mcpSetExportTreeUri(uri.toString())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MCP 服务器") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                McpSettingsCard(
                    state = mcpState,
                    onSetBindMode = { viewModel.mcpSetBindMode(it) },
                    onSetPort = { viewModel.mcpSetPort(it) },
                    onSetToken = { viewModel.mcpSetToken(it) },
                    onSetPatchEnabled = { viewModel.mcpSetPatchEnabled(it) },
                    onSetEmuToolsEnabled = { viewModel.mcpSetEmuToolsEnabled(it) },
                    onPickExportFolder = { folderPicker.launch(null) },
                    onStart = { viewModel.mcpStartServer() },
                    onStop = { viewModel.mcpStopServer() },
                    onOpenLog = onOpenLog,
                    onOpenTools = { showToolsDialog = true },
                )
            }

            item {
                CardListTile(
                    title = "MCP 调用统计",
                    subtitle = "工具调用次数 · 错误 · 耗时（本地持久化）",
                    onClick = onOpenStats,
                )
            }
        }
    }

    // MCP 工具列表弹窗
    if (showToolsDialog) {
        McpToolsDialog(
            tools = viewModel.mcpTools,
            onDismiss = { showToolsDialog = false },
        )
    }
}

@Composable
private fun McpToolsDialog(
    tools: List<McpToolHandlers.McpTool>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Build, contentDescription = null) },
        title = { Text("MCP 工具列表") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(tools, key = { it.name }) { tool ->
                    Column {
                        Text(
                            text = tool.name,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = tool.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        modifier = Modifier.padding(top = 8.dp),
    )
}
