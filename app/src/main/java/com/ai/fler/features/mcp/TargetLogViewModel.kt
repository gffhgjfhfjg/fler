package com.ai.fler.features.mcp

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.frida.TargetLogCollector
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * 目标应用日志 ViewModel：观察 [TargetLogCollector.version]，新日志到达时
 * 重新拉取快照；额外提供级别过滤与关键字搜索。
 */
@HiltViewModel
class TargetLogViewModel @Inject constructor(
    private val collector: TargetLogCollector,
) : ViewModel() {

    /** 最近 [MAX_DISPLAY] 条目标应用 logcat。 */
    val entries: StateFlow<List<TargetLogCollector.TargetLogEntry>> = collector.version
        .map { collector.snapshot(MAX_DISPLAY) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 当前采集的 pid（0 = 未采集，attach/spawn 后自动开始）。 */
    val activePid: StateFlow<Long> = collector.activePid

    /** 级别过滤：ALL / V / D / I / W / E */
    private val _filter = MutableStateFlow("ALL")
    val filter: StateFlow<String> = _filter.asStateFlow()

    /** 关键字搜索：tag / message 子串匹配（忽略大小写） */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setFilter(value: String) { _filter.value = value }
    fun setQuery(value: String) { _query.value = value }
    fun clear() = collector.clear()

    companion object {
        private const val MAX_DISPLAY = 2000
    }
}
