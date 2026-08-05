package com.ai.fler.features.mcp

import androidx.lifecycle.ViewModel
import com.ai.fler.core.log.AppLogEntry
import com.ai.fler.core.log.AppLogger
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * 应用日志 ViewModel：观察 [AppLogger] 收集的内存日志，不再读取 logcat。
 *
 * 各模块通过 Hilt 注入 [AppLogger] 后调用 [AppLogger.info]/[debug]/[warn]/[error]
 * 写入日志，此处仅负责过滤与展示。
 */
@HiltViewModel
class AppLogViewModel @Inject constructor(
    private val appLogger: AppLogger,
) : ViewModel() {

    /** 日志条目委托给 AppLogger（有界内存，最近 3000 条）。 */
    val entries: StateFlow<List<AppLogEntry>> = appLogger.entries

    /** 级别过滤：ALL / D / I / W / E */
    private val _filter = MutableStateFlow("ALL")
    val filter: StateFlow<String> = _filter.asStateFlow()

    /** 关键字搜索：tag / message 子串匹配（忽略大小写） */
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setFilter(value: String) { _filter.value = value }
    fun setQuery(value: String) { _query.value = value }
    fun clear() { appLogger.clear() }
}