package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.PpEntry
import kotlinx.coroutines.flow.Flow

/**
 * 补丁点条目 DAO。
 * 提供补丁点条目的 CRUD 操作和查询方法。
 */
@Dao
interface PpEntryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ppEntries: List<PpEntry>): List<Long>

    @Query("DELETE FROM pp_entries WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM pp_entries WHERE analysis_id = :analysisId ORDER BY vm_offset")
    fun getByAnalysisId(analysisId: Long): Flow<List<PpEntry>>

    @Query("SELECT * FROM pp_entries WHERE analysis_id = :analysisId ORDER BY vm_offset")
    suspend fun getByAnalysisIdList(analysisId: Long): List<PpEntry>

    @Query("SELECT * FROM pp_entries WHERE analysis_id = :analysisId AND type = 'String' ORDER BY vm_offset")
    fun getStringsByAnalysisId(analysisId: Long): Flow<List<PpEntry>>

    @Query("SELECT * FROM pp_entries WHERE analysis_id = :analysisId AND type = 'String' ORDER BY vm_offset")
    suspend fun getStringsByAnalysisIdList(analysisId: Long): List<PpEntry>

    @Query("SELECT * FROM pp_entries WHERE analysis_id = :analysisId AND type = 'String' AND description LIKE '%' || :query || '%' LIMIT :limit")
    suspend fun searchStrings(analysisId: Long, query: String, limit: Int): List<PpEntry>

    @Query("SELECT * FROM pp_entries WHERE analysis_id = :analysisId AND vm_offset = :vmOffset")
    suspend fun getPpByVmOffset(analysisId: Long, vmOffset: Long): List<PpEntry>

    @Query("SELECT * FROM pp_entries WHERE analysis_id = :analysisId ORDER BY caller_count DESC LIMIT :limit")
    fun getTopCallersByAnalysisId(analysisId: Long, limit: Int = 50): Flow<List<PpEntry>>

    @Query("SELECT COUNT(*) FROM pp_entries WHERE analysis_id = :analysisId")
    suspend fun countByAnalysisId(analysisId: Long): Int

    @Query("SELECT COUNT(*) FROM pp_entries WHERE analysis_id = :analysisId AND is_leaf = 1")
    suspend fun countLeavesByAnalysisId(analysisId: Long): Int
}
