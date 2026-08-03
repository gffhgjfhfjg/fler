package com.ai.fler.ui.animation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.unit.IntOffset

// ─── 页面级转场 ───────────────────────────────────────────

/** 进入：从右侧滑入 + 淡入（标准导航正向） */
val enterFromRight: EnterTransition =
    slideInHorizontally(
        animationSpec = tween<IntOffset>(AnimDuration.normal, easing = AnimEasing.entry),
        initialOffsetX = { it },
    ) + fadeIn(tweenSpec(AnimDuration.normal, AnimEasing.entry))

/** 退出：淡出（正向导航退出） */
val exitToLeft: ExitTransition =
    fadeOut(tweenSpec(AnimDuration.fast, AnimEasing.exit))

/** 回退进入：淡入（返回时） */
val popEnterFromLeft: EnterTransition =
    fadeIn(tweenSpec(AnimDuration.fast, AnimEasing.entry))

/** 回退退出：从右侧滑出 + 淡出（返回时） */
val popExitToRight: ExitTransition =
    slideOutHorizontally(
        animationSpec = tween<IntOffset>(AnimDuration.normal, easing = AnimEasing.exit),
        targetOffsetX = { it },
    ) + fadeOut(tweenSpec(AnimDuration.normal, AnimEasing.exit))

/** 进入：从下方滑入 + 淡入（内容切换，如 Tab 切换） */
val enterFromBottom: EnterTransition =
    slideInVertically(
        animationSpec = tween<IntOffset>(AnimDuration.slow, easing = AnimEasing.entry),
        initialOffsetY = { it / 4 },
    ) + fadeIn(tweenSpec(AnimDuration.slow, AnimEasing.entry))

/** 退出：朝上方滑出 + 淡出（内容切换） */
val exitToTop: ExitTransition =
    slideOutVertically(
        animationSpec = tween<IntOffset>(AnimDuration.slow, easing = AnimEasing.exit),
        targetOffsetY = { -it / 4 },
    ) + fadeOut(tweenSpec(AnimDuration.slow, AnimEasing.exit))

// ─── 列表项动画 ───────────────────────────────────────────

/** 展开可见性（垂直展开 + 淡入） */
val expandVisibility: EnterTransition =
    expandVertically(animationSpec = tween(AnimDuration.normal, easing = AnimEasing.entry)) +
        fadeIn(tweenSpec(AnimDuration.normal, AnimEasing.entry))

/** 收缩消失（垂直收缩 + 淡出） */
val shrinkVisibility: ExitTransition =
    shrinkVertically(animationSpec = tween(AnimDuration.normal, easing = AnimEasing.exit)) +
        fadeOut(tweenSpec(AnimDuration.normal, AnimEasing.exit))

// ─── 导航 AnimatedContent 辅助 ────────────────────────────

/**
 * 返回 Navigation Compose 的 [enterTransition] lambda。
 * 用法：`enterTransition = { navEnterFromRight() }`
 */
fun navEnterFromRight() = enterFromRight

/**
 * 返回 Navigation Compose 的 [exitTransition] lambda。
 * 用法：`exitTransition = { navExitToLeft() }`
 */
fun navExitToLeft() = exitToLeft

/**
 * 返回 Navigation Compose 的 [popEnterTransition] lambda。
 * 用法：`popEnterTransition = { navPopEnterFromLeft() }`
 */
fun navPopEnterFromLeft() = popEnterFromLeft

/**
 * 返回 Navigation Compose 的 [popExitTransition] lambda。
 * 用法：`popExitTransition = { navPopExitToRight() }`
 */
fun navPopExitToRight() = popExitToRight