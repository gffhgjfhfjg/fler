package com.ai.fler.features.engine

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.service.EnginePackManager
import com.ai.fler.ui.components.CardListTile

/**
 * 引擎下载/管理页面。
 *
 * 展示引擎包就绪状态、已安装版本列表，以及下载进度。
 * 集成在设置 Tab 中，作为引擎包管理入口。
 */
@Composable
fun EngineDownloadScreen(
    onStartDownload: () -> Unit = {},
    viewModel: EngineViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = "引擎包", style = MaterialTheme.typography.headlineMedium)

        // 引擎就绪状态
        CardListTile(
            title = if (uiState.isReady) "✅ 引擎已就绪" else "⚠️ 引擎未就绪",
            subtitle = if (uiState.isReady) {
                "已安装 ${uiState.installedVersions.size} 个 Dart 版本"
            } else {
                "需要下载引擎包才能分析 APK"
            },
            onClick = {},
        )

        // 自定义下载源提示
        if (uiState.isCustomSource) {
            Text(
                text = "⚠️ 当前使用自定义下载源，可能不是最新版本",
                color = MaterialTheme.colorScheme.tertiary,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        // 已安装版本列表
        if (uiState.isReady && uiState.installedVersions.isNotEmpty()) {
            Text(
                text = "已安装版本",
                style = MaterialTheme.typography.titleMedium,
            )
            uiState.installedVersions.forEach { version ->
                CardListTile(
                    title = "Dart $version",
                    subtitle = "引擎版本",
                    onClick = {},
                )
            }
        }

        // 下载进度
        uiState.progress?.let { progress ->
            if (progress.phase != EnginePackManager.EngineProgress.Phase.IDLE &&
                progress.phase != EnginePackManager.EngineProgress.Phase.COMPLETED
            ) {
                Card {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = phaseLabel(progress.phase),
                            style = MaterialTheme.typography.titleMedium,
                        )

                        LinearProgressIndicator(
                            progress = { progress.overallProgress },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Text(
                            text = "%.0f%%".format(progress.overallProgress * 100),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Text(
                            text = progressDetail(progress),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // 错误提示
        uiState.errorMessage?.let { error ->
            Text(
                text = "❌ $error",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // 操作按钮
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!uiState.isReady || uiState.isDownloading) {
                Button(
                    onClick = {
                        onStartDownload()
                        viewModel.startDownload()
                    },
                    enabled = !uiState.isDownloading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (uiState.isDownloading) "下载中..." else "下载引擎包")
                }
            }

            if (uiState.isReady) {
                OutlinedButton(
                    onClick = { viewModel.clearEngines() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("清除引擎")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // 说明
        Text(
            text = "引擎包包含 12 个 Dart 版本的分析引擎 + Capstone 反汇编库，约 10-14 MB。" +
                    "首次使用需下载，后续启动自动检测就绪状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun phaseLabel(phase: EnginePackManager.EngineProgress.Phase): String = when (phase) {
    EnginePackManager.EngineProgress.Phase.DOWNLOADING -> "下载中"
    EnginePackManager.EngineProgress.Phase.VERIFYING -> "校验中"
    EnginePackManager.EngineProgress.Phase.EXTRACTING -> "解压中"
    EnginePackManager.EngineProgress.Phase.LOADING -> "加载中"
    EnginePackManager.EngineProgress.Phase.COMPLETED -> "完成"
    EnginePackManager.EngineProgress.Phase.FAILED -> "失败"
    EnginePackManager.EngineProgress.Phase.IDLE -> "等待中"
}

private fun progressDetail(progress: EnginePackManager.EngineProgress): String = when (progress.phase) {
    EnginePackManager.EngineProgress.Phase.DOWNLOADING -> {
        val mb = progress.downloadedBytes / (1024.0 * 1024.0)
        val totalMb = progress.totalBytes / (1024.0 * 1024.0)
        if (progress.totalBytes > 0) {
            "%.1f / %.1f MB · %s".format(mb, totalMb, progress.speed)
        } else {
            "%.1f MB · %s".format(mb, progress.speed)
        }
    }
    EnginePackManager.EngineProgress.Phase.EXTRACTING -> "%.0f%%".format(progress.extractProgress * 100)
    EnginePackManager.EngineProgress.Phase.VERIFYING -> "SHA256 校验中..."
    EnginePackManager.EngineProgress.Phase.FAILED -> progress.errorMessage ?: "未知错误"
    else -> ""
}
