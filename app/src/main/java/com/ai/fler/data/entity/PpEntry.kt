package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 补丁点（Patch Point）条目实体。
 * 存储分析结果中识别的可 hook 点信息。
 */
@Entity(
    tableName = "pp_entries",
    foreignKeys = [
        ForeignKey(
            entity = DartMethod::class,
            parentColumns = ["id"],
            childColumns = ["method_id"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = Analysis::class,
            parentColumns = ["id"],
            childColumns = ["analysis_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["method_id"]),
        Index(value = ["analysis_id"]),
        Index(value = ["vm_offset"]),
        Index(value = ["file_offset"])
    ]
)
data class PpEntry(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "method_id")
    val methodId: Long,

    @ColumnInfo(name = "analysis_id")
    val analysisId: Long,

    @ColumnInfo(name = "vm_offset")
    val vmOffset: Long,

    @ColumnInfo(name = "file_offset")
    val fileOffset: Long,

    @ColumnInfo(name = "function_size")
    val functionSize: Int = 0,

    @ColumnInfo(name = "is_leaf")
    val isLeaf: Boolean = false,

    @ColumnInfo(name = "caller_count")
    val callerCount: Int = 0,

    @ColumnInfo(name = "description")
    val description: String? = null,

    /** Blutter pp 条目类型（String / Type / Stub / Field ...），用于筛选展示。 */
    @ColumnInfo(name = "type")
    val type: String? = null
)
