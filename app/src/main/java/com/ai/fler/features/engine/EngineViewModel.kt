package com.ai.fler.features.engine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.service.EnginePackManager
import com.ai.fler.core.service.EngineSourceConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 引擎下载/管理 ViewModel。
 *
 * 负责：
 * 1. 查询引擎包就绪状态
 * 2. 启动/取消引擎包下载
 * 3. 监听下载进度并更新 UI
 */
@HiltViewModel
class EngineViewModel @Inject constructor(
    private val enginePackManager: EnginePackManager,
    private val sourceConfig: EngineSourceConfig,
) : ViewModel() {

    data class EngineUiState(
        val isReady: Boolean = false,
        val progress: EnginePackManager.EngineProgress? = null,
        val installedVersions: List<String> = emptyList(),
        val isDownloading: Boolean = false,
        val errorMessage: String? = null,
        val isCustomSource: Boolean = false,
    )

    private val _uiState = MutableStateFlow(EngineUiState())
    val uiState: StateFlow<EngineUiState> = _uiState.asStateFlow()

    init {
        checkEngineStatus()
        // 引擎版本变化（下载完成/清除）时实时刷新列表
        viewModelScope.launch {
            enginePackManager.versionsEpoch.collect {
                _uiState.value = _uiState.value.copy(
                    installedVersions = enginePackManager.listInstalledVersions(),
                    isReady = enginePackManager.isEnginePackReady(),
                )
            }
        }
    }

    /**
     * 检查引擎包就绪状态。
     */
    fun checkEngineStatus() {
        val isReady = enginePackManager.isEnginePackReady()
        _uiState.value = _uiState.value.copy(
            isReady = isReady,
            isCustomSource = sourceConfig.isCustom(),
        )

        if (isReady) {
            viewModelScope.launch {
                val versions = enginePackManager.listInstalledVersions()
                _uiState.value = _uiState.value.copy(installedVersions = versions)
            }
        }
    }

    /**
     * 启动引擎包下载。
     * 对于大部分场景，建议使用 EngineDownloadService（前台服务）。
     * 此处提供直接在 ViewModel 中观察进度的能力，
     * 方便前台服务通知与应用内 UI 同步。
     */
    fun startDownload() {
        if (_uiState.value.isDownloading) return

        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            errorMessage = null,
        )

        viewModelScope.launch {
            enginePackManager.ensureEnginesReady().collectLatest { progress ->
                _uiState.value = _uiState.value.copy(
                    progress = progress,
                    isDownloading = progress.phase == EnginePackManager.EngineProgress.Phase.DOWNLOADING ||
                            progress.phase == EnginePackManager.EngineProgress.Phase.EXTRACTING ||
                            progress.phase == EnginePackManager.EngineProgress.Phase.VERIFYING ||
                            progress.phase == EnginePackManager.EngineProgress.Phase.LOADING,
                )

                when (progress.phase) {
                    EnginePackManager.EngineProgress.Phase.COMPLETED -> {
                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            isReady = true,
                        )
                        // 刷新已安装版本列表
                        val versions = enginePackManager.listInstalledVersions()
                        _uiState.value = _uiState.value.copy(installedVersions = versions)
                    }
                    EnginePackManager.EngineProgress.Phase.FAILED -> {
                        _uiState.value = _uiState.value.copy(
                            isDownloading = false,
                            errorMessage = progress.errorMessage,
                        )
                    }
                    else -> { /* 继续 */ }
                }
            }
        }
    }

    /**
     * 启动前台服务下载（推荐方式，Android 14+ 合规）。
     */
    fun startDownloadService(context: android.content.Context) {
        com.ai.fler.core.service.EngineDownloadService.start(context)
    }

    /**
     * 清理引擎包。
     */
    fun clearEngines() {
        viewModelScope.launch {
            enginePackManager.clearEngines()
            _uiState.value = EngineUiState(isReady = false)
        }
    }
}
