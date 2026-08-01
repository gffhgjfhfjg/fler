package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.AddressMapping
import kotlinx.coroutines.flow.Flow

/**
 * 地址映射 DAO。
 * 提供地址映射的 CRUD 操作和查询方法。
 */
@Dao
interface AddressMappingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(addressMapping: AddressMapping): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(addressMappings: List<AddressMapping>): List<Long>

    @Query("DELETE FROM address_mappings WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM address_mappings WHERE project_id = :projectId")
    suspend fun deleteByProjectId(projectId: Long)

    @Query("SELECT * FROM address_mappings WHERE id = :id")
    suspend fun getById(id: Long): AddressMapping?

    @Query("SELECT * FROM address_mappings WHERE project_id = :projectId")
    fun getByProjectId(projectId: Long): Flow<List<AddressMapping>>

    @Query("SELECT * FROM address_mappings WHERE project_id = :projectId")
    suspend fun getByProjectIdList(projectId: Long): List<AddressMapping>

    @Query("SELECT * FROM address_mappings WHERE vm_offset = :vmOffset LIMIT 1")
    suspend fun findByVmOffset(vmOffset: Long): AddressMapping?

    @Query("SELECT * FROM address_mappings WHERE file_offset = :fileOffset LIMIT 1")
    suspend fun findByFileOffset(fileOffset: Long): AddressMapping?

    @Query("SELECT * FROM address_mappings WHERE elf_address = :elfAddress LIMIT 1")
    suspend fun findByElfAddress(elfAddress: Long): AddressMapping?

    @Query("SELECT * FROM address_mappings WHERE project_id = :projectId AND vm_offset >= :minVmOffset AND vm_offset <= :maxVmOffset ORDER BY vm_offset")
    fun findByVmOffsetRange(projectId: Long, minVmOffset: Long, maxVmOffset: Long): Flow<List<AddressMapping>>

    @Query("SELECT COUNT(*) FROM address_mappings WHERE project_id = :projectId")
    suspend fun countByProjectId(projectId: Long): Int
}
