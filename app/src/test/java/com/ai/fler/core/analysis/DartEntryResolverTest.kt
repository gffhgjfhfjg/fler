package com.ai.fler.core.analysis

import com.ai.fler.core.jni.DisasmInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** DartEntryResolver：序言识别 + 入口探测纯逻辑单测。 */
class DartEntryResolverTest {

    private fun ins(mnemonic: String, opStr: String) = DisasmInstruction(
        address = 0L,
        size = 4,
        mnemonic = mnemonic,
        opStr = opStr,
        bytes = byteArrayOf(),
    )

    @Test
    fun `标准序言识别 - Dart AOT stp x29 x30 x15`() {
        assertTrue(DartEntryResolver.isPrologue(ins("stp", "x29, x30, [x15, #-0x10]!")))
        assertTrue(DartEntryResolver.isPrologue(ins("stp", "x29, x30, [x15, #-0x18]!")))
    }

    @Test
    fun `标准序言识别 - stp fp lr SP`() {
        assertTrue(DartEntryResolver.isPrologue(ins("stp", "fp, lr, [sp, #-0x10]!")))
    }

    @Test
    fun `标准序言识别 - sub x15 压栈`() {
        assertTrue(DartEntryResolver.isPrologue(ins("sub", "x15, x15, #0x20")))
    }

    @Test
    fun `非序言 - 普通指令不是`() {
        assertEquals(false, DartEntryResolver.isPrologue(ins("ldr", "x0, [x29, #0x10]")))
        assertEquals(false, DartEntryResolver.isPrologue(ins("ret", "")))
    }

    @Test
    fun `stub 开头识别`() {
        assertTrue(DartEntryResolver.isStubHead(ins("blr", "x30")))
        assertTrue(DartEntryResolver.isStubHead(ins("b", "#0x99b954")))
        assertEquals(false, DartEntryResolver.isStubHead(ins("stp", "x29, x30, [x15, #-0x10]!")))
        assertEquals(false, DartEntryResolver.isStubHead(ins("ldr", "x0, [x29, #0x10]")))
    }
}
