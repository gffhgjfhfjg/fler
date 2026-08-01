package com.ai.fler.core.service

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎源下载地址配置。
 *
 * 使用 SharedPreferences 持久化用户自定义的下载源地址。
 * 未自定义时使用默认内置地址（GitHub myfler）。
 */
@Singleton
class EngineSourceConfig @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "engine_source"
        private const val KEY_PRIMARY_URL = "primary_url"
        private const val KEY_FALLBACK_URL = "fallback_url"
        private const val KEY_CHECKSUM_URL = "checksum_url"
        private const val KEY_VERSION_URL = "version_url"

        // 默认地址（v0.3.10 起：引擎改为内存直导 DB（classes/methods/pp/strings），
        // 修复产物页类/方法为空；PRODUCT 布局宏已含，分析成功）
        const val DEFAULT_PRIMARY_URL = "https://github.com/myfler/fler-dart/releases/download/v0.3.10/fler-engines.7z"
        const val DEFAULT_FALLBACK_URL = "https://github.com/myfler/fler-dart/releases/download/v0.3.10/fler-engines.7z"
        const val DEFAULT_CHECKSUM_URL = "https://github.com/myfler/fler-dart/releases/download/v0.3.10/checksums.txt"
        // 版本信息 JSON（稳定地址，永远指向 fler-dart main 分支的最新版本）
        const val DEFAULT_VERSION_URL = "https://raw.githubusercontent.com/myfler/fler-dart/main/version.json"

        /** 引擎包版本标识（用于项目卡片 Engine 展示；随默认源升级时同步更新）。 */
        const val ENGINE_PACKAGE_VERSION = "v0.3.10"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 主下载地址（GitHub myfler）。 */
    var primaryUrl: String
        get() = prefs.getString(KEY_PRIMARY_URL, DEFAULT_PRIMARY_URL) ?: DEFAULT_PRIMARY_URL
        set(value) = prefs.edit().putString(KEY_PRIMARY_URL, value).apply()

    /** 备用下载地址（GitHub myfler 回退）。 */
    var fallbackUrl: String
        get() = prefs.getString(KEY_FALLBACK_URL, DEFAULT_FALLBACK_URL) ?: DEFAULT_FALLBACK_URL
        set(value) = prefs.edit().putString(KEY_FALLBACK_URL, value).apply()

    /** SHA256 校验地址。 */
    var checksumUrl: String
        get() = prefs.getString(KEY_CHECKSUM_URL, DEFAULT_CHECKSUM_URL) ?: DEFAULT_CHECKSUM_URL
        set(value) = prefs.edit().putString(KEY_CHECKSUM_URL, value).apply()

    /** 版本信息 JSON 地址。 */
    var versionUrl: String
        get() = prefs.getString(KEY_VERSION_URL, DEFAULT_VERSION_URL) ?: DEFAULT_VERSION_URL
        set(value) = prefs.edit().putString(KEY_VERSION_URL, value).apply()

    /** 是否使用了自定义地址。 */
    fun isCustom(): Boolean {
        return primaryUrl != DEFAULT_PRIMARY_URL ||
            fallbackUrl != DEFAULT_FALLBACK_URL ||
            checksumUrl != DEFAULT_CHECKSUM_URL ||
            versionUrl != DEFAULT_VERSION_URL
    }

    /** 重置为默认地址。 */
    fun resetToDefault() {
        prefs.edit().clear().apply()
    }
}
