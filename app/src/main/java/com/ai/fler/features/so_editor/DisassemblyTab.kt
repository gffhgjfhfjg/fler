package com.ai.fler.features.so_editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.jni.DisasmInstruction
import kotlinx.coroutines.launch

/**
 * 汇编 Tab（显示反汇编结果 + 支持汇编指令编辑）。
 *
 * 展示 ARM64 反汇编指令，支持：
 * - 三列布局：地址 | 字节码 | 指令 + 注释
 * - 函数标签导航
 * - 搜索高亮
 * - **点击指令行直接编辑汇编文本**（如把 MOV W0,#0 改成 MOV W0,#1），
 *   内部调用 [SoEditorViewModel.applyInstructionPatch] 汇编为机器码写入文件。
 *
 * Tab 名称为「汇编」而非「反汇编」——因为这里既能查看反汇编结果，
 * 又能直接编辑汇编指令（[InstructionEditDialog]），「汇编」更能体现可编辑性。
 *
 * @param isMethodMode 方法编辑模式：true 时隐藏上下页/地址跳转导航，
 *   只展示当前方法范围内的指令（从 ASM 浏览跳转进来时为 true）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisassemblyTab(
    viewModel: SoEditorViewModel,
    modifier: Modifier = Modifier,
    isMethodMode: Boolean = false,
    onInstructionClick: (Long) -> Unit = {}
) {
    val disassemblyData by viewModel.disassemblyData.collectAsStateWithLifecycle()
    val selectedOffset by viewModel.selectedOffset.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var inputAddress by remember { mutableStateOf(selectedOffset.toString()) }
    var searchQuery by remember { mutableStateOf("") }
    // 正在编辑的指令（null 表示对话框未打开）
    var editingInstruction by remember { mutableStateOf<DisasmInstruction?>(null) }

    // 初始加载（非方法模式才自动加载默认页；方法模式由调用方主动 loadDisassembly）
    LaunchedEffect(Unit) {
        if (!isMethodMode && disassemblyData.instructions.isEmpty()) {
            viewModel.loadDisassembly(selectedOffset)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 导航栏（方法模式下隐藏地址跳转与翻页）
        DisassemblyNavigationBar(
            inputAddress = inputAddress,
            onInputAddressChange = { inputAddress = it },
            onJumpToAddress = {
                val address = inputAddress.toLongOrNull(16) ?: inputAddress.toLongOrNull() ?: 0L
                viewModel.loadDisassembly(address)
            },
            onPrevPage = {
                viewModel.loadDisassembly(disassemblyData.baseAddress - 4096)
            },
            onNextPage = {
                viewModel.loadDisassembly(disassemblyData.baseAddress + 4096)
            },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            showNavigation = !isMethodMode
        )

        // 反汇编数据
        when {
            disassemblyData.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            disassemblyData.instructions.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isMethodMode) "该方法无可汇编字节（检查文件偏移或方法长度）" else "暂无汇编数据",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                DisassemblyListView(
                    instructions = disassemblyData.instructions,
                    searchQuery = searchQuery,
                    highlightAddress = disassemblyData.highlightAddress,
                    onInstructionClick = { instruction ->
                        // 1. 通知外部（设置 patchAddress 等）
                        onInstructionClick(instruction.address)
                        // 2. 打开汇编编辑对话框
                        editingInstruction = instruction
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    // 汇编指令编辑对话框
    editingInstruction?.let { instruction ->
        InstructionEditDialog(
            instruction = instruction,
            // 用 Capstone cs_asm 实时校验编码（需传入指令地址，分支偏移量依赖它）
            onEncode = { asmText -> viewModel.assembleInstruction(asmText, instruction.address) },
            onDismiss = { editingInstruction = null },
            onApply = { asmText ->
                // 解析 "MOV W0, #1" -> instruction="MOV", args="W0, #1"
                val parsed = parseAsmText(asmText)
                if (parsed == null) {
                    false
                } else {
                    val (inst, args) = parsed
                    scope.launch {
                        val ok = viewModel.applyInstructionPatch(
                            offset = instruction.address,
                            instruction = inst,
                            args = args
                        )
                        if (ok) {
                            // 高亮被修改的指令，并用原 loadedSize 刷新（方法模式不会变成整 SO）
                            viewModel.setHighlightAddress(instruction.address)
                            viewModel.loadDisassembly(
                                disassemblyData.baseAddress,
                                disassemblyData.loadedSize.takeIf { it > 0 }
                                    ?: disassemblyData.instructions.size.toLong() * 4L
                            )
                        }
                        editingInstruction = null
                    }
                    true
                }
            }
        )
    }
}

/**
 * 解析汇编文本为 (指令名, 操作数)。
 *
 * 支持：
 * - "MOV W0, #1" → ("MOV", "W0, #1")
 * - "RET" → ("RET", "")
 * - "nop" → ("NOP", "")
 *
 * 首个空白（空格/制表符）作为指令名与操作数的分隔符；无法识别时返回 null。
 */
