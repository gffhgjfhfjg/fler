package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ai.fler.data.entity.DartClass
import kotlinx.coroutines.flow.Flow

/**
 * Dart 类 DAO。
 * 提供 Dart 类的 CRUD 操作和查询方法。
 */
@Dao
interface DartClassDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dartClass: DartClass): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dartClasses: List<DartClass>): List<Long>

    @Update
    suspend fun update(dartClass: DartClass)

    @Query("DELETE FROM dart_classes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM dart_classes WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM dart_classes WHERE id = :id")
    suspend fun getById(id: Long): DartClass?

    @Query("SELECT * FROM dart_classes WHERE analysis_id = :analysisId ORDER BY class_name")
    fun getByAnalysisId(analysisId: Long): Flow<List<DartClass>>

    @Query("SELECT * FROM dart_classes WHERE analysis_id = :analysisId ORDER BY class_name")
    suspend fun getByAnalysisIdList(analysisId: Long): List<DartClass>

    @Query("SELECT * FROM dart_classes WHERE analysis_id = :analysisId AND class_name LIKE '%' || :query || '%' ORDER BY class_name")
    fun searchByAnalysisId(analysisId: Long, query: String): Flow<List<DartClass>>

    @Query("SELECT * FROM dart_classes WHERE library_path = :libraryPath ORDER BY class_name")
    fun getByLibrary(libraryPath: String): Flow<List<DartClass>>

    @Query("SELECT COUNT(*) FROM dart_classes WHERE analysis_id = :analysisId")
    suspend fun countByAnalysisId(analysisId: Long): Int

    @Query("SELECT COUNT(DISTINCT library_path) FROM dart_classes WHERE analysis_id = :analysisId")
    suspend fun countLibrariesByAnalysisId(analysisId: Long): Int
}
