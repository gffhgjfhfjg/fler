package com.ai.fler.core.service

import android.util.Log
import com.ai.fler.core.log.AppLogger
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
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "FlerEngine"
    }

    /**
     * 下载引擎包到目标文件。
     *
     * @param target 目标文件路径
     * @param onProgress 进度回调：(已下载字节, 总字节, 速度字符串)
     * @param urls 候选下载地址；缺省用 [EngineSourceConfig.primaryUrl] / [EngineSourceConfig.fallbackUrl]。
     *        代理开启时：主下载 = 代理前缀 URL，备用 = 同一文件的原始 GitHub 地址。
     * @return 下载完成后的文件
     * @throws IllegalStateException 所有源均下载失败
     */
    suspend fun downloadEnginePack(
        target: File,
        onProgress: (downloaded: Long, total: Long, speed: String) -> Unit,
        urls: List<String>? = null,
    ): File = withContext(Dispatchers.IO) {
        val main = urls?.firstOrNull() ?: sourceConfig.primaryUrl
        val resolved = resolveUrl(main)
        // 代理主 + 原始备用（同一文件）；显式目标（如更新下载）无代理时只取目标；默认主/备
        val candidates = when {
            resolved != main -> listOf(resolved, main)
            urls != null -> listOf(main)
            else -> listOf(main, sourceConfig.fallbackUrl)
        }
        var lastException: Exception? = null

        for (url in candidates) {
            try {
                Log.i(TAG, "尝试下载源: $url")
                appLogger.info(TAG, "尝试下载源: $url")
                downloadFromSource(url, target, onProgress)
                Log.i(TAG, "下载源成功: $url")
                appLogger.info(TAG, "下载源成功: $url")
                return@withContext target
            } catch (e: Exception) {
                Log.e(TAG, "下载源失败: $url, 原因: ${e.message}", e)
                appLogger.error(TAG, "下载源失败: $url, 原因: ${e.message}")
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
     * @param url 校验文件地址；缺省用 [EngineSourceConfig.checksumUrl]。先走代理，失败回退原始地址。
     * 支持多种格式：
     * - 单行纯哈希: "abcdef1234567890..."
     * - 单行哈希+文件名: "abcdef1234567890...  fler-engines.7z"
     * - 多行 checksums.txt: 每行 "哈希  文件名"，查找 fler-engines.7z 对应的哈希
     */
    suspend fun fetchChecksum(url: String? = null): String? = withContext(Dispatchers.IO) {
        val target = url ?: sourceConfig.checksumUrl
        val resolved = resolveUrl(target)
        fetchChecksumFrom(resolved) ?: if (resolved != target) fetchChecksumFrom(target) else null
    }

    private fun fetchChecksumFrom(url: String): String? {
        return try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val content = response.body?.string()?.trim() ?: return null
                    val hash = parseChecksumContent(content)
                    Log.i(TAG, "校验和获取成功: ${hash?.take(16) ?: "null"}... url=$url")
                    hash
                } else {
                    Log.w(TAG, "校验和请求失败: HTTP ${response.code}, url=$url")
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
     * GitHub 加速：配置了代理前缀时，把 GitHub 域名 URL 前缀为 $proxy$url。
     * 仅对 github.com / raw.githubusercontent.com 生效，避免破坏自建下载源。
     */
    private fun resolveUrl(url: String): String {
        val proxy = sourceConfig.githubProxy
        if (proxy.isBlank()) return url
        if (!isGithubUrl(url)) return url
        if (url.startsWith(proxy)) return url
        // 代理前缀统一无尾斜杠，需补分隔符：https://gh-proxy.com/https://github.com/...
        return "$proxy/$url"
    }

    private fun isGithubUrl(url: String): Boolean {
        return url.startsWith("https://github.com/") ||
            url.startsWith("http://github.com/") ||
            url.startsWith("https://raw.githubusercontent.com/") ||
            url.startsWith("http://raw.githubusercontent.com/")
    }

    /**
     * 获取远程版本信息 JSON。
     *
     * 格式：
     * ```json
     * {"version":"1.0.0","dartVersions":["3.12.2","3.13.0"],"sizeBytes":12345678,"releaseNotes":"...","downloadUrl":"...","checksumUrl":"..."}
     * ```
     */
    suspend fun fetchVersionInfo(): RemoteVersionInfo? = withContext(Dispatchers.IO) {
        val url = sourceConfig.versionUrl
        if (url.isBlank()) {
            Log.i(TAG, "未配置版本信息 URL，跳过更新检查")
            return@withContext null
        }
        val resolved = resolveUrl(url)
        fetchVersionFrom(resolved) ?: if (resolved != url) fetchVersionFrom(url) else null
    }

    private fun fetchVersionFrom(url: String): RemoteVersionInfo? {
        return try {
            val request = Request.Builder().url(url).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return null
                    parseVersionJson(json)
                } else {
                    Log.w(TAG, "版本信息请求失败: HTTP ${response.code}, url=$url")
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "获取版本信息异常: ${e.message}", e)
            null
        }
    }

    private fun parseVersionJson(json: String): RemoteVersionInfo? {
        return try {
            // 简单 JSON 解析（避免引入 Gson 依赖）
            val version = extractJsonField(json, "version") ?: return null
            val sizeBytes = extractJsonField(json, "sizeBytes")?.toLongOrNull() ?: 0L
            val releaseNotes = extractJsonField(json, "releaseNotes")

            // dartVersions 是数组
            val dartVersions = extractJsonArray(json, "dartVersions")

            // downloadUrl / checksumUrl 优先取 JSON 字段，缺省回退到主下载源
            val downloadUrl = extractJsonField(json, "downloadUrl")
                ?.takeIf { it.isNotBlank() }
                ?: sourceConfig.primaryUrl
            val checksumUrl = extractJsonField(json, "checksumUrl")
                ?.takeIf { it.isNotBlank() }

            RemoteVersionInfo(
                version = version,
                dartVersions = dartVersions,
                sizeBytes = sizeBytes,
                releaseNotes = releaseNotes,
                downloadUrl = downloadUrl,
                checksumUrl = checksumUrl,
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
    val checksumUrl: String? = null,
)

private class HttpException(code: Int, override val message: String) : Exception("HTTP $code: $message")
