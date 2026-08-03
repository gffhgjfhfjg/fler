package com.ai.fler.core.service

import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.entity.DartClass
import com.ai.fler.data.entity.DartMethod
import com.ai.fler.data.entity.PpEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Blutter 分析结果导入器。
 *
 * blutter_analyze() 把分析结果直接写入 SQLite（cache/analysis_{id}.db），
 * 该数据库的表结构由 fler-dart 引擎决定，与 App 的 Room schema 不同。
 * 本类把 Blutter DB 中的 classes/methods/pp_entries/strings 表
 * 防御式地读入 Room（DartClass/DartMethod/PpEntry），并回写统计计数。
 *
 * 防御式说明：
 * - 用 sqlite_master 枚举实际存在的表，缺失的表跳过
 * - 用 PRAGMA table_info 读取实际列名，按列名（非序号）取值
 * - 单表失败不影响其他表
 */
@Singleton
class AnalysisImporter @Inject constructor(
    private val analysisDao: AnalysisDao,
    private val dartClassDao: DartClassDao,
    private val dartMethodDao: DartMethodDao,
    private val ppEntryDao: PpEntryDao,
) {
    companion object {
        private const val TAG = "AnalysisImporter"
        private const val UNKNOWN_CLASS = "<unknown>"
        private const val UNKNOWN_METHOD = "<unknown>"

        /** 单批插入上限：避免一次性绑定数万条参数，SQLite 变量数有上限（默认 999）。 */
        private const val BATCH_SIZE = 500
    }

    /** 导入结果统计。 */
    data class ImportResult(
        val classesCount: Int = 0,
        val methodsCount: Int = 0,
        val ppEntriesCount: Int = 0,
    )

    /**
     * 导入指定分析结果到 Room。
     *
     * @param analysisId App 侧 Analysis 记录 ID（必须先于本调用创建）
     * @param dbPath blutter_analyze 生成的 SQLite 绝对路径
     * @return 各类导入计数；失败/无文件时返回全 0
     */
    suspend fun import(analysisId: Long, dbPath: String): ImportResult = withContext(Dispatchers.IO) {
        val dbFile = File(dbPath)
        if (!dbFile.exists() || dbFile.length() == 0L) {
            Log.w(TAG, "Blutter DB 不存在或为空: $dbPath")
            return@withContext ImportResult()
        }

        var classes = 0
        var methods = 0
        var pp = 0

        try {
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val tables = listTables(db)

                // ========== classes ==========
                val blutterClassIdToRoomId = LinkedHashMap<Long, Long>()
                if ("classes" in tables) {
                    try {
                        val rows = readClasses(db)
                        val entities = rows.map { (_, name, superCls) ->
                            DartClass(
                                analysisId = analysisId,
                                className = name,
                                libraryPath = "",
                                superClass = superCls,
                            )
                        }
                        if (entities.isNotEmpty()) {
                            // 分批插入并保持顺序，保证 blutterId -> RoomId 映射正确
                            val ids = mutableListOf<Long>()
                            for (batch in entities.chunked(BATCH_SIZE)) {
                                ids += dartClassDao.insertAll(batch)
                            }
                            rows.forEachIndexed { i, (blutterId, _, _) ->
                                if (i < ids.size) blutterClassIdToRoomId[blutterId] = ids[i]
                            }
                        }
                        classes = entities.size
                        Log.i(TAG, "导入 classes: $classes 条")
                    } catch (e: Exception) {
                        Log.e(TAG, "导入 classes 失败", e)
                    }
                }

                // 兜底 class（方法可能引用不存在的 class；也用于孤立 pp 条目）
                val unknownClassId = dartClassDao.insert(
                    DartClass(analysisId = analysisId, className = UNKNOWN_CLASS, libraryPath = "")
                )

                // 兜底 method（Blutter 的 pp_entries / strings 没有 method 关联）
                val unknownMethodId = dartMethodDao.insert(
                    DartMethod(
                        analysisId = analysisId,
                        classId = unknownClassId,
                        methodName = UNKNOWN_METHOD,
                        selector = UNKNOWN_METHOD,
                    )
                )

                // ========== methods ==========
                if ("methods" in tables) {
                    try {
                        val rows = readMethods(db)
                        if (rows.isNotEmpty()) {
                            val entities = rows.map { (classId, name, address, size, srcCode) ->
                                DartMethod(
                                    analysisId = analysisId,
                                    classId = blutterClassIdToRoomId[classId] ?: unknownClassId,
                                    methodName = name,
                                    selector = name,
                                    signature = null,
                                    functionOffset = address.takeIf { it > 0 },
                                    // 保存方法字节长度，供 SO 编辑器只展示该方法范围
                                    functionSize = size.takeIf { it > 0 },
                                    srcCode = srcCode,
                                )
                            }
                            entities.chunked(BATCH_SIZE).forEach { batch ->
                                dartMethodDao.insertAll(batch)
                            }
                        }
                        methods = rows.size
                        Log.i(TAG, "导入 methods: $methods 条")
                    } catch (e: Exception) {
                        Log.e(TAG, "导入 methods 失败", e)
                    }
                }

                // ========== pp_entries ==========
                if ("pp_entries" in tables) {
                    try {
                        val rows = readPpEntries(db)
                        if (rows.isNotEmpty()) {
                            val entities = rows.map { (ppOffset, type, value, soAddr) ->
                                PpEntry(
                                    methodId = unknownMethodId,
                                    analysisId = analysisId,
                                    vmOffset = ppOffset,
                                    fileOffset = soAddr,
                                    description = value ?: type,
                                    type = type,
                                )
                            }
                            entities.chunked(BATCH_SIZE).forEach { batch ->
                                ppEntryDao.insertAll(batch)
                            }
                        }
                        pp += rows.size
                        Log.i(TAG, "导入 pp_entries: ${rows.size} 条")
                    } catch (e: Exception) {
                        Log.e(TAG, "导入 pp_entries 失败", e)
                    }
                }

                // ========== strings ==========
                if ("strings" in tables) {
                    try {
                        val rows = readStrings(db)
                        if (rows.isNotEmpty()) {
                            val entities = rows.map { (ppOffset, value) ->
                                PpEntry(
                                    methodId = unknownMethodId,
                                    analysisId = analysisId,
                                    vmOffset = ppOffset,
                                    fileOffset = 0,
                                    description = value,
                                    type = "String",
                                )
                            }
                            entities.chunked(BATCH_SIZE).forEach { batch ->
                                ppEntryDao.insertAll(batch)
                            }
                        }
                        pp += rows.size
                        Log.i(TAG, "导入 strings: ${rows.size} 条")
                    } catch (e: Exception) {
                        Log.e(TAG, "导入 strings 失败", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "打开/读取 Blutter DB 失败: $dbPath", e)
            return@withContext ImportResult()
        }

        val result = ImportResult(classesCount = classes, methodsCount = methods, ppEntriesCount = pp)
        // 回写统计计数，产物页据此展示真实数据
        try {
            analysisDao.updateCounts(analysisId, classes, methods, pp)
        } catch (e: Exception) {
            Log.e(TAG, "回写统计计数失败", e)
        }
        Log.i(TAG, "导入完成: classes=$classes, methods=$methods, pp=$pp")
        result
    }

    // ========== 读取辅助 ==========

    private fun listTables(db: SQLiteDatabase): Set<String> {
        val tables = mutableSetOf<String>()
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null).use { c ->
            while (c.moveToNext()) {
                tables.add(c.getString(0))
            }
        }
        return tables
    }

    private fun columnNames(db: SQLiteDatabase, table: String): Set<String> {
        val names = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table)", null).use { c ->
            while (c.moveToNext()) {
                names.add(c.getString(1))
            }
        }
        return names
    }

    /** Blutter classes: id, name, super_cls, fields。 */
    private fun readClasses(db: SQLiteDatabase): List<Triple<Long, String, String?>> {
        val cols = columnNames(db, "classes")
        val idxId = cols.indexOf("id")
        val idxName = cols.indexOf("name")
        val idxSuper = cols.indexOf("super_cls")
        if (idxId < 0 || idxName < 0) return emptyList()

        val result = mutableListOf<Triple<Long, String, String?>>()
        db.rawQuery("SELECT * FROM classes", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(idxName)?.takeIf { it.isNotBlank() } ?: continue
                result.add(
                    Triple(
                        c.getLong(idxId),
                        name,
                        if (idxSuper >= 0) c.getString(idxSuper) else null,
                    )
                )
            }
        }
        return result
    }

    /** Blutter methods: id, class_id, name, address, size, src_code。 */
    private fun readMethods(db: SQLiteDatabase): List<MethodRow> {
        val cols = columnNames(db, "methods")
        val idxClass = cols.indexOf("class_id")
        val idxName = cols.indexOf("name")
        if (idxClass < 0 || idxName < 0) return emptyList()
        val idxAddress = cols.indexOf("address")
        val idxSize = cols.indexOf("size")
        val idxSrc = cols.indexOf("src_code")

        val result = mutableListOf<MethodRow>()
        db.rawQuery("SELECT * FROM methods", null).use { c ->
            while (c.moveToNext()) {
                val name = c.getString(idxName)?.takeIf { it.isNotBlank() } ?: continue
                result.add(
                    MethodRow(
                        classId = c.getLong(idxClass),
                        name = name,
                        address = if (idxAddress >= 0) c.getLong(idxAddress) else 0L,
                        size = if (idxSize >= 0) c.getLong(idxSize) else 0L,
                        srcCode = if (idxSrc >= 0) c.getString(idxSrc) else null,
                    )
                )
            }
        }
        return result
    }

    /** Blutter pp_entries: pp_offset, type, value, so_addr。 */
    private fun readPpEntries(db: SQLiteDatabase): List<PpRow> {
        val cols = columnNames(db, "pp_entries")
        val idxOff = cols.indexOf("pp_offset")
        if (idxOff < 0) return emptyList()
        val idxType = cols.indexOf("type")
        val idxValue = cols.indexOf("value")
        val idxSoAddr = cols.indexOf("so_addr")

        val result = mutableListOf<PpRow>()
        db.rawQuery("SELECT * FROM pp_entries", null).use { c ->
            while (c.moveToNext()) {
                val type = if (idxType >= 0) c.getString(idxType) else null
                val value = if (idxValue >= 0) c.getString(idxValue) else null
                if (type.isNullOrBlank() && value.isNullOrBlank()) continue
                result.add(
                    PpRow(
                        ppOffset = c.getLong(idxOff),
                        type = type ?: "unknown",
                        value = value,
                        soAddr = if (idxSoAddr >= 0) c.getLong(idxSoAddr) else 0L,
                    )
                )
            }
        }
        return result
    }

    /** Blutter strings: pp_offset(UNIQUE), value, ref_count。 */
    private fun readStrings(db: SQLiteDatabase): List<Pair<Long, String?>> {
        val cols = columnNames(db, "strings")
        val idxOff = cols.indexOf("pp_offset")
        val idxValue = cols.indexOf("value")
        if (idxOff < 0 || idxValue < 0) return emptyList()

        val result = mutableListOf<Pair<Long, String?>>()
        db.rawQuery("SELECT * FROM strings", null).use { c ->
            while (c.moveToNext()) {
                val value = c.getString(idxValue)
                if (value.isNullOrBlank()) continue
                result.add(c.getLong(idxOff) to value)
            }
        }
        return result
    }

    private data class MethodRow(
        val classId: Long,
        val name: String,
        val address: Long,
        val size: Long,
        val srcCode: String?,
    )

    private data class PpRow(
        val ppOffset: Long,
        val type: String,
        val value: String?,
        val soAddr: Long,
    )
}
