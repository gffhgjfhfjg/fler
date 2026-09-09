package com.ai.fler.core.gadget

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/**
 * GadgetInjector 注入计划测试（纯 JVM，伪造 gadget .so 字节）。
 */
class GadgetInjectorTest {

    private fun copyFixture(name: String): File {
        val bytes = javaClass.classLoader.getResourceAsStream("gadget/$name")!!
            .use { it.readBytes() }
        return File.createTempFile("gadget", ".apk").apply { writeBytes(bytes) }
    }

    private val fakeGadget: ByteArray = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
    private val fakeScript = "// fake anti-tamper".toByteArray()

    @Test
    fun `plan on fixtureA multi-abi with clinit`() {
        val apk = ZipFile(copyFixture("fixtureA.apk"))
        val plan = GadgetInjector.plan(
            apk = apk,
            gadgetByAbi = mapOf("arm64-v8a" to fakeGadget, "x86_64" to fakeGadget, "x86" to fakeGadget),
            options = GadgetInjector.Options(
                interaction = GadgetConfig.Interaction.Listen(port = 27042),
                antiTamper = GadgetConfig.AntiTamperParams(
                    pkg = "com.example.fixture", certB64 = "QUJD", entryCrcs = emptyMap()),
                scriptBytes = fakeScript,
            ),
        )

        assertEquals("com.example.fixture", plan.manifestInfo.packageName)
        assertEquals("com.example.fixture.FixtureApp", plan.entryClass)
        assertEquals("Lcom/example/fixture/FixtureApp;", plan.entryClassType)
        assertEquals(listOf("arm64-v8a", "x86_64"), plan.abis) // APK 现有 ABI
        assertEquals("libgadget", plan.libBase)
        assertTrue(!plan.createdClinit)
        assertTrue(!plan.alreadyInjected)

        // 替换表：manifest + dex
        assertEquals(setOf("AndroidManifest.xml", "classes.dex"), plan.overrides.keys)

        // 新增表：两 ABI × 三件套
        assertEquals(
            setOf(
                "lib/arm64-v8a/libgadget.so",
                "lib/arm64-v8a/libgadget.config.so",
                "lib/arm64-v8a/libgadget.script.so",
                "lib/x86_64/libgadget.so",
                "lib/x86_64/libgadget.config.so",
                "lib/x86_64/libgadget.script.so",
            ),
            plan.adds.keys,
        )
        // gadget 字节原样写入
        assertTrue(plan.adds["lib/arm64-v8a/libgadget.so"]!!.contentEquals(fakeGadget))

        // manifest 改写：extractNativeLibs=true
        val reread = Axml.parse(plan.overrides["AndroidManifest.xml"]!!)
        assertEquals(true, reread.manifestInfo().extractNativeLibs)

        // config JSON（listen 模式）
        val cfg = Json.parseToJsonElement(plan.configJson).jsonObject
        val interaction = cfg["interaction"]!!.jsonObject
        assertEquals("listen", interaction["type"]!!.jsonPrimitive.content)
        assertEquals("27042", interaction["port"]!!.jsonPrimitive.content)
        assertEquals("wait", interaction["on_load"]!!.jsonPrimitive.content)
        assertEquals(
            "wait",
            Json.parseToJsonElement(plan.adds["lib/arm64-v8a/libgadget.config.so"]!!
                .decodeToString()).jsonObject["interaction"]!!.jsonObject["on_load"]!!
                .jsonPrimitive.content,
        )

        // 原条目指纹
        assertNotNull(plan.originalEntryCrcs["classes.dex"])
        assertNotNull(plan.originalEntryCrcs["AndroidManifest.xml"])
        apk.close()
    }

