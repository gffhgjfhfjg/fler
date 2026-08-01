package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.DartMethod

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

    @Query("DELETE FROM dart_methods WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM dart_methods WHERE id = :id")
    suspend fun getById(id: Long): DartMethod?

    @Query("SELECT * FROM dart_methods WHERE analysis_id = :analysisId ORDER BY method_name")
    suspend fun getByAnalysisIdList(analysisId: Long): List<DartMethod>

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
