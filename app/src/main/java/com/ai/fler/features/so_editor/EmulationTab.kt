package com.ai.fler.features.so_editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.analysis.StopReason

/**
 * 仿真 Tab（M4）：Unicorn 引擎的交互式操作界面。
 *
 * 布局（自上而下）：
 * 1. 函数调用区：函数名（可搜索下拉）+ 8 个参数输入 + Call
 * 2. 控制区：Run / Step / Stop + 断点管理 + 执行计数与停止原因
 * 3. 结果区：返回值卡片 + 全寄存器网格（点击可编辑）
 * 4. 日志区：最近 200 条操作日志
 *
 * 首次进入且未开会话 → 自动 openSession(filePath)。
 */
@OptIn(ExperimentalLayoutApi::class)
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
                    LogSection(state = state, modifier = Modifier.weight(1f, fill = false))
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

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("函数调用", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedTextField(
                    value = state.selectedFunctionName,
                    onValueChange = {
                        viewModel.selectFunction(it)
                        showDropdown = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("函数名或地址（0x…）") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                    )
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
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { viewModel.callSelectedFunction() },
                enabled = !state.isRunning && state.selectedFunctionName.isNotBlank()
            ) {
                Text("Call")
            }
        }

        // 参数输入：8 格横向滚动（x0-x7）
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for (i in 0 until 8) {
                OutlinedTextField(
                    value = state.argInputs[i],
                    onValueChange = { viewModel.setArgInput(i, it) },
                    modifier = Modifier.width(96.dp),
                    label = { Text("x$i") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                        unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                    )
                )
            }
        }
    }
}

// ══════════════════════════════════════════════════════════
// 2. 控制区：Run / Step / Stop + 断点 + 状态
// ══════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ControlSection(viewModel: EmulationViewModel, state: EmulationUiState) {
    var bpInput by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("执行控制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(
                onClick = { viewModel.runFromPc() },
                enabled = !state.isRunning
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Run")
            }
            OutlinedButton(
                onClick = { viewModel.stepOnce() },
                enabled = !state.isRunning
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Step")
            }
            OutlinedButton(
                onClick = { viewModel.requestStop() },
                enabled = state.isRunning
            ) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Stop")
            }

            Spacer(Modifier.weight(1f))

            // 断点输入 + Add
            OutlinedTextField(
                value = bpInput,
                onValueChange = { bpInput = it },
                modifier = Modifier.width(130.dp),
                placeholder = { Text("断点地址") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outline
                )
            )
            TextButton(
                onClick = {
                    viewModel.addBreakpoint(bpInput)
                    bpInput = ""
                },
                enabled = bpInput.isNotBlank() && !state.isRunning
            ) { Text("Add") }
        }

        // 运行状态
        if (state.isRunning) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text("执行中…", style = MaterialTheme.typography.labelMedium)
            }
        }

        // 状态 chip：执行计数 + 停止原因 + PC
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("已执行 ${state.executedCount} 条")
            state.lastStopReason?.let { StatusChip("停止：${stopReasonLabel(it)}") }
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
                            .padding(horizontal = 8.dp, vertical = 4.dp),
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
                            modifier = Modifier.height(20.dp)
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "删除断点",
                                tint = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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

// ══════════════════════════════════════════════════════════
// 3. 结果区：返回值 + 寄存器
// ══════════════════════════════════════════════════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultSection(viewModel: EmulationViewModel, state: EmulationUiState) {
    var editRegName by remember { mutableStateOf<String?>(null) }
    var editRegValue by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // 返回值卡片
        state.lastCallResult?.let { r ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp))
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

        // 寄存器网格
        if (state.registers.isNotEmpty()) {
            Text("寄存器（点击编辑）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
private fun LogSection(state: EmulationUiState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // 新日志自动滚底
    LaunchedEffect(state.logs.size) {
        if (state.logs.isNotEmpty()) {
            listState.animateScrollToItem(state.logs.size - 1)
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("日志", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp))
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
