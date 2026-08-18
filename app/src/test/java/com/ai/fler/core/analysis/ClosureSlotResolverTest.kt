package com.ai.fler.core.analysis

import com.ai.fler.data.entity.PpEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ClosureSlotResolver：AnonymousClosure 槽解析纯逻辑单测（不触 JNI）。 */
class ClosureSlotResolverTest {

    private fun slot(vmOffset: Long, value: String) = PpEntry(
        id = 0, methodId = 0, analysisId = 0, vmOffset = vmOffset,
        fileOffset = 0, description = value, type = "[pp+0x${vmOffset.toString(16)}] AnonymousClosure",
    )

    @Test
    fun `解析 of 形态 - 类名归属`() {
        val c = ClosureSlotResolver.parse(slot(0x91ef8, "(0x90f900), of [zuq] Ofa"))
        assertTrue(c != null)
        c!!.let {
            assertEquals(0x90f900L, it.closureVaddr)
            assertEquals("Ofa", it.owner)
            assertEquals("zuq", it.library)
            assertNull(it.parentVaddr)
            assertFalse(it.isStatic)
            assertEquals("Ofa::<closure>", it.displayName)
        }
    }

    @Test
    fun `解析 of 形态 - 库名归属回退`() {
        val c = ClosureSlotResolver.parse(slot(0x260, "static (0x6a93f0), of [Qpq] "))
        assertTrue(c != null)
        c!!.let {
            assertEquals(0x6a93f0L, it.closureVaddr)
            assertEquals("Qpq", it.library)
            assertEquals("", it.owner)
            assertTrue(it.isStatic)
            assertEquals("Qpq::<closure>", it.displayName)
        }
    }

    @Test
    fun `解析 in 形态 - 父方法 anonymous closure`() {
        val c = ClosureSlotResolver.parse(slot(0x1270, "(0x11f10dc), in [package:flutter/src/services/platform_channel.dart] sma::<anonymous closure> (0x11f0f6c)"))
        assertTrue(c != null)
        c!!.let {
            assertEquals(0x11f10dcL, it.closureVaddr)
            assertEquals("sma", it.owner)
            assertEquals(0x11f0f6cL, it.parentVaddr)
            assertEquals("package:flutter/src/services/platform_channel.dart", it.library)
            assertEquals("sma::<closure>", it.displayName)
        }
    }

    @Test
    fun `解析 in 形态 - 命名方法内闭包`() {
        val c = ClosureSlotResolver.parse(slot(0x6b0, "static (0x66b560), in [dart:async] _Future::Can (0x11152b0)"))
        assertTrue(c != null)
        c!!.let {
            assertEquals(0x66b560L, it.closureVaddr)
            assertEquals("_Future", it.owner)
            assertEquals(0x11152b0L, it.parentVaddr)
            assertEquals("dart:async", it.library)
            assertTrue(it.isStatic)
        }
    }

    @Test
    fun `非闭包槽返回 null`() {
        val f = PpEntry(id = 0, methodId = 0, analysisId = 0, vmOffset = 0x10, fileOffset = 0,
            description = "", type = "[pp+0x10] Sentinel")
        assertNull(ClosureSlotResolver.parse(f))
        val str = PpEntry(id = 0, methodId = 0, analysisId = 0, vmOffset = 0x40, fileOffset = 0,
            description = "[pp+0x40] \"VIP\"", type = "")
        assertNull(ClosureSlotResolver.parse(str))
    }

    @Test
    fun `parseAll 过滤 + buildVaddrMap 去重`() {
        val entries = listOf(
            slot(0x10, "(0x6ac868), of [Dpq] vK"),
            slot(0x20, "(0x6ac868), of [Dpq] vK"),
            PpEntry(id = 0, methodId = 0, analysisId = 0, vmOffset = 0x30, fileOffset = 0,
                description = "[pp+0x30] List(5)", type = ""),
        )
        val slots = ClosureSlotResolver.parseAll(entries)
        assertEquals(2, slots.size)
        val map = ClosureSlotResolver.buildVaddrMap(slots)
        assertEquals(1, map.size)
        assertTrue(map.containsKey(0x6ac868L))
    }
}