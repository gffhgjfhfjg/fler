package com.ai.fler.feature.project

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.analysis.AnalysisReportGenerator
import com.ai.fler.core.analysis.DartCallGraphBuilder
import com.ai.fler.core.service.WorkDirectory
import com.ai.fler.data.AppDatabase
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.data.entity.Analysis
import com.ai.fler.data.entity.Project
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * 项目详情 ViewModel。
 *
 * 展示项目信息、分析记录列表，以及从 APK 提取的 so 文件。
 */
@HiltViewModel
class ProjectDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val projectDao: ProjectDao,
    private val analysisDao: AnalysisDao,
    private val appDatabase: AppDatabase,
    private val callGraphBuilder: DartCallGraphBuilder,
    private val analysisRunner: AnalysisRunner,
    private val reportGenerator: AnalysisReportGenerator,
    private val workDirectory: WorkDirectory,
) : ViewModel() {

    val projectId: Long = savedStateHandle["projectId"] ?: 0L

    private val _project = MutableStateFlow<Project?>(null)
    val project: StateFlow<Project?> = _project.asStateFlow()

    private val _analyses = MutableStateFlow<List<Analysis>>(emptyList())
    val analyses: StateFlow<List<Analysis>> = _analyses.asStateFlow()

    private val _soFiles = MutableStateFlow<List<File>>(emptyList())
    val soFiles: StateFlow<List<File>> = _soFiles.asStateFlow()

    /** 错误消息（一次性事件，UI 层 snackbar 消费后可清空）。 */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** 报告生成状态：null=空闲，true=生成中，Pair<文件名,字符数>=完成。 */
    private val _reportState = MutableStateFlow<Any?>(null)
    val reportState: StateFlow<Any?> = _reportState.asStateFlow()

    /** 消费报告状态（UI 展示后调用）。 */
    fun consumeReportState() {
        _reportState.value = null
    }

    /**
     * 一键生成 Markdown 逆向报告（最新一条成功分析）。
     * 生成后写入工作目录（未设置则 App 缓存 so_export）。
     */
    fun generateReport() {
        viewModelScope.launch {
            _reportState.value = true
            try {
                val latest = _analyses.value.firstOrNull { it.resultCode == Analysis.RESULT_SUCCESS }
                if (latest == null) {
                    _reportState.value = null
                    _errorMessage.value = "暂无可报告的分析（需先完成一次成功分析）"
                    return@launch
                }
                val report = reportGenerator.generate(latest.id)
                if (report == null) {
                    _reportState.value = null
                    _errorMessage.value = "报告生成失败：分析 ${latest.id} 不存在"
                    return@launch
                }
                val saved = saveReportToWorkDir(latest.id, report)
                _reportState.value = saved to report.length
                Log.i(TAG, "报告已生成: analysis=${latest.id}, ${report.length} chars, saved=$saved")
            } catch (e: Exception) {
                Log.e(TAG, "报告生成失败", e)
                _reportState.value = null
                _errorMessage.value = "报告生成失败: ${e.message}"
            }
        }
    }

    /** 报告落盘工作目录（SAF，未设置回退 cacheDir/so_export）。返回落盘文件名或 null。 */
    private suspend fun saveReportToWorkDir(analysisId: Long, content: String): String? =
        withContext(Dispatchers.IO) {
            val fileName = "fler_report_$analysisId.md"
            runCatching {
                val doc = workDirectory.asDocumentFile()
                if (doc != null && doc.canWrite()) {
                    doc.findFile(fileName)?.delete()
                    val child = doc.createFile("text/markdown", fileName) ?: return@runCatching null
                    context.contentResolver.openOutputStream(child.uri)?.use { os ->
                        os.write(content.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }
                    fileName
                } else {
                    val dir = File(context.cacheDir, "so_export").apply { mkdirs() }
                    File(dir, fileName).writeText(content, Charsets.UTF_8)
                    fileName
                }
            }.getOrNull()
        }

    init {
        load()
    }

    /**
     * 加载项目 / 分析记录 / so 文件。
     *
     * 注意：每个 Flow 用独立协程收集 —— 若串行 `collect`，前一个 Flow 永不结束
     * 会阻塞后续代码（此前导致分析记录与 so 文件永远为空）。
     */
    fun load() {
        viewModelScope.launch {
            _project.value = projectDao.getById(projectId)
            _soFiles.value = loadSoFiles()
        }
        viewModelScope.launch {
            projectDao.getByIdFlow(projectId).collect { _project.value = it }
        }
        viewModelScope.launch {
            analysisDao.getByProjectId(projectId).collect { analyses ->
                _analyses.value = analyses
                // 分析记录变化时刷新 so 文件（分析完成后提取的 so 才会出现）
                _soFiles.value = loadSoFiles()
            }
        }
    }

    private suspend fun loadSoFiles(): List<File> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "extracted_$projectId")
        if (dir.isDirectory) {
            dir.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name }
                ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * 删除单条分析记录。
     *
     * 调用 [AppDatabase.cascadeDeleteAnalysis] 级联清理子表数据，
     * 删除完成后通过 Flow 自动刷新列表。
     *
     * @param analysis 要删除的分析记录
     */
    fun deleteAnalysis(analysis: Analysis) {
        viewModelScope.launch {
            try {
                Log.i(TAG, "删除分析记录: id=${analysis.id}, projectId=$projectId")
                // 若该分析正在后台执行，先取消，避免删库后导入写入已删除记录
                analysisRunner.cancelAnalysisIfActive(analysis.id)
                appDatabase.cascadeDeleteAnalysis(analysis.id)
                callGraphBuilder.invalidate(analysis.id)
                Log.i(TAG, "分析记录 ${analysis.id} 已删除")
            } catch (e: Exception) {
                Log.e(TAG, "删除分析记录失败: id=${analysis.id}", e)
                _errorMessage.value = "删除分析记录失败: ${e.message}"
            }
        }
    }

    /** 清空错误消息（UI 消费后调用）。 */
    fun consumeErrorMessage() {
        _errorMessage.value = null
    }

    companion object {
        private const val TAG = "ProjectDetailViewModel"
    }
}
