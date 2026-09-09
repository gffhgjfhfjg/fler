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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * 真实 APK 注入手动验证（环境变量门控，CI 不跑）：
 *
 *   FLER_REAL_APK=xxx.apk FLER_GADGET_SO=libgadget.so \
 *     ./gradlew :app:testDebugUnitTest --tests "*.RealApkManualTest"
 *
 * 用于发布前用复杂真实 APK（多 dex / 完整 manifest / 真实 gadget 二进制）
 * 走一遍完整管线（注入 → 重打包 → 签名 → 验证），产物写到 /tmp 供装机验证。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class RealApkManualTest {

    private lateinit var repacker: ApkRepacker

    private val realApk: String? get() = System.getenv("FLER_REAL_APK")
    private val gadgetSo: String? get() = System.getenv("FLER_GADGET_SO")

    @Before
    fun setup() {
        assumeTrue("需要 FLER_REAL_APK / FLER_GADGET_SO 环境变量", realApk != null && gadgetSo != null)
        repacker = ApkRepacker(
            RuntimeEnvironment.getApplication(), AppLogger(),
            mockk<AnalysisDao>(), mockk<LibraryDao>(), mockk<ProjectDao>(),
        )
    }

    @Test
    fun `inject real apk with real gadget`() = runBlocking<Unit> {
        val src = File(realApk!!)
        val gadget = File(gadgetSo!!).readBytes()
        val script = File("src/main/assets/gadget/anti_tamper.js").readBytes()

        val plan = ZipFile(src).use { zip ->
            GadgetInjector.plan(
                apk = zip,
                gadgetByAbi = mapOf("arm64-v8a" to gadget),
                options = GadgetInjector.Options(
                    interaction = GadgetConfig.Interaction.Listen(),
                    antiTamper = GadgetConfig.AntiTamperParams(
                        pkg = "", certB64 = null, entryCrcs = emptyMap()),
                    scriptBytes = script,
                ),
            )
        }
        println("注入计划: 入口=${plan.entryClass} dex=${plan.injectedDexEntry} " +
            "ABI=${plan.abis} lib=${plan.libBase} createdClinit=${plan.createdClinit} " +
            "overrides=${plan.overrides.keys} adds=${plan.adds.keys}")

        val output = repacker.repackWithEntriesToTempFile(
            apkFile = src,
            entryOverrides = plan.overrides,
            entryAdds = plan.adds,
            entryName = "manual",
            signOptions = ApkRepacker.SignOptions(),
            keySource = ApkRepacker.KeySource.Debug,
        )
        assertTrue("回打失败: ${output.result.error}", output.result.ok)

        val verify = ApkVerifier.Builder(output.file)
            .setMinCheckedPlatformVersion(24).build().verify()
        assertTrue("签名验证失败: ${verify.allErrors.joinToString()}", verify.isVerified)

        ZipFile(output.file).use { zip ->
            assertNotNull(zip.getEntry("lib/${plan.abis.first()}/${plan.libBase}.so"))
            assertNotNull(zip.getEntry("lib/${plan.abis.first()}/${plan.libBase}.config.so"))
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
            val info = Axml.parse(manifest).manifestInfo()
            assertEquals(true, info.extractNativeLibs)
            println("manifest OK: pkg=${info.packageName} app=${info.applicationName}")
        }

        val out = File("/tmp/${src.nameWithoutExtension}_gadget.apk")
        output.file.copyTo(out, overwrite = true)
        println("产物: $out (${out.length() / 1048576}MB)")
        output.file.delete()
    }
}
