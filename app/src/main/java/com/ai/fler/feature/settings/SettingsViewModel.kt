package com.ai.fler.feature.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.mcp.McpConfig
import com.ai.fler.core.mcp.McpToolHandlers
import com.ai.fler.core.service.EnginePackManager
import com.ai.fler.core.service.EngineSourceConfig
import com.ai.fler.core.service.EngineUpdate
import com.ai.fler.features.mcp.McpServerManager
import com.ai.fler.features.mcp.McpServerService
import com.ai.fler.features.mcp.McpStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 设置 ViewModel。
 *
 * 管理设置页面的状态，包括引擎更新检测、下载源配置、项目缓存清理与 MCP 服务器。
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val application: Application,
    private val enginePackManager: EnginePackManager,
    private val sourceConfig: EngineSourceConfig,
    private val mcpConfig: McpConfig,
    private val mcpServerManager: McpServerManager,
    private val toolHandlers: McpToolHandlers,
) : ViewModel() {

    private val _updateState = MutableStateFlow(UpdateCheckState())
    val updateState: StateFlow<UpdateCheckState> = _updateState.asStateFlow()

    private val _installedVersions = MutableStateFlow<List<String>>(emptyList())
    val installedVersions: StateFlow<List<String>> = _installedVersions.asStateFlow()

    private val _sourceState = MutableStateFlow(sourceStateFromConfig())
    val sourceState: StateFlow<EngineSourceState> = _sourceState.asStateFlow()

    /** 缓存清理结果（释放字节数）；null 表示未操作。 */
    private val _cacheCleanResult = MutableStateFlow<Long?>(null)
    val cacheCleanResult: StateFlow<Long?> = _cacheCleanResult.asStateFlow()

    /** MCP 服务器状态（配置 + 运行状态聚合）。 */
    private val _mcpState = MutableStateFlow(McpUiState())
    val mcpState: StateFlow<McpUiState> = _mcpState.asStateFlow()

    /** MCP 工具列表（名称 + 解释）。 */
    val mcpTools: List<McpToolHandlers.McpTool>
        get() = toolHandlers.tools.values.sortedBy { it.name }

    init {
        loadInstalledVersions()
        // 引擎版本变化（下载完成/清除）时实时刷新已安装版本，无需重启
        viewModelScope.launch {
            enginePackManager.versionsEpoch.collect {
                _installedVersions.value = enginePackManager.listInstalledVersions()
            }
        }
        // 聚合 MCP 配置与运行状态
        viewModelScope.launch {
            combineMcpState()
        }
    }

    private suspend fun combineMcpState() {
        combine(
            mcpConfig.enabled,
            mcpConfig.bindMode,
            mcpConfig.port,
            mcpConfig.token,
            mcpConfig.patchEnabled,
        ) { enabled, bindMode, port, token, patchEnabled ->
            McpConfigSnapshot(enabled, bindMode, port, token, patchEnabled, "")
        }.combine(mcpConfig.exportTreeUri) { cfg, exportTreeUri ->
            cfg.copy(exportTreeUri = exportTreeUri)
        }.combine(mcpServerManager.status) { cfg, status ->
            McpUiState(
                enabled = cfg.enabled,
                bindMode = cfg.bindMode,
                port = cfg.port,
                token = cfg.token,
                patchEnabled = cfg.patchEnabled,
                exportTreeUri = cfg.exportTreeUri,
                isRunning = status.isRunning,
                activeSessions = status.activeSessions,
                localUrl = status.localUrl,
                lanUrl = status.lanUrl,
                sseLocalUrl = status.sseLocalUrl,
                sseLanUrl = status.sseLanUrl,
                errorMessage = status.errorMessage,
            )
        }.collect { _mcpState.value = it }
    }

    // ========== MCP Server ==========

    fun mcpStartServer() {
        mcpConfig.setEnabled(true)
        if (mcpConfig.bindMode.value == McpConfig.BindMode.LAN) {
            // 局域网模式：前台服务保活
            McpServerService.start(application)
        } else {
            mcpServerManager.start()
        }
    }

    fun mcpStopServer() {
        mcpConfig.setEnabled(false)
        McpServerService.stop(application)
        mcpServerManager.stop()
    }

    fun mcpSetBindMode(mode: McpConfig.BindMode) {
        mcpConfig.setBindMode(mode)
    }

    fun mcpSetPort(port: Int) {
        mcpConfig.setPort(port.coerceIn(1024, 65535))
    }

    fun mcpSetToken(token: String) {
        mcpConfig.setToken(token)
    }

    fun mcpSetPatchEnabled(value: Boolean) {
        mcpConfig.setPatchEnabled(value)
    }

    fun mcpSetExportTreeUri(value: String) {
        mcpConfig.setExportTreeUri(value)
    }

    /**
     * 清理项目缓存文件 + 内存态。
     *
     *  磁盘：
     *   - cacheDir/：apk_import_* / so_import_* / extracted_* / analysis_*.db{,-wal,-shm}
     *                patches / blutter_tmp / fler-runtime-libs.7z / dartvm-*.7z
     *   - filesDir/：undo / mcp_patches
     *
     *  内存：
     *   - SoEditorCache（sections/symbols/functions/注入标记/Dart 标签）
     *   - AnalysisSession（所有 Rizin open handle）
     *   - BackupManager（撤销栈内存、备份标记）
     *
     *  不清理：引擎文件（由「清除引擎」按钮负责）、Room 数据库、MCP 配置。
     */
    fun cleanProjectCache() {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                enginePackManager.cleanProjectCaches()
            }
            _cacheCleanResult.value = freed
        }
    }

    fun clearCacheCleanResult() {
        _cacheCleanResult.value = null
    }

    /**
     * 从配置读取当前源状态。
     */
    private fun sourceStateFromConfig(): EngineSourceState {
        return EngineSourceState(
            manifestUrl = sourceConfig.manifestUrl,
            githubProxy = sourceConfig.githubProxy,
            isCustom = sourceConfig.isCustom()
        )
    }

    /**
     * 加载本地已安装版本。
     */
    fun loadInstalledVersions() {
        viewModelScope.launch {
            try {
                _installedVersions.value = enginePackManager.listInstalledVersions()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 检查引擎更新。
     */
    fun checkForUpdates() {
        viewModelScope.launch {
            _updateState.value = UpdateCheckState(isChecking = true)

            try {
                val update = enginePackManager.checkForUpdates()
                _updateState.value = if (update != null) {
                    UpdateCheckState(update = update, hasUpdate = true)
                } else {
                    UpdateCheckState(hasUpdate = false, lastChecked = System.currentTimeMillis())
                }
            } catch (e: Exception) {
                _updateState.value = UpdateCheckState(
                    errorMessage = e.message ?: "检查更新失败"
                )
            }
        }
    }

    /**
     * 清除已安装引擎包（版本列表经 versionsEpoch 自动刷新）。
     */
    fun clearEngines() {
        viewModelScope.launch {
            try {
                enginePackManager.clearEngines()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * 保存下载源配置。
     */
    fun saveSourceConfig(
        manifestUrl: String,
        githubProxy: String
    ) {
        sourceConfig.manifestUrl = manifestUrl.trim()
        sourceConfig.githubProxy = githubProxy.trim()

        _sourceState.value = sourceStateFromConfig()
    }

    /**
     * 重置下载源为默认值。
     */
    fun resetSourceConfig() {
        sourceConfig.resetToDefault()
        _sourceState.value = sourceStateFromConfig()
    }
}

/**
 * 更新检测状态。
 */
data class UpdateCheckState(
    val isChecking: Boolean = false,
    val hasUpdate: Boolean = false,
    val update: EngineUpdate? = null,
    val lastChecked: Long = 0L,
    val errorMessage: String? = null
)

/**
 * 引擎下载源状态。
 */
data class EngineSourceState(
    val manifestUrl: String = "",
    val githubProxy: String = "",
    val isCustom: Boolean = false
)

/**
 * MCP 服务器 UI 状态（配置 + 运行状态聚合）。
 */
data class McpUiState(
    val enabled: Boolean = false,
    val bindMode: com.ai.fler.core.mcp.McpConfig.BindMode = com.ai.fler.core.mcp.McpConfig.BindMode.LOCAL,
    val port: Int = com.ai.fler.core.mcp.McpConfig.DEFAULT_PORT,
    val token: String = "",
    val patchEnabled: Boolean = false,
    val exportTreeUri: String = "",
    val isRunning: Boolean = false,
    val activeSessions: Int = 0,
    val localUrl: String = "",
    val lanUrl: String = "",
    val sseLocalUrl: String = "",
    val sseLanUrl: String = "",
    val errorMessage: String? = null,
)

/** MCP 配置快照（combine 中间值）。 */
private data class McpConfigSnapshot(
    val enabled: Boolean,
    val bindMode: com.ai.fler.core.mcp.McpConfig.BindMode,
    val port: Int,
    val token: String,
    val patchEnabled: Boolean,
    val exportTreeUri: String = "",
)
