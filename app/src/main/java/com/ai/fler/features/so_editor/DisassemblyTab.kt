package com.ai.fler.features.so_editor

import androidx.compose.animation.core.Animatable
import com.ai.fler.ui.animation.AnimDuration
import com.ai.fler.ui.animation.AnimEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.analysis.DisasmInstruction
import com.ai.fler.core.analysis.SectionInfo
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
    onInstructionClick: (Long) -> Unit = {},
    /** 长按菜单「断点调试」：在仿真中于该地址下断点并跳转仿真 Tab。入参为文件偏移。 */
    onBreakpointDebug: (Long) -> Unit = {},
    /** 长按菜单「函数调用」：跳转仿真 Tab 并预填该地址。入参为文件偏移。 */
    onCallAtEmulation: (Long) -> Unit = {}
) {
    val disassemblyData by viewModel.disassemblyData.collectAsStateWithLifecycle()
    val selectedOffset by viewModel.selectedOffset.collectAsStateWithLifecycle()
    val patchedOffsets by viewModel.patchedOffsets.collectAsStateWithLifecycle()
    val xrefData by viewModel.xrefData.collectAsStateWithLifecycle()
    val functionOverlay by viewModel.functionOverlay.collectAsStateWithLifecycle()
    val flashTrigger by viewModel.flashTrigger.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var inputAddress by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    // 正在编辑的指令（null 表示对话框未打开）
    var editingInstruction by remember { mutableStateOf<DisasmInstruction?>(null) }
    // 指令帮助对话框
    var showAsmHelp by remember { mutableStateOf(false) }
    // 交叉引用面板
    var showXrefSheet by remember { mutableStateOf(false) }
    // 长按操作菜单目标指令（null 表示菜单未打开）
    var menuInstruction by remember { mutableStateOf<DisasmInstruction?>(null) }
    // 节区跳转：悬浮按钮 → 节区列表对话框
    var showSectionJump by remember { mutableStateOf(false) }
    val editorUiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 初始加载：仅当 selectedOffset == 0（用户未指定目标地址）时自动加载偏移 0 起始的指令；
    // 若用户已通过 onFunctionClick/onSymbolClick 设置了目标地址，则跳过自动加载，
    // 避免与带 highlightAfterLoad 的 loadDisassembly 并发竞争覆盖。
    LaunchedEffect(Unit) {
        if (!isMethodMode && disassemblyData.instructions.isEmpty() && !disassemblyData.isLoading && selectedOffset == 0L) {
            viewModel.loadDisassembly(selectedOffset)
        }
    }

    // 数据更新后，滚动到 highlightAddress 对应的行（用 instructions 作 key，
    // 闪烁 toggle 时 instructions 不变，不会重复触发）
    LaunchedEffect(disassemblyData.instructions) {
        val addr = disassemblyData.highlightAddress ?: return@LaunchedEffect
        val idx = disassemblyData.instructions.indexOfFirst { it.address == addr }
        if (idx >= 0) {
            listState.scrollToItem(idx)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxSize()) {
        // 导航栏（方法模式下隐藏地址跳转与翻页）
        DisassemblyNavigationBar(
            inputAddress = inputAddress,
            onInputAddressChange = { inputAddress = it },
            onJumpToAddress = {
                val raw = inputAddress.toLongOrNull(16) ?: inputAddress.toLongOrNull() ?: 0L
                // resolveJumpAddress：粘贴的虚拟地址（如长按菜单复制的函数地址）超出
                // 文件大小时自动按 vaddr→paddr 换算，避免越界读不到数据
                scope.launch {
                    val address = viewModel.resolveJumpAddress(raw)
                    // 传入 highlightAfterLoad = address 确保：
                    // 1. 加载 512 字节上下文（前文指令），定位更准确
                    // 2. 加载后自动滚动到目标地址行
                    // 3. 触发脉冲高亮动画，让用户清楚看到目标位置
                    viewModel.loadDisassembly(address, highlightAfterLoad = address)
                }
            },
            onPrevPage = {
                viewModel.loadDisassembly(disassemblyData.baseAddress - 4096)
            },
            onNextPage = {
                viewModel.loadDisassembly(disassemblyData.baseAddress + 4096)
            },
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            onOpenHelp = { showAsmHelp = true },
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
                        text = disassemblyData.errorMessage
                            ?: if (isMethodMode) "该方法无可汇编字节（检查文件偏移或方法长度）" else "暂无汇编数据",
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
                    patchedOffsets = patchedOffsets,
                    functionOverlay = functionOverlay,
                    flashTrigger = flashTrigger,
                    listState = listState,
                    onInstructionClick = { instruction ->
                        // 点击 = 编辑汇编指令
                        onInstructionClick(instruction.address)
                        editingInstruction = instruction
                    },
                    onInstructionLongClick = { instruction ->
                        // 长按 = 操作菜单（断点调试 / 交叉引用 / 函数调用）
                        onInstructionClick(instruction.address)
                        menuInstruction = instruction
                    },
                    onLoadMoreBefore = { viewModel.loadMoreBefore() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
            }

            // 节区跳转悬浮按钮（方法模式隐藏，避免破坏方法范围视图）
            if (!isMethodMode) {
                SmallFloatingActionButton(
                    onClick = { showSectionJump = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                ) {
                    Icon(Icons.Default.List, contentDescription = "节区跳转")
                }
            }
        }
    }

    // 节区跳转对话框：节区名 + 首地址，点击加载对应文件偏移
    if (showSectionJump) {
        SectionJumpDialog(
            sections = editorUiState.sections,
            onDismiss = { showSectionJump = false },
            onJump = { section ->
                showSectionJump = false
                // 反汇编视图工作在文件偏移坐标，跳转用 paddr
                viewModel.loadDisassembly(section.paddr, highlightAfterLoad = section.paddr)
            }
        )
    }

    // 汇编指令编辑对话框
    editingInstruction?.let { instruction ->
        InstructionEditDialog(
            instruction = instruction,
            // 用 Capstone cs_asm 实时校验编码（需传入指令地址，分支偏移量依赖它）
            onEncode = { asmText -> viewModel.assembleInstruction(asmText, instruction.address) },
            onDismiss = { editingInstruction = null },
            onOpenHelp = { showAsmHelp = true },
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
                            viewModel.loadDisassembly(
                                disassemblyData.baseAddress,
                                disassemblyData.loadedSize.takeIf { it > 0 }
                                    ?: disassemblyData.instructions.size.toLong() * 4L,
                                highlightAfterLoad = instruction.address
                            )
                        }
                        editingInstruction = null
                    }
                    true
                }
            }
        )
    }

    // 指令帮助文档
    if (showAsmHelp) {
        AsmHelpDialog(onDismiss = { showAsmHelp = false })
    }

    // 长按操作菜单：断点调试 / 交叉引用 / 函数调用
    menuInstruction?.let { inst ->
        InstructionActionDialog(
            address = inst.address,
            onDismiss = { menuInstruction = null },
            onBreakpointDebug = {
                menuInstruction = null
                onBreakpointDebug(inst.address)
            },
            onXref = {
                menuInstruction = null
                viewModel.loadXrefsAtFileOffset(inst.address)
                showXrefSheet = true
            },
            onCallFunction = {
                menuInstruction = null
                onCallAtEmulation(inst.address)
            }
        )
    }

    // 交叉引用面板
    if (showXrefSheet) {
        XrefBottomSheet(
            xrefData = xrefData,
            onDismiss = { showXrefSheet = false },
            onXrefClick = { addr ->
                showXrefSheet = false
                viewModel.loadDisassembly(addr, highlightAfterLoad = addr)
            }
        )
    }
}

