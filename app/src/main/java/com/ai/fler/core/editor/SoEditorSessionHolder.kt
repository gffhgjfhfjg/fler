package com.ai.fler.core.editor

import com.ai.fler.core.analysis.FileInfo
import com.ai.fler.core.analysis.FunctionInfo
import com.ai.fler.core.analysis.SectionInfo
import com.ai.fler.core.analysis.SymbolInfo
import javax.inject.Inject
import javax.inject.Singleton

/**
 * @Singleton 持有当前 SO 编辑器的完整会话状态。
 *
 * 为什么需要：
 * 1. SoEditorViewModel 是 @HiltViewModel，绑定 NavBackStackEntry。
 *    切 Tab 时 entry 可能被销毁（取决于 Navigation 状态保存策略），
 *    ViewModel 重建后 uiState 全丢，需要重新走 openFile 全链路（aaa/aar 等）。
 * 2. 把已打开的 SO 文件状态上移到 @Singleton，即使 ViewModel 重建也能瞬间恢复。
  * 3. 顶层 Tab 与上下文模式（项目/PP/ASM 跳转进入的沉浸式 SoEditor）共用同一文件时，
 *    也可以直接切换，无需重新加载。
 *
 * 生命周期：App 进程内常驻（随 @Singleton 生命周期）。
 * 清理时机：ViewModel 显式调用 closeFile() 时由 ViewModel 调用 clear()。
 */
@Singleton
class SoEditorSessionHolder @Inject constructor() {

    private var session: SessionState? = null

    /** 当前打开的 SO 文件路径，若无会话则为 null。 */
    val currentFilePath: String? get() = session?.filePath

    data class SessionState(
        val filePath: String,
        val fileName: String,
        val fileSize: Long,
        val sections: List<SectionInfo>,
        val staticSymbols: List<SymbolInfo>,
        val dynamicSymbols: List<SymbolInfo>,
        val functions: List<FunctionInfo>,
        val fileInfo: FileInfo?,
        val currentTabOrdinal: Int,
        val selectedOffset: Long,
        val dartFunctionLabels: Map<Long, String>,
    )

    /** 保存会话状态。 */
    fun save(state: SessionState) {
        session = state
    }

    /**
     * 恢复指定路径的会话。
     * @return 匹配的会话状态，或 null（无会话/路径不匹配）
     */
    fun restore(filePath: String): SessionState? =
        session?.takeIf { it.filePath == filePath }

    /** 清除会话。 */
    fun clear() {
        session = null
    }
}