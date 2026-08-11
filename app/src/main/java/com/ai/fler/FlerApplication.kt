package com.ai.fler

import android.app.Application
import com.ai.fler.core.frida.HookScriptSeeder
import com.ai.fler.core.jni.NativeLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * fler Application 入口。
 *
 * 通过 [HiltAndroidApp] 启用 Hilt 依赖注入；启动时加载原生库，并在后台
 * 种子默认 Hook 脚本（[HookScriptSeeder]）。
 */
@HiltAndroidApp
class FlerApplication : Application() {

    @Inject
    lateinit var hookScriptSeeder: HookScriptSeeder

    override fun onCreate() {
        super.onCreate()
        // 加载原生 JNI 库
        NativeLoader.load()
        // 后台种子默认 Hook 脚本（幂等，不阻塞启动）
        hookScriptSeeder.seed()
    }
}