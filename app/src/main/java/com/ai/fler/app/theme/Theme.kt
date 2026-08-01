package com.ai.fler.app.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = FlerTypography,
        content = content,
    )
}
