package com.ai.fler.feature.output

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.MethodLight
import com.ai.fler.data.dao.MethodWithClass
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ASM 方法列表 ViewModel。
 *
 * Keyset 分页：按 (class_name, method_name) 递增顺序逐页加载，
 * 避免一次性查 55781 行且拉取 src_code 大文本。
 */
@HiltViewModel
class AsmListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dartMethodDao: DartMethodDao
) : ViewModel() {

    companion object {
        private const val PAGE_SIZE = 200
    }

    private val analysisId: Long = savedStateHandle["analysisId"] ?: 0L

    private val _uiState = MutableStateFlow(AsmListUiState())
    val uiState: StateFlow<AsmListUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** 累积加载的方法列表（轻量投影，不含 src_code）。 */
    private val _accumulatedMethods = MutableStateFlow<List<MethodLight>>(emptyList())
    val accumulatedMethods: StateFlow<List<MethodLight>> = _accumulatedMethods.asStateFlow()

    /** 是否还有更多数据可加载。 */
    private val _hasMore = MutableStateFlow(true)
    val hasMore: StateFlow<Boolean> = _hasMore.asStateFlow()

    /** 是否正在加载下一页。 */
    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore.asStateFlow()

    /** Keyset 游标：当前加载到的最后一个 (class_name, method_name)。 */
    private var lastClassName = ""
    private var lastMethodName = ""

    init {
        loadData()
        loadMore()
    }

    private fun loadData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                _uiState.value = AsmListUiState(
                    analysisId = analysisId,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = AsmListUiState(
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
                    if (_searchQuery.value.isBlank()) {
                        dartMethodDao.getMethodPage(analysisId, lastClassName, lastMethodName, PAGE_SIZE)
                    } else {
                        // 搜索模式：使用 SQL 下推，限制 200 条
                        dartMethodDao.searchMethodsWithClass(
                            analysisId = analysisId,
                            name = _searchQuery.value.trim(),
                            classId = null,
                            limit = 200,
                            offset = 0
                        ).map { it.toMethodLight() }
                    }
                }
                if (page.isNotEmpty()) {
                    _accumulatedMethods.value = _accumulatedMethods.value + page
                    lastClassName = page.last()._className
                    lastMethodName = page.last().methodName
                    // 搜索模式一次性加载完，不再翻页
                    if (_searchQuery.value.isNotBlank()) {
                        _hasMore.value = false
                    } else {
                        _hasMore.value = page.size >= PAGE_SIZE
                    }
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

    /** 重置分页并重新加载（切换搜索时调用）。 */
    fun resetAndLoad() {
        _accumulatedMethods.value = emptyList()
        lastClassName = ""
        lastMethodName = ""
        _hasMore.value = true
        loadMore()
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        resetAndLoad()
    }

    fun refresh() {
        resetAndLoad()
    }

    /** 将 MethodWithClass 转为 MethodLight（搜索模式使用）。 */
    private fun MethodWithClass.toMethodLight() = MethodLight(
        id = method.id,
        classId = method.classId,
        methodName = method.methodName,
        selector = method.selector,
        functionOffset = method.functionOffset,
        functionSize = method.functionSize,
        _className = _className
    )

    /** 过滤后的方法列表（用于全量内存过滤，当前已改为分页，此方法不再使用）。 */
    fun getFilteredMethods(): List<MethodLight> {
        val query = _searchQuery.value.trim()
        if (query.isBlank()) return _accumulatedMethods.value
        return _accumulatedMethods.value.filter {
            it.methodName.contains(query, ignoreCase = true) ||
                it._className.contains(query, ignoreCase = true)
        }
    }
}

data class AsmListUiState(
    val analysisId: Long = 0L,
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)
