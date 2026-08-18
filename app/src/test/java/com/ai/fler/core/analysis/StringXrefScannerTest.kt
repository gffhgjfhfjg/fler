package com.ai.fler.core.analysis

import com.ai.fler.core.analysis.StringXrefScanner.TextRange
import com.ai.fler.core.jni.DisasmInstruction
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * StringXrefScanner 双指令（add+ldr）大槽匹配单测。
 *
 * 覆盖三种 pool 加载形态：
 * 1. 单指令小槽：`ldr x1, [x27, #0x57b0]`
 * 2. 双指令大槽：`add x2, x27, #0x1f, lsl #12` + `ldr x2, [x2, #0x4e0]`（pp = 0x1f4e0）
 * 3. 双指令 x26 基址：`add x9, x26, #0x62, lsl #12` + `ldr x9, [x9, #0xcd8]`（pp = 0x62cd8）
 *
 * 及干扰项过滤：
 * - 堆字段：`add x1, x1, x28, lsl #32` + `ldur x1, [x1, #0x17]`（非 pool 形态，不命中）
 * - 非池基址：`ldr x0, [x0, #8]`（base 非 poolRegs，不命中）
 * - 双指令但 ldr 基址不匹配 add 目标：`add x2, x27, #0x1f, lsl #12` + `ldr x3, [x3, #0x4e0]`
 */
class StringXrefScannerTest {

    private fun scanner() = StringXrefScanner(functionIndex = mockk(relaxed = true))

    private val text = TextRange(
        fileOffset = 0x650000L,
        fileSize = 13517072L,
        vaddrBase = 0x650000L,
    )

    /** 经反射调用 private processBlock。 */
    private fun runProcess(
        scanner: StringXrefScanner,
        insns: List<DisasmInstruction>,
        targets: Set<Long>,
        poolRegs: List<String> = StringXrefScanner.DEFAULT_POOL_REGS,
    ): Map<Long, StringXrefScanner.Hit> {
        val method = StringXrefScanner::class.java.getDeclaredMethod(
            "processBlock",
            List::class.java,
            TextRange::class.java,
            FunctionIndex.Snapshot::class.java,
            Set::class.java,
            List::class.java,
            LinkedHashMap::class.java,
            StringXrefScanner.PendingAdd::class.java,
        )
        method.isAccessible = true
        val out = LinkedHashMap<Long, StringXrefScanner.Hit>()
        val pending = method.invoke(scanner, insns, text, null, targets, poolRegs, out, null)
        // 忽略返回的 pending（单块测试内无跨块需求）
        assertEquals(null, pending)
        return out
    }

    private fun ins(address: Long, mnemonic: String, opStr: String) =
        DisasmInstruction(address = address, size = 4, mnemonic = mnemonic, opStr = opStr, bytes = ByteArray(4))

    @Test
    fun `单指令小槽命中`() {
        val s = scanner()
        val hits = runProcess(
            s,
            listOf(ins(0x657000, "ldr", "x1, [x27, #0x57b0]")),
            setOf(0x57b0L),
        )
        assertEquals(1, hits.size)
        val h = hits.values.first()
        assertEquals(0x657000L, h.siteVaddr)
        assertEquals(0x57b0L, h.ppOffset)
    }

    @Test
    fun `双指令大槽 add+ldr 命中`() {
        val s = scanner()
        val hits = runProcess(
            s,
            listOf(
                ins(0x657008, "add", "x2, x27, #0x1f, lsl #12"),
                ins(0x65700c, "ldr", "x2, [x2, #0x4e0]"),
            ),
            setOf(0x1f4e0L),
        )
        assertEquals(1, hits.size)
        val h = hits.values.first()
        // site 归属到 add 指令地址
        assertEquals(0x657008L, h.siteVaddr)
        assertEquals(0x1f4e0L, h.ppOffset)
    }

    @Test
    fun `双指令 x26 基址命中`() {
        val s = scanner()
        val hits = runProcess(
            s,
            listOf(
                ins(0x657010, "add", "x9, x26, #0x62, lsl #12"),
                ins(0x657014, "ldr", "x9, [x9, #0xcd8]"),
            ),
            setOf(0x62cd8L),
            poolRegs = listOf("x27", "x26"),
        )
        assertEquals(1, hits.size)
        assertEquals(0x62cd8L, hits.values.first().ppOffset)
    }

    @Test
    fun `目标槽非匹配集合不命中`() {
        val s = scanner()
        val hits = runProcess(
            s,
            listOf(
                ins(0x657008, "add", "x2, x27, #0x1f, lsl #12"),
                ins(0x65700c, "ldr", "x2, [x2, #0x4e0]"),
            ),
            setOf(0x12345L), // 目标不含 0x1f4e0
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `堆字段形态不命中`() {
        val s = scanner()
        val hits = runProcess(
            s,
            listOf(
                ins(0x657020, "add", "x1, x1, x28, lsl #32"),
                ins(0x657024, "ldur", "x1, [x1, #0x17]"),
            ),
            setOf(0x17L, 0x1f4e0L),
        )
        // 堆字段 ldur 不是 ldr，add 源非池基址 → 0 命中
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `非池基址直接 ldr 不命中`() {
        val s = scanner()
        val hits = runProcess(
            s,
            listOf(ins(0x657030, "ldr", "x0, [x0, #8]")),
            setOf(8L),
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `双指令 ldr 基址与 add 目标不符不命中`() {
        val s = scanner()
        val hits = runProcess(
            s,
            listOf(
                ins(0x657040, "add", "x2, x27, #0x1f, lsl #12"),
                ins(0x657044, "ldr", "x3, [x3, #0x4e0]"),
            ),
            setOf(0x1f4e0L),
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `add 无 lsl12 不命中`() {
        val s = scanner()
        val hits = runProcess(
            s,
            listOf(
                ins(0x657050, "add", "x2, x27, #0x1f"),
                ins(0x657054, "ldr", "x2, [x2, #0x4e0]"),
            ),
            setOf(0x1f4e0L),
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun `多形态混合扫描`() {
        val s = scanner()
        val hits = runProcess(
            s,
            listOf(
                ins(0x657060, "ldr", "x1, [x27, #0x57b0]"),          // 命中 0x57b0
                ins(0x657064, "add", "x2, x27, #0x1f, lsl #12"),
                ins(0x657068, "ldr", "x2, [x2, #0x4e0]"),             // 命中 0x1f4e0
                ins(0x65706c, "add", "x1, x1, x28, lsl #32"),        // 堆字段，不命中
                ins(0x657070, "ldur", "x1, [x1, #0x17]"),
            ),
            setOf(0x57b0L, 0x1f4e0L),
        )
        assertEquals(2, hits.size)
        val pps = hits.values.map { it.ppOffset }.sorted()
        assertEquals(listOf(0x57b0L, 0x1f4e0L), pps)
    }
}