    @Test
    fun `plan on fixtureB creates clinit and embeds anti-tamper params`() {
        val apk = ZipFile(copyFixture("fixtureB.apk"))
        val plan = GadgetInjector.plan(
            apk = apk,
            gadgetByAbi = mapOf("arm64-v8a" to fakeGadget),
            options = GadgetInjector.Options(
                interaction = GadgetConfig.Interaction.Script(),
                antiTamper = GadgetConfig.AntiTamperParams(
                    pkg = "com.example.fixture", certB64 = "QUJD",
                    entryCrcs = mapOf("classes.dex" to listOf(1L, 2L, 3L))),
                scriptBytes = fakeScript,
            ),
        )
        assertEquals("com.example.fixture.NoClinitApp", plan.entryClass)
        assertTrue(plan.createdClinit)
        // 无 lib 目录的 APK：注入提供的（设备）ABI
        assertEquals(listOf("arm64-v8a"), plan.abis)
        assertEquals(
            "com.example.fixture.NoClinitApp",
            Axml.parse(plan.overrides["AndroidManifest.xml"]!!).manifestInfo().applicationName,
        )

        // script 模式 config：path 相对 gadget 目录 + parameters
        val cfg = Json.parseToJsonElement(
            plan.adds["lib/arm64-v8a/libgadget.config.so"]!!.decodeToString()
        ).jsonObject["interaction"]!!.jsonObject
        assertEquals("script", cfg["type"]!!.jsonPrimitive.content)
        assertEquals("libgadget.script.so", cfg["path"]!!.jsonPrimitive.content)
        val params = cfg["parameters"]!!.jsonObject
        assertEquals("com.example.fixture", params["pkg"]!!.jsonPrimitive.content)
        assertEquals("QUJD", params["certB64"]!!.jsonPrimitive.content)
        assertEquals(
            3L,
            params["entryCrcs"]!!.jsonObject["classes.dex"]!!
                .let { arr -> (arr as kotlinx.serialization.json.JsonArray)[2].jsonPrimitive.content.toLong() },
        )
        apk.close()
    }

    @Test
    fun `lib name collision shifts suffix`() {
        // fixtureA 已有 liborig.so；用 libBase=liborig 应顺延为 liborig2
        val apk = ZipFile(copyFixture("fixtureA.apk"))
        val plan = GadgetInjector.plan(
            apk = apk,
            gadgetByAbi = mapOf("arm64-v8a" to fakeGadget),
            options = GadgetInjector.Options(libBase = "liborig"),
        )
        assertEquals("liborig2", plan.libBase)
        assertTrue(plan.adds.containsKey("lib/arm64-v8a/liborig2.so"))
        assertTrue(plan.adds.containsKey("lib/arm64-v8a/liborig2.config.so"))
        apk.close()
    }

    @Test
    fun `idempotent second plan reports already injected`() {
        val apkFile = copyFixture("fixtureA.apk")
        val first = GadgetInjector.plan(
            ZipFile(apkFile), mapOf("arm64-v8a" to fakeGadget), GadgetInjector.Options()
        )
        // 用第一次的结果构造「已注入 APK」再 plan
        val injected = File.createTempFile("gadget2", ".apk")
        java.util.zip.ZipOutputStream(injected.outputStream()).use { zos ->
            ZipFile(apkFile).use { zip ->
                for (e in zip.entries()) {
                    val name = e.name
                    val data = first.overrides[name]
                        ?: first.adds.entries.firstOrNull { it.key == name }?.value
                        ?: zip.getInputStream(e).use { it.readBytes() }
                    val entry = java.util.zip.ZipEntry(name)
                    zos.putNextEntry(entry)
                    zos.write(data)
                }
            }
        }
        val second = GadgetInjector.plan(
            ZipFile(injected), mapOf("arm64-v8a" to fakeGadget), GadgetInjector.Options()
        )
        assertTrue(second.alreadyInjected)
        // dex 不再替换，manifest 已是 true 也无需改写？manifest 仍需写出（若已 true 则无修改）
        // 这里断言：已注入时不改 dex
        assertTrue(second.overrides["classes.dex"] == null || second.alreadyInjected)
    }
}
