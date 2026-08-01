package com.ai.fler.core.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎包管理器。
 *
 * 协调"下载 → SHA256 校验 → 7z 解压 → 就绪检查"全流程，
 * 是 P1 阶段的核心类，后续所有分析功能依赖引擎包就绪。
 *
 * 引擎目录布局（解压后）：
 * ```
 * filesDir/engines/
 * ├── lib/                          ← 共享库
 * │   ├── libc++_shared.so
 * │   └── libcapstone.so
 * ├── dartvm_3.13.0.so             ← 11 个版本引擎
 * ├── dartvm_3.12.1.so
 * └── ...
 * ```
 */
@Singleton
class EnginePackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: DualSourceDownloader,
    private val extractor: EngineExtractor,
    private val engineLoader: EngineLoader,
    private val sourceConfig: EngineSourceConfig,
) {
    private val engineDir: File by lazy { File(context.filesDir, "engines") }

    companion object {
        private const val TAG = "FlerEngine"
        private const val KEY_INSTALLED_PACK_VERSION = "installed_pack_version"

        /** 下载+SHA256 校验的最大尝试次数（失败自动重试）。 */
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
    }

    /**
     * 引擎进度数据类，用于向 UI 汇报当前状态。
     */
    data class EngineProgress(
        val phase: Phase,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val speed: String = "",
        val extractProgress: Float = 0f,
        val errorMessage: String? = null,
    ) {
        enum class Phase {
            IDLE,
            DOWNLOADING,
            VERIFYING,
            EXTRACTING,
            LOADING,
            COMPLETED,
            FAILED,
        }

        val downloadProgress: Float
            get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f

        val overallProgress: Float
            get() = when (phase) {
                Phase.IDLE -> 0f
                Phase.DOWNLOADING -> downloadProgress * 0.7f
                Phase.VERIFYING -> 0.75f
                Phase.EXTRACTING -> 0.75f + extractProgress * 0.2f
                Phase.LOADING -> 0.95f
                Phase.COMPLETED -> 1.0f
                Phase.FAILED -> 0f
            }
    }

    private val _progress = MutableStateFlow(EngineProgress(EngineProgress.Phase.IDLE))
    val progress: Flow<EngineProgress> = _progress.asStateFlow()

    /** 引擎版本变更信号：下载完成 / 清除后自增，供设置页等 UI 实时刷新已安装版本。 */
    private val _versionsEpoch = MutableStateFlow(0L)
    val versionsEpoch: StateFlow<Long> = _versionsEpoch.asStateFlow()

    /** 已安装引擎包版本（Fix 2：装完新版本后更新检测不再提示；缺省回退内置版本）。 */
    private var installedPackVersion: String
        get() = prefs().getString(KEY_INSTALLED_PACK_VERSION, null)
            ?: EngineSourceConfig.ENGINE_PACKAGE_VERSION
        set(value) = prefs().edit().putString(KEY_INSTALLED_PACK_VERSION, value).apply()

    private fun prefs() = context.getSharedPreferences("engine_pack", Context.MODE_PRIVATE)

    /** 通知已安装引擎版本发生变化（下载完成 / 清除）。 */
    fun notifyVersionsChanged() {
        _versionsEpoch.value++
    }

    /**
     * 检查引擎包是否就绪。
     *
     * 严格条件（重启后也应满足，否则会触发重新下载）：
     * 1. 至少一个 dartvm_*.so 存在
     * 2. 必要共享库齐全（libc++_shared.so、libcapstone.so）
     * 3. ICU 库（libicudata.so / libicuuc.so）可选，打包方式可能不同
     */
    fun isEnginePackReady(): Boolean {
        val hasDartVm = engineDir.listFiles()?.any {
            it.name.startsWith("dartvm_") && it.name.endsWith(".so")
        } ?: false
        val libCxx = File(engineDir, "lib/libc++_shared.so")
        val libCapstone = File(engineDir, "lib/libcapstone.so")
        // ICU 可选：部分引擎包将 ICU 静态链接进 dartvm，或打包方式不同
        return hasDartVm && libCxx.exists() && libCapstone.exists()
    }

    /**
     * 列出已安装的 Dart 引擎版本。
     */
    suspend fun listInstalledVersions(): List<String> = withContext(Dispatchers.IO) {
        if (!engineDir.exists()) return@withContext emptyList()

        engineDir.listFiles()
            ?.filter { it.name.startsWith("dartvm_") && it.name.endsWith(".so") }
            ?.map { file ->
                // 从 dartvm_3.12.1.so 提取 3.12.1
                file.name.removePrefix("dartvm_").removeSuffix(".so")
            }
            ?.sorted()
            ?: emptyList()
    }

    /**
     * 确保引擎包就绪：如无则下载+解压，如有则跳过。
     *
     * 使用 [channelFlow] 以便下载/解压回调也能把实时进度（字节/百分比）发送给订阅者
     * （设置页进度条、前台服务通知），而不只是阶段切换。
     *
     * @return Flow<EngineProgress> 进度流
     */
    /**
     * 确保引擎包就绪：未就绪时下载 + 校验 + 解压。
     *
     * @param force 为 true 时强制重新下载（「下载更新」用），跳过已就绪短路。
     */
    fun ensureEnginesReady(force: Boolean = false): Flow<EngineProgress> = channelFlow {
        if (!force && isEnginePackReady()) {
            Log.i(TAG, "引擎包已就绪，跳过下载")
            send(EngineProgress(EngineProgress.Phase.COMPLETED))
            return@channelFlow
        }

        try {
            val archiveFile = File(context.cacheDir, "fler-engines.7z")

            // 获取远程版本信息：优先用 version.json 的下载/校验地址（方案 B），失败回退默认源
            val remote = downloader.fetchVersionInfo()
            val downloadUrls = remote?.downloadUrl?.let {
                listOf(it, sourceConfig.primaryUrl, sourceConfig.fallbackUrl)
            }
            val checksumUrl = remote?.checksumUrl?.takeIf { it.isNotBlank() }
                ?: sourceConfig.checksumUrl

            // 1+2. 下载 + SHA256 校验（失败自动重试，最多 MAX_DOWNLOAD_ATTEMPTS 次）
            var attempt = 0
            while (true) {
                attempt++
                Log.i(TAG, "开始下载引擎包 (第 $attempt/${MAX_DOWNLOAD_ATTEMPTS} 次尝试), 源: ${downloader.sourceDescription()}")
                send(EngineProgress(EngineProgress.Phase.DOWNLOADING))
                _progress.value = EngineProgress(EngineProgress.Phase.DOWNLOADING)

                try {
                    downloader.downloadEnginePack(
                        archiveFile,
                        urls = downloadUrls,
                        onProgress = { downloaded, total, speed ->
                            val p = EngineProgress(
                                phase = EngineProgress.Phase.DOWNLOADING,
                                downloadedBytes = downloaded,
                                totalBytes = total,
                                speed = speed,
                            )
                            _progress.value = p
                            // 实时字节进度也发送给 flow 订阅者（进度条/通知）
                            trySend(p)
                        }
                    )

                    Log.i(TAG, "下载完成，文件大小: ${archiveFile.length()} bytes, 路径: ${archiveFile.absolutePath}")

                    // SHA256 校验
                    send(EngineProgress(EngineProgress.Phase.VERIFYING))
                    _progress.value = EngineProgress(EngineProgress.Phase.VERIFYING)

                    // 验证文件头（7z 魔术字节: 37 7A BC AF 27 1C）
                    if (!isValid7zFile(archiveFile)) {
                        Log.e(TAG, "文件头不是有效 7z 归档: ${archiveFile.absolutePath}")
                        throw IllegalStateException("下载的文件不是有效的 7z 归档，可能下载不完整")
                    }

                    val expectedSha256 = downloader.fetchChecksum(checksumUrl)
                    if (expectedSha256 != null) {
                        Log.i(TAG, "获取到校验和: ${expectedSha256.take(16)}...")
                        val isValid = extractor.verifyChecksum(archiveFile, expectedSha256)
                        if (!isValid) {
                            val actual = extractor.computeSha256(archiveFile)
                            Log.e(TAG,
                                "SHA256 校验失败: 期望 ${expectedSha256.take(16)}..., 实际 ${actual.take(16)}..., " +
                                    "源: ${downloader.sourceDescription()}")
                            throw IllegalStateException(
                                "SHA256 校验失败（若在设置中自定义过下载源，请重置为默认）"
                            )
                        }
                        Log.i(TAG, "SHA256 校验通过")
                    } else {
                        Log.w(TAG, "未获取到远程校验和，跳过 SHA256 校验")
                    }

                    break
                } catch (e: Exception) {
                    archiveFile.delete()
                    if (attempt < MAX_DOWNLOAD_ATTEMPTS) {
                        Log.w(TAG, "第 $attempt 次尝试失败: ${e.message}, 将重试", e)
                        continue
                    }
                    throw e
                }
            }

            // 3. 解压
            Log.i(TAG, "开始解压到: ${engineDir.absolutePath}")
            send(EngineProgress(EngineProgress.Phase.EXTRACTING))
            _progress.value = EngineProgress(EngineProgress.Phase.EXTRACTING)

            extractor.extract(archiveFile, engineDir) { progress ->
                val p = EngineProgress(
                    phase = EngineProgress.Phase.EXTRACTING,
                    extractProgress = progress,
                )
                _progress.value = p
                // 实时解压进度也发送给 flow 订阅者
                trySend(p)
            }

            // 解压后立即做一次严格的就绪检查，失败立刻抛错（不要到下次重启才"又要重下"）
            val ready = isEnginePackReady()
            if (!ready) {
                val installed = listInstalledVersions()
                val engineFiles = engineDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
                val libFiles = File(engineDir, "lib").listFiles()?.map { it.name }?.sorted() ?: emptyList()
                Log.e(TAG, buildString {
                    append("解压后 isEnginePackReady() 仍然为 false!\n")
                    append("  - 识别到引擎版本: $installed\n")
                    append("  - engines/ 下文件: $engineFiles\n")
                    append("  - engines/lib/ 下文件: $libFiles\n")
                    append("  若发现文件多了一层 fler-engines/ 子目录，可能是顶层目录前缀未正确剥离。")
                })
                throw IllegalStateException(
                    "解压成功但引擎仍不可用（目录结构不匹配）。" +
                        "已安装引擎版本: $installed。请清除引擎后重试，或更新引擎包版本。"
                )
            }

            // 清理临时文件
            archiveFile.delete()

            // 4. 预加载共享库
            send(EngineProgress(EngineProgress.Phase.LOADING))
            _progress.value = EngineProgress(EngineProgress.Phase.LOADING)
            engineLoader.ensureSharedLibsLoaded()

            // 5. 完成
            Log.i(TAG, "引擎包就绪完成")
            send(EngineProgress(EngineProgress.Phase.COMPLETED))
            _progress.value = EngineProgress(EngineProgress.Phase.COMPLETED)
            notifyVersionsChanged()
            // Fix 2：记录本次实际安装的引擎包版本，避免更新后仍提示「发现新版本」
            remote?.let { installedPackVersion = it.version }

        } catch (e: Exception) {
            Log.e(TAG, "引擎包准备失败: ${e.message}", e)
            val error = EngineProgress(
                phase = EngineProgress.Phase.FAILED,
                errorMessage = e.message ?: "未知错误",
            )
            _progress.value = error
            send(error)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 检查引擎包更新。
     *
     * 对比本地已安装版本与远程最新版本，返回更新信息（如有）。
     */
    suspend fun checkForUpdates(): EngineUpdate? = withContext(Dispatchers.IO) {
        try {
            val remote = downloader.fetchVersionInfo() ?: return@withContext null
            val installed = listInstalledVersions()

            // 判断是否需要更新：
            // 1. 远程引擎版本号与「已安装引擎包版本」不同（装完新版本后不再提示）；
            // 2. 远程支持版本包含本地未安装的 Dart 版本。
            val hasNewVersion = remote.version != installedPackVersion ||
                remote.dartVersions.any { !installed.contains(it) }

            if (!hasNewVersion && installed.isNotEmpty()) {
                return@withContext null
            }

            EngineUpdate(
                version = remote.version,
                downloadUrl = remote.downloadUrl,
                sizeBytes = remote.sizeBytes,
                releaseNotes = remote.releaseNotes,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 清理引擎包（调试/卸载场景）。
     */
    suspend fun clearEngines() = withContext(Dispatchers.IO) {
        engineDir.deleteRecursively()
        notifyVersionsChanged()
    }

    /**
     * 验证文件是否为有效的 7z 归档（检查魔术字节）。
     *
     * 7z 文件头前 6 字节: 37 7A BC AF 27 1C
     */
    private fun isValid7zFile(file: File): Boolean {
        if (!file.exists() || file.length() < 6) return false
        return try {
            file.inputStream().use { input ->
                val header = ByteArray(6)
                val read = input.read(header)
                if (read < 6) return@use false
                // 7z magic: 37 7A BC AF 27 1C
                header[0] == 0x37.toByte() &&
                    header[1] == 0x7A.toByte() &&
                    header[2] == 0xBC.toByte() &&
                    header[3] == 0xAF.toByte() &&
                    header[4] == 0x27.toByte() &&
                    header[5] == 0x1C.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }
}

/** 引擎更新信息（预留）。 */
data class EngineUpdate(
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val releaseNotes: String?,
)
