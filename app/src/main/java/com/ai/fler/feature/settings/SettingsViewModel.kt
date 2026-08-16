package com.ai.fler.feature.settings

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.PowerManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.frida.FridaEngine
import com.ai.fler.core.mcp.McpConfig
import com.ai.fler.core.mcp.McpToolHandlers
import com.ai.fler.core.service.EnginePackManager
import com.ai.fler.core.service.EngineSourceConfig
import com.ai.fler.core.service.EngineUpdate
import com.ai.fler.core.service.OverlayKeepAliveService
import com.ai.fler.core.service.WorkDirectory
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
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
    private val fridaEngine: FridaEngine,
    private val workDirectory: WorkDirectory,
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

    /** Frida 动态插桩状态（客户端可用性/root/server/初始化）。 */
    private val _fridaStatus = MutableStateFlow(FridaStatusUiState())
    val fridaStatus: StateFlow<FridaStatusUiState> = _fridaStatus.asStateFlow()

    /** 工作目录 SAF tree URI（未设置 = 空串）。 */
    private val _workDirTreeUri = MutableStateFlow(workDirectory.treeUri.value)
    val workDirTreeUri: StateFlow<String> = _workDirTreeUri.asStateFlow()

    /** 后台保活状态（电池优化豁免）。 */
    private val _keepAliveState = MutableStateFlow(KeepAliveUiState())
    val keepAliveState: StateFlow<KeepAliveUiState> = _keepAliveState.asStateFlow()

    /** 工作目录显示名（目录名或 URI 尾部；未设置 = null），UI 副标题用。 */
    val workDirDisplayName: String? get() = workDirectory.displayName()

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
        // 工作目录状态独立收集（App 级，不属于 MCP 配置）。
        // 必须单独 launch：combineMcpState 的 collect 是无限收集，
        // 若把这段放在 combineMcpState 内部，将永远执行不到（死代码），
        // 导致设置工作目录后 UI 仍显示「未设置」。
        viewModelScope.launch {
            workDirectory.treeUri.collect { _workDirTreeUri.value = it }
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
            McpConfigSnapshot(enabled, bindMode, port, token, patchEnabled, false)
        }.combine(mcpConfig.emuToolsEnabled) { cfg, emu ->
            cfg.copy(emuToolsEnabled = emu)
        }.combine(mcpServerManager.status) { cfg, status ->
            McpUiState(
                enabled = cfg.enabled,
                bindMode = cfg.bindMode,
                port = cfg.port,
                token = cfg.token,
                patchEnabled = cfg.patchEnabled,
                emuToolsEnabled = cfg.emuToolsEnabled,
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
        // 一律前台化：本机（127.0.0.1）与局域网模式都挂前台服务保活，
        // 与 MainActivity 启动逻辑一致（避免本机模式退后台即断）。
        McpServerService.start(application)
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

    fun mcpSetEmuToolsEnabled(value: Boolean) {
        mcpConfig.setEmuToolsEnabled(value)
    }

    // ========== 工作目录（App 级设置） ==========

    /** 设置工作目录（SAF tree URI，调用方需先 takePersistableUriPermission）。 */
    fun setWorkDirTreeUri(value: String) {
        workDirectory.setTreeUri(value)
    }

    /** 清除工作目录，回退到默认 App 缓存。 */
    fun clearWorkDir() {
        workDirectory.clear()
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

    // ========== Frida 状态 ==========

    /** 探测 Frida 状态（ensureReady=true 时会先部署/拉起 frida-server）。 */
    fun refreshFridaStatus(ensureReady: Boolean) {
        viewModelScope.launch {
            _fridaStatus.value = _fridaStatus.value.copy(loading = true, errorMessage = null)
            try {
                val s = withTimeout(8_000) {
                    withContext(Dispatchers.IO) {
                        if (ensureReady) fridaEngine.ensureReady() else fridaEngine.status()
                    }
                }
                _fridaStatus.value = FridaStatusUiState(
                    available = s.available,
                    version = s.version,
                    root = s.root,
                    serverRunning = s.serverRunning,
                    initialized = s.initialized,
                )
            } catch (e: TimeoutCancellationException) {
                if (ensureReady) retriggerRootAuthForProbe()
                _fridaStatus.value = FridaStatusUiState(
                    errorMessage = "探测超时：Magisk 授权未完成。请在弹出的 root 授权窗口点击「允许」后重试",
                )
            } catch (e: Exception) {
                _fridaStatus.value = FridaStatusUiState(
                    errorMessage = e.message ?: "Frida 探测失败",
                )
            }
        }
    }

    /** 探测超时兜底：再触发一次 Magisk 授权弹窗。 */
    private fun retriggerRootAuthForProbe() {
        try {
            Thread {
                try {
                    Thread.sleep(800)
                    val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
                    if (!p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
                } catch (_: Exception) {
                }
            }.start()
        } catch (_: Exception) {
        }
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

    // ========== 后台保活（电池优化豁免 + 悬浮窗保活） ==========

    /** 悬浮窗保活开关持久化（与 OverlayKeepAliveService 共享键名）。 */
    private val keepAlivePrefs =
        application.getSharedPreferences(OverlayKeepAliveService.PREFS_NAME, Context.MODE_PRIVATE)

    /** 悬浮窗保活开关是否已开启（用户意图，实际运行状态以服务为准）。 */
    private val overlayEnabled: Boolean
        get() = keepAlivePrefs.getBoolean(OverlayKeepAliveService.KEY_OVERLAY_ENABLED, false)

    /**
     * 刷新后台保活状态（Android 9+ 电池优化豁免 + 悬浮窗权限/开关）。
     * 每次回到设置页前台时调用，保证状态实时更新。
     */
    fun refreshKeepAliveStatus() {
        val pm = application.getSystemService(Context.POWER_SERVICE) as PowerManager
        val exempt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(application.packageName)
        } else {
            true
        }
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            android.provider.Settings.canDrawOverlays(application)
        // 自动恢复：用户已开启悬浮窗保活但服务不在运行（进程被杀/重启后）时重新拉起
        if (overlayEnabled && canOverlay && !OverlayKeepAliveService.isRunning()) {
            OverlayKeepAliveService.start(application)
        }
        _keepAliveState.value = KeepAliveUiState(
            isIgnoringBatteryOptimizations = exempt,
            canDrawOverlay = canOverlay,
            overlayRunning = OverlayKeepAliveService.isRunning() || overlayEnabled,
        )
    }

    /**
     * 开启/关闭悬浮窗保活。
     * 开启前需已获得悬浮窗权限（UI 负责引导授权）。
     */
    fun setOverlayKeepAlive(enabled: Boolean) {
        keepAlivePrefs.edit().putBoolean(OverlayKeepAliveService.KEY_OVERLAY_ENABLED, enabled).apply()
        if (enabled) {
            OverlayKeepAliveService.start(application)
        } else {
            OverlayKeepAliveService.stop(application)
        }
        refreshKeepAliveStatus()
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
    val emuToolsEnabled: Boolean = false,
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
    val emuToolsEnabled: Boolean,
)

/** Frida 动态插桩状态（设置页状态卡片用）。 */
data class FridaStatusUiState(
    val available: Boolean = false,
    val version: String = "",
    val root: Boolean = false,
    val serverRunning: Boolean = false,
    val initialized: Boolean = false,
    val loading: Boolean = false,
    val errorMessage: String? = null,
)

/** 后台保活状态（设置页后台保活卡片用）。 */
data class KeepAliveUiState(
    /** 是否已豁免电池优化（false = 需引导用户开启）。 */
    val isIgnoringBatteryOptimizations: Boolean = true,
    /** 是否已授予悬浮窗权限（SYSTEM_ALERT_WINDOW）。 */
    val canDrawOverlay: Boolean = false,
    /** 悬浮窗保活开关是否已开启。 */
    val overlayRunning: Boolean = false,
)
