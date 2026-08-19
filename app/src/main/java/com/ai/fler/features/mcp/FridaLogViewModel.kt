package com.ai.fler.features.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.frida.FridaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Frida 日志 ViewModel：观察 [FridaEngine.eventsVersion]，事件到达/清空时
 * 重新拉取最近事件（hook 命中 / 脚本 send() 输出）。
 */
@HiltViewModel
class FridaLogViewModel @Inject constructor(
    private val engine: FridaEngine,
) : ViewModel() {

    /** 最近 [MAX_DISPLAY] 条 Frida 事件（环形缓冲 5000 条内的尾部窗口）。 */
    val entries: StateFlow<List<FridaEngine.FridaEvent>> = engine.eventsVersion
        .map { engine.events(null, null, MAX_DISPLAY) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun clear() = engine.clearEvents()

    companion object {
        private const val MAX_DISPLAY = 500
    }
}
