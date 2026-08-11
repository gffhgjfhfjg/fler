package com.ai.fler.core.frida

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 应用启动时后台种子默认 Hook 脚本。
 *
 * 保证 `frida_scripts` / `frida_use_script` 无需先打开「Hook 脚本」页就有内置预设
 * 可用；幂等（ensureDefaults 按 name 去重），启动不阻塞。
 */
@Singleton
class HookScriptSeeder @Inject constructor(
    private val repository: HookScriptRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun seed() {
        scope.launch {
            runCatching { repository.ensureDefaults() }
                .onSuccess { inserted ->
                    if (inserted > 0) {
                        Log.d(TAG, "hook 默认预设种子完成，新增 $inserted 条")
                    }
                }
                .onFailure { e ->
                    Log.w(TAG, "hook 默认预设种子失败: ${e.message}")
                }
        }
    }

    companion object {
        private const val TAG = "FlerHookSeeder"
    }
}