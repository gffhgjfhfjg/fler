package com.ai.fler.core.service

import android.app.Application
import android.content.Context
import com.ai.fler.core.log.AppLogger
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.ProjectDao
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * 已导入密钥参数的持久化语义（「导入一次，后续免再次输入」）：
 *
 * 1. 未保存过 → [ApkRepacker.savedKeyConfig] 返回 null；
 * 2. 密钥库密码为空视为未保存（storePass 是回退链的必备项）；
 * 3. 保存过的参数完整读回（UI 弹窗预填与 MCP storePass 回退共用）；
 * 4. 重新导入密钥库 → 已保存参数清空（换库后旧密码残留会导致预填错误密码）。
 *
 * 「签名成功后写入」在 repackToTempFile 成功路径内（见 ApkRepacker.saveKeyConfig），
 * 该路径依赖完整 APK 回打流程，此处仅覆盖 prefs 语义。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApkRepackerSavedKeyConfigTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private fun newRepacker() = ApkRepacker(
        context, AppLogger(), mockk<AnalysisDao>(), mockk<LibraryDao>(), mockk<ProjectDao>(),
    )

    /** 直接写 prefs，模拟「签名成功后已保存」状态。 */
    private fun writePrefs(alias: String, storePass: String, keyPass: String) {
        context.getSharedPreferences("signing_key", Context.MODE_PRIVATE).edit()
            .putString("custom_key_alias", alias)
            .putString("custom_key_store_pass", storePass)
            .putString("custom_key_key_pass", keyPass)
            .apply()
    }

    @Test
    fun `未保存过返回 null`() {
        assertNull(newRepacker().savedKeyConfig())
    }

    @Test
    fun `密钥库密码为空视为未保存`() {
        writePrefs(alias = "signkey", storePass = "", keyPass = "")
        assertNull(newRepacker().savedKeyConfig())
    }

    @Test
    fun `保存过的参数完整读回`() {
        writePrefs(alias = "signkey", storePass = "storepass", keyPass = "keypass")
        val saved = newRepacker().savedKeyConfig()
        assertNotNull(saved)
        assertEquals("signkey", saved!!.alias)
        assertEquals("storepass", saved.storePassword)
        assertEquals("keypass", saved.keyPassword)
    }

    @Test
    fun `重新导入密钥库清空已保存参数`() = runTest {
        writePrefs(alias = "signkey", storePass = "oldpass", keyPass = "")
        val repacker = newRepacker()
        repacker.importCustomKeystore(ByteArrayInputStream(ByteArray(32)))
        assertNull("换新密钥库后旧参数应作废", repacker.savedKeyConfig())
    }
}