/**
 * 长按操作菜单：断点调试 / 交叉引用 / 函数调用。
 */
@Composable
private fun InstructionActionDialog(
    address: Long,
    onDismiss: () -> Unit,
    onBreakpointDebug: () -> Unit,
    onXref: () -> Unit,
    onCallFunction: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "0x${address.toString(16).uppercase()}",
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column {
                InstructionActionRow(
                    title = "断点调试",
                    subtitle = "跳转仿真并在该地址下断点",
                    onClick = onBreakpointDebug
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                InstructionActionRow(
                    title = "交叉引用",
                    subtitle = "查看该地址的引用关系",
                    onClick = onXref
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                InstructionActionRow(
                    title = "函数调用",
                    subtitle = "跳转仿真并预填该地址",
                    onClick = onCallFunction
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
private fun InstructionActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 10.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
    // 用 Capstone cs_asm + 自研编码器编码预览
    return try {
        val bytes = encode(text.trim())
        if (bytes == null || bytes.isEmpty()) {
            ValidationResult(
                inst, args,
                errorMessage = "无法编码该指令（capstone/自研编码器均不支持该形式）；请尝试输入 NOP / MOV W0, #1 / B #0x... 等"
            )
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
    onOpenHelp: () -> Unit,
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
    var showHelp by remember { mutableStateOf(false) }

    // 实时校验（onEncode 依赖 address，地址在对话框生命周期内不变）
    val validation = remember(input, instruction.address) {
        if (input.isBlank()) null else validateAsmText(input, onEncode)
    }
    val canApply = validation?.encodedBytes != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "编辑汇编指令",
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { showHelp = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                        contentDescription = "指令帮助"
                    )
                }
            }
        },
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

    if (showHelp) {
        AsmHelpDialog(onDismiss = { showHelp = false })
    }
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
    onOpenHelp: () -> Unit = {},
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
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPrevPage,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "上一页"
                    )
                }

                CompactTextField(
                    value = inputAddress,
                    onValueChange = onInputAddressChange,
                    modifier = Modifier.weight(1f),
                    placeholder = "地址 (hex 或 dec)",
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
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
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
            CompactTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = "搜索指令...",
                leadingIcon = Icons.Default.Search,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )
            IconButton(
                onClick = onOpenHelp,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "指令帮助"
                )
            }
        }
    }
}

