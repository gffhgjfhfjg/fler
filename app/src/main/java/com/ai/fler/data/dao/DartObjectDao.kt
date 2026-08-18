package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.DartObject
import kotlinx.coroutines.flow.Flow

/**
 * 对象池对象索引 DAO（引擎 objs.txt 轻量索引）。
 */
@Dao
interface DartObjectDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(objects: List<DartObject>)

    @Query("DELETE FROM objs WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM objs WHERE analysis_id = :analysisId ORDER BY obj_address")
    fun getByAnalysisId(analysisId: Long): Flow<List<DartObject>>

    @Query("SELECT * FROM objs WHERE analysis_id = :analysisId ORDER BY obj_address")
    suspend fun getByAnalysisIdList(analysisId: Long): List<DartObject>

    /** 按地址精确查对象。 */
    @Query("SELECT * FROM objs WHERE analysis_id = :analysisId AND obj_address = :address")
    suspend fun getByAddress(analysisId: Long, address: Long): List<DartObject>

    /** 按类名子串模糊搜索对象。 */
    @Query(
        "SELECT * FROM objs WHERE analysis_id = :analysisId AND class_name LIKE '%' || :query || '%' " +
            "ORDER BY obj_address LIMIT :limit"
    )
    suspend fun searchObjects(analysisId: Long, query: String, limit: Int): List<DartObject>

    /** 在字段摘要中搜索字符串值（如搜「至尊」命中 field_hint 含 VIP 的对象）。 */
    @Query(
        "SELECT * FROM objs WHERE analysis_id = :analysisId " +
            "AND (class_name LIKE '%' || :query || '%' OR field_hint LIKE '%' || :query || '%') " +
            "ORDER BY obj_address LIMIT :limit"
    )
    suspend fun searchObjectsAnywhere(analysisId: Long, query: String, limit: Int): List<DartObject>

    @Query("SELECT COUNT(*) FROM objs WHERE analysis_id = :analysisId")
    suspend fun countByAnalysisId(analysisId: Long): Int
}