package com.ai.fler.core.service

import android.content.Context
import android.util.Log
import com.ai.fler.core.log.AppLogger
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.ProjectDao
import com.android.apksig.ApkSigner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.CRC32
import java.util.zip.Deflater
import javax.inject.Inject
import javax.inject.Singleton

/**
 * APK 回打器：把补丁后的 SO 替换回 APK 原条目，自动 ZIP 对齐并按所选方案重签名。
 *
 * 流程（全部设备端完成，不依赖 zipalign/apksigner 命令行工具）：
 * 1. 解析原 APK 的 EOCD + 中央目录（原始字节级解析，不改写原文件）
 * 2. 重建 ZIP：
 *    - 普通条目按「原始压缩字节」原样拷贝（不重压缩，速度快且字节不变）
 *    - lib/<abi>/<so> 目标条目替换为补丁后的 SO（保留原条目的压缩方式）
 *    - 旧 v1 签名文件（META-INF/MANIFEST.MF、*.SF、*.[RSA|DSA|EC]）剔除
 *    - STORED 条目自动对齐：.so → 16384（16KB 页设备兼容），其余 → 4 字节
 * 3. 签名（apksig，可选 v1/v2/v3；未启用则输出未签名 APK）
 * 4. 结果写入调用方指定的输出流
 *
 * 对齐实现：在本地文件头（Local File Header）追加 extra 填充字节，使数据区
 * 起始偏移落在对齐边界上——与 zipalign 的 extra 填充方案原理一致。
 */
