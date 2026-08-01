package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 库信息实体。
 * 存储分析结果中涉及的库文件信息。
 */
@Entity(
    tableName = "libraries",
    foreignKeys = [
        ForeignKey(
            entity = Analysis::class,
            parentColumns = ["id"],
            childColumns = ["analysis_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["analysis_id"]),
        Index(value = ["library_name"]),
        Index(value = ["path"])
    ]
)
data class Library(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "analysis_id")
    val analysisId: Long,

    @ColumnInfo(name = "library_name")
    val libraryName: String,

    @ColumnInfo(name = "path")
    val path: String,

    @ColumnInfo(name = "load_address")
    val loadAddress: Long = 0,

    @ColumnInfo(name = "size")
    val size: Long = 0,

    @ColumnInfo(name = "is_dart_snapshot")
    val isDartSnapshot: Boolean = false,

    @ColumnInfo(name = "section_count")
    val sectionCount: Int = 0,

    @ColumnInfo(name = "symbol_count")
    val symbolCount: Int = 0
)