@Composable
private fun DisassemblyListView(
    instructions: List<DisasmInstruction>,
    searchQuery: String,
    highlightAddress: Long?,
    patchedOffsets: Set<Long>,
    functionOverlay: Map<Long, String>,
    flashTrigger: Int,
    listState: LazyListState,
    onInstructionClick: (DisasmInstruction) -> Unit,
    onInstructionLongClick: (DisasmInstruction) -> Unit,
    onLoadMoreBefore: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filteredInstructions by remember(instructions, searchQuery) {
        derivedStateOf {
            if (searchQuery.isBlank()) {
                instructions.map { it to false }
            } else {
                instructions.map { instruction ->
                    val matches = instruction.mnemonic.contains(searchQuery, ignoreCase = true) ||
                        instruction.opStr.contains(searchQuery, ignoreCase = true)
                    instruction to matches
                }
            }
        }
    }

    // 撤销跳转：闪烁地址所在行滚动到可视区
    LaunchedEffect(highlightAddress) {
        val target = highlightAddress ?: return@LaunchedEffect
        val idx = filteredInstructions.indexOfFirst { it.first.address == target }
        if (idx >= 0) listState.animateScrollToItem(idx)
    }

    // 向上无限滚动：滚到顶部时自动往前加载更多指令
    val prevSize = remember { mutableIntStateOf(instructions.size) }
    LaunchedEffect(instructions.size) {
        // 指令数量增加（往前追加了），保持当前可见项位置不变
        val delta = instructions.size - prevSize.intValue
        if (delta > 0 && prevSize.intValue > 0) {
            // 新指令插入到头部，把滚动位置往后移 delta，保持视觉位置不变
            listState.scrollToItem(
                index = listState.firstVisibleItemIndex + delta,
                scrollOffset = listState.firstVisibleItemScrollOffset
            )
        }
        prevSize.intValue = instructions.size
    }
    val firstVisibleIndex by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(firstVisibleIndex) {
        // 滑到顶部前 3 条时触发加载
        if (firstVisibleIndex <= 2 && instructions.isNotEmpty()) {
            onLoadMoreBefore()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp)
    ) {
        items(
            items = filteredInstructions,
            // 地址在反汇编流中唯一；用 (address, mnemonic).hashCode() 作为 key 会哈希碰撞
            // 导致 LazyColumn 抛 "Key was already used" 崩溃
            key = { it.first.address }
        ) { (instruction, isMatch) ->
            // 函数边界标注：如果该地址是某个函数的起始地址，显示函数名
            val funcName = functionOverlay[instruction.address]
            if (funcName != null) {
                FunctionLabel(funcName)
            }
            DisassemblyRow(
                instruction = instruction,
                isMatch = isMatch,
                isFlash = highlightAddress != null && instruction.address == highlightAddress,
                flashKey = flashTrigger,
                isPatched = instruction.address in patchedOffsets,
                onClick = { onInstructionClick(instruction) },
                onLongClick = { onInstructionLongClick(instruction) }
            )
        }
    }
}

