package com.ai.fler.core.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Flutter 引擎探测器。
 *
 * 创建项目时对 APK 做轻量探测：只提取 libflutter.so 检测 Dart 版本，
 * 并判断本机是否已安装对应引擎，用于在创建阶段就引导用户下载引擎。
 *
 * 探测完成后删除临时目录，不占用磁盘空间。
 */
@Singleton
class FlutterEngineProber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apkExtractor: ApkExtractor,
    private val dartVersionDetector: DartVersionDetector,
    private val enginePackManager: EnginePackManager,
) {

    /** 探测结果。 */
    sealed class ProbeResult {
        /** 非 Flutter 应用（无 libflutter.so），不需要引擎。 */
        data object NotFlutter : ProbeResult()

        /** 检测到 Dart 版本，并给出本机引擎是否就绪。 */
        data class Detected(val dartVersion: String, val engineReady: Boolean) : ProbeResult()

        /** 是 Flutter 应用但版本检测失败（不阻塞分析，交给分析阶段真实报错）。 */
        data object Undetectable : ProbeResult()
    }

    /**
     * 探测 APK 的 Dart 版本与引擎就绪状态。
     *
     * @param apkPath APK 文件路径
     */
    suspend fun probe(apkPath: String): ProbeResult = withContext(Dispatchers.IO) {
        val probeDir = File(context.cacheDir, "flutter_probe_${System.currentTimeMillis()}")
        try {
            if (!apkExtractor.isFlutterApp(apkPath)) {
                Log.i(TAG, "非 Flutter 应用，跳过引擎探测: $apkPath")
                return@withContext ProbeResult.NotFlutter
            }
            val libflutter = apkExtractor.extractLibflutter(apkPath, probeDir)
                ?: return@withContext ProbeResult.Undetectable
            val version = dartVersionDetector.detect(libflutter.path)
            if (version.isNullOrBlank()) {
                Log.w(TAG, "Flutter 应用但 Dart 版本检测失败: $apkPath")
                return@withContext ProbeResult.Undetectable
            }
            val engineReady = enginePackManager.isEngineVersionReady(version)
            Log.i(TAG, "探测完成: Dart $version, engineReady=$engineReady")
            ProbeResult.Detected(version, engineReady)
        } catch (e: Exception) {
            Log.e(TAG, "探测异常: ${e.message}", e)
            ProbeResult.Undetectable
        } finally {
            runCatching { probeDir.deleteRecursively() }
        }
    }

    companion object {
        private const val TAG = "FlutterEngineProber"
    }
}