package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.ai.fler.data.entity.HookScript
import kotlinx.coroutines.flow.Flow

/**
 * Hook 脚本 CRUD DAO（落地于 app 数据库，随应用长期保存）。
 */
@Dao
interface HookScriptDao {

    /** 全部脚本，最近更新在前。 */
    @Query("SELECT * FROM hook_scripts ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<HookScript>>

    @Query("SELECT * FROM hook_scripts WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): HookScript?

    @Query("SELECT * FROM hook_scripts WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): HookScript?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(script: HookScript): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(scripts: List<HookScript>)

    @Update
    suspend fun update(script: HookScript)

    @Delete
    suspend fun delete(script: HookScript)

    @Query("DELETE FROM hook_scripts WHERE id = :id")
    suspend fun deleteById(id: Long)
}