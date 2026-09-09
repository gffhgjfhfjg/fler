package com.ai.fler.core.gadget

import android.content.Context
import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.service.DualSourceDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * frida-gadget 下载与缓存（按 ABI，xz 解压，GitHub Releases 源 + gh-proxy 镜像兜底）。
 *
 * 缓存布局：filesDir/frida-gadget/<version>/<abi>.so（如 arm64-v8a.so）
 * 有效性：ELF 魔数 + 最小体积。
 */
@Singleton
class FridaGadgetDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: DualSourceDownloader,
    private val appLogger: AppLogger,
) {

    companion object {
        /** 与 RootAccess.FRIDA_VERSION 保持一致（17.17.0，gadget 与 server 互操作性最佳）。 */
        const val GADGET_VERSION = "17.17.0"

        /** gadget 库最小体积（frida-gadget 实际 ~20MB+，防半截文件）。 */
        private const val MIN_SO_SIZE = 5L * 1024 * 1024

        private const val TAG = "FridaGadgetDownloader"

        /** ABI（APK 命名）→ frida release 资产 ABI。 */
        private val ASSET_ABI = mapOf(
            "arm64-v8a" to "arm64",
            "armeabi-v7a" to "arm",
            "x86_64" to "x86_64",
            "x86" to "x86",
        )

        private fun releaseUrl(abi: String): String =
            "https://github.com/frida/frida/releases/download/$GADGET_VERSION/" +
                "frida-gadget-$GADGET_VERSION-android-${ASSET_ABI[abi]}.so.xz"
    }

    val cacheDir: File
        get() = File(File(context.filesDir, "frida-gadget"), GADGET_VERSION)

    /**
     * 确保 [abi] 的 gadget .so 就绪（缓存有效直接复用），返回 .so 文件。
     *
     * @param onProgress 粗粒度进度：0=开始下载，1=就绪
     */
    suspend fun ensureGadget(
        abi: String,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val assetAbi = ASSET_ABI[abi]
            ?: throw IllegalArgumentException("不支持的 ABI: $abi（支持 ${ASSET_ABI.keys}）")
        val dir = cacheDir.apply { mkdirs() }
        val soFile = File(dir, "$abi.so")
        if (isValidGadget(soFile)) {
            appLogger.debug(TAG, "gadget 缓存命中: $soFile")
            return@withContext soFile
        }

        val xzFile = File(dir, "$abi.xz")
        val url = releaseUrl(abi)
        appLogger.info(TAG, "下载 gadget $abi: $url")
        try {
            onProgress(0f, "下载 gadget $abi")
            downloader.downloadAsset(url, xzFile) { _, _, _ -> }
        } catch (e: Exception) {
            xzFile.delete()
            throw IllegalStateException("gadget 下载失败（$assetAbi）: ${e.message}", e)
        }
        try {
            xzFile.inputStream().use { raw ->
                XZInputStream(BufferedInputStream(raw)).use { xzIn ->
                    FileOutputStream(soFile).use { out -> xzIn.copyTo(out) }
                }
            }
        } catch (e: Exception) {
            soFile.delete()
            throw IllegalStateException("gadget xz 解压失败（$assetAbi）: ${e.message}", e)
        } finally {
            xzFile.delete()
        }
        if (!isValidGadget(soFile)) {
            soFile.delete()
            throw IllegalStateException("gadget 文件校验失败（$assetAbi，非 ELF 或过小）")
        }
        appLogger.info(TAG, "gadget 就绪: $soFile (${soFile.length() / 1048576}MB)")
        onProgress(1f, "gadget $abi 就绪")
        soFile
    }

    /** 缓存中已就绪的 ABI 列表（离线可用）。 */
    fun cachedAbis(): List<String> =
        ASSET_ABI.keys.filter { isValidGadget(File(cacheDir, "$it.so")) }

    private fun isValidGadget(f: File): Boolean {
        if (!f.isFile || f.length() < MIN_SO_SIZE) return false
        return try {
            val head = f.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            head[0] == 0x7f.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        } catch (_: Exception) {
            false
        }
    }
}
