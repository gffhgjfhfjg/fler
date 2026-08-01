package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.Library
import kotlinx.coroutines.flow.Flow

/**
 * 库信息 DAO。
 * 提供库信息的 CRUD 操作和查询方法。
 */
@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(library: Library): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(libraries: List<Library>): List<Long>

    @Query("DELETE FROM libraries WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM libraries WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM libraries WHERE id = :id")
    suspend fun getById(id: Long): Library?

    @Query("SELECT * FROM libraries WHERE analysis_id = :analysisId ORDER BY library_name")
    fun getByAnalysisId(analysisId: Long): Flow<List<Library>>

    @Query("SELECT * FROM libraries WHERE analysis_id = :analysisId ORDER BY library_name")
    suspend fun getByAnalysisIdList(analysisId: Long): List<Library>

    @Query("SELECT * FROM libraries WHERE analysis_id = :analysisId AND is_dart_snapshot = 1")
    suspend fun getDartSnapshotsByAnalysisId(analysisId: Long): List<Library>

    @Query("SELECT * FROM libraries WHERE path = :path")
    suspend fun getByPath(path: String): Library?

    @Query("SELECT COUNT(*) FROM libraries WHERE analysis_id = :analysisId")
    suspend fun countByAnalysisId(analysisId: Long): Int
}
