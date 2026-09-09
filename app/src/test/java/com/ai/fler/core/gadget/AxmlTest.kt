package com.ai.fler.core.gadget

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipFile

/**
 * AXML（二进制 AndroidManifest）解析 / 改写测试。
 *
 * 夹具：aapt2 36.0.0 编译（fixtureA：无 extractNativeLibs 属性；
 * fixtureB：extractNativeLibs="false"），与真实 APK 内的格式一致。
 */
class AxmlTest {

    private fun manifestBytes(fixture: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("gadget/$fixture")!!.use { it.readBytes() }

    @Test
    fun `parse fixtureA manifest info`() {
        val doc = Axml.parse(manifestBytes("fixtureA.apk").let { bytes ->
            ZipFile(java.io.File.createTempFile("tmpAxml", ".apk").apply { writeBytes(bytes) })
                .use { zip -> zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() } }
        })
        val info = doc.manifestInfo()
        assertEquals("com.example.fixture", info.packageName)
        assertEquals("com.example.fixture.FixtureApp", info.applicationName)
        assertNull(info.extractNativeLibs)
        assertEquals(listOf("com.example.fixture.MainActivity"), info.launcherActivities)
        assertEquals("com.example.fixture.FixtureApp", info.entryClassName)
    }

    @Test
    fun `parse fixtureB manifest info`() {
        val doc = Axml.parse(manifestBytes("fixtureB.apk").let { bytes ->
            ZipFile(java.io.File.createTempFile("tmpAxml", ".apk").apply { writeBytes(bytes) })
                .use { zip -> zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() } }
        })
        val info = doc.manifestInfo()
        assertEquals("com.example.fixture", info.packageName)
        assertEquals("com.example.fixture.NoClinitApp", info.applicationName)
        assertEquals(false, info.extractNativeLibs)
    }

    @Test
    fun `set extractNativeLibs adds attribute when absent`() {
        val bytes = extractManifest("fixtureA.apk")
        val doc = Axml.parse(bytes)
        assertNull(doc.manifestInfo().extractNativeLibs)

        doc.setExtractNativeLibs(true)
        val out = doc.toBytes()

        // 重解析验证
        val reread = Axml.parse(out)
        assertEquals(true, reread.manifestInfo().extractNativeLibs)
        // 原信息不丢
        assertEquals("com.example.fixture", reread.manifestInfo().packageName)
        assertEquals("com.example.fixture.FixtureApp", reread.manifestInfo().applicationName)
        // 资源 id 表覆盖新属性名
        val nameIdx = reread.strings.indexOf("extractNativeLibs")
        assertTrue(nameIdx >= 0)
        assertTrue(nameIdx < reread.resourceIds.size)
        assertEquals(Axml.ATTR_EXTRACT_NATIVE_LIBS_RES_ID, reread.resourceIds[nameIdx])
    }

    @Test
    fun `set extractNativeLibs flips existing false to true`() {
        val bytes = extractManifest("fixtureB.apk")
        val doc = Axml.parse(bytes)
        assertEquals(false, doc.manifestInfo().extractNativeLibs)

        doc.setExtractNativeLibs(true)
        val reread = Axml.parse(doc.toBytes())
        assertEquals(true, reread.manifestInfo().extractNativeLibs)
        assertEquals("com.example.fixture.NoClinitApp", reread.manifestInfo().applicationName)
    }

    @Test
    fun `roundtrip preserves strings and element structure`() {
        val bytes = extractManifest("fixtureA.apk")
        val doc = Axml.parse(bytes)
        val out = doc.toBytes()
        val reread = Axml.parse(out)
        assertEquals(doc.strings, reread.strings)
        // 再跑一次 roundtrip 应稳定（幂等字节）
        assertArrayEquals(out, reread.toBytes())
    }

    private fun extractManifest(fixture: String): ByteArray {
        val tmp = java.io.File.createTempFile("tmpAxml", ".apk")
        tmp.writeBytes(manifestBytes(fixture))
        return ZipFile(tmp).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }.also { tmp.delete() }
    }
}
