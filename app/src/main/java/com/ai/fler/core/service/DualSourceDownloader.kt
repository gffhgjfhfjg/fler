package com.ai.fler.core.service

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 双源下载器：按配置依次尝试主源与备用源（默认均为 GitHub myfler）。
 *
 * 任一源下载失败时自动切换下一个源。
 */
@Singleton
class DualSourceDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sourceConfig: EngineSourceConfig,
) {
    companion object {
        private const val TAG = "FlerEngine"
    }

    /**
     * 下载引擎包到目标文件。
     *
     * @param target 目标文件路径
     * @param onProgress 进度回调：(已下载字节, 总字节, 速度字符串)
     * @return 下载完成后的文件
     * @throws IllegalStateException 所有源均下载失败
     */
    suspend fun downloadEnginePack(
        target: File,
        onProgress: (downloaded: Long, total: Long, speed: String) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val sources = listOf(sourceConfig.primaryUrl, sourceConfig.fallbackUrl)
        var lastException: Exception? = null

        for (url in sources) {
            try {
                Log.i(TAG, "尝试下载源: $url")
                downloadFromSource(url, target, onProgress)
                Log.i(TAG, "下载源成功: $url")
                return@withContext target
            } catch (e: Exception) {
                Log.e(TAG, "下载源失败: $url, 原因: ${e.message}", e)
                lastException = e
                // 清理可能的部分下载
                target.delete()
            }
        }

        throw IllegalStateException("下载引擎包失败，所有源均不可用", lastException)
    }

    /**
     * 下载 SHA256 校验和。
     *
     * 支持多种格式：
     * - 单行纯哈希: "abcdef1234567890..."
     * - 单行哈希+文件名: "abcdef1234567890...  fler-engines.7z"
     * - 多行 checksums.txt: 每行 "哈希  文件名"，查找 fler-engines.7z 对应的哈希
     */
    suspend fun fetchChecksum(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(sourceConfig.checksumUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string()?.trim() ?: return@use null
                    val hash = parseChecksumContent(content)
                    Log.i(TAG, "校验和获取成功: ${hash?.take(16) ?: "null"}... url=${sourceConfig.checksumUrl}")
                    hash
                } else {
                    Log.w(TAG, "校验和请求失败: HTTP ${response.code}, url=${sourceConfig.checksumUrl}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取校验和异常: ${e.message}", e)
            null
        }
    }

    /**
     * 解析校验和内容，提取 fler-engines.7z 对应的 SHA256。
     */
    private fun parseChecksumContent(content: String): String? {
        val targetFile = "fler-engines.7z"
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }

        // 如果只有一行，直接提取哈希
        if (lines.size == 1) {
            return extractHashFromLine(lines[0])
        }

        // 多行格式：查找包含目标文件名的行
        for (line in lines) {
            if (line.contains(targetFile)) {
                return extractHashFromLine(line)
            }
        }

        // 未找到特定文件，返回第一行的哈希
        return lines.firstOrNull()?.let { extractHashFromLine(it) }
    }

    /**
     * 从单行中提取 SHA256 哈希。
     *
     * 支持格式：
     * - "哈希值" (纯哈希)
     * - "哈希值  文件名" (哈希在前)
     * - "文件名  哈希值" (哈希在后)
     */
    private fun extractHashFromLine(line: String): String? {
        val parts = line.trim().split(Regex("\\s{2,}|\\s+"))
        // 确保有足够的部分
        if (parts.isEmpty()) return null

        // 检查是否是纯哈希（64 个十六进制字符）
        if (parts.size == 1 && isHexString(parts[0], 64)) {
            return parts[0]
        }

        // 尝试提取 64 字符的十六进制字符串作为哈希
        for (part in parts) {
            if (isHexString(part, 64)) {
                return part
            }
        }

        return null
    }

    /**
     * 检查字符串是否为指定长度的十六进制字符串。
     */
    private fun isHexString(s: String, expectedLength: Int): Boolean {
        return s.length == expectedLength && s.all { it in "0123456789abcdefABCDEF" }
    }

    /**
     * 当前生效的下载源描述（用于日志诊断）。
     */
    fun sourceDescription(): String {
        return "primary=${sourceConfig.primaryUrl}, fallback=${sourceConfig.fallbackUrl}, " +
            "checksum=${sourceConfig.checksumUrl}"
    }

    private fun downloadFromSource(
        url: String,
        target: File,
        onProgress: (downloaded: Long, total: Long, speed: String) -> Unit,
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Fler/1.0")
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw HttpException(response.code, "HTTP ${response.code}")
            }

            val body = response.body ?: throw IllegalStateException("空响应体")
            val totalBytes = body.contentLength()
            Log.i(TAG, "HTTP ${response.code}, Content-Length: $totalBytes bytes, url=$url")
            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(target)

            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var downloaded = 0L
            var lastTime = System.currentTimeMillis()
            var lastDownloaded = 0L

            inputStream.use { input ->
                outputStream.use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        downloaded += read

                        // 每 500ms 报告一次进度
                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 500) {
                            val elapsed = (now - lastTime) / 1000.0
                            val delta = downloaded - lastDownloaded
                            val speed = if (elapsed > 0) {
                                formatSpeed(delta / elapsed)
                            } else "-- KB/s"
                            onProgress(downloaded, totalBytes, speed)
                            lastTime = now
                            lastDownloaded = downloaded
                        }
                    }
                }
            }

            onProgress(downloaded, totalBytes, formatSpeed(0.0))
        }
    }

    private fun formatSpeed(bytesPerSecond: Double): String {
        return when {
            bytesPerSecond >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSecond / (1024 * 1024))
            bytesPerSecond >= 1024 -> "%.1f KB/s".format(bytesPerSecond / 1024)
            else -> "%.0f B/s".format(bytesPerSecond)
        }
    }

    /**
     * 获取远程版本信息 JSON。
     *
     * 格式：
     * ```json
     * {"version":"1.0.0","dartVersions":["3.12.2","3.13.0"],"sizeBytes":12345678,"releaseNotes":"..."}
     * ```
     */
    suspend fun fetchVersionInfo(): RemoteVersionInfo? = withContext(Dispatchers.IO) {
        val url = sourceConfig.versionUrl
        if (url.isBlank()) {
            Log.i(TAG, "未配置版本信息 URL，跳过更新检查")
            return@withContext null
        }
        try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return@use null
                    return@withContext parseVersionJson(json)
                } else {
                    Log.w(TAG, "版本信息请求失败: HTTP ${response.code}, url=$url")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取版本信息异常: ${e.message}", e)
        }
        null
    }

    private fun parseVersionJson(json: String): RemoteVersionInfo? {
        return try {
            // 简单 JSON 解析（避免引入 Gson 依赖）
            val version = extractJsonField(json, "version") ?: return null
            val sizeBytes = extractJsonField(json, "sizeBytes")?.toLongOrNull() ?: 0L
            val releaseNotes = extractJsonField(json, "releaseNotes")

            // dartVersions 是数组
            val dartVersions = extractJsonArray(json, "dartVersions")

            // downloadUrl 优先取 JSON 字段，缺省回退到主下载源
            val downloadUrl = extractJsonField(json, "downloadUrl")
                ?.takeIf { it.isNotBlank() }
                ?: sourceConfig.primaryUrl

            RemoteVersionInfo(
                version = version,
                dartVersions = dartVersions,
                sizeBytes = sizeBytes,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJsonField(json: String, field: String): String? {
        val pattern = """"$field"\s*:\s*"(.*?)"""".toRegex()
        return pattern.find(json)?.groupValues?.getOrNull(1)
    }

    private fun extractJsonArray(json: String, field: String): List<String> {
        val pattern = """"$field"\s*:\s*\[(.*?)\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val match = pattern.find(json) ?: return emptyList()
        val arrayContent = match.groupValues.getOrNull(1) ?: return emptyList()
        return arrayContent.split(",")
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }
    }
}

/** 远程版本信息。 */
data class RemoteVersionInfo(
    val version: String,
    val dartVersions: List<String>,
    val sizeBytes: Long,
    val releaseNotes: String?,
    val downloadUrl: String,
)

private class HttpException(code: Int, override val message: String) : Exception("HTTP $code: $message")
