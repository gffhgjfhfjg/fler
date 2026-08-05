package com.ai.fler.core.service

import android.content.Context
import android.system.Os
import android.util.Log
import com.ai.fler.core.jni.BlutterEngine
import com.ai.fler.core.log.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 动态引擎加载器。
 *
 * 核心职责：
 * 1. 严格按依赖顺序加载共享库（先于引擎 so）
 * 2. 使用 System.load(absolutePath) 而非 System.loadLibrary()，
 *    因为 .so 文件在 filesDir/engines/ 下而非 jniLibs
 * 3. 线程安全：多线程竞争加载同一引擎时保证只加载一次
 *
 * @see dev-plan §3.1 引擎包加载方式
 * @see dev-plan §6.1 System.load 加载顺序
 */
@Singleton
class EngineLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
) {
    private val engineDir by lazy { File(context.filesDir, "engines") }
    private val loadedLibs = mutableSetOf<String>()
    private val loadLock = Any()

    companion object {
        private const val TAG = "FlerEngine"
    }

    /**
     * 必须严格按依赖顺序加载。
     * ICU 库用于 Dart VM 的国际化支持。
     *
     * 注：libcapstone.so 已移除——capstone 静态链接进 fler_jni.so，SO 编辑器
     * 反汇编不再依赖引擎包；blutter 引擎也改为静态 capstone。
     */
    private val sharedLibs = listOf(
        "lib/libc++_shared.so",
        "lib/libicudata.so",
        "lib/libicuuc.so",
    )

    /**
     * 加载所有共享库（幂等，已加载的跳过）。
     * 必须在加载引擎之前调用。
     *
     * 先调用 [prepareVersionedSymlinks] 为 lib/ 目录下每个 .so 创建从其 SONAME
     * （如 libicudata.so.73）到实际文件名（libicudata.so）的符号链接。
     * 否则 dlopen 加载 libicuuc.so 时找不到 NEEDED libicudata.so.73。
     */
    fun ensureSharedLibsLoaded() {
        synchronized(loadLock) {
            prepareVersionedSymlinks()
            for (libPath in sharedLibs) {
                val libName = File(libPath).name
                if (libName !in loadedLibs) {
                    val libFile = File(engineDir, libPath)
                    if (libFile.exists()) {
                        try {
                            System.load(libFile.absolutePath)
                            loadedLibs.add(libName)
                        } catch (e: UnsatisfiedLinkError) {
                            // ICU 等可选库加载失败不致命，dartvm 可能已静态链接
                            Log.w(TAG, "共享库加载失败（可忽略若 dartvm 已静态链接）: ${libFile.name} - ${e.message}")
                        }
                    }
                }
            }
        }
    }

    /**
     * 加载指定 Dart 版本的引擎并返回 [BlutterEngine] 实例。
     *
     * @param dartVersion Dart SDK 版本，如 "3.12.2"
     * @return BlutterEngine 封装实例（持有 dartvm_*.so 路径供 JNI 用 RTLD_NOLOAD 查找符号）
     * @throws EngineNotReadyException 引擎文件不存在
     */
    fun loadEngine(dartVersion: String): BlutterEngine {
        ensureSharedLibsLoaded()

        val engineFileName = "dartvm_${dartVersion}.so"
        val engineFile = File(engineDir, engineFileName)
        if (!engineFile.exists()) {
            throw EngineNotReadyException(dartVersion)
        }

        synchronized(loadLock) {
            if (engineFileName !in loadedLibs) {
                Log.i(TAG, "System.load: ${engineFile.absolutePath}")
                appLogger.info(TAG, "加载引擎: $engineFileName")
                System.load(engineFile.absolutePath)
                loadedLibs.add(engineFileName)
            } else {
                Log.i(TAG, "引擎已加载（跳过）: $engineFileName")
                appLogger.info(TAG, "引擎已加载（跳过）: $engineFileName")
            }
        }

        // 把 so 绝对路径传给 BlutterEngine，JNI 端用 dlopen(path, RTLD_NOLOAD)
        // 拿到正确 linker namespace 的 handle 再 dlsym
        return BlutterEngine(dartVersion, engineFile.absolutePath)
    }

    /**
     * 获取引擎目录（调试用）。
     */
    fun engineDirectory(): File = engineDir

    // ========== SONAME 符号链接准备 ==========

    /**
     * 为 lib/ 目录下每个 .so 创建符号链接，确保 linker 能解析带版本号的依赖名。
     *
     * 两类链接：
     * 1. SONAME → 文件名（如 libicuuc.so 的 SONAME 是 libicuuc.so.73 → 创建 .so.73 链接）
     * 2. DT_NEEDED → 基础文件名（libicuuc.so 声明 NEEDED libicudata.so.73，
     *    而 libicudata.so 的 SONAME 是不带版本号的 libicudata.so —— 单靠 SONAME 规则
     *    不会生成 .so.73 链接，必须按 NEEDED 补齐）
     *
     * 幂等：已存在的 symlink 不会重建。
     */
    private fun prepareVersionedSymlinks() {
        val libDir = File(engineDir, "lib")
        if (!libDir.isDirectory) return

        val soFiles = libDir.listFiles { f -> f.isFile && f.name.endsWith(".so") } ?: return

        // 1) SONAME → 文件名
        for (soFile in soFiles) {
            val soname = readElfSoname(soFile) ?: continue
            if (soname == soFile.name) continue
            createSymlinkIfNeeded(libDir, soname, soFile)
        }

        // 2) DT_NEEDED → 基础文件名
        for (soFile in soFiles) {
            val needed = readElfNeeded(soFile) ?: emptyList()
            for (need in needed) {
                if (!need.startsWith("lib") || !need.endsWith(".so")) continue
                if (File(libDir, need).exists()) continue
                // "libicudata.so.73" → 基础名 "libicudata.so"
                val baseName = need.substringBeforeLast(".so") + ".so"
                val target = soFiles.firstOrNull { it.name == baseName }
                    ?: soFiles.firstOrNull { it.name == need }
                    ?: continue
                createSymlinkIfNeeded(libDir, need, target)
            }
        }
    }

    private fun createSymlinkIfNeeded(libDir: File, linkName: String, target: File) {
        val linkFile = File(libDir, linkName)
        if (linkFile.exists()) return
        try {
            Os.symlink(target.absolutePath, linkFile.absolutePath)
            Log.i(TAG, "symlink: ${linkFile.name} → ${target.name}")
        } catch (e: Exception) {
            Log.w(TAG, "创建 symlink 失败 ${linkFile.name}: ${e.message}")
        }
    }

    /** ELF .dynamic 段信息：dynstr 字符串表 + (tag, value) 条目。 */
    private data class DynamicInfo(
        val dynstr: ByteArray,
        val entries: List<Pair<Long, Long>>
    )

    /**
     * 读取 ELF64 的 .dynamic 段（tag/value 列表 + .dynstr 字符串表）。
     */
    private fun readDynamicInfo(file: File): DynamicInfo? {
        if (!file.exists() || file.length() < 64) return null
        try {
            RandomAccessFile(file, "r").use { raf ->
                // ---- ELF header (64 bytes for ELF64) ----
                val header = ByteArray(64)
                raf.readFully(header)
                // magic: 7F 45 4C 46
                if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                    header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
                ) return null
                // EI_CLASS = 2 (ELFCLASS64)
                if (header[4] != 2.toByte()) return null

                val eShoff = readLongLE(header, 0x28)
                val eShentsize = readShortLE(header, 0x3a).toInt()
                val eShnum = readShortLE(header, 0x3c).toInt()
                if (eShoff == 0L || eShnum == 0 || eShentsize < 56) return null

                // ---- section headers ----
                data class ShEntry(val type: Int, val offset: Long, val size: Long, val link: Int)
                val sections = ArrayList<ShEntry>(eShnum)
                val sh = ByteArray(eShentsize)
                for (i in 0 until eShnum) {
                    raf.seek(eShoff + i.toLong() * eShentsize)
                    raf.readFully(sh, 0, eShentsize)
                    sections.add(
                        ShEntry(
                            readIntLE(sh, 4),
                            readLongLE(sh, 24),
                            readLongLE(sh, 32),
                            readIntLE(sh, 40)
                        )
                    )
                }

                // .dynamic = SHT_DYNAMIC (6)
                val dynSec = sections.firstOrNull { it.type == 6 } ?: return null
                if (dynSec.link >= sections.size) return null
                val strSec = sections[dynSec.link]

                // 读 .dynstr
                raf.seek(strSec.offset)
                val dynstr = ByteArray(strSec.size.toInt())
                raf.readFully(dynstr)

                // 读 .dynamic 条目
                raf.seek(dynSec.offset)
                val dyn = ByteArray(dynSec.size.toInt())
                raf.readFully(dyn)

                val entries = mutableListOf<Pair<Long, Long>>()
                var i = 0
                while (i + 16 <= dyn.size) {
                    val tag = readLongLE(dyn, i)
                    val value = readLongLE(dyn, i + 8)
                    entries.add(tag to value)
                    if (tag == 0L) break // DT_NULL 结束
                    i += 16
                }
                return DynamicInfo(dynstr, entries)
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取动态段失败 ${file.name}: ${e.message}")
        }
        return null
    }

    /**
     * 读取 ELF64 文件的 SONAME（DT_SONAME，tag=14）。
     */
    private fun readElfSoname(file: File): String? {
        val info = readDynamicInfo(file) ?: return null
        val value = info.entries.firstOrNull { it.first == 14L }?.second ?: return null
        return readString(info.dynstr, value)
    }

    /**
     * 读取 ELF64 文件的 DT_NEEDED 依赖列表（tag=1）。
     */
    private fun readElfNeeded(file: File): List<String>? {
        val info = readDynamicInfo(file) ?: return null
        return info.entries.filter { it.first == 1L }
            .mapNotNull { (_, v) -> readString(info.dynstr, v) }
            .filter { it.isNotEmpty() }
    }

    /** 从 dynstr 按偏移读 C 字符串。 */
    private fun readString(dynstr: ByteArray, offset: Long): String? {
        if (offset < 0 || offset >= dynstr.size) return null
        val off = offset.toInt()
        val end = (off until dynstr.size).firstOrNull { dynstr[it] == 0.toByte() } ?: dynstr.size
        return String(dynstr, off, end - off, Charsets.US_ASCII)
    }

    private fun readLongLE(b: ByteArray, off: Int): Long {
        return (b[off].toLong() and 0xff) or
            ((b[off + 1].toLong() and 0xff) shl 8) or
            ((b[off + 2].toLong() and 0xff) shl 16) or
            ((b[off + 3].toLong() and 0xff) shl 24) or
            ((b[off + 4].toLong() and 0xff) shl 32) or
            ((b[off + 5].toLong() and 0xff) shl 40) or
            ((b[off + 6].toLong() and 0xff) shl 48) or
            ((b[off + 7].toLong() and 0xff) shl 56)
    }

    private fun readIntLE(b: ByteArray, off: Int): Int {
        return (b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8) or
            ((b[off + 2].toInt() and 0xff) shl 16) or
            ((b[off + 3].toInt() and 0xff) shl 24)
    }

    private fun readShortLE(b: ByteArray, off: Int): Short {
        return (((b[off].toInt() and 0xff) or
            ((b[off + 1].toInt() and 0xff) shl 8)) and 0xffff).toShort()
    }
}

/**
 * 引擎未就绪异常。
 */
class EngineNotReadyException(dartVersion: String) :
    IllegalStateException("引擎版本 $dartVersion 未就绪，请先下载引擎包")
