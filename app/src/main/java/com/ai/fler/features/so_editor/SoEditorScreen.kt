package com.ai.fler.features.so_editor

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import com.ai.fler.ui.animation.AnimDuration
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Undo
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
    var isCopying by remember { mutableStateOf(false) }

    // SAF 文件选择器：选择任意文件（.so 文件通常 MIME 为 application/octet-stream）
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { u ->
            // 复制放到 Dispatchers.IO，避免主线程 Binder/磁盘阻塞（MIUI APP_SCOUT_WARNING）
            isCopying = true
            scope.launch(Dispatchers.IO) {
                val localFile = copyUriToLocalCache(context, u)
                withContext(Dispatchers.Main) {
                    isCopying = false
                    if (localFile == null) {
                        snackbarHostState.showSnackbar("文件读取失败")
                        return@withContext
                    }
                    viewModel.openFile(localFile.absolutePath)
                }
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

    // 拦截系统返回键：已打开文件时，非结构 Tab → 先回结构 Tab，结构 Tab 关闭文件
    BackHandler(enabled = uiState.isFileOpen) {
        if (currentTab == EditorTab.STRUCTURE) {
            viewModel.closeFile()
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
                            text = uiState.fileName.ifBlank { "SO 编辑器" },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        if (uiState.isFileOpen) {
                            Text(
                                text = formatFileSize(uiState.fileSize),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = {
                    // 打开文件后显示返回按钮：
                    // - 在非结构 Tab 下：返回到结构 Tab
                    // - 在结构 Tab 下：关闭文件，回到最近文件列表
                    if (uiState.isFileOpen) {
                        IconButton(onClick = {
                            if (currentTab == EditorTab.STRUCTURE) {
                                viewModel.closeFile()
                            } else {
                                viewModel.setTab(EditorTab.STRUCTURE)
                            }
                        }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "返回"
                            )
                        }
                    }
                },
                actions = {
                    // 打开文件按钮（始终可见，支持随时切换文件；复制中禁用避免叠加触发
                    IconButton(
                        onClick = { filePickerLauncher.launch("application/octet-stream") },
                        enabled = !isCopying
                    ) {
                        if (isCopying) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.FileOpen,
                                contentDescription = "打开 SO 文件"
                            )
                        }
                    }

                    // 撤销按钮
                    IconButton(
                        onClick = {
                            // undo 内部已切 IO 线程，结果通过主线程回调返回
                            viewModel.undo { record ->
                                scope.launch {
                                    if (record != null) {
                                        snackbarHostState.showSnackbar("已撤销: 0x${record.address.toString(16)}")
                                    } else {
                                        snackbarHostState.showSnackbar("无可撤销操作")
                                    }
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

                !uiState.isFileOpen && !isCopying -> {
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

                isCopying -> {
                    CopyingContent(modifier = Modifier.align(Alignment.Center))
                }

                uiState.isAnalyzing -> {
                    AnalyzingContent(modifier = Modifier.align(Alignment.Center))
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
                onClick = { onTabSelected(EditorTab.STRUCTURE) },
                text = { Text("结构") },
            )
            Tab(
                selected = currentTab == EditorTab.HEX,
                onClick = { onTabSelected(EditorTab.HEX) },
                text = { Text("Hex") },
            )
            Tab(
                selected = currentTab == EditorTab.DISASSEMBLY,
                onClick = { onTabSelected(EditorTab.DISASSEMBLY) },
                text = { Text("汇编") },
            )
            Tab(
                selected = currentTab == EditorTab.EMULATION,
                onClick = { onTabSelected(EditorTab.EMULATION) },
                text = { Text("仿真") },
            )
        }

        // Tab 内容（方向性转场：下方滑入 + 淡入 / 上方滑出 + 淡出）
        AnimatedContent(
            targetState = currentTab,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                // 回结构 Tab：快 fade(200ms)，不做 slow 滑入。
                // 原全局 slow 滑入导致 500ms 内 Layout 未完成，StructureTab 的滚动+闪烁只能排队等待，
                // 结构 Tab 呈现出「先停在原位置 500ms 再被拉走」的粘着感；用 fast fade 直接显示，
                // 然后立即 scrollToItem + 闪烁，整体流畅度显著提升。
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
                EditorTab.STRUCTURE -> {
                    StructureTab(
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
                            viewModel.setSelectedOffset(symbol.address)
                            viewModel.setStructureFlashAddress(symbol.address)
                            viewModel.setTab(EditorTab.DISASSEMBLY)
                            viewModel.loadDisassembly(symbol.address, highlightAfterLoad = symbol.address)
                        },
                        onFunctionClick = { func ->
                            viewModel.setSelectedOffset(func.vaddr)
                            viewModel.setStructureFlashAddress(func.vaddr)
                            viewModel.setTab(EditorTab.DISASSEMBLY)
                            viewModel.loadDisassembly(func.vaddr, highlightAfterLoad = func.vaddr)
                        },
                        onStringsTabSelected = { viewModel.loadStrings() },
                        viewModel = viewModel
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

                EditorTab.EMULATION -> {
                    EmulationTab(
                        viewModel = hiltViewModel(),
                        filePath = uiState.filePath
                    )
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
private fun CopyingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "正在复制 SO 文件到本地...",
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

        // 用 ContentResolver.query 读 SIZE（替代 openFileDescriptor），避免 Binder openTypedAssetFile
        // 在 MIUI 云盘/SD 卡环境下 openFileDescriptor 单步可达 2-3s，引发 APP_SCOUT_WARNING。
        val remoteSize = queryUriSizeOrNull(context, uri)
        if (remoteSize != null) {
            if (outFile.exists() && outFile.length() == remoteSize) {
                android.util.Log.i("SoEditorScreen", "SO 已存在本地，跳过复制: ${outFile.name} (${remoteSize} bytes)")
                return@withContext outFile
            }
        } else {
            // 回退：极少数 ContentProvider 不返回 OpenableColumns.SIZE，仍用 openFileDescriptor
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                if (outFile.exists() && outFile.length() == pfd.statSize) {
                    return@withContext outFile
                }
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
    // 只查 DISPLAY_NAME 一列（避免全列 projection 跨进程多读大字段，SD/云盘场景下减缓 Binder 开销）
    val cursor = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null, null, null
        )
    } catch (_: Throwable) { return null }
    cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && !it.isNull(idx)) {
                return it.getString(idx)
            }
        }
    }
    return uri.lastPathSegment
}

/**
 * 轻量读 URI SIZE：用 ContentResolver.query(OpenableColumns.SIZE)，
 * 避免 openFileDescriptor(ParcelFileDescriptor) 触发 Binder openTypedAssetFile
 * 在 MIUI 云盘/远程文档/SD 卡上 2-3s 阻塞引发 APP_SCOUT_WARNING。
 */
private fun queryUriSizeOrNull(context: Context, uri: Uri): Long? {
    val cursor = try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null, null, null
        )
    } catch (_: Throwable) { return null }
    return cursor?.use {
        if (it.moveToFirst()) {
            val idx = it.getColumnIndex(OpenableColumns.SIZE)
            if (idx >= 0 && !it.isNull(idx)) it.getLong(idx) else null
        } else null
    }
}
