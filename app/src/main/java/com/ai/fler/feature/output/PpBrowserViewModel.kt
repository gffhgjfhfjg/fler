package com.ai.fler.feature.output

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.entity.PpEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PP 浏览器 ViewModel。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PpBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val ppEntryDao: PpEntryDao,
    private val dartMethodDao: DartMethodDao
) : ViewModel() {

    private val analysisId: Long = savedStateHandle["analysisId"] ?: 0L

    private val _filterType = MutableStateFlow(FilterType.ALL)
    val filterType: StateFlow<FilterType> = _filterType.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val ppEntries: StateFlow<List<PpEntry>> = _filterType
        .flatMapLatest { filter ->
            when (filter) {
                FilterType.ALL -> ppEntryDao.getByAnalysisId(analysisId)
                FilterType.LEAVES -> ppEntryDao.getStringsByAnalysisId(analysisId)
                FilterType.TOP_CALLERS -> ppEntryDao.getTopCallersByAnalysisId(analysisId, limit = 100)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(PpBrowserUiState())
    val uiState: StateFlow<PpBrowserUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            try {
                val totalCount = ppEntryDao.countByAnalysisId(analysisId)
                val leafCount = ppEntryDao.countLeavesByAnalysisId(analysisId)
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

    fun setFilter(filter: FilterType) {
        _filterType.value = filter
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    suspend fun getMethodName(methodId: Long): String {
        return try {
            dartMethodDao.getById(methodId)?.let { method ->
                method.methodName
            } ?: "未知方法"
        } catch (e: Exception) {
            "未知方法"
        }
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
