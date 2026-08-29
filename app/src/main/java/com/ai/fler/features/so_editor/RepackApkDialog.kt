package com.ai.fler.features.so_editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * 「回打 APK」选项弹窗。
 *
 * 展示回打前置信息（源 APK / 目标条目 / 补丁数），
 * 提供签名开关、签名方案（v1/v2/v3）与签名密钥（内置 debug / 自定义导入）选择。
 *
 * 点击「选择位置并回打」后由调用方拉起 SAF CreateDocument，
 * URI 返回后调用 [onRepack]（弹窗保持打开以显示进度）。
 */
@Composable
fun RepackApkDialog(
    info: SoEditorViewModel.RepackInfo,
    state: SoEditorViewModel.RepackState,
    hasCustomKey: Boolean,
    onImportKey: () -> Unit,
    onRepack: (options: RepackSelection) -> Unit,
    onDismiss: () -> Unit,
) {
    // rememberSaveable：旋转屏幕后保留用户选择
    var sign by rememberSaveable { mutableStateOf(true) }
    var v1 by rememberSaveable { mutableStateOf(true) }
    var v2 by rememberSaveable { mutableStateOf(true) }
    var v3 by rememberSaveable { mutableStateOf(true) }
    var useCustomKey by rememberSaveable { mutableStateOf(false) }
    var alias by rememberSaveable { mutableStateOf("") }
    var storePass by rememberSaveable { mutableStateOf("") }
    var keyPass by rememberSaveable { mutableStateOf("") }

    val canStart = info.available && !state.running

    AlertDialog(
        onDismissRequest = { if (!state.running) onDismiss() },
        title = { Text("回打 APK") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ---------- 前置信息 ----------
                if (info.loading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp
                        )
                        Text("定位源 APK…")
                    }
                } else if (!info.available) {
                    Text(
                        info.reason.ifBlank { "当前不可回打" },
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    InfoRow("源 APK", "${info.apkName}（${formatSize(info.apkSize)}）")
                    InfoRow("替换条目", info.soEntryName, mono = true)
                    InfoRow("补丁数", "${info.patchCount} 处")
                }

                HorizontalDivider()

                // ---------- 签名开关 ----------
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("重签名", fontWeight = FontWeight.Medium)
                        Text(
                            "不签名则输出未签名 APK（无法直接安装）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = sign, onCheckedChange = { sign = it }, enabled = canStart)
                }

                if (sign) {
                    // ---------- 签名方案 ----------
                    Text("签名方案", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth()) {
                        SchemeCheck("v1 (JAR)", v1, { v1 = it }, canStart, subtitle = "Android <7")
                        SchemeCheck("v2", v2, { v2 = it }, canStart, subtitle = "Android 7+")
                        SchemeCheck("v3", v3, { v3 = it }, canStart, subtitle = "Android 9+")
                    }

                    HorizontalDivider()

                    // ---------- 密钥选择 ----------
                    Text("签名密钥", style = MaterialTheme.typography.labelMedium)
                    KeyOption(
                        title = "内置 debug 密钥",
                        subtitle = "标准 Android debug 密钥（android/androiddebugkey）",
                        selected = !useCustomKey,
                        enabled = canStart,
                        icon = { Icon(Icons.Default.Key, null) }
                    ) { useCustomKey = false }
                    KeyOption(
                        title = "自定义密钥",
                        subtitle = if (hasCustomKey) "已导入，选择后回打时使用" else "未导入（PKCS12，JKS 需先转换）",
                        selected = useCustomKey,
                        enabled = canStart && hasCustomKey,
                        icon = { Icon(Icons.Default.Archive, null) }
                    ) { useCustomKey = true }

                    if (useCustomKey && hasCustomKey) {
                        OutlinedTextField(
                            value = alias,
                            onValueChange = { alias = it },
                            label = { Text("别名（空=自动）") },
                            singleLine = true,
                            enabled = canStart,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = storePass,
                            onValueChange = { storePass = it },
                            label = { Text("密钥库密码") },
                            singleLine = true,
                            enabled = canStart,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = keyPass,
                            onValueChange = { keyPass = it },
                            label = { Text("密钥密码（空=同密钥库密码）") },
                            singleLine = true,
                            enabled = canStart,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    TextButton(onClick = onImportKey, enabled = canStart) {
                        Text(if (hasCustomKey) "重新导入密钥库…" else "导入密钥库（.p12/.keystore）…")
                    }
                }

                // ---------- 执行中 / 错误 ----------
                if (state.running) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        state.stage,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace
                    )
                }
                if (state.error != null) {
                    Text(
                        state.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canStart,
                onClick = {
                    onRepack(
                        RepackSelection(
                            suggestedName = suggestedApkName(info.apkName),
                            sign = sign,
                            v1 = v1,
                            v2 = v2,
                            v3 = v3,
                            useCustomKey = useCustomKey && hasCustomKey,
                            alias = alias,
                            storePass = storePass,
                            keyPass = keyPass,
                        )
                    )
                }
            ) { Text("选择位置并回打") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !state.running) { Text("关闭") }
        }
    )
}

/** 回打选项（弹窗 → Screen 的传递载体）。 */
data class RepackSelection(
    val suggestedName: String,
    val sign: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val useCustomKey: Boolean,
    val alias: String,
    val storePass: String,
    val keyPass: String,
)

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null
        )
    }
}

@Composable
private fun SchemeCheck(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    subtitle: String? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun KeyOption(
    title: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        icon()
    }
}

private fun suggestedApkName(apkName: String): String {
    val base = apkName.removeSuffix(".apk")
    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(java.util.Date())
    return "${base}_patched_$ts.apk"
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.1fGB".format(bytes / 1024.0 / 1024 / 1024)
    bytes >= 1024 * 1024 -> "%.1fMB".format(bytes / 1024.0 / 1024)
    bytes >= 1024 -> "%.0fKB".format(bytes / 1024.0)
    else -> "${bytes}B"
}
