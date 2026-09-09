package com.ai.fler.core.gadget

import android.app.Application
import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.service.ApkRepacker
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.ProjectDao
import com.android.apksig.ApkVerifier
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import org.jf.dexlib2.iface.instruction.formats.Instruction21c
import org.jf.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * gadget 注入端到端（JVM + Robolectric）：
 * fixtureA.apk → GadgetInjector 注入计划 → ApkRepacker 重建 + v1/v2/v3 签名 →
 * 校验输出（签名验证 / manifest / dex / 条目 / 原条目保持）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GadgetRepackE2eTest {

    private lateinit var repacker: ApkRepacker

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        repacker = ApkRepacker(
            context, AppLogger(),
            mockk<AnalysisDao>(), mockk<LibraryDao>(), mockk<ProjectDao>(),
        )
    }

    @Test
    fun `inject plan into fixture and sign`() = runBlocking<Unit> {
        val srcBytes = javaClass.classLoader.getResourceAsStream("gadget/fixtureA.apk")!!
            .use { it.readBytes() }
        val src = File.createTempFile("e2e", ".apk").apply { writeBytes(srcBytes) }

        // ---- 注入计划 ----
        val fakeGadget = ByteArray(6 * 1024 * 1024) { (it % 251).toByte() }
        val script = "// anti tamper".toByteArray()
        val plan = ZipFile(src).use { zip ->
            GadgetInjector.plan(
                apk = zip,
                gadgetByAbi = mapOf("arm64-v8a" to fakeGadget, "x86_64" to fakeGadget),
                options = GadgetInjector.Options(
                    interaction = GadgetConfig.Interaction.Listen(port = 27042),
                    antiTamper = GadgetConfig.AntiTamperParams(
                        pkg = "com.example.fixture", certB64 = "QUJD", entryCrcs = emptyMap()),
                    scriptBytes = script,
                ),
            )
        }

        // ---- 重建 + 签名（v1+v2+v3，debug 密钥）----
        val output = repacker.repackWithEntriesToTempFile(
            apkFile = src,
            entryOverrides = plan.overrides,
            entryAdds = plan.adds,
            entryName = "e2e",
            signOptions = ApkRepacker.SignOptions(),
            keySource = ApkRepacker.KeySource.Debug,
        )
        try {
            assertTrue("回打失败: ${output.result.error}", output.result.ok)
            assertEquals(listOf("v1", "v2", "v3"), output.result.schemes)
            val out = output.file

            // ---- 签名验证（apksig）----
            val verify = ApkVerifier.Builder(out)
                .setMinCheckedPlatformVersion(24)
                .build()
                .verify()
            assertTrue(
                "签名验证失败: ${verify.allErrors.joinToString()}",
                verify.isVerified,
            )

            ZipFile(out).use { zip ->
                // ---- gadget 三件套（两 ABI）----
                val so = zip.getEntry("lib/arm64-v8a/libgadget.so")
                assertNotNull("缺少 libgadget.so", so)
                assertTrue(zip.getInputStream(so).use { it.readBytes() }.contentEquals(fakeGadget))
                assertNotNull(zip.getEntry("lib/x86_64/libgadget.so"))
                assertNotNull(zip.getEntry("lib/arm64-v8a/libgadget.config.so"))
                assertNotNull(zip.getEntry("lib/arm64-v8a/libgadget.script.so"))
                assertNotNull(zip.getEntry("lib/x86_64/libgadget.config.so"))

                // ---- 原有条目保持 ----
                val orig = ZipFile(src)
                assertTrue(zip.getInputStream(zip.getEntry("lib/arm64-v8a/liborig.so"))
                    .use { it.readBytes() }
                    .contentEquals(orig.getInputStream(orig.getEntry("lib/arm64-v8a/liborig.so"))
                        .use { it.readBytes() }))

                // ---- manifest：extractNativeLibs=true ----
                val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml"))
                    .use { it.readBytes() }
                val info = Axml.parse(manifest).manifestInfo()
                assertEquals(true, info.extractNativeLibs)
                assertEquals("com.example.fixture.FixtureApp", info.applicationName)

                // ---- dex：clinit 首指令 loadLibrary("gadget") ----
                val dexBytes = zip.getInputStream(zip.getEntry("classes.dex")).use { it.readBytes() }
                val dexFile = File.createTempFile("out", ".dex").apply { writeBytes(dexBytes) }
                val dex = DexFileFactory.loadDexFile(dexFile, Opcodes.forApi(28))
                val clazz = dex.classes.first { it.type == "Lcom/example/fixture/FixtureApp;" }
                val clinit = clazz.directMethods.first { it.name == "<clinit>" }
                val first = clinit.implementation!!.instructions.first() as Instruction21c
                assertEquals("gadget", (first.reference as StringReference).string)
                dexFile.delete()
            }
        } finally {
            output.file.delete()
        }
    }
}
