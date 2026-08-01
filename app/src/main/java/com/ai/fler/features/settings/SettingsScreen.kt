package com.ai.fler.features.settings

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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Update
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.feature.settings.McpUiState
import com.ai.fler.feature.settings.SettingsViewModel
import com.ai.fler.features.engine.EngineDownloadScreen
import com.ai.fler.ui.components.CardListTile

/**
 * 设置 Tab。
 *
 * 集成引擎包管理、版本更新检测、主题切换、关于等入口。
 */
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenMcpSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val sourceState by viewModel.sourceState.collectAsStateWithLifecycle()
    val cacheCleanResult by viewModel.cacheCleanResult.collectAsStateWithLifecycle()
    val mcpState by viewModel.mcpState.collectAsStateWithLifecycle()
    var showCacheCleanConfirm by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }

        // 引擎包管理
        item {
            EngineDownloadScreen()
        }

        // 引擎版本更新检测
        item {
            UpdateCheckCard(
                state = updateState,
                installedVersions = installedVersions,
                onCheckForUpdates = { viewModel.checkForUpdates() }
            )
        }

        // 下载源配置
        item {
            EngineSourceCard(
                state = sourceState,
                onSave = { primary, fallback, checksum, version ->
                    viewModel.saveSourceConfig(primary, fallback, checksum, version)
                },
                onReset = { viewModel.resetSourceConfig() }
            )
        }

        // MCP 服务器（紧凑入口，详细配置在二级 Screen）
        item {
            McpEntryCard(
                state = mcpState,
                onClick = onOpenMcpSettings
            )
        }

        // 项目缓存清理
        item {
            CacheCleanCard(
                onClean = { showCacheCleanConfirm = true },
                result = cacheCleanResult,
                onClearResult = { viewModel.clearCacheCleanResult() }
            )
        }

        // 关于
        item {
            AboutCard(onClick = onOpenAbout)
        }
    }

    if (showCacheCleanConfirm) {
        AlertDialog(
            onDismissRequest = { showCacheCleanConfirm = false },
            title = { Text("清理项目缓存") },
            text = {
                Text("将删除 APK/SO 导入副本、提取产物、分析数据库（analysis_*.db）、残留引擎包文件与补丁导出文件。引擎文件与已导入的分析记录不受影响。")
            },
            confirmButton = {
                TextButton(onClick = {
                    showCacheCleanConfirm = false
                    viewModel.cleanProjectCache()
                }) { Text("清理") }
            },
            dismissButton = {
                TextButton(onClick = { showCacheCleanConfirm = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun UpdateCheckCard(
    state: com.ai.fler.feature.settings.UpdateCheckState,
    installedVersions: List<String>,
    onCheckForUpdates: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "引擎版本",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                if (state.isChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onCheckForUpdates) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "检查更新"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 当前引擎版本（单卡片，不再展示完整已安装版本列表）
            val currentVersion = installedVersions.firstOrNull()
            if (currentVersion != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "当前引擎: Dart $currentVersion",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            } else {
                Text(
                    text = "尚未安装任何引擎",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                state.isChecking -> {
                    Text(
                        text = "正在检查更新...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.errorMessage != null -> {
                    Text(
                        text = "检查失败: ${state.errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                state.hasUpdate && state.update != null -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Update,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "发现新版本: ${state.update!!.version}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (!state.update!!.releaseNotes.isNullOrBlank()) {
                        Text(
                            text = state.update!!.releaseNotes,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { /* 触发引擎下载流程 */ },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("下载更新")
                    }
                }

                !state.hasUpdate && state.lastChecked > 0 -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "已是最新版本",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }

                else -> {
                    OutlinedButton(
                        onClick = onCheckForUpdates,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("检查更新")
                    }
                }
            }
        }
    }
}

@Composable
private fun EngineSourceCard(
    state: com.ai.fler.feature.settings.EngineSourceState,
    onSave: (String, String, String, String) -> Unit,
    onReset: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var primaryUrl by remember(state) { mutableStateOf(state.primaryUrl) }
    var fallbackUrl by remember(state) { mutableStateOf(state.fallbackUrl) }
    var checksumUrl by remember(state) { mutableStateOf(state.checksumUrl) }
    var versionUrl by remember(state) { mutableStateOf(state.versionUrl) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "下载源配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (state.isCustom) {
                    Text(
                        text = "已自定义",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isEditing) {
                // 编辑模式
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = primaryUrl,
                        onValueChange = { primaryUrl = it },
                        label = { Text("主下载地址 (GitHub)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = fallbackUrl,
                        onValueChange = { fallbackUrl = it },
                        label = { Text("备用地址 (GitHub)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = checksumUrl,
                        onValueChange = { checksumUrl = it },
                        label = { Text("校验地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = versionUrl,
                        onValueChange = { versionUrl = it },
                        label = { Text("版本信息地址") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onReset() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("重置默认")
                        }
                        Button(
                            onClick = {
                                onSave(primaryUrl, fallbackUrl, checksumUrl, versionUrl)
                                isEditing = false
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("保存")
                        }
                    }
                }
            } else {
                // 展示模式
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SourceItem(label = "主下载", url = state.primaryUrl)
                    SourceItem(label = "备用", url = state.fallbackUrl)
                    SourceItem(label = "校验", url = state.checksumUrl)
                    SourceItem(label = "版本", url = state.versionUrl)
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { isEditing = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("编辑地址")
                }
            }
        }
    }
}

@Composable
private fun SourceItem(label: String, url: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1
        )
    }
}

@Composable
private fun McpEntryCard(
    state: McpUiState,
    onClick: () -> Unit
) {
    val statusText = if (state.isRunning) "运行中" else "已停止"
    val urlText = if (state.isRunning) {
        (state.localUrl.ifBlank { state.lanUrl }).let {
            if (it.isNotBlank()) "本机 $it" else statusText
        }
    } else {
        "点击进入详细配置"
    }
    CardListTile(
        title = "MCP 服务器 · $statusText",
        subtitle = urlText,
        leadingIcon = Icons.Outlined.Storage,
        onClick = onClick,
    )
}

@Composable
private fun AboutCard(
    onClick: () -> Unit
) {
    CardListTile(
        title = "关于",
        subtitle = "fler ${com.ai.fler.BuildConfig.VERSION_NAME} · 开源项目与第三方库",
        onClick = onClick,
    )
}

@Composable
private fun CacheCleanCard(
    onClean: () -> Unit,
    result: Long?,
    onClearResult: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = "项目缓存",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "APK/SO 导入副本、提取产物、分析数据库（analysis_*.db）、残留引擎包文件与补丁导出文件。引擎文件与已导入的分析记录不受影响。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onClean) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("清理项目缓存")
                }
                if (result != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    val sizeStr = if (result >= 1024 * 1024) "${result / 1024 / 1024} MB"
                                  else if (result >= 1024) "${result / 1024} KB"
                                  else "$result B"
                    Text(
                        text = "已释放 $sizeStr",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(onClick = onClearResult, modifier = Modifier.size(20.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "关闭", modifier = Modifier.size(14.dp))
                    }
                }
            }
        }
    }
}
