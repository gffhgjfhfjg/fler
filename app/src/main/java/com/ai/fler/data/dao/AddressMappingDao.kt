package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.ai.fler.data.entity.AddressMapping

/**
 * 地址映射 DAO。
 * 提供地址映射的 CRUD 操作和查询方法。
 */
@Dao
abstract class AddressMappingDao {

    /** 单批插入上限：避免 SQLite 绑定变量数超限（默认 999）。 */
    private companion object {
        const val BATCH_SIZE = 500
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertBatch(addressMappings: List<AddressMapping>): List<Long>

    /** 全量插入包在单个事务内分批执行：55781 条无事务逐条 INSERT 需数分钟，
     * 单事务内执行可降至秒级（Room 的 @Insert(List) 是逐条语句，事务由本方法统一管理）。 */
    @Transaction
    open suspend fun insertAllInTransaction(addressMappings: List<AddressMapping>) {
        addressMappings.chunked(BATCH_SIZE).forEach { batch ->
            insertBatch(batch)
        }
    }

    @Query("DELETE FROM address_mappings WHERE project_id = :projectId")
    abstract suspend fun deleteByProjectId(projectId: Long)

    @Query("SELECT * FROM address_mappings WHERE vm_offset = :vmOffset LIMIT 1")
    abstract suspend fun findByVmOffset(vmOffset: Long): AddressMapping?

    @Query("SELECT * FROM address_mappings WHERE file_offset = :fileOffset LIMIT 1")
    abstract suspend fun findByFileOffset(fileOffset: Long): AddressMapping?

    @Query("SELECT * FROM address_mappings WHERE elf_address = :elfAddress LIMIT 1")
    abstract suspend fun findByElfAddress(elfAddress: Long): AddressMapping?
}
