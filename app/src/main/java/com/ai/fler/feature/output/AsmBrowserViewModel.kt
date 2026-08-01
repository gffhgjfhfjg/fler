package com.ai.fler.feature.output

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.data.dao.DartMethodDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * ASM 浏览器 ViewModel。
 *
 * 从 Blutter 分析导入的 dart_methods.src_code（反汇编伪代码）加载内容，
 * 替代早期基于 asm 文件的实现（新引擎直接写 SQLite，不再生成 asm 文件）。
 */
@HiltViewModel
class AsmBrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val dartMethodDao: DartMethodDao
) : ViewModel() {

    private val analysisId: Long = savedStateHandle["analysisId"] ?: 0L
    private val methodId: Long = savedStateHandle["methodId"] ?: 0L

    private val _uiState = MutableStateFlow(AsmBrowserUiState())
    val uiState: StateFlow<AsmBrowserUiState> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    init {
        loadAsmContent()
    }

    private fun loadAsmContent() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val result = withContext(Dispatchers.IO) {
                    dartMethodDao.getById(methodId)?.let { method ->
                        val src = method.srcCode ?: ""
                        val lines = src.split("\n").filter { it.isNotEmpty() }
                        Triple(
                            method.methodName,
                            lines,
                            lines.size
                        )
                    }
                }

                if (result != null) {
                    val (name, lines, lineCount) = result
                    _uiState.value = AsmBrowserUiState(
                        analysisId = analysisId,
                        methodId = methodId,
                        fileName = name,
                        content = lines.joinToString("\n"),
                        lines = lines,
                        lineCount = lineCount,
                        isLoading = false
                    )
                } else {
                    _uiState.value = AsmBrowserUiState(
                        analysisId = analysisId,
                        methodId = methodId,
                        isLoading = false,
                        errorMessage = "未找到该方法的反汇编内容"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = AsmBrowserUiState(
                    analysisId = analysisId,
                    methodId = methodId,
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
        loadAsmContent()
    }
}

data class AsmBrowserUiState(
    val analysisId: Long = 0L,
    val methodId: Long = 0L,
    val fileName: String = "",
    val content: String = "",
    val lines: List<String> = emptyList(),
    val lineCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)
