package com.ai.fler.ui.animation

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Easing

/**
 * fler 统一动画时长常量。
 *
 * 所有组件应优先使用这些时长，避免各处硬编码不同值导致视觉节奏不一致。
 */
object AnimDuration {
    /** 极快反馈（点击涟漪、微交互），~100ms */
    val micro get() = 100

    /** 快速过渡（悬浮展开、小元素变化），~200ms */
    val fast get() = 200

    /** 标准过渡（页面转场、列表动画），~300ms */
    val normal get() = 300

    /** 慢速过渡（强调动画、加载 shimmer），~500ms */
    val slow get() = 500

    /** 极慢过渡（闪烁脉冲、大范围展开），~800ms */
    val xslow get() = 800

    /** Shimmer 骨架屏单次扫描周期，~1200ms */
    val shimmer get() = 1200
}

/**
 * fler 统一缓动曲线常量。
 *
 * 规则：
 * - **进入动画**（enter / appear）→ [FastOutSlowInEasing]（先快后慢，自然）
 * - **退出动画**（exit / disappear）→ [FastOutLinearInEasing]（快速退出，干脆）
 * - **线性循环**（shimmer / 无限循环）→ [LinearEasing]
 * - **强调/展开**（expand / emphasize）→ [LinearOutSlowInEasing]（慢入强调）
 */
object AnimEasing {
    /** 进入动画（先快后慢） */
    val entry: Easing get() = FastOutSlowInEasing

    /** 退出动画（快速退出） */
    val exit: Easing get() = FastOutLinearInEasing

    /** 线性循环（shimmer 等） */
    val linear: Easing get() = LinearEasing

    /** 强调展开（慢入） */
    val emphasize: Easing get() = LinearOutSlowInEasing
}

/**
 * 根据 [durationMillis] 和 [easing] 创建 [tween] 动画规格。
 * 等价于 `tween(durationMillis = durationMillis, easing = easing)`。
 */
fun tweenSpec(
    durationMillis: Int = AnimDuration.normal,
    easing: Easing = AnimEasing.entry,
): FiniteAnimationSpec<Float> = tween(durationMillis = durationMillis, easing = easing)