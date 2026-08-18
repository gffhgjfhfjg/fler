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

    /** 轻量投影（不含 src_code 大字段）：供 AddressTranslator 构建地址映射。
     * 55781 条方法全量载入 src_code 会占用数百 MB 内存，此处只取必要列。 */
    @Query(
        "SELECT dm.id, dm.class_id, dm.method_name, dm.selector, dm.function_offset, dm.function_size, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId"
    )
    suspend fun getByAnalysisIdLight(analysisId: Long): List<MethodLight>

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

    /** 按 function_offset（vaddr）精确查找（某次分析内），供 sub_<vaddr> 显示名反查。 */
    @Query(
        "SELECT dm.*, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId AND dm.function_offset = :offset LIMIT 1"
    )
    suspend fun getMethodWithClassByOffset(analysisId: Long, offset: Long): MethodWithClass?

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

    /** Keyset 分页：方法列表（轻量投影，不含 src_code）。 */
    @Query(
        "SELECT dm.id, dm.class_id, dm.method_name, dm.selector, dm.function_offset, dm.function_size, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id = :analysisId " +
            "AND (:lastClassName = '' OR (dc.class_name > :lastClassName OR (dc.class_name = :lastClassName AND dm.method_name > :lastMethodName))) " +
            "ORDER BY dc.class_name, dm.method_name " +
            "LIMIT :pageSize"
    )
    suspend fun getMethodPage(
        analysisId: Long,
        lastClassName: String = "",
        lastMethodName: String = "",
        pageSize: Int = 200
    ): List<MethodLight>

    /** 按 SO 路径查找所有 Dart 方法（带类名），用于 SO 编辑器注入函数标签。
     * 限定 library_name='libapp.so'：dart_classes/dart_methods 是 Blutter 从 libapp.so
     * 提取的（Blutter 只分析 libapp.so），而 libraries 表里 libapp.so 与 libflutter.so
     * 等共用同一 analysis_id——不限定库名时按 libflutter.so 的 path 查询也会命中
     * libapp.so 的全部方法，导致给 libflutter.so 注入错位标签并污染标签缓存。 */
    @Query(
        "SELECT dm.id, dm.class_id, dm.method_name, dm.selector, dm.function_offset, dm.function_size, dc.class_name AS _class_name FROM dart_methods dm " +
            "INNER JOIN dart_classes dc ON dm.class_id = dc.id " +
            "WHERE dm.analysis_id IN (SELECT l.analysis_id FROM libraries l WHERE l.path = :soPath AND l.library_name = 'libapp.so') " +
            "AND dm.function_offset > 0 " +
            "ORDER BY dm.function_offset"
    )
    suspend fun getMethodsBySoPathLight(soPath: String): List<MethodLight>

    /** 分页取方法体（functionOffset + src_code），供 DartCallGraphBuilder 解析调用边。
     * 一次只取一页避免把数万条大文本 src_code 全载入内存。 */
    @Query(
        "SELECT function_offset AS functionOffset, function_size AS functionSize, src_code AS srcCode " +
            "FROM dart_methods WHERE analysis_id = :analysisId AND function_offset > 0 " +
            "ORDER BY function_offset LIMIT :pageSize OFFSET :offset"
    )
    suspend fun getSrcPage(
        analysisId: Long,
        offset: Int,
        pageSize: Int
    ): List<MethodSrcRow>
}

/** 方法 + 所属类名投影。 */
data class MethodWithClass(
    @Embedded val method: DartMethod,
    @androidx.room.ColumnInfo(name = "_class_name") val _className: String
)

/** 轻量方法投影（不含 src_code/signature 等大文本字段）。 */
data class MethodLight(
    val id: Long = 0,
    @androidx.room.ColumnInfo(name = "class_id") val classId: Long = 0,
    @androidx.room.ColumnInfo(name = "method_name") val methodName: String = "",
    val selector: String = "",
    @androidx.room.ColumnInfo(name = "function_offset") val functionOffset: Long? = null,
    @androidx.room.ColumnInfo(name = "function_size") val functionSize: Long? = null,
    @androidx.room.ColumnInfo(name = "_class_name") val _className: String = "",
)

/** 类 -> 方法数投影（GROUP BY 统计结果）。 */
data class ClassMethodCount(
    val classId: Long,
    val methodCount: Int,
)

/** 方法体行投影（functionOffset + src_code），供调用图构建分页拉取。 */
data class MethodSrcRow(
    val functionOffset: Long,
    val functionSize: Long,
    val srcCode: String?,
)
