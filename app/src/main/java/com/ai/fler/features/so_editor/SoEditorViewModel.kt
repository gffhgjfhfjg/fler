package com.ai.fler.features.so_editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.analysis.AnalysisSession
import com.ai.fler.core.analysis.AnalysisCapability
import com.ai.fler.core.analysis.DisasmInstruction
import com.ai.fler.core.analysis.FileInfo
import com.ai.fler.core.analysis.FunctionInfo
import com.ai.fler.core.analysis.SectionInfo
import com.ai.fler.core.analysis.SoEditorCache
import com.ai.fler.core.analysis.StringInfo
import com.ai.fler.core.analysis.SymbolInfo
import com.ai.fler.core.analysis.Xref
import com.ai.fler.core.analysis.assembler.KeystoneAssembler
import com.ai.fler.core.service.BackupManager
import com.ai.fler.core.service.PatchExporter
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.MethodWithClass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.net.Uri
import android.util.Log
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SoEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val session: AnalysisSession,
    private val backupManager: BackupManager,
    private val keystoneAssembler: KeystoneAssembler,
    private val patchExporter: PatchExporter,
    private val dartMethodDao: DartMethodDao,
    private val soEditorCache: SoEditorCache
) : ViewModel() {

    companion object {
        private const val TAG = "SoEditorViewModel"
        const val HEX_PAGE_SIZE = 2048L
        const val DISASM_PAGE_SIZE = 4096L
        const val MAX_RECENT_FILES = 10
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

    init {
        // 启动时过滤一次最近文件列表：缓存被清理后文件可能不存在，避免用户点击打开空文件路径
        viewModelScope.launch(Dispatchers.IO) {
            val snapshot = _recentFiles.value
            if (snapshot.isNotEmpty()) {
                val existing = snapshot.filter { java.io.File(it.path).exists() }
                if (existing.size != snapshot.size) {
                    _recentFiles.value = existing
                }
            }
        }
    }

    fun setTab(tab: EditorTab) { _currentTab.value = tab }

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
                functions = try { session.listFunctions() } catch (_: Throwable) { emptyList() }
                dynamicSymbols = symbolsAll.filter { it.bind != com.ai.fler.core.analysis.SymbolBind.LOCAL }
                staticSymbols = symbolsAll.filter { it.bind == com.ai.fler.core.analysis.SymbolBind.LOCAL }
                fileSize = fileInfo?.fileSize?.takeIf { it > 0 } ?: withContext(Dispatchers.IO) {
                    File(filePath).length()
                }
                soEditorCache.putMetadata(
                    filePath,
                    SoEditorCache.SoMetadata(sections, staticSymbols, dynamicSymbols, functions, fileInfo, fileSize)
                )
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
        } catch (e: Exception) {
            Log.e(TAG, "打开文件失败", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = e.message
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
            _disassemblyData.value = _disassemblyData.value.copy(isLoading = true)
            try {
                // 交叉引用跳转时，往前加载 512 字节上下文（ARM64 = 128 条指令），
                // 让用户能看到 call 目标前后的代码，而不是只从目标地址开始
                val contextBefore = if (highlightAfterLoad != null) 512L else 0L
                val loadOffset = (offset - contextBefore).coerceAtLeast(0L)
                // 对齐到 4 字节边界（ARM64 指令宽度）
                val alignedOffset = loadOffset - (loadOffset % 4)
                val loadSize = size + (offset - alignedOffset)

                var errorMsg: String? = null
                val list = if (_uiState.value.isFileOpen) {
                    val bytes = session.readBytes(alignedOffset, loadSize)
                    if (bytes.isEmpty()) emptyList()
                    else {
                        try {
                            com.ai.fler.core.jni.CapstoneBindings.disassembleWithCapstone(
                                bytes, alignedOffset
                            )?.map { DisasmInstruction.fromJni(it) }?.also {
                                if (it.isEmpty()) errorMsg = "该区域无法用 Capstone 解码"
                            } ?: run { errorMsg = "Capstone 反汇编不可用"; emptyList() }
                        } catch (_: Throwable) { errorMsg = "反汇编失败"; emptyList() }
                    }
                } else emptyList()
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
            } catch (e: Exception) {
                _disassemblyData.value = DisassemblyDataState(
                    baseAddress = offset,
                    loadedSize = size,
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
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
                    try {
                        com.ai.fler.core.jni.CapstoneBindings.disassembleWithCapstone(
                            bytes, alignedOffset
                        )?.map { DisasmInstruction.fromJni(it) } ?: emptyList()
                    } catch (_: Throwable) { emptyList() }
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

    /** 加载指定地址的交叉引用（点击指令行时调用）。 */
    fun loadXrefs(address: Long) {
        _xrefData.value = _xrefData.value.copy(address = address, isLoading = true)
        viewModelScope.launch {
            try {
                val to = session.xrefsTo(address)
                val from = session.xrefsFrom(address)
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
     * 为一批 xref 地址计算函数名。
     * 优先 [dartFunctionLabels]（ClassName.methodName），
     * 再查 [uiState].functions 的包含关系（vaddr ≤ addr < vaddr+size）。
     * 若两者都不命中，从函数列表找最近的函数起始地址。
     */
    private fun buildXrefFunctionNames(addresses: Set<Long>): Map<Long, String> {
        if (addresses.isEmpty()) return emptyMap()
        val dartLabels = _dartFunctionLabels.value
        val functions = _uiState.value.functions
        if (functions.isEmpty()) return emptyMap()

        // 按 vaddr 排序 + 二分定位，避免对每个地址线性扫描全部函数（函数数以万计时
        // 原实现最坏 O(N) / 地址，xref 面板一次可能数百地址）。
        val sorted = functions.sortedBy { it.vaddr }
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
        return result
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

    /** 根据当前反汇编页的指令列表，更新函数边界标注。 */
    private fun updateFunctionOverlay(instructions: List<DisasmInstruction>) {
        val funcSet = _uiState.value.functions.associateBy { it.vaddr }
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
        _functionOverlay.value = overlay
    }

    /** 加载 Blutter 分析的 Dart 方法标签到 SO 偏移的映射。 */
    private suspend fun loadDartFunctionLabels(soPath: String) {
        try {
            // 1) 先看跨 ViewModel 的 DartLabels 缓存（命中则跳过 DAO 查询 + 标签构建）
            val cachedLabels = soEditorCache.getDartLabels(soPath)
            if (cachedLabels != null) {
                _dartFunctionLabels.value = cachedLabels.labels
                val existingAddrs = _uiState.value.functions.map { it.vaddr }.toSet()
                val merged = _uiState.value.functions + cachedLabels.dartFunctions.filter { it.vaddr !in existingAddrs }
                _uiState.value = _uiState.value.copy(functions = merged)
                // 即使命中 DartLabels 缓存，仍要检查 Rizin 注入状态（可能上次注入失败）
                if (soEditorCache.isInjected(soPath)) {
                    Log.i(TAG, "Dart 方法标签 + Rizin 注入均已缓存: ${cachedLabels.labels.size} 条")
                    // 始终调用 reanalyzeXrefs()，确保 xref 表存在
                    // 即使 Rizin 会话被重建，也能重新建立 xref 表
                    val rebuilt = session.reanalyzeXrefs()
                    Log.i(TAG, "xref 补充扫描: $rebuilt")
                } else {
                    val pairs = cachedLabels.labels.map { (addr, name) -> addr to name }
                    // 注入前诊断（可选，用于排查地址空间问题）
                    if (pairs.isNotEmpty()) {
                        session.checkAddressSpace(pairs.first().first)
                    }
                    // 注入全部 Dart 函数作为 flag（只设名不调 af，不破坏 xref 表）
                    val injected = session.defineFunctions(pairs)
                    soEditorCache.markInjected(soPath)
                    // 补充扫描 xref（aar 不依赖函数边界，不影响已有 xref）
                    val rebuilt = session.reanalyzeXrefs()
                    Log.i(TAG, "Dart 标签缓存命中但 Rizin 未注入: 注入 $injected 个, xref 补充扫描 $rebuilt")
                }
                return
            }

            // 2) 缓存未命中：查 DAO → 构建标签 → 缓存 → 注入 Rizin
            val methods = dartMethodDao.getMethodsBySoPath(soPath)
            if (methods.isEmpty()) return
            val labels = mutableMapOf<Long, String>()
            val dartFunctions = mutableListOf<FunctionInfo>()
            for (m in methods) {
                val addr = m.method.functionOffset ?: continue
                if (addr <= 0) continue
                val name = "${m._className}.${m.method.methodName}"
                labels[addr] = name
                dartFunctions.add(
                    FunctionInfo(
                        name = name,
                        offset = addr,
                        vaddr = addr,
                        size = m.method.functionSize ?: 0,
                    )
                )
            }
            // 写入 DartLabels 缓存（跨 ViewModel 复用，避免下次再查 DAO）
            soEditorCache.putDartLabels(soPath, SoEditorCache.DartLabels(labels, dartFunctions))

            _dartFunctionLabels.value = labels
            // 合并到 uiState.functions（去重：Blutter 优先于 Rizin）
            val existingAddrs = _uiState.value.functions.map { it.vaddr }.toSet()
            val merged = _uiState.value.functions + dartFunctions.filter { it.vaddr !in existingAddrs }
            _uiState.value = _uiState.value.copy(functions = merged)
            // 仅首次打开该 SO 时注入 Rizin（flag 不可重复设置）
            if (!soEditorCache.isInjected(soPath)) {
                val pairs = labels.map { (addr, name) -> addr to name }
                // 注入前诊断（可选，用于排查地址空间问题）
                if (pairs.isNotEmpty()) {
                    session.checkAddressSpace(pairs.first().first)
                }
                // 注入全部 Dart 函数作为 flag（只设名不调 af，不破坏 xref 表）
                val injected = session.defineFunctions(pairs)
                soEditorCache.markInjected(soPath)
                // 补充扫描 xref（aar 不依赖函数边界，不影响已有 xref）
                val rebuilt = session.reanalyzeXrefs()
                Log.i(TAG, "加载 Dart 方法标签: ${labels.size} 条 (Blutter), 函数合并后 ${merged.size} 条, Rizin 注入 $injected 个, xref 补充扫描 $rebuilt")
            } else {
                Log.i(TAG, "Rizin 注入已缓存: ${labels.size} 条 (跳过 Rizin 注入)")
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

    // ==================================================================
    // 最近文件
    // ==================================================================

    private fun addToRecent(path: String) {
        val file = File(path)
        val list = _recentFiles.value.toMutableList()
        list.removeAll { it.path == path }
        list.add(0, RecentFile(path = path, name = file.name))
        // 注意：dropLast 是纯函数，必须取返回值，否则超过上限后列表无限增长
        if (list.size > MAX_RECENT_FILES) {
            _recentFiles.value = list.take(MAX_RECENT_FILES)
        } else {
            _recentFiles.value = list
        }
    }

    /** 从「最近文件」列表中移除某一项（UI 删除按钮用）。 */
    fun removeRecent(path: String) {
        _recentFiles.value = _recentFiles.value.filter { it.path != path }
    }
}

enum class EditorTab { STRUCTURE, HEX, DISASSEMBLY, EMULATION }

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

data class RecentFile(val path: String, val name: String)

data class DisassemblyDataState(
    val baseAddress: Long = 0,
    val loadedSize: Long = 0,
    val highlightAddress: Long? = null,
    val instructions: List<DisasmInstruction> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/** 交叉引用面板状态。 */
data class XrefDataState(
    val address: Long = 0,
    val xrefsTo: List<Xref> = emptyList(),   // 谁调用/引用了我
    val xrefsFrom: List<Xref> = emptyList(), // 我调用/引用了谁
    val xrefFunctionNames: Map<Long, String> = emptyMap(), // xref 地址 → 函数名（含类名）
    val isLoading: Boolean = false
)
