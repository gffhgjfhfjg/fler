package com.ai.fler.core.service

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 引擎源配置回归测试（Robolectric 提供 Android SharedPreferences）。
 *
 * 用普通 Application 替代 App 的 FlerApplication（后者 onCreate 会加载 native fler_jni，
 * JVM 单测环境无法加载 arm64 .so，会导致 UnsatisfiedLinkError）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class EngineSourceConfigTest {

    private fun newConfig(): EngineSourceConfig {
        val ctx: Context = RuntimeEnvironment.getApplication()
        return EngineSourceConfig(ctx)
    }

    @Test
    fun `默认配置使用稳定 manifest 地址与代理`() {
        val config = newConfig()
        assertEquals(EngineSourceConfig.DEFAULT_MANIFEST_URL, config.manifestUrl)
        assertEquals(EngineSourceConfig.DEFAULT_GITHUB_PROXY, config.githubProxy)
    }

    @Test
    fun `自定义 manifest 地址后 isCustom 为 true`() {
        val config = newConfig()
        assertFalse(config.isCustom())
        config.manifestUrl = "https://example.com/manifest.json"
        assertTrue(config.isCustom())
        assertEquals("https://example.com/manifest.json", config.manifestUrl)
    }

    @Test
    fun `代理地址写入时去除末尾斜杠`() {
        val config = newConfig()
        config.githubProxy = "https://proxy.example.com//"
        assertEquals("https://proxy.example.com", config.githubProxy)
    }

    @Test
    fun `清空代理表示关闭且 isCustom 为 true`() {
        val config = newConfig()
        config.githubProxy = ""
        assertTrue(config.isCustom())
        assertEquals("", config.githubProxy)
    }

    @Test
    fun `resetToDefault 恢复默认地址`() {
        val config = newConfig()
        config.manifestUrl = "https://x.com/m.json"
        config.githubProxy = "https://p.com"
        config.resetToDefault()
        assertEquals(EngineSourceConfig.DEFAULT_MANIFEST_URL, config.manifestUrl)
        assertEquals(EngineSourceConfig.DEFAULT_GITHUB_PROXY, config.githubProxy)
    }
}