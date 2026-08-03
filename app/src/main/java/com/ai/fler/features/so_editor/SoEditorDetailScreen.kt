package com.ai.fler.features.so_editor

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.ai.fler.ui.animation.AnimDuration
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * SO 编辑器详情页（从产物 / 方法列表跳转进来）。
 *
 * 与 [SoEditorScreen] 功能一致，区别：
 * - 带返回按钮（navigationIcon）
 * - 支持 [methodLength] > 0 的「方法编辑模式」：汇编 Tab 只展示该方法范围
 * - 方法模式下默认打开汇编 Tab（而非结构 Tab），避免用户从方法列表点进来看到空白结构页
 *
 * @param methodLength 方法字节长度；>0 时表示「方法编辑模式」
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoEditorDetailScreen(
    filePath: String,
    initialOffset: Long = 0L,
    methodLength: Long = 0L,
    onBack: () -> Unit = {},
    viewModel: SoEditorViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val patchedOffsets by viewModel.patchedOffsets.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // SAF CreateDocument：导出补丁到用户可读位置（Documents 等），与 SoEditorScreen 一致
    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val ok = viewModel.exportPatchesToUri(it)
                snackbarHostState.showSnackbar(if (ok) "已导出" else "导出失败")
            }
        }
    }

    // SAF CreateDocument：导出修改后的 SO 二进制，与 SoEditorScreen 一致
    val createSoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val ok = viewModel.exportSoToUri(it)
                snackbarHostState.showSnackbar(if (ok) "已导出修改后的 SO" else "无补丁可导出")
            }
        }
    }

    // 方法编辑模式：methodLength>0 时只展示该方法范围
    val isMethodMode = methodLength > 0L

    LaunchedEffect(filePath) {
        // 方法模式下默认切换到汇编 Tab，避免用户从方法列表点进来看到空结构页
        if (isMethodMode) {
            viewModel.setTab(EditorTab.DISASSEMBLY)
        }
        viewModel.openFile(filePath)
        val target = if (initialOffset > 0) initialOffset else 0L
        viewModel.setSelectedOffset(target)
        // 方法模式下，汇编只加载方法范围内的字节；否则使用默认页大小
        if (isMethodMode) {
            viewModel.loadDisassembly(target, methodLength)
        } else {
            viewModel.loadDisassembly(target)
        }
        // Hex 视图对齐到方法起始
        val hexSize = if (isMethodMode) methodLength.coerceAtLeast(256L) else 256L
        viewModel.loadHexData(target, hexSize)
    }

    // 拦截系统返回键：非结构 Tab → 先回结构 Tab，在结构 Tab 再返回
    BackHandler {
        if (currentTab == EditorTab.STRUCTURE) {
            onBack()
        } else {
            viewModel.setTab(EditorTab.STRUCTURE)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.fileName.ifEmpty { "SO 编辑器" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (isMethodMode) {
                            Text(
                                text = "方法: 0x${initialOffset.toString(16).uppercase()} + 0x${methodLength.toString(16).uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else if (initialOffset > 0) {
                            Text(
                                text = "偏移: 0x${initialOffset.toString(16).uppercase()}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentTab == EditorTab.STRUCTURE) {
                            onBack()
                        } else {
                            viewModel.setTab(EditorTab.STRUCTURE)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                actions = {
                    // 撤销按钮（与 SoEditorScreen 一致）
                    IconButton(
                        onClick = {
                            val record = viewModel.undo()
                            scope.launch {
                                if (record != null) {
                                    snackbarHostState.showSnackbar("已撤销: 0x${record.address.toString(16)}")
                                } else {
                                    snackbarHostState.showSnackbar("无可撤销操作")
                                }
                            }
                        },
                        enabled = uiState.isFileOpen
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Undo,
                            contentDescription = "撤销"
                        )
                    }
                    // 保存按钮：标记所有修改为已保存，清除红色高亮，与 SoEditorScreen 一致
                    IconButton(
                        onClick = {
                            scope.launch {
                                val count = viewModel.commitChanges()
                                if (count > 0) {
                                    snackbarHostState.showSnackbar("已保存 $count 处修改")
                                } else {
                                    snackbarHostState.showSnackbar("无未保存的修改")
                                }
                            }
                        },
                        enabled = uiState.isFileOpen && patchedOffsets.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Save,
                            contentDescription = "保存"
                        )
                    }
                    // 导出菜单（补丁 / 修改后的 SO），与 SoEditorScreen 一致
                    var showExportMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(
                            onClick = { showExportMenu = true },
                            enabled = uiState.isFileOpen
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileUpload,
                                contentDescription = "导出"
                            )
                        }
                        DropdownMenu(
                            expanded = showExportMenu,
                            onDismissRequest = { showExportMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("导出补丁 (.patch)") },
                                onClick = {
                                    showExportMenu = false
                                    val name = uiState.fileName.removeSuffix(".so")
                                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                        .format(Date())
                                    createDocLauncher.launch("${name}_$ts.patch")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导出修改后的 SO (.so)") },
                                onClick = {
                                    showExportMenu = false
                                    val name = uiState.fileName.removeSuffix(".so")
                                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                        .format(Date())
                                    createSoLauncher.launch("${name}_patched_$ts.so")
                                }
                            )
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                // ① 文件未打开 + 加载中
                uiState.isLoading && !uiState.isFileOpen -> {
                    LoadingContent(modifier = Modifier.align(Alignment.Center))
                }
                // ② 错误
                uiState.errorMessage != null -> {
                    ErrorContent(
                        message = uiState.errorMessage!!,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
                // ③ 正在分析交叉引用（本计划核心修复点）
                uiState.isAnalyzing -> {
                    AnalyzingContent(modifier = Modifier.align(Alignment.Center))
                }
                // ④ 正常状态：渲染原有 Tab 内容
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        TabRow(
                            selectedTabIndex = currentTab.ordinal,
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Tab(
                                selected = currentTab == EditorTab.STRUCTURE,
                                onClick = { viewModel.setTab(EditorTab.STRUCTURE) },
                                text = { Text("结构") },
                            )
                            Tab(
                                selected = currentTab == EditorTab.HEX,
                                onClick = { viewModel.setTab(EditorTab.HEX) },
                                text = { Text("Hex") },
                            )
                            Tab(
                                selected = currentTab == EditorTab.DISASSEMBLY,
                                onClick = { viewModel.setTab(EditorTab.DISASSEMBLY) },
                                text = { Text("汇编") },
                            )
                        }

                        // Tab 内容：从汇编切回结构 Tab 时用快 fade(200ms)，其他方向保持滑入转场
                        AnimatedContent(
                            targetState = currentTab,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            transitionSpec = {
                                if (targetState == EditorTab.STRUCTURE) {
                                    fadeIn(tween(AnimDuration.fast)) togetherWith fadeOut(tween(AnimDuration.fast))
                                } else {
                                    (fadeIn() + slideInVertically(initialOffsetY = { it / 4 })) togetherWith
                                        (fadeOut() + slideOutVertically(targetOffsetY = { -it / 4 }))
                                }
                            },
                            label = "tabContent"
                        ) { tab ->
                            when (tab) {
                                EditorTab.STRUCTURE -> StructureTab(
                                    sections = uiState.sections,
                                    symbols = uiState.symbols,
                                    dynamicSymbols = uiState.dynamicSymbols,
                                    functions = uiState.functions,
                                    strings = uiState.strings,
                                    onSectionClick = { section ->
                                        viewModel.setSelectedOffset(section.address)
                                        viewModel.setStructureFlashAddress(section.address)
                                        viewModel.setTab(EditorTab.DISASSEMBLY)
                                        viewModel.loadDisassembly(section.address, highlightAfterLoad = section.address)
                                    },
                                    onSymbolClick = { symbol ->
                                        viewModel.setStructureFlashAddress(symbol.address)
                                        viewModel.setSelectedOffset(symbol.address)
                                        viewModel.setTab(EditorTab.DISASSEMBLY)
                                        viewModel.loadDisassembly(symbol.address, highlightAfterLoad = symbol.address)
                                    },
                                    onFunctionClick = { func ->
                                        viewModel.setStructureFlashAddress(func.vaddr)
                                        viewModel.setSelectedOffset(func.vaddr)
                                        viewModel.setTab(EditorTab.DISASSEMBLY)
                                        viewModel.loadDisassembly(func.vaddr, highlightAfterLoad = func.vaddr)
                                    },
                                    onStringsTabSelected = { viewModel.loadStrings() },
                                    viewModel = viewModel
                                )
                                EditorTab.HEX -> HexEditorTab(viewModel = viewModel)
                                EditorTab.DISASSEMBLY -> DisassemblyTab(
                                    viewModel = viewModel,
                                    isMethodMode = isMethodMode,
                                    onInstructionClick = { address ->
                                        viewModel.setSelectedOffset(address)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LoadingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "加载中...",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun AnalyzingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "正在分析交叉引用...",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ErrorContent(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "加载失败",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
