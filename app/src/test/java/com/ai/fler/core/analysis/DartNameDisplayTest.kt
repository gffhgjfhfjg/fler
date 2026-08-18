package com.ai.fler.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** DartNameDisplay：混淆方法显示层 sub_<vaddr> 命名的纯逻辑单测。 */
class DartNameDisplayTest {

    @Test
    fun `占位方法名识别`() {
        assertTrue(DartNameDisplay.isPlaceholder("<anonymous closure>"))
        assertTrue(DartNameDisplay.isPlaceholder("<unknown>"))
        assertTrue(DartNameDisplay.isPlaceholder(""))
        assertFalse(DartNameDisplay.isPlaceholder("_fetchList"))
        assertFalse(DartNameDisplay.isPlaceholder("nhe"))
        assertFalse(DartNameDisplay.isPlaceholder(null))
    }

    @Test
    fun `sub 名生成`() {
        assertEquals("sub_10075112", DartNameDisplay.displayMethodName("<anonymous closure>", 0x10075112L))
        assertEquals("sub_10075112", DartNameDisplay.displayMethodName("<unknown>", 0x10075112L))
        assertEquals("sub_10075112", DartNameDisplay.displayMethodName("", 0x10075112L))
        // 非占位名原样返回
        assertEquals("nhe", DartNameDisplay.displayMethodName("nhe", 0x10075112L))
    }

    @Test
    fun `vaddr 缺失时退化保留原名`() {
        assertEquals("<anonymous closure>", DartNameDisplay.displayMethodName("<anonymous closure>", null))
        assertEquals("<anonymous closure>", DartNameDisplay.displayMethodName("<anonymous closure>", 0L))
    }

    @Test
    fun `fullName 保留有效类名`() {
        assertEquals("_hba.sub_10075112", DartNameDisplay.displayFullName("_hba", "<anonymous closure>", 0x10075112L))
        // 类名占位时省略类前缀
        assertEquals("sub_10075112", DartNameDisplay.displayFullName("<unknown>", "<anonymous closure>", 0x10075112L))
        assertEquals("sub_10075112", DartNameDisplay.displayFullName("", "<anonymous closure>", 0x10075112L))
        // 非占位方法名：与原始 类.方法 一致
        assertEquals("_hba.nhe", DartNameDisplay.displayFullName("_hba", "nhe", 0x10075112L))
    }

    @Test
    fun `parseSubName 解析 hex 目标`() {
        assertEquals(0x10075112L, DartNameDisplay.parseSubName("sub_10075112"))
        assertEquals(0x10075112L, DartNameDisplay.parseSubName("sub_0x10075112"))
        assertEquals(0x10075112L, DartNameDisplay.parseSubName("SUB_0X10075112"))
        assertEquals(0x10075112L, DartNameDisplay.parseSubName("_hba.sub_10075112"))
        assertEquals(0x10075112L, DartNameDisplay.parseSubName("  sub_10075112  "))
    }

    @Test
    fun `parseSubName 非 sub 形态返回 null`() {
        assertNull(DartNameDisplay.parseSubName("_fetchList"))
        assertNull(DartNameDisplay.parseSubName("nhe"))
        assertNull(DartNameDisplay.parseSubName("<anonymous closure>"))
        assertNull(DartNameDisplay.parseSubName(""))
        assertNull(DartNameDisplay.parseSubName(null))
    }
}