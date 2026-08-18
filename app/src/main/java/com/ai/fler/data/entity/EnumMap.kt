package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 枚举索引映射（引擎 enum_map 表）。
 *
 * 枚举对象地址不在 pp.txt 中，但 Blutter objs.txt 里每个枚举值对应一个
 * 常量对象（如 Obj!qCb@b42021【至尊永久VIP】level 5），其中 level 5 的 5 是枚举序号，
 * 【至尊永久VIP】是枚举值名称。本表把枚举值名 → 序号映射记录下来，便于 UI 反查。
 *
 * 同一枚举类下的同名值可对应多个对象（同值多实例），故主键按 analysis+class+index 唯一，
 * 同 index 若有多名则取首个。
 */
@Entity(
    tableName = "enum_map",
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
        Index(value = ["analysis_id", "enum_index"])
    ]
)
data class EnumMap(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "analysis_id")
    val analysisId: Long,

    @ColumnInfo(name = "class_name")
    val className: String,

    @ColumnInfo(name = "enum_index")
    val enumIndex: Int,

    @ColumnInfo(name = "enum_name")
    val enumName: String
)