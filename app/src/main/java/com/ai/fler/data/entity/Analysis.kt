package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分析记录实体。
 * 存储一次完整的 Blutter 分析执行信息。
 */
@Entity(
    tableName = "analyses",
    foreignKeys = [
        ForeignKey(
            entity = Project::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["project_id"]),
        Index(value = ["started_at"])
    ]
)
data class Analysis(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "project_id")
    val projectId: Long,

    @ColumnInfo(name = "libapp_path")
    val libappPath: String? = null,

    @ColumnInfo(name = "libflutter_path")
    val libflutterPath: String? = null,

    @ColumnInfo(name = "result_code")
    val resultCode: Int = RESULT_PENDING,

    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,

    @ColumnInfo(name = "classes_count")
    val classesCount: Int = 0,

    @ColumnInfo(name = "methods_count")
    val methodsCount: Int = 0,

    @ColumnInfo(name = "pp_entries_count")
    val ppEntriesCount: Int = 0,

    @ColumnInfo(name = "started_at")
    val startedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
) {
    companion object {
        const val RESULT_PENDING = -1
        const val RESULT_SUCCESS = 0
        const val RESULT_GENERIC_ERROR = 1
        const val RESULT_INVALID_ELF = 2
        const val RESULT_WRONG_ARCH = 3
        const val RESULT_DART_NOT_FOUND = 4
        const val RESULT_NO_SYMBOLS = 5
    }
}
