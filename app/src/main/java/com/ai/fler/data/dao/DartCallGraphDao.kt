package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.DartCallEdge

/**
 * Dart 调用图边 DAO。
 * 供 MCP（get_method_callers / get_method_callees）与 App SO 编辑器
 * 交叉引用面板读取真实调用关系。
 */
@Dao
interface DartCallGraphDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(edges: List<DartCallEdge>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(edge: DartCallEdge): Long

    @Query("DELETE FROM dart_call_edges WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT COUNT(*) FROM dart_call_edges WHERE analysis_id = :analysisId")
    suspend fun countByAnalysisId(analysisId: Long): Int

    /** 该分析是否有至少 1 条边（轻量判空，供建图就绪快判）。 */
    @Query("SELECT EXISTS(SELECT 1 FROM dart_call_edges WHERE analysis_id = :analysisId)")
    suspend fun hasEdges(analysisId: Long): Boolean

    /** 取该分析全部边（供内存索引一次加载）。 */
    @Query("SELECT * FROM dart_call_edges WHERE analysis_id = :analysisId")
    suspend fun getAllByAnalysisId(analysisId: Long): List<DartCallEdge>

    /** 该方法调用了谁（callerMethodId 作为源）。 */
    @Query(
        "SELECT callee_method_id AS methodId, callee_name AS name, callee_vaddr AS vaddr, " +
            "callee_kind AS kind, site_vaddr AS siteVaddr, analysis_id AS analysisId " +
            "FROM dart_call_edges WHERE analysis_id = :analysisId AND caller_method_id = :callerId " +
            "ORDER BY site_vaddr LIMIT :limit"
    )
    suspend fun calleesOf(analysisId: Long, callerId: Long, limit: Int): List<CalleeInfo>

    /** 谁调用了指定方法（callee_method_id 匹配）。 */
    @Query(
        "SELECT caller_method_id AS methodId, caller_name AS name, caller_vaddr AS vaddr, " +
            "callee_vaddr AS targetVaddr, site_vaddr AS siteVaddr, analysis_id AS analysisId " +
            "FROM dart_call_edges WHERE analysis_id = :analysisId AND callee_method_id = :calleeId " +
            "ORDER BY site_vaddr LIMIT :limit"
    )
    suspend fun callersOfMethod(analysisId: Long, calleeId: Long, limit: Int): List<CallerInfo>

    /** 谁调用了某目标地址（callee_vaddr 精确命中，用于编辑器点击地址）。 */
    @Query(
        "SELECT caller_method_id AS methodId, caller_name AS name, caller_vaddr AS vaddr, " +
            "callee_vaddr AS targetVaddr, site_vaddr AS siteVaddr, analysis_id AS analysisId " +
            "FROM dart_call_edges WHERE analysis_id = :analysisId AND callee_vaddr = :address " +
            "ORDER BY site_vaddr LIMIT :limit"
    )
    suspend fun callersOfVaddr(analysisId: Long, address: Long, limit: Int): List<CallerInfo>

    /** 谁调用了名字含 calleeName 的方法（MCP get_method_callers 帮助）。 */
    @Query(
        "SELECT caller_method_id AS methodId, caller_name AS name, caller_vaddr AS vaddr, " +
            "callee_vaddr AS targetVaddr, site_vaddr AS siteVaddr, analysis_id AS analysisId " +
            "FROM dart_call_edges WHERE analysis_id = :analysisId AND callee_name LIKE '%' || :name || '%' " +
            "ORDER BY site_vaddr LIMIT :limit"
    )
    suspend fun callersByName(analysisId: Long, name: String, limit: Int): List<CallerInfo>
}

/** 被调方投影（calleesOf 结果）。 */
data class CalleeInfo(
    val methodId: Long?,
    val name: String,
    val vaddr: Long,
    val kind: String,
    val siteVaddr: Long,
    val analysisId: Long,
)

/** 调用方投影（callersOf* 结果）。 */
data class CallerInfo(
    val methodId: Long,
    val name: String,
    val vaddr: Long,
    val targetVaddr: Long,
    val siteVaddr: Long,
    val analysisId: Long,
)