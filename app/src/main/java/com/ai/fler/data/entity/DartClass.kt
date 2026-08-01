package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dart 类实体。
 * 存储分析结果中的 Dart 类信息。
 */
@Entity(
    tableName = "dart_classes",
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
        Index(value = ["class_name"]),
        Index(value = ["library_path"])
    ]
)
data class DartClass(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "analysis_id")
    val analysisId: Long,

    @ColumnInfo(name = "class_name")
    val className: String,

    @ColumnInfo(name = "library_path")
    val libraryPath: String,

    @ColumnInfo(name = "super_class")
    val superClass: String? = null,

    @ColumnInfo(name = "is_abstract")
    val isAbstract: Boolean = false,

    @ColumnInfo(name = "is_final")
    val isFinal: Boolean = false,

    @ColumnInfo(name = "method_count")
    val methodCount: Int = 0,

    @ColumnInfo(name = "is_analyzed")
    val isAnalyzed: Boolean = false
)
