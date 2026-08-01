package com.ai.fler.feature.settings

import android.app.Application
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.mcp.McpConfig
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
            McpConfigSnapshot(enabled, bindMode, port, token, patchEnabled)
        }.combine(mcpServerManager.status) { cfg, status ->
            McpUiState(
                enabled = cfg.enabled,
                bindMode = cfg.bindMode,
                port = cfg.port,
                token = cfg.token,
                patchEnabled = cfg.patchEnabled,
                isRunning = status.isRunning,
                activeSessions = status.activeSessions,
                localUrl = status.localUrl,
                lanUrl = status.lanUrl,
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

    /**
     * 清理项目缓存文件（APK 导入副本、SO 提取产物、SO 导入副本、补丁导出）。
     * 不清理引擎文件（由清除引擎按钮负责）。
     */
    fun cleanProjectCache() {
        viewModelScope.launch {
            val freed = withContext(Dispatchers.IO) {
                var total = 0L
                val cache = application.cacheDir
                // 删除 apk_import_* / so_import_* / extracted_* / patches/ 目录
                cache.listFiles()?.forEach { f ->
                    val name = f.name
                    if (name.startsWith("apk_import_") ||
                        name.startsWith("so_import_") ||
                        name.startsWith("extracted_") ||
                        name == "patches" ||
                        name == "blutter_tmp"
                    ) {
                        total += f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                        f.deleteRecursively()
                    }
                }
                total
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
            primaryUrl = sourceConfig.primaryUrl,
            fallbackUrl = sourceConfig.fallbackUrl,
            checksumUrl = sourceConfig.checksumUrl,
            versionUrl = sourceConfig.versionUrl,
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
     * 保存下载源配置。
     */
    fun saveSourceConfig(
        primaryUrl: String,
        fallbackUrl: String,
        checksumUrl: String,
        versionUrl: String
    ) {
        sourceConfig.primaryUrl = primaryUrl.trim()
        sourceConfig.fallbackUrl = fallbackUrl.trim()
        sourceConfig.checksumUrl = checksumUrl.trim()
        sourceConfig.versionUrl = versionUrl.trim()

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
    val primaryUrl: String = "",
    val fallbackUrl: String = "",
    val checksumUrl: String = "",
    val versionUrl: String = "",
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
    val isRunning: Boolean = false,
    val activeSessions: Int = 0,
    val localUrl: String = "",
    val lanUrl: String = "",
    val errorMessage: String? = null,
)

/** MCP 配置快照（combine 中间值）。 */
private data class McpConfigSnapshot(
    val enabled: Boolean,
    val bindMode: com.ai.fler.core.mcp.McpConfig.BindMode,
    val port: Int,
    val token: String,
    val patchEnabled: Boolean,
)