/** 函数边界标注行。 */
@Composable
private fun FunctionLabel(name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "▶",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(16.dp)
        )
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 节区跳转对话框：列出节区名与首地址（文件偏移 + vaddr），点击跳转。 */
@Composable
private fun SectionJumpDialog(
    sections: List<SectionInfo>,
    onDismiss: () -> Unit,
    onJump: (SectionInfo) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("跳转到节区") },
        text = {
            if (sections.isEmpty()) {
                Text(
                    "暂无节区数据",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(modifier = Modifier.height(360.dp)) {
                    items(sections) { section ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onJump(section) }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = section.name.ifBlank { "<unnamed>" },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "文件偏移 0x${section.paddr.toString(16).uppercase()}" +
                                        "  ·  vaddr 0x${section.address.toString(16).uppercase()}" +
                                        "  ·  ${section.size}B",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/** 交叉引用面板（internal：StructureTab 长按交叉引用复用）。 */
@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun XrefBottomSheet(
    xrefData: XrefDataState,
    onDismiss: () -> Unit,
    onXrefClick: (Long) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // 类型筛选：null=全部，否则只显示指定类型
    var typeFilter: com.ai.fler.core.analysis.XrefType? by remember { mutableStateOf(null) }
    // 搜索关键字：地址 hex 匹配
    var searchQuery by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // 扁平化数据：to/from 合并，带 tag 区分，附函数名（从 xrefData.xrefFunctionNames 取）
    data class FlatItem(val isTo: Boolean, val xref: com.ai.fler.core.analysis.Xref, val address: Long, val functionName: String?)
    val flatItems: List<FlatItem> = remember(xrefData, typeFilter, searchQuery) {
        val list = mutableListOf<FlatItem>()
        val fnMap = xrefData.xrefFunctionNames
        // isTo=true → 调用方（xref.from 是跳转地址）；isTo=false → 被调用（xref.to 是地址）
        for (xref in xrefData.xrefsTo) {
            val fn = fnMap[xref.from]
            list.add(FlatItem(true, xref, xref.from, fn))
        }
        for (xref in xrefData.xrefsFrom) {
            val fn = fnMap[xref.to]
            list.add(FlatItem(false, xref, xref.to, fn))
        }
        // 类型筛选
        val filtered1 = if (typeFilter == null) list else list.filter { it.xref.type == typeFilter }
        // 搜索筛选
        if (searchQuery.isBlank()) filtered1 else {
            val q = searchQuery.uppercase()
            filtered1.filter {
                val hex = it.address.toString(16).uppercase()
                hex.contains(q) || ("0x$hex").contains(q) ||
                    (it.functionName?.uppercase()?.contains(q) == true)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // 标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "交叉引用",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "0x${xrefData.address.toString(16).uppercase()}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (xrefData.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                Spacer(modifier = Modifier.height(16.dp))
                return@Column
            }

            if (xrefData.xrefsTo.isEmpty() && xrefData.xrefsFrom.isEmpty()) {
                Text(
                    text = "无交叉引用",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
                Spacer(modifier = Modifier.height(16.dp))
                return@Column
            }

            // 搜索框
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                placeholder = { Text("搜索地址 (如 0x1234 或 1234)") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "搜索",
                        modifier = Modifier.size(20.dp)
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp)
            )

            // 类型筛选 Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                XrefTypeChip(
                    label = "全部 (${xrefData.xrefsTo.size + xrefData.xrefsFrom.size})",
                    selected = typeFilter == null,
                    onClick = { typeFilter = null }
                )
                XrefTypeChip(
                    label = "调用 (${xrefData.xrefsTo.size})",
                    selected = typeFilter == com.ai.fler.core.analysis.XrefType.CALL,
                    onClick = { typeFilter = com.ai.fler.core.analysis.XrefType.CALL }
                )
                XrefTypeChip(
                    label = "跳转 (${xrefData.xrefsTo.count { it.type == com.ai.fler.core.analysis.XrefType.JUMP } + xrefData.xrefsFrom.count { it.type == com.ai.fler.core.analysis.XrefType.JUMP }})",
                    selected = typeFilter == com.ai.fler.core.analysis.XrefType.JUMP,
                    onClick = { typeFilter = com.ai.fler.core.analysis.XrefType.JUMP }
                )
                XrefTypeChip(
                    label = "数据 (${xrefData.xrefsTo.count { it.type == com.ai.fler.core.analysis.XrefType.DATA || it.type == com.ai.fler.core.analysis.XrefType.STRING } + xrefData.xrefsFrom.count { it.type == com.ai.fler.core.analysis.XrefType.DATA || it.type == com.ai.fler.core.analysis.XrefType.STRING }})",
                    selected = typeFilter == com.ai.fler.core.analysis.XrefType.DATA,
                    onClick = { typeFilter = com.ai.fler.core.analysis.XrefType.DATA }
                )
            }

            // 结果列表（固定高度 → 滚动条 + 完整列表不截断）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(420.dp)  // 固定高度：BottomSheet 本身可以整体滑，但内部列表要独立滚动
            ) {
                if (flatItems.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "无匹配项",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 分组展示：先调用方，再被调用
                        val hasTo = flatItems.any { it.isTo }
                        val hasFrom = flatItems.any { !it.isTo }

                        if (hasTo) {
                            stickyHeader {
                                Text(
                                    text = "调用方 (${flatItems.count { it.isTo }})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(top = 4.dp, bottom = 4.dp)
                                )
                            }
                            items(
                                items = flatItems.filter { it.isTo },
                                key = { "to_${it.xref.from}_${it.xref.to}_${it.xref.type}" }
                            ) { item ->
                                XrefRow(
                                    address = item.address,
                                    type = item.xref.type,
                                    functionName = item.functionName,
                                    onClick = { onXrefClick(item.address) }
                                )
                            }
                        }

                        if (hasFrom) {
                            stickyHeader {
                                Text(
                                    text = "被调用 (${flatItems.count { !it.isTo }})",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(MaterialTheme.colorScheme.surface)
                                        .padding(top = 12.dp, bottom = 4.dp)
                                )
                            }
                            items(
                                items = flatItems.filter { !it.isTo },
                                key = { "from_${it.xref.from}_${it.xref.to}_${it.xref.type}" }
                            ) { item ->
                                XrefRow(
                                    address = item.address,
                                    type = item.xref.type,
                                    functionName = item.functionName,
                                    onClick = { onXrefClick(item.address) }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            // 底部计数
            Text(
                text = "显示 ${flatItems.size} 条 / 共 ${xrefData.xrefsTo.size + xrefData.xrefsFrom.size} 条",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/** 交叉引用类型筛选 Chip。 */
@Composable
private fun XrefTypeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected)
        MaterialTheme.colorScheme.primaryContainer
    else
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val fg = if (selected)
        MaterialTheme.colorScheme.onPrimaryContainer
    else
        MaterialTheme.colorScheme.onSurfaceVariant
    androidx.compose.material3.Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() },
        color = bg,
        contentColor = fg
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun XrefRow(
    address: Long,
    type: com.ai.fler.core.analysis.XrefType,
    functionName: String? = null,
    onClick: () -> Unit
) {
    val typeText = when (type) {
        com.ai.fler.core.analysis.XrefType.CALL -> "CALL"
        com.ai.fler.core.analysis.XrefType.JUMP -> "JUMP"
        com.ai.fler.core.analysis.XrefType.DATA -> "DATA"
        com.ai.fler.core.analysis.XrefType.STRING -> "STR"
        com.ai.fler.core.analysis.XrefType.CODE -> "CODE"
        com.ai.fler.core.analysis.XrefType.UNKNOWN -> "?"
    }
    val typeColor = when (type) {
        com.ai.fler.core.analysis.XrefType.CALL -> Color(0xFF4CAF50)
        com.ai.fler.core.analysis.XrefType.JUMP -> Color(0xFFFF9800)
        com.ai.fler.core.analysis.XrefType.DATA -> Color(0xFF2196F3)
        com.ai.fler.core.analysis.XrefType.STRING -> Color(0xFF9C27B0)
        else -> Color.Gray
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = typeText,
                style = MaterialTheme.typography.labelSmall,
                color = typeColor,
                modifier = Modifier
                    .background(typeColor.copy(alpha = 0.1f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .width(48.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "0x${address.toString(16).uppercase().padStart(8, '0')}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        // 函数名（第二行，可选）
        if (functionName != null) {
            Text(
                text = functionName,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 56.dp, top = 2.dp)
            )
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DisassemblyRow(
    instruction: DisasmInstruction,
    isMatch: Boolean,
    isFlash: Boolean,
    flashKey: Int,
    isPatched: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    // 呼吸脉冲：只在闪烁行本地驱动动画（其余行 isFlash=false，不参与脉冲重组）
    val pulseAlpha = remember { Animatable(0f) }
    LaunchedEffect(isFlash, flashKey) {
        if (!isFlash) {
            pulseAlpha.snapTo(0f)
            return@LaunchedEffect
        }
        pulseAlpha.snapTo(0f)
        repeat(2) {
            pulseAlpha.animateTo(1f, tween(AnimDuration.slow, easing = AnimEasing.entry))
            pulseAlpha.animateTo(0f, tween(AnimDuration.slow, easing = AnimEasing.entry))
        }
    }
    val a = pulseAlpha.value
    // 显示字符串缓存：指令不可变，按对象缓存避免滚动/重组时重复格式化分配
    val addressHex = remember(instruction) { "0x${instruction.address.toString(16).uppercase().padStart(8, '0')}" }
    val bytesHex = remember(instruction) { instruction.bytes.joinToString(" ") { byte -> byte.toUByte().toString(16).uppercase().padStart(2, '0') } }
    val mnemonicUpper = remember(instruction) { instruction.mnemonic.uppercase() }
    val isBranch = remember(instruction) { instruction.mnemonic.startsWith("b") || instruction.mnemonic == "ret" }
    val isCsel = remember(instruction) { instruction.mnemonic.startsWith("csel") }
    // 闪烁 > 未保存修改（红）> 搜索匹配
    val backgroundColor = when {
        isFlash -> Color(0xFFD32F2F).copy(alpha = (a * 0.85f).coerceIn(0f, 1f))
        isPatched -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
        isMatch -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        else -> Color.Transparent
    }
    val flashScale = 1f + a * 0.02f
    // 放大效果仅作用于闪烁行，普通行不挂 graphicsLayer（省去每行图层开销）
    val scaleModifier = if (isFlash) Modifier.graphicsLayer { scaleX = flashScale; scaleY = flashScale } else Modifier
    // 指令行合并为单个 AnnotatedString（助记符加粗着色 + 操作数），省掉内层 Row+Spacer 的第二次测量
    val scheme = MaterialTheme.colorScheme
    val mnemColor = when {
        isPatched -> scheme.error
        isMatch -> scheme.primary
        isBranch -> Color(0xFFFF9800)
        isCsel -> Color(0xFF2196F3)
        else -> scheme.onSurface
    }
    val opColor = when {
        isPatched -> scheme.error
        else -> scheme.onSurface.copy(alpha = if (isMatch) 1.0f else 0.8f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(scaleModifier)
            .background(backgroundColor)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onClick() },
                    onLongPress = { onLongClick() }
                )
            }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 地址列（8 位十六进制，紧凑）
        Text(
            text = addressHex,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = when {
                isPatched -> scheme.error
                else -> scheme.onSurfaceVariant
            },
            modifier = Modifier.width(96.dp)
        )

        // 字节码列
        Text(
            text = bytesHex,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = if (isPatched) scheme.error else scheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.width(92.dp)
        )

        // 助记符列（固定通配宽度，扁平于外层 Row，省掉内层 Row 的第二次测量）
        Text(
            text = mnemonicUpper,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = mnemColor,
            modifier = Modifier.padding(start = 12.dp)
        )
        // 操作数列（占剩余宽度，过长省略）
        Text(
            text = instruction.opStr,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = opColor,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
        )
    }
}
