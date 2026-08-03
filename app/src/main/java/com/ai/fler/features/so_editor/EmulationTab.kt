package com.ai.fler.features.so_editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.analysis.StopReason

/**
 * 仿真 Tab（M4）：Unicorn 引擎的交互式操作界面。
 *
 * 布局（自上而下，各区块为圆角卡片）：
 * 1. 函数调用区：函数名（可搜索下拉）+ 8 个参数输入（4×2 网格）+ 调用
 * 2. 执行控制区：运行 / 单步 / 停止 + 断点管理 + 状态 chips
 * 3. 结果区：返回值卡片 + 全寄存器网格（点击可编辑）
 * 4. 日志区：最近 200 条操作日志（可清空）
 *
 * 首次进入且未开会话 → 自动 openSession(filePath)。
 */
@Composable
fun EmulationTab(
    viewModel: EmulationViewModel,
    filePath: String,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val functionOptions by viewModel.functionOptions.collectAsStateWithLifecycle()

    // 首次进入自动开会话
    LaunchedEffect(filePath) {
        if (filePath.isNotEmpty() && !state.isSessionOpen && !state.isOpening) {
            viewModel.openSession(filePath)
        }
    }

    // 引擎不可用：整页降级提示
    if (!viewModel.engineAvailable) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = "仿真引擎不可用\n（Unicorn 未编译进当前构建）",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    Column(modifier = modifier.fillMaxSize()) {
        when {
            state.isOpening -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在打开仿真会话…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            !state.isSessionOpen -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            state.errorMessage ?: "会话未打开",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { viewModel.openSession(filePath) }) {
                            Text("重新打开")
                        }
                    }
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Spacer(Modifier.height(8.dp))
                    FunctionCallSection(viewModel = viewModel, state = state, functionOptions = functionOptions)
                    ControlSection(viewModel = viewModel, state = state)
                    ResultSection(viewModel = viewModel, state = state)
                    LogSection(viewModel = viewModel, state = state)
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        // 错误 Snackbar 式提示条（底部固定，点击关闭）
        AnimatedVisibility(visible = state.errorMessage != null, enter = fadeIn(), exit = fadeOut()) {
            state.errorMessage?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .clickable { viewModel.clearError() }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = MaterialTheme.colorScheme.onErrorContainer)
                }
            }
        }
    }
}


// ══════════════════════════════════════════════════════════
// 通用小区块
// ══════════════════════════════════════════════════════════

/** 区块卡片容器：标题行（图标 + 标题 + 可选右侧操作）+ 内容。 */
@Composable
private fun EmuSection(
    title: String,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            if (action != null) {
                Spacer(Modifier.weight(1f))
                action()
            }
        }
        content()
    }
}

