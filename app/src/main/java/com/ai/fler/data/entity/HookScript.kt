package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 可管理的 Frida Hook 脚本（落地数据库）。
 *
 * 内置预设模板（来自 [com.ai.fler.core.frida.FridaScriptBuilder]）在仓库首次
 * 加载时作为种子写入 [isPreset]=true；用户可通过设置页「Hook 脚本」进行新增/
 * 编辑/删除。源码为完整 Frida JS，可由 `frida_eval`/`frida_hook` 加载执行。
 */
@Entity(tableName = "hook_scripts")
data class HookScript(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    /** 脚本名（用户可改，用于列表展示）。 */
    @ColumnInfo(name = "name")
    val name: String,

    /** 说明（用途/目标）。 */
    @ColumnInfo(name = "description")
    val description: String,

    /** Frida JavaScript 源码。 */
    @ColumnInfo(name = "source")
    val source: String,

    /** 是否内置预设（预设可被删除，但可通过「恢复默认」重新种子）。 */
    @ColumnInfo(name = "is_preset")
    val isPreset: Boolean = false,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis(),
)