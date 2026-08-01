package com.ai.fler.core.service

import android.util.Log
import com.ai.fler.core.jni.ElfParserBindings
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dart 版本检测器。
 *
 * 从 libflutter.so 中检测 Dart SDK 版本。
 *
 * 检测策略（按优先级）：
 * 1. 全文件扫描带前缀的版本字符串：`Dart SDK version: X.Y.Z` / `Dart VM version: X.Y.Z` 等
 * 2. 全文件扫描 `X.Y.Z (stable)` / `X.Y.Z (dev)` 形式 —— Flutter 引擎版本串的强特征
 *    （如 `3.12.1 (stable) (Tue May 26 01:02:21 2025 ...)`）
 * 3. `.dart_snapshot_data` / `.rodata` 中带 `dart` 上下文的 `X.Y.Z`
 * 4. 均未命中返回 null（由上层明确报错，不再随机猜测）
 *
 * 注意：不同 Flutter 引擎构建的版本串格式可能不同（有的带 `Dart SDK version:` 前缀，
 * 有的只有裸版本串），因此扫描范围覆盖整个文件而非仅 .rodata。
 */
@Singleton
class DartVersionDetector @Inject constructor() {

    companion object {
        private const val TAG = "DartVersionDetector"

        // 带前缀的版本 pattern（按优先级排序）
        private val PREFIX_PATTERNS = listOf(
            "Dart SDK version:",
            "Dart VM version:",
            "Dart Version:",
            "Dart version:",
            "dart-sdk-",
            "dart-sdk version",
        )

        private val VERSION_REGEX = Regex("(\\d+\\.\\d+\\.\\d+)")

        // 强特征：Dart/Flutter 引擎的版本串常带 "(stable)"/"(dev)" 后缀
        private val STABLE_REGEX = Regex("(\\d+\\.\\d+\\.\\d+)\\s*\\((stable|dev)\\)")
    }

    /**
     * 从 libflutter.so 检测 Dart 版本。
     *
     * @param libflutterPath libflutter.so 文件路径
     * @return Dart 版本字符串（如 "3.12.1"），检测失败返回 null
     */
    fun detect(libflutterPath: String): String? {
        if (libflutterPath.isBlank()) {
            Log.w(TAG, "libflutterPath is blank")
            return null
        }

        try {
            ElfParserBindings().use { parser ->
                if (!parser.open(libflutterPath)) {
                    Log.e(TAG, "Failed to open libflutter.so: $libflutterPath")
                    return null
                }

                // 方法 1：整文件扫描（强特征优先）
                val whole = readWholeFile(libflutterPath)
                if (whole.isNotEmpty()) {
                    // 1a. 带 "(stable)"/"(dev)" 的裸版本串 —— 最高置信
                    val stable = STABLE_REGEX.find(whole)
                    if (stable != null) {
                        val v = stable.groupValues[1]
                        Log.i(TAG, "Found Dart version (stable/dev): $v")
                        return v
                    }

                    // 1b. 带前缀的版本串
                    for (pattern in PREFIX_PATTERNS) {
                        val idx = whole.indexOf(pattern)
                        if (idx >= 0) {
                            val start = idx + pattern.length
                            val end = whole.indexOf('\n', start).let {
                                if (it == -1) whole.indexOf('\r', start) else it
                            }
                            if (end > start) {
                                val raw = whole.substring(start, end).trim()
                                val v = parseVersion(raw)
                                if (v != null) {
                                    Log.i(TAG, "Found Dart version from '$pattern': $v")
                                    return v
                                }
                            }
                        }
                    }

                    // 1c. dart 上下文中的 X.Y.Z（"dart" 关键字附近）
                    val ctxVersion = findVersionNearDartKeyword(whole)
                    if (ctxVersion != null) {
                        Log.i(TAG, "Found Dart version near dart keyword: $ctxVersion")
                        return ctxVersion
                    }
                }

                // 方法 2：.rodata 扫描（备选）
                val rodataVersion = searchInRodata(parser)
                if (rodataVersion != null) {
                    Log.i(TAG, "Found Dart version from .rodata: $rodataVersion")
                    return rodataVersion
                }

                Log.w(TAG, "Could not detect Dart version from $libflutterPath")
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error detecting Dart version", e)
            return null
        }
    }

    // ========== 实现 ==========

    /** 读取整个文件为字节串（检测专用，libflutter.so ~12MB 可接受）。 */
    private fun readWholeFile(path: String): String {
        return try {
            val file = File(path)
            if (!file.exists() || file.length() == 0L) return ""
            RandomAccessFile(file, "r").use { raf ->
                val bytes = ByteArray(raf.length().toInt())
                raf.readFully(bytes)
                // ISO-8859-1 保留全部字节，避免高位被替换
                String(bytes, Charsets.ISO_8859_1)
            }
        } catch (e: Exception) {
            Log.w(TAG, "readWholeFile failed: ${e.message}")
            ""
        }
    }

    /** 扫描 .rodata 中的版本串（带前缀 pattern 或裸版本串）。 */
    private fun searchInRodata(parser: ElfParserBindings): String? {
        val rodata = parser.getSectionData(".rodata")
        if (rodata.isEmpty()) {
            Log.d(TAG, ".rodata section is empty or not found")
            return null
        }
        val content = String(rodata, Charsets.ISO_8859_1)

        for (pattern in PREFIX_PATTERNS) {
            val index = content.indexOf(pattern)
            if (index >= 0) {
                val start = index + pattern.length
                val end = content.indexOf('\n', start).let {
                    if (it == -1) content.indexOf('\r', start) else it
                }
                if (end > start) {
                    val raw = content.substring(start, end).trim()
                    val v = parseVersion(raw)
                    if (v != null) return v
                }
            }
        }

        // 裸 "(stable)"/"(dev)" 版本串
        val stable = STABLE_REGEX.find(content)
        if (stable != null) {
            return stable.groupValues[1]
        }

        return null
    }

    /** 从原始字符串解析 x.y.z 版本号。 */
    private fun parseVersion(raw: String): String? {
        return VERSION_REGEX.find(raw)?.groupValues?.get(1)
    }

    /**
     * 在 "dart" 关键字附近（±512 字节）查找 x.y.z 版本串。
     * 用于无前缀、无 (stable) 标记的构建（排除无关的库版本号）。
     */
    private fun findVersionNearDartKeyword(content: String): String? {
        val dartIdx = content.indexOf("dart", ignoreCase = true)
        if (dartIdx < 0) return null
        val start = (dartIdx - 512).coerceAtLeast(0)
        val end = (dartIdx + 512).coerceAtMost(content.length)
        return VERSION_REGEX.find(content.substring(start, end))?.groupValues?.get(1)
    }
}