/** 紧凑操作按钮（36dp 高，与 CompactTextField 对齐）。 */
@Composable
private fun CompactActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.secondaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSecondaryContainer
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(36.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

/** 状态 chip（可自定义配色）。 */
@Composable
private fun StatusChip(
    text: String,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant
) {
    Box(
        modifier = Modifier
            .background(containerColor, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = contentColor
        )
    }
}

private fun stopReasonLabel(reason: StopReason): String = when (reason) {
    StopReason.NONE -> "正常结束"
    StopReason.BREAKPOINT -> "断点"
    StopReason.SINGLE_STEP -> "单步"
    StopReason.TIMEOUT -> "超时"
    StopReason.ERROR -> "错误"
    StopReason.INTERRUPTED -> "已中断"
    StopReason.FUNCTION_RETURN -> "函数返回"
}

/** 停止原因 chip 的配色（错误/超时醒目红色，函数返回强调色，断点次级色）。 */
@Composable
private fun stopReasonColors(reason: StopReason): Pair<Color, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (reason) {
        StopReason.ERROR, StopReason.TIMEOUT -> scheme.errorContainer to scheme.onErrorContainer
        StopReason.FUNCTION_RETURN -> scheme.tertiaryContainer to scheme.onTertiaryContainer
        StopReason.BREAKPOINT -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
}

// ══════════════════════════════════════════════════════════
// 1. 函数调用区
// ══════════════════════════════════════════════════════════

@Composable
private fun FunctionCallSection(
    viewModel: EmulationViewModel,
    state: EmulationUiState,
    functionOptions: List<com.ai.fler.core.analysis.FunctionInfo>
) {
    var showDropdown by remember { mutableStateOf(false) }
    val filtered = remember(state.selectedFunctionName, functionOptions) {
        val q = state.selectedFunctionName.trim().lowercase()
        if (q.isEmpty()) functionOptions.take(100)
        else functionOptions.filter { it.name.lowercase().contains(q) }.take(100)
    }

    EmuSection(title = "函数调用", icon = Icons.Default.PlayArrow) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                CompactTextField(
                    value = state.selectedFunctionName,
                    onValueChange = {
                        viewModel.selectFunction(it)
                        showDropdown = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "函数名或地址（0x…）",
                    leadingIcon = Icons.Default.Search,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        showDropdown = false
                        viewModel.callSelectedFunction()
                    })
                )
                DropdownMenu(expanded = showDropdown && filtered.isNotEmpty(), onDismissRequest = { showDropdown = false }) {
                    filtered.forEach { func ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(func.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "0x${java.lang.Long.toHexString(func.vaddr)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            },
                            onClick = {
                                viewModel.selectFunction(func.name)
                                showDropdown = false
                            }
                        )
                    }
                }
            }
            CompactActionButton(
                text = "调用",
                icon = Icons.Default.PlayArrow,
                onClick = { viewModel.callSelectedFunction() },
                enabled = !state.isRunning && state.selectedFunctionName.isNotBlank(),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }

        // 参数输入：4×2 网格（x0-x7），免横向滚动
        for (row in 0 until 2) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (col in 0 until 4) {
                    val i = row * 4 + col
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "x$i",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(22.dp)
                        )
                        CompactTextField(
                            value = state.argInputs[i],
                            onValueChange = { viewModel.setArgInput(i, it) },
                            modifier = Modifier.weight(1f),
                            placeholder = "0",
                            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                        )
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// 2. 控制区：运行 / 单步 / 停止 + 断点 + 状态
// ══════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlSection(viewModel: EmulationViewModel, state: EmulationUiState) {
    var bpInput by remember { mutableStateOf("") }

    EmuSection(title = "执行控制", icon = Icons.Default.SkipNext) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CompactActionButton(
                text = "运行",
                icon = Icons.Default.PlayArrow,
                onClick = { viewModel.runFromPc() },
                enabled = !state.isRunning,
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
            CompactActionButton(
                text = "单步",
                icon = Icons.Default.SkipNext,
                onClick = { viewModel.stepOnce() },
                enabled = !state.isRunning
            )
            CompactActionButton(
                text = "停止",
                icon = Icons.Default.Stop,
                onClick = { viewModel.requestStop() },
                enabled = state.isRunning,
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer
            )
            if (state.isRunning) {
                Spacer(Modifier.weight(1f))
                LinearProgressIndicator(modifier = Modifier.weight(1f))
            }
        }

        // 断点输入 + 添加
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CompactTextField(
                value = bpInput,
                onValueChange = { bpInput = it },
                modifier = Modifier.weight(1f),
                placeholder = "断点地址（hex/dec）",
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (bpInput.isNotBlank()) {
                        viewModel.addBreakpoint(bpInput)
                        bpInput = ""
                    }
                })
            )
            CompactActionButton(
                text = "添加",
                onClick = {
                    viewModel.addBreakpoint(bpInput)
                    bpInput = ""
                },
                enabled = bpInput.isNotBlank() && !state.isRunning
            )
        }

        // 状态 chip：执行计数 + 停止原因 + PC + 断点数
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("已执行 ${state.executedCount} 条")
            state.lastStopReason?.let {
                val (bg, fg) = stopReasonColors(it)
                StatusChip("停止：${stopReasonLabel(it)}", containerColor = bg, contentColor = fg)
            }
            state.lastPc?.let { StatusChip("pc=0x${java.lang.Long.toHexString(it)}") }
            if (state.breakpoints.isNotEmpty()) {
                StatusChip("断点 ${state.breakpoints.size} 个")
            }
        }

        // 断点列表
        if (state.breakpoints.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.breakpoints.forEach { addr ->
                    Row(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(start = 8.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "0x${java.lang.Long.toHexString(addr)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        IconButton(
                            onClick = { viewModel.removeBreakpoint(addr) },
                            modifier = Modifier.size(22.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "删除断点",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// 3. 结果区：返回值 + 寄存器
// ══════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultSection(viewModel: EmulationViewModel, state: EmulationUiState) {
    var editRegName by remember { mutableStateOf<String?>(null) }
    var editRegValue by remember { mutableStateOf("") }

    // 返回值卡片
    state.lastCallResult?.let { r ->
        EmuSection(title = "调用结果", icon = Icons.Default.PlayArrow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                Text(
                    "${r.functionName} 返回",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    r.returnValueUnsigned,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    "有符号 ${r.returnValue} ｜ ${stopReasonLabel(r.stopReason)} ｜ ${r.instructionCount} 条指令",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }

    // 寄存器网格
    if (state.registers.isNotEmpty()) {
        EmuSection(title = "寄存器", icon = Icons.Default.SkipNext) {
            Text(
                "点击任意寄存器可编辑写入",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                state.registers.forEach { (name, value) ->
                    val isPc = name == "pc"
                    Row(
                        modifier = Modifier
                            .background(
                                if (isPc) MaterialTheme.colorScheme.tertiaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable {
                                editRegName = name
                                editRegValue = "0x${java.lang.Long.toHexString(value)}"
                            }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "$name=",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            "0x${java.lang.Long.toHexString(value)}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // 寄存器编辑弹窗
    editRegName?.let { regName ->
        AlertDialog(
            onDismissRequest = { editRegName = null },
            title = { Text("编辑寄存器 $regName") },
            text = {
                OutlinedTextField(
                    value = editRegValue,
                    onValueChange = { editRegValue = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setRegisterValue(regName, editRegValue)
                    editRegName = null
                }) { Text("写入") }
            },
            dismissButton = {
                TextButton(onClick = { editRegName = null }) { Text("取消") }
            }
        )
    }
}

// ══════════════════════════════════════════════════════════
// 4. 日志区
// ══════════════════════════════════════════════════════════

@Composable
private fun LogSection(viewModel: EmulationViewModel, state: EmulationUiState) {
    val listState = rememberLazyListState()

    // 新日志自动滚底
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            listState.animateScrollToItem(state.logs.size - 1)
        }
    }

    EmuSection(
        title = "日志（${state.logs.size}）",
        action = {
            if (state.logs.isNotEmpty()) {
                IconButton(
                    onClick = { viewModel.clearLogs() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "清空日志",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .padding(8.dp)
        ) {
            if (state.logs.isEmpty()) {
                Text(
                    "暂无日志",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(state = listState) {
                    items(state.logs) { log ->
                        Text(
                            log,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
