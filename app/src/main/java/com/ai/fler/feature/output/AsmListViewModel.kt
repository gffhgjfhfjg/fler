package com.ai.fler.feature.output

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.data.dao.DartMethodDao
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
 * 按 类名 / 方法名 列出某次分析的所有 Dart 方法，
 * 点击直接跳转 SO 编辑器（定位到方法文件偏移，进入方法编辑模式）。
 */
@HiltViewModel
class AsmListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dartMethodDao: DartMethodDao
) : ViewModel() {

    private val analysisId: Long = savedStateHandle["analysisId"] ?: 0L

    private val _uiState = MutableStateFlow(AsmListUiState())
    val uiState: StateFlow<AsmListUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        loadMethods()
    }

    private fun loadMethods() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val methods = withContext(Dispatchers.IO) {
                    dartMethodDao.getMethodsWithClass(analysisId)
                }
                _uiState.value = AsmListUiState(
                    analysisId = analysisId,
                    methods = methods,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = AsmListUiState(
                    analysisId = analysisId,
                    isLoading = false,
                    errorMessage = e.message
                )
            }
        }
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun refresh() {
        loadMethods()
    }

    /** 过滤后的方法列表（按类名/方法名匹配）。 */
    fun getFilteredMethods(): List<MethodWithClass> {
        val query = _searchQuery.value.trim()
        if (query.isBlank()) return _uiState.value.methods
        return _uiState.value.methods.filter {
            it.method.methodName.contains(query, ignoreCase = true) ||
                it._className.contains(query, ignoreCase = true)
        }
    }
}

data class AsmListUiState(
    val analysisId: Long = 0L,
    val methods: List<MethodWithClass> = emptyList(),
    val isLoading: Boolean = true,
    val errorMessage: String? = null
)
