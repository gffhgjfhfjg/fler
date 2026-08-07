package com.ai.fler.core.analysis.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RizinJsonParser 边界/容错回归测试：缺失字段、类型缺失、非对象元素、脏 hex 等。
 */
class RizinJsonParserEdgeTest {

    @Test
    fun `parseSymbols 缺字段时回退默认值`() {
        val symbols = RizinJsonParser.parseSymbols("""[{"name":"s","vaddr":100}]""")
        assertEquals(1, symbols.size)
        assertEquals(0L, symbols[0].size)
        assertEquals(0, symbols[0].shndx)
        assertEquals("", symbols[0].sectionName)
    }

    @Test
    fun `parseSymbols paddr 缺失或为 0 回退 vaddr`() {
        // paddr=0（UND/导入）→ 回退 vaddr
        val a = RizinJsonParser.parseSymbols("""[{"name":"s","vaddr":999,"paddr":0}]""")[0]
        assertEquals(999L, a.paddr)
        // 无 paddr → 回退 addr
        val b = RizinJsonParser.parseSymbols("""[{"name":"s","addr":123}]""")[0]
        assertEquals(123L, b.paddr)
    }

    @Test
    fun `parseSymbols 混合非对象元素时安全返回空列表不抛异常`() {
        // Rizin 输出同质；混入非对象元素时整体安全失败（空列表），绝不抛出
        val symbols = RizinJsonParser.parseSymbols("""[{"name":"a"}, 42, "x", null, {"name":"b"}]""")
        assertTrue(symbols.isEmpty())
    }

    @Test
    fun `parseDisassembly 脏 hex 安全返回空列表不抛异常`() {
        // "0nothex" 非法 hex → 整体绑定 try 捕获 → 返回空，绝不把崩溃漏给调用方
        val insns = RizinJsonParser.parseDisassembly("""[{"offset": 0, "size": 4, "bytes": "0nothex", "mnemonic": "nop"}]""")
        assertTrue(insns.isEmpty())
    }

    @Test
    fun `parseFunctions 空数组返回空列表`() {
        assertTrue(RizinJsonParser.parseFunctions("[]").isEmpty())
        assertTrue(RizinJsonParser.parseSections("[]").isEmpty())
        assertTrue(RizinJsonParser.parseStrings("[]").isEmpty())
        assertTrue(RizinJsonParser.parseXrefs("[]", isFrom = false).isEmpty())
    }

    @Test
    fun `parseFileInfo 无 bin 子对象时读顶层字段`() {
        val info = RizinJsonParser.parseFileInfo(
            """{"arch":"x86","bits":32,"machine":"Intel 80386","nx":true}""",
            fileSize = 10,
        )
        assertEquals("x86", info?.arch)
        assertEquals(32, info?.bits)
        assertEquals("Intel 80386", info?.machine)
        assertEquals(10L, info?.fileSize)
    }

    @Test
    fun `parseFileInfo 整数字段缺失回退 0`() {
        val info = RizinJsonParser.parseFileInfo("""{"nx":true}""", fileSize = 5)
        assertEquals(0, info?.bits)
        assertEquals(5L, info?.fileSize)
    }

    @Test
    fun `字符串与数组混用非法输入返回安全空值`() {
        assertNull(RizinJsonParser.parseFileInfo("oops", fileSize = 0))
        assertEquals(0, RizinJsonParser.parseSymbols("oops").size)
        assertEquals(0, RizinJsonParser.parseFunctions("null").size)
    }
}