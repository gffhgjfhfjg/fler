package com.ai.fler.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DartFunctionBoundary：真实函数边界判定的纯逻辑单测（不触 JNI）。 */
class DartFunctionBoundaryTest {

    @Test
    fun `ret 为标准方法结束`() {
        assertTrue(DartFunctionBoundary.isMethodEnd("ret", "", 0x1000, 0x2000))
        assertTrue(DartFunctionBoundary.isMethodEnd("brk", "#0", 0x1000, 0x2000))
    }

    @Test
    fun `尾调用 b 目标大于当前地址算结束`() {
        assertTrue(DartFunctionBoundary.isMethodEnd("b", "0x1500", 0x1000, 0x2000))
        assertTrue(DartFunctionBoundary.isMethodEnd("b", "#0x1500", 0x1000, 0x2000))
    }

    @Test
    fun `循环回跳不算结束`() {
        assertFalse(DartFunctionBoundary.isMethodEnd("b", "0x900", 0x1000, 0x2000))
        assertFalse(DartFunctionBoundary.isMethodEnd("b", "#0x900", 0x1000, 0x2000))
    }

    @Test
    fun `越过下一方法起点交兜底`() {
        assertFalse(DartFunctionBoundary.isMethodEnd("ret", "", 0x2100, 0x2000))
        assertFalse(DartFunctionBoundary.isMethodEnd("b", "0x2200", 0x2100, 0x2000))
    }

    @Test
    fun `其他指令不算结束`() {
        assertFalse(DartFunctionBoundary.isMethodEnd("mov", "x0, #0", 0x1000, 0x2000))
        assertFalse(DartFunctionBoundary.isMethodEnd("bl", "0x1500", 0x1000, 0x2000))
        assertFalse(DartFunctionBoundary.isMethodEnd("ldr", "x0, [x27, #0x10]", 0x1000, 0x2000))
    }

    @Test
    fun `parseImm 解析 hex 目标`() {
        assertEquals(0x1500L, DartFunctionBoundary.parseImm("0x1500"))
        assertEquals(0x1500L, DartFunctionBoundary.parseImm("#0x1500"))
        assertNull(DartFunctionBoundary.parseImm("lr"))
        assertNull(DartFunctionBoundary.parseImm(""))
        assertNull(DartFunctionBoundary.parseImm(null))
    }
}