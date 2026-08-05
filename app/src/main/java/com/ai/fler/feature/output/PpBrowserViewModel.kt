package com.ai.fler.feature.output

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.entity.PpEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * PP 浏览器 ViewModel。
 *
 * Keyset 分页：按 vm_offset 递增顺序逐页加载，避免一次性查 10 万行。
 */
@HiltViewModel
class PpBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ppEntryDao: PpEntryDao
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 200
    }

    private val analysisId: Long = savedStateHandle["analysisId"] ?: 0L

    private val _filterType = MutableStateFlow(FilterType.ALL)
    val filterType: StateFlow<FilterType> = _filterType.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 累积加载的 PP 条目列表。 */
    private val _accumulatedEntries = MutableStateFlow<List<PpEntry>>(emptyList())
    val accumulatedEntries: StateFlow<List<PpEntry>> = _accumulatedEntries.asStateFlow()

    /** 是否还有更多数据可加载。 */
    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 是否正在加载下一页。 */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** 当前加载到的最后一个 vm_offset（keyset 游标）。 */
    private var lastVmOffset = -1L

    private val _uiState = MutableStateFlow(PpBrowserUiState())
    val uiState: StateFlow<PpBrowserUiState> = _uiState.asStateFlow()

    init {
        loadData()
        loadMore()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val totalCount = withContext(Dispatchers.IO) { ppEntryDao.countByAnalysisId(analysisId) }
                val leafCount = withContext(Dispatchers.IO) { ppEntryDao.countLeavesByAnalysisId(analysisId) }
                _uiState.value = PpBrowserUiState(
                    analysisId = analysisId,
                    totalCount = totalCount,
                    leafCount = leafCount,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = PpBrowserUiState(
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
    }

    /**
     * 加载下一页。
     * 由 UI 层在滚动触底时调用。
     */
    fun loadMore() {
        if (_isLoadingMore.value || !_hasMore.value) return
        _isLoadingMore.value = true
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) {
                    when (_filterType.value) {
                        FilterType.ALL -> ppEntryDao.getPpPage(analysisId, lastVmOffset, PAGE_SIZE)
                        FilterType.LEAVES -> ppEntryDao.getPpStringPage(analysisId, lastVmOffset, PAGE_SIZE)
                        FilterType.TOP_CALLERS -> {
                            // TOP_CALLERS 一次性加载（按 caller_count 排序，最多 100 条）
                            if (lastVmOffset < 0) {
                                ppEntryDao.getTopCallersByAnalysisIdList(analysisId, 100)
                            } else {
                                emptyList()
                            }
                        }
                    }
                }
                if (page.isNotEmpty()) {
                    _accumulatedEntries.value = _accumulatedEntries.value + page
                    lastVmOffset = page.last().vmOffset
                    _hasMore.value = page.size >= PAGE_SIZE
                } else {
                    _hasMore.value = false
                }
            } catch (_: Exception) {
                _hasMore.value = false
            } finally {
                _isLoadingMore.value = false
            }
        }
    }

    /** 重置分页并重新加载（切换筛选时调用）。 */
    private fun resetAndLoad() {
        _accumulatedEntries.value = emptyList()
        lastVmOffset = -1L
        _hasMore.value = true
        loadMore()
    }

    fun setFilter(filter: FilterType) {
        _filterType.value = filter
        resetAndLoad()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    /** 搜索 PP 条目（SQL 下推，仅限 String 类型）。 */
    suspend fun search(query: String, limit: Int = 200): List<PpEntry> {
        return withContext(Dispatchers.IO) { ppEntryDao.searchStrings(analysisId, query, limit) }
    }
}

enum class FilterType {
    ALL,
    LEAVES,
    TOP_CALLERS
}

data class PpBrowserUiState(
    val analysisId: Long = 0L,
    val totalCount: Int = 0,
    val leafCount: Int = 0,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)