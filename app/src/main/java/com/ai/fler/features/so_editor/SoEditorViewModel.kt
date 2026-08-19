package com.ai.fler.features.so_editor

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.analysis.AnalysisSession
import com.ai.fler.core.analysis.AnalysisCapability
import com.ai.fler.core.analysis.DartCallGraphBuilder
import com.ai.fler.core.analysis.DisasmInstruction
import com.ai.fler.core.analysis.FileInfo
import com.ai.fler.core.analysis.FunctionInfo
import com.ai.fler.core.analysis.SectionInfo
import com.ai.fler.core.analysis.SoEditorCache
import com.ai.fler.core.analysis.StringInfo
import com.ai.fler.core.editor.SoEditorSessionHolder
import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.analysis.SymbolInfo
import com.ai.fler.core.analysis.Xref
import com.ai.fler.core.analysis.XrefType
import com.ai.fler.core.analysis.assembler.KeystoneAssembler
import com.ai.fler.core.service.ApkRepacker
import com.ai.fler.core.service.BackupManager
import com.ai.fler.core.service.PatchExporter
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartCallGraphDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.MethodLight
import com.ai.fler.data.dao.MethodWithClass
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import android.net.Uri
import android.util.Log
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SoEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val appContext: Context,
    private val session: AnalysisSession,
    private val backupManager: BackupManager,
    private val keystoneAssembler: KeystoneAssembler,
    private val patchExporter: PatchExporter,
    private val apkRepacker: ApkRepacker,
    private val dartMethodDao: DartMethodDao,
    private val soEditorCache: SoEditorCache,
    private val sessionHolder: SoEditorSessionHolder,
    private val appLogger: AppLogger,
    private val analysisDao: AnalysisDao,
    private val dartCallGraphDao: DartCallGraphDao,
    private val callGraphBuilder: DartCallGraphBuilder,
) : ViewModel() {

    companion object {
        private const val TAG = "SoEditorViewModel"
        const val HEX_PAGE_SIZE = 2048L
        const val DISASM_PAGE_SIZE = 4096L
        const val MAX_RECENT_FILES = 10
        private const val PREFS_NAME = "so_editor"
        private const val KEY_RECENT_FILES = "recent_files"
    }

    private val _uiState = MutableStateFlow(SoEditorUiState())
    val uiState: StateFlow<SoEditorUiState> = _uiState.asStateFlow()

    private val _hexData = MutableStateFlow(HexDataState())
    val hexData: StateFlow<HexDataState> = _hexData.asStateFlow()

    private val _disassemblyData = MutableStateFlow(DisassemblyDataState())
    val disassemblyData: StateFlow<DisassemblyDataState> = _disassemblyData.asStateFlow()

    private val _currentTab = MutableStateFlow(EditorTab.STRUCTURE)
    val currentTab: StateFlow<EditorTab> = _currentTab.asStateFlow()

    private val _selectedOffset = MutableStateFlow(0L)
    val selectedOffset: StateFlow<Long> = _selectedOffset.asStateFlow()

    private var savedSeq = -1L
    private val _patchedOffsets = MutableStateFlow<Set<Long>>(emptySet())
    val patchedOffsets: StateFlow<Set<Long>> = _patchedOffsets.asStateFlow()

    private val _flashOffset = MutableStateFlow<Long?>(null)
    val flashOffset: StateFlow<Long?> = _flashOffset.asStateFlow()

    /** 汇编Tab呼吸脉冲触发器（递增计数器，UI 层用 LaunchedEffect + Animatable 驱动动画）。 */
    private val _flashTrigger = MutableStateFlow(0)
    val flashTrigger: StateFlow<Int> = _flashTrigger.asStateFlow()

    /** 当前选中指令的交叉引用（点击指令行时加载）。 */
    private val _xrefData = MutableStateFlow<XrefDataState>(XrefDataState())
    val xrefData: StateFlow<XrefDataState> = _xrefData.asStateFlow()

    /** 函数边界标注：当前反汇编页中，函数起始地址 → 函数名。 */
    private val _functionOverlay = MutableStateFlow<Map<Long, String>>(emptyMap())
    val functionOverlay: StateFlow<Map<Long, String>> = _functionOverlay.asStateFlow()

    /** Blutter 分析的 Dart 方法标签：SO 偏移 → "ClassName.methodName"。 */
    private val _dartFunctionLabels = MutableStateFlow<Map<Long, String>>(emptyMap())
    val dartFunctionLabels: StateFlow<Map<Long, String>> = _dartFunctionLabels.asStateFlow()

    /**
     * 按 vaddr 排序的函数快照（供 xref 二分查找复用，避免每次点击指令都全量排序 5 万级函数列表）。
     * [functionsSnapshotRef] 跟踪上一次排序时的 functions 引用，引用变化才重建快照。
     */
    private var functionsByVaddr: List<FunctionInfo> = emptyList()
    private var functionsSnapshotRef: List<FunctionInfo>? = null

    /** 结构Tab各子Tab的滚动位置（index to offset），用于切到汇编Tab再回来时保持位置。 */
    private val _structureScrollStates = MutableStateFlow<Map<Int, Pair<Int, Int>>>(emptyMap())
    val structureScrollStates: StateFlow<Map<Int, Pair<Int, Int>>> = _structureScrollStates.asStateFlow()

    /** 结构Tab中上次点击的函数/符号地址（持久保存，不随闪烁 toggle 清空）。 */
    private val _structureFlashAddress = MutableStateFlow<Long?>(null)
    val structureFlashAddress: StateFlow<Long?> = _structureFlashAddress.asStateFlow()

    /** 结构Tab呼吸脉冲触发器（递增计数器，UI 层用 LaunchedEffect + Animatable 驱动动画）。 */
    private val _structureFlashTrigger = MutableStateFlow(0)
    val structureFlashTrigger: StateFlow<Int> = _structureFlashTrigger.asStateFlow()

    /** 结构Tab当前选中的子Tab ordinal（持久化，切到汇编再回来不丢）。 */
    private val _structureSubTab = MutableStateFlow(0)
    val structureSubTab: StateFlow<Int> = _structureSubTab.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())
    val recentFiles: StateFlow<List<RecentFile>> = _recentFiles.asStateFlow()

    private val prefs by lazy {
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // 历史持久化：SharedPreferences + JSON，退出应用后保留。
    // 必须声明在 init 之前：init 里 loadRecentFiles 会用到它，
    // 若放在类后面，init 执行时字段还是 null → NPE 静默吞掉，历史恢复失效
    private val recentJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    /** 当前 SO 对应分析 id（Dart 调用图按 analysis_id 查询）。0 = 未确定。 */
    private var _graphAnalysisId: Long = 0L

    /** Dart 方法索引（按 functionOffset 排序），供 xref 面板定位"该地址属于哪个方法"。 */
    private var dartMethodIndex: List<MethodLight>? = null

    init {
        // 会话恢复：只恢复与导航参数一致的会话。
        // 从项目详情/PP/ASM 进入时带有 filePath 参数（base64），若无视参数直接恢复
        // SessionHolder 的 currentFilePath（上一个文件，如 libapp.so），用户点另一个 SO
        // （libflutter.so）时页面会先以 libapp.so 的状态渲染 → 「显示旧文件数据」bug。
        // 无导航参数（Tab 入口，已移除）时才回退恢复当前会话。
        val navFilePath = decodeNavFilePath(savedStateHandle.get<String>("filePath"))
        val restorePath = navFilePath ?: sessionHolder.currentFilePath
        val restored = if (restorePath != null) sessionHolder.restore(restorePath) else null
        if (restored != null) {
            _uiState.value = SoEditorUiState(
                filePath = restored.filePath,
                fileName = restored.fileName,
                fileSize = restored.fileSize,
                sections = restored.sections,
                symbols = restored.staticSymbols,
                dynamicSymbols = restored.dynamicSymbols,
                functions = restored.functions,
                fileInfo = restored.fileInfo,
                isLoading = false,
                isFileOpen = true,
                isAnalyzing = false
            )
            _currentTab.value = EditorTab.values()[restored.currentTabOrdinal]
            _selectedOffset.value = restored.selectedOffset
            _dartFunctionLabels.value = restored.dartFunctionLabels
            Log.i(TAG, "从 SessionHolder 恢复: ${restored.filePath}")
            // 始终重查标签：1) 标签为空需从 DAO/缓存加载；2) 标签非空时需重建
            // _uiState.functions 的合并（恢复路径只恢复了 Map，没恢复 dart 函数的合并列表）
            viewModelScope.launch {
                loadDartFunctionLabels(restored.filePath)
                // 重查完成后重建函数边界标注：LaunchedEffect 已先行渲染反汇编页，
                // 不刷新 overlay 的话标签加载成功也无法显示在已渲染的指令上
                refreshFunctionOverlay()
            }
        }

        // 从持久化恢复历史（IO 协程，避免主线程同步读 SharedPreferences 卡顿）。
        // 先给空列表，IO 读完再过滤不存在的文件并更新。
        viewModelScope.launch(Dispatchers.IO) {
            val files = loadRecentFiles()
            val existing = files.filter { java.io.File(it.path).exists() }
            if (existing.size != files.size) {
                saveRecentFiles(existing)
            }
            _recentFiles.value = existing
        }
    }

    /** 导航参数中的 filePath 是 base64 URL_SAFE 编码（与 Screen.SoEditor.createRoute 对应）。 */
    private fun decodeNavFilePath(encoded: String?): String? {
        if (encoded.isNullOrEmpty()) return null
        return try {
            String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), Charsets.UTF_8)
        } catch (_: Exception) {
            encoded
        }
    }

    fun setTab(tab: EditorTab) { _currentTab.value = tab }

    /**
     * 待仿真 Tab 消费的调试请求：(函数名或地址, 是否同时下断点)。
     * 跨 ViewModel 桥接（SoEditorViewModel 与 EmulationViewModel 可能不同 owner），
     * 由 EmulationTab 观察并消费，避免直传 EmulationViewModel 实例在独立路由下失效。
     */
    private val _pendingEmuRequest = MutableStateFlow<Pair<String, Boolean>?>(null)
    val pendingEmuRequest: StateFlow<Pair<String, Boolean>?> = _pendingEmuRequest.asStateFlow()

    /** 跳转仿真 Tab 并预填函数/地址（可选同时下断点）。结构页/汇编页长按菜单入口。 */
    fun debugInEmulation(target: String, addBreakpoint: Boolean = false) {
        _pendingEmuRequest.value = target to addBreakpoint
        setTab(EditorTab.EMULATION)
    }

    fun consumeEmuRequest() { _pendingEmuRequest.value = null }

    /** 文件偏移 → 虚拟地址（汇编行跳仿真用；自研引擎恒等返回，失败回退原值）。 */
    suspend fun paddrToVaddr(paddr: Long): Long = session.paddrToVaddr(paddr)

    /**
     * 用节区表构建 vaddr→paddr 映射（纯内存二分，无 JNI 开销）。
     * Rizin isj/aflj 输出的地址均为虚拟地址，而反汇编/十六进制视图工作在
     * 文件偏移坐标，打开文件时需一次性规范化符号与函数的文件偏移。
     * 非加载型节区（如 .symtab/.bss）vsize 为 0，跳过避免虚假映射。
     */
    private fun buildVaddrToPaddrMapper(sections: List<SectionInfo>): (Long) -> Long {
        data class Span(val vaddr: Long, val vsize: Long, val delta: Long)
        val spans = sections
            .filter { it.size > 0 && it.paddr > 0 }
            .map { Span(it.address, it.size, it.paddr - it.address) }
            .sortedBy { it.vaddr }
            .distinctBy { it.vaddr }
        if (spans.isEmpty()) return { it }
        return { vaddr ->
            val idx = spans.binarySearchBy(vaddr) { it.vaddr }
            val hi = if (idx >= 0) idx else -idx - 2
            if (hi >= 0 && vaddr < spans[hi].vaddr + spans[hi].vsize) {
                vaddr + spans[hi].delta
            } else {
                vaddr
            }
        }
    }

    fun setStructureSubTab(ordinal: Int) { _structureSubTab.value = ordinal }

    fun setSelectedOffset(offset: Long) { _selectedOffset.value = offset }

    fun closeFile() {
        savedSeq = -1L
        _flashOffset.value = null
        _flashTrigger.value = 0
        _uiState.value = SoEditorUiState()
        _hexData.value = HexDataState()
        _disassemblyData.value = DisassemblyDataState()
        _currentTab.value = EditorTab.STRUCTURE
        _selectedOffset.value = 0L
        _structureFlashAddress.value = null
        _structureFlashTrigger.value = 0
        _structureSubTab.value = 0
        _dartFunctionLabels.value = emptyMap()
        // 显式关闭文件 → 清除 @Singleton 会话持有者
        sessionHolder.clear()
        // 不调 session.closeAll()：保留 Rizin 会话、soEditorCache、injectedSoPaths，
        // 下次打开同一文件时直接复用（秒开）。
        // 三者均为 @Singleton，App 进程内常驻，App 退出时随进程回收。
    }

    override fun onCleared() {
        super.onCleared()
        // 故意不调 session.closeAll()：SoEditorScreen 是 NavHost 顶层 destination，
        // 用户返回项目列表时 NavBackStackEntry 销毁会触发 onCleared。
        // 若在此关闭 Rizin 会话，下次重开同一 SO 会重新跑 aaa 分析（数秒~数十秒），
        // 体感「缓存没效果」。Rizin 会话由 @Singleton AnalysisSession 持有，App 退出时随进程回收。
    }

    // ==================================================================
    // 打开文件：用 AnalysisSession.open，顺序串行 -> 避免旧版竞争问题
    // ==================================================================
    suspend fun openFile(filePath: String) {
        savedSeq = -1L
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        // 切换 BackupManager 到该文件，加载持久化的撤销栈
        backupManager.setCurrentFile(filePath)

        try {
            val result = session.open(
                filePath = filePath,
                requireCaps = listOf(
                    AnalysisCapability.ELF_PARSING,
                    AnalysisCapability.BYTE_EDIT
                )
            )
            if (result !is com.ai.fler.core.analysis.OpenResult.Success) {
                val reason = (result as? com.ai.fler.core.analysis.OpenResult.Failure)?.reason ?: "未知错误"
                throw Exception(reason)
            }

            // 检查缓存：同路径已查过 sections/symbols/functions 则直接复用（跨 ViewModel 实例）
            val cached = soEditorCache.getMetadata(filePath)
            val sections: List<SectionInfo>
            val staticSymbols: List<SymbolInfo>
            val dynamicSymbols: List<SymbolInfo>
            val functions: List<FunctionInfo>
            val fileInfo: FileInfo?
            val fileSize: Long

            if (cached != null) {
                sections = cached.sections
                staticSymbols = cached.staticSymbols
                dynamicSymbols = cached.dynamicSymbols
                functions = cached.functions
                fileInfo = cached.fileInfo
                fileSize = cached.fileSize
                Log.i(TAG, "使用缓存: $filePath (跳过 Rizin 查询)")
            } else {
                sections = session.getSections()
                val symbolsAll = session.getSymbols(true)
                fileInfo = session.getFileInfo()
                // 坐标规范化：Rizin 的 isj 无 paddr 字段（回退后仍是 vaddr），aflj 的 offset
                // 实为虚拟地址。PIE 库 vaddr≠paddr（如 emu_demo 差 0x4000），直接当文件偏移
                // 跳转会超出 EOF。用节区映射批量转成文件偏移（内存级 LRU 缓存存的就是规范后值）
                val toPaddr = buildVaddrToPaddrMapper(sections)
                functions = try { session.listFunctions() } catch (_: Throwable) { emptyList() }
                    .map { it.copy(offset = toPaddr(it.vaddr)) }
                // 去重：同一导出符号会同时出现在 symtab 与 dynsym（同名同址），
                // 不去重会导致「动态符号」列表出现重复行
                dynamicSymbols = symbolsAll.filter { it.bind != com.ai.fler.core.analysis.SymbolBind.LOCAL }
                    .map { it.copy(paddr = toPaddr(it.address)) }
                    .distinctBy { it.name to it.address }
                staticSymbols = symbolsAll.filter { it.bind == com.ai.fler.core.analysis.SymbolBind.LOCAL }
                    .map { it.copy(paddr = toPaddr(it.address)) }
                fileSize = fileInfo?.fileSize?.takeIf { it > 0 } ?: withContext(Dispatchers.IO) {
                    File(filePath).length()
                }
                soEditorCache.putMetadata(
                    filePath,
                    SoEditorCache.SoMetadata(sections, staticSymbols, dynamicSymbols, functions, fileInfo, fileSize)
                )
                // 诊断日志：验证 vaddr→paddr 规范化是否生效（确认后删除）
                Log.i(TAG, "诊断 sections(text): " + sections.filter { it.name.contains("text") }
                    .joinToString { "${it.name} vaddr=0x${it.address.toString(16)} paddr=0x${it.paddr.toString(16)} size=0x${it.size.toString(16)}" })
                Log.i(TAG, "诊断 functions前5: " + functions.take(5)
                    .joinToString { "${it.name} vaddr=0x${it.vaddr.toString(16)} offset=0x${it.offset.toString(16)}" })
                Log.i(TAG, "诊断 dynsym前5: " + dynamicSymbols.take(5)
                    .joinToString { "${it.name} vaddr=0x${it.address.toString(16)} paddr=0x${it.paddr.toString(16)}" })
            }

            _uiState.value = SoEditorUiState(
                filePath = filePath,
                fileName = File(filePath).name,
                fileSize = fileSize,
                sections = sections,
                symbols = staticSymbols,
                dynamicSymbols = dynamicSymbols,
                functions = functions,
                fileInfo = fileInfo,
                isLoading = false,
                isFileOpen = true,
                isAnalyzing = true
            )
            addToRecent(filePath)
            // 恢复该文件持久化的补丁高亮（上次修改过才有红色）
            refreshPatchedOffsets()
            // 给 UI 层一个帧渲染 "正在分析" 状态，再开始 xref 分析
            delay(50)
            // 加载 Blutter 分析的 Dart 方法标签（如果有该 SO 的分析记录）
            // 改为同步调用，确保 xref 分析完成后再显示编辑器内容
            loadDartFunctionLabels(filePath)
            _uiState.value = _uiState.value.copy(isAnalyzing = false)
            Log.i(TAG, "打开文件成功: $filePath, ${sections.size} 节, ${staticSymbols.size + dynamicSymbols.size} 符号, engine=${result.engineId}")
            appLogger.info(TAG, "打开文件成功: $filePath, ${sections.size} 节, ${staticSymbols.size + dynamicSymbols.size} 符号")
            // 保存会话状态到 @Singleton Holder（切 Tab 等场景可秒恢复）
            sessionHolder.save(SoEditorSessionHolder.SessionState(
                filePath = filePath,
                fileName = File(filePath).name,
                fileSize = fileSize,
                sections = sections,
                staticSymbols = staticSymbols,
                dynamicSymbols = dynamicSymbols,
                functions = functions,
                fileInfo = fileInfo,
                currentTabOrdinal = _currentTab.value.ordinal,
                selectedOffset = _selectedOffset.value,
                dartFunctionLabels = _dartFunctionLabels.value,
            ))
        } catch (e: Exception) {
            Log.e(TAG, "打开文件失败", e)
            appLogger.error(TAG, "打开文件失败: ${e.message}")
            // 失败时全量重置（不留上个文件的数据）：openFile 只在「未打开或切换文件」时调用，
            // 若保留旧 uiState，从 libapp.so 切到 libflutter.so 打开失败时会残留 libapp.so 的数据
            _uiState.value = SoEditorUiState(
                isLoading = false,
                errorMessage = "打开文件失败: ${e.message}"
            )
        }
    }

    // ==================================================================
    // Hex 数据 / Disassembly / 补丁（直接走 AnalysisSession）
    // ==================================================================

    fun loadHexData(offset: Long, size: Long = HEX_PAGE_SIZE) {
        viewModelScope.launch {
            _hexData.value = _hexData.value.copy(isLoading = true)
            _selectedOffset.value = offset
            try {
                val data = session.readBytes(offset, size)
                _hexData.value = HexDataState(
                    offset = offset,
                    data = data,
                    isLoading = false
                )
            } catch (e: Exception) {
                _hexData.value = HexDataState(
                    offset = offset,
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
    }

    suspend fun writeByte(offset: Long, newValue: Byte): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val f = File(_uiState.value.filePath)
                if (f.exists()) backupManager.createBackupIfNeeded(f)
                val ok = session.writeBytes(offset, byteArrayOf(newValue), _uiState.value.fileName)
                if (ok) {
                    refreshPatchedOffsets()
                    loadHexData(_hexData.value.offset, _hexData.value.data.size.toLong())
                }
                ok
            } catch (e: Exception) {
                Log.e(TAG, "写入字节失败", e)
                false
            }
        }
    }

    suspend fun applyPatch(offset: Long, newBytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 首次编辑前创建 .bak
                val f = File(_uiState.value.filePath)
                if (f.exists()) backupManager.createBackupIfNeeded(f)
                session.writeBytes(offset, newBytes, _uiState.value.fileName).also {
                    if (it) refreshPatchedOffsets()
                }
            } catch (e: Exception) {
                Log.e(TAG, "应用补丁失败", e)
                false
            }
        }
    }

    // ==================================================================
    // 汇编指令补丁：Keystone 编码 -> applyPatch 写盘
    // ==================================================================

    suspend fun applyInstructionPatch(offset: Long, instruction: String, args: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val assembly = if (args.isBlank()) instruction.trim() else "${instruction.trim()} ${args.trim()}"
                val newBytes = encodeInstruction(assembly, offset) ?: run {
                    Log.w(TAG, "汇编失败: $assembly")
                    return@withContext false
                }
                applyPatch(offset, newBytes)
            } catch (e: Exception) {
                Log.e(TAG, "汇编指令补丁失败: $instruction $args", e)
                false
            }
        }
    }

    private fun encodeInstruction(assembly: String, address: Long): ByteArray? {
        if (assembly.isBlank()) return null
        // Keystone 大小写敏感：原文本和小写各试一遍
        val attempts = if (assembly == assembly.lowercase()) listOf(assembly)
        else listOf(assembly, assembly.lowercase())
        for (a in attempts) {
            val bytes = try { keystoneAssembler.assemble(a, address)?.takeIf { it.isNotEmpty() } }
                catch (e: Exception) { Log.w(TAG, "Keystone 汇编失败: $a", e); null }
            if (bytes != null) return bytes
        }
        return null
    }

    fun assembleInstruction(assembly: String, address: Long): ByteArray? =
        encodeInstruction(assembly, address)

    fun loadDisassembly(offset: Long, size: Long = DISASM_PAGE_SIZE, highlightAfterLoad: Long? = null) {
        viewModelScope.launch {
            // 坐标保护统一入口：传入值超出文件范围时按 vaddr 换算。
            // 覆盖所有跳转路径（符号/函数点击、节区跳转、xref 点击、输入框），
            // 避免某符号 vaddr 未落在任何节区时 paddr 映射失败直接拿 vaddr 读盘
            val target = resolveJumpAddress(offset)
            val highlight = highlightAfterLoad?.let { resolveJumpAddress(it) }
            val list = loadDisassemblyInternal(target, size, highlight, contextBefore = highlight != null)
            // 兜底：跳转前 512B 上下文落进非代码区时，rz 读取可能被段 map 截断，
            // 解码结果到不了目标地址。此时去掉前文上下文从目标处重试，保证目标可见
            if (highlight != null && list.none { it.address == highlight }) {
                loadDisassemblyInternal(target, size, highlight, contextBefore = false)
            }
        }
    }

    private suspend fun loadDisassemblyInternal(
        offset: Long,
        size: Long,
        highlightAfterLoad: Long?,
        contextBefore: Boolean
    ): List<DisasmInstruction> {
        _disassemblyData.value = _disassemblyData.value.copy(isLoading = true)
        return try {
                // 交叉引用跳转时，往前加载 512 字节上下文（ARM64 = 128 条指令），
                // 让用户能看到 call 目标前后的代码，而不是只从目标地址开始
                val contextBefore2 = if (contextBefore) 512L else 0L
                val loadOffset = (offset - contextBefore2).coerceAtLeast(0L)
                // 对齐到 4 字节边界（ARM64 指令宽度）
                val alignedOffset = loadOffset - (loadOffset % 4)
                val loadSize = size + (offset - alignedOffset)

                var errorMsg: String? = null
                val list = if (_uiState.value.isFileOpen) {
                    val bytes = session.readBytes(alignedOffset, loadSize)
                    if (bytes.isEmpty()) emptyList()
                    else {
                        // Capstone 反汇编是 CPU 密集 JNI 调用（1024 条 ARM64 解码 + 对象创建），
                        // 必须在 IO 线程执行，否则主线程卡顿掉帧（Skipped N frames）
                        val (decoded, decodeError) = withContext(Dispatchers.IO) {
                            try {
                                val l = com.ai.fler.core.jni.CapstoneBindings.disassembleWithCapstone(
                                    bytes, alignedOffset
                                )?.map { DisasmInstruction.fromJni(it) }
                                when {
                                    l == null -> emptyList<DisasmInstruction>() to "Capstone 反汇编不可用"
                                    l.isEmpty() -> l to "该区域无法用 Capstone 解码"
                                    else -> l to null
                                }
                            } catch (_: Throwable) {
                                emptyList<DisasmInstruction>() to "反汇编失败"
                            }
                        }
                        errorMsg = decodeError
                        decoded
                    }
                } else emptyList()
                // 诊断日志：定位跳转后无数据的断点（确认后删除）
                Log.i(TAG, "诊断 loadDisassembly: 请求offset=0x${offset.toString(16)} 对齐=0x${alignedOffset.toString(16)} " +
                    "size=$loadSize 上下文=$contextBefore isFileOpen=${_uiState.value.isFileOpen} 指令数=${list.size} err=$errorMsg")
                _disassemblyData.value = DisassemblyDataState(
                    baseAddress = alignedOffset,
                    loadedSize = loadSize,
                    highlightAddress = highlightAfterLoad,
                    instructions = list,
                    isLoading = false,
                    errorMessage = errorMsg
                )
                updateFunctionOverlay(list)
                // 数据加载完成后才触发闪烁，避免时序竞争（闪烁放完数据才到）
                if (highlightAfterLoad != null) {
                    setHighlightAddress(highlightAfterLoad)
                }
                list
            } catch (e: Exception) {
                _disassemblyData.value = DisassemblyDataState(
                    baseAddress = offset,
                    loadedSize = size,
                    isLoading = false,
                    errorMessage = e.message
                )
                emptyList()
            }
    }

    /**
     * 智能地址跳转：汇编页输入框坐标是文件偏移，但用户常粘贴虚拟地址
     * （如长按菜单复制的函数 vaddr）。超出文件大小时自动按 vaddr→paddr 换算。
     */
    suspend fun resolveJumpAddress(input: Long): Long {
        if (input <= 0) return input
        val fsize = _uiState.value.fileSize
        if (fsize > 0 && input >= fsize) {
            val mapped = session.vaddrToPaddr(input)
            if (mapped != input && mapped < fsize) {
                Log.i(TAG, "跳转地址越界，按 vaddr 换算: 0x${input.toString(16)} → 0x${mapped.toString(16)}")
                return mapped
            }
        }
        return input
    }

    /** 向前追加加载的标志位（避免触发 isLoading 导致 UI 切换、DisassemblyListView 被销毁重建）。 */
    private var isLoadMoreInProgress = false

    /** 向前追加加载指令（LazyColumn 滚到顶部时触发）。 */
    fun loadMoreBefore() {
        if (isLoadMoreInProgress) return
        val current = _disassemblyData.value
        if (current.instructions.isEmpty()) return
        val baseAddr = current.baseAddress
        if (baseAddr <= 0) return

        // 往前加载 2048 字节（512 条 ARM64 指令）
        val loadSize = 2048L
        val loadOffset = (baseAddr - loadSize).coerceAtLeast(0L)
        val alignedOffset = loadOffset - (loadOffset % 4)
        val actualSize = baseAddr - alignedOffset
        if (actualSize <= 0) return

        isLoadMoreInProgress = true

        viewModelScope.launch {
            try {
                val bytes = session.readBytes(alignedOffset, actualSize)
                val newInstructions = if (bytes.isEmpty()) emptyList()
                else {
                    withContext(Dispatchers.IO) {
                        try {
                            com.ai.fler.core.jni.CapstoneBindings.disassembleWithCapstone(
                                bytes, alignedOffset
                            )?.map { DisasmInstruction.fromJni(it) } ?: emptyList()
                        } catch (_: Throwable) { emptyList() }
                    }
                }

                if (newInstructions.isNotEmpty()) {
                    // 合并：新指令在前，旧指令在后；去重（防止地址重叠）
                    val existingAddrs = current.instructions.map { it.address }.toSet()
                    val merged = newInstructions.filter { it.address !in existingAddrs } + current.instructions

                    _disassemblyData.value = current.copy(
                        baseAddress = alignedOffset,
                        loadedSize = current.loadedSize + actualSize,
                        instructions = merged
                    )
                    updateFunctionOverlay(merged)
                }
            } catch (_: Throwable) {
                // 静默失败，不影响已有数据
            } finally {
                isLoadMoreInProgress = false
            }
        }
    }

    fun setHighlightAddress(address: Long?) {
        if (address == null) {
            _flashOffset.value = null
            _disassemblyData.value = _disassemblyData.value.copy(highlightAddress = null)
            return
        }
        _disassemblyData.value = _disassemblyData.value.copy(highlightAddress = address)
        _flashOffset.value = address
        // 递增触发器，UI 层用 LaunchedEffect + Animatable 驱动 0→1→0→1→0 脉冲
        _flashTrigger.value = _flashTrigger.value + 1
    }

    /** 设置 hex 页闪烁目标（文件偏移），递增触发器驱动 UI 层脉冲动画。 */
    fun setFlashOffset(address: Long?) {
        _flashOffset.value = address
        _flashTrigger.value = _flashTrigger.value + 1
    }

    /** 加载指定地址的交叉引用（点击指令行时调用）。 */
    fun loadXrefs(address: Long) {
        _xrefData.value = _xrefData.value.copy(address = address, isLoading = true)
        viewModelScope.launch {
            try {
                val to = session.xrefsTo(address).toMutableList()
                val from = session.xrefsFrom(address).toMutableList()
                // 补充 Dart 调用图（真实方法级交叉引用），只作用于 libapp 且有图数据的分析
                supplementDartCallGraph(address, to, from)
                // 为所有 xref 地址计算函数名（优先 Dart 标签，再查 FunctionInfo 包含关系）
                val allXrefAddrs = mutableSetOf<Long>()
                to.forEach { allXrefAddrs.add(it.from) }
                from.forEach { allXrefAddrs.add(it.to) }
                val fnNames = buildXrefFunctionNames(allXrefAddrs)
                _xrefData.value = XrefDataState(
                    address = address,
                    xrefsTo = to,
                    xrefsFrom = from,
                    xrefFunctionNames = fnNames,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.w(TAG, "加载交叉引用失败: 0x${address.toString(16)}", e)
                _xrefData.value = XrefDataState(address = address, isLoading = false)
            }
        }
    }

    /**
     * 用 Dart 调用图（dart_call_edges）补充方法级交叉引用。
     *
     * 语义：仅在当前 SO 是 libapp（有 Dart 方法标签）且相应分析已建图时生效。
     * - xrefsTo（谁引用我）：该地址所属方法的所有真实调用方（callersOfMethod）→ Xref(from=调用方函数vaddr, to=当前地址, CALL)
     * - xrefsFrom（我引用谁）：该地址所属方法真实调用的子方法（calleesOf） → Xref(from=当前地址, to=被调vaddr, CALL)
     * 非 Flutter / 无图数据时为空操作（不影响原生 Rizin xref）。
     */
    private suspend fun supplementDartCallGraph(
        address: Long,
        to: MutableList<Xref>,
        from: MutableList<Xref>
    ) {
        val analysisId = _graphAnalysisId
        if (analysisId <= 0 || !callGraphBuilder.isBuilt(analysisId)) return
        val methodId = findDartMethodContaining(address)?.id ?: return

        val callers = runCatching { dartCallGraphDao.callersOfMethod(analysisId, methodId, 64) }.getOrNull()
        callers?.forEach { c ->
            if (to.none { it.from == c.vaddr && it.to == address }) {
                to += Xref(from = c.vaddr, to = address, type = XrefType.CALL)
            }
        }
        val callees = runCatching { dartCallGraphDao.calleesOf(analysisId, methodId, 64) }.getOrNull()
        callees?.forEach { c ->
            if (from.none { it.from == address && it.to == c.vaddr }) {
                from += Xref(from = address, to = c.vaddr, type = XrefType.CALL)
            }
        }
    }

    /** 二分查找包含指定 vaddr 的 Dart 方法；无则返回 null。索引在加载 Dart 标签时构建。 */
    private fun findDartMethodContaining(vaddr: Long): MethodLight? {
        val idx = dartMethodIndex ?: return null
        if (idx.isEmpty() || vaddr <= 0) return null
        var lo = 0
        var hi = idx.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (idx[mid].functionOffset!! <= vaddr) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (found < 0) return null
        for (i in found downTo 0) {
            val m = idx[i]
            val size = m.functionSize ?: 0
            if (size > 0 && vaddr <= m.functionOffset!! + size) return m
            if (size <= 0 && m.functionOffset == vaddr) return m
            if (size > 0 && vaddr > m.functionOffset!! + size) break
        }
        return null
    }

    /**
     * 从文件偏移（反汇编坐标）加载交叉引用。
     *
     * 反汇编指令的 [DisasmInstruction.address] 是文件偏移（paddr），
     * 而 [loadXrefs] 内的 axtj/axfj 工作于 Rizin 虚拟地址（vaddr）空间。
     * PIE/加载后的库 vaddr≠paddr（如 emu_demo 差 0x4000）时，直接拿 paddr
     * 去查会让所有反汇编入口的 xref 永远为空——这里先做 paddr→vaddr 换算再查。
     */
    fun loadXrefsAtFileOffset(fileOffset: Long) {
        _xrefData.value = _xrefData.value.copy(address = fileOffset, isLoading = true)
        viewModelScope.launch {
            val vaddr = session.paddrToVaddr(fileOffset)
            loadXrefs(vaddr)
        }
    }

    /**
     * 为一批 xref 地址计算函数名。
     * 优先 [dartFunctionLabels]（ClassName.methodName），
     * 再查 [uiState].functions 的包含关系（vaddr ≤ addr < vaddr+size）。
     * 若两者都不命中，从函数列表找最近的函数起始地址。
     *
     * 排序快照按 functions 引用复用：仅当 [uiState].functions 引用变化时才重建
     * [functionsByVaddr]，避免每次点击指令都对 5 万级函数列表重复 sortedBy。
     * 整体在 [Dispatchers.Default] 执行，防止首次构建快照时主线程排序卡顿。
     */
    private suspend fun buildXrefFunctionNames(addresses: Set<Long>): Map<Long, String> =
        withContext(Dispatchers.Default) {
            if (addresses.isEmpty()) return@withContext emptyMap()
            val dartLabels = _dartFunctionLabels.value
            val functions = _uiState.value.functions
            if (functions.isEmpty()) return@withContext emptyMap()

            // 引用未变则复用已排序快照；引用变了（如 Dart 标签合并后）才重建
            val sorted = if (functionsSnapshotRef === functions) {
                functionsByVaddr
            } else {
                functions.sortedBy { it.vaddr }.also {
                    functionsByVaddr = it
                    functionsSnapshotRef = functions
                }
            }
            if (sorted.isEmpty()) return@withContext emptyMap()

            val result = mutableMapOf<Long, String>()
            for (addr in addresses) {
                // 1) 精确命中 Dart 标签
                if (addr in dartLabels) { result[addr] = dartLabels[addr]!!; continue }
                // 2) 二分定位最后一个 vaddr <= addr 的函数，从近到远找包含 addr 的函数。
                //    函数区间互不重叠时，包含 addr 的只会是 vaddr 最大的那几个之一。
                val idx = sorted.binarySearchBy(addr) { it.vaddr }
                val hi = if (idx >= 0) idx else -idx - 2
                if (hi >= 0) {
                    var fallback: String? = null   // size=0 的最近函数（退而求其次）
                    for (i in hi downTo 0) {
                        val f = sorted[i]
                        if (f.size > 0) {
                            // 最近的 size>0 函数包含 addr：最精确的匹配
                            if (addr < f.vaddr + f.size) { result[addr] = f.name }
                            // 该函数在 addr 之前已结束，更早的函数更不可能包含 addr
                            break
                        }
                        // size=0 的函数视为退而求其次的候选，继续向前找 size>0 的精确匹配
                        if (fallback == null) fallback = f.name
                    }
                    if (addr in result) continue
                    if (fallback != null) { result[addr] = fallback; continue }
                    // 3) 无包含关系：取 addr 之前的最后一个函数作为近似归属
                    result[addr] = sorted[hi].name
                }
            }
            result
        }

    /** 清除交叉引用面板。 */
    fun clearXrefs() {
        _xrefData.value = XrefDataState()
    }

    /** 加载字符串扫描结果（Rizin izzj）。 */
    fun loadStrings() {
        if (_uiState.value.strings.isNotEmpty()) return  // 已加载过
        viewModelScope.launch {
            try {
                val result = session.scanStrings()
                Log.i(TAG, "字符串扫描完成: ${result.size} 条")
                _uiState.value = _uiState.value.copy(strings = result)
            } catch (e: Exception) {
                Log.w(TAG, "字符串扫描失败", e)
            }
        }
    }

    // ==================================================================
    // 结构Tab：滚动位置保存 + 点击行闪烁
    // ==================================================================

    /** 保存某个子Tab的滚动位置（子Tab ordinal → (index, offset)）。 */
    fun saveStructureScroll(subTabOrdinal: Int, firstVisibleItemIndex: Int, firstVisibleItemScrollOffset: Int) {
        _structureScrollStates.value = _structureScrollStates.value +
            (subTabOrdinal to (firstVisibleItemIndex to firstVisibleItemScrollOffset))
    }

    /** 读取某个子Tab的滚动位置。 */
    fun getStructureScroll(subTabOrdinal: Int): Pair<Int, Int>? = _structureScrollStates.value[subTabOrdinal]

    /** 从结构Tab跳转汇编前，记录点击地址（返回结构Tab时要闪烁的行）。 */
    fun setStructureFlashAddress(address: Long?) {
        _structureFlashAddress.value = address
    }

    /** 从汇编切回结构Tab时，触发呼吸脉冲（2次呼吸，0→1→0 由 UI 层 LaunchedEffect + Animatable 驱动）。
     *  flashAddress 保持不清空，下次切回来还能触发。 */
    fun triggerStructureFlash() {
        val addr = _structureFlashAddress.value ?: return
        // 递增触发器，UI 层用 LaunchedEffect 监听后驱动 Animatable 动画
        _structureFlashTrigger.value = _structureFlashTrigger.value + 1
    }

    /** 用当前反汇编页的指令重建函数边界标注（异步标签加载完成后调用）。 */
    private suspend fun refreshFunctionOverlay() {
        val instructions = _disassemblyData.value.instructions
        if (instructions.isNotEmpty()) {
            updateFunctionOverlay(instructions)
        }
    }

    /** 根据当前反汇编页的指令列表，更新函数边界标注。 */
    private suspend fun updateFunctionOverlay(instructions: List<DisasmInstruction>) {
        // 5 万级 functions 的 associateBy 映射构建较耗时，放 Default 线程避免主线程卡顿
        val overlay = withContext(Dispatchers.Default) {
            // 指令地址是文件偏移（paddr）坐标，必须用 FunctionInfo.offset 匹配（vaddr 会全部错位）
            val funcSet = _uiState.value.functions.associateBy { it.offset }
            val dartLabels = _dartFunctionLabels.value
            val overlay = mutableMapOf<Long, String>()
            for (inst in instructions) {
                // 优先使用 Blutter 分析的 Dart 方法名
                dartLabels[inst.address]?.let { overlay[inst.address] = it }
                // 再用 Rizin 识别的函数名
                val func = funcSet[inst.address]
                if (func != null && inst.address !in overlay) {
                    overlay[inst.address] = func.name
                }
            }
            overlay
        }
        _functionOverlay.value = overlay
    }

    /** 加载 Blutter 分析的 Dart 方法标签到 SO 偏移的映射。 */
    private suspend fun loadDartFunctionLabels(soPath: String) {
        try {
            // 语义校验：Blutter 只分析 libapp.so，dart 标签只可能属于它。
            // 旧版 bug（libraries 表共用 analysis_id）会让 libflutter.so 等库也查到
            // libapp.so 的方法并缓存污染标签，这里直接跳过并清掉可能的污染缓存
            if (File(soPath).name != "libapp.so") {
                soEditorCache.invalidateDartLabels(soPath)
                return
            }
            // 记录该 SO 对应分析 id（Dart 调用图按 analysis_id 查询）并后台触发生成调用图。
            // 非 Flutter 库上面已 return，这里只可能命中 libapp。
            runCatching {
                if (_graphAnalysisId <= 0) {
                    _graphAnalysisId = analysisDao.getByLibappPath(soPath)?.id ?: 0L
                }
                if (_graphAnalysisId > 0) {
                    if (dartMethodIndex == null) {
                        dartMethodIndex = dartMethodDao.getByAnalysisIdLight(_graphAnalysisId)
                            .sortedBy { it.functionOffset ?: 0 }
                    }
                    if (!callGraphBuilder.isBuilt(_graphAnalysisId)) {
                        viewModelScope.launch {
                            val n = callGraphBuilder.build(_graphAnalysisId)
                            Log.i(TAG, "Dart 调用图构建完成: analysis=$_graphAnalysisId 边=$n")
                        }
                    }
                }
            }.getOrElse { Log.w(TAG, "Dart 调用图初始化失败（非关键）", it) }
            // 1) 先看跨 ViewModel 的 DartLabels 缓存（命中则跳过 DAO 查询 + 标签构建）
            val cachedLabels = soEditorCache.getDartLabels(soPath)
            // 空标签缓存视为未命中：早期 bug（functionOffset 映射失败）会缓存空结果并标记已注入，
            // 命中后永远无法重试；必须丢弃空缓存重新查 DAO。
            if (cachedLabels != null && cachedLabels.labels.isNotEmpty()) {
                _dartFunctionLabels.value = cachedLabels.labels
                // 合并 5 万级函数列表在 Default 线程做，避免主线程卡顿
                val merged = withContext(Dispatchers.Default) {
                    val existingAddrs = _uiState.value.functions.map { it.vaddr }.toSet()
                    _uiState.value.functions + cachedLabels.dartFunctions.filter { it.vaddr !in existingAddrs }
                }
                _uiState.value = _uiState.value.copy(functions = merged)
                // 即使命中 DartLabels 缓存，仍要检查 Rizin 注入状态（可能上次注入失败）
                if (soEditorCache.isInjected(soPath)) {
                    Log.i(TAG, "Dart 方法标签 + Rizin 注入均已缓存: ${cachedLabels.labels.size} 条")
                    // xref 已就绪则跳过 aar（会话级去重，避免每次切 Tab 回来都重扫）
                    if (!soEditorCache.isXrefReady(soPath)) {
                        val rebuilt = session.reanalyzeXrefs()
                        // 仅在重建成功时才标记；失败留待下次打开重试（避免失败后本会话永不补扫）
                        if (rebuilt) {
                            soEditorCache.markXrefReady(soPath)
                        } else {
                            Log.w(TAG, "xref 补充扫描失败，未标记就绪，下次打开自动重试")
                        }
                    } else {
                        Log.i(TAG, "xref 已就绪，跳过 aar")
                    }
                } else {
                    // 注入用 vaddr（Rizin 虚拟地址空间）；labels 存的是 paddr（UI/反汇编坐标）
                    val pairs = cachedLabels.dartFunctions.map { it.vaddr to it.name }
                    // 注入前诊断（可选，用于排查地址空间问题）
                    if (pairs.isNotEmpty()) {
                        session.checkAddressSpace(pairs.first().first)
                    }
                    // 注入全部 Dart 函数作为 flag（只设名不调 af，不破坏 xref 表）
                    val injected = session.defineFunctions(pairs)
                    // 仅在确实注入过 flag 时才标记（防止注入失败也标记，导致后续永不重试）
                    if (injected > 0) {
                        soEditorCache.markInjected(soPath)
                    } else {
                        Log.w(TAG, "Rizin flag 注入失败（0 个成功），不标记已注入，下次打开自动重试")
                    }
                    // 补充扫描 xref（aar 不依赖函数边界，不影响已有 xref）
                    val rebuilt = session.reanalyzeXrefs()
                    Log.i(TAG, "Dart 标签缓存命中但 Rizin 未注入: 注入 $injected 个, xref 补充扫描 $rebuilt")
                }
                return
            }

            // 2) 缓存未命中：查 DAO → 构建标签 → 缓存 → 注入 Rizin
            val methods = dartMethodDao.getMethodsBySoPathLight(soPath)
            if (methods.isEmpty()) return
            // 坐标归一 + 标签构建 + 函数合并都在 Default 线程做（5 万级数据，主线程会卡顿掉帧）：
            // - Blutter methods.address 是 ELF 虚拟地址（vaddr），而反汇编/函数列表
            //   工作于文件偏移（paddr）。PIE 库 vaddr≠paddr（如 emu_demo 差 0x4000）时若直接
            //   当偏移使用，标签 key 与指令地址永远错位 → 加载了也不显示。用节区映射换算。
            val sections = _uiState.value.sections
            val (labels, dartFunctions, merged) = withContext(Dispatchers.Default) {
                val toPaddr = buildVaddrToPaddrMapper(sections)
                val labels = mutableMapOf<Long, String>()
                val dartFunctions = mutableListOf<FunctionInfo>()
                for (m in methods) {
                    val addr = m.functionOffset ?: continue
                    if (addr <= 0) continue
                    val name = "${m._className}.${m.methodName}"
                    val paddr = toPaddr(addr)
                    labels[paddr] = name
                    dartFunctions.add(
                        FunctionInfo(
                            name = name,
                            // offset 必须与反汇编/函数列表同坐标（paddr），供 UI 跳转与标签匹配
                            offset = paddr,
                            vaddr = addr,
                            size = m.functionSize ?: 0,
                        )
                    )
                }
                // 合并到 uiState.functions（去重：Blutter 优先于 Rizin）
                val existingAddrs = _uiState.value.functions.map { it.vaddr }.toSet()
                val merged = _uiState.value.functions + dartFunctions.filter { it.vaddr !in existingAddrs }
                Triple(labels, dartFunctions, merged)
            }
            // 写入 DartLabels 缓存（跨 ViewModel 复用，避免下次再查 DAO）；
            // 空标签不写缓存，避免污染后永久命中空结果。
            if (labels.isNotEmpty()) {
                soEditorCache.putDartLabels(soPath, SoEditorCache.DartLabels(labels, dartFunctions))
            } else {
                Log.w(TAG, "Dart 方法查询到 ${methods.size} 条但 functionOffset 全无效（可能分析结果无地址），跳过缓存与注入")
            }

            _dartFunctionLabels.value = labels
            _uiState.value = _uiState.value.copy(functions = merged)
            // 仅首次打开该 SO 时注入 Rizin（flag 不可重复设置）。
            // 注入用 vaddr：Rizin 工作于虚拟地址空间（io.va=true），f 命令按 vaddr 解释
            if (!soEditorCache.isInjected(soPath)) {
                val pairs = dartFunctions.map { it.vaddr to it.name }
                // 注入前诊断（可选，用于排查地址空间问题）
                if (pairs.isNotEmpty()) {
                    session.checkAddressSpace(pairs.first().first)
                }
                // 注入全部 Dart 函数作为 flag（只设名不调 af，不破坏 xref 表）
                val injected = session.defineFunctions(pairs)
                // 仅在确实注入过 flag 时才标记（防止空 pairs 或注入失败也标记，导致后续永不重试）
                if (injected > 0) {
                    soEditorCache.markInjected(soPath)
                } else {
                    Log.w(TAG, "Rizin flag 注入失败（0 个成功），不标记已注入，下次打开自动重试")
                }
                // 补充扫描 xref（aar 不依赖函数边界，不影响已有 xref）
                val rebuilt = session.reanalyzeXrefs()
                // 仅在重建成功时才标记；失败留待下次打开重试
                if (rebuilt) soEditorCache.markXrefReady(soPath)
                Log.i(TAG, "加载 Dart 方法标签: ${labels.size} 条 (Blutter), 函数合并后 ${merged.size} 条, Rizin 注入 $injected 个, xref 补充扫描 $rebuilt")
            } else {
                // Rizin 注入已缓存，但 xref 可能尚未就绪（如旧项目升级后首次打开）
                if (!soEditorCache.isXrefReady(soPath)) {
                    val rebuilt = session.reanalyzeXrefs()
                    // 仅在重建成功时才标记；失败留待下次打开重试
                    if (rebuilt) soEditorCache.markXrefReady(soPath)
                    Log.i(TAG, "Rizin 注入已缓存，补充 xref 扫描: $rebuilt")
                } else {
                    Log.i(TAG, "Rizin 注入已缓存: ${labels.size} 条 (跳过 Rizin 注入 + aar)")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "加载 Dart 方法标签失败（非关键）", e)
        }
    }

    /** 从 BackupManager 重建补丁偏移高亮（应用补丁 / 撤销 / 恢复时调用）。 */
    private fun refreshPatchedOffsets() {
        val records = backupManager.getPatchRecords()
        _patchedOffsets.value = records
            .flatMap { r -> (0 until r.newBytes.size).map { r.address + it } }
            .toSet()
    }

    fun commitChanges(): Int {
        val records = backupManager.getPatchRecords()
        val unsaved = records.filter { it.seq > savedSeq }
        if (unsaved.isNotEmpty()) {
            savedSeq = records.maxOfOrNull { it.seq } ?: savedSeq
            refreshPatchedOffsets()
        }
        return unsaved.size
    }

    // ==================================================================
    // 撤销 / 导出
    // ==================================================================

    /**
     * 撤销上一次补丁（IO 异步，避免主线程读写撤销栈文件）。
     *
     * @param onResult 主线程回调，参数为被撤销的记录；无可撤销时为 null。
     */
    fun undo(onResult: (com.ai.fler.core.service.PatchRecord?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val record = backupManager.undo()
            withContext(Dispatchers.Main) {
                if (record != null) {
                    refreshPatchedOffsets()
                    if (_currentTab.value == EditorTab.STRUCTURE) setTab(EditorTab.DISASSEMBLY)
                    viewModelScope.launch {
                        session.writeRawBytes(record.address, record.oldBytes)
                        loadHexData(_hexData.value.offset, _hexData.value.data.size.toLong())
                        loadDisassembly(
                            _disassemblyData.value.baseAddress,
                            _disassemblyData.value.loadedSize.takeIf { it > 0 } ?: DISASM_PAGE_SIZE,
                            highlightAfterLoad = record.address
                        )
                    }
                }
                onResult(record)
            }
        }
    }

    suspend fun exportPatchesToUri(uri: Uri): Boolean {
        val records = backupManager.getPatchRecords()
        if (records.isEmpty()) return false
        return patchExporter.exportToUri(
            uri = uri,
            soFileName = _uiState.value.fileName,
            records = records
        )
    }

    suspend fun exportSoToUri(uri: Uri): Boolean {
        if (!_uiState.value.isFileOpen) return false
        if (backupManager.getPatchRecords().isEmpty()) return false
        return patchExporter.exportSoToUri(uri, File(_uiState.value.filePath))
    }

    /** 导出补丁 .patch 到工作目录（SAF 或兜底 App 缓存）。 */
    suspend fun exportPatchesToWorkDir(): Boolean {
        val records = backupManager.getPatchRecords()
        if (records.isEmpty()) return false
        return patchExporter.exportToWorkDir(
            soFileName = _uiState.value.fileName,
            records = records
        )
    }

    /** 导出修改后的 SO 到工作目录（SAF 或兜底 App 缓存）。 */
    suspend fun exportSoToWorkDir(): Boolean {
        if (!_uiState.value.isFileOpen) return false
        if (backupManager.getPatchRecords().isEmpty()) return false
        return patchExporter.exportSoToWorkDir(File(_uiState.value.filePath))
    }

    // ==================================================================
    // 回打 APK（补丁后的 SO → APK + 对齐 + 可选重签名）
    // ==================================================================

    /** 回打前置信息（弹窗展示 + 可用性判断）。 */
    data class RepackInfo(
        val loading: Boolean = false,
        val apkPath: String = "",
        val apkName: String = "",
        val apkSize: Long = 0L,
        val soEntryName: String = "",
        val patchCount: Int = 0,
        /** 无补丁 / 找不到源 APK / APK 文件缺失 时不可用。 */
        val available: Boolean = false,
        val reason: String = "",
    )

    /** 回打执行状态。 */
    data class RepackState(
        val running: Boolean = false,
        val stage: String = "",
        val progress: Float = 0f,
        val error: String? = null,
        /** 一次性成功消息（UI 消费后调 consumeRepackResult 清空）。 */
        val successMessage: String? = null,
    )

    private val _repackInfo = MutableStateFlow(RepackInfo())
    val repackInfo: StateFlow<RepackInfo> = _repackInfo.asStateFlow()

    private val _repackState = MutableStateFlow(RepackState())
    val repackState: StateFlow<RepackState> = _repackState.asStateFlow()

    /** 自定义密钥是否已导入。 */
    private val _hasCustomKey = MutableStateFlow(false)
    val hasCustomKey: StateFlow<Boolean> = _hasCustomKey.asStateFlow()

    /** 加载回打前置信息（打开弹窗时调用）。 */
    fun loadRepackInfo() {
        val soPath = _uiState.value.filePath
        val patchCount = backupManager.getPatchRecords().size
        if (!_uiState.value.isFileOpen || soPath.isBlank()) {
            _repackInfo.value = RepackInfo(patchCount = patchCount, reason = "未打开文件")
            return
        }
        if (patchCount == 0) {
            _repackInfo.value = RepackInfo(patchCount = 0, reason = "无补丁记录，无内容可回打")
            return
        }
        _repackInfo.value = _repackInfo.value.copy(loading = true, patchCount = patchCount)
        viewModelScope.launch {
            val apkPath = apkRepacker.resolveApkPathForSo(soPath)
            val info = if (apkPath == null) {
                RepackInfo(
                    patchCount = patchCount,
                    reason = "未找到该 SO 所属项目的源 APK（SAF 直接打开的游离 SO 不支持回打）",
                )
            } else {
                val apkFile = File(apkPath)
                val entry = apkRepacker.resolveSoEntry(
                    apkFile, File(soPath).name, soFileLen(soPath)
                )
                RepackInfo(
                    apkPath = apkPath,
                    apkName = apkFile.name,
                    apkSize = apkFile.length(),
                    soEntryName = entry ?: "",
                    patchCount = patchCount,
                    available = entry != null,
                    reason = if (entry == null) "APK 内未找到 ${File(soPath).name} 对应条目" else "",
                )
            }
            _repackInfo.value = info
            _hasCustomKey.value = apkRepacker.customKeystoreFile.length() > 0
        }
    }

    /** 当前 SO 工作文件大小（条目匹配用；补丁不改大小）。 */
    private fun soFileLen(soPath: String): Long = runCatching { File(soPath).length() }.getOrDefault(0L)

    /** 导入自定义签名密钥库（SAF 选中后复制到私有目录）。 */
    fun importCustomKey(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = runCatching {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    apkRepacker.importCustomKeystore(input)
                } != null
            }.getOrDefault(false)
            _hasCustomKey.value = ok && apkRepacker.customKeystoreFile.length() > 0
            onResult(ok)
        }
    }

    /**
     * 回打 APK 并写入 SAF 目标。
     *
     * @param uri CreateDocument 返回的 APK 输出 Uri
     * @param sign 是否签名
     * @param v1/v2/v3 签名方案开关
     * @param useCustomKey 使用自定义密钥（否则内置 debug 密钥）
     * @param alias/storePass/keyPass 自定义密钥参数
     */
    fun repackApkToUri(
        uri: Uri,
        sign: Boolean,
        v1: Boolean,
        v2: Boolean,
        v3: Boolean,
        useCustomKey: Boolean,
        alias: String,
        storePass: String,
        keyPass: String,
    ) {
        val info = _repackInfo.value
        if (!info.available) return
        val soFile = File(_uiState.value.filePath)
        if (!soFile.exists()) return

        _repackState.value = RepackState(running = true, stage = "准备回打")
        viewModelScope.launch {
            try {
                val keySource = if (useCustomKey) {
                    ApkRepacker.KeySource.Custom(
                        storeFile = apkRepacker.customKeystoreFile,
                        storePassword = storePass,
                        keyAlias = alias,
                        keyPassword = keyPass,
                    )
                } else {
                    ApkRepacker.KeySource.Debug
                }

                val output = apkRepacker.repackToTempFile(
                    apkFile = File(info.apkPath),
                    patchedSo = soFile,
                    signOptions = ApkRepacker.SignOptions(enabled = sign, v1 = v1, v2 = v2, v3 = v3),
                    keySource = keySource,
                ) { p, stage ->
                    _repackState.value = _repackState.value.copy(progress = p, stage = stage)
                }

                if (!output.result.ok) {
                    _repackState.value = RepackState(error = output.result.error)
                    return@launch
                }

                _repackState.value = _repackState.value.copy(
                    progress = 0.95f, stage = "写出 APK"
                )
                val size = appContext.contentResolver.openOutputStream(uri)?.use { os ->
                    apkRepacker.copyToStream(output.file, os)
                } ?: throw IllegalStateException("无法打开输出流")

                output.file.delete()
                val r = output.result
                val schemeText = if (r.signed) r.schemes.joinToString("+") else "未签名"
                _repackState.value = RepackState(
                    successMessage = "回打完成（${schemeText}，${size / 1024 / 1024}MB，${r.durationMs / 1000}s）",
                )
            } catch (e: Exception) {
                Log.e(TAG, "回打 APK 失败", e)
                _repackState.value = RepackState(error = "回打失败: ${e.message}")
            }
        }
    }

    /** 消费一次性回打结果消息。 */
    fun consumeRepackResult() {
        _repackState.value = RepackState()
    }

    // ==================================================================
    // 最近文件
    // ==================================================================

    private fun addToRecent(path: String) {
        val file = File(path)
        val list = _recentFiles.value.toMutableList()
        list.removeAll { it.path == path }
        list.add(0, RecentFile(path = path, name = file.name))
        // 注意：dropLast 是纯函数，必须取返回值，否则超过上限后列表无限增长
        val trimmed = if (list.size > MAX_RECENT_FILES) list.take(MAX_RECENT_FILES) else list
        _recentFiles.value = trimmed
        saveRecentFiles(trimmed)
    }

    /** 从「最近文件」列表中移除某一项（UI 删除按钮用）。 */
    fun removeRecent(path: String) {
        val updated = _recentFiles.value.filter { it.path != path }
        _recentFiles.value = updated
        saveRecentFiles(updated)
    }

    // 历史持久化读写（recentJson 声明在 init 之前，见文件前部）
    private fun loadRecentFiles(): List<RecentFile> = try {
        val raw = prefs.getString(KEY_RECENT_FILES, null) ?: return emptyList()
        recentJson.decodeFromString<List<RecentFile>>(raw)
    } catch (e: Throwable) {
        Log.w(TAG, "恢复最近文件失败", e)
        emptyList()
    }

    private fun saveRecentFiles(list: List<RecentFile>) {
        try {
            prefs.edit().putString(KEY_RECENT_FILES, recentJson.encodeToString(list)).apply()
        } catch (e: Throwable) {
            Log.w(TAG, "保存最近文件失败", e)
        }
    }
}

