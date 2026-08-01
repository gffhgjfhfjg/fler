package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.DartClass

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

    @Query("DELETE FROM dart_classes WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM dart_classes WHERE analysis_id = :analysisId ORDER BY class_name")
    suspend fun getByAnalysisIdList(analysisId: Long): List<DartClass>
}