@Singleton
class ApkRepacker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
    private val analysisDao: AnalysisDao,
    private val libraryDao: LibraryDao,
    private val projectDao: ProjectDao,
) {

    companion object {
        private const val TAG = "ApkRepacker"

        /** .so STORED 条目的对齐粒度：16KB（兼容 16KB 页设备，覆盖 4KB）。 */
        private const val SO_ALIGNMENT = 16384L

        /** 其余 STORED 条目对齐粒度（zipalign 默认行为）。 */
        private const val DEFAULT_ALIGNMENT = 4L

        /** 复制条目时的流式缓冲区大小。 */
        private const val COPY_BUFFER = 128 * 1024

        // ZIP 常量
        private const val EOCD_SIG = 0x06054b50
        private const val CD_SIG = 0x02014b50
        private const val LFH_SIG = 0x04034b50

        // 通用标志位
        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_DATA_DESCRIPTOR = 0x0008

        /** v1 签名文件名（重建时剔除，签名时由 apksig 重新生成）。 */
        private val V1_SIG_SUFFIXES = listOf(".SF", ".RSA", ".DSA", ".EC")
        private const val V1_MANIFEST = "META-INF/MANIFEST.MF"
    }

    // ==================================================================
    // 公开配置与结果
    // ==================================================================

    /** 签名选项。 */
    data class SignOptions(
        val enabled: Boolean = true,
        val v1: Boolean = true,
        val v2: Boolean = true,
        val v3: Boolean = true,
    )

    /** 签名密钥来源。 */
    sealed interface KeySource {
        /** 内置 debug 密钥（assets/debug.keystore，密码 android）。 */
        data object Debug : KeySource

        /** 用户导入的自定义密钥库（PKCS12；JKS 部分设备不支持，建议转换）。 */
        data class Custom(
            val storeFile: File,
            val storePassword: String,
            val keyAlias: String,   // 空 = 自动选择第一个 PrivateKeyEntry
            val keyPassword: String, // 空 = 使用 storePassword
        ) : KeySource
    }

    /** 回打结果。 */
    data class RepackResult(
        val ok: Boolean,
        val entryName: String = "",
        val entryCount: Int = 0,
        val signed: Boolean = false,
        val schemes: List<String> = emptyList(),
        val outputSize: Long = 0L,
        val durationMs: Long = 0L,
        val error: String? = null,
    )

    /**
     * 回打产物：[file] 是 cacheDir 下的临时文件，调用方用完必须删除。
     */
    data class RepackOutput(val file: File, val result: RepackResult)

    // ==================================================================
    // APK 定位（SO 路径 → 源 APK 路径）
    // ==================================================================

    /**
     * 反查 SO 所属项目的源 APK 路径。
     *
     * 查询链：libraries.path → analysis_id → analyses.project_id → projects.apk_path，
     * 兜底 analyses.libapp_path（libapp.so 直查）。SAF 打开的游离 SO 查不到 → null。
     */
    suspend fun resolveApkPathForSo(soPath: String): String? = withContext(Dispatchers.IO) {
        val soFile = File(soPath)
        if (!soFile.exists()) return@withContext null

        val projectId = runCatching {
            val fromLib = libraryDao.getByPath(soPath)?.analysisId
                ?.let { analysisDao.getById(it)?.projectId }
            fromLib ?: analysisDao.getByLibappPath(soPath)?.projectId
        }.getOrNull() ?: return@withContext null

        val apkPath = runCatching { projectDao.getById(projectId)?.apkPath }
            .getOrNull() ?: return@withContext null

        if (apkPath.isNotBlank() && File(apkPath).isFile) apkPath else null
    }

    /**
     * 在 APK 内定位目标 SO 条目名（lib/<abi>/<name>）。
     *
     * 补丁不改文件大小，优先按「条目 size == 补丁后大小」过滤；多 ABI 命中时
     * 优先 arm64-v8a（本机 ABI）。完全无 size 匹配时退回首个同名条目。
     */
    suspend fun resolveSoEntry(apkFile: File, soName: String, patchedSize: Long): String? =
        withContext(Dispatchers.IO) {
            try {
                RandomAccessFile(apkFile, "r").use { raf ->
                    val eocd = parseEocd(raf) ?: return@withContext null
                    val records = parseCentralDirectory(raf, eocd)
                    val candidates = records.filter { it.name.startsWith("lib/") && it.name.endsWith("/$soName") }
                    when {
                        candidates.isEmpty() -> null
                        else -> {
                            val bySize = candidates.filter { it.size == patchedSize }
                            (if (bySize.isNotEmpty()) bySize else candidates)
                                .minByOrNull { if (it.name.contains("arm64-v8a")) 0 else 1 }
                                ?.name
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "resolveSoEntry 失败: ${e.message}")
                null
            }
        }

    // ==================================================================
    // 回打主流程
    // ==================================================================

    /** 串行化回打（UI 与 MCP 并发调用时共享 cacheDir/apk_repack，互斥防互删）。 */
    private val repackMutex = Mutex()

    /**
     * 回打并签名，产物落在 cacheDir/apk_repack/ 下（调用方负责删除）。
     *
     * @param apkFile 源 APK
     * @param patchedSo 补丁后的 SO 工作文件（编辑器当前文件）
     * @param signOptions 签名选项（enabled=false 输出未签名 APK）
     * @param keySource 签名密钥
     * @param onProgress 进度回调 (0..1, 阶段描述)
     */
    suspend fun repackToTempFile(
        apkFile: File,
        patchedSo: File,
        signOptions: SignOptions,
        keySource: KeySource,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
    ): RepackOutput = repackMutex.withLock {
        withContext(Dispatchers.IO) {
            val start = System.currentTimeMillis()
            val workDir = File(context.cacheDir, "apk_repack").apply { mkdirs() }
            // 清理上次残留
            workDir.listFiles()?.forEach { it.delete() }
            val unsignedFile = File(workDir, "unsigned.apk")

            try {
                onProgress(0.02f, "解析 APK 结构")
                val soName = patchedSo.name
                val entryName = resolveSoEntry(apkFile, soName, patchedSo.length())
                    ?: throw IllegalStateException("APK 内未找到 lib/*/$soName 条目")

                // ----------------------------------------------------------
                // 1. 重建对齐 ZIP（替换目标 SO，剔除旧 v1 签名）
                // ----------------------------------------------------------
                val entryCount = rebuildAlignedZip(
                    apkFile = apkFile,
                    patchedSo = patchedSo,
                    soEntryName = entryName,
                    outFile = unsignedFile,
                    onProgress = onProgress,
                )

                // ----------------------------------------------------------
                // 2. 签名（可选）
                // ----------------------------------------------------------
                var schemes = listOf<String>()
                var finalFile = unsignedFile
                if (signOptions.enabled) {
                    onProgress(0.75f, "加载签名密钥")
                    val keyPair = loadKeyEntry(keySource)
                    val v1 = signOptions.v1
                    // v3 依赖 v2（apksig 要求 v2 先启用），此处强制联动
                    val v2 = signOptions.v2 || signOptions.v3
                    val v3 = signOptions.v3

                    onProgress(0.80f, "签名中 (v1=$v1 v2=$v2 v3=$v3)")
                    val signedFile = File(workDir, "signed.apk")
                    val signerConfig = ApkSigner.SignerConfig.Builder(
                        "CERT", keyPair.first, keyPair.second
                    ).build()
                    val signer = ApkSigner.Builder(listOf(signerConfig))
                        .setInputApk(unsignedFile)
                        .setOutputApk(signedFile)
                        .setV1SigningEnabled(v1)
                        .setV2SigningEnabled(v2)
                        .setV3SigningEnabled(v3)
                        .setMinSdkVersion(24)
                        .build()
                    signer.sign()
                    finalFile = signedFile
                    schemes = buildList {
                        if (v1) add("v1")
                        if (v2) add("v2")
                        if (v3) add("v3")
                    }
                }

                onProgress(1f, "完成")
                val size = finalFile.length()
                val duration = System.currentTimeMillis() - start
                appLogger.info(TAG, "回打完成: ${apkFile.name} 条目=$entryCount 签名=${schemes.ifEmpty { "无" }} " +
                    "输出=${size / 1024}KB 耗时=${duration}ms")
                RepackOutput(
                    file = finalFile,
                    result = RepackResult(
                        ok = true,
                        entryName = entryName,
                        entryCount = entryCount,
                        signed = signOptions.enabled,
                        schemes = schemes,
                        outputSize = size,
                        durationMs = duration,
                    ),
                )
            } catch (e: Exception) {
                val msg = "回打失败: ${e.message ?: e.javaClass.simpleName}"
                Log.e(TAG, msg, e)
                appLogger.error(TAG, "$msg (${e.javaClass.name})")
                // 清理半成品
                unsignedFile.delete()
                File(workDir, "signed.apk").delete()
                RepackOutput(File(workDir, "failed.apk"), RepackResult(ok = false, error = msg))
            }
        }
    }

    /** 把回打产物流式复制到输出流（带进度），用于 SAF Uri 写出。 */
    suspend fun copyToStream(
        file: File,
        output: OutputStream,
        onProgress: suspend (Float) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        var copied = 0L
        val total = file.length().coerceAtLeast(1L)
        val buf = ByteArray(COPY_BUFFER)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                copied += n
                onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        output.flush()
        copied
    }

    // ==================================================================
    // ZIP 重建（对齐 + 条目替换 + 旧签名剔除）
    // ==================================================================

    /** 中央目录单条记录（解析自源 APK）。 */
    private class CdRecord(
        val name: String,
        val nameBytes: ByteArray,
        val versionMadeBy: Int,
        val versionNeeded: Int,
        val flags: Int,
        val method: Int,
        val modTime: Int,
        val modDate: Int,
        val crc: Long,
        val compressedSize: Long,
        val size: Long,
        val internalAttr: Int,
        val externalAttr: Long,
        val localHeaderOffset: Long,
    ) {
        val isDirectory: Boolean get() = name.endsWith("/")
    }

    private class Eocd(val totalEntries: Int, val cdSize: Long, val cdOffset: Long)

    /**
     * 重建 ZIP：
     * - 普通条目：本地头重写（对齐 extra 填充），数据按原始压缩字节流式拷贝
     * - 目标 SO 条目：替换为补丁后内容（保留原压缩方式；STORED 时 16KB 对齐）
     * - 目录条目：原样重建
     * 返回写入的条目数。
     */
    private suspend fun rebuildAlignedZip(
        apkFile: File,
        patchedSo: File,
        soEntryName: String,
        outFile: File,
        onProgress: suspend (Float, String) -> Unit,
    ): Int {
        RandomAccessFile(apkFile, "r").use { raf ->
            val eocd = parseEocd(raf)
                ?: throw IllegalStateException("APK EOCD 解析失败（非标准 ZIP）")
            val records = parseCentralDirectory(raf, eocd)

            // 加密 APK 不支持（标志位 bit0）
            records.firstOrNull { it.flags and FLAG_ENCRYPTED != 0 }?.let {
                throw IllegalStateException("APK 含加密条目，不支持回打")
            }

            // 补丁 SO 的压缩数据（若原条目为 DEFLATED）
            val patchedBytes = patchedSo.readBytes()
            val targetRecord = records.firstOrNull { it.name == soEntryName }
                ?: throw IllegalStateException("目标条目不存在: $soEntryName")
            val targetIsStored = targetRecord.method == java.util.zip.ZipEntry.STORED

            FileOutputStream(outFile).use { fos ->
                val counting = CountingOutputStream(fos)
                val centralBuf = ByteArrayOutputStream()

                var written = 0
                val total = records.size
                for (record in records) {
                    // 剔除旧 v1 签名（重新签名时由 apksig 生成新的）
                    if (isV1SignatureFile(record.name)) continue

                    val isTarget = record.name == soEntryName
                    val method = if (isTarget) targetRecord.method else record.method
                    val stored = method == java.util.zip.ZipEntry.STORED

                    // ------------------------------------------------------
                    // 准备数据（内存中）与 CRC/尺寸
                    // ------------------------------------------------------
                    var crc = record.crc
                    var compSize: Long
                    var uncompSize: Long
                    val data: ByteArray

                    if (isTarget) {
                        if (targetIsStored) {
                            data = patchedBytes
                            crc = crc32Of(patchedBytes)
                            compSize = data.size.toLong()
                            uncompSize = compSize
                        } else {
                            val deflated = deflate(patchedBytes)
                            data = deflated
                            crc = crc32Of(patchedBytes)
                            compSize = data.size.toLong()
                            uncompSize = patchedBytes.size.toLong()
                        }
                    } else {
                        if (record.isDirectory) {
                            data = ByteArray(0)
                            compSize = 0L
                            uncompSize = 0L
                        } else {
                            // 原始压缩字节拷贝（不重压缩，保证速度与字节一致性）
                            val dataOffset = localDataOffset(raf, record)
                            val rawData = ByteArray(record.compressedSize.toInt())
                            raf.seek(dataOffset)
                            raf.readFully(rawData)
                            data = rawData
                            compSize = record.compressedSize
                            uncompSize = record.size
                        }
                    }

                    // ------------------------------------------------------
                    // 写本地文件头（STORED 时按 extra 填充对齐）
                    // ------------------------------------------------------
                    val flags = record.flags and FLAG_DATA_DESCRIPTOR.inv()
                    val nameBytes = record.nameBytes
                    val alignment = if (stored) alignmentFor(record.name) else 1L
                    val headerBase = 30 + nameBytes.size
                    val pad = if (alignment > 1L) {
                        ((alignment - ((counting.count + headerBase) % alignment)) % alignment).toInt()
                    } else 0

                    val newLocalOffset = counting.count
                    counting.u32(LFH_SIG.toLong() and 0xffffffffL)
                    counting.u16(record.versionNeeded)
                    counting.u16(flags)
                    counting.u16(method)
                    counting.u16(record.modTime)
                    counting.u16(record.modDate)
                    counting.u32(crc)
                    counting.u32(compSize)
                    counting.u32(uncompSize)
                    counting.u16(nameBytes.size)
                    counting.u16(pad)
                    counting.bytes(nameBytes)
                    if (pad > 0) counting.bytes(ByteArray(pad))     // 对齐填充（extra）
                    counting.bytes(data)

                    // ------------------------------------------------------
                    // 中央目录记录（重定向本地头偏移到新位置）
                    // ------------------------------------------------------
                    val cd = LeBytes(centralBuf)
                    cd.u32(CD_SIG.toLong() and 0xffffffffL)
                    cd.u16(record.versionMadeBy)
                    cd.u16(record.versionNeeded)
                    cd.u16(flags)
                    cd.u16(method)
                    cd.u16(record.modTime)
                    cd.u16(record.modDate)
                    cd.u32(crc)
                    cd.u32(compSize)
                    cd.u32(uncompSize)
                    cd.u16(nameBytes.size)
                    cd.u16(0)                                       // extra（中央目录不写填充）
                    cd.u16(0)                                       // comment
                    cd.u16(0)                                       // disk start
                    cd.u16(record.internalAttr)
                    cd.u32(record.externalAttr)
                    cd.u32(newLocalOffset)
                    cd.bytes(nameBytes)

                    written++
                    if (written % 50 == 0 || written == total) {
                        onProgress(
                            0.05f + 0.65f * written / total,
                            "重建 ZIP $written/$total"
                        )
                    }
                }

                // ------------------------------------------------------
                // 中央目录 + EOCD
                // ------------------------------------------------------
                val cdBytes = centralBuf.toByteArray()
                val cdOffset = counting.count
                counting.bytes(cdBytes)
                counting.u32(EOCD_SIG.toLong() and 0xffffffffL)
                counting.u16(0)
                counting.u16(0)
                counting.u16(written)
                counting.u16(written)
                counting.u32(cdBytes.size.toLong())
                counting.u32(cdOffset)
                counting.u16(0)
                fos.flush()

                if (written > 0xffff) {
                    throw IllegalStateException("条目数超过 65535（ZIP64 未支持）")
                }
                return written
            }
        }
    }

    /** 对齐粒度：.so 16KB，其余 STORED 条目 4 字节。 */
    private fun alignmentFor(entryName: String): Long =
        if (entryName.endsWith(".so")) SO_ALIGNMENT else DEFAULT_ALIGNMENT

    /** 旧 v1 签名文件判定。 */
    private fun isV1SignatureFile(name: String): Boolean {
        if (!name.startsWith("META-INF/")) return false
        if (name == V1_MANIFEST) return true
        val upper = name.uppercase()
        return V1_SIG_SUFFIXES.any { upper.endsWith(it) }
    }

    // ==================================================================
    // ZIP 解析辅助
    // ==================================================================

    /** 解析 EOCD（从文件尾部反向扫描签名）。 */
    private fun parseEocd(raf: RandomAccessFile): Eocd? {
        val fileSize = raf.length()
        if (fileSize < 22) return null
        val scanLen = minOf(fileSize, (65535 + 22).toLong()).toInt()
        raf.seek(fileSize - scanLen)
        val buf = ByteArray(scanLen)
        raf.readFully(buf)
        for (i in (buf.size - 22) downTo 0) {
            if (readU32(buf, i) == EOCD_SIG.toLong()) {
                val commentLen = readU16(buf, i + 20)
                if (i + 22 + commentLen == buf.size) {
                    val total = readU16(buf, i + 10)
                    val cdSize = readU32(buf, i + 12)
                    val cdOffset = readU32(buf, i + 16)
                    return Eocd(total, cdSize, cdOffset)
                }
            }
        }
        return null
    }

    /** 解析中央目录全部记录。 */
    private fun parseCentralDirectory(raf: RandomAccessFile, eocd: Eocd): List<CdRecord> {
        raf.seek(eocd.cdOffset)
        val cd = ByteArray(eocd.cdSize.toInt())
        raf.readFully(cd)
        val records = ArrayList<CdRecord>(eocd.totalEntries)
        var pos = 0
        while (pos + 46 <= cd.size) {
            if (readU32(cd, pos) != CD_SIG.toLong()) break
            val nameLen = readU16(cd, pos + 28)
            val extraLen = readU16(cd, pos + 30)
            val commentLen = readU16(cd, pos + 32)
            val nameBytes = cd.copyOfRange(pos + 46, pos + 46 + nameLen)
            records.add(
                CdRecord(
                    name = String(nameBytes, Charsets.UTF_8),
                    nameBytes = nameBytes,
                    versionMadeBy = readU16(cd, pos + 4),
                    versionNeeded = readU16(cd, pos + 6),
                    flags = readU16(cd, pos + 8),
                    method = readU16(cd, pos + 10),
                    modTime = readU16(cd, pos + 12),
                    modDate = readU16(cd, pos + 14),
                    crc = readU32(cd, pos + 16),
                    compressedSize = readU32(cd, pos + 20),
                    size = readU32(cd, pos + 24),
                    internalAttr = readU16(cd, pos + 36),
                    externalAttr = readU32(cd, pos + 38),
                    localHeaderOffset = readU32(cd, pos + 42),
                )
            )
            pos += 46 + nameLen + extraLen + commentLen
        }
        return records
    }

    /** 读取条目本地头，返回数据区起始偏移（本地头 30B + nameLen + extraLen）。 */
    private fun localDataOffset(raf: RandomAccessFile, record: CdRecord): Long {
        raf.seek(record.localHeaderOffset)
        val lh = ByteArray(30)
        raf.readFully(lh)
        if (readU32(lh, 0) != LFH_SIG.toLong()) {
            throw IllegalStateException("本地头签名错误: ${record.name}")
        }
        val nameLen = readU16(lh, 26)
        val extraLen = readU16(lh, 28)
        return record.localHeaderOffset + 30 + nameLen + extraLen
    }

    private fun readU16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xff) or ((b[off + 1].toInt() and 0xff) shl 8)

    private fun readU32(b: ByteArray, off: Int): Long =
        (readU16(b, off).toLong() and 0xffffffffL) or (readU16(b, off + 2).toLong() shl 16)

    private fun crc32Of(data: ByteArray): Long {
        val crc = CRC32()
        crc.update(data)
        return crc.value
    }

    /** 压缩数据（DEFLATED 条目替换时用）。 */
    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 2)
        val buf = ByteArray(COPY_BUFFER)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }

    // ==================================================================
    // 小端写出辅助
    // ==================================================================

    /** 计数 + 小端写出的输出流。 */
    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var count = 0L
            private set

        fun u16(v: Int) {
            delegate.write(v and 0xff)
            delegate.write((v ushr 8) and 0xff)
            count += 2
        }

        fun u32(v: Long) {
            u16((v and 0xffffL).toInt())
            u16(((v ushr 16) and 0xffffL).toInt())
        }

        fun bytes(b: ByteArray) {
            if (b.isEmpty()) return
            delegate.write(b)
            count += b.size
        }

        override fun write(b: Int) {
            delegate.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            count += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    /** 追加到 ByteArrayOutputStream 的小端写入器。 */
    private class LeBytes(private val out: ByteArrayOutputStream) {
        fun u16(v: Int) {
            out.write(v and 0xff)
            out.write((v ushr 8) and 0xff)
        }

        fun u32(v: Long) {
            u16((v and 0xffffL).toInt())
            u16(((v ushr 16) and 0xffffL).toInt())
        }

        fun bytes(b: ByteArray) = out.write(b)
    }

    // ==================================================================
    // 密钥加载
    // ==================================================================

    /** 内置 debug 密钥落盘路径（首次使用时从 assets 复制）。 */
    val debugKeystoreFile: File
        get() {
            val dir = File(context.filesDir, "signing").apply { mkdirs() }
            val f = File(dir, "debug.keystore")
            if (!f.exists() || f.length() == 0L) {
                context.assets.open("debug.keystore").use { input ->
                    FileOutputStream(f).use { output -> input.copyTo(output) }
                }
            }
            return f
        }

    /** 自定义密钥库落盘路径。 */
    val customKeystoreFile: File
        get() = File(File(context.filesDir, "signing").apply { mkdirs() }, "custom_key.store")

    /** 导入自定义密钥库（SAF 选中后复制到私有目录），返回落盘文件。 */
    suspend fun importCustomKeystore(source: InputStream): File = withContext(Dispatchers.IO) {
        val target = customKeystoreFile
        val tmp = File(target.parentFile, "custom_key.tmp")
        source.use { input ->
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
        }
        if (!tmp.renameTo(target)) {
            target.delete()
            if (!tmp.renameTo(target)) throw IllegalStateException("密钥库写入失败")
        }
        target
    }

    /** 加载密钥：返回 (私钥, 证书链)。 */
    private fun loadKeyEntry(source: KeySource): Pair<PrivateKey, List<X509Certificate>> {
        return when (source) {
            is KeySource.Debug -> {
                loadFromKeystore(debugKeystoreFile, "android", "androiddebugkey", "android")
            }
            is KeySource.Custom -> {
                val keyPass = source.keyPassword.ifBlank { source.storePassword }
                loadFromKeystore(
                    source.storeFile, source.storePassword, source.keyAlias, keyPass
                )
            }
        }
    }

    /**
     * 从密钥库加载私钥与证书链。
     *
     * 密钥库类型按 PKCS12 → JKS 顺序尝试（Android 平台不提供 JKS，
     * 报错时提示转换：keytool -importkeystore ... -deststoretype PKCS12）。
     */
    private fun loadFromKeystore(
        storeFile: File,
        storePassword: String,
        alias: String,
        keyPassword: String,
    ): Pair<PrivateKey, List<X509Certificate>> {
        var lastError: Exception? = null
        for (type in listOf("PKCS12", "JKS")) {
            try {
                val ks = KeyStore.getInstance(type)
                storeFile.inputStream().use { ks.load(it, storePassword.toCharArray()) }

                val keyAlias = alias.ifBlank {
                    ks.aliases().toList().firstOrNull { a ->
                        runCatching {
                            val e = ks.getEntry(a, KeyStore.PasswordProtection(keyPassword.toCharArray()))
                            e is KeyStore.PrivateKeyEntry
                        }.getOrDefault(false)
                    } ?: throw IllegalStateException("密钥库中未找到私钥条目")
                }

                val entry = ks.getEntry(
                    keyAlias, KeyStore.PasswordProtection(keyPassword.toCharArray())
                ) as? KeyStore.PrivateKeyEntry
                    ?: throw IllegalStateException("别名 \"$keyAlias\" 不是私钥条目")

                val certs = (entry.certificateChain ?: arrayOf(entry.certificate))
                    .mapNotNull { it as? X509Certificate }
                if (certs.isEmpty()) throw IllegalStateException("证书链为空")

                return entry.privateKey to certs
            } catch (e: Exception) {
                lastError = e
                if (e is IllegalStateException) throw e
            }
        }
        throw IllegalStateException(
            "密钥库加载失败（支持 PKCS12；JKS 请先转换: keytool -importkeystore " +
                "-srckeystore key.jks -destkeystore key.p12 -deststoretype PKCS12）: ${lastError?.message}"
        )
    }
}
