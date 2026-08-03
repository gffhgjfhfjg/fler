package com.ai.fler.core.analysis

import javax.inject.Inject
import javax.inject.Singleton

/**
 * SO 编辑器跨 ViewModel 实例的元数据缓存。
 *
 * 为什么需要：SoEditorScreen 是 NavHost 顶层 destination，用户返回项目列表时
 * NavBackStackEntry 销毁 → SoEditorViewModel.onCleared() → 本地 soCache / injectedSoPaths
 * 全部丢失。下次再打开同一 SO 时会重新跑 Rizin 查询和 defineFunctions，体感「依旧很慢」。
 *
 * 解决方案：把与 SO 文件绑定的「分析结果 / 注入状态 / Dart 标签」放到 @Singleton 里，
 * 生命周期与 [AnalysisSession] 一致（App 进程内常驻）。
 *
 * 缓存内容：
 * 1. [SoMetadata] —— sections / symbols / functions / fileInfo，避免重复 Rizin 查询
 * 2. 已注入 Rizin 的 SO 路径 —— 避免重复 [AnalysisSession.defineFunctions]（很慢）
 * 3. [DartLabels] —— Blutter 分析的 Dart 方法标签，避免重复 DAO 查询和标签构建
 *
 * 清理时机：仅由 [AnalysisSession.closeAll] 显式触发（App 退出时）。
 */
@Singleton
class SoEditorCache @Inject constructor() {

    /** 单个 SO 的元数据快照（仅含纯数据，不含 RzCore* 指针）。 */
    data class SoMetadata(
        val sections: List<SectionInfo>,
        val staticSymbols: List<SymbolInfo>,
        val dynamicSymbols: List<SymbolInfo>,
        val functions: List<FunctionInfo>,
        val fileInfo: FileInfo?,
        val fileSize: Long,
    )

    /** Blutter 分析产出的 Dart 方法标签。 */
    data class DartLabels(
        val labels: Map<Long, String>,                  // SO 偏移 → "ClassName.methodName"
        val dartFunctions: List<FunctionInfo>,           // 合并到 uiState.functions 的去重列表
    )

    private val soMetadataCache = mutableMapOf<String, SoMetadata>()
    private val injectedSoPaths = mutableSetOf<String>()
    private val dartLabelsCache = mutableMapOf<String, DartLabels>()

    // ------------------------------------------------------------------
    // SO 元数据
    // ------------------------------------------------------------------

    fun getMetadata(path: String): SoMetadata? = soMetadataCache[path]

    fun putMetadata(path: String, meta: SoMetadata) {
        soMetadataCache[path] = meta
    }

    // ------------------------------------------------------------------
    // Dart 方法标签
    // ------------------------------------------------------------------

    fun getDartLabels(path: String): DartLabels? = dartLabelsCache[path]

    fun putDartLabels(path: String, labels: DartLabels) {
        dartLabelsCache[path] = labels
    }

    // ------------------------------------------------------------------
    // Rizin 注入状态
    // ------------------------------------------------------------------

    fun isInjected(path: String): Boolean = path in injectedSoPaths

    fun markInjected(path: String) {
        injectedSoPaths.add(path)
    }

    // ------------------------------------------------------------------
    // 清理（仅 AnalysisSession.closeAll 时调用，App 退出级）
    // ------------------------------------------------------------------

    fun clearAll() {
        soMetadataCache.clear()
        injectedSoPaths.clear()
        dartLabelsCache.clear()
    }
}