enum class EditorTab { STRUCTURE, HEX, DISASSEMBLY, EMULATION }

@Immutable
data class SoEditorUiState(
    val filePath: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val sections: List<SectionInfo> = emptyList(),
    val symbols: List<SymbolInfo> = emptyList(),
    val dynamicSymbols: List<SymbolInfo> = emptyList(),
    val functions: List<FunctionInfo> = emptyList(),
    val strings: List<StringInfo> = emptyList(),
    val fileInfo: FileInfo? = null,
    val isLoading: Boolean = false,
    val isFileOpen: Boolean = false,
    val isAnalyzing: Boolean = false,
    val errorMessage: String? = null
)

@Stable
data class HexDataState(
    val offset: Long = 0,
    val data: ByteArray = ByteArray(0),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HexDataState) return false
        return offset == other.offset && data.contentEquals(other.data)
    }
    override fun hashCode(): Int {
        var r = offset.hashCode()
        r = 31 * r + data.contentHashCode()
        return r
    }
}

@kotlinx.serialization.Serializable
data class RecentFile(val path: String, val name: String)

@Immutable
data class DisassemblyDataState(
    val baseAddress: Long = 0,
    val loadedSize: Long = 0,
    val highlightAddress: Long? = null,
    val instructions: List<DisasmInstruction> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/** 交叉引用面板状态。 */
@Immutable
data class XrefDataState(
    val address: Long = 0,
    val xrefsTo: List<Xref> = emptyList(),   // 谁调用/引用了我
    val xrefsFrom: List<Xref> = emptyList(), // 我调用/引用了谁
    val xrefFunctionNames: Map<Long, String> = emptyMap(), // xref 地址 → 函数名（含类名）
    val isLoading: Boolean = false
)
