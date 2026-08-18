package com.ai.fler.core.analysis

import com.ai.fler.data.AppDatabase
import com.ai.fler.data.dao.AnalysisDao
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
import java.io.RandomAccessFile
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
 * 二期（混淆包增强）：对 src_code 为空的方法，用 Capstone 直接反汇编 libapp.so
 * 的 .text 机器码提取 `bl #imm` / `b #imm`——混淆包 Blutter 的 src_code 大字段
 * 多为空，但机器码结构不变，可弥补调用图稀疏（实测 43 边 → 数千边）。
 *
 * 坐标：functionOffset 为 ELF 虚拟地址（libapp 上 fileOffset == functionOffset），
 * src_code 内的 `0x..:` 亦为 vaddr，可直接二分定位到目标方法。
 */
@Singleton
class DartCallGraphBuilder @Inject constructor(
    private val dartMethodDao: DartMethodDao,
    private val dartCallGraphDao: DartCallGraphDao,
    private val appDatabase: AppDatabase,
    private val analysisDao: AnalysisDao,
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

        // 1b) 机器码边界估计：为 function_size=0 的方法估算真实 end vaddr，
        //     提高 findContaining 精度（占位方法内部 bl 归属更准确）。
        val libappPath = analysisDao.getById(analysisId)?.libappPath
        if (libappPath != null) {
            val text = textSection(libappPath)
            if (text != null) {
                val ends = DartFunctionBoundary.estimateEnds(
                    soPath = libappPath,
                    text = text,
                    funcs = funcs.map { it.entry },
                )
                for (f in funcs) {
                    ends[f.offset]?.let { f.endVaddr = it }
                }
            }
        }

        // 2) 逐页拉取方法体并解析直连调用边（按 caller|callee 去重）。
        val edges = HashMap<Long, DartCallEdge>()
        val emptySrcOffsets = HashSet<Long>()
        var page = 0
        var parsedMethods = 0
        var srcMissingMethods = 0
        while (true) {
            val rows = dartMethodDao.getSrcPage(analysisId, page * PAGE_SIZE, PAGE_SIZE)
            if (rows.isEmpty()) break
            for (row in rows) {
                val caller = byOffset[row.functionOffset] ?: continue
                val src = row.srcCode ?: ""
                // 占位符 src（<anonymous closure>/<unknown>/空）无真实反汇编文本，
                // 与空串同等对待：交给机器码扫描补边。
                if (src.isNotBlank() && !DartNameDisplay.isPlaceholder(src.trim())) {
                    collectEdges(analysisId, src, caller, funcs, edges)
                    parsedMethods++
                } else {
                    srcMissingMethods++
                    emptySrcOffsets.add(row.functionOffset)
                }
            }
            if (rows.size < PAGE_SIZE) break
            page++
        }
        // 3) 机器码补充：src_code 为空的方法直接反汇编 .text 提取 bl/b。
        //    混淆包 Blutter 的 src_code 大字段多为空，机器码结构不变，可显著补全调用图。
        val machineParsed = if (libappPath != null && srcMissingMethods > 0) {
            collectEdgesFromMachineCode(analysisId, libappPath, funcs, emptySrcOffsets, edges)
        } else 0
        dartCallGraphDao.deleteByAnalysisId(analysisId)
        if (edges.isNotEmpty()) bulkInsert(edges.values)
        builtIds.add(analysisId)
        android.util.Log.i(
            "DartCallGraphBuilder",
            "建图完成 analysis=$analysisId parsed=$parsedMethods machine=$machineParsed 边=${edges.size} 耗时=${System.currentTimeMillis()-t0}ms"
        )
        edges.size
    }

    /**
     * 对 src_code 为空的方法，用 Capstone 直接反汇编 libapp.so 的 .text 机器码提取
     * `bl #imm` / `b #imm` 边。分块读取 + 反汇编，把命中指令归属到包含该指令
     * vaddr 的方法，且仅当该方法是空 src 方法（[emptySrcOffsets]）才建边，
     * 避免与步骤 2 的 src_code 解析重复计数。
     *
     * @param emptySrcOffsets src_code 为空的方法 functionOffset 集合
     * @return 处理的块数（进度参考）
     */
    private suspend fun collectEdgesFromMachineCode(
        analysisId: Long,
        soPath: String,
        funcs: List<Func>,
        emptySrcOffsets: Set<Long>,
        edges: HashMap<Long, DartCallEdge>,
    ): Int = withContext(Dispatchers.IO) {
        val text = textSection(soPath) ?: return@withContext 0
        var blocks = 0
        RandomAccessFile(soPath, "r").use { raf ->
            val buf = ByteArray(MACHINE_CHUNK)
            var filePos = text.fileOffset
            val textEnd = text.fileOffset + text.fileSize
            while (filePos < textEnd) {
                val want = minOf(MACHINE_CHUNK.toLong(), textEnd - filePos).toInt()
                raf.seek(filePos)
                val read = raf.read(buf, 0, want)
                if (read <= 0) break
                val block = buf.copyOf(read)
                val baseVaddr = text.vaddrBase + (filePos - text.fileOffset)
                val insns = com.ai.fler.core.jni.CapstoneBindings.disassembleWithCapstone(block, baseVaddr)
                    ?: run { filePos += read; continue }
                val lines = ArrayList<String>(insns.size)
                for (ins in insns) {
                    val mnemonic = ins.mnemonic
                    val isCall = mnemonic == "bl"
                    val isBranch = mnemonic == "b" || mnemonic.startsWith("b.")
                    if (!isCall && !isBranch) continue
                    val op = ins.opStr
                    // Capstone 的 bl/b 目标 opStr 形如 `0x6f6ec4`（无 #），collectEdges 要求 `#0x..`，
                    // 这里规范化：已是 # 开头直接保留，纯 hex 补 #，其余（寄存器等）跳过。
                    val normalizedOp = when {
                        op.contains('#') -> op
                        HEX_ONLY.matches(op.trim()) -> "#" + op.trim()
                        else -> continue
                    }
                    lines.add("// 0x${ins.address.toString(16)}: $mnemonic $normalizedOp")
                }
                for (line in lines) {
                    val colon = line.indexOf(':', 2)
                    if (colon <= 2) continue
                    val site = strip0x(line.substring(2, colon).trim()).toLongOrNull(16) ?: continue
                    val caller = findContaining(funcs, site) ?: continue
                    if (caller.offset !in emptySrcOffsets) continue
                    collectEdges(analysisId, line, caller, funcs, edges)
                }
                filePos += read
                blocks++
            }
        }
        blocks
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

    /** 给定目标地址，二分返回包含它的方法；找不到返回 null。
     *  兼容混淆方法 function_size=0：优先用机器码边界估计出的 [endVaddr]（[DartFunctionBoundary]），
     *  未知时按「下一方法 start」为界，[start, nextStart) 归当前方法
     *  （与 FunctionIndex.Snapshot.findContaining 语义一致）。 */
    private fun findContaining(funcs: List<Func>, target: Long): Func? {
        var lo = 0
        var hi = funcs.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (funcs[mid].offset <= target) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return null
        // 从最近的起点向前找一个覆盖 target 的方法。
        for (i in idx downTo 0) {
            val f = funcs[i]
            val nextStart = if (i + 1 < funcs.size) funcs[i + 1].offset else Long.MAX_VALUE
            val effectiveHi = when {
                f.endVaddr > 0 -> f.endVaddr
                f.size > 0 -> f.offset + f.size
                else -> nextStart
            }
            if (target < effectiveHi) return f
            if (f.endVaddr > 0 || f.size > 0) break
        }
        return null
    }

    private fun fullName(m: MethodLight): String =
        DartNameDisplay.displayFullName(m._className, m.methodName, m.functionOffset)

    /** 用 ELF 解析器取 .text 节区间（文件坐标 + vaddr 基线）。 */
    private fun textSection(soPath: String): com.ai.fler.core.analysis.StringXrefScanner.TextRange? =
        com.ai.fler.core.jni.ElfParserBindings().use { parser ->
            if (!parser.open(soPath)) null
            else parser.getSections().firstOrNull { it.name == ".text" }?.let {
                com.ai.fler.core.analysis.StringXrefScanner.TextRange(it.offset, it.size, it.address)
            }
        }

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
        var endVaddr: Long = 0L, // 边界估计出的真实 end（0=未知，退回 size/nextStart）
    ) {
        val id: Long get() = entry.id
    }

    companion object {
        private const val PAGE_SIZE = 1200
        private const val KIND_DIRECT_CALL = "DIRECT_CALL"
        private const val KIND_DIRECT_BRANCH = "DIRECT_BRANCH"

        /** 机器码反汇编分块大小（同 StringXrefScanner 的 256KB 流式）。 */
        private const val MACHINE_CHUNK = 256 * 1024

        /** 纯十六进制目标（bl/b 的操作数，如 `0x6f6ec4`）。 */
        private val HEX_ONLY = Regex("0[xX][0-9a-fA-F]+")

        /** 内存边索引最多缓存的分析数（每个约 10~30MB，控内存）。 */
        private const val MAX_CACHED_ANALYSES = 2

        private const val INSERT_SQL =
            "INSERT OR REPLACE INTO dart_call_edges " +
            "(analysis_id,caller_method_id,caller_name,caller_vaddr," +
            "callee_method_id,callee_name,callee_vaddr,callee_kind,site_vaddr) " +
            "VALUES (?,?,?,?,?,?,?,?,?)"

    }
}