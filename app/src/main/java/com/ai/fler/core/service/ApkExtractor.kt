package com.ai.fler.core.service

import android.content.Context
import android.util.Log
import com.ai.fler.core.log.AppLogger
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
 * 从 APK 文件中提取 native 库：
 * 1. 优先路径（Flutter 应用）：提取 libapp.so 和 libflutter.so
 * 2. 回退路径（非 Flutter 应用）：libapp.so 不存在时，提取最优 ABI 目录下的全部 *.so
 *
 * APK 本质上是一个 ZIP 文件，其中 .so 文件存储在 lib/<abi>/ 目录下。
 */
@Singleton
class ApkExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
) {

    companion object {
        private const val TAG = "ApkExtractor"
        private const val ARM64_ABI = "arm64-v8a"
        private const val X86_64_ABI = "x86_64"

        /** 回退提取单个 APK 的 .so 数量上限（超大 APK 截断并记日志）。 */
        private const val MAX_FALLBACK_SO_COUNT = 30

        // 需要提取的 so 文件列表（Flutter 优先路径）
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
        appLogger.info(TAG, "开始提取 APK: $apkPath, size=${apkFile.length()}")

        // 确保输出目录存在
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        try {
            ZipFile(apkPath).use { zip ->
                // 优先路径：Flutter 双目标库（arm64 优先，x86_64 兜底）
                val abiDirs = listOf(ARM64_ABI, X86_64_ABI)
                var foundAnySo = false

                for (abi in abiDirs) {
                    for (targetSo in TARGET_SO_FILES) {
                        val entryName = "lib/$abi/$targetSo"
                        val entry = zip.getEntry(entryName)

                        if (entry != null) {
                            Log.i(TAG, "找到 $entryName (size=${entry.size}, method=${entry.method})")
                            val library = extractEntry(zip, entry, entryName, outputDir)
                            foundAnySo = true

                            when (targetSo) {
                                "libapp.so" -> result.libapp = library
                                "libflutter.so" -> result.libflutter = library
                            }

                            Log.i(TAG, "Extracted $entryName -> ${library.path} (${library.size} bytes)")
                        } else {
                            Log.d(TAG, "未找到 $entryName")
                        }
                    }
                    // arm64 找到后就不回退到 x86_64
                    if (foundAnySo) break
                }

                // 回退路径（非 Flutter）：无 libapp.so 时提取最优 ABI 目录下的全部 *.so。
                // libflutter 已命中但 libapp 缺失的罕见情况同样走回退（此时已有库不重复提取）。
                if (result.libapp == null) {
                    val fallbackAbi = pickFallbackAbi(zip)
                    if (fallbackAbi != null) {
                        Log.i(TAG, "未找到 libapp.so，回退提取 lib/$fallbackAbi/ 下全部 native 库")
                        val prefix = "lib/$fallbackAbi/"
                        val soEntries = zip.entries().toList()
                            .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".so") }
                            .sortedBy { it.name }
                        if (soEntries.size > MAX_FALLBACK_SO_COUNT) {
                            Log.w(TAG, "lib/$fallbackAbi/ 含 ${soEntries.size} 个 .so，超过上限 " +
                                "$MAX_FALLBACK_SO_COUNT，截断")
                        }
                        for (entry in soEntries.take(MAX_FALLBACK_SO_COUNT)) {
                            val libName = entry.name.removePrefix(prefix)
                            // 优先路径已提取过的（如 libflutter.so）跳过
                            if (result.libflutter?.libraryName == libName) continue
                            val library = extractEntry(zip, entry, entry.name, outputDir)
                            result.extraLibs.add(library)
                            Log.i(TAG, "回退提取 ${entry.name} -> ${library.path} (${library.size} bytes)")
                        }
                        foundAnySo = foundAnySo || result.extraLibs.isNotEmpty()
                    }
                }

                if (!foundAnySo) {
                    Log.e(TAG, "APK 内未找到任何 native 库（lib/<abi>/*.so）。" +
                        "请确认 APK 包含 arm64-v8a / x86_64 等架构的 native 库。")
                }
            }

            // 一个库都没提到才报错（非 Flutter 但有 so 的情况视为成功）
            if (result.libapp == null && result.extraLibs.isEmpty() && result.libflutter == null) {
                result.error = "APK 内未找到任何 native 库（lib/<abi>/*.so）"
                Log.e(TAG, result.error!!)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting APK: ${e.message}", e)
            result.error = "Failed to extract APK: ${e.message}"
        }

        Log.i(TAG, "extract 完成: success=${result.isSuccess}, isFlutter=${result.isFlutter}, " +
            "libapp=${result.libapp != null}, libflutter=${result.libflutter != null}, " +
            "extraLibs=${result.extraLibs.size}")
        appLogger.info(TAG, "提取完成: success=${result.isSuccess}, isFlutter=${result.isFlutter}, soCount=${result.extraLibs.size + (if (result.libapp != null) 1 else 0) + (if (result.libflutter != null) 1 else 0)}")
        result
    }

    /**
     * 提取单个 zip 条目到输出目录，返回 Library 元数据。
     */
    private fun extractEntry(
        zip: ZipFile,
        entry: java.util.zip.ZipEntry,
        entryName: String,
        outputDir: File
    ): Library {
        val libName = entryName.substringAfterLast('/')
        val outputFile = File(outputDir, libName)
        zip.getInputStream(entry).use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }
        return Library(
            analysisId = 0, // 将在关联分析时设置
            libraryName = libName,
            path = outputFile.absolutePath,
            size = outputFile.length(),
            loadAddress = 0,
            isDartSnapshot = libName == "libflutter.so",
            sectionCount = 0,
            symbolCount = 0
        )
    }

    /**
     * 回退路径的 ABI 选择：遍历 zip 内 lib/<abi>/ 目录，
     * 优先 arm64-v8a，其次任一含 .so 的目录；无则返回 null。
     */
    private fun pickFallbackAbi(zip: ZipFile): String? {
        val abiWithSo = mutableSetOf<String>()
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            val name = entry.name
            if (entry.isDirectory || !name.startsWith("lib/") || !name.endsWith(".so")) continue
            val remaining = name.removePrefix("lib/")
            val slashIndex = remaining.indexOf('/')
            if (slashIndex > 0) {
                abiWithSo.add(remaining.substring(0, slashIndex))
            }
        }
        return when {
            ARM64_ABI in abiWithSo -> ARM64_ABI
            else -> abiWithSo.sorted().firstOrNull()
        }
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
     *
     * [extraLibs] 为非 Flutter 回退路径提取的全部 native 库
     * （Flutter 路径下通常为空）。
     */
    data class ExtractResult(
        var libapp: Library? = null,
        var libflutter: Library? = null,
        val extraLibs: MutableList<Library> = mutableListOf(),
        var error: String? = null
    ) {
        val isSuccess: Boolean get() = error == null && (libapp != null || extraLibs.isNotEmpty())

        /** 是否 Flutter 应用（以 libapp.so 存在为准）。 */
        val isFlutter: Boolean get() = libapp != null

        val libappPath: String? get() = libapp?.path
        val libflutterPath: String? get() = libflutter?.path
    }
}
