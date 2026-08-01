package com.ai.fler.core.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 补丁导出器。
 *
 * 将 BackupManager 中的补丁记录导出为 .patch 文件，
 * 支持通过 SAF（Storage Access Framework）保存到用户指定位置。
 *
 * .patch 文件格式：
 * ```
 * # Patch file for libapp.so
 * # Generated at: 2026-07-31 12:00:00
 * # Records: 3
 * # CRC32: 0xDEADBEEF
 *
 * # Patch 1: offset=0x1234
 * 0x1234: D2 80 05 40
 * # Patch 2: offset=0x5678
 * 0x5678: 1F 20 03 D5
 * ```
 */
@Singleton
class PatchExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupManager: BackupManager
) {

    /**
     * 生成补丁文件内容。
     */
    fun generatePatchContent(
        soFileName: String,
        records: List<PatchRecord>
    ): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date())

        val builder = StringBuilder()
        builder.appendLine("# Fler Patch File")
        builder.appendLine("# Target: $soFileName")
        builder.appendLine("# Generated: $timestamp")
        builder.appendLine("# Records: ${records.size}")

        // 计算所有补丁的 CRC32
        val allBytes = records.flatMap { it.newBytes.toList() }.toByteArray()
        val crc32 = backupManager.computeCRC32(allBytes)
        builder.appendLine("# CRC32: 0x${crc32.toString(16).uppercase()}")
        builder.appendLine()

        records.forEachIndexed { index, record ->
            builder.appendLine("# Patch ${index + 1}: offset=0x${record.address.toString(16).uppercase()}")
            val hexBytes = record.newBytes.joinToString(" ") { byte ->
                byte.toUByte().toString(16).uppercase().padStart(2, '0')
            }
            builder.appendLine("0x${record.address.toString(16).uppercase()}: $hexBytes")
        }

        return builder.toString()
    }

    /**
     * 导出补丁到 SAF 指定目录。
     *
     * @param directoryUri SAF 目录 Uri
     * @param soFileName 目标 SO 文件名
     * @param records 补丁记录列表
     * @return 导出的文件 Uri，失败返回 null
     */
    suspend fun exportToSaf(
        directoryUri: Uri,
        soFileName: String,
        records: List<PatchRecord>
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val dir = DocumentFile.fromTreeUri(context, directoryUri) ?: return@withContext null

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val baseName = soFileName.removeSuffix(".so")
            val patchFileName = "${baseName}_${timestamp}.patch"

            // 删除同名文件（如果存在）
            dir.findFile(patchFileName)?.delete()

            val file = dir.createFile("application/octet-stream", patchFileName)
                ?: return@withContext null

            val content = generatePatchContent(soFileName, records)

            context.contentResolver.openOutputStream(file.uri)?.use { outputStream ->
                outputStream.write(content.toByteArray())
                outputStream.flush()
            }

            file.uri
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 导出补丁到 SAF 指定文件 Uri（CreateDocument）。
     *
     * 用于让用户保存到 Documents 等可读位置，而非 app 私有 cacheDir。
     *
     * @param uri SAF CreateDocument 返回的文件 Uri
     * @param soFileName 目标 SO 文件名
     * @param records 补丁记录列表
     * @return 是否成功
     */
    suspend fun exportToUri(
        uri: Uri,
        soFileName: String,
        records: List<PatchRecord>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val content = generatePatchContent(soFileName, records)
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray())
                os.flush()
            } ?: return@withContext false
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 导出补丁应用到应用缓存目录（用于分享）。
     *
     * @param soFileName 目标 SO 文件名
     * @param records 补丁记录列表
     * @return 补丁文件，失败返回 null
     */
    suspend fun exportToCache(
        soFileName: String,
        records: List<PatchRecord>
    ): File? = withContext(Dispatchers.IO) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            val baseName = soFileName.removeSuffix(".so")
            val patchFileName = "${baseName}_${timestamp}.patch"

            val cacheDir = File(context.cacheDir, "patches").apply { mkdirs() }
            val patchFile = File(cacheDir, patchFileName)

            val content = generatePatchContent(soFileName, records)
            patchFile.writeText(content)

            patchFile
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 导出「修改后的 SO」二进制到 SAF 指定文件 Uri（CreateDocument）。
     *
     * 编辑器把补丁直接写入工作文件，因此把工作文件复制到用户选择的位置即为补丁后的 SO。
     *
     * @param uri SAF CreateDocument 返回的文件 Uri
     * @param sourceFile 已应用补丁的 SO 文件
     * @return 是否成功
     */
    suspend fun exportSoToUri(
        uri: Uri,
        sourceFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists() || !sourceFile.isFile) return@withContext false
            context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                sourceFile.inputStream().use { input ->
                    input.copyTo(outputStream)
                }
                outputStream.flush()
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }
}
