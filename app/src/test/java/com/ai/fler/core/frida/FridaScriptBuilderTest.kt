package com.ai.fler.core.frida

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frida 模板生成单测：验证 trimMargin 管道符剥离、vaddr/模块/标签转义、
 * Dart 解码器注入开关，以及脚本关键控制流存在性。
 */
class FridaScriptBuilderTest {

    private fun hook(
        vaddr: Long = 0xA5F0B0,
        label: String = "User.isVIP",
        decodeDart: Boolean = true,
    ): String = FridaScriptBuilder.hookNative("libapp.so", vaddr, label, decodeDart)

    @Test
    fun `模板无残留管道符前缀`() {
        val js = hook()
        val leftovers = js.lines().filter { it.trimStart().startsWith("|") }
        assertTrue("残留管道符行: ${leftovers.joinToString("\\n")}", leftovers.isEmpty())
    }

    @Test
    fun `vaddr 十六进制拼接正确`() {
        val js = hook(vaddr = 0xA5F0B0)
        assertTrue(js.contains("add(0xa5f0b0)"))
    }

    @Test
    fun `label 单引号被转义且注入`() {
        val js = hook(label = "a'b")
        assertTrue(js.contains("attaching a\\'b"))
    }

    @Test
    fun `模块名子串匹配与模块缺失分支存在`() {
        val js = hook()
        assertTrue(js.contains("indexOf('libapp.so')"))
        assertTrue(js.contains("not loaded"))
    }

    @Test
    fun `decodeDart 开关切换解码器注入`() {
        val on = hook(decodeDart = true)
        val off = hook(decodeDart = false)
        assertTrue(on.contains("decodeDartString"))
        assertFalse(on.contains("function describeDartArg(p) { try { return String(p); }"))
        assertFalse("关闭解码时不应注入 decodeDartString", off.contains("function decodeDartString"))
    }

    @Test
    fun `onEnter 收集前 8 个参数且 enter-leave 事件齐全`() {
        val js = hook()
        assertTrue(js.contains("for (let i = 0; i < 8; i++)"))
        assertTrue(js.contains("send({ type: 'enter', method:"))
        assertTrue(js.contains("send({ type: 'leave', method:"))
    }

    @Test
    fun `hookTemplateSource 含待填参数块与解码器`() {
        val src = FridaScriptBuilder.hookTemplateSource()
        assertTrue(src.contains("MODULE_TPL"))
        assertTrue(src.contains("VADDR_TPL"))
        assertTrue(src.contains("LABEL_TPL"))
        assertTrue(src.contains("decodeDartString"))
        assertTrue(src.contains("indexOf(MODULE_TPL)"))
        assertFalse(src.lines().any { it.trimStart().startsWith("|") })
    }

    @Test
    fun `bootstrapScan 模板输出完整`() {
        val src = FridaScriptBuilder.bootstrapScan()
        assertTrue(src.contains("Process.enumerateModules()"))
        assertTrue(src.contains("bootstrap"))
    }

    @Test
    fun `fillTemplate 替换三个占位符`() {
        val src = FridaScriptBuilder.hookTemplateSource()
        val filled = FridaScriptBuilder.fillTemplate(
            source = src,
            module = "libapp.so",
            vaddr = 0xA5F0B0,
            label = "User.isVIP",
        )
        assertTrue(filled.contains("const MODULE_TPL = 'libapp.so';"))
        assertTrue(filled.contains("const VADDR_TPL = 0xa5f0b0;"))
        assertTrue(filled.contains("const LABEL_TPL = 'User.isVIP';"))
        assertTrue(!filled.contains("const VADDR_TPL = 0x00000000;"))
    }

    @Test
    fun `fillTemplate 只替换显式参数`() {
        val src = FridaScriptBuilder.hookTemplateSource()
        val filled = FridaScriptBuilder.fillTemplate(source = src, vaddr = 0x1234)
        assertTrue(filled.contains("const VADDR_TPL = 0x1234;"))
        assertTrue(filled.contains("const MODULE_TPL = 'libapp.so';"))
        assertTrue(filled.contains("const LABEL_TPL = 'MyApp.method';"))
    }

    @Test
    fun `fillTemplate 对自定义脚本安全无副作用`() {
        val arbitrary = "(function () { send('hi'); })();"
        assertEquals(arbitrary, FridaScriptBuilder.fillTemplate(arbitrary, module = "x", vaddr = 5L, label = "y"))
    }

    @Test
    fun `fillTemplate 转义 module 与 label 中的单引号`() {
        val src = FridaScriptBuilder.hookTemplateSource()
        val filled = FridaScriptBuilder.fillTemplate(source = src, module = "a'b.so", label = "l'bl")
        assertTrue(filled.contains("const MODULE_TPL = 'a\\'b.so';"))
        assertTrue(filled.contains("const LABEL_TPL = 'l\\'bl';"))
    }
}