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
import com.ai.fler.features.onboarding.OnboardingPreferences
import com.ai.fler.features.onboarding.OnboardingScreen
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一 Activity。
 *
 * 装载 Compose 主题与根导航图 ([AppNavGraph])，业务界面均通过导航在内部切换。
 * 首次启动时显示新手引导。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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
