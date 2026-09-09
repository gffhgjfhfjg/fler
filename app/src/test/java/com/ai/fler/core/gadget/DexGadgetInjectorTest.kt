package com.ai.fler.core.gadget

import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.instruction.formats.Instruction35c
import org.jf.dexlib2.iface.reference.MethodReference
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * dex clinit 注入测试。
 *
 * 夹具：d8 36.0.0 编译（fixtureA：FixtureApp 含静态初始化块；
 * fixtureB：NoClinitApp 无 clinit）。
 */
class DexGadgetInjectorTest {

    private fun dexBytes(fixture: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("gadget/$fixture")!!.use { it.readBytes() }

    private fun load(dexBytes: ByteArray) =
        DexFileFactory.loadDexFile(
            File.createTempFile("tmpDex", ".dex").apply { writeBytes(dexBytes) },
            Opcodes.forApi(28)
        )

    @Test
    fun `inject into existing clinit`() {
        val bytes = dexBytes("fixtureA-classes.dex")
        val classType = DexGadgetInjector.toClassType("com.example.fixture.FixtureApp")

        val result = DexGadgetInjector.inject(bytes, classType, "gadget")!!
        assertEquals(classType, result.targetClass)
        assertFalse(result.createdClinit)
        assertFalse(result.alreadyInjected)

        // 载回验证：clinit 头两条指令为 const-string / invoke-static loadLibrary
        val dex = load(result.dexBytes)
        val clazz = dex.classes.first { it.type == classType }
        val clinit = clazz.directMethods.first { it.name == "<clinit>" }
        val ins = clinit.implementation!!.instructions.toList()
        assertTrue(ins.size >= 4)

        val first = ins[0] as Instruction21c
        assertEquals("gadget", (first.reference as StringReference).string)

        val second = ins[1] as Instruction35c
        val mref = second.reference as MethodReference
        assertEquals("Ljava/lang/System;", mref.definingClass)
        assertEquals("loadLibrary", mref.name)

        // 其余类保持存在
        assertNotNull(dex.classes.first { it.type == "Lcom/example/fixture/Other;" })
    }

    @Test
    fun `create clinit when absent`() {
        val bytes = dexBytes("fixtureB-classes.dex")
        val classType = DexGadgetInjector.toClassType("com.example.fixture.NoClinitApp")

        val result = DexGadgetInjector.inject(bytes, classType, "gadget")!!
        assertTrue(result.createdClinit)

        val dex = load(result.dexBytes)
        val clazz = dex.classes.first { it.type == classType }
        val clinit = clazz.directMethods.first { it.name == "<clinit>" }
        val ins = clinit.implementation!!.instructions.toList()
        assertEquals(3, ins.size) // const-string + invoke-static + return-void
        assertEquals("gadget", (ins[0] as Instruction21c).let { (it.reference as StringReference).string })
    }

    @Test
    fun `idempotent when already injected`() {
        val bytes = dexBytes("fixtureA-classes.dex")
        val classType = DexGadgetInjector.toClassType("com.example.fixture.FixtureApp")
        val once = DexGadgetInjector.inject(bytes, classType, "gadget")!!
        val twice = DexGadgetInjector.inject(once.dexBytes, classType, "gadget")!!
        assertTrue(twice.alreadyInjected)
        // 已注入时原样返回
        assertTrue(once.dexBytes.contentEquals(twice.dexBytes))
    }

    @Test
    fun `returns null for missing class`() {
        val bytes = dexBytes("fixtureA-classes.dex")
        val result = DexGadgetInjector.inject(
            bytes, DexGadgetInjector.toClassType("com.example.missing.App"), "gadget")
        assertEquals(null, result)
    }
}
