package com.ai.fler.data.dao

import androidx.room.Dao
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

    @Update
    suspend fun update(project: Project)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM projects ORDER BY updated_at DESC")
    fun getAll(): Flow<List<Project>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun getById(id: Long): Project?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun getByIdFlow(id: Long): Flow<Project?>
}
