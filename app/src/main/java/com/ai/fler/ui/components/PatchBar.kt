package com.ai.fler.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 指令补丁栏（Hex/反汇编编辑用）。
 *
 * 显示目标偏移，输入十六进制字节，点击应用补丁。
 * 提供常用 ARM64 指令模板（NOP/RET/MOV W0,#0/BRK），降低新手使用门槛。
 */
@Composable
fun PatchBar(
    address: Long,
    input: String,
    onInputChange: (String) -> Unit,
    onApply: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "偏移: 0x${address.toString(16).uppercase()}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "点击指令可选中补丁地址",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("新字节 (hex)，如 1F 20 03 D5") },
                    singleLine = true,
                )
                TextButton(onClick = onApply) {
                    Text("应用补丁")
                }
            }

            // 常用 ARM64 指令模板（点击即填入字节，新手友好）
            Spacer(modifier = Modifier.height(4.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(Arm64Templates, key = { it.label }) { tpl ->
                    AssistChip(
                        onClick = { onInputChange(tpl.hex) },
                        label = { Text(tpl.label) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        ),
                    )
                }
            }
        }
    }
}

/** ARM64 常用指令模板（字节以 little-endian 顺序排列）。 */
private val Arm64Templates: List<Arm64Template> = listOf(
    Arm64Template("NOP", "1F 20 03 D5", "空指令"),
    Arm64Template("RET", "C0 03 5F D6", "返回（函数结束）"),
    Arm64Template("MOV W0,#0", "00 00 80 52", "返回 0"),
    Arm64Template("MOV W0,#1", "20 00 80 52", "返回 1"),
    Arm64Template("BRK #0", "00 00 20 D4", "断点陷阱"),
    Arm64Template("WFI", "03 20 03 D5", "等待中断（halt）"),
)

private data class Arm64Template(
    val label: String,
    val hex: String,
    val desc: String,
)

/** 解析 "1F 20 03 D5"（可含空格/逗号）为字节数组；输入非法返回空。 */
fun parseHexBytes(input: String): ByteArray {
    val cleaned = input.trim().replace(Regex("[^0-9a-fA-F]"), "")
    if (cleaned.isEmpty() || cleaned.length % 2 != 0) return ByteArray(0)
    return ByteArray(cleaned.length / 2) { i ->
        cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
