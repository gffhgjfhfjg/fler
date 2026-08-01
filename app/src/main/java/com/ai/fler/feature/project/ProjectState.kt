package com.ai.fler.feature.project

import com.ai.fler.data.entity.Project

/**
 * 项目列表 UI 状态。
 */
data class ProjectListState(
    val projects: List<Project> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isRefreshing: Boolean = false
)

/**
 * 分析进度状态。
 */
data class AnalysisProgress(
    val projectId: Long = 0,
    val stage: AnalysisStage = AnalysisStage.Idle,
    val progress: Float = 0f,
    val message: String = "",
    val error: String? = null
)

/**
 * 分析阶段枚举。
 */
enum class AnalysisStage {
    Idle,
    Extracting,
    DetectingVersion,
    LoadingEngine,
    Analyzing,
    SavingResults,
    Completed,
    Failed
}
