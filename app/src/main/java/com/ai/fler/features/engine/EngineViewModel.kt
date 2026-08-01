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
        val isDownloading: Boolean = false,
        val errorMessage: String? = null,
        val isCustomSource: Boolean = false,
    )

    private val _uiState = MutableStateFlow(EngineUiState())
    val uiState: StateFlow<EngineUiState> = _uiState.asStateFlow()

    init {
        checkEngineStatus()
        // 引擎版本变化（下载完成/清除）时实时刷新就绪状态
        viewModelScope.launch {
            enginePackManager.versionsEpoch.collect {
                _uiState.value = _uiState.value.copy(
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
    }

    /**
     * 启动引擎包下载。
     *
     * @param force 为 true 时强制重新下载（「下载更新」用），即使已就绪也重下。
     */
    fun startDownload(force: Boolean = false) {
        if (_uiState.value.isDownloading) return

        _uiState.value = _uiState.value.copy(
            isDownloading = true,
            errorMessage = null,
        )

        viewModelScope.launch {
            enginePackManager.ensureEnginesReady(force).collectLatest { progress ->
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
}
