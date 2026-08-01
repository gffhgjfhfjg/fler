package com.ai.fler.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 通用 shimmer 占位骨架。后续 P3/P4 列表加载时复用：
 *
 * ```
 * if (state.isLoading) ShimmerPlaceholder() else ...
 * ```
 *
 * 单行高度可调，多行可通过 `lines` 参数叠加。
 */
@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    height: Dp = 16.dp,
    lines: Int = 1,
    spacing: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    val base = MaterialColors.surfaceVariant
    Column(modifier = modifier.fillMaxWidth()) {
        repeat(lines) { i ->
            if (i > 0) Spacer(Modifier.height(spacing))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .clip(RoundedCornerShape(4.dp))
                    .background(base)
                    .alpha(alpha),
            )
        }
    }
}

/** 局部颜色助手，避免上层 [androidx.compose.material3.MaterialTheme] 依赖污染纯 Box 调用。 */
private object MaterialColors {
    val surfaceVariant: Color
        @Composable get() = androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant
}
