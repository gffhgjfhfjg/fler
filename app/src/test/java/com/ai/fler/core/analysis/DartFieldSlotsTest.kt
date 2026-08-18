package com.ai.fler.core.analysis

import com.ai.fler.data.entity.PpEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DartFieldSlots：pp_entries Field 槽解析纯逻辑单测（不触 JNI）。 */
class DartFieldSlotsTest {

    private fun slot(vmOffset: Long, type: String, desc: String) = PpEntry(
        id = 0, methodId = 0, analysisId = 0, vmOffset = vmOffset,
        fileOffset = 0, description = desc, type = type,
    )

    @Test
    fun `解析 owner 无 hash 的字段槽`() {
        val f = DartFieldSlots.parse(slot(0x91e78, "[pp+0x91e78] Field <Ofa._bvf@401245603>", "late (offset: 0x120)"))
        assertTrue(f != null)
        f!!.let {
            assertEquals(0x91e78L, it.vmOffset)
            assertEquals("Ofa", it.owner)
            assertEquals("Ofa", it.ownerPlain)
            assertEquals("_bvf", it.fieldName)
            assertEquals(0x120L, it.offset)
            assertFalse(it.isStatic); assertFalse(it.isFinal); assertTrue(it.isLate)
            assertEquals("Ofa._bvf", it.displayName)
            assertEquals("late", it.modifierText)
        }
    }

    @Test
    fun `解析 owner 带 hash 的字段槽`() {
        val f = DartFieldSlots.parse(slot(0x7d618, "[pp+0x7d618] Field <_Kya@334280878._bgf@334280878>", "late final (offset: 0x14)"))
        assertTrue(f != null)
        f!!.let {
            assertEquals("_Kya", it.ownerPlain)
            assertEquals("_bgf", it.fieldName)
            assertEquals(0x14L, it.offset)
            assertTrue(it.isLate); assertTrue(it.isFinal); assertFalse(it.isStatic)
            assertEquals("_Kya._bgf", it.displayName)
            assertEquals("final late", it.modifierText)
        }
    }

    @Test
    fun `解析静态字段槽`() {
        val f = DartFieldSlots.parse(slot(0x66ea8, "[pp+0x66ea8] Field <_LCb@2440358812._aLl@2440358812>", "static late final (offset: 0x213c)"))
        assertTrue(f != null)
        f!!.let {
            assertEquals("_LCb", it.ownerPlain)
            assertEquals("_aLl", it.fieldName)
            assertEquals(0x213cL, it.offset)
            assertTrue(it.isStatic); assertTrue(it.isFinal); assertTrue(it.isLate)
            assertEquals("static final late", it.modifierText)
        }
    }

    @Test
    fun `非 Field 槽返回 null`() {
        assertNull(DartFieldSlots.parse(slot(0x10, "[pp+0x10] Sentinel", "")))
        assertNull(DartFieldSlots.parse(slot(0x48, "[pp+0x48] List(5)", "")))
        assertNull(DartFieldSlots.parse(slot(0x20, "[pp+0x20] AnonymousClosure", "(0x90f900), of [zuq] Ofa")))
        assertNull(DartFieldSlots.parse(slot(0x30, "[pp+0x30] String", "\"VIP\"")))
    }

    @Test
    fun `字段名无 hash 后缀也解析`() {
        val f = DartFieldSlots.parse(slot(0x8fe58, "[pp+0x8fe58] Field <Dea.DUf>", "late final (offset: 0x10)"))
        assertTrue(f != null)
        f!!.let {
            assertEquals("Dea", it.ownerPlain)
            assertEquals("DUf", it.fieldName)
            assertEquals(0x10L, it.offset)
        }
    }

    @Test
    fun `ownerMatches 忽略大小写与 hash`() {
        val f = DartFieldSlots.parse(slot(0x7d618, "[pp+0x7d618] Field <_Kya@334280878._bgf@334280878>", "late final (offset: 0x14)"))!!
        assertTrue(DartFieldSlots.ownerMatches(f, "_Kya"))
        assertTrue(DartFieldSlots.ownerMatches(f, "_kya"))
        assertFalse(DartFieldSlots.ownerMatches(f, "Ofa"))
    }

    @Test
    fun `偏移缺失时 offset 为 null`() {
        val f = DartFieldSlots.parse(slot(0x10, "[pp+0x10] Field <A.b>", "static"))
        assertTrue(f != null)
        assertNull(f!!.offset)
        assertTrue(f.isStatic)
        assertEquals("static", f.modifierText)
    }

    @Test
    fun `parseAll 过滤非字段槽`() {
        val entries = listOf(
            slot(0x10, "[pp+0x10] Sentinel", ""),
            slot(0x91e78, "[pp+0x91e78] Field <Ofa._bvf@401245603>", "late (offset: 0x120)"),
            slot(0x20, "[pp+0x20] AnonymousClosure", "(0x90f900)"),
            slot(0x30, "[pp+0x30] Field <Ofa._avf@401245603>", "late (offset: 0x11c)"),
        )
        val fields = DartFieldSlots.parseAll(entries)
        assertEquals(2, fields.size)
        assertEquals(listOf(0x91e78L, 0x30L), fields.map { it.vmOffset })
    }
}