package com.ai.fler.ui.components

import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import kotlinx.coroutines.delay

/**
 * 快速消失的 Snackbar 宿主。
 *
 * Material3 默认 Snackbar 驻留 4 秒（Short），对「已撤销」这类确认型提示太长，
 * 遮挡编辑区且打断操作节奏。此组件在 [durationMs] 后主动 dismiss，
 * 其余行为（排队、action）与标准 SnackbarHost 一致。
 */
@Composable
fun FastSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier,
    durationMs: Long = 1500L
) {
    val current = hostState.currentSnackbarData
    LaunchedEffect(current) {
        if (current != null) {
            delay(durationMs)
            current.dismiss()
        }
    }
    SnackbarHost(hostState = hostState, modifier = modifier) { data ->
        Snackbar(snackbarData = data)
    }
}
