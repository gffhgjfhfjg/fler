package com.ai.fler.features.so_editor

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.ElfParserBindings
import com.ai.fler.core.jni.KeystoneBindings
import com.ai.fler.core.service.BackupManager
import com.ai.fler.core.service.EngineLoader
import com.ai.fler.core.service.PatchExporter
import com.ai.fler.data.dao.AddressMappingDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * SO 编辑器 ViewModel。
 *
 * 负责加载 SO 文件、解析 ELF 结构、分段读取 Hex、反汇编与补丁应用。
 * 数据全部来自 [ElfParserBindings]（原生 ELF 解析器）与自研 ARM64 解码器。
 */
@HiltViewModel
class SoEditorViewModel @Inject constructor(
    private val application: Application,
    savedStateHandle: SavedStateHandle,
    private val addressMappingDao: AddressMappingDao,
    private val backupManager: BackupManager,
    private val patchExporter: PatchExporter,
    private val engineLoader: EngineLoader
) : ViewModel() {

    companion object {
        private const val TAG = "SoEditorViewModel"
        private const val HEX_PAGE_SIZE = 256L
        private const val DISASM_PAGE_SIZE = 4096L
        private const val PREF_NAME = "so_editor_recent"
        private const val KEY_RECENT = "recent_paths"
        private const val MAX_RECENT = 10
    }

    /** 最近打开的 SO 文件路径列表（按时间倒序，最新在前）。 */
    private val _recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())
    val recentFiles: StateFlow<List<RecentFile>> = _recentFiles.asStateFlow()

    init {
        loadRecentFiles()
    }

    private fun prefs() = application.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 读取 SharedPreferences 中的最近文件列表。 */
    private fun loadRecentFiles() {
        val raw = prefs().getString(KEY_RECENT, "") ?: ""
        _recentFiles.value = raw.split('\n')
            .filter { it.isNotBlank() }
            .map { line ->
                val parts = line.split('\t', limit = 2)
                RecentFile(path = parts[0], name = parts.getOrNull(1) ?: File(parts[0]).name)
            }
            .filter { File(it.path).exists() }
    }

    /**
     * 把 [path] 记入最近文件列表（去重 + 移到首位 + 限制 [MAX_RECENT] 条）。
     */
    private fun addToRecent(path: String) {
        val name = File(path).name
        val current = _recentFiles.value.toMutableList()
        current.removeAll { it.path == path }
        current.add(0, RecentFile(path = path, name = name))
        if (current.size > MAX_RECENT) current.subList(MAX_RECENT, current.size).clear()
        _recentFiles.value = current
        prefs().edit().putString(KEY_RECENT, current.joinToString("\n") { "${it.path}\t${it.name}" }).apply()
    }

    /**
     * 从最近文件列表移除指定路径。
     */
    fun removeRecent(path: String) {
        val current = _recentFiles.value.toMutableList()
        current.removeAll { it.path == path }
        _recentFiles.value = current
        prefs().edit().putString(KEY_RECENT, current.joinToString("\n") { "${it.path}\t${it.name}" }).apply()
    }

    private var currentFilePath: String = ""
    private var currentFileSize: Long = 0

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

    fun setTab(tab: EditorTab) {
        _currentTab.value = tab
    }

    fun setSelectedOffset(offset: Long) {
        _selectedOffset.value = offset
    }

    /**
     * 关闭当前文件，回到文件选择/最近列表（SO 编辑器返回按钮）。
     */
    fun closeFile() {
        currentFilePath = ""
        currentFileSize = 0
        _uiState.value = SoEditorUiState()
        _hexData.value = HexDataState()
        _disassemblyData.value = DisassemblyDataState()
        _currentTab.value = EditorTab.STRUCTURE
        _selectedOffset.value = 0L
    }

    // ========== 打开文件 ==========

    /**
     * 打开 SO 文件并解析 ELF 结构（节 / 符号 / 动态符号）。
     *
     * **suspend 函数**：调用方必须在协程中调用并等待完成，
     * 否则后续 [loadDisassembly] / [loadHexData] 会因 currentFileSize 还是 0
     * 而读取到空字节（表现为"该方法无可汇编字节"）。
     */
    suspend fun openFile(filePath: String) {
        currentFilePath = filePath
        _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)

        try {
            val parsed = withContext(Dispatchers.IO) {
                val file = File(filePath)
                if (!file.exists()) throw Exception("文件不存在: $filePath")
                val size = file.length()

                ElfParserBindings().use { parser ->
                    if (!parser.open(filePath)) {
                        throw Exception("ELF 解析失败（可能不是有效的 so 文件）")
                    }
                    ElfParseResult(
                        sections = parser.getSections(),
                        symbols = parser.getSymbols(),
                        dynamicSymbols = parser.getDynamicSymbols(),
                        fileSize = size
                    )
                }
            }
            currentFileSize = parsed.fileSize

            _uiState.value = SoEditorUiState(
                filePath = filePath,
                fileName = File(filePath).name,
                fileSize = parsed.fileSize,
                sections = parsed.sections,
                symbols = parsed.symbols,
                dynamicSymbols = parsed.dynamicSymbols,
                isLoading = false,
                isFileOpen = true
            )
            addToRecent(filePath)
            Log.i(TAG, "打开文件成功: $filePath, ${parsed.sections.size} 节, ${parsed.symbols.size} 符号, ${parsed.dynamicSymbols.size} 动态符号")
        } catch (e: Exception) {
            Log.e(TAG, "打开文件失败", e)
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = e.message
            )
        }
    }

    // ========== Hex 数据 ==========

    /**
     * 从文件真实读取指定偏移的字节（分段加载，避免大文件 OOM）。
     */
    fun loadHexData(offset: Long, size: Long = HEX_PAGE_SIZE) {
        viewModelScope.launch {
            _hexData.value = _hexData.value.copy(isLoading = true)
            // 同步 selectedOffset，让翻页按钮能基于当前页计算下一页
            _selectedOffset.value = offset
            try {
                val data = withContext(Dispatchers.IO) {
                    readFileBytes(offset, size)
                }
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

    /**
     * 写入单个字节到文件，并记录补丁（可撤销）。
     */
    fun writeByte(offset: Long, newValue: Byte) {
        viewModelScope.launch {
            try {
                val oldBytes = withContext(Dispatchers.IO) { readFileBytes(offset, 1) }
                if (oldBytes.isEmpty()) return@launch

                val ok = withContext(Dispatchers.IO) {
                    ElfParserBindings().use { parser ->
                        if (!parser.open(currentFilePath)) return@withContext false
                        parser.writeBytes(offset, byteArrayOf(newValue))
                    }
                }
                if (ok) {
                    backupManager.recordPatch(offset, oldBytes, byteArrayOf(newValue), currentFilePath.substringAfterLast('/'))
                    loadHexData(_hexData.value.offset, _hexData.value.data.size.toLong())
                }
            } catch (e: Exception) {
                Log.e(TAG, "写入字节失败", e)
            }
        }
    }

    /**
     * 应用多字节补丁（供指令编辑使用）。
     *
     * @param offset 文件偏移
     * @param newBytes 新字节
     * @return 是否成功
     */
    suspend fun applyPatch(offset: Long, newBytes: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val oldBytes = readFileBytes(offset, newBytes.size.toLong())
                if (oldBytes.isEmpty() || oldBytes.size != newBytes.size) return@withContext false

                // 首次编辑前创建 .bak 备份
                backupManager.createBackupIfNeeded(File(currentFilePath))

                val ok = ElfParserBindings().use { parser ->
                    if (!parser.open(currentFilePath)) return@withContext false
                    parser.writeBytes(offset, newBytes)
                }
                if (ok) {
                    backupManager.recordPatch(
                        address = offset,
                        oldBytes = oldBytes,
                        newBytes = newBytes,
                        soName = currentFilePath.substringAfterLast('/')
                    )
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "应用补丁失败", e)
                false
            }
        }
    }

    /**
     * 汇编指令补丁：把人类可读的汇编指令（如 "MOV W0, #1"）编码为机器码并写入文件。
     *
     * 编码走 [encodeInstruction]（Keystone 完整 AArch64 汇编）。
     *
     * @param offset 文件偏移（指令起始地址）
     * @param instruction 指令名（如 "MOV" / "RET" / "NOP"），大小写不敏感
     * @param args 操作数（如 "W0, #1"），无操作数时传空串
     * @return 编码并应用成功返回 true；编码失败/写盘失败返回 false
     */
    suspend fun applyInstructionPatch(offset: Long, instruction: String, args: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                // 1. 汇编：指令文本 -> 机器码字节
                val assembly = if (args.isBlank()) instruction.trim() else "${instruction.trim()} ${args.trim()}"
                val newBytes = encodeInstruction(assembly, offset)
                if (newBytes == null) {
                    Log.w(TAG, "汇编失败: $assembly（Keystone 无法编码）")
                    return@withContext false
                }

                // 2. 复用 applyPatch 完成备份 + 写盘 + 记录撤销
                applyPatch(offset, newBytes)
            } catch (e: Exception) {
                Log.e(TAG, "汇编指令补丁失败: $instruction $args", e)
                false
            }
        }
    }

    // ========== 反汇编 ==========

    /**
     * 编码一条 ARM64 指令：仅使用 Keystone（完整 AArch64 汇编器）。
     *
     * @param assembly 完整指令文本（如 "MOV W0, #1"）
     * @param address 指令所在地址（分支指令偏移量计算依赖它）
     * @return 机器码字节；Keystone 失败返回 null
     */
    private fun encodeInstruction(assembly: String, address: Long): ByteArray? {
        if (assembly.isBlank()) return null
        return try {
            KeystoneBindings.asm(assembly, address)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.w(TAG, "Keystone 汇编失败: $assembly", e)
            null
        }
    }

    /**
     * 编码一条 ARM64 指令（供指令编辑对话框实时校验预览）。
     *
     * @param assembly 完整指令文本（如 "MOV W0, #1"）
     * @param address 指令所在地址（分支指令偏移量计算依赖它）
     * @return 机器码字节；Keystone 失败返回 null
     */
    fun assembleInstruction(assembly: String, address: Long): ByteArray? =
        encodeInstruction(assembly, address)

    /**
     * 加载反汇编数据。
     *
     * 注意：自研解码器与 capstone 解码均已移除，SO 编辑器不再内置反汇编。
     * 汇编内容请通过 ASM 浏览（分析时生成的 src_code）查看；此处置空并提示。
     */
    /**
     * 从文件读取字节并反汇编（分段，每页 4096 字节）。
     *
     * 用引擎包已加载的 libcapstone.so（[CapstoneBindings.disassembleWithCapstone]）解码；
     * 引擎包不可用时置空并提示。
     * 保留 [DisassemblyDataState.highlightAddress]（若已设置），便于编辑/撤销后刷新仍高亮。
     */
    fun loadDisassembly(offset: Long, size: Long = DISASM_PAGE_SIZE) {
        viewModelScope.launch {
            val previousHighlight = _disassemblyData.value.highlightAddress
            _disassemblyData.value = _disassemblyData.value.copy(isLoading = true)
            try {
                var capstoneError: String? = null
                val result = withContext(Dispatchers.IO) {
                    val bytes = readFileBytes(offset, size)
                    if (bytes.isEmpty()) {
                        emptyList<com.ai.fler.core.jni.DisasmInstruction>()
                    } else {
                        CapstoneBindings.disassembleWithCapstone(capstonePath(), bytes, offset)?.also {
                            if (it.isEmpty()) capstoneError = "该区域无法用 Capstone 解码"
                        } ?: run {
                            capstoneError = "Capstone 反汇编不可用（请先下载引擎包）"
                            emptyList()
                        }
                    }
                }
                _disassemblyData.value = DisassemblyDataState(
                    baseAddress = offset,
                    loadedSize = size,
                    highlightAddress = previousHighlight,
                    instructions = result,
                    isLoading = false,
                    errorMessage = capstoneError
                )
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

    /** 引擎包内 libcapstone.so 的绝对路径。 */
    private fun capstonePath(): String =
        engineLoader.engineDirectory().resolve("lib/libcapstone.so").absolutePath

    /**
     * 设置/清除高亮地址（编辑或撤销后调用，让 UI 高亮被修改的指令行）。
     */
    fun setHighlightAddress(address: Long?) {
        _disassemblyData.value = _disassemblyData.value.copy(highlightAddress = address)
    }

    // ========== 符号 / CRC ==========

    /**
     * 根据符号名查找虚拟地址。
     */
    fun findSymbolAddress(name: String): Long {
        return try {
            ElfParserBindings().use { parser ->
                if (parser.open(currentFilePath)) parser.findSymbolAddress(name) else 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * 计算指定偏移范围的 CRC32。
     */
    fun computeCRC32(offset: Long, size: Long): Long {
        return try {
            ElfParserBindings().use { parser ->
                if (parser.open(currentFilePath)) parser.computeCRC32(offset, size) else 0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    // ========== 撤销 / 导出 ==========

    /**
     * 撤销上一次补丁：恢复文件字节并刷新视图。
     *
     * 刷新反汇编时用 [DisassemblyDataState.loadedSize] 保持原加载范围（方法模式不会变成整 SO），
     * 并把 [highlightAddress][DisassemblyDataState.highlightAddress] 设为撤销地址，高亮该行。
     */
    fun undo(): com.ai.fler.core.service.PatchRecord? {
        val record = backupManager.undo() ?: return null
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                ElfParserBindings().use { parser ->
                    if (parser.open(currentFilePath)) {
                        parser.writeBytes(record.address, record.oldBytes)
                    }
                }
            }
            loadHexData(_hexData.value.offset, _hexData.value.data.size.toLong())
            setHighlightAddress(record.address)
            loadDisassembly(
                _disassemblyData.value.baseAddress,
                _disassemblyData.value.loadedSize.takeIf { it > 0 } ?: DISASM_PAGE_SIZE
            )
        }
        return record
    }

    /**
     * 从 vmOffset 查找文件偏移。
     */
    suspend fun vmOffsetToFileOffset(vmOffset: Long): Long? {
        return try {
            val mapping = withContext(Dispatchers.IO) {
                addressMappingDao.findByVmOffset(vmOffset)
            }
            mapping?.fileOffset
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取撤销栈大小。
     */
    fun getUndoCount(): Int = backupManager.getUndoCount()

    /**
     * 导出补丁文件到 SAF 目录。
     */
    suspend fun exportPatches(directoryUri: Uri): Uri? {
        val records = backupManager.getPatchRecords()
        if (records.isEmpty()) return null
        return patchExporter.exportToSaf(
            directoryUri = directoryUri,
            soFileName = _uiState.value.fileName,
            records = records
        )
    }

    /**
     * 导出补丁到缓存（用于分享）。
     */
    suspend fun exportPatchesToCache(): File? {
        val records = backupManager.getPatchRecords()
        if (records.isEmpty()) return null
        return patchExporter.exportToCache(
            soFileName = _uiState.value.fileName,
            records = records
        )
    }

    /**
     * 通过 SAF 导出补丁到用户指定位置（如 Documents）。
     *
     * 调用方先用 [androidx.activity.result.contract.ActivityResultContracts.CreateDocument]
     * 让用户选目标文件，再调用本方法写入。
     *
     * @param uri SAF CreateDocument 返回的 Uri
     * @return 是否成功
     */
    suspend fun exportPatchesToUri(uri: Uri): Boolean {
        val records = backupManager.getPatchRecords()
        if (records.isEmpty()) return false
        return patchExporter.exportToUri(
            uri = uri,
            soFileName = _uiState.value.fileName,
            records = records
        )
    }

    // ========== 内部工具 ==========

    /**
     * 从文件读取字节（clamp 到文件大小）。
     */
    private fun readFileBytes(offset: Long, size: Long): ByteArray {
        if (currentFilePath.isBlank() || currentFileSize <= 0) return ByteArray(0)
        val start = offset.coerceAtLeast(0)
        if (start >= currentFileSize) return ByteArray(0)
        val len = size.coerceIn(1, currentFileSize - start).toInt()
        return ElfParserBindings().use { parser ->
            if (parser.open(currentFilePath)) parser.readBytes(start, len.toLong()) else ByteArray(0)
        }
    }

    override fun onCleared() {
        super.onCleared()
    }
}

/**
 * 编辑器 Tab 类型。
 */
enum class EditorTab {
    STRUCTURE,
    HEX,
    DISASSEMBLY
}

/**
 * SO 编辑器 UI 状态。
 */
data class SoEditorUiState(
    val filePath: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val sections: List<com.ai.fler.core.jni.ElfSection> = emptyList(),
    val symbols: List<com.ai.fler.core.jni.ElfSymbol> = emptyList(),
    val dynamicSymbols: List<com.ai.fler.core.jni.ElfSymbol> = emptyList(),
    val isLoading: Boolean = false,
    val isFileOpen: Boolean = false,
    val errorMessage: String? = null
)

/**
 * Hex 数据状态。
 */
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
        var result = offset.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * 反汇编数据状态。
 */
/**
 * 最近打开的文件条目。
 */
data class RecentFile(
    val path: String,
    val name: String
)

data class DisassemblyDataState(
    val baseAddress: Long = 0,
    /** 当前已加载的字节范围（方法模式下 = 方法长度；翻页模式下 = 4096）。刷新时复用，避免撤销后加载整 SO。 */
    val loadedSize: Long = 0,
    /** 最近被修改/撤销的指令地址，用于列表高亮；null 表示无高亮。 */
    val highlightAddress: Long? = null,
    val instructions: List<com.ai.fler.core.jni.DisasmInstruction> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

/** ELF 解析结果（打开文件时一次性读取）。 */
private data class ElfParseResult(
    val sections: List<com.ai.fler.core.jni.ElfSection>,
    val symbols: List<com.ai.fler.core.jni.ElfSymbol>,
    val dynamicSymbols: List<com.ai.fler.core.jni.ElfSymbol>,
    val fileSize: Long
)
