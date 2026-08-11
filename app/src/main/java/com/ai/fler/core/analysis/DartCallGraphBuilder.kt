package com.ai.fler.core.analysis

import com.ai.fler.data.AppDatabase
import com.ai.fler.data.dao.CallerInfo
import com.ai.fler.data.dao.DartCallGraphDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.MethodLight
import com.ai.fler.data.entity.DartCallEdge
import androidx.sqlite.db.SupportSQLiteStatement
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Dart 方法调用图构建器（真实交叉引用）。
 *
 * 从各方法已入库的 src_code（Blutter 反汇编伪代码，Arm64 指令文本形如
 * `// 0x...: bl  #0x...`）解析**直接调用/尾跳转**边，得到方法级调用图，
 * 落库 [DartCallEdge]。不依赖 Rizin 会话，纯 DB 离线构建。
 *
 * 一期只解析目标直接可见的 `bl #imm` / `b #imm`；间接派发（`blr x` + PP 槽）
 * 属二期，暂不产生边。
 *
 * 坐标：functionOffset 为 ELF 虚拟地址（libapp 上 fileOffset == functionOffset），
 * src_code 内的 `0x..:` 亦为 vaddr，可直接二分定位到目标方法。
 */
@Singleton
class DartCallGraphBuilder @Inject constructor(
    private val dartMethodDao: DartMethodDao,
    private val dartCallGraphDao: DartCallGraphDao,
    private val appDatabase: AppDatabase
) {

    /** 常驻后台作用域（建图不随请求/ViewModel 取消而中断）。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 正在构建的分析集合（去重并发触发）。 */
    private val buildingIds = ConcurrentHashMap.newKeySet<Long>()

    /** 已完成建图的分析集合（进程内缓存，"已建完"含 0 边情形）。 */
    private val builtIds = ConcurrentHashMap.newKeySet<Long>()

    /** 分析 id -> 内存边索引（懒加载）。仅 [builtIds] 命中后构建；受 [edgeIndexLock] 保护。 */
    private val edgeIndexes = HashMap<Long, EdgeIndex>()
    private val edgeIndexLock = Any()

    /** 该方法是否已建图：内存缓存命中直接返回，否则一次轻量 EXISTS 判空并回填。 */
    suspend fun isBuilt(analysisId: Long): Boolean {
        if (analysisId in builtIds) return true
        val built = dartCallGraphDao.hasEdges(analysisId)
        if (built) builtIds.add(analysisId)
        return built
    }

    /** 是否已建完（进程内已完成 build，含 0 边）；供 MCP 状态字段免费读取。 */
    fun hasCompleted(analysisId: Long): Boolean = analysisId in builtIds

    /** 是否正在为该分析构建（避免重复触发）。 */
    fun isBuilding(analysisId: Long): Boolean = buildingIds.contains(analysisId)

    /** 是否已建图或正在建图（UI/MCP 提示用）。 */
    suspend fun isReadyOrBuilding(analysisId: Long): Boolean =
        isBuilt(analysisId) || isBuilding(analysisId)

    /**
     * 使该分析的内存缓存（builtIds/边索引）失效。删除分析后调用，
     * 避免查询到残留状态。
     */
    fun invalidate(analysisId: Long) {
        builtIds.remove(analysisId)
        synchronized(edgeIndexLock) { edgeIndexes.remove(analysisId) }
    }

    /**
     * 非阻塞确保建图：未建图且未在构建时，后台起一个常驻协程构建，立即返回。
     * 构建跑在独立 [scope]（不随调用方取消），完成后由后续查询读取落库结果。
     */
    suspend fun ensureAsync(analysisId: Long) {
        if (isBuilt(analysisId) || !buildingIds.add(analysisId)) return
        android.util.Log.i("DartCallGraphBuilder", "后台建图启动 analysis=$analysisId")
        scope.launch {
            try {
                build(analysisId)
            } catch (e: Exception) {
                android.util.Log.w("DartCallGraphBuilder", "建图失败: analysis=$analysisId", e)
            } finally {
                buildingIds.remove(analysisId)
            }
        }
    }

    /**
     * 同步构建：等待本次构建完成返回边数（供显式触发/验证用，会阻塞调用线程）。
     */
    suspend fun buildAndAwait(analysisId: Long): Int = build(analysisId)

    /**
     * 为某次分析构建调用图（幂等：先清空该分析旧边再重建）。
     * 内部切到 Default 线程执行，调用方无需关心线程。
     *
     * @return 本次落库的边数（去重后）。
     */
    suspend fun build(analysisId: Long): Int = withContext(Dispatchers.Default) {
        invalidate(analysisId)
        val t0 = System.currentTimeMillis()
        android.util.Log.i("DartCallGraphBuilder", "build 开始 analysis=$analysisId")
        // 1) 方法索引（含类名投影，无 src 大字段），排序供二分。
        val funcs = dartMethodDao.getByAnalysisIdLight(analysisId)
            .filter { it.functionOffset != null && it.functionOffset!! > 0 }
            .map { Func(it, fullName(it)) }
            .sortedBy { it.offset }

        val byOffset = HashMap<Long, Func>(funcs.size)
        for (f in funcs) byOffset[f.offset] = f

        // 2) 逐页拉取方法体并解析直连调用边（按 caller|callee 去重）。
        val edges = HashMap<Long, DartCallEdge>()
        var page = 0
        var parsedMethods = 0
        while (true) {
            val rows = dartMethodDao.getSrcPage(analysisId, page * PAGE_SIZE, PAGE_SIZE)
            if (rows.isEmpty()) break
            for (row in rows) {
                val caller = byOffset[row.functionOffset] ?: continue
                val src = row.srcCode ?: continue
                collectEdges(analysisId, src, caller, funcs, edges)
                parsedMethods++
            }
            if (rows.size < PAGE_SIZE) break
            page++
        }
        dartCallGraphDao.deleteByAnalysisId(analysisId)
        if (edges.isNotEmpty()) bulkInsert(edges.values)
        builtIds.add(analysisId)
        android.util.Log.i("DartCallGraphBuilder", "建图完成 analysis=$analysisId parsed=$parsedMethods 边=${edges.size} 耗时=${System.currentTimeMillis()-t0}ms")
        edges.size
    }

    /** 该分析总边数：内存索引已加载则免费读取，否则一次 COUNT（显式状态查询可接受）。 */
    suspend fun edgeCount(analysisId: Long): Int {
        synchronized(edgeIndexLock) { edgeIndexes[analysisId] }?.let { return it.edgeCount }
        return dartCallGraphDao.countByAnalysisId(analysisId)
    }

    /**
     * 按被调方法名子串反查调用方（不区分大小写），走内存索引，避免每次请求 SQL LIKE 全表扫描。
     *
     * @return 图未就绪（未建完/分析失效）时返回 null，调用方据此回 `graphBuilt=false`；
     *         已建完但无匹配返回非 null 空列表。
     */
    suspend fun findCallersByName(analysisId: Long, query: String, limit: Int): EdgeQueryResult? {
        if (analysisId !in builtIds && !isBuilt(analysisId)) return null
        val q = query.lowercase(Locale.ROOT)
        val idx = synchronized(edgeIndexLock) { edgeIndexes[analysisId] }
            ?: loadIndex(analysisId)?.also { loaded ->
                synchronized(edgeIndexLock) {
                    edgeIndexes.putIfAbsent(analysisId, loaded)
                    trimLocked()
                }
            }
            ?: return EdgeQueryResult(emptyList(), 0)
        val out = ArrayList<CallerInfo>(minOf(limit, 16))
        for (r in idx.callers) {
            if (r.calleeNameLower.contains(q)) {
                out.add(CallerInfo(r.methodId, r.callerName, r.callerVaddr, r.targetVaddr, r.siteVaddr, analysisId))
                if (out.size >= limit) break
            }
        }
        return EdgeQueryResult(out, idx.edgeCount)
    }

    /** 从 DB 一次性加载该分析全部边构建内存索引；无边返回 null。 */
    private suspend fun loadIndex(analysisId: Long): EdgeIndex? {
        val all = dartCallGraphDao.getAllByAnalysisId(analysisId)
        if (all.isEmpty()) return null
        val recs = ArrayList<EdgeIndex.CallerRec>(all.size)
        for (e in all) {
            recs.add(
                EdgeIndex.CallerRec(
                    calleeNameLower = e.calleeName.orEmpty().lowercase(Locale.ROOT),
                    methodId = e.callerMethodId,
                    callerName = e.callerName.orEmpty(),
                    callerVaddr = e.callerVaddr,
                    targetVaddr = e.calleeVaddr,
                    siteVaddr = e.siteVaddr,
                )
            )
        }
        return EdgeIndex(recs.size, recs)
    }

    /** 超出 [MAX_CACHED_ANALYSES] 时淘汰最早加载的索引，控制内存（调用方需持 [edgeIndexLock]）。 */
    private fun trimLocked() {
        while (edgeIndexes.size > MAX_CACHED_ANALYSES) {
            val oldest = edgeIndexes.keys.firstOrNull() ?: break
            edgeIndexes.remove(oldest)
        }
    }

    /**
     * 原始语句批量插入（单事务 + 复用编译语句），避开 Room @Insert 逐实体装箱，
     * 十万级边 ~1s 内完成；插入后再批量刷新索引。
     */
    private fun bulkInsert(edges: Collection<DartCallEdge>) {
        val db = appDatabase.openHelper.writableDatabase
        db.beginTransaction()
        try {
            val stmt: SupportSQLiteStatement = db.compileStatement(INSERT_SQL)
            try {
                for (e in edges) {
                    stmt.bindLong(1, e.analysisId)
                    stmt.bindLong(2, e.callerMethodId)
                    stmt.bindString(3, e.callerName.orEmpty())
                    stmt.bindLong(4, e.callerVaddr)
                    stmt.bindLong(5, e.calleeMethodId ?: 0)
                    stmt.bindString(6, e.calleeName.orEmpty())
                    stmt.bindLong(7, e.calleeVaddr)
                    stmt.bindString(8, e.calleeKind)
                    stmt.bindLong(9, e.siteVaddr)
                    stmt.executeInsert()
                }
            } finally {
                stmt.close()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 解析单方法反汇编文本里的直连调用/跳转，写入边集合。逐行手动解析，避免正则对大量行的开销。 */
    private fun collectEdges(
        analysisId: Long,
        src: String,
        caller: Func,
        funcs: List<Func>,
        edges: HashMap<Long, DartCallEdge>
    ) {
        val lo = caller.offset
        val hi = caller.offset + caller.size
        for (line in src.lineSequence()) {
            if (line.length < 4 || line[0] != '/' || line[1] != '/') continue
            val colon = line.indexOf(':', 2)
            if (colon <= 2) continue
            val site = strip0x(line.substring(2, colon).trim()).toLongOrNull(16) ?: continue

            val rest = line.substring(colon + 1).trim()
            if (rest.isEmpty()) continue
            val sp = nextSpace(rest)
            val mnemonic = if (sp < 0) rest else rest.substring(0, sp)
            val isCall = mnemonic == "bl"
            val isBranch = mnemonic == "b" || mnemonic.startsWith("b.")
            if (!isCall && !isBranch) continue

            // 操作数里的立即目标（间接寄存器调用如 blr x16 无 #0x 操作数，自动跳过）
            val op = if (sp < 0) "" else rest.substring(sp + 1)
            val hashIdx = op.indexOf('#')
            if (hashIdx < 0) continue
            var j = hashIdx + 1
            // 跳过 `0x` 前缀，否则 hex 长度在 'x' 处截断，只取到 '0' → target=0
            if (j + 1 < op.length && op[j] == '0' && (op[j + 1] == 'x' || op[j + 1] == 'X')) j += 2
            val hexStart = j
            var hexEnd = j
            while (hexEnd < op.length && isHexDigit(op[hexEnd])) hexEnd++
            if (hexEnd == hexStart) continue
            val target = op.substring(hexStart, hexEnd).toLongOrNull(16) ?: continue
            if (target <= 0) continue

            // 函数内跳转不算调用边（不含自递归）。
            // 注意用半开区间 [lo, hi)：下一条方法恰接在 caller 结束地址（hi）时，
            // 闭区间 `in lo..hi` 会把 tail-call 到紧邻下一方法的 bl 误判为函数内跳转而漏边。
            if (target >= lo && target < hi) continue
            val callee = findContaining(funcs, target) ?: continue
            if (callee.id == caller.id) continue

            val key = (caller.id shl 32) or callee.id
            if (edges.containsKey(key)) continue
            edges[key] = DartCallEdge(
                analysisId = analysisId,
                callerMethodId = caller.id,
                callerName = caller.name,
                callerVaddr = caller.offset,
                calleeMethodId = callee.id,
                calleeName = callee.name,
                calleeVaddr = callee.offset,
                calleeKind = if (isCall) KIND_DIRECT_CALL else KIND_DIRECT_BRANCH,
                siteVaddr = site
            )
        }
    }

    private fun nextSpace(s: String): Int {
        var i = 0
        while (i < s.length) {
            if (s[i] == ' ') return i
            i++
        }
        return -1
    }

    private inline fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    /** Kotlin toLongOrNull(16) 不接受 `0x` 前缀，这里去掉。 */
    private fun strip0x(s: String): String =
        if (s.length > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s.substring(2) else s

    /** 给定目标地址，二分返回包含它的方法；找不到返回 null。 */
    private fun findContaining(funcs: List<Func>, target: Long): Func? {
        var lo = 0
        var hi = funcs.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (funcs[mid].offset <= target) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return null
        // 从最近的起点向前找一个 size 覆盖 target 的方法。
        // 用半开区间 [f.offset, f.offset+size)：target 恰等于方法结束地址时
        // 不算包含（应归属下一个紧邻方法），与 collectEdges 保持一致。
        for (i in idx downTo 0) {
            val f = funcs[i]
            if (f.size > 0 && target < f.offset + f.size) return f
            if (f.size > 0 && target >= f.offset + f.size) break
        }
        return null
    }

    private fun fullName(m: MethodLight): String = "${m._className}.${m.methodName}"

    /** 名字子串反查结果：命中列表 + 该分析总边数（供状态字段）。 */
    class EdgeQueryResult(
        val callers: List<CallerInfo>,
        val edgeCount: Int,
    )

    /** 单分析的只读内存边索引（calleeName 已小写化，便于子串过滤）。 */
    private class EdgeIndex(
        val edgeCount: Int,
        val callers: List<CallerRec>,
    ) {
        data class CallerRec(
            val calleeNameLower: String,
            val methodId: Long,
            val callerName: String,
            val callerVaddr: Long,
            val targetVaddr: Long,
            val siteVaddr: Long,
        )
    }

    private class Func(
        val entry: MethodLight,
        val name: String,
        val offset: Long = entry.functionOffset ?: 0,
        val size: Long = entry.functionSize ?: 0,
    ) {
        val id: Long get() = entry.id
    }

    companion object {
        private const val PAGE_SIZE = 1200
        private const val KIND_DIRECT_CALL = "DIRECT_CALL"
        private const val KIND_DIRECT_BRANCH = "DIRECT_BRANCH"

        /** 内存边索引最多缓存的分析数（每个约 10~30MB，控内存）。 */
        private const val MAX_CACHED_ANALYSES = 2

        private const val INSERT_SQL =
            "INSERT OR REPLACE INTO dart_call_edges " +
            "(analysis_id,caller_method_id,caller_name,caller_vaddr," +
            "callee_method_id,callee_name,callee_vaddr,callee_kind,site_vaddr) " +
            "VALUES (?,?,?,?,?,?,?,?,?)"

    }
}