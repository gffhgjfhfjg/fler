package com.ai.fler.core.service

import android.util.Log
import com.ai.fler.core.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎包解压工具。
 *
 * 使用 Apache Commons Compress 的 SevenZFile 解压 7z 格式引擎包。
 * LZMA2 编解码由 org.tukaani:xz 提供。
 */
@Singleton
class EngineExtractor @Inject constructor(
    private val appLogger: AppLogger,
) {

    companion object {
        private const val TAG = "FlerEngine"
    }

    /**
     * 解压 7z 归档到目标目录（旧协议整包用，全量覆盖）。
     *
     * 解压前会清空目标目录，避免上次失败的残留文件影响就绪判断与重试。
     * 自动剥离归档内的公共顶层目录前缀（例如 7z 内部路径为 lib/dartvm_3.12.2.so，
     * 解压后落在 targetDir/dartvm_3.12.2.so 而非 targetDir/lib/dartvm_3.12.2.so）。
     *
     * @param archive 7z 文件
     * @param targetDir 解压目标目录
     * @param onProgress 进度回调 (0.0 ~ 1.0)
     */
    suspend fun extract(
        archive: File,
        targetDir: File,
        onProgress: (Float) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        appLogger.info(TAG, "开始解压 7z: ${archive.absolutePath}, 大小 ${archive.length()} bytes")
        Log.i(TAG, "开始解压 7z: ${archive.absolutePath}, 大小 ${archive.length()} bytes")

        // 清理上一次可能残留的半成品文件
        if (targetDir.exists()) {
            targetDir.deleteRecursively()
        }
        targetDir.mkdirs()

        try {
            // ------------------------------------------------------------------
            // 阶段 1：第一次打开，仅扫描所有条目的名称，用于：
            //   a) 识别公共顶层目录前缀（剥离用）
            //   b) 统计总条目数（进度百分比用）
            // SevenZFile 不支持 reset / seek 回到开头，所以扫完必须 close 后重建。
            // ------------------------------------------------------------------
            val (entryNames, topPrefix) = scanEntryNamesAndPrefix(archive)
            val totalEntries = entryNames.size
            Log.i(TAG, "7z 归档共 $totalEntries 个条目")
            if (topPrefix.isNotEmpty()) {
                Log.i(TAG, "检测到顶层目录前缀 \"$topPrefix\"，解压时将剥离")
            }
            if (totalEntries == 0) {
                onProgress(1.0f)
                return@withContext
            }

            // ------------------------------------------------------------------
            // 阶段 2：第二次打开，边遍历边写文件。
            // 注意：不可再次调用 .entries / .toList()，否则元数据迭代器会消耗
            //       后续 getInputStream() 读取位置会出错（length=17; index=1 之类）。
            // ------------------------------------------------------------------
            SevenZFile.builder()
                .setFile(archive)
                .get()
                .use { sevenZFile ->
                    var processed = 0
                    var entry = sevenZFile.nextEntry
                    while (entry != null) {
                        // 剥离顶层目录前缀
                        val relativeName = if (topPrefix.isNotEmpty() && entry.name.startsWith(topPrefix)) {
                            entry.name.substring(topPrefix.length)
                        } else {
                            entry.name
                        }

                        // 跳过剥离后变成空字符串的条目（即顶层目录自身）
                        if (relativeName.isNotEmpty()) {
                            val outputFile = File(targetDir, relativeName)
                            if (entry.isDirectory) {
                                outputFile.mkdirs()
                            } else {
                                val parentDir = outputFile.parentFile
                                if (parentDir != null && !parentDir.exists()) {
                                    parentDir.mkdirs()
                                }
                                sevenZFile.getInputStream(entry).use { input ->
                                    FileOutputStream(outputFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }

                        processed++
                        if (processed % 4 == 0 || processed == totalEntries) {
                            Log.i(TAG, "解压 $processed/$totalEntries: ${entry.name}")
                        }
                        onProgress(processed.toFloat() / totalEntries.toFloat())
                        entry = sevenZFile.nextEntry
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "7z 解压失败: ${e.message}", e)
            throw IllegalStateException(
                "7z 解压失败: ${e.message}（引擎包版本可能过旧或已损坏，请更新引擎包）", e
            )
        }

        Log.i(TAG, "7z 解压完成 → ${targetDir.absolutePath}")
        onProgress(1.0f)
    }

    /**
     * 增量解压 7z 归档到目标目录（不删除目标目录、不剥离顶层前缀）。
     *
     * 供按版本/运行库独立下载使用：每个 7z 内含的路径即为最终布局
     * （运行库包 → lib/libc++_shared.so；版本包 → dartvm_<v>.so），
     * 追加到引擎目录，不影响其它已装版本。
     *
     * @param archive 7z 文件
     * @param targetDir 解压目标目录（已存在时追加，不清空）
     * @param onProgress 进度回调 (0.0 ~ 1.0)
     */
    suspend fun extractIncremental(
        archive: File,
        targetDir: File,
        onProgress: (Float) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        appLogger.info(TAG, "增量解压 7z: ${archive.absolutePath}, 大小 ${archive.length()} bytes")
        Log.i(TAG, "增量解压 7z: ${archive.absolutePath}, 大小 ${archive.length()} bytes")

        val totalEntries = scanEntryNames(archive).size
        Log.i(TAG, "7z 归档共 $totalEntries 个条目")
        if (totalEntries == 0) {
            onProgress(1.0f)
            return@withContext
        }
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }

        try {
            SevenZFile.builder()
                .setFile(archive)
                .get()
                .use { sevenZFile ->
                    var processed = 0
                    var entry = sevenZFile.nextEntry
                    while (entry != null) {
                        if (entry.name.isNotEmpty()) {
                            val outputFile = File(targetDir, entry.name)
                            if (entry.isDirectory) {
                                outputFile.mkdirs()
                            } else {
                                outputFile.parentFile?.mkdirs()
                                sevenZFile.getInputStream(entry).use { input ->
                                    FileOutputStream(outputFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                            }
                        }
                        processed++
                        if (processed % 4 == 0 || processed == totalEntries) {
                            Log.i(TAG, "解压 $processed/$totalEntries: ${entry.name}")
                        }
                        onProgress(processed.toFloat() / totalEntries.toFloat())
                        entry = sevenZFile.nextEntry
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "7z 增量解压失败: ${e.message}", e)
            throw IllegalStateException("7z 解压失败: ${e.message}", e)
        }

        Log.i(TAG, "7z 增量解压完成 → ${targetDir.absolutePath}")
        onProgress(1.0f)
    }

    /**
     * 第一遍扫描：读取所有条目名，返回名称列表。
     */
    private fun scanEntryNames(archive: File): List<String> {
        val names = mutableListOf<String>()
        SevenZFile.builder()
            .setFile(archive)
            .get()
            .use { sevenZFile ->
                var e = sevenZFile.nextEntry
                while (e != null) {
                    names.add(e.name)
                    e = sevenZFile.nextEntry
                }
            }
        return names
    }

    /**
     * 第一遍扫描：读取所有条目名，返回 (名称列表, 需要剥离的公共顶层目录前缀)。
     *
     * 公共前缀识别规则：
     *   - 所有非目录条目都以 "xxx/" 开头 → 前缀为 "xxx/"
     *   - 否则 → 前缀为空字符串（不剥离）
     */
    private fun scanEntryNamesAndPrefix(archive: File): Pair<List<String>, String> {
        val names = mutableListOf<String>()
        SevenZFile.builder()
            .setFile(archive)
            .get()
            .use { sevenZFile ->
                var e = sevenZFile.nextEntry
                while (e != null) {
                    names.add(e.name)
                    e = sevenZFile.nextEntry
                }
            }
        return names to detectCommonTopLevelPrefix(names)
    }

    /**
     * 从所有条目中找出公共的顶层目录前缀（带 '/' 后缀），没有则返回空串。
     */
    private fun detectCommonTopLevelPrefix(names: List<String>): String {
        if (names.isEmpty()) return ""

        // 先找到第一个非目录条目的顶层目录名
        val firstFileTop = names
            .firstOrNull { !it.endsWith('/') && it.contains('/') }
            ?.substringBefore('/')
            ?: return ""

        val candidate = "$firstFileTop/"

        // 验证：所有非目录条目都以 candidate 开头，或就在 candidate 下（等于 candidate 即目录本身）
        val allMatch = names.all { name ->
            when {
                name.endsWith('/') -> {
                    // 目录条目：等于 candidate 或包含在 candidate 下
                    name == candidate || name.startsWith(candidate) ||
                        // 或该目录是 candidate 的父级（只有 candidate 自己）
                        candidate.startsWith(name)
                }
                else -> name.startsWith(candidate) || !name.contains('/')
            }
        }
        return if (allMatch) candidate else ""
    }

    /**
     * 验证 SHA256 校验和。
     */
    fun verifyChecksum(file: File, expectedSha256: String): Boolean {
        return try {
            val actual = computeSha256(file)
            actual.equals(expectedSha256.trim(), ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 计算文件的 SHA256（用于校验失败时的诊断日志）。
     */
    fun computeSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
