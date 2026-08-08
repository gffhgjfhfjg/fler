package com.ai.fler.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ai.fler.core.mcp.McpConfig
import com.ai.fler.feature.settings.McpUiState

/**
 * MCP 服务器设置卡片：开关 / 绑定模式 / 端口 / Token / 补丁开关 / 连接 URL。
 */
@Composable
fun McpSettingsCard(
    state: McpUiState,
    onSetBindMode: (McpConfig.BindMode) -> Unit,
    onSetPort: (Int) -> Unit,
    onSetToken: (String) -> Unit,
    onSetPatchEnabled: (Boolean) -> Unit,
    onPickExportFolder: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLog: () -> Unit,
    onOpenTools: () -> Unit = {},
) {
    var tokenInput by remember(state.token) { mutableStateOf(state.token) }
    var portInput by remember(state.port) { mutableStateOf(state.port.toString()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "MCP 服务器",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = if (state.isRunning) "运行中" else "已停止",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state.isRunning) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = "内嵌 MCP 服务器：把 fler 逆向能力开放给 AI 代理（Claude Desktop / MCP 客户端）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (state.isRunning) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "端口 ${state.port} · 连接数 ${state.activeSessions}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (state.localUrl.isNotBlank()) {
                    UrlLine("本机 /mcp", state.localUrl)
                    UrlLine("本机 /sse", state.sseLocalUrl)
                    UrlLine("本机 /export", "http://127.0.0.1:${state.port}/export")
                }
                if (state.lanUrl.isNotBlank()) {
                    UrlLine("局域网 /mcp", state.lanUrl)
                    UrlLine("局域网 /sse", state.sseLanUrl)
                    UrlLine("局域网 /export", exportUrlFrom(state.lanUrl))
                }
                Text(
                    text = "/mcp: MCP Inspector/通用客户端 · /sse: Claude Desktop · /export: 下载导出目录内的 so",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "电脑访问: adb reverse tcp:${state.port} tcp:${state.port}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            if (state.errorMessage != null) {
                Text(
                    text = state.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 绑定模式
            Text("绑定模式", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                BindModeOption("仅本机", McpConfig.BindMode.LOCAL, state.bindMode, onSetBindMode)
                BindModeOption("局域网", McpConfig.BindMode.LAN, state.bindMode, onSetBindMode)
            }
            if (state.bindMode == McpConfig.BindMode.LAN) {
                Text(
                    text = "局域网模式以前台服务保活（常驻通知）；建议设置 Token",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // 端口
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("端口", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it.filter { c -> c.isDigit() }.take(5) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isRunning
                )
            }

            // Token
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Token", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(56.dp))
                OutlinedTextField(
                    value = tokenInput,
                    onValueChange = { tokenInput = it; onSetToken(it) },
                    singleLine = true,
                    placeholder = { Text("留空不鉴权") },
                    modifier = Modifier.weight(1f)
                )
            }

            // 补丁开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("指令补丁工具", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "默认关闭；开启后可远程修改 so（破坏性，由客户端决定调用）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.patchEnabled, onCheckedChange = onSetPatchEnabled)
            }

            // 导出文件夹
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("导出文件夹", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = if (state.exportTreeUri.isBlank())
                            "未设置（默认 App 缓存 cacheDir/so_export）"
                        else
                            "patch 后的 so 将导出到此目录",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2
                    )
                }
                OutlinedButton(onClick = onPickExportFolder) {
                    Text(if (state.exportTreeUri.isBlank()) "选择" else "更换")
                }
            }

            // 应用端口
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSetPort(portInput.toIntOrNull() ?: McpConfig.DEFAULT_PORT) },
                    enabled = !state.isRunning
                ) { Text("应用端口") }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 启停按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onStart,
                    enabled = !state.isRunning
                ) { Text("启动") }
                OutlinedButton(
                    onClick = onStop,
                    enabled = state.isRunning
                ) { Text("停止") }
                OutlinedButton(
                    onClick = onOpenLog
                ) { Text("查看日志") }
                OutlinedButton(
                    onClick = onOpenTools
                ) { Text("工具列表") }
            }
        }
    }
}

@Composable
private fun UrlLine(label: String, url: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace
        )
    }
}

/** 由 /mcp 地址推导 /export 下载地址（保留 host:port）。 */
private fun exportUrlFrom(mcpUrl: String): String {
    val host = mcpUrl.removePrefix("http://").substringBefore('/')
    return "http://$host/export"
}

@Composable
private fun BindModeOption(
    label: String,
    value: McpConfig.BindMode,
    selected: McpConfig.BindMode,
    onSelect: (McpConfig.BindMode) -> Unit,
) {
    Row(
        modifier = Modifier
            .selectable(
                selected = selected == value,
                onClick = { onSelect(value) }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected == value, onClick = { onSelect(value) })
        Spacer(modifier = Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
