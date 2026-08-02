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
import com.ai.fler.core.analysis.StringInfo
import com.ai.fler.core.analysis.SymbolInfo
import com.ai.fler.core.analysis.Xref
import com.ai.fler.core.analysis.assembler.KeystoneAssembler
import com.ai.fler.core.service.BackupManager
import com.ai.fler.core.service.PatchExporter
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
    private val patchExporter: PatchExporter
) : ViewModel() {

    companion object {
        private const val TAG = "SoEditorViewModel"
        const val HEX_PAGE_SIZE = 4096L
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

    /** 当前选中指令的交叉引用（点击指令行时加载）。 */
    private val _xrefData = MutableStateFlow<XrefDataState>(XrefDataState())
    val xrefData: StateFlow<XrefDataState> = _xrefData.asStateFlow()

    /** 函数边界标注：当前反汇编页中，函数起始地址 → 函数名。 */
    private val _functionOverlay = MutableStateFlow<Map<Long, String>>(emptyMap())
    val functionOverlay: StateFlow<Map<Long, String>> = _functionOverlay.asStateFlow()

    /** 结构Tab各子Tab的滚动位置（index to offset），用于切到汇编Tab再回来时保持位置。 */
    private val _structureScrollStates = MutableStateFlow<Map<Int, Pair<Int, Int>>>(emptyMap())
    val structureScrollStates: StateFlow<Map<Int, Pair<Int, Int>>> = _structureScrollStates.asStateFlow()

    /** 结构Tab中上次点击的函数/符号地址（用于从汇编切回时高亮闪烁）。 */
    private val _structureFlashAddress = MutableStateFlow<Long?>(null)
    val structureFlashAddress: StateFlow<Long?> = _structureFlashAddress.asStateFlow()

    private val _recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())
    val recentFiles: StateFlow<List<RecentFile>> = _recentFiles.asStateFlow()

    fun setTab(tab: EditorTab) { _currentTab.value = tab }

    fun setSelectedOffset(offset: Long) { _selectedOffset.value = offset }

    fun closeFile() {
        savedSeq = -1L
        _flashOffset.value = null
        _uiState.value = SoEditorUiState()
        _hexData.value = HexDataState()
        _disassemblyData.value = DisassemblyDataState()
        _currentTab.value = EditorTab.STRUCTURE
        _selectedOffset.value = 0L
        _structureFlashAddress.value = null
        viewModelScope.launch {
            session.closeAll()
        }
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
            val sections = session.getSections()
            val symbolsAll = session.getSymbols(true)
            val fileInfo = session.getFileInfo()
            // Rizin aaa 识别的函数列表（SelfAnalysisEngine 返回空，不影响功能）
            val functions = try { session.listFunctions() } catch (_: Throwable) { emptyList() }
            // 兼容旧 SoEditorUiState：把 dynamicSymbols 从符号里按 bind=GLOBAL/WHILE 拆分
            val dynamicSymbols = symbolsAll.filter { it.bind != com.ai.fler.core.analysis.SymbolBind.LOCAL }
            val staticSymbols = symbolsAll.filter { it.bind == com.ai.fler.core.analysis.SymbolBind.LOCAL }
            val fileSize = fileInfo?.fileSize?.takeIf { it > 0 } ?: withContext(Dispatchers.IO) {
                File(filePath).length()
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
                isFileOpen = true
            )
            addToRecent(filePath)
            // 恢复该文件持久化的补丁高亮（上次修改过才有红色）
            restorePatchedOffsets()
            Log.i(TAG, "打开文件成功: $filePath, ${sections.size} 节, ${symbolsAll.size} 符号, engine=${result.engineId}")
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

    fun writeByte(offset: Long, newValue: Byte) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    session.writeBytes(offset, byteArrayOf(newValue), _uiState.value.fileName)
                }
                if (ok) {
                    refreshPatchedOffsets()
                    loadHexData(_hexData.value.offset, _hexData.value.data.size.toLong())
                }
            } catch (e: Exception) {
                Log.e(TAG, "写入字节失败", e)
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

    fun loadDisassembly(offset: Long, size: Long = DISASM_PAGE_SIZE) {
        viewModelScope.launch {
            val previousHighlight = _disassemblyData.value.highlightAddress
            _disassemblyData.value = _disassemblyData.value.copy(isLoading = true)
            try {
                var errorMsg: String? = null
                // 反汇编始终用 Capstone（质量最好），不走引擎选择
                // Rizin 仅负责函数识别/交叉引用/字符串扫描等高级分析
                val list = if (_uiState.value.isFileOpen) {
                    val bytes = session.readBytes(offset, size)
                    if (bytes.isEmpty()) emptyList()
                    else {
                        try {
                            com.ai.fler.core.jni.CapstoneBindings.disassembleWithCapstone(
                                bytes, offset
                            )?.map { DisasmInstruction.fromJni(it) }?.also {
                                if (it.isEmpty()) errorMsg = "该区域无法用 Capstone 解码"
                            } ?: run { errorMsg = "Capstone 反汇编不可用"; emptyList() }
                        } catch (_: Throwable) { errorMsg = "反汇编失败"; emptyList() }
                    }
                } else emptyList()
                _disassemblyData.value = DisassemblyDataState(
                    baseAddress = offset,
                    loadedSize = size,
                    highlightAddress = previousHighlight,
                    instructions = list,
                    isLoading = false,
                    errorMessage = errorMsg
                )
                // 更新函数边界标注（Rizin aaa 识别的函数起始地址 → 函数名）
                updateFunctionOverlay(list)
            } catch (e: Exception) {
                _disassemblyData.value = DisassemblyDataState(
                    baseAddress = offset,
                    loadedSize = size,
                    highlightAddress = previousHighlight,
                    isLoading = false,
                    errorMessage = e.message
                )
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
        viewModelScope.launch {
            val on = 250L; val off = 150L; val cycles = 3
            for (i in 0 until cycles) {
                delay(on)
                if (_flashOffset.value != address) return@launch
                _flashOffset.value = null
                _disassemblyData.value = _disassemblyData.value.copy(highlightAddress = null)
                if (i < cycles - 1) {
                    delay(off)
                    if (_flashOffset.value != address) return@launch
                    _flashOffset.value = address
                    _disassemblyData.value = _disassemblyData.value.copy(highlightAddress = address)
                }
            }
        }
    }

    /** 加载指定地址的交叉引用（点击指令行时调用）。 */
    fun loadXrefs(address: Long) {
        _xrefData.value = _xrefData.value.copy(address = address, isLoading = true)
        viewModelScope.launch {
            try {
                val to = session.xrefsTo(address)
                val from = session.xrefsFrom(address)
                _xrefData.value = XrefDataState(
                    address = address,
                    xrefsTo = to,
                    xrefsFrom = from,
                    isLoading = false
                )
            } catch (e: Exception) {
                Log.w(TAG, "加载交叉引用失败: 0x${address.toString(16)}", e)
                _xrefData.value = XrefDataState(address = address, isLoading = false)
            }
        }
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

    /** 从汇编切回结构Tab时，触发闪烁（闪3次后清空）。 */
    fun triggerStructureFlash() {
        val addr = _structureFlashAddress.value ?: return
        viewModelScope.launch {
            val on = 250L; val off = 150L; val cycles = 3
            for (i in 0 until cycles) {
                delay(on)
                if (_structureFlashAddress.value != addr) return@launch
                _structureFlashAddress.value = null
                if (i < cycles - 1) {
                    delay(off)
                    if (_structureFlashAddress.value == null) {
                        _structureFlashAddress.value = addr
                    }
                }
            }
        }
    }

    /** 根据当前反汇编页的指令列表，更新函数边界标注。 */
    private fun updateFunctionOverlay(instructions: List<DisasmInstruction>) {
        if (_uiState.value.functions.isEmpty()) {
            _functionOverlay.value = emptyMap()
            return
        }
        val funcSet = _uiState.value.functions.associateBy { it.vaddr }
        val overlay = mutableMapOf<Long, String>()
        for (inst in instructions) {
            val func = funcSet[inst.address]
            if (func != null) {
                overlay[inst.address] = func.name
            }
        }
        _functionOverlay.value = overlay
    }

    /** 从 BackupManager 恢复所有补丁偏移（返回后重新进入时红色高亮还在）。 */
    private fun restorePatchedOffsets() {
        val records = backupManager.getPatchRecords()
        _patchedOffsets.value = records
            .flatMap { r -> (0 until r.newBytes.size).map { r.address + it } }
            .toSet()
    }

    /** 应用补丁后增量更新高亮。 */
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

    fun undo(): com.ai.fler.core.service.PatchRecord? {
        val record = backupManager.undo() ?: return null
        refreshPatchedOffsets()
        if (_currentTab.value == EditorTab.STRUCTURE) setTab(EditorTab.DISASSEMBLY)
        viewModelScope.launch {
            // 撤回用 writeRawBytes，不记录新补丁（否则撤回 → 记录 → 死循环）
            session.writeRawBytes(record.address, record.oldBytes)
            loadHexData(_hexData.value.offset, _hexData.value.data.size.toLong())
            setHighlightAddress(record.address)
            loadDisassembly(
                _disassemblyData.value.baseAddress,
                _disassemblyData.value.loadedSize.takeIf { it > 0 } ?: DISASM_PAGE_SIZE
            )
        }
        return record
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
        if (list.size > MAX_RECENT_FILES) list.dropLast(list.size - MAX_RECENT_FILES)
        _recentFiles.value = list
    }

    /** 从「最近文件」列表中移除某一项（UI 删除按钮用）。 */
    fun removeRecent(path: String) {
        _recentFiles.value = _recentFiles.value.filter { it.path != path }
    }

    override fun onCleared() {
        super.onCleared()
        viewModelScope.launch { session.closeAll() }
    }
}

enum class EditorTab { STRUCTURE, HEX, DISASSEMBLY }

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
    val isLoading: Boolean = false
)
