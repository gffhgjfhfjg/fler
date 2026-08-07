package com.ai.fler.features.settings

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
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ai.fler.core.service.EnginePackManager
import com.ai.fler.feature.settings.McpUiState
import com.ai.fler.feature.settings.SettingsViewModel
import com.ai.fler.features.engine.EngineViewModel
import com.ai.fler.ui.components.CardListTile

/**
 * 设置 Tab。
 *
 * 集成引擎包管理、版本更新检测、下载源配置、MCP 服务器、缓存清理与关于等入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    onOpenMcpSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
    engineViewModel: EngineViewModel = hiltViewModel(),
) {
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val installedVersions by viewModel.installedVersions.collectAsStateWithLifecycle()
    val sourceState by viewModel.sourceState.collectAsStateWithLifecycle()
    val cacheCleanResult by viewModel.cacheCleanResult.collectAsStateWithLifecycle()
    val mcpState by viewModel.mcpState.collectAsStateWithLifecycle()
    val engineState by engineViewModel.uiState.collectAsStateWithLifecycle()
    var showCacheCleanConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("设置") })
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 引擎包 + 引擎版本（合并为一个引擎卡片）
            item {
                EngineVersionCard(
                    state = updateState,
                    installedVersions = installedVersions,
                    engineState = engineState,
                    onRefreshManifest = { engineViewModel.loadManifest() },
                    onCheckForUpdates = { viewModel.checkForUpdates() },
                    onClearEngines = { viewModel.clearEngines() },
                    onInstallRuntime = { engineViewModel.installRuntimeLibs() },
                    onInstallSelected = { engineViewModel.installSelectedVersion() },
                    onSelectVersion = { engineViewModel.selectVersion(it) },
                    onDownloadUpdate = { engineViewModel.installRuntimeLibs(force = true) },
                )
            }

        // 下载源配置
        item {
            EngineSourceCard(
                state = sourceState,
                onSave = { manifestUrl, proxy ->
                    viewModel.saveSourceConfig(manifestUrl, proxy)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EngineVersionCard(
    state: com.ai.fler.feature.settings.UpdateCheckState,
    installedVersions: List<String>,
    engineState: com.ai.fler.features.engine.EngineViewModel.EngineUiState,
    onRefreshManifest: () -> Unit,
    onCheckForUpdates: () -> Unit,
    onClearEngines: () -> Unit,
    onInstallRuntime: () -> Unit,
    onInstallSelected: () -> Unit,
    onSelectVersion: (String) -> Unit,
    onDownloadUpdate: () -> Unit,
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
                    text = "引擎包",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )

                if (engineState.loadingManifest) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    IconButton(onClick = onRefreshManifest) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新引擎清单"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 状态图标 + 已安装版本号（芯片装饰）
            if (installedVersions.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "引擎就绪",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "引擎就绪",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    installedVersions.forEach { version ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "Dart $version",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "引擎未就绪",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "引擎未就绪，需下载引擎包",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 必装运行库状态行
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "运行库（必装）",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (engineState.isRuntimeReady) "已安装 libc++_shared.so" else "未安装",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (engineState.isRuntimeReady) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
                if (!engineState.isRuntimeReady) {
                    OutlinedButton(
                        onClick = onInstallRuntime,
                        enabled = !engineState.isDownloading,
                    ) {
                        Text("安装运行库")
                    }
                }
            }

            // 自定义下载源提示
            if (engineState.isCustomSource) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "⚠️ 当前使用自定义下载源，可能不是最新版本",
                    color = MaterialTheme.colorScheme.tertiary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 远程版本下拉
            val manifest = engineState.manifest
            if (manifest != null && manifest.engines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                EngineVersionDropdown(
                    engines = manifest.engines,
                    installedVersions = installedVersions,
                    selectedVersion = engineState.selectedVersion,
                    enabled = !engineState.isDownloading,
                    onSelect = onSelectVersion,
                )

                val selectedInstalled = engineState.selectedVersion?.let { installedVersions.contains(it) } == true
                val canInstall = engineState.selectedVersion != null && !selectedInstalled
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = onInstallSelected,
                    enabled = canInstall && !engineState.isDownloading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            engineState.isDownloading -> "下载中..."
                            selectedInstalled -> "已安装 Dart ${engineState.selectedVersion}"
                            engineState.selectedVersion != null -> "下载 Dart ${engineState.selectedVersion}"
                            else -> "请选择版本"
                        }
                    )
                }
            }

            // manifest 加载失败提示
            engineState.manifestError?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "❌ 清单获取失败: $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 下载进度
            engineState.progress?.let { progress ->
                if (progress.phase != EnginePackManager.EngineProgress.Phase.IDLE &&
                    progress.phase != EnginePackManager.EngineProgress.Phase.COMPLETED
                ) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
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
            engineState.errorMessage?.let { error ->
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "❌ $error",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // 清除引擎按钮
            if (installedVersions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onClearEngines,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("清除引擎")
                }
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
                        onClick = onDownloadUpdate,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(4.dp))
                        Text("下载更新运行库")
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

            Spacer(modifier = Modifier.height(12.dp))

            // 说明
            Text(
                text = "引擎包由「必装运行库 + 各 Dart 版本引擎」组成，按需下载。" +
                        "运行库仅需安装一次；引擎按分析需要的 Dart 版本逐个下载。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EngineVersionDropdown(
    engines: List<com.ai.fler.core.service.EngineEntry>,
    installedVersions: List<String>,
    selectedVersion: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedInstalled = selectedVersion?.let { installedVersions.contains(it) } == true
    val selectedEntry = engines.firstOrNull { it.dartVersion == selectedVersion }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { if (enabled) expanded = it }
    ) {
        OutlinedTextField(
            value = selectedVersion?.let { "Dart $it" } ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Dart 版本") },
            placeholder = { Text("选择要下载的版本") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            engines.forEach { entry ->
                val isInstalled = installedVersions.contains(entry.dartVersion)
                val isSelected = entry.dartVersion == selectedVersion
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Dart ${entry.dartVersion}",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            when {
                                isInstalled -> {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = "已安装",
                                        tint = MaterialTheme.colorScheme.tertiary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "已安装",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }

                                else -> {
                                    val mb = entry.sizeBytes / (1024.0 * 1024.0)
                                    Text(
                                        text = "%.1f MB".format(mb),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    },
                    onClick = {
                        onSelect(entry.dartVersion)
                        expanded = false
                    }
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = when {
                selectedInstalled -> "Dart $selectedVersion 已安装，无需下载"
                selectedEntry != null -> "Dart $selectedVersion 尚未安装，可下载"
                else -> "选择版本后即可下载"
            },
            style = MaterialTheme.typography.labelSmall,
            color = when {
                selectedInstalled -> MaterialTheme.colorScheme.tertiary
                selectedEntry != null -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun phaseLabel(phase: com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase): String = when (phase) {
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.DOWNLOADING -> "下载中"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.VERIFYING -> "校验中"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.EXTRACTING -> "解压中"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.LOADING -> "加载中"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.COMPLETED -> "完成"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.FAILED -> "失败"
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.IDLE -> "等待中"
}

private fun progressDetail(progress: com.ai.fler.core.service.EnginePackManager.EngineProgress): String = when (progress.phase) {
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.DOWNLOADING -> {
        val mb = progress.downloadedBytes / (1024.0 * 1024.0)
        val totalMb = progress.totalBytes / (1024.0 * 1024.0)
        if (progress.totalBytes > 0) {
            "%.1f / %.1f MB · %s".format(mb, totalMb, progress.speed)
        } else {
            "%.1f MB · %s".format(mb, progress.speed)
        }
    }
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.EXTRACTING -> "%.0f%%".format(progress.extractProgress * 100)
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.VERIFYING -> "SHA256 校验中..."
    com.ai.fler.core.service.EnginePackManager.EngineProgress.Phase.FAILED -> progress.errorMessage ?: "未知错误"
    else -> ""
}

@Composable
private fun EngineSourceCard(
    state: com.ai.fler.feature.settings.EngineSourceState,
    onSave: (String, String) -> Unit,
    onReset: () -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var manifestUrl by remember(state) { mutableStateOf(state.manifestUrl) }
    var githubProxy by remember(state) { mutableStateOf(state.githubProxy) }

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
                        value = manifestUrl,
                        onValueChange = { manifestUrl = it },
                        label = { Text("引擎清单地址 (manifest.json)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = githubProxy,
                        onValueChange = { githubProxy = it },
                        label = { Text("GitHub 加速前缀") },
                        placeholder = { Text("如 https://gh-proxy.com（留空关闭）") },
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
                                onSave(manifestUrl, githubProxy)
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
                    SourceItem(label = "引擎清单", url = state.manifestUrl)
                    SourceItem(
                        label = "GitHub 加速",
                        url = if (state.githubProxy.isBlank()) "未启用" else state.githubProxy
                    )
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
        subtitle = "Fler ${com.ai.fler.BuildConfig.VERSION_NAME} · 开源项目与第三方库",
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
