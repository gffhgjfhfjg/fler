package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 项目实体。
 * 存储用户创建的分析项目基本信息。
 */
@Entity(
    tableName = "projects",
    indices = [
        Index(value = ["apk_path"], unique = true),
        Index(value = ["created_at"])
    ]
)
data class Project(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "apk_path")
    val apkPath: String,

    @ColumnInfo(name = "package_name")
    val packageName: String? = null,

    @ColumnInfo(name = "apk_version")
    val apkVersion: String? = null,

    @ColumnInfo(name = "dart_version")
    val dartVersion: String? = null,

    @ColumnInfo(name = "engine_version")
    val engineVersion: String? = null,

    @ColumnInfo(name = "status")
    val status: String = STATUS_CREATED,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATUS_CREATED = "created"
        const val STATUS_EXTRACTING = "extracting"
        const val STATUS_ANALYZING = "analyzing"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }
}
