package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ai.fler.data.entity.Project
import kotlinx.coroutines.flow.Flow

/**
 * 项目 DAO。
 * 提供项目的 CRUD 操作和查询方法。
 */
@Dao
interface ProjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(project: Project): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(projects: List<Project>): List<Long>

    @Update
    suspend fun update(project: Project)

    @Delete
    suspend fun delete(project: Project)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM projects")
    suspend fun deleteAll()

    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    fun getAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): Project?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Project?>

    @Query("SELECT * FROM projects WHERE apk_path = :apkPath")
    suspend fun getByApkPath(apkPath: String): Project?

    @Query("SELECT * FROM projects WHERE status = :status ORDER BY updated_at DESC")
    fun getByStatus(status: String): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE name LIKE '%' || :query || '%' ORDER BY updated_at DESC")
    fun searchByName(query: String): Flow<List<Project>>

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM projects WHERE status = :status")
    suspend fun countByStatus(status: String): Int
}