private fun parseAsmText(text: String): Pair<String, String>? {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return null
    val firstSpace = trimmed.indexOfFirst { it == ' ' || it == '\t' }
    return if (firstSpace < 0) {
        trimmed.uppercase() to ""
    } else {
        trimmed.substring(0, firstSpace).uppercase() to trimmed.substring(firstSpace + 1).trim()
    }
}

/**
 * 校验汇编文本语法并返回预览信息。
 *
 * 统一用 Capstone cs_asm 编码（经 [SoEditorViewModel.assembleInstruction]）。
 *
 * 返回：
 * - null：输入为空或无法解析
 * - ValidationResult：包含指令名、操作数、预编码字节（若可编码）或错误信息
 */
private data class ValidationResult(
    val instruction: String,
    val args: String,
    val encodedBytes: ByteArray? = null,
    val errorMessage: String? = null
)

private fun validateAsmText(
    text: String,
    encode: (String) -> ByteArray?
): ValidationResult? {
    val parsed = parseAsmText(text) ?: return null
    val (inst, args) = parsed
    // 用 Capstone cs_asm 编码预览
    return try {
        val bytes = encode(text.trim())
        if (bytes == null || bytes.isEmpty()) {
            ValidationResult(inst, args, errorMessage = "Capstone 无法编码该指令")
        } else {
            ValidationResult(inst, args, encodedBytes = bytes)
        }
    } catch (e: Exception) {
        ValidationResult(inst, args, errorMessage = e.message)
    }
}

