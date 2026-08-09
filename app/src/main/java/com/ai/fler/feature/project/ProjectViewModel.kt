package com.ai.fler.feature.project

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.analysis.DartCallGraphBuilder
import com.ai.fler.data.AppDatabase
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.data.entity.Project
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * 项目 ViewModel。
 *
 * 负责项目的 CRUD 操作；分析流程的协调已迁移到应用级 [AnalysisRunner]
 * （分析可在离开页面后继续后台执行，不再受屏幕 ViewModel 生命周期影响），
 * 本类持分析的只做进度转发与协程启动入口。
 */
@HiltViewModel
class ProjectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val projectDao: ProjectDao,
    private val analysisDao: AnalysisDao,
    private val callGraphBuilder: DartCallGraphBuilder,
    private val analysisRunner: AnalysisRunner
) : ViewModel() {

    // ========== 项目列表状态 ==========
    private val _projectListState = MutableStateFlow(ProjectListState())
    val projectListState: StateFlow<ProjectListState> = _projectListState.asStateFlow()

    // ========== 分析进度（转发到应用级 AnalysisRunner） ==========
    val analysisProgress: StateFlow<AnalysisProgress> = analysisRunner.analysisProgress

    /**
     * 关闭分析进度对话框。
     */
    fun dismissAnalysisDialog() = analysisRunner.dismissAnalysisDialog()

    /**
     * 将分析转入后台执行。
     */
    fun dismissToBackground() = analysisRunner.dismissToBackground()

    // ========== 初始化 ==========
    init {
        loadProjects()
    }

    private var projectsJob: Job? = null

    /**
     * 加载所有项目。
     */
    private fun loadProjects() {
        projectsJob?.cancel()
        projectsJob = viewModelScope.launch {
            _projectListState.value = _projectListState.value.copy(isLoading = true)
            try {
                projectDao.getAll().collect { projects ->
                    _projectListState.value = ProjectListState(
                        projects = projects,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _projectListState.value = _projectListState.value.copy(
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
    }

    /**
     * 刷新项目列表：重新订阅数据库 Flow，立即触发一次查询。
     */
    fun refreshProjects() {
        viewModelScope.launch {
            _projectListState.value = _projectListState.value.copy(isRefreshing = true)
            loadProjects()
            _projectListState.value = _projectListState.value.copy(isRefreshing = false)
        }
    }

    // ========== 新建项目 ==========

    /**
     * 创建新项目。
     */
    fun createProject(name: String, apkPath: String) {
        viewModelScope.launch {
            try {
                val project = Project(
                    name = name,
                    apkPath = apkPath,
                    status = Project.STATUS_CREATED
                )
                projectDao.insert(project)
            } catch (e: Exception) {
                _projectListState.value = _projectListState.value.copy(
                    errorMessage = e.message
                )
            }
        }
    }

    // ========== 删除项目 ==========

    /**
     * 删除项目。
     *
     * 调用 [AppDatabase.cascadeDeleteProject] 级联删除所有关联数据，
     * 同时清理从 APK 提取的 so 文件目录。
     */
    fun deleteProject(project: Project) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "删除项目: id=${project.id}, name=${project.name}")
                // 若该项目正在后台分析，先取消，避免删库后导入写入已删除记录
                analysisRunner.cancelProjectIfActive(project.id)
                val deletedAnalyses = analysisDao.getByProjectIdList(project.id)
                val deletedAnalysisCount = appDatabase.cascadeDeleteProject(project.id)
                deletedAnalyses.forEach { callGraphBuilder.invalidate(it.id) }
                Log.i(TAG, "项目 ${project.id} 已删除，级联清理 $deletedAnalysisCount 条分析记录")

                // 清理从 APK 提取的 so 文件目录
                // 目录约定：cacheDir/extracted_<projectId>/
                val extractDir = File(context.cacheDir, "extracted_${project.id}")
                if (extractDir.exists()) {
                    val deleted = extractDir.deleteRecursively()
                    Log.i(TAG, "清理提取目录 ${extractDir.absolutePath}: $deleted")
                }
            } catch (e: Exception) {
                Log.e(TAG, "删除项目失败: id=${project.id}", e)
                _projectListState.value = _projectListState.value.copy(
                    errorMessage = "Failed to delete project: ${e.message}"
                )
            }
        }
    }

    // ========== 分析流程入口 ==========

    /**
     * 启动完整分析流程（委托给应用级 [AnalysisRunner]，可后台执行）。
     *
     * @param projectId 项目 ID
     */
    fun startAnalysis(projectId: Long) {
        analysisRunner.startAnalysis(projectId)
    }

    companion object {
        private const val TAG = "ProjectViewModel"
    }
}

/**
 * 分析结果数据类。
 */
data class AnalyzeResult(
    val success: Boolean = false,
    val classesCount: Int = 0,
    val methodsCount: Int = 0,
    val ppEntriesCount: Int = 0,
    val errorMessage: String? = null
)

/**
 * 分析执行结果（含生成的 Blutter SQLite 路径，供导入阶段使用）。
 */
data class RunOutcome(
    val result: AnalyzeResult,
    val dbPath: String
)