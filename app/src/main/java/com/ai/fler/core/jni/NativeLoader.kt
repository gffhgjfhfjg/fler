package com.ai.fler.core.jni

import android.util.Log

/**
 * 原生库加载器。
 *
 * 负责在应用启动时加载所有 JNI 原生库。
 * 使用 System.loadLibrary 确保库在使用前已加载。
 */
object NativeLoader {

    private const val TAG = "NativeLoader"
    private var loaded = false

    /**
     * 加载所有原生库。
     * 幂等操作，可安全多次调用。
     */
    @Synchronized
    fun load() {
        if (loaded) return

        try {
            // 加载 JNI 桥接库
            System.loadLibrary("fler_jni")
            Log.i(TAG, "Native library 'fler_jni' loaded successfully")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load native library", e)
            throw e
        }
    }
}