@Composable
private fun InstructionEditDialog(
    instruction: DisasmInstruction,
    onEncode: (String) -> ByteArray?,
    onDismiss: () -> Unit,
    onApply: (String) -> Boolean
) {
    // 预填当前指令文本（如 "MOV W0, #0"）
    val initialText = buildString {
        append(instruction.mnemonic.uppercase())
        if (instruction.opStr.isNotBlank()) {
            append(' ')
            append(instruction.opStr)
        }
    }
    var input by remember { mutableStateOf(initialText) }

    // 实时校验（onEncode 依赖 address，地址在对话框生命周期内不变）
    val validation = remember(input, instruction.address) {
        if (input.isBlank()) null else validateAsmText(input, onEncode)
    }
    val canApply = validation?.encodedBytes != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑汇编指令") },
        text = {
            Column {
                Text(
                    text = "地址: 0x${instruction.address.toString(16).uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("汇编指令") },
                    placeholder = { Text("如 MOV W0, #1 或 RET") },
                    singleLine = true,
                    isError = validation?.errorMessage != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 原指令
                Text(
                    text = "原指令: $initialText",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))

                // 实时校验结果
                when {
                    input.isBlank() -> {
                        Text(
                            text = "提示：指令名大小写不敏感；支持 NOP / RET / MOV / ADD / SUB / B / BL 等",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                    validation?.errorMessage != null -> {
                        // 语法错误提示
                        Text(
                            text = "✗ ${validation.errorMessage}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    validation?.encodedBytes != null -> {
                        // 编码预览
                        val hexBytes = validation.encodedBytes!!.joinToString(" ") { byte ->
                            byte.toUByte().toString(16).uppercase().padStart(2, '0')
                        }
                        Text(
                            text = "✓ 将写入: $hexBytes",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(input) },
                enabled = canApply
            ) {
                Text("汇编并应用")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
private fun DisassemblyNavigationBar(
    inputAddress: String,
    onInputAddressChange: (String) -> Unit,
    onJumpToAddress: () -> Unit,
    onPrevPage: () -> Unit,
    onNextPage: () -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    showNavigation: Boolean = true
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (showNavigation) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrevPage,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowLeft,
                        contentDescription = "上一页"
                    )
                }

                OutlinedTextField(
                    value = inputAddress,
                    onValueChange = onInputAddressChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("地址 (hex 或 dec)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                )

                IconButton(
                    onClick = onJumpToAddress,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "跳转"
                    )
                }

                IconButton(
                    onClick = onNextPage,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = "下一页"
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("搜索指令...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
        }
    }
}

@Composable
private fun DisassemblyListView(
    instructions: List<DisasmInstruction>,
    searchQuery: String,
    highlightAddress: Long?,
    onInstructionClick: (DisasmInstruction) -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredInstructions = if (searchQuery.isBlank()) {
        instructions.map { it to false }
    } else {
        instructions.map { instruction ->
            val matches = instruction.mnemonic.contains(searchQuery, ignoreCase = true) ||
                instruction.opStr.contains(searchQuery, ignoreCase = true)
            instruction to matches
        }
    }

    LazyColumn(
        modifier = modifier
            .horizontalScroll(rememberScrollState()),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
    ) {
        items(
            items = filteredInstructions,
            // 地址在反汇编流中唯一；用 (address, mnemonic).hashCode() 作为 key 会哈希碰撞
            // 导致 LazyColumn 抛 "Key was already used" 崩溃
            key = { it.first.address }
        ) { (instruction, isMatch) ->
            DisassemblyRow(
                instruction = instruction,
                isMatch = isMatch,
                isHighlighted = highlightAddress != null && instruction.address == highlightAddress,
                onClick = { onInstructionClick(instruction) }
            )
        }
    }
}

@Composable
private fun DisassemblyRow(
    instruction: DisasmInstruction,
    isMatch: Boolean,
    isHighlighted: Boolean,
    onClick: () -> Unit
) {
    // 高亮（被修改/撤销）优先级 > 搜索匹配
    val backgroundColor = when {
        isHighlighted -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        isMatch -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 地址列
        Text(
            text = "0x${instruction.address.toString(16).uppercase().padStart(16, '0')}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isHighlighted) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(130.dp)
        )

        // 字节码列
        Text(
            text = instruction.bytes.joinToString(" ") { byte ->
                byte.toUByte().toString(16).uppercase().padStart(2, '0')
            },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isHighlighted) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(120.dp)
        )

        // 指令列
        Row(modifier = Modifier.padding(start = 16.dp)) {
            Text(
                text = instruction.mnemonic.uppercase(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = when {
                    isHighlighted -> MaterialTheme.colorScheme.error
                    isMatch -> MaterialTheme.colorScheme.primary
                    // 分支指令用不同颜色
                    instruction.mnemonic.startsWith("b") || instruction.mnemonic == "ret" -> Color(0xFFFF9800)
                    instruction.mnemonic.startsWith("csel") -> Color(0xFF2196F3)
                    else -> MaterialTheme.colorScheme.onSurface
                }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = instruction.opStr,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = if (isHighlighted) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (isMatch) 1.0f else 0.8f
                        )
            )
        }
    }
}
