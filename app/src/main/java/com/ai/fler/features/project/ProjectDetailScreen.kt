package com.ai.fler.features.project

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.data.entity.Analysis
import com.ai.fler.data.entity.Project
import com.ai.fler.feature.project.AnalysisStage
import com.ai.fler.feature.project.ProjectDetailViewModel
import com.ai.fler.feature.project.ProjectViewModel
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 项目详情页。
 *
 * 展示项目信息、分析记录（可进入 PP/ASM 浏览）、提取的 SO 文件（可进入 SO 编辑器），
 * 并提供「运行分析」入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectDetailScreen(
    projectId: Long,
    onBack: () -> Unit = {},
    onPpBrowse: (Long) -> Unit = {},
    onAsmBrowse: (Long) -> Unit = {},
    onOpenSo: (String, Long) -> Unit = { _, _ -> },
    viewModel: ProjectDetailViewModel = hiltViewModel(),
    projectViewModel: ProjectViewModel = hiltViewModel(),
    modifier: Modifier = Modifier
) {
    val project by viewModel.project.collectAsStateWithLifecycle()
    val analyses by viewModel.analyses.collectAsStateWithLifecycle()
    val soFiles by viewModel.soFiles.collectAsStateWithLifecycle()
    val progress by projectViewModel.analysisProgress.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    // 删除失败时弹 Snackbar 提示
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeErrorMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "项目详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "返回"
                        )
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
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                project?.let { p ->
                    item {
                        ProjectInfoCard(project = p)
                    }

                    item {
                        Button(
                            onClick = {
                                if (progress.stage == AnalysisStage.Idle ||
                                    progress.stage == AnalysisStage.Completed ||
                                    progress.stage == AnalysisStage.Failed
                                ) {
                                    projectViewModel.startAnalysis(p.id)
                                }
                            },
                            enabled = progress.stage == AnalysisStage.Idle ||
                                progress.stage == AnalysisStage.Completed ||
                                progress.stage == AnalysisStage.Failed,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                imageVector = Icons.Default.Analytics,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("运行分析")
                        }
                    }
                }

                item {
                    Text(
                        text = "分析记录 (${analyses.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (analyses.isEmpty()) {
                    item {
                        Text(
                            text = "暂无分析记录，点击上方「运行分析」开始",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(analyses, key = { it.id }) { analysis ->
                        AnalysisCard(
                            analysis = analysis,
                            onPpBrowse = { onPpBrowse(analysis.id) },
                            onAsmBrowse = { onAsmBrowse(analysis.id) },
                            onDelete = { viewModel.deleteAnalysis(analysis) }
                        )
                    }
                }

                item {
                    Text(
                        text = "SO 文件 (${soFiles.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }

                if (soFiles.isEmpty()) {
                    item {
                        Text(
                            text = "尚未提取 SO 文件（运行分析后生成）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    items(soFiles, key = { it.absolutePath }) { soFile ->
                        SoFileRow(
                            soFile = soFile,
                            onClick = { onOpenSo(soFile.absolutePath, 0L) }
                        )
                    }
                }
            }

            // 分析进度对话框
            if (progress.stage != AnalysisStage.Idle && progress.stage != AnalysisStage.Completed) {
                AnalysisProgressDialog(
                    progress = progress,
                    onDismiss = { projectViewModel.dismissAnalysisDialog() }
                )
            }
        }
    }
}

@Composable
private fun ProjectInfoCard(project: Project) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = project.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                AssistChip(
                    onClick = {},
                    label = { Text(statusLabel(project.status)) }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "APK: ${project.apkPath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (project.dartVersion != null) {
                Text(
                    text = "Dart ${project.dartVersion}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AnalysisCard(
    analysis: Analysis,
    onPpBrowse: () -> Unit,
    onAsmBrowse: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "分析 #${analysis.id}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                StatusText(resultCode = analysis.resultCode)
                // 删除按钮（图标，配合下方确认对话框防误删）
                IconButton(onClick = { showDeleteConfirm = true }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "删除分析记录",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = formatTimestamp(analysis.startedAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (analysis.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = analysis.errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 2
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatBox(label = "类", value = analysis.classesCount, modifier = Modifier.weight(1f))
                StatBox(label = "方法", value = analysis.methodsCount, modifier = Modifier.weight(1f))
                StatBox(label = "PP", value = analysis.ppEntriesCount, modifier = Modifier.weight(1f))
            }

            if (analysis.resultCode == Analysis.RESULT_SUCCESS) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextButton(onClick = onPpBrowse, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("PP 浏览")
                    }
                    TextButton(onClick = onAsmBrowse, modifier = Modifier.weight(1f)) {
                        Icon(
                            imageVector = Icons.Default.Category,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("ASM 浏览")
                    }
                }
            }
        }
    }

    // 删除确认对话框（防误删）
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除分析记录") },
            text = {
                Text("确定要删除分析 #${analysis.id} 吗？\n关联的 PP 条目、Dart 类/方法、库文件记录将被一并清除，此操作不可撤销。")
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

@Composable
private fun StatBox(label: String, value: Int, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SoFileRow(soFile: File, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Memory,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = soFile.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "%.2f MB".format(soFile.length() / (1024.0 * 1024.0)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "打开",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun StatusText(resultCode: Int) {
    val (text, color) = when (resultCode) {
        Analysis.RESULT_SUCCESS -> "成功" to Color(0xFF4CAF50)
        Analysis.RESULT_GENERIC_ERROR -> "错误" to Color(0xFFF44336)
        Analysis.RESULT_INVALID_ELF -> "无效ELF" to Color(0xFFFF9800)
        Analysis.RESULT_WRONG_ARCH -> "架构错误" to Color(0xFF9C27B0)
        Analysis.RESULT_DART_NOT_FOUND -> "版本未识别" to Color(0xFFFF9800)
        Analysis.RESULT_NO_SYMBOLS -> "无符号" to Color(0xFFFF9800)
        else -> "待处理" to Color.Gray
    }
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color
    )
}

@Composable
private fun AnalysisProgressDialog(
    progress: com.ai.fler.feature.project.AnalysisProgress,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {
            if (progress.stage == AnalysisStage.Failed) onDismiss()
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
                        text = when (stage) {
                            AnalysisStage.Extracting -> "正在提取文件"
                            AnalysisStage.DetectingVersion -> "正在检测版本"
                            AnalysisStage.LoadingEngine -> "正在加载引擎"
                            AnalysisStage.Analyzing -> "正在分析"
                            AnalysisStage.SavingResults -> "正在保存结果"
                            AnalysisStage.Failed -> "分析失败"
                            else -> "处理中"
                        },
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
                        text = progress.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            if (progress.stage == AnalysisStage.Failed) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    )
}

private fun statusLabel(status: String): String = when (status) {
    Project.STATUS_CREATED -> "已创建"
    Project.STATUS_EXTRACTING -> "提取中"
    Project.STATUS_ANALYZING -> "分析中"
    Project.STATUS_COMPLETED -> "已完成"
    Project.STATUS_FAILED -> "失败"
    else -> "未知"
}

private fun formatTimestamp(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
}
