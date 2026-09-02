package com.ai.fler.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.mcp.EmbeddingConfig
import com.ai.fler.core.mcp.McpToolHandlers
import com.ai.fler.feature.settings.EmbeddingUiState
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
    val tunnelState by viewModel.tunnelState.collectAsStateWithLifecycle()
    val embeddingState by viewModel.embeddingState.collectAsStateWithLifecycle()
    var showToolsDialog by remember { mutableStateOf(false) }

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
                    onStart = { viewModel.mcpStartServer() },
                    onStop = { viewModel.mcpStopServer() },
                    onOpenLog = onOpenLog,
                    onOpenTools = { showToolsDialog = true },
                )
            }

            item {
                McpTunnelCard(
                    state = tunnelState,
                    mcpRunning = mcpState.isRunning,
                    tokenBlank = mcpState.token.isBlank(),
                    onSetEnabled = { viewModel.tunnelSetEnabled(it) },
                    onSetProvider = { viewModel.tunnelSetProvider(it) },
                    onSetHost = { viewModel.tunnelSetHost(it) },
                    onSetSshPort = { viewModel.tunnelSetSshPort(it) },
                    onSetUsername = { viewModel.tunnelSetUsername(it) },
                    onSetPassword = { viewModel.tunnelSetPassword(it) },
                    onSetRemotePort = { viewModel.tunnelSetRemotePort(it) },
                )
            }

            item {
                CardListTile(
                    title = "MCP 调用统计",
                    subtitle = "工具调用次数 · 错误 · 耗时（本地持久化）",
                    onClick = onOpenStats,
                )
            }

            item {
                EmbeddingSettingsCard(
                    state = embeddingState,
                    onSetApiKey = { viewModel.embeddingSetApiKey(it) },
                    onSetModel = { viewModel.embeddingSetModel(it) },
                    onSetBaseUrl = { viewModel.embeddingSetBaseUrl(it) },
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

/**
 * 语义搜索 embedding 配置卡片（MCP semantic_search 工具用）。
 *
 * 配置 SiliconFlow 兼容 /v1/embeddings API：Key / 模型 / API 地址。
 */
@Composable
private fun EmbeddingSettingsCard(
    state: EmbeddingUiState,
    onSetApiKey: (String) -> Unit,
    onSetModel: (String) -> Unit,
    onSetBaseUrl: (String) -> Unit,
) {
    // 输入框本地态：避免每次按键都写 SharedPreferences；失焦/关闭页面时由 onXXX 持久化
    var apiKeyInput by remember(state.apiKey) { mutableStateOf(state.apiKey) }
    var modelInput by remember(state.model) { mutableStateOf(state.model) }
    var baseUrlInput by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "语义搜索（Embedding）",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = if (state.configured) {
                    "已配置：semantic_search / semantic_index_build 工具可用（构建索引时调用外部 embedding API）"
                } else {
                    "未配置 API Key：semantic_search 等语义搜索工具不可用。配置 SiliconFlow API Key 后可用"
                },
                style = MaterialTheme.typography.labelSmall,
                color = if (state.configured) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )

            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = { apiKeyInput = it; onSetApiKey(it) },
                singleLine = true,
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it; onSetModel(it) },
                singleLine = true,
                label = { Text("模型") },
                placeholder = { Text(EmbeddingConfig.DEFAULT_MODEL) },
                supportingText = { Text("更换模型后已建索引会自动重建（维度需一致）") },
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = baseUrlInput,
                onValueChange = { baseUrlInput = it; onSetBaseUrl(it) },
                singleLine = true,
                label = { Text("API 地址") },
                placeholder = { Text(EmbeddingConfig.DEFAULT_BASE_URL) },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "POST {API地址}/v1/embeddings · 默认 BAAI/bge-m3（1024 维）· 兼容任意 OpenAI 风格 embeddings 接口",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
        }
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
