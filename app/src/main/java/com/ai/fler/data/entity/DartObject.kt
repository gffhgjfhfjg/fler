package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 对象池对象条目（引擎 objs.txt 轻量索引）。
 *
 * 每个对象地址对应一个 Dart 堆对象（如 Obj!qCb@b42021【至尊永久VIP】level 5）。
 * field_hint 是引擎从 dumpInstance 文本提炼的摘要（字符串/int 字段），非全量字段。
 */
@Entity(
    tableName = "objs",
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
        Index(value = ["analysis_id", "class_name"]),
        Index(value = ["analysis_id", "obj_address"])
    ]
)
data class DartObject(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "analysis_id")
    val analysisId: Long,

    @ColumnInfo(name = "obj_address")
    val objAddress: Long,

    @ColumnInfo(name = "class_name")
    val className: String?,

    /** 字段摘要，如 "off_8: "至尊永久VIP", off_c: int(0x5)"。 */
    @ColumnInfo(name = "field_hint")
    val fieldHint: String?
)