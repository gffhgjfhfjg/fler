package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Dart 方法实体。
 * 存储分析结果中的 Dart 方法（函数）信息。
 */
@Entity(
    tableName = "dart_methods",
    foreignKeys = [
        ForeignKey(
            entity = DartClass::class,
            parentColumns = ["id"],
            childColumns = ["class_id"],
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
        Index(value = ["class_id"]),
        Index(value = ["analysis_id"]),
        Index(value = ["method_name"]),
        Index(value = ["selector"])
    ]
)
data class DartMethod(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "class_id")
    val classId: Long,

    @ColumnInfo(name = "analysis_id")
    val analysisId: Long,

    @ColumnInfo(name = "method_name")
    val methodName: String,

    @ColumnInfo(name = "selector")
    val selector: String,

    @ColumnInfo(name = "is_static")
    val isStatic: Boolean = false,

    @ColumnInfo(name = "is_getter")
    val isGetter: Boolean = false,

    @ColumnInfo(name = "is_setter")
    val isSetter: Boolean = false,

    @ColumnInfo(name = "is_constructor")
    val isConstructor: Boolean = false,

    @ColumnInfo(name = "signature")
    val signature: String? = null,

    @ColumnInfo(name = "function_offset")
    val functionOffset: Long? = null,

    /** 函数字节长度（来自 Blutter methods.size）。用于 SO 编辑器只展示该方法范围。 */
    @ColumnInfo(name = "function_size")
    val functionSize: Long? = null,

    @ColumnInfo(name = "pp_count")
    val ppCount: Int = 0,

    /** Blutter 反汇编伪代码（methods.src_code），用于 ASM 浏览。 */
    @ColumnInfo(name = "src_code")
    val srcCode: String? = null
)
