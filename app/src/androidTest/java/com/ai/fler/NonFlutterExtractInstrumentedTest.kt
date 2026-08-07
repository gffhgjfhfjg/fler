package com.ai.fler

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.service.ApkExtractor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 非 Flutter APK 兜底提取回归测试（真机）。
 *
 * 用例 1：从设备上找一个「含 native 库但非 Flutter」的已安装应用 APK，
 *         验证回退提取全部 *.so、isSuccess/isFlutter/extraLibs 语义正确。
 * 用例 2：构造无任何 native 库的假 APK，验证明确报错。
 */
@RunWith(AndroidJUnit4::class)
class NonFlutterExtractInstrumentedTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 遍历已安装应用，找第一个「含 lib/<abi>/ 下 so 文件且不含 libapp.so/libflutter.so」的 APK 路径。 */
    @Suppress("DEPRECATION")
    private fun findNativeOnlyApk(): String? {
        val pm = context.packageManager
        for (info in pm.getInstalledApplications(0)) {
            val src = info.sourceDir ?: continue
            try {
                ZipFile(src).use { zip ->
                    var hasSo = false
                    var hasFlutter = false
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val name = entries.nextElement().name
                        if (name.startsWith("lib/") && name.endsWith(".so")) hasSo = true
                        if (name.endsWith("libflutter.so") || name.endsWith("libapp.so")) hasFlutter = true
                    }
                    if (hasSo && !hasFlutter) return src
                }
            } catch (_: Exception) {
                // 个别 APK 读取失败（split 等），跳过
            }
        }
        return null
    }

    @Test
    fun fallbackExtractsAllNativeLibs(): Unit = runBlocking {
        val apkPath = findNativeOnlyApk()
        assertNotNull("设备上未找到含 native 库的非 Flutter 应用，无法回归", apkPath)

        val outDir = File(context.cacheDir, "nfx_test_out").apply {
            deleteRecursively()
            mkdirs()
        }
        val extractor = ApkExtractor(context, AppLogger())
        val result = extractor.extract(apkPath!!, outDir)

        assertTrue("提取应成功: ${result.error}", result.isSuccess)
        assertFalse("不应识别为 Flutter", result.isFlutter)
        assertNull("不应有 libapp.so", result.libapp)
        assertTrue("extraLibs 不应为空", result.extraLibs.isNotEmpty())

        // 每个提取的库文件都真实落盘且非空
        result.extraLibs.forEach { lib ->
            val f = File(lib.path)
            assertTrue("${lib.libraryName} 应落盘", f.exists())
            assertTrue("${lib.libraryName} 不应为空文件", f.length() > 0)
        }
        android.util.Log.i(
            "NfxExtractTest",
            "fallback ok: ${result.extraLibs.size} libs from $apkPath -> " +
                result.extraLibs.joinToString { it.libraryName }
        )
    }

    @Test
    fun apkWithoutNativeLibsFails(): Unit = runBlocking {
        val fakeApk = File(context.cacheDir, "nfx_empty.apk")
        ZipOutputStream(fakeApk.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex"))
            zos.write(ByteArray(16))
            zos.closeEntry()
        }

        val outDir = File(context.cacheDir, "nfx_test_out2").apply {
            deleteRecursively()
            mkdirs()
        }
        val extractor = ApkExtractor(context, AppLogger())
        val result = extractor.extract(fakeApk.absolutePath, outDir)

        assertFalse("无 native 库的 APK 应失败", result.isSuccess)
        assertNotNull(result.error)
        assertTrue(
            "错误文案应提示无 native 库: ${result.error}",
            result.error!!.contains("native")
        )
    }
}
