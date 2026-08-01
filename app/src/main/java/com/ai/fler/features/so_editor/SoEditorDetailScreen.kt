package com.ai.fler.features.so_editor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Undo
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
                    IconButton(onClick = onBack) {
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
                            imageVector = Icons.Default.Undo,
                            contentDescription = "撤销"
                        )
                    }
                    // 导出补丁按钮（SAF 让用户选保存位置，默认文件名含时间戳）
                    IconButton(
                        onClick = {
                            val name = uiState.fileName.removeSuffix(".so")
                            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                .format(Date())
                            createDocLauncher.launch("${name}_$ts.patch")
                        },
                        enabled = uiState.isFileOpen
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = "导出补丁"
                        )
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
            Column(modifier = Modifier.fillMaxSize()) {
                if (uiState.errorMessage != null) {
                    Text(
                        text = "打开失败: ${uiState.errorMessage}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(16.dp),
                    )
                }

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

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize(),
                ) {
                    when (currentTab) {
                        EditorTab.STRUCTURE -> StructureTab(
                            sections = uiState.sections,
                            symbols = uiState.symbols,
                            dynamicSymbols = uiState.dynamicSymbols,
                            onSectionClick = { section ->
                                viewModel.setSelectedOffset(section.offset)
                                viewModel.setTab(EditorTab.HEX)
                                viewModel.loadHexData(section.offset)
                            },
                            onSymbolClick = { symbol ->
                                viewModel.setSelectedOffset(symbol.address)
                                viewModel.setTab(EditorTab.DISASSEMBLY)
                                viewModel.loadDisassembly(symbol.address)
                            },
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
