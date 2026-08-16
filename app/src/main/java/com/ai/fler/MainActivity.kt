package com.ai.fler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.ai.fler.app.navigation.AppNavGraph
import com.ai.fler.app.theme.FlerTheme
import com.ai.fler.core.mcp.McpConfig
import com.ai.fler.core.service.OverlayKeepAliveService
import com.ai.fler.features.mcp.McpServerService
import com.ai.fler.features.onboarding.OnboardingPreferences
import com.ai.fler.features.onboarding.OnboardingScreen
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * 唯一 Activity。
 *
 * 装载 Compose 主题与根导航图 ([AppNavGraph])，业务界面均通过导航在内部切换。
 * 首次启动时显示新手引导。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var config: McpConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 启动时自动拉起 MCP 服务器（默认启用 + 前台服务 START_STICKY 保活），
        // 方便 AI 代理随时连接，无需手动到设置里开关。
        if (config.enabled.value) {
            McpServerService.start(this)
        }
        // 自动恢复悬浮窗保活：用户已开启过且拥有悬浮窗权限时，任何入口进入应用都拉起悬浮球
        if (OverlayKeepAliveService.isOverlayEnabled(this) &&
            android.provider.Settings.canDrawOverlays(this) &&
            !OverlayKeepAliveService.isRunning()
        ) {
            OverlayKeepAliveService.start(this)
        }
        setContent {
            FlerTheme {
                var showOnboarding by remember {
                    mutableStateOf(!OnboardingPreferences.isCompleted(this))
                }

                if (showOnboarding) {
                    OnboardingScreen(
                        onFinish = {
                            OnboardingPreferences.setCompleted(this)
                            showOnboarding = false
                        }
                    )
                } else {
                    AppNavGraph()
                }
            }
        }
    }
}
