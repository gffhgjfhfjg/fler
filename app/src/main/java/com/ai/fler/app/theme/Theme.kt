package com.ai.fler.app.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = FlerPrimary,
    onPrimary = FlerOnPrimary,
    primaryContainer = FlerPrimaryContainer,
    onPrimaryContainer = FlerOnPrimaryContainer,
    secondary = FlerSecondary,
    onSecondary = FlerOnSecondary,
    secondaryContainer = FlerSecondaryContainer,
    onSecondaryContainer = FlerOnSecondaryContainer,
    tertiary = FlerTertiary,
    onTertiary = FlerOnTertiary,
    tertiaryContainer = FlerTertiaryContainer,
    onTertiaryContainer = FlerOnTertiaryContainer,
    background = FlerBackground,
    onBackground = FlerOnBackground,
    surface = FlerSurface,
    onSurface = FlerOnSurface,
    surfaceVariant = FlerSurfaceVariant,
    onSurfaceVariant = FlerOnSurfaceVariant,
    outline = FlerOutline,
    error = FlerError,
    onError = FlerOnError,
    errorContainer = FlerErrorContainer,
    onErrorContainer = FlerOnErrorContainer,
)

private val DarkColors = darkColorScheme(
    primary = FlerPrimaryDark,
    onPrimary = FlerOnPrimaryDark,
    primaryContainer = FlerPrimaryContainerDark,
    onPrimaryContainer = FlerOnPrimaryContainerDark,
    secondary = FlerSecondaryDark,
    onSecondary = FlerOnSecondaryDark,
    secondaryContainer = FlerSecondaryContainerDark,
    onSecondaryContainer = FlerOnSecondaryContainerDark,
    tertiary = FlerTertiaryDark,
    onTertiary = FlerOnTertiaryDark,
    tertiaryContainer = FlerTertiaryContainerDark,
    onTertiaryContainer = FlerOnTertiaryContainerDark,
    background = FlerBackgroundDark,
    onBackground = FlerOnBackgroundDark,
    surface = FlerSurfaceDark,
    onSurface = FlerOnSurfaceDark,
    surfaceVariant = FlerSurfaceVariantDark,
    onSurfaceVariant = FlerOnSurfaceVariantDark,
    outline = FlerOutlineDark,
    error = FlerErrorDark,
    onError = FlerOnErrorDark,
    errorContainer = FlerErrorContainerDark,
    onErrorContainer = FlerOnErrorContainerDark,
)

/**
 * fler 统一形状系统。
 *
 * 所有组件统一使用这套圆角值，避免各处硬编码不同圆角导致视觉不一致。
 */
val FlerShapes = Shapes(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
)

/**
 * fler 主题入口。
 *
 * Android 12+ 默认启用 Material You 动态取色；老设备回退到 fler 自定义品牌蓝调色板。
 * 同时配置 system bar 与背景色匹配，避免状态栏突兀。
 */
@Composable
fun FlerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 沉浸式：不设纯色 statusBarColor（enableEdgeToEdge 已设透明），
            // 仅控制系统栏图标深浅对比（跟随明暗主题）。
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FlerTypography,
        shapes = FlerShapes,
        content = content,
    )
}
