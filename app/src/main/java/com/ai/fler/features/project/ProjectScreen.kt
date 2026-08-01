package com.ai.fler.features.project

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.data.entity.Project
import com.ai.fler.feature.project.AnalyzeResult
import com.ai.fler.feature.project.AnalysisProgress
import com.ai.fler.feature.project.AnalysisStage
import com.ai.fler.feature.project.ProjectListState
import com.ai.fler.feature.project.ProjectViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 项目管理主界面。
 *
 * 显示项目列表，支持新建、删除和分析操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    viewModel: ProjectViewModel = hiltViewModel(),
    onProjectClick: (Long) -> Unit = {}
) {
    val state by viewModel.projectListState.collectAsStateWithLifecycle()
    val progress by viewModel.analysisProgress.collectAsStateWithLifecycle()
    var showNewProjectDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Flutter") },
                actions = {
                    IconButton(onClick = { viewModel.refreshProjects() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewProjectDialog = true }
            ) {
                Icon(Icons.Default.Add, contentDescription = "新建项目")
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading && state.projects.isEmpty() -> {
                    LoadingContent()
                }
                state.projects.isEmpty() -> {
                    EmptyContent(onAddClick = { showNewProjectDialog = true })
                }
                else -> {
                    ProjectList(
                        projects = state.projects,
                        onProjectClick = onProjectClick,
                        onDeleteClick = { project -> viewModel.deleteProject(project) },
                        onAnalyzeClick = { project -> viewModel.startAnalysis(project.id) }
                    )
                }
            }

            // 分析进度对话框
            if (progress.stage != AnalysisStage.Idle && progress.stage != AnalysisStage.Completed) {
                AnalysisProgressDialog(
                    progress = progress,
                    onDismiss = { viewModel.dismissAnalysisDialog() }
                )
            }
        }
    }

    // 新建项目对话框
    if (showNewProjectDialog) {
        NewProjectDialog(
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, apkPath ->
                viewModel.createProject(name, apkPath)
                showNewProjectDialog = false
            }
        )
    }
}

// ========== 加载状态 ==========

@Composable
private fun LoadingContent() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator()
    }
}

// ========== 空状态 ==========

@Composable
private fun EmptyContent(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无项目",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "点击右下角按钮创建第一个项目",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}

// ========== 项目列表 ==========

@Composable
private fun ProjectList(
    projects: List<Project>,
    onProjectClick: (Long) -> Unit,
    onDeleteClick: (Project) -> Unit,
    onAnalyzeClick: (Project) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = projects,
            key = { it.id }
        ) { project ->
            ProjectCard(
                project = project,
                onClick = { onProjectClick(project.id) },
                onDelete = { onDeleteClick(project) },
                onAnalyze = { onAnalyzeClick(project) }
            )
        }
    }
}

// ========== 项目卡片 ==========

@Composable
private fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    onAnalyze: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 项目图标
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(getStatusColor(project.status).copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = getStatusColor(project.status)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                // 项目信息
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = formatDate(project.updatedAt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // 状态标签
                AssistChip(
                    onClick = {},
                    label = { Text(getStatusLabel(project.status)) }
                )

                // 删除按钮（直接触发，配合下方的确认对话框防误删）
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除"
                    )
                }
            }

            // 分析结果摘要
            if (project.status == Project.STATUS_COMPLETED) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SummaryItem("Dart", project.dartVersion ?: "N/A")
                }
            }

            // 分析按钮（仅对未完成的项目显示）
            if (project.status != Project.STATUS_COMPLETED &&
                project.status != Project.STATUS_ANALYZING &&
                project.status != Project.STATUS_EXTRACTING) {
                Spacer(modifier = Modifier.height(12.dp))
                TextButton(
                    onClick = onAnalyze,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Analytics,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("开始分析")
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除项目") },
            text = {
                Text("确定要删除项目 \"${project.name}\" 吗？\n所有分析记录和提取的 so 文件将被一并清除，此操作不可撤销。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消")
                }
            }
        )
    }
}

// ========== 摘要项 ==========

@Composable
private fun SummaryItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

// ========== 分析进度对话框 ==========

