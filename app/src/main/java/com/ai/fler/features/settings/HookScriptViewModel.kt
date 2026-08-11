package com.ai.fler.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.frida.HookScriptRepository
import com.ai.fler.data.entity.HookScript
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hook 脚本管理页 ViewModel：提供列表流 + 增删改查 + 恢复默认预设。
 */
@HiltViewModel
class HookScriptViewModel @Inject constructor(
    private val repository: HookScriptRepository,
) : ViewModel() {

    /** 全部脚本（Room Flow，UI 感知生命周期）。 */
    val scripts: StateFlow<List<HookScript>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 增删改查成功/错误反馈（snackbar 文案；null=无）。 */
    private val _feedback = MutableStateFlow<String?>(null)
    val feedback: StateFlow<String?> = _feedback.asStateFlow()

    /** 恢复默认预设后新增的条数。 */
    private val _restoredCount = MutableStateFlow<Int?>(null)
    val restoredCount: StateFlow<Int?> = _restoredCount.asStateFlow()

    init {
        seedDefaults()
    }

    /** 首次启动种子默认预设。幂等（已存在跳过）。 */
    fun seedDefaults() {
        viewModelScope.launch {
            repository.ensureDefaults()
        }
    }

    /** 新增自定义脚本；name/source 为空时返回 false 并提示。 */
    fun create(name: String, description: String, source: String) {
        viewModelScope.launch {
            when {
                name.isBlank() -> _feedback.value = "脚本名不能为空"
                source.isBlank() -> _feedback.value = "脚本源码不能为空"
                else -> {
                    repository.create(name.trim(), description.trim(), source)
                    _feedback.value = "已新增「$name」"
                }
            }
        }
    }

    /** 编辑脚本。 */
    fun update(id: Long, name: String, description: String, source: String) {
        viewModelScope.launch {
            when {
                name.isBlank() -> _feedback.value = "脚本名不能为空"
                source.isBlank() -> _feedback.value = "脚本源码不能为空"
                else -> {
                    val ok = repository.update(id, name.trim(), description.trim(), source)
                    _feedback.value = if (ok) "已保存修改" else "脚本不存在"
                }
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
            _feedback.value = "已删除"
        }
    }

    /** 一键复制脚本（预设副本自动转为自定义）。 */
    fun duplicate(id: Long) {
        viewModelScope.launch {
            val newId = repository.duplicate(id)
            _feedback.value = if (newId != null) "已复制为新脚本" else "脚本不存在"
        }
    }

    /** 恢复默认预设（同名跳过，补回被删除的预设）。 */
    fun restoreDefaults() {
        viewModelScope.launch {
            _restoredCount.value = repository.restoreDefaults()
            _feedback.value = "已补回默认预设"
        }
    }

    fun clearFeedback() {
        _feedback.value = null
        _restoredCount.value = null
    }
}