package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.AddressMapping

/**
 * 地址映射 DAO。
 * 提供地址映射的 CRUD 操作和查询方法。
 */
@Dao
interface AddressMappingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(addressMappings: List<AddressMapping>): List<Long>

    @Query("DELETE FROM address_mappings WHERE project_id = :projectId")
    suspend fun deleteByProjectId(projectId: Long)

    @Query("SELECT * FROM address_mappings WHERE vm_offset = :vmOffset LIMIT 1")
    suspend fun findByVmOffset(vmOffset: Long): AddressMapping?

    @Query("SELECT * FROM address_mappings WHERE file_offset = :fileOffset LIMIT 1")
    suspend fun findByFileOffset(fileOffset: Long): AddressMapping?

    @Query("SELECT * FROM address_mappings WHERE elf_address = :elfAddress LIMIT 1")
    suspend fun findByElfAddress(elfAddress: Long): AddressMapping?
}
