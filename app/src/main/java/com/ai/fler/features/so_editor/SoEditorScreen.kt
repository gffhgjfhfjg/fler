package com.ai.fler.features.so_editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * SO 编辑器主界面（顶层 Tab）。
 *
 * 包含 3 个 Tab：
 * 1. 结构 - ELF 节头表和符号表
 * 2. Hex - 字节级十六进制查看和编辑
 * 3. 汇编 - ARM64 指令反汇编查看 + 汇编指令编辑（点击指令行弹窗编辑）
 *
 * 支持通过 SAF 选择任意 .so 文件打开（不限于 Flutter 的 SO）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoEditorScreen(
    filePath: String = "",
    viewModel: SoEditorViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val currentTab by viewModel.currentTab.collectAsStateWithLifecycle()
    val patchedOffsets by viewModel.patchedOffsets.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // SAF 文件选择器：选择任意文件（.so 文件通常 MIME 为 application/octet-stream）
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            scope.launch {
                val localFile = copyUriToLocalCache(context, it)
                if (localFile == null) {
                    snackbarHostState.showSnackbar("文件读取失败")
                    return@launch
                }
                viewModel.openFile(localFile.absolutePath)
            }
        }
    }

    // SAF CreateDocument：导出补丁到用户可读位置（Documents 等）
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

    // SAF CreateDocument：导出修改后的 SO 二进制
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

    // 如果有传入路径且未打开，打开文件
    LaunchedEffect(filePath) {
        if (filePath.isNotBlank() && !uiState.isFileOpen && !uiState.isLoading) {
            viewModel.openFile(filePath)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = uiState.fileName.ifBlank { "SO 编辑器" },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    // 打开文件后显示返回按钮，回到最近文件/选择列表
                    if (uiState.isFileOpen) {
                        IconButton(onClick = { viewModel.closeFile() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                actions = {
                    // 打开文件按钮（始终可见，支持随时切换文件）
                    IconButton(
                        onClick = { filePickerLauncher.launch("application/octet-stream") }
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileOpen,
                            contentDescription = "打开 SO 文件"
                        )
                    }

                    // 撤销按钮
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

                    // 保存按钮：标记所有修改为已保存，清除红色高亮
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

                    // 导出菜单（补丁 / 修改后的 SO）
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
                                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                                        .format(java.util.Date())
                                    createDocLauncher.launch("${name}_$ts.patch")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("导出修改后的 SO (.so)") },
                                onClick = {
                                    showExportMenu = false
                                    val name = uiState.fileName.removeSuffix(".so")
                                    val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                                        .format(java.util.Date())
                                    createSoLauncher.launch("${name}_patched_$ts.so")
                                }
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isLoading && !uiState.isFileOpen -> {
                    LoadingContent(modifier = Modifier.align(Alignment.Center))
                }

                uiState.errorMessage != null -> {
                    ErrorContent(
                        message = uiState.errorMessage!!,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                !uiState.isFileOpen -> {
                    val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle()
                    NoFileContent(
                        onPickFile = { filePickerLauncher.launch("application/octet-stream") },
                        recentFiles = recentFiles,
                        onOpenRecent = { path ->
                            scope.launch { viewModel.openFile(path) }
                        },
                        onRemoveRecent = { path -> viewModel.removeRecent(path) },
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                else -> {
                    SoEditorContent(
                        uiState = uiState,
                        currentTab = currentTab,
                        onTabSelected = { viewModel.setTab(it) },
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
}

@Composable
private fun SoEditorContent(
    uiState: SoEditorUiState,
    currentTab: EditorTab,
    onTabSelected: (EditorTab) -> Unit,
    viewModel: SoEditorViewModel,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 文件信息条（紧凑）
        FileInfoBar(
            fileName = uiState.fileName,
            fileSize = uiState.fileSize,
            sectionCount = uiState.sections.size,
            symbolCount = uiState.symbols.size + uiState.dynamicSymbols.size
        )

        // Tab 切换
        TabRow(selectedTabIndex = currentTab.ordinal) {
            Tab(
                selected = currentTab == EditorTab.STRUCTURE,
                onClick = { onTabSelected(EditorTab.STRUCTURE) },
                text = { Text("结构") },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null
                    )
                }
            )
            Tab(
                selected = currentTab == EditorTab.HEX,
                onClick = { onTabSelected(EditorTab.HEX) },
                text = { Text("Hex") },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null
                    )
                }
            )
            Tab(
                selected = currentTab == EditorTab.DISASSEMBLY,
                onClick = { onTabSelected(EditorTab.DISASSEMBLY) },
                text = { Text("汇编") },
                icon = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null
                    )
                }
            )
        }

        // Tab 内容
        Box(modifier = Modifier.weight(1f)) {
            when (currentTab) {
                EditorTab.STRUCTURE -> {
                    StructureTab(
                        sections = uiState.sections,
                        symbols = uiState.symbols,
                        dynamicSymbols = uiState.dynamicSymbols,
                        onSectionClick = { section ->
                            viewModel.loadHexData(section.offset, section.size)
                            viewModel.setSelectedOffset(section.offset)
                        },
                        onSymbolClick = { symbol ->
                            viewModel.loadDisassembly(symbol.address)
                        }
                    )
                }

                EditorTab.HEX -> {
                    HexEditorTab(viewModel = viewModel)
                }

                EditorTab.DISASSEMBLY -> {
                    DisassemblyTab(
                        viewModel = viewModel,
                        onInstructionClick = { address ->
                            viewModel.setSelectedOffset(address)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FileInfoBar(
    fileName: String,
    fileSize: Long,
    sectionCount: Int,
    symbolCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = fileName,
                style = MaterialTheme.typography.labelMedium
            )
            Text(
                text = formatFileSize(fileSize),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "$sectionCount 节",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "$symbolCount 符号",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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

@Composable
private fun NoFileContent(
    onPickFile: () -> Unit,
    recentFiles: List<RecentFile> = emptyList(),
    onOpenRecent: (String) -> Unit = {},
    onRemoveRecent: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "SO 编辑器",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "选择任意 ELF/SO 文件查看结构、Hex、汇编并编辑",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(20.dp))
        Button(onClick = onPickFile) {
            Icon(
                imageVector = Icons.Default.FileOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.size(8.dp))
            Text("选择 SO 文件")
        }

        // 最近文件列表
        if (recentFiles.isNotEmpty()) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "最近打开",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            recentFiles.forEach { file ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .clickable { onOpenRecent(file.path) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Memory,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = file.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = file.path,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1
                        )
                    }
                    IconButton(
                        onClick = { onRemoveRecent(file.path) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "移除",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
    }
}

/**
 * 将 SAF 返回的 content:// URI 复制到 app cacheDir，返回本地文件。
 *
 * 为什么需要复制：
 * - content:// URI 是临时权限，且无法直接作为 File 路径给 ElfParser 使用
 * - 复制到 cacheDir/so_import_<hash>_<name> 后可长期访问，直至 cache 被清理
 *
 * @return 本地文件（成功）或 null（失败）
 */
private suspend fun copyUriToLocalCache(
    context: Context,
    uri: Uri
): File? = withContext(Dispatchers.IO) {
    try {
        val displayName = getDisplayName(context, uri) ?: "unknown.so"
        val safeName = displayName.replace('/', '_').ifBlank { "unknown.so" }
        val hashId = uri.toString().hashCode().toUInt().toString(16)
        val outFile = File(context.cacheDir, "so_import_${hashId}_$safeName")

        // 若已存在且大小相同，跳过复制
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        pfd?.use {
            val statSize = it.statSize
            if (outFile.exists() && outFile.length() == statSize) {
                return@withContext outFile
            }
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(outFile).use { output ->
                input.copyTo(output)
            }
        }
        outFile.takeIf { it.exists() && it.length() > 0 }
    } catch (e: Exception) {
        null
    }
}

private fun getDisplayName(context: Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) {
                return it.getString(idx)
            }
        }
    }
    return uri.lastPathSegment
}
