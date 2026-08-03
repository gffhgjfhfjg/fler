package com.ai.fler.feature.project

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.jni.BlutterEngine
import com.ai.fler.core.service.AddressTranslator
import com.ai.fler.core.service.AnalysisImporter
import com.ai.fler.core.service.ApkExtractor
import com.ai.fler.core.service.DartVersionDetector
import com.ai.fler.core.service.EngineLoader
import com.ai.fler.core.service.EngineNotReadyException
import com.ai.fler.core.service.EnginePackManager
import com.ai.fler.core.service.EngineSourceConfig
import com.ai.fler.data.AppDatabase
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.data.entity.Analysis
import com.ai.fler.data.entity.DartClass
import com.ai.fler.data.entity.DartMethod
import com.ai.fler.data.entity.Library
import com.ai.fler.data.entity.PpEntry
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
 * 项目 ViewModel。
 *
 * 负责项目的 CRUD 操作和分析流程的协调。
 *
 * 分析流程：
 * 1. 创建/选择项目
 * 2. 从 APK 提取 libapp.so 和 libflutter.so
 * 3. 检测 Dart 版本
 * 4. 加载 Blutter 引擎
 * 5. 执行分析
 * 6. 将结果写入数据库
 */
@HiltViewModel
class ProjectViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appDatabase: AppDatabase,
    private val projectDao: ProjectDao,
    private val analysisDao: AnalysisDao,
    private val dartClassDao: DartClassDao,
    private val dartMethodDao: DartMethodDao,
    private val ppEntryDao: PpEntryDao,
    private val libraryDao: LibraryDao,
    private val apkExtractor: ApkExtractor,
    private val dartVersionDetector: DartVersionDetector,
    private val enginePackManager: EnginePackManager,
    private val engineLoader: EngineLoader,
    private val analysisImporter: AnalysisImporter,
    private val addressTranslator: AddressTranslator
) : ViewModel() {

    // ========== 项目列表状态 ==========
    private val _projectListState = MutableStateFlow(ProjectListState())
    val projectListState: StateFlow<ProjectListState> = _projectListState.asStateFlow()

    // ========== 分析进度 ==========

    private val _analysisProgress = MutableStateFlow(AnalysisProgress())
    val analysisProgress: StateFlow<AnalysisProgress> = _analysisProgress.asStateFlow()

    /**
     * 关闭分析进度对话框。
     *
     * 仅在分析已完成或失败时调用，把 stage 重置为 Idle 让对话框消失。
     */
    fun dismissAnalysisDialog() {
        val current = _analysisProgress.value
        if (current.stage == AnalysisStage.Completed || current.stage == AnalysisStage.Failed) {
            _analysisProgress.value = AnalysisProgress()
        }
    }

    // ========== 初始化 ==========
    init {
        loadProjects()
    }

    /**
     * 加载所有项目。
     */
    private fun loadProjects() {
        viewModelScope.launch {
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
     * 刷新项目列表。
     */
    fun refreshProjects() {
        viewModelScope.launch {
            _projectListState.value = _projectListState.value.copy(isRefreshing = true)
            // 重新加载由 Flow 自动处理
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
                val deletedAnalysisCount = appDatabase.cascadeDeleteProject(project.id)
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

    // ========== 分析流程 ==========

    /**
     * 执行完整分析流程。
     *
     * 每个阶段独立 try-catch，失败时通过 [failAnalysis] 切换对话框状态到 Failed，
     * 并把异常详情打到 logcat（TAG = "ProjectViewModel"），便于定位卡在哪一步。
     *
     * @param projectId 项目 ID
     */
    fun startAnalysis(projectId: Long) {
        viewModelScope.launch {
            val project = projectDao.getById(projectId)
            if (project == null) {
                Log.e(TAG, "startAnalysis: project $projectId not found")
                return@launch
            }

            // 创建分析记录（即使后续失败也要有数据库行）
            val analysisId = try {
                createAnalysisRecord(projectId)
            } catch (e: Exception) {
                Log.e(TAG, "createAnalysisRecord failed", e)
                return@launch
            }

            // ====================================================================
            // 阶段 1: 提取 so 文件
            // ====================================================================
            val extractResult: ApkExtractor.ExtractResult
            try {
                updateProgress(projectId, AnalysisStage.Extracting, 0.1f, "Extracting .so files...")
                Log.i(TAG, "阶段 1/5: 提取 so 文件, apkPath=${project.apkPath}")

                extractResult = extractSoFiles(project)
                if (!extractResult.isSuccess) {
                    failAnalysis(analysisId, extractResult.error ?: "Extraction failed")
                    return@launch
                }
                Log.i(TAG, "阶段 1/5 完成: libapp=${extractResult.libappPath}, libflutter=${extractResult.libflutterPath}")
            } catch (e: Exception) {
                Log.e(TAG, "阶段 1/5 提取 so 失败", e)
                failAnalysis(analysisId, "提取 so 文件失败: ${e.message}")
                return@launch
            }

            // ====================================================================
            // 阶段 2: 检测 Dart 版本
            // ====================================================================
            val dartVersion: String
            try {
                updateProgress(projectId, AnalysisStage.DetectingVersion, 0.3f, "Detecting Dart version...")
                val libflutterPath = extractResult.libflutterPath
                val detected = if (libflutterPath.isNullOrEmpty()) {
                    Log.w(TAG, "libflutterPath 为空，跳过版本检测")
                    null
                } else {
                    dartVersionDetector.detect(libflutterPath)
                }

                if (detected != null) {
                    dartVersion = detected
                    Log.i(TAG, "阶段 2/5 完成: 检测到 Dart 版本 = $dartVersion")
                } else {
                    // 检测失败：不再静默 fallback（避免用错版本引擎导致快照解析越界），明确报错并列出已安装引擎
                    val installed = enginePackManager.listInstalledVersions()
                    Log.e(TAG, "阶段 2/5: 未检测到 Dart 版本。已安装引擎: $installed")
                    failAnalysis(
                        analysisId,
                        "未检测到 Dart 版本。请确认 APK 是 Flutter 应用。" +
                            (if (installed.isNotEmpty()) " 已安装引擎版本: ${installed.joinToString()}。" else " 本地未安装任何引擎，请先在设置页下载。")
                    )
                    return@launch
                }

                // 更新项目的 Dart 版本
                projectDao.update(
                    project.copy(
                        dartVersion = dartVersion,
                        status = Project.STATUS_ANALYZING
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "阶段 2/5 Dart 版本检测失败", e)
                failAnalysis(analysisId, "Dart 版本检测失败: ${e.message}")
                return@launch
            }

            // ====================================================================
            // 阶段 3: 加载引擎
            // 必须 System.load dartvm_<dartVersion>.so，否则 dlsym 找不到 blutter_analyze 符号
            // ====================================================================
            try {
                updateProgress(projectId, AnalysisStage.LoadingEngine, 0.5f, "Loading engine $dartVersion...")
                Log.i(TAG, "阶段 3/5: 加载引擎 dartvm_${dartVersion}.so")
                engineLoader.loadEngine(dartVersion)
                Log.i(TAG, "阶段 3/5 完成: 引擎已加载")
            } catch (e: EngineNotReadyException) {
                Log.e(TAG, "阶段 3/5 引擎未就绪: ${e.message}")
                failAnalysis(analysisId, "引擎未就绪: ${e.message}。请在设置页下载包含 $dartVersion 的引擎包。")
                return@launch
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "阶段 3/5 引擎加载失败 (UnsatisfiedLinkError)", e)
                failAnalysis(analysisId, "引擎加载失败 (UnsatisfiedLinkError): ${e.message}")
                return@launch
            } catch (e: Exception) {
                Log.e(TAG, "阶段 3/5 引擎加载失败", e)
                failAnalysis(analysisId, "引擎加载失败: ${e.message}")
                return@launch
            }

            // ====================================================================
            // 阶段 4: 执行分析
            // ====================================================================
            val analyzeOutcome: RunOutcome
            try {
                updateProgress(projectId, AnalysisStage.Analyzing, 0.6f, "Running analysis...")
                val libappPath = extractResult.libappPath
                if (libappPath.isNullOrEmpty()) {
                    failAnalysis(analysisId, "libapp.so 路径为空，无法执行分析")
                    return@launch
                }
                Log.i(TAG, "阶段 4/5: 调用 BlutterEngine.analyze, libapp=$libappPath, dartVersion=$dartVersion")
                analyzeOutcome = runAnalysis(
                    libappPath = libappPath,
                    dartVersion = dartVersion,
                    analysisId = analysisId
                )
                if (!analyzeOutcome.result.success) {
                    Log.e(TAG, "阶段 4/5 分析失败: ${analyzeOutcome.result.errorMessage}")
                    failAnalysis(analysisId, analyzeOutcome.result.errorMessage ?: "分析失败")
                    return@launch
                }
                Log.i(TAG, "阶段 4/5 完成")
            } catch (e: Exception) {
                Log.e(TAG, "阶段 4/5 BlutterEngine.analyze 异常", e)
                failAnalysis(analysisId, "分析执行异常: ${e.message}")
                return@launch
            }

            // ====================================================================
            // 阶段 5: 保存结果
            // ====================================================================
            try {
                updateProgress(projectId, AnalysisStage.SavingResults, 0.8f, "Saving results...")
                saveAnalysisResults(analysisId, analyzeOutcome)
                completeAnalysis(analysisId, extractResult, analyzeOutcome.result)

                // 构建地址映射（产物 ↔ SO 联动）
                try {
                    val libappPath = extractResult.libapp?.path
                    if (libappPath != null) {
                        val methods = dartMethodDao.getByAnalysisIdList(analysisId)
                        addressTranslator.importMethods(projectId, libappPath, methods)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "构建地址映射失败（不影响分析结果）: ${e.message}", e)
                }

                Log.i(TAG, "阶段 5/5 完成")
            } catch (e: Exception) {
                Log.e(TAG, "阶段 5/5 保存结果失败", e)
                failAnalysis(analysisId, "保存分析结果失败: ${e.message}")
                return@launch
            }

            updateProgress(projectId, AnalysisStage.Completed, 1.0f, "Analysis completed!")
            // 必须用 dartVersion（阶段 2 检测到的）保留，否则会覆盖回 null（用最初的 project 会导致 Dart N/A）
            projectDao.update(
                project.copy(
                    dartVersion = dartVersion,
                    engineVersion = dartVersion,
                    status = Project.STATUS_COMPLETED
                )
            )
        }
    }

    /**
     * 创建分析记录。
     */
    private suspend fun createAnalysisRecord(projectId: Long): Long {
        val analysis = Analysis(
            projectId = projectId,
            resultCode = Analysis.RESULT_PENDING
        )
        return analysisDao.insert(analysis)
    }

    /**
     * 提取 so 文件。
     */
    private suspend fun extractSoFiles(project: Project): ApkExtractor.ExtractResult {
        val extractDir = File(context.cacheDir, "extracted_${project.id}")
        return apkExtractor.extract(project.apkPath, extractDir)
    }

    /**
     * 运行 Blutter 分析。
     *
     * @param libappPath libapp.so 路径
     * @param dartVersion 检测到的 Dart 版本
     * @param analysisId 分析记录 ID（用于生成数据库路径）
     */
    private suspend fun runAnalysis(
        libappPath: String,
        dartVersion: String,
        analysisId: Long
    ): RunOutcome {
        return try {
            // loadEngine 会触发 System.load dartvm_<dartVersion>.so（如已在阶段 3 加载则跳过）
            // 并返回持有 so 路径的 BlutterEngine，供 JNI 用 dlopen(RTLD_NOLOAD) 查找符号
            val engine = engineLoader.loadEngine(dartVersion)

            // 生成数据库文件路径
            val dbPath = File(context.cacheDir, "analysis_${analysisId}.db").absolutePath
            val cacheDir = context.cacheDir.absolutePath

            // 调用 analyze 方法（在 IO 线程执行，避免阻塞主线程）
            val result = withContext(Dispatchers.IO) { engine.analyze(libappPath, dbPath, cacheDir) }

            // 转换结果
            val success = result.isSuccess
            RunOutcome(
                AnalyzeResult(
                    success = success,
                    // 注意：实际统计数据在导入阶段从分析结果数据库读取
                    classesCount = 0,
                    methodsCount = 0,
                    ppEntriesCount = 0,
                    errorMessage = if (success) null
                    else "Blutter 引擎错误码 code=${result.rawCode}（查看 logcat TAG=FlerBlutterJNI 输入诊断信息）"
                ),
                dbPath = dbPath
            )
        } catch (e: Exception) {
            RunOutcome(
                AnalyzeResult(
                    success = false,
                    errorMessage = e.message ?: "Analysis exception"
                ),
                dbPath = ""
            )
        }
    }

    /**
     * 保存分析结果到数据库。
     *
     * 先通过 [AnalysisImporter] 把 Blutter 生成的 SQLite 中的
     * classes/methods/pp_entries/strings 读入 Room，再回写真实统计计数。
     */
    private suspend fun saveAnalysisResults(
        analysisId: Long,
        outcome: RunOutcome
    ) {
        val importResult = if (outcome.result.success && outcome.dbPath.isNotBlank()) {
            analysisImporter.import(analysisId, outcome.dbPath)
        } else {
            AnalysisImporter.ImportResult()
        }

        // updateCounts 已在 AnalysisImporter 内部回写；这里兜底再同步一次
        analysisDao.updateCounts(
            id = analysisId,
            classesCount = importResult.classesCount,
            methodsCount = importResult.methodsCount,
            ppEntriesCount = importResult.ppEntriesCount
        )
    }

    /**
     * 完成分析。
     */
    private suspend fun completeAnalysis(
        analysisId: Long,
        extractResult: ApkExtractor.ExtractResult,
        result: AnalyzeResult
    ) {
        analysisDao.completeAnalysis(
            id = analysisId,
            resultCode = if (result.success) Analysis.RESULT_SUCCESS else Analysis.RESULT_GENERIC_ERROR
        )

        // 保存 SO 路径到分析记录（供 SO 编辑器查询 Dart 方法标签）
        analysisDao.updateLibPaths(
            id = analysisId,
            libappPath = extractResult.libapp?.path,
            libflutterPath = extractResult.libflutter?.path
        )

        // 保存库信息
        extractResult.libapp?.let { lib ->
            libraryDao.insert(lib.copy(analysisId = analysisId))
        }
        extractResult.libflutter?.let { lib ->
            libraryDao.insert(lib.copy(analysisId = analysisId))
        }
    }

    /**
     * 标记分析失败。
     *
     * 同步更新进度对话框状态（stage=Failed + error），避免 UI 永远卡在 Extracting。
     */
    private suspend fun failAnalysis(analysisId: Long, error: String) {
        analysisDao.updateResult(
            id = analysisId,
            resultCode = Analysis.RESULT_GENERIC_ERROR,
            errorMessage = error
        )
        // 关键：必须更新 _analysisProgress，否则对话框永远停在当前阶段
        _analysisProgress.value = AnalysisProgress(
            projectId = _analysisProgress.value.projectId,
            stage = AnalysisStage.Failed,
            progress = _analysisProgress.value.progress,
            message = "分析失败",
            error = error
        )
    }

    /**
     * 更新进度。
     */
    private fun updateProgress(
        projectId: Long,
        stage: AnalysisStage,
        progress: Float,
        message: String
    ) {
        _analysisProgress.value = AnalysisProgress(
            projectId = projectId,
            stage = stage,
            progress = progress,
            message = message
        )
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
