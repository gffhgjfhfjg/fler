package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.Analysis
import kotlinx.coroutines.flow.Flow

/**
 * 分析记录 DAO。
 * 提供分析记录的 CRUD 操作和统计查询。
 */
@Dao
interface AnalysisDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(analysis: Analysis): Long

    @Query("DELETE FROM analyses WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM analyses WHERE project_id = :projectId")
    suspend fun deleteByProjectId(projectId: Long)

    @Query("SELECT * FROM analyses WHERE id = :id")
    suspend fun getById(id: Long): Analysis?

    @Query("SELECT * FROM analyses WHERE project_id = :projectId ORDER BY started_at DESC")
    fun getByProjectId(projectId: Long): Flow<List<Analysis>>

    /**
     * 同步获取项目下所有分析记录（非 Flow）。
     *
     * 用于级联删除：在 @Transaction 内需要立即拿到结果列表，不能用 Flow。
     */
    @Query("SELECT * FROM analyses WHERE project_id = :projectId")
    suspend fun getByProjectIdList(projectId: Long): List<Analysis>

    @Query("UPDATE analyses SET completed_at = :completedAt, result_code = :resultCode WHERE id = :id")
    suspend fun completeAnalysis(id: Long, resultCode: Int, completedAt: Long = System.currentTimeMillis())

    @Query("UPDATE analyses SET result_code = :resultCode, error_message = :errorMessage WHERE id = :id")
    suspend fun updateResult(id: Long, resultCode: Int, errorMessage: String?)

    @Query("UPDATE analyses SET classes_count = :classesCount, methods_count = :methodsCount, pp_entries_count = :ppEntriesCount WHERE id = :id")
    suspend fun updateCounts(id: Long, classesCount: Int, methodsCount: Int, ppEntriesCount: Int)

    @Query("UPDATE analyses SET libapp_path = :libappPath, libflutter_path = :libflutterPath WHERE id = :id")
    suspend fun updateLibPaths(id: Long, libappPath: String?, libflutterPath: String?)

    @Query("SELECT * FROM analyses ORDER BY started_at DESC LIMIT :limit")
    suspend fun getRecentList(limit: Int): List<Analysis>
}
