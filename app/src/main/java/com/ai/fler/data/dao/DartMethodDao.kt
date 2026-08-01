package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ai.fler.data.entity.DartMethod
import kotlinx.coroutines.flow.Flow

/**
 * Dart 方法 DAO。
 * 提供 Dart 方法的 CRUD 操作和查询方法。
 */
@Dao
interface DartMethodDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dartMethod: DartMethod): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(dartMethods: List<DartMethod>): List<Long>

    @Update
    suspend fun update(dartMethod: DartMethod)

    @Query("DELETE FROM dart_methods WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM dart_methods WHERE class_id = :classId")
    suspend fun deleteByClassId(classId: Long)

    @Query("DELETE FROM dart_methods WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM dart_methods WHERE id = :id")
    suspend fun getById(id: Long): DartMethod?

    @Query("SELECT * FROM dart_methods WHERE class_id = :classId ORDER BY method_name")
    fun getByClassId(classId: Long): Flow<List<DartMethod>>

    @Query("SELECT * FROM dart_methods WHERE class_id = :classId ORDER BY method_name")
    suspend fun getByClassIdList(classId: Long): List<DartMethod>

    @Query("SELECT * FROM dart_methods WHERE analysis_id = :analysisId ORDER BY method_name")
    fun getByAnalysisId(analysisId: Long): Flow<List<DartMethod>>

    @Query("SELECT * FROM dart_methods WHERE analysis_id = :analysisId ORDER BY method_name")
    suspend fun getByAnalysisIdList(analysisId: Long): List<DartMethod>

    @Query("SELECT * FROM dart_methods WHERE analysis_id = :analysisId AND method_name LIKE '%' || :query || '%' ORDER BY method_name")
    fun searchByAnalysisId(analysisId: Long, query: String): Flow<List<DartMethod>>

    @Query("SELECT * FROM dart_methods WHERE class_id = :classId AND method_name LIKE '%' || :query || '%' ORDER BY method_name")
    fun searchByClassId(classId: Long, query: String): Flow<List<DartMethod>>

    @Query("SELECT * FROM dart_methods WHERE pp_count > 0 ORDER BY pp_count DESC")
    fun getWithPpEntries(): Flow<List<DartMethod>>

    @Query("SELECT COUNT(*) FROM dart_methods WHERE analysis_id = :analysisId")
    suspend fun countByAnalysisId(analysisId: Long): Int

    @Query("SELECT COUNT(*) FROM dart_methods WHERE class_id = :classId")
    suspend fun countByClassId(classId: Long): Int

    /** 方法 + 所属类名（供 ASM 方法列表展示）。 */
    @Query(
        "SELECT dm.*, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId " +
            "ORDER BY dc.class_name, dm.method_name"
    )
    suspend fun getMethodsWithClass(analysisId: Long): List<MethodWithClass>
}

/** 方法 + 所属类名投影。 */
data class MethodWithClass(
    @Embedded val method: DartMethod,
    @androidx.room.ColumnInfo(name = "_class_name") val _className: String
)
