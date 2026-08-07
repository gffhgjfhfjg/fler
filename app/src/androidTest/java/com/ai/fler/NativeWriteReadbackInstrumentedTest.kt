package com.ai.fler

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.fler.core.jni.ElfParserBindings
import com.ai.fler.core.jni.NativeLoader
import com.ai.fler.core.jni.RizinBindings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 原生层 写→读回 回归测试。
 *
 * 针对本次性能优化：Rizin/elf_parser 的 mmap 缓存 + readback 一致性是改动重点。
 * 这里用真实 arm64 设备上的 libemu_demo.so 验证：
 *  - writeBytes 落盘后 readBytes 读到一致的新字节（而非旧的 mmap 缓存/脏读）
 *  - ElfParserBindings 写后读回一致
 *  - 恢复原字节后再次读回一致，确保测试不污染
 *
 * 运行：gradlew :app:connectedDebugAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class NativeWriteReadbackInstrumentedTest {

    companion object {
        private const val ASSET = "libemu_demo.so"
        // ELF header padding（偏移 0x10，安全可写区域）
        private const val PATCH_OFFSET = 0x10L
        private val PATCH_BYTES = byteArrayOf(0x11, 0x22, 0x33, 0x44)
    }

    private lateinit var workDir: File
    private lateinit var target: File
    private lateinit var second: File

    @Before
    fun setUp() {
        NativeLoader.load()
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        workDir = File(ctx.filesDir, "rw_test").apply { mkdirs() }
        target = File(workDir, "target.so")
        second = File(workDir, "second.so")
        extractAssetTo(ASSET, target)
        extractAssetTo(ASSET, second)
    }

    private fun extractAssetTo(name: String, dest: File) {
        val ic = InstrumentationRegistry.getInstrumentation().context
        ic.assets.open(name).use { input ->
            input.copyTo(dest.outputStream())
        }
    }

    // ===== RizinBindings 写→读回 =====

    @Test
    fun rizinWriteThenReadbackMatches() {
        val h = RizinBindings.open(target.absolutePath)
        assertTrue("Rizin open 应成功", h != 0L)
        try {
            val original = RizinBindings.readBytes(h, PATCH_OFFSET, PATCH_BYTES.size)
            assertNotNull("read Bytes 非空", original)

            assertTrue("writeBytes 应成功", RizinBindings.writeBytes(h, PATCH_OFFSET, PATCH_BYTES))
            val afterWrite = RizinBindings.readBytes(h, PATCH_OFFSET, PATCH_BYTES.size)
            assertNotNull("写后读回非空", afterWrite)
            assertArrayEquals("写后读回应等于写入内容（非脏读/缓存过期）", PATCH_BYTES, afterWrite)

            assertTrue("恢复原值应成功", RizinBindings.writeBytes(h, PATCH_OFFSET, original!!))
            val restored = RizinBindings.readBytes(h, PATCH_OFFSET, PATCH_BYTES.size)
            assertArrayEquals("恢复后应等于原字节", original, restored)
        } finally {
            RizinBindings.close(h)
        }
    }

    @Test
    fun rizinOpenFileIsSameAcrossSessions() {
        val h1 = RizinBindings.open(target.absolutePath)
        val h2 = RizinBindings.open(target.absolutePath)
        assertTrue("两次 open 均应成功", h1 != 0L && h2 != 0L)
        try {
            val v1 = RizinBindings.readBytes(h1, 0x20, 8)
            val v2 = RizinBindings.readBytes(h2, 0x20, 8)
            assertNotNull(v1)
            assertArrayEquals("不同句柄读同一文件应一致", v1, v2)
        } finally {
            RizinBindings.close(h1)
            RizinBindings.close(h2)
        }
    }

    @Test
    fun rizinAnalysisOnSampleLib() {
        val h = RizinBindings.open(target.absolutePath)
        assertTrue("open 应成功", h != 0L)
        try {
            val json = RizinBindings.cmdStr(h, "isj")
            assertNotNull("isj 应返回 JSON", json)
            // 该版本 rizin 的 isj 返回可识别的符号/函数（符号在 vaddr 层已重定位），
            // 校验能定位到 demo 库导出的真实函数。
            assertTrue(
                "isj 应含库导出的函数符号: ${json!!.take(300)}",
                json.contains("\"add\"") && json.contains("\"fib\"") && json.contains("\"hash_buf\"")
            )
        } finally {
            RizinBindings.close(h)
        }
    }

    // ===== ElfParserBindings 写→读回 =====

    @Test
    fun elfParserWriteThenReadbackMatches() {
        ElfParserBindings().use { elf ->
            assertTrue("ElfParser 打开应成功", elf.open(second.absolutePath))
            val original = elf.readBytes(PATCH_OFFSET, PATCH_BYTES.size.toLong())
            assertTrue("原始读取非空", original.isNotEmpty())

            assertTrue("写回应成功", elf.writeBytes(PATCH_OFFSET, PATCH_BYTES))
            val afterWrite = elf.readBytes(PATCH_OFFSET, PATCH_BYTES.size.toLong())
            assertArrayEquals("elf_parser 写后读回应一致", PATCH_BYTES, afterWrite)

            assertTrue("恢复原值应成功", elf.writeBytes(PATCH_OFFSET, original))
            val restored = elf.readBytes(PATCH_OFFSET, PATCH_BYTES.size.toLong())
            assertArrayEquals("恢复后应与原始字节一致", original, restored)
        }
    }

    @Test
    fun elfParserSectionsSymbolsLoadSegments() {
        ElfParserBindings().use { fp ->
            assertTrue("打开应成功", fp.open(second.absolutePath))
            val sections = fp.getSections()
            assertTrue("应有节区", sections.isNotEmpty())
            assertTrue("含 .text 与 .symtab", sections.any { it.name == ".text" } && sections.any { it.name == ".symtab" })

            val symbols = fp.getSymbols()
            val dynSymbols = fp.getDynamicSymbols()
            assertTrue("有静态或动态符号", symbols.isNotEmpty() || dynSymbols.isNotEmpty())

            val segments = fp.getLoadSegments()
            assertTrue("应有 PT_LOAD 段", segments.isNotEmpty())
            assertTrue("至少一个可执行段", segments.any { it.isExecutable })

            // 共享库 e_entry 为 0（无独立入口），仅需能成功读取
            assertTrue("入口读取不应失败", fp.getEntry() >= 0L)
        }
    }
}