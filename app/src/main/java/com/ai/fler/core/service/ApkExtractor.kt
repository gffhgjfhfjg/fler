package com.ai.fler.core.service

import android.content.Context
import android.util.Log
import com.ai.fler.data.entity.Library
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * APK 提取器。
 *
 * 从 APK 文件中提取 libapp.so 和 libflutter.so。
 * APK 本质上是一个 ZIP 文件，其中 .so 文件存储在 lib/<abi>/ 目录下。
 */
@Singleton
class ApkExtractor @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "ApkExtractor"
        private const val ARM64_ABI = "arm64-v8a"
        private const val X86_64_ABI = "x86_64"

        // 需要提取的 so 文件列表
        private val TARGET_SO_FILES = listOf(
            "libapp.so",
            "libflutter.so"
        )
    }

    /**
     * 从 APK 中提取目标 so 文件。
     *
     * @param apkPath APK 文件路径（必须是本地绝对路径，不接受 content:// URI）
     * @param outputDir 输出目录
     * @return 提取结果，包含各 so 文件的路径和信息
     */
    suspend fun extract(
        apkPath: String,
        outputDir: File
    ): ExtractResult = withContext(Dispatchers.IO) {
        val result = ExtractResult()
        val apkFile = File(apkPath)

        // ------------------------------------------------------------------
        // 入参校验 + 详细日志（便于定位"提取失败"具体原因）
        // ------------------------------------------------------------------
        Log.i(TAG, "extract: apkPath=$apkPath, outputDir=${outputDir.absolutePath}")
        if (!apkFile.exists()) {
            // 常见原因：project.apkPath 存了 content:// URI 而非文件路径
            val isUri = apkPath.startsWith("content://") || apkPath.startsWith("file://")
            val hint = if (isUri) "（路径看起来是 URI，应使用本地文件路径）" else ""
            Log.e(TAG, "APK file not found: $apkPath$hint")
            result.error = "APK file not found$hint: $apkPath"
            return@withContext result
        }
        if (!apkFile.isFile) {
            Log.e(TAG, "APK path is not a regular file: $apkPath")
            result.error = "APK path is not a regular file: $apkPath"
            return@withContext result
        }
        if (apkFile.length() < 4) {
            Log.e(TAG, "APK file too small (${apkFile.length()} bytes), likely corrupted: $apkPath")
            result.error = "APK file too small or corrupted: $apkPath"
            return@withContext result
        }

        // 检查 ZIP 文件头（PK\x03\x04）
        try {
            apkFile.inputStream().use { input ->
                val magic = ByteArray(4)
                val read = input.read(magic)
                if (read < 4 || magic[0] != 0x50.toByte() || magic[1] != 0x4b.toByte()) {
                    Log.e(TAG, "Not a valid APK/ZIP file (bad magic): $apkPath")
                    result.error = "Not a valid APK/ZIP file: $apkPath"
                    return@withContext result
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read APK header", e)
            result.error = "Failed to read APK header: ${e.message}"
            return@withContext result
        }

        Log.i(TAG, "APK size: ${apkFile.length()} bytes")

        // 确保输出目录存在
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        try {
            ZipFile(apkPath).use { zip ->
                // 优先提取 arm64-v8a 架构
                val abiDirs = listOf(ARM64_ABI, X86_64_ABI)
                var foundAnySo = false

                for (abi in abiDirs) {
                    for (targetSo in TARGET_SO_FILES) {
                        val entryName = "lib/$abi/$targetSo"
                        val entry = zip.getEntry(entryName)

                        if (entry != null) {
                            Log.i(TAG, "找到 $entryName (size=${entry.size}, method=${entry.method})")
                            val outputFile = File(outputDir, targetSo)
                            zip.getInputStream(entry).use { input ->
                                FileOutputStream(outputFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                            foundAnySo = true

                            val library = Library(
                                analysisId = 0, // 将在关联分析时设置
                                libraryName = targetSo,
                                path = outputFile.absolutePath,
                                size = outputFile.length(),
                                loadAddress = 0,
                                isDartSnapshot = targetSo == "libflutter.so",
                                sectionCount = 0,
                                symbolCount = 0
                            )

                            when (targetSo) {
                                "libapp.so" -> result.libapp = library
                                "libflutter.so" -> result.libflutter = library
                            }

                            Log.i(TAG, "Extracted $entryName -> ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                        } else {
                            Log.d(TAG, "未找到 $entryName")
                        }
                    }
                    // arm64 找到后就不回退到 x86_64
                    if (foundAnySo) break
                }

                if (!foundAnySo) {
                    Log.e(TAG, "APK 内未找到任何目标 so（libapp.so/libflutter.so）。" +
                        "支持的 ABI: ${abiDirs.joinToString()}。" +
                        "请确认这是 Flutter 应用且包含 arm64-v8a 架构。")
                }
            }

            // 检查是否成功提取了必要的文件
            if (result.libapp == null) {
                result.error = "Could not find libapp.so in APK. " +
                    "Supported ABIs: $ARM64_ABI, $X86_64_ABI"
                Log.e(TAG, result.error!!)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting APK: ${e.message}", e)
            result.error = "Failed to extract APK: ${e.message}"
        }

        Log.i(TAG, "extract 完成: success=${result.isSuccess}, " +
            "libapp=${result.libapp != null}, libflutter=${result.libflutter != null}")
        result
    }

    /**
     * 检查 APK 中包含哪些 ABI 架构。
     *
     * @param apkPath APK 文件路径
     * @return ABI 列表
     */
    suspend fun getSupportedAbis(apkPath: String): List<String> = withContext(Dispatchers.IO) {
        val abis = mutableSetOf<String>()

        try {
            ZipFile(apkPath).use { zip ->
                // 遍历所有条目，查找 lib/<abi>/ 模式
                val prefix = "lib/"
                val entries = zip.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name

                    if (name.startsWith(prefix) && !entry.isDirectory) {
                        val remaining = name.removePrefix(prefix)
                        val slashIndex = remaining.indexOf('/')
                        if (slashIndex > 0) {
                            val abi = remaining.substring(0, slashIndex)
                            if (isValidAbi(abi)) {
                                abis.add(abi)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting ABIs", e)
        }

        abis.toList().sorted()
    }

    /**
     * 检查 APK 是否是 Flutter 应用。
     *
     * @param apkPath APK 文件路径
     * @return 是否包含 libflutter.so
     */
    suspend fun isFlutterApp(apkPath: String): Boolean = withContext(Dispatchers.IO) {
        try {
            ZipFile(apkPath).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name.contains("libflutter.so")) {
                        return@withContext true
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking Flutter app", e)
        }
        false
    }

    private fun isValidAbi(abi: String): Boolean {
        val validAbis = listOf(
            "arm64-v8a", "armeabi-v7a", "armeabi",
            "x86_64", "x86",
            "mips", "mips64"
        )
        return abi in validAbis
    }

    /**
     * 提取结果数据类。
     */
    data class ExtractResult(
        var libapp: Library? = null,
        var libflutter: Library? = null,
        var error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null && libapp != null
        val libappPath: String? get() = libapp?.path
        val libflutterPath: String? get() = libflutter?.path
    }
}
