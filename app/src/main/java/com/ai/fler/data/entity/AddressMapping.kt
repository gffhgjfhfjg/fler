package com.ai.fler.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 地址映射实体。
 * 存储 Dart VM 偏移、ELF 文件偏移和虚拟地址之间的映射关系。
 */
@Entity(
    tableName = "address_mappings",
    indices = [
        Index(value = ["project_id"]),
        Index(value = ["vm_offset"]),
        Index(value = ["file_offset"]),
        Index(value = ["elf_address"])
    ]
)
data class AddressMapping(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0,

    @ColumnInfo(name = "project_id")
    val projectId: Long,

    @ColumnInfo(name = "vm_offset")
    val vmOffset: Long,

    @ColumnInfo(name = "file_offset")
    val fileOffset: Long,

    @ColumnInfo(name = "elf_address")
    val elfAddress: Long,

    @ColumnInfo(name = "section")
    val section: String = "",

    @ColumnInfo(name = "symbol")
    val symbol: String? = null
)