@Composable
private fun AnalysisProgressDialog(
    progress: AnalysisProgress,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = {
            // 仅在已完成或失败时允许点外部/返回键关闭
            if (progress.stage == AnalysisStage.Completed || progress.stage == AnalysisStage.Failed) {
                onDismiss()
            }
        },
        title = {
            // 阶段切换：标题淡入淡出 + 轻微位移；保存结果阶段标题旁加旋转 spinner
            AnimatedContent(
                targetState = progress.stage,
                transitionSpec = {
                    (fadeIn() + slideInVertically(initialOffsetY = { it / 4 })) togetherWith
                        (fadeOut() + slideOutVertically(targetOffsetY = { -it / 4 }))
                },
                label = "stage-title"
            ) { stage ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = getStageTitle(stage),
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (stage == AnalysisStage.SavingResults) {
                        Spacer(modifier = Modifier.width(8.dp))
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        },
        text = {
            Column {
                // 进度条平滑过渡（阶段间不再跳变）
                val animatedProgress by animateFloatAsState(targetValue = progress.progress)
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                AnimatedContent(
                    targetState = progress.message,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "stage-msg"
                ) { msg ->
                    Text(
                        text = msg,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (progress.error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Error: ${progress.error}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (progress.stage == AnalysisStage.Completed || progress.stage == AnalysisStage.Failed) {
                TextButton(onClick = onDismiss) {
                    Text("关闭")
                }
            }
        }
    )
}

// ========== 新建项目对话框 ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var apkPath by remember { mutableStateOf("") }
    var showFilePicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            // 将 content:// URI 复制到 app 私有目录，确保后续能用文件路径访问
            // （GetContent 返回的是临时权限 URI，进程重启后失效，也不能直接当 File 路径）
            val localFile = copyUriToLocalCache(context, it, getFileNameFromUri(context, it))
            val path = localFile?.absolutePath ?: it.toString()
            apkPath = path
            // 如果名称为空，使用文件名作为项目名
            if (name.isBlank()) {
                name = localFile?.name ?: getFileNameFromUri(context, it)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建项目") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("项目名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))

                // APK 选择区域
                Card(
                    onClick = { showFilePicker = true; filePickerLauncher.launch("application/vnd.android.package-archive") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (apkPath.isBlank()) "选择 APK 文件" else getFileNameFromPath(apkPath),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (apkPath.isBlank()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                            if (apkPath.isNotBlank()) {
                                Text(
                                    text = apkPath,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }

                // 备选：直接输入路径
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = apkPath,
                    onValueChange = { apkPath = it },
                    label = { Text("或直接输入 APK 路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isNotBlank() && apkPath.isNotBlank()) {
                        onCreate(name, apkPath)
                    }
                },
                enabled = name.isNotBlank() && apkPath.isNotBlank()
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

// ========== 工具函数 ==========

private fun getStatusColor(status: String): Color {
    return when (status) {
        Project.STATUS_COMPLETED -> Color(0xFF4CAF50)
        Project.STATUS_ANALYZING -> Color(0xFF2196F3)
        Project.STATUS_EXTRACTING -> Color(0xFFFF9800)
        Project.STATUS_FAILED -> Color(0xFFF44336)
        else -> Color(0xFF9E9E9E)
    }
}

private fun getStatusLabel(status: String): String {
    return when (status) {
        Project.STATUS_CREATED -> "已创建"
        Project.STATUS_EXTRACTING -> "提取中"
        Project.STATUS_ANALYZING -> "分析中"
        Project.STATUS_COMPLETED -> "已完成"
        Project.STATUS_FAILED -> "失败"
        else -> "未知"
    }
}

private fun getStageTitle(stage: AnalysisStage): String {
    return when (stage) {
        AnalysisStage.Extracting -> "正在提取文件"
        AnalysisStage.DetectingVersion -> "正在检测版本"
        AnalysisStage.LoadingEngine -> "正在加载引擎"
        AnalysisStage.Analyzing -> "正在分析"
        AnalysisStage.SavingResults -> "正在保存结果"
        AnalysisStage.Completed -> "分析完成"
        AnalysisStage.Failed -> "分析失败"
        else -> "处理中"
    }
}

private fun formatDate(timestamp: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun getFileNameFromUri(context: android.content.Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) {
                return it.getString(nameIndex) ?: "Unknown"
            }
        }
    }
    return uri.lastPathSegment ?: "Unknown"
}

private fun getFileNameFromPath(path: String): String {
    return path.substringAfterLast("/", path)
}

/**
 * 将 content:// URI 的内容复制到 app cacheDir，返回本地文件。
 *
 * 为什么需要这一步：
 * - GetContent 返回的 content:// URI 是临时权限，进程重启后失效
 * - ZipFile / File 需要真实文件路径，无法直接读取 content://
 * - 复制到本地文件后 Project.apkPath 存真实绝对路径，后续流程全链路都能用
 *
 * @return 本地文件（成功）或 null（失败）
 */
private fun copyUriToLocalCache(
    context: android.content.Context,
    uri: Uri,
    displayName: String
): java.io.File? {
    return try {
        // 去重：cacheDir 下用 URI hash + 显示名，避免多次复制同一个 APK
        val safeName = displayName.replace('/', '_').takeIf { it.isNotBlank() } ?: "unknown.apk"
        val hashId = uri.toString().hashCode().toUInt().toString(16)
        val outFile = java.io.File(context.cacheDir, "apk_import_${hashId}_${safeName}")

        // 若已存在且大小相同，跳过复制（导入过同一个 APK）
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
        pfd?.use {
            val statSize = it.statSize
            if (outFile.exists() && outFile.length() == statSize) {
                android.util.Log.i("ProjectScreen", "APK 已存在本地，跳过复制: ${outFile.name} (${statSize} bytes)")
                return outFile
            }
        }

        context.contentResolver.openInputStream(uri)?.use { input ->
            java.io.FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    total += read
                }
                android.util.Log.i("ProjectScreen", "APK 复制完成: ${outFile.absolutePath} (${total} bytes)")
            }
        }
        outFile.takeIf { it.exists() && it.length() > 0 }
    } catch (e: Exception) {
        android.util.Log.e("ProjectScreen", "复制 APK 到本地失败: ${e.message}", e)
        null
    }
}
