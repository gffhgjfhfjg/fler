package com.ai.fler.features.so_editor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.analysis.CallResult
import com.ai.fler.core.analysis.EmulationSession
import com.ai.fler.core.analysis.FunctionInfo
import com.ai.fler.core.analysis.SoEditorCache
import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.analysis.StopReason
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/**
 * 仿真 Tab UI 状态。
 */
data class EmulationUiState(
    val isSessionOpen: Boolean = false,
    val isOpening: Boolean = false,
    val isRunning: Boolean = false,
    val executedCount: Long = 0,
    val selectedFunctionName: String = "",
    val argInputs: List<String> = List(8) { "" },
    val lastCallResult: CallResult? = null,
    val registers: Map<String, Long> = emptyMap(),
    val breakpoints: List<Long> = emptyList(),
    val lastStopReason: StopReason? = null,
    val lastPc: Long? = null,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null
)

/**
 * 仿真 Tab ViewModel（M4）。
 *
 * 通过 [EmulationSession] 门面操作 Unicorn 引擎；函数列表取自
 * [SoEditorCache] 的分析元数据（Rizin aaa 结果），未分析时为空
 * （用户仍可手输函数名/地址调用，EmulationSession 有 ElfParser 兜底）。
 */
@HiltViewModel
class EmulationViewModel @Inject constructor(
    private val emulationSession: EmulationSession,
    private val soEditorCache: SoEditorCache,
    private val appLogger: AppLogger,
) : ViewModel() {

    companion object {
        private const val MAX_LOGS = 200
        private const val TAG = "FlerEmuVM"
    }

    private val _uiState = MutableStateFlow(EmulationUiState())
    val uiState: StateFlow<EmulationUiState> = _uiState.asStateFlow()

    /** 当前绑定的 so 路径（openSession 时记录）。 */
    private var currentFilePath: String = ""

    /** 引擎是否可用（UI 据此显示降级提示）。 */
    val engineAvailable: Boolean get() = emulationSession.isAvailable

    /** 函数候选列表（供下拉选择）。 */
    private val _functionOptions = MutableStateFlow<List<FunctionInfo>>(emptyList())
    val functionOptions: StateFlow<List<FunctionInfo>> = _functionOptions.asStateFlow()

    /** 会话未打开时排队的断点（跨 Tab 跳转场景，openSession 后补挂）。 */
    private val pendingBreakpoints = mutableListOf<Long>()

    // ==================================================================
    // 会话
    // ==================================================================

    /**
     * 打开（或复用）仿真会话。Tab 首次进入时由 UI 自动调用。
     */
    fun openSession(filePath: String) {
        if (filePath.isEmpty()) return
        if (currentFilePath == filePath && _uiState.value.isSessionOpen) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isOpening = true, errorMessage = null)
            val handle = emulationSession.open(filePath)
            if (handle == null) {
                _uiState.value = _uiState.value.copy(
                    isOpening = false,
                    errorMessage = if (!emulationSession.isAvailable)
                        "仿真引擎不可用（Unicorn 未编译进当前构建）"
                    else "仿真会话打开失败"
                )
                return@launch
            }
            currentFilePath = filePath
            _functionOptions.value = soEditorCache.getMetadata(filePath)?.functions ?: emptyList()
            _uiState.value = _uiState.value.copy(
                isSessionOpen = true,
                isOpening = false,
                errorMessage = null
            )
            // 函数表就绪后修正预填值：跨 Tab 跳转时请求可能在函数表加载前被
            // 消费（填了目标地址而非函数入口），这里统一修正为入口地址。
            val prefilled = _uiState.value.selectedFunctionName
            val prefilledAddr = prefilled.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
            if (prefilledAddr != null) {
                val entry = functionEntryAt(prefilledAddr)
                if (entry != null && entry != prefilledAddr) {
                    _uiState.value = _uiState.value.copy(
                        selectedFunctionName = "0x${entry.toString(16)}"
                    )
                    appendLog(
                        "函数入口修正：0x${java.lang.Long.toHexString(prefilledAddr)} " +
                            "→ 0x${java.lang.Long.toHexString(entry)}"
                    )
                }
            }
            appendLog("会话已打开：$filePath")
            refreshRegisters()
            refreshBreakpoints()
            // 补挂跨 Tab 跳转时排队的断点
            val pendings = synchronized(pendingBreakpoints) {
                val copy = pendingBreakpoints.toList()
                pendingBreakpoints.clear()
                copy
            }
            pendings.forEach { addr ->
                emulationSession.addBreakpoint(filePath, addr)
                appendLog("断点 +0x${java.lang.Long.toHexString(addr)}（排队补挂）")
            }
            if (pendings.isNotEmpty()) refreshBreakpoints()
        }
    }

    /** 关闭会话并重置状态。 */
    fun closeSession() {
        val path = currentFilePath
        if (path.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            emulationSession.close(path)
            appendLog("会话已关闭")
        }
        currentFilePath = ""
        _uiState.value = EmulationUiState()
        _functionOptions.value = emptyList()
    }

    // ==================================================================
    // 输入
    // ==================================================================

    fun selectFunction(name: String) {
        _uiState.value = _uiState.value.copy(selectedFunctionName = name)
    }

    /**
     * 应用跨 Tab 调试请求（结构页/汇编页长按菜单）：预填函数/地址，
     * 可选同时下断点（会话未打开时自动排队，openSession 后补挂）。
     *
     * 地址类目标：函数框预填断点所在函数入口（从入口执行，断点仍下
     * 在原目标指令上）；若不在任何函数内则原样预填。
     */
    fun applyDebugRequest(target: String, addBreakpoint: Boolean) {
        android.util.Log.i(TAG, "applyDebugRequest target=$target addBp=$addBreakpoint")
        val addr = target.trim().removePrefix("0x").removePrefix("0X").toLongOrNull(16)
        if (addr != null) {
            val entry = functionEntryAt(addr)
            val filled = entry?.let { "0x${it.toString(16)}" } ?: target
            android.util.Log.i(TAG, "applyDebugRequest -> selectFunction($filled)")
            selectFunction(filled)
            if (addBreakpoint) queueBreakpoint(addr)
        } else {
            selectFunction(target)
        }
    }

    /**
     * 地址所在函数入口 vaddr。匹配顺序：
     * 1. 精确入口（该地址本身就是函数入口）；
     * 2. size 区间含端点，取**入口最大（最接近目标地址）**的函数——
     *    大函数可能包裹多个小函数，取最近入口避免选到段起始函数（如 .text 0x47f8）；
     * 3. 退化：最近的前一个函数入口（且未越过下一函数入口）。
     */
    private fun functionEntryAt(vaddr: Long): Long? {
        val funcs = _functionOptions.value
        if (funcs.isEmpty()) return null
        val sorted = funcs.sortedBy { it.vaddr }
        sorted.firstOrNull { it.vaddr == vaddr }?.let { return it.vaddr }
        // 区间匹配：取包含 vaddr 且入口最大（最接近目标）的函数
        sorted.filter { it.size > 0 && vaddr >= it.vaddr && vaddr <= it.vaddr + it.size }
            .maxByOrNull { it.vaddr }?.let { return it.vaddr }
        // 退化匹配：最近前一个入口（且未越过下一函数入口）
        val idx = sorted.indexOfLast { it.vaddr < vaddr }
        if (idx >= 0) {
            val f = sorted[idx]
            val next = sorted.getOrNull(idx + 1)
            if (next == null || vaddr < next.vaddr) return f.vaddr
        }
        return null
    }

    fun setArgInput(index: Int, text: String) {
        if (index !in 0..7) return
        val args = _uiState.value.argInputs.toMutableList()
        args[index] = text
        _uiState.value = _uiState.value.copy(argInputs = args)
    }

    // ==================================================================
    // 执行
    // ==================================================================

    /** 调用选中函数（参数 x0-x7，hex/十进制均可）。 */
    fun callSelectedFunction() {
        val state = _uiState.value
        if (state.isRunning || currentFilePath.isEmpty()) return
        val name = state.selectedFunctionName.trim()
        if (name.isEmpty()) {
            _uiState.value = state.copy(errorMessage = "请先选择或输入函数名/地址")
            return
        }
        val args = state.argInputs
            .takeWhile { it.isNotBlank() }
            .map { parseNumber(it) }
        if (args.any { it == null }) {
            _uiState.value = state.copy(errorMessage = "参数格式错误（支持 0x1F / 31）")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isRunning = true, errorMessage = null)
            appendLog("call $name(${args.filterNotNull().joinToString { "0x" + java.lang.Long.toHexString(it) }})")
            val result = emulationSession.callFunction(
                currentFilePath, name, args.filterNotNull()
            )
            if (result == null) {
                _uiState.value = _uiState.value.copy(
                    isRunning = false,
                    errorMessage = "调用失败：函数未找到或会话无效"
                )
                return@launch
            }
            appendLog(
                "← x0=${result.returnValueUnsigned} (${result.stopReason}, " +
                    "${result.instructionCount} 条指令)"
            )
            _uiState.value = _uiState.value.copy(
                isRunning = false,
                lastCallResult = result,
                lastStopReason = result.stopReason,
                lastPc = result.pc,
                executedCount = result.instructionCount
            )
            refreshRegisters()
        }
    }

    /** 从当前 PC 运行。instrCount=0 表示不限（靠超时/断点/Stop 停止）。 */
    fun runFromPc(instrCount: Long = 100_000L, timeoutMs: Long = 30_000L) {
        if (_uiState.value.isRunning || currentFilePath.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            // 刚停在断点上时“继续”：先摘除当前 PC 处断点，跑完再恢复，
            // 否则 uc_emu_start 第一指令就会再次命中同一断点（永远无法继续）。
            val st = _uiState.value
            val bpAtPc = st.lastStopReason == StopReason.BREAKPOINT &&
                st.lastPc != null && st.breakpoints.contains(st.lastPc)
            if (bpAtPc) {
                emulationSession.removeBreakpoint(currentFilePath, st.lastPc!!)
                appendLog("越过断点继续（临时摘除 0x${java.lang.Long.toHexString(st.lastPc!!)}）")
            }
            _uiState.value = _uiState.value.copy(isRunning = true, errorMessage = null)
            appendLog("run (max ${if (instrCount == 0L) "∞" else instrCount} 条)")
            val result = emulationSession.run(currentFilePath, instrCount, timeoutMs)
            if (bpAtPc) {
                emulationSession.addBreakpoint(currentFilePath, st.lastPc!!)
                refreshBreakpoints()
            }
            if (result == null) {
                _uiState.value = _uiState.value.copy(isRunning = false, errorMessage = "会话无效")
                return@launch
            }
            appendLog("停止：${result.stoppedBy} @ pc=0x${java.lang.Long.toHexString(result.pc)}")
            _uiState.value = _uiState.value.copy(
                isRunning = false,
                lastStopReason = result.stoppedBy,
                lastPc = result.pc,
                executedCount = result.instructionCount
            )
            refreshRegisters()
            syncCallResult(result.stoppedBy, result.pc, result.instructionCount)
        }
    }

    /** 单步一条指令。 */
    fun stepOnce() {
        if (_uiState.value.isRunning || currentFilePath.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isRunning = true, errorMessage = null)
            val result = emulationSession.step(currentFilePath)
            if (result == null) {
                _uiState.value = _uiState.value.copy(isRunning = false, errorMessage = "会话无效")
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                isRunning = false,
                lastStopReason = result.stoppedBy,
                lastPc = result.pc
            )
            refreshRegisters()
            syncCallResult(result.stoppedBy, result.pc, result.instructionCount)
        }
    }

    /** 中断正在运行的会话（UI Stop 按钮）。 */
    fun requestStop() {
        if (currentFilePath.isEmpty()) return
        appendLog("请求停止…")
        emulationSession.requestStop(currentFilePath)
    }

    // ==================================================================
    // 断点
    // ==================================================================

    /** 添加断点（hex/十进制地址文本，地址为 vaddr）。 */
    fun addBreakpoint(addressText: String) {
        val addr = parseNumber(addressText)
        if (addr == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "断点地址格式错误")
            return
        }
        // 会话未开（或打开中）时排队，openSession 后自动补挂；
        // 已开会话但挂载失败（如 open 未完成）也转排队，避免静默丢失。
        if (currentFilePath.isEmpty()) {
            queueBreakpoint(addr)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = emulationSession.addBreakpoint(currentFilePath, addr)
            if (ok) {
                appendLog("断点 +0x${java.lang.Long.toHexString(addr)}")
                refreshBreakpoints()
            } else {
                appendLog("断点挂载失败，转排队 +0x${java.lang.Long.toHexString(addr)}")
                queueBreakpoint(addr)
            }
        }
    }

    /**
     * 直接地址添加断点（汇编页长按菜单用）。
     * 会话尚未打开（用户刚从其他 Tab 跳过来）时先排队，openSession 后自动补挂。
     */
    fun queueBreakpoint(address: Long) {
        if (currentFilePath.isEmpty()) {
            synchronized(pendingBreakpoints) { pendingBreakpoints.add(address) }
            appendLog("断点排队 +0x${java.lang.Long.toHexString(address)}（会话打开后生效）")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ok = emulationSession.addBreakpoint(currentFilePath, address)
            if (ok) {
                appendLog("断点 +0x${java.lang.Long.toHexString(address)}")
                refreshBreakpoints()
            } else {
                // 会话 entry 尚未就绪：入队，openSession 完成后统一补挂
                appendLog("断点挂载失败，入队待补挂 +0x${java.lang.Long.toHexString(address)}")
                synchronized(pendingBreakpoints) { pendingBreakpoints.add(address) }
            }
        }
    }

    fun removeBreakpoint(address: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            emulationSession.removeBreakpoint(currentFilePath, address)
            appendLog("断点 -0x${java.lang.Long.toHexString(address)}")
            refreshBreakpoints()
        }
    }

    private suspend fun refreshBreakpoints() {
        if (currentFilePath.isEmpty()) return
        val bps = emulationSession.listBreakpoints(currentFilePath)
        _uiState.value = _uiState.value.copy(breakpoints = bps)
    }

    // ==================================================================
    // 寄存器
    // ==================================================================

    private suspend fun refreshRegisters() {
        if (currentFilePath.isEmpty()) return
        val regs = emulationSession.readRegisters(currentFilePath)
        _uiState.value = _uiState.value.copy(registers = regs.values)
    }

    /**
     * 继续运行/单步结束后同步调用结果卡片：读取当前 x0，以最新停止原因、
     * PC、指令数刷新 lastCallResult——否则卡片残留断点时的中间值，与
     * 「停止：函数返回」等状态矛盾（函数完成时 x0 即最终返回值）。
     */
    private suspend fun syncCallResult(stopReason: StopReason, pc: Long, instrCount: Long) {
        val old = _uiState.value.lastCallResult ?: return
        if (currentFilePath.isEmpty()) return
        val x0 = emulationSession.readRegister(currentFilePath, "x0") ?: old.returnValue
        _uiState.value = _uiState.value.copy(
            lastCallResult = old.copy(
                returnValue = x0,
                returnValueUnsigned = "0x${java.lang.Long.toUnsignedString(x0, 16)}",
                stopReason = stopReason,
                instructionCount = instrCount,
                pc = pc
            )
        )
    }

    /** 手动改寄存器（hex/十进制文本）。 */
    fun setRegisterValue(name: String, text: String) {
        val value = parseNumber(text)
        if (value == null) {
            _uiState.value = _uiState.value.copy(errorMessage = "寄存器值格式错误")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            emulationSession.writeRegister(currentFilePath, name, value)
            appendLog("$name = 0x${java.lang.Long.toHexString(value)}")
            refreshRegisters()
        }
    }

    // ==================================================================

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    /** 清空日志（UI 日志区标题栏按钮）。 */
    fun clearLogs() {
        _uiState.value = _uiState.value.copy(logs = emptyList())
    }

    private fun appendLog(msg: String) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val logs = _uiState.value.logs.toMutableList()
        logs.add("[$ts] $msg")
        while (logs.size > MAX_LOGS) logs.removeAt(0)
        _uiState.value = _uiState.value.copy(logs = logs)
        appLogger.info("EmuVM", msg)
    }

    /** 0x 前缀 hex 或十进制；非法返回 null。 */
    private fun parseNumber(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return if (t.startsWith("0x", ignoreCase = true) || t.startsWith("-0x", ignoreCase = true)) {
            val neg = t.startsWith('-')
            val body = t.removePrefix("-").substring(2)
            body.toLongOrNull(16)?.let { if (neg) -it else it }
        } else {
            t.toLongOrNull(10) ?: t.toLongOrNull(16)
        }
    }
}
