package com.ai.fler

import com.ai.fler.core.analysis.SymbolBind
import com.ai.fler.core.analysis.SymbolType
import com.ai.fler.core.analysis.XrefType
import com.ai.fler.core.analysis.engine.RizinJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rizin JSON 输出解析器单元测试（字段取用与容错行为）。
 */
class RizinJsonParserTest {

    @Test
    fun `parseSymbols 解析静态符号字段`() {
        val json = """
            [
              {"name": "sym.foo", "vaddr": 4096, "size": 16, "type": "FUNC", "bind": "GLOBAL", "section": ".text"},
              {"name": "sym.bar", "vaddr": 8192, "size": 0, "type": "OBJ", "bind": "LOCAL", "section": ".data"}
            ]
        """.trimIndent()
        val symbols = RizinJsonParser.parseSymbols(json)
        assertEquals(2, symbols.size)
        assertEquals("sym.foo", symbols[0].name)
        assertEquals(4096L, symbols[0].address)
        assertEquals(16L, symbols[0].size)
        assertEquals(SymbolType.FUNC, symbols[0].type)
        assertEquals(SymbolBind.GLOBAL, symbols[0].bind)
        assertEquals(".text", symbols[0].sectionName)
        assertEquals(SymbolType.OBJECT, symbols[1].type)
    }

    @Test
    fun `parseSymbols 兼容 addr 字段与 demangled 名称`() {
        val json = """
            [
              {"name": "_ZN3foo3barEv", "demangled": "foo::bar()", "addr": 1234, "type": "FUNC", "bind": "WEAK"}
            ]
        """.trimIndent()
        val symbols = RizinJsonParser.parseSymbols(json)
        assertEquals(1, symbols.size)
        assertEquals(1234L, symbols[0].address)
        assertEquals("foo::bar()", symbols[0].demangledName)
        assertEquals(SymbolBind.WEAK, symbols[0].bind)
    }

    @Test
    fun `parseSymbols 非法 JSON 返回空列表`() {
        assertTrue(RizinJsonParser.parseSymbols("not json").isEmpty())
        assertTrue(RizinJsonParser.parseSymbols("").isEmpty())
    }

    @Test
    fun `parseFunctions 解析函数基本信息`() {
        val json = """
            [
              {"name": "fcn.00001000", "offset": 4096, "addr": 4096, "size": 64,
               "nargs": 2, "nlocals": 1, "nbbs": 3, "calltype": "cdcel", "edges": 4, "signature": "int foo(int)"}
            ]
        """.trimIndent()
        val funcs = RizinJsonParser.parseFunctions(json)
        assertEquals(1, funcs.size)
        assertEquals("fcn.00001000", funcs[0].name)
        assertEquals(4096L, funcs[0].vaddr)
        assertEquals(64L, funcs[0].size)
        assertEquals(2, funcs[0].nargs)
        assertEquals("int foo(int)", funcs[0].signature)
    }

    @Test
    fun `parseDisassembly 解析指令并转换 bytes 为十六进制`() {
        val json = """
            [
              {"offset": 4096, "size": 4, "bytes": "0001c0d2", "opcode": "mov x0, #0", "disasm": "mov x0, #0"}
            ]
        """.trimIndent()
        val insns = RizinJsonParser.parseDisassembly(json)
        assertEquals(1, insns.size)
        assertEquals(4096L, insns[0].address)
        assertEquals(4, insns[0].size)
        assertEquals("mov", insns[0].mnemonic)
        assertEquals("x0, #0", insns[0].opStr)
        assertEquals(4, insns[0].bytes.size)
        assertEquals(0xD2.toByte(), insns[0].bytes[3])   // "d2" 是最后两个 hex 字符
    }

    @Test
    fun `parseStrings 解析字符串扫描结果`() {
        val json = """
            [
              {"string": "hello", "vaddr": 1000, "paddr": 500, "size": 6, "section": ".rodata"},
              {"string": "world", "vaddr": 2000, "paddr": 1500, "size": 6, "section": ".rodata"}
            ]
        """.trimIndent()
        val strings = RizinJsonParser.parseStrings(json)
        assertEquals(2, strings.size)
        assertEquals("hello", strings[0].string)
        assertEquals(1000L, strings[0].address)
        assertEquals(500L, strings[0].paddr)
        assertEquals(".rodata", strings[0].section)
    }

    @Test
    fun `parseFileInfo 从 ij 输出读取 bin 信息`() {
        val json = """
            {
              "bin": {"arch": "arm", "bits": 64, "endian": "little", "machine": "AArch64", "class": "ELFCLASS64", "os": "linux",
                       "canary": true, "nx": true, "pie": true, "relro": "full", "stripped": true}
            }
        """.trimIndent()
        val info = RizinJsonParser.parseFileInfo(json, fileSize = 12345)
        assertEquals("arm", info?.arch)
        assertEquals(64, info?.bits)
        assertEquals("AArch64", info?.machine)
        assertTrue(info?.canary == true)
        assertTrue(info?.pie == true)
        assertEquals("full", info?.relro)
        assertEquals(12345L, info?.fileSize)
    }

    @Test
    fun `parseFileInfo 非法 JSON 返回 null`() {
        assertNull(RizinJsonParser.parseFileInfo("oops", fileSize = 0))
    }

    @Test
    fun `parseXrefs 解析 axtj 输出`() {
        val json = """
            [
              {"from": 100, "to": 200, "type": "CALL", "perm": "x"},
              {"from": 300, "to": 200, "type": "DATA", "perm": "r"}
            ]
        """.trimIndent()
        val xrefs = RizinJsonParser.parseXrefs(json, isFrom = false)
        assertEquals(2, xrefs.size)
        assertEquals(100L, xrefs[0].from)
        assertEquals(200L, xrefs[0].to)
        assertEquals(XrefType.CALL, xrefs[0].type)
    }
}
