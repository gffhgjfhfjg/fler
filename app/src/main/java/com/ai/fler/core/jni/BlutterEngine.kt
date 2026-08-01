package com.ai.fler.core.jni

import android.util.Log

/**
 * Blutter 引擎 JNI 封装。
 *
 * 封装 blutter_entry.cpp 导出的 blutter_analyze() 函数。
 * 引擎 so 通过 EngineLoader 加载后，使用 dlsym 在已加载库中查找符号。
 *
 * 使用方式：
 * ```
 * val engine = BlutterEngine("3.12.2")
 * val result = engine.analyze(soPath, dbPath)
 * ```
 *
 * @see dev-plan §P1.5 BlutterEngine
 * @see dev-plan §P1.6 blutter_jni.cpp
 */
class BlutterEngine(
    private val dartVersion: String,
    private val engineSoPath: String
) {

    /**
     * 分析结果。
     *
     * 注意：Success (0) 是唯一确定语义的返回码。
     * 其他负值的含义（TempDirError / AnalysisError 等）来自早期实现假设，
     * 与实际 blutter 引擎返回码不完全对应，因此 UI 层应展示 [rawCode]
     * 而不是枚举名称，以免误导。
     */
    enum class AnalyzeResult(val code: Int) {
        Success(0),
        TempDirError(-1),     // 语义存疑：实际不一定是临时目录问题
        AnalysisError(-2),     // 语义存疑
        DbError(-3),           // 语义存疑
        SignalCrash(-997),     // 信号崩溃（SIGSEGV/SIGBUS/SIGFPE/SIGABRT/SIGILL），已被 JNI 捕获并保活
        UnknownError(-999),
        ;

        /** 引擎返回的原始错误码（含 0）。 */
        val rawCode: Int get() = code

        /** 是否成功（只有 code == 0 才是成功）。 */
        val isSuccess: Boolean get() = code == 0

        /** 面向 UI 的可读描述（不依赖枚举名称）。 */
        fun displayName(): String = when (code) {
            0 -> "Success"
            else -> "Error(code=$code)"
        }

        companion object {
            fun fromCode(code: Int): AnalyzeResult =
                values().firstOrNull { it.code == code } ?: UnknownError
        }
    }

    companion object {
        private const val TAG = "BlutterEngine"

        /**
         * JNI 方法声明。
         *
         * 实际实现在 blutter_jni.cpp 中：
         * - 通过 dlopen(engineSoPath, RTLD_NOLOAD) 拿到已加载的 dartvm_*.so 的 handle
         * - 通过 dlsym(handle, "blutter_analyze") 查找符号
         * - 调用 blutter_analyze(soPath, dbPath) 执行分析
         * - 返回错误码
         *
         * cacheDir 用于 JNI 端 chdir + setenv("TMPDIR")，让 blutter 内部创建
         * 临时文件时走 app 私有目录（Android app 对 /tmp 无写权限，会立即返回
         * TempDirError=-1）。
         */
        @JvmStatic
        private external fun nativeBlutterAnalyze(
            engineSoPath: String,
            soPath: String,
            dbPath: String,
            cacheDir: String
        ): Int
    }

    /**
     * 运行 Blutter 分析，结果直接写入 SQLite 数据库。
     *
     * @param soPath libapp.so 的绝对路径
     * @param dbPath 输出 SQLite 数据库的绝对路径
     * @param cacheDir app cacheDir 的绝对路径，用于 blutter 内部临时文件
     * @return 分析结果
     * @throws IllegalStateException 如果 JNI 调用抛出异常（如引擎未加载、dlsym 失败）
     */
    fun analyze(soPath: String, dbPath: String, cacheDir: String): AnalyzeResult {
        Log.i(TAG, "analyze 开始: dartVersion=$dartVersion, engine=$engineSoPath, soPath=$soPath, dbPath=$dbPath, cacheDir=$cacheDir")
        val startTime = System.currentTimeMillis()
        try {
            val ret = nativeBlutterAnalyze(engineSoPath, soPath, dbPath, cacheDir)
            val elapsed = System.currentTimeMillis() - startTime
            val result = AnalyzeResult.fromCode(ret)
            Log.i(TAG, "analyze 完成: rawCode=$ret (${result.displayName()}), 耗时 ${elapsed}ms")
            return result
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "analyze 失败: nativeBlutterAnalyze 链接错误（dartvm_${dartVersion}.so 是否已加载？）", e)
            throw IllegalStateException("引擎未加载或符号不存在: ${e.message}", e)
        } catch (e: Exception) {
            Log.e(TAG, "analyze 异常", e)
            throw IllegalStateException("Blutter 分析异常: ${e.message}", e)
        }
    }

    override fun toString(): String = "BlutterEngine(dartVersion=$dartVersion, engineSoPath=$engineSoPath)"
}
