package com.ai.fler

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import com.ai.fler.core.jni.NativeLoader

/**
 * fler Application 入口。
 *
 * 通过 [HiltAndroidApp] 启用 Hilt 依赖注入；后续 P1 的 EnginePackManager、
 * P3 的 AppDatabase 等 @Singleton 组件均挂载到此 Application。
 */
@HiltAndroidApp
class FlerApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // 加载原生 JNI 库
        NativeLoader.load()
    }
}
