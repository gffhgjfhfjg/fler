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

    /** 每个类的方法数（SQL 下推：GROUP BY 统计，供 list_classes 避免全量载入方法表）。 */
    @Query(
        "SELECT class_id AS classId, COUNT(*) AS methodCount FROM dart_methods " +
            "WHERE analysis_id = :analysisId GROUP BY class_id"
    )
    suspend fun countMethodsGroupedByClass(analysisId: Long): List<ClassMethodCount>

    /** 按 id 取方法 + 所属类名。 */
    @Query(
        "SELECT dm.*, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.id = :id"
    )
    suspend fun getMethodWithClassById(id: Long): MethodWithClass?

    /** 按方法名精确查找（某次分析内）。 */
    @Query(
        "SELECT dm.*, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId AND dm.method_name = :name LIMIT 1"
    )
    suspend fun getMethodWithClassByName(analysisId: Long, name: String): MethodWithClass?

    /** 按类取方法（带类名）。 */
    @Query(
        "SELECT dm.*, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId AND dm.class_id = :classId " +
            "ORDER BY dm.method_name"
    )
    suspend fun getMethodsByClassIdWithClass(analysisId: Long, classId: Long): List<MethodWithClass>

    /** 组合过滤 + 分页（SQL 下推）：类名/方法名可选过滤。 */
    @Query(
        "SELECT dm.*, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId " +
            "AND (:name IS NULL OR dm.method_name LIKE '%' || :name || '%') " +
            "AND (:classId IS NULL OR dm.class_id = :classId) " +
            "ORDER BY dc.class_name, dm.method_name " +
            "LIMIT :limit OFFSET :offset"
    )
    suspend fun searchMethodsWithClass(
        analysisId: Long,
        name: String?,
        classId: Long?,
        limit: Int,
        offset: Int,
    ): List<MethodWithClass>

    /** 组合过滤的计数（配合分页）。 */
    @Query(
        "SELECT COUNT(*) FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId " +
            "AND (:name IS NULL OR dm.method_name LIKE '%' || :name || '%') " +
            "AND (:classId IS NULL OR dm.class_id = :classId)"
    )
    suspend fun countMethodsWithClass(analysisId: Long, name: String?, classId: Long?): Int

    /** 在 src_code 中搜索（调用关系 / PP 引用等）。 */
    @Query(
        "SELECT dm.*, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId AND dm.src_code LIKE '%' || :target || '%' " +
            "ORDER BY dc.class_name, dm.method_name " +
            "LIMIT :limit"
    )
    suspend fun searchSrcWithClass(analysisId: Long, target: String, limit: Int): List<MethodWithClass>

    /** 方法 + 所属类名（供 ASM 方法列表展示）。 */
    @Query(
        "SELECT dm.*, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId " +
            "ORDER BY dc.class_name, dm.method_name"
    )
    suspend fun getMethodsWithClass(analysisId: Long): List<MethodWithClass>

    /** 按 libapp SO 路径查找所有 Dart 方法（带类名），用于 SO 编辑器注入函数标签。 */
    @Query(
        "SELECT dm.*, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "INNER JOIN analyses a ON dm.analysis_id = a.id " +
            "WHERE a.libapp_path = :soPath AND dm.function_offset > 0 " +
            "ORDER BY dm.function_offset"
    )
    suspend fun getMethodsBySoPath(soPath: String): List<MethodWithClass>
}

/** 方法 + 所属类名投影。 */
data class MethodWithClass(
    @Embedded val method: DartMethod,
    @androidx.room.ColumnInfo(name = "_class_name") val _className: String
)

/** 类 -> 方法数投影（GROUP BY 统计结果）。 */
data class ClassMethodCount(
    val classId: Long,
    val methodCount: Int,
)
