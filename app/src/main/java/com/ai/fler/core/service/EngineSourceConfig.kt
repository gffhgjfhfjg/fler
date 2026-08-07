package com.ai.fler.core.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎源下载地址配置。
 *
 * 引擎资产协议（v0.4.0 起）：
 * - 远程清单 manifest.json（stable 地址，指向 fler-dart main 分支）为唯一来源；
 * - 下载 URL / sha256 全部来自 manifest 每项，不再单独配置主/备/校验地址；
 * - 仅需配置 manifest 地址与 GitHub 加速前缀。
 */
@Singleton
class EngineSourceConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "engine_source"
        private const val KEY_MANIFEST_URL = "manifest_url"
        private const val KEY_GITHUB_PROXY = "github_proxy"

        // 版本信息清单 JSON（稳定地址，永远指向 fler-dart main 分支的最新版本）
        const val DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/myfler/fler-dart/main/manifest.json"

        /** GitHub 加速默认前缀（可通过设置页修改/清空关闭）。 */
        const val DEFAULT_GITHUB_PROXY = "https://gh-proxy.com"

        /** 引擎包版本标识（随默认源升级时同步更新；未安装时作为 installedPackVersion 缺省回退）。 */
        const val ENGINE_PACKAGE_VERSION = "v0.4.0"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 远程 manifest.json 地址。 */
    var manifestUrl: String
        get() = prefs.getString(KEY_MANIFEST_URL, DEFAULT_MANIFEST_URL) ?: DEFAULT_MANIFEST_URL
        set(value) = prefs.edit().putString(KEY_MANIFEST_URL, value).apply()

    /** GitHub 加速前缀（如 https://gh-proxy.com），清空表示关闭。 */
    var githubProxy: String
        get() = prefs.getString(KEY_GITHUB_PROXY, DEFAULT_GITHUB_PROXY) ?: DEFAULT_GITHUB_PROXY
        set(value) = prefs.edit().putString(KEY_GITHUB_PROXY, value.trim().trimEnd('/')).apply()

    /** 是否使用了自定义地址（含改过默认加速前缀/清空）。 */
    fun isCustom(): Boolean {
        return manifestUrl != DEFAULT_MANIFEST_URL ||
            githubProxy != DEFAULT_GITHUB_PROXY
    }

    /** 重置为默认地址。 */
    fun resetToDefault() {
        prefs.edit().clear().apply()
    }
}
