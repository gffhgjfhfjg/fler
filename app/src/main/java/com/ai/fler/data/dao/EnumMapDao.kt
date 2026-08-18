package com.ai.fler.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ai.fler.data.entity.EnumMap
import kotlinx.coroutines.flow.Flow

/**
 * 枚举索引映射 DAO（引擎 enum_map 表）。
 */
@Dao
interface EnumMapDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<EnumMap>)

    @Query("DELETE FROM enum_map WHERE analysis_id = :analysisId")
    suspend fun deleteByAnalysisId(analysisId: Long)

    @Query("SELECT * FROM enum_map WHERE analysis_id = :analysisId ORDER BY class_name, enum_index")
    fun getByAnalysisId(analysisId: Long): Flow<List<EnumMap>>

    @Query("SELECT * FROM enum_map WHERE analysis_id = :analysisId ORDER BY class_name, enum_index")
    suspend fun getByAnalysisIdList(analysisId: Long): List<EnumMap>

    /** 按枚举类名查该类的全部枚举值。 */
    @Query(
        "SELECT * FROM enum_map WHERE analysis_id = :analysisId AND class_name = :className " +
            "ORDER BY enum_index"
    )
    suspend fun getByClass(analysisId: Long, className: String): List<EnumMap>

    /** 按枚举名反查（如搜「至尊」返回该枚举所有值）。 */
    @Query(
        "SELECT * FROM enum_map WHERE analysis_id = :analysisId AND enum_name LIKE '%' || :query || '%' " +
            "ORDER BY class_name, enum_index LIMIT :limit"
    )
    suspend fun searchByName(analysisId: Long, query: String, limit: Int): List<EnumMap>

    @Query("SELECT COUNT(*) FROM enum_map WHERE analysis_id = :analysisId")
    suspend fun countByAnalysisId(analysisId: Long): Int
}