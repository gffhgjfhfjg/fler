package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.Library

/**
 * 库信息 DAO。
 * 提供库信息的 CRUD 操作和查询方法。
 */
@Dao
interface LibraryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(library: Library): Long

    @Query("DELETE FROM libraries WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM libraries WHERE analysis_id = :analysisId ORDER BY library_name")
    suspend fun getByAnalysisIdList(analysisId: Long): List<Library>

    /** 按 so 绝对路径反查库记录（APK 回打时定位所属分析/项目）。 */
    @Query("SELECT * FROM libraries WHERE path = :path LIMIT 1")
    suspend fun getByPath(path: String): Library?
}
