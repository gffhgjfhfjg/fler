package com.ai.fler.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.feature.settings.SettingsViewModel

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
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val mcpState by viewModel.mcpState.collectAsStateWithLifecycle()

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
                    onStart = { viewModel.mcpStartServer() },
                    onStop = { viewModel.mcpStopServer() },
                    onOpenLog = onOpenLog,
                )
            }
        }
    }
}
