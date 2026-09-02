package com.ai.fler.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.ai.fler.core.mcp.McpTunnelConfig
import com.ai.fler.feature.settings.McpTunnelUiState

/**
 * MCP 外网隧道设置卡片：开关 / 隧道方式（公网中继·自建 SSH）/ 连接参数 / 外网 URL。
 *
 * 隧道随 MCP 服务器自动启停，无需单独的启停按钮。
 */
@Composable
fun McpTunnelCard(
    state: McpTunnelUiState,
    mcpRunning: Boolean,
    tokenBlank: Boolean,
    onSetEnabled: (Boolean) -> Unit,
    onSetProvider: (McpTunnelConfig.Provider) -> Unit,
    onSetHost: (String) -> Unit,
    onSetSshPort: (Int) -> Unit,
    onSetUsername: (String) -> Unit,
    onSetPassword: (String) -> Unit,
    onSetRemotePort: (Int) -> Unit,
) {
    // 输入框本地态：失焦/变更时由 onXXX 持久化（与 Embedding 卡片一致）
    var hostInput by remember(state.host) { mutableStateOf(state.host) }
    var sshPortInput by remember(state.sshPort) { mutableStateOf(state.sshPort.toString()) }
    var userInput by remember(state.username) { mutableStateOf(state.username) }
    var passwordInput by remember(state.password) { mutableStateOf(state.password) }
    var remotePortInput by remember(state.remotePort) { mutableStateOf(state.remotePort.toString()) }

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
                    text = "外网隧道",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = when {
                        state.isRunning -> "运行中"
                        state.isConnecting -> "连接中…"
                        state.enabled -> "已启用"
                        else -> "已关闭"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when {
                        state.isRunning -> MaterialTheme.colorScheme.primary
                        state.isConnecting || state.enabled -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Text(
                text = "通过 SSH 反向隧道把本机 MCP 映射到公网，无需公网 IP / 端口映射；随 MCP 服务器自动启停",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 安全面提示：暴露公网务必设 Token
            if (state.enabled && tokenBlank) {
                Text(
                    text = "警告：MCP 未设置 Token，公网任何人拿到地址即可调用（含补丁工具）。请先在上方设置 Token",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (state.isRunning && state.publicUrl.isNotBlank()) {
                TunnelUrlLine("外网 /mcp", state.publicUrl.trimEnd('/') + "/mcp")
                TunnelUrlLine("外网 /sse", state.publicUrl.trimEnd('/') + "/sse")
                TunnelUrlLine("外网 /export", state.publicUrl.trimEnd('/') + "/export")
            } else if (state.enabled && !mcpRunning) {
                Text(
                    text = "MCP 服务器未运行：隧道将在服务器启动后自动连接",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

            // 开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("启用隧道", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "两种绑定模式下均可用（转发走本机回环，服务器监听不变）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = state.enabled, onCheckedChange = onSetEnabled)
            }

            // 隧道方式
            Text("隧道方式", style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TunnelProviderOption(
                    "公网中继",
                    McpTunnelConfig.Provider.PUBLIC,
                    state.provider,
                    onSetProvider
                )
                TunnelProviderOption(
                    "自建 SSH",
                    McpTunnelConfig.Provider.CUSTOM,
                    state.provider,
                    onSetProvider
                )
            }

            if (state.provider == McpTunnelConfig.Provider.PUBLIC) {
                Text(
                    text = "免费公共中继 localhost.run，无需注册：连接后自动分配随机 " +
                        "https://xxx.lhr.life 地址（每次重连可能变化）。第三方公共服务，" +
                        "国内网络可能较慢或不稳定，正式使用建议自建",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedTextField(
                    value = hostInput,
                    onValueChange = { hostInput = it; onSetHost(it) },
                    singleLine = true,
                    label = { Text("服务器地址") },
                    placeholder = { Text("vps.example.com") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = sshPortInput,
                        onValueChange = {
                            sshPortInput = it.filter { c -> c.isDigit() }.take(5)
                            sshPortInput.toIntOrNull()?.let(onSetSshPort)
                        },
                        singleLine = true,
                        label = { Text("SSH 端口") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = remotePortInput,
                        onValueChange = {
                            remotePortInput = it.filter { c -> c.isDigit() }.take(5)
                            onSetRemotePort(remotePortInput.toIntOrNull() ?: 0)
                        },
                        singleLine = true,
                        label = { Text("远端端口") },
                        supportingText = { Text("0 = 随机分配") },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = userInput,
                        onValueChange = { userInput = it; onSetUsername(it) },
                        singleLine = true,
                        label = { Text("用户名") },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it; onSetPassword(it) },
                        singleLine = true,
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    text = "需自备 SSH 服务器（sshd_config 设置 GatewayPorts clientspecified 或 yes，" +
                        "并在防火墙/安全组放行远端端口），隧道建立后地址为 http://服务器:远端端口",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TunnelUrlLine(label: String, url: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp)
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun TunnelProviderOption(
    label: String,
    value: McpTunnelConfig.Provider,
    selected: McpTunnelConfig.Provider,
    onSelect: (McpTunnelConfig.Provider) -> Unit,
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
