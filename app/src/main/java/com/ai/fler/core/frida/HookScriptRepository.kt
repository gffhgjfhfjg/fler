package com.ai.fler.core.frida

import com.ai.fler.data.dao.HookScriptDao
import com.ai.fler.data.entity.HookScript
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hook 脚本仓库：把 FridaScriptBuilder 里硬编码的 JS 模板落地为数据库条目，
 * 并提供增删改查 + 默认预设恢复。
 */
@Singleton
class HookScriptRepository @Inject constructor(
    private val dao: HookScriptDao,
) {

    /** 全部脚本流（最近更新在前）。 */
    fun observeAll(): Flow<List<HookScript>> = dao.observeAll()

    suspend fun getById(id: Long): HookScript? = dao.getById(id)

    /** 内置默认预设（首次启动 / 手动恢复时种子）。 */
    fun defaultPresets(): List<HookScript> = listOf(
        HookScript(
            name = "Dart 入口 Hook（Interceptor）",
            description = "模块+vaddr 直挂方法入口，含 Dart 参数最佳努力解码。改顶部 MODULE_TPL/VADDR_TPL/LABEL_TPL 三参数即可用",
            source = FridaScriptBuilder.hookTemplateSource(),
            isPreset = true,
        ),
        HookScript(
            name = "启动模块扫描（bootstrap）",
            description = "目标进程加载后列出全部 so 模块名，用于确认 libapp.so 是否在内存中",
            source = FridaScriptBuilder.bootstrapScan(),
            isPreset = true,
        ),
    )

    /**
     * 确保默认预设存在（按 name 去重，首次启动/恢复时调用）。
     * @return 本次实际插入条数
     */
    suspend fun ensureDefaults(): Int {
        var inserted = 0
        for (preset in defaultPresets()) {
            if (dao.getByName(preset.name) == null) {
                dao.insert(preset.copy(id = 0, updatedAt = System.currentTimeMillis()))
                inserted++
            }
        }
        return inserted
    }

    /** 手动恢复默认预设（已存在同名项跳过；删除过的预设被重新种子）。 */
    suspend fun restoreDefaults(): Int = ensureDefaults()

    /** 新增自定义脚本。 */
    suspend fun create(name: String, description: String, source: String): Long =
        dao.insert(
            HookScript(
                name = name,
                description = description,
                source = source,
                isPreset = false,
                updatedAt = System.currentTimeMillis(),
            )
        )

    /** 编辑脚本（名称/说明/源码全量更新）。返回是否命中。 */
    suspend fun update(id: Long, name: String, description: String, source: String): Boolean {
        val existing = dao.getById(id) ?: return false
        dao.update(
            existing.copy(
                name = name,
                description = description,
                source = source,
                updatedAt = System.currentTimeMillis(),
            )
        )
        return true
    }

    /** 一键复制为新脚本（副本不带内置标记）。返回新 id；源不存在返回 null。 */
    suspend fun duplicate(id: Long): Long? {
        val src = dao.getById(id) ?: return null
        return dao.insert(
            src.copy(
                id = 0,
                name = "${src.name} (副本)",
                isPreset = false,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }
}