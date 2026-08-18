package com.ai.fler.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MethodCfg：方法级 CFG 的纯逻辑判定单测（不触 JNI）。 */
class MethodCfgTest {

    @Test
    fun `尾调用目标在方法体外`() {
        // 方法 [0x1000, 0x1100)，目标 0x1117c08（公共返回 stub，低于 start）→ 尾调用
        assertTrue(MethodCfg.isTailCallTarget(0x1117c08L, 0x12be2c0L, 0x12be3e8L))
        // 目标高于 bodyHi → 尾调用
        assertTrue(MethodCfg.isTailCallTarget(0x12be400L, 0x12be2c0L, 0x12be3e8L))
    }

    @Test
    fun `体内跳转不是尾调用`() {
        // 循环回跳（目标在方法内）→ 不是尾调用
        assertFalse(MethodCfg.isTailCallTarget(0x12be300L, 0x12be2c0L, 0x12be3e8L))
        // 目标等于 bodyHi 边界外 → 尾调用
        assertTrue(MethodCfg.isTailCallTarget(0x12be3e8L, 0x12be2c0L, 0x12be3e8L))
        // null 目标（非 b 指令）→ 不是
        assertFalse(MethodCfg.isTailCallTarget(null, 0x12be2c0L, 0x12be3e8L))
    }

    @Test
    fun `parseImm 解析`() {
        assertEquals(0x1117c08L, MethodCfg.parseImm("#0x1117c08"))
        assertEquals(0x1117c08L, MethodCfg.parseImm("0x1117c08"))
        assertNull(MethodCfg.parseImm("lr"))
        assertNull(MethodCfg.parseImm(""))
        assertNull(MethodCfg.parseImm(null))
    }
}