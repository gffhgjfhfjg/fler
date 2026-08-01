package com.ai.fler.features.so_editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * ARM64 指令帮助文档（新手向）。
 *
 * 从汇编指令编辑对话框或汇编导航栏进入，分组展示语法、寄存器、
 * 常用指令、条件码与注意事项，供学习参考。
 */
private data class AsmHelpItem(
    val mnemonic: String,
    val description: String,
    val example: String? = null,
)

private data class AsmHelpSection(
    val title: String,
    val items: List<AsmHelpItem>,
)

private val asmHelpSections = listOf(
    AsmHelpSection(
        "语法总览",
        listOf(
            AsmHelpItem("指令 操作数", "指令名大小写不敏感；Keystone 编码；AArch64 每条指令固定 4 字节", "MOV W0, #1"),
            AsmHelpItem("寄存器", "W0-W30（32 位）、X0-X30（64 位）、SP（栈指针）、LR（返回地址）、PC", "ADD X0, X1, X2"),
            AsmHelpItem("立即数", "# 后跟十进制或 0x 十六进制", "MOV W0, #0x30"),
            AsmHelpItem("内存寻址", "[Xn, #偏移]；Flutter 逆向常用 PP 池 [PP, #偏移]", "LDR X0, [PP, #0x428]"),
        ),
    ),
    AsmHelpSection(
        "数据传送",
        listOf(
            AsmHelpItem("MOV", "寄存器间传送 / 写入立即数", "MOV W0, #1"),
            AsmHelpItem("MOVZ", "把 16 位立即数写入寄存器（其余位清零）", "MOVZ X0, #0x1234"),
            AsmHelpItem("MOVK", "把 16 位立即数写入寄存器（保留其余位）", "MOVK X0, #0xAB, LSL #16"),
        ),
    ),
    AsmHelpSection(
        "算术",
        listOf(
            AsmHelpItem("ADD", "加法（寄存器或立即数）", "ADD X0, X1, #8"),
            AsmHelpItem("SUB", "减法", "SUB X0, X1, #0x20"),
            AsmHelpItem("CMP", "比较（只更新标志位，不写结果）", "CMP X0, #0"),
            AsmHelpItem("NEG", "取负", "NEG X0, X1"),
        ),
    ),
    AsmHelpSection(
        "逻辑",
        listOf(
            AsmHelpItem("AND", "按位与", "AND W0, W1, #0xFF"),
            AsmHelpItem("ORR", "按位或", "ORR X0, X0, X1"),
            AsmHelpItem("EOR", "按位异或", "EOR X0, X0, X1"),
            AsmHelpItem("LSL/LSR", "逻辑左移 / 逻辑右移", "LSL X0, X1, #4"),
        ),
    ),
    AsmHelpSection(
        "加载 / 存储",
        listOf(
            AsmHelpItem("LDR", "从内存加载到寄存器", "LDR X0, [X1, #8]"),
            AsmHelpItem("STR", "把寄存器存储到内存", "STR W0, [SP, #0x10]"),
            AsmHelpItem("LDRB/STRB", "按字节加载 / 存储", "LDRB W0, [X1]"),
            AsmHelpItem("LDP/STP", "成对加载 / 存储（常用于函数序言）", "STP X29, X30, [SP, #-16]!"),
        ),
    ),
    AsmHelpSection(
        "分支",
        listOf(
            AsmHelpItem("B", "无条件跳转（目标为地址）", "B #0x1200"),
            AsmHelpItem("BL", "跳转并把返回地址保存到 LR", "BL #0x1000"),
            AsmHelpItem("B.cond", "条件跳转（见下方条件码）", "BEQ #0x200"),
            AsmHelpItem("CBZ/CBNZ", "寄存器为 0 / 非 0 时跳转", "CBZ X0, #0x400"),
            AsmHelpItem("RET", "返回（跳转到 LR）", "RET"),
        ),
    ),
    AsmHelpSection(
        "其他",
        listOf(
            AsmHelpItem("NOP", "空操作", "NOP"),
            AsmHelpItem("BIC", "位清零", "BIC X0, X1, #0xF"),
            AsmHelpItem("CSEL", "条件选择：条件成立取第 2 个，否则取第 3 个", "CSEL X0, X1, X2, EQ"),
            AsmHelpItem("SXTW", "符号扩展 32 位到 64 位", "SXTW X0, W1"),
        ),
    ),
    AsmHelpSection(
        "条件码",
        listOf(
            AsmHelpItem("EQ / NE", "等于 / 不等于", "CMP X0, #0; BEQ ..."),
            AsmHelpItem("CS / CC", "无符号大于等于 / 小于", "BCC ..."),
            AsmHelpItem("MI / PL", "负数 / 非负", "BMI ..."),
            AsmHelpItem("HI / LS", "无符号大于 / 小于等于", "BHI ..."),
            AsmHelpItem("GE / LT", "有符号大于等于 / 小于", "BGE ..."),
            AsmHelpItem("GT / LE", "有符号大于 / 小于等于", "BLE ..."),
        ),
    ),
)

/** 指令帮助对话框。 */
@Composable
fun AsmHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ARM64 指令帮助") },
        text = {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                items(asmHelpSections) { section ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        section.items.forEach { item ->
                            AsmHelpRow(item)
                        }
                    }
                }
                item {
                    Text(
                        text = "参考：ARM Architecture Reference Manual (AArch64)。" +
                            "修改后请点「保存」，再通过「导出修改后的 SO」取走文件。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("关闭")
            }
        }
    )
}

@Composable
private fun AsmHelpRow(item: AsmHelpItem) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = item.mnemonic,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(96.dp)
        )
        Column {
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (item.example != null) {
                Spacer(Modifier.width(0.dp))
                Text(
                    text = "示例: ${item.example}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
