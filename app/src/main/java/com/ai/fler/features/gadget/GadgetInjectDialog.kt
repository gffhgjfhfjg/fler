package com.ai.fler.features.gadget

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ai.fler.core.service.ApkRepacker

/**
 * 非 root Frida（gadget 注入）弹窗。
 *
 * 流程：选 APK（调用方 SAF 拉起 onPickApk）→ 配置模式/签名 →
 * 「选择位置并注入」（调用方 SAF CreateDocument，回调 onInject）。
 * 弹窗保持打开显示进度与结果。
 */
@Composable
fun GadgetInjectDialog(
    state: GadgetInjectViewModel.UiState,
    savedKeyConfig: ApkRepacker.SavedKeyConfig?,
    onPickApk: () -> Unit,
    onImportKey: () -> Unit,
    onInject: (selection: GadgetInjectViewModel.InjectSelection, suggestedName: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf("listen") }
    var port by remember { mutableStateOf("27042") }
    var onLoadWait by remember { mutableStateOf(true) }
    var sign by remember { mutableStateOf(true) }
    var v1 by remember { mutableStateOf(true) }
    var v2 by remember { mutableStateOf(true) }
    var v3 by remember { mutableStateOf(true) }
    var useCustomKey by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf("") }
    var storePass by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var prefillDone by remember { mutableStateOf(false) }

    LaunchedEffect(savedKeyConfig) {
        if (!prefillDone && savedKeyConfig != null) {
            alias = savedKeyConfig.alias
            storePass = savedKeyConfig.storePassword
            keyPass = savedKeyConfig.keyPassword
            prefillDone = true
        }
    }

    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text("非 root Frida（gadget 注入）") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // APK 选择
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onPickApk, enabled = !state.running) {
                        Text("选择 APK")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = state.pickedApkName ?: "未选择",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                    )
                }

                // 模式
                Text("gadget 交互模式", style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                ModeRow(
                    selected = mode == "listen",
                    title = "listen（attach 调试）",
                    subtitle = "frida-server 兼容接口，FLER/frida CLI 直连"
                        + if (onLoadWait) "；启动冻结至 attach（早期插桩）" else "；启动即放行（后续 attach）",
                    onClick = { mode = "listen" },
                )
                if (mode == "listen") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                            label = { Text("端口") },
                            enabled = !state.running,
                            modifier = Modifier.width(110.dp),
                            singleLine = true,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("on_load", style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.width(8.dp))
                        RadioButton(selected = onLoadWait, enabled = !state.running,
                            onClick = { onLoadWait = true })
                        Text("wait", style = MaterialTheme.typography.bodySmall)
                        RadioButton(selected = !onLoadWait, enabled = !state.running,
                            onClick = { onLoadWait = false })
                        Text("resume", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ModeRow(
                    selected = mode == "script",
                    title = "script（启动自对抗）",
                    subtitle = "加载内置对抗脚本（签名伪装/完整性指纹）后放行，无需 attach",
                    onClick = { mode = "script" },
                )

                // 签名
                Text("重签名", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sign, enabled = !state.running,
                        onCheckedChange = { sign = it })
                    Text("签名")
                    Spacer(modifier = Modifier.width(10.dp))
                    if (sign) {
                        Checkbox(checked = v1, enabled = !state.running, onCheckedChange = { v1 = it })
                        Text("v1")
                        Spacer(modifier = Modifier.width(6.dp))
                        Checkbox(checked = v2, enabled = !state.running, onCheckedChange = { v2 = it })
                        Text("v2")
                        Spacer(modifier = Modifier.width(6.dp))
                        Checkbox(checked = v3, enabled = !state.running, onCheckedChange = { v3 = it })
                        Text("v3")
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useCustomKey, enabled = !state.running,
                        onCheckedChange = { useCustomKey = it })
                    Text("自定义密钥")
                    if (useCustomKey) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = onImportKey, enabled = !state.running) { Text("导入") }
                    }
                }
                if (useCustomKey) {
                    OutlinedTextField(
                        value = alias, onValueChange = { alias = it },
                        label = { Text("别名（空=自动）") }, enabled = !state.running,
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = storePass, onValueChange = { storePass = it },
                        label = { Text("密钥库密码") }, enabled = !state.running,
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                    OutlinedTextField(
                        value = keyPass, onValueChange = { keyPass = it },
                        label = { Text("密钥密码（空=同库密码）") }, enabled = !state.running,
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                    )
                }

                // 进度 / 结果
                if (state.running) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${(state.progress * 100).toInt()}% · ${state.stage}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
                state.success?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace)
                }
                Text(
                    text = "提示：安装前需卸载原 App（签名变化）；listen 模式 attach " +
                        "127.0.0.1:$port（进程名 Gadget）。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            if (state.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.width(18.dp).height(18.dp),
                        strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(state.stage, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                TextButton(
                    onClick = {
                        val base = (state.pickedApkName ?: "app.apk").removeSuffix(".apk")
                        val name = "${base}_gadget.apk"
                        onInject(
                            GadgetInjectViewModel.InjectSelection(
                                mode = mode,
                                port = port.toIntOrNull() ?: 27042,
                                onLoad = if (onLoadWait) "wait" else "resume",
                                sign = sign, v1 = v1, v2 = v2, v3 = v3,
                                useCustomKey = useCustomKey,
                                alias = alias, storePass = storePass, keyPass = keyPass,
                            ),
                            name,
                        )
                    },
                    enabled = state.pickedApkPath != null,
                ) { Text("选择位置并注入") }
            }
        },
        dismissButton = {
            if (!state.running) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        },
    )
}

@Composable
private fun ModeRow(selected: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(top = 6.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
