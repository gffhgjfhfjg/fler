package com.ai.fler.core.mcp

import android.annotation.SuppressLint
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import com.ai.fler.core.analysis.DartCallGraphBuilder
import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.ElfParserBindings
import com.ai.fler.core.jni.KeystoneBindings
import com.ai.fler.core.service.AddressTranslator
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.entity.Analysis
import com.ai.fler.data.dao.DartCallGraphDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.features.mcp.McpPatchService
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MCP 工具处理器：把 fler 现有服务映射为 MCP 工具。
 * 所有只读工具在 Dispatchers.IO 上运行（由调用方调度）。
 */
@Singleton
class McpToolHandlers @Inject constructor(
    private val analysisDao: AnalysisDao,
    private val libraryDao: LibraryDao,
    private val dartClassDao: DartClassDao,
    private val dartMethodDao: DartMethodDao,
    private val ppEntryDao: PpEntryDao,
    private val projectDao: ProjectDao,
    private val addressTranslator: AddressTranslator,
    private val config: McpConfig,
    private val patchService: McpPatchService,
    private val engineMcp: EngineMcpToolRegistry,
    private val emulationMcp: EmulationMcpToolRegistry,
    private val axisResolver: AddressAxisResolver,
    private val dartCallGraphDao: DartCallGraphDao,
    private val callGraphBuilder: DartCallGraphBuilder,
    @SuppressLint("StaticFieldLeak")
    @ApplicationContext private val context: Context,
) : McpResourceProvider {

    class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val handler: suspend (JsonObject) -> JsonElement,
    )

    // ========== 坐标换算辅助 ==========

    /** 请求级 DB 缓存：避免单个工具内重复 getById（Analysis 行数据无热点变化）。 */
    private class AnalysisCache(private val dao: AnalysisDao) {
        private val map = java.util.concurrent.ConcurrentHashMap<Long, Cached>()
        private class Cached(val analysis: Analysis, val ts: Long)

        suspend fun get(id: Long): Analysis? {
            map[id]?.let { c -> if (System.currentTimeMillis() - c.ts < TTL_MS) return c.analysis }
            val a = dao.getById(id)
            if (a != null) map[id] = Cached(a, System.currentTimeMillis())
            trim()
            return a
        }

        private fun trim() {
            val now = System.currentTimeMillis()
            val it = map.entries.iterator()
            while (it.hasNext()) if (now - it.next().value.ts >= TTL_MS) it.remove()
            while (map.size > MAX_SIZE) {
                val oldest = map.entries.minByOrNull { it.value.ts }?.key ?: break
                map.remove(oldest)
            }
        }

        companion object {
            private const val TTL_MS = 60_000L
            private const val MAX_SIZE = 64
        }
    }

    private val analysisCache = AnalysisCache(analysisDao)

    /** 缓存版 getById。 */
    private suspend fun cachedAnalysis(id: Long): Analysis? = analysisCache.get(id)

    /** 分析对应的 libapp.so 路径（坐标换算用）。 */
    private suspend fun libAppPath(analysisId: Long): String? =
        cachedAnalysis(analysisId)?.libappPath

    /** functionOffset(vaddr) → 文件偏移；so 不可用或地址无效时返回 null。
     *  歧义（同节偏差≠0，数字同时是文件偏移与 vaddr）时按 vaddr 解读取 altFileOffset，
     *  因为方法工具的 functionOffset 恒为 Blutter 给的 vaddr。 */
    private fun fileOffsetOf(soPath: String?, functionOffset: Long): Long? {
        if (soPath == null || functionOffset <= 0) return null
        val res = axisResolver.resolve(soPath, functionOffset) ?: return null
        return if (res.ambiguous) (res.altFileOffset ?: res.fileOffset) else res.fileOffset
    }

    /** 触发建图但不阻塞请求；建图完成后查询自动读到新边。 */
    private suspend fun ensureGraph(analysisId: Long) {
        callGraphBuilder.ensureAsync(analysisId)
    }

    val tools: Map<String, McpTool> = buildMap {
        buildList {
            addAll(buildAnalysisTools())
            addAll(buildBrowseTools())
            addAll(buildDisasmTools())
            addAll(buildPatchTools())
        }.forEach { this[it.name] = it }
        // Engine 能力自动暴露的工具（带 engine_ 前缀）
        engineMcp.buildTools().forEach { (k, v) -> this[k] = v }
        // 仿真工具（带 emu_ 前缀，Unicorn 会话/调用/寄存器/内存/断点）
        emulationMcp.buildTools().forEach { (k, v) -> this[k] = v }
    }

    // ========== 参数读取辅助 ==========

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    // ========== 分析工具 ==========

    private fun buildAnalysisTools(): List<McpTool> = listOf(
        McpTool(
            name = "list_analyses",
            description = "列出 App 内所有 Blutter 分析记录（近 200 条）：含 id（analysisId，供后续 list_methods/get_method 等用）、类/方法/PP 计数、libapp.so 路径。分析对应一次 libapp.so 的 Blutter 恢复结果",
            inputSchema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) }
        ) { _ ->
            val rows = analysisDao.getRecentList(200)
            buildJsonArray {
                rows.forEach { a ->
                    addJsonObject {
                        put("id", a.id)
                        put("projectId", a.projectId)
                        put("libappPath", a.libappPath ?: "")
                        put("resultCode", a.resultCode)
                        put("classesCount", a.classesCount)
                        put("methodsCount", a.methodsCount)
                        put("ppEntriesCount", a.ppEntriesCount)
                        put("startedAt", a.startedAt)
                    }
                }
            }
        },
        McpTool(
            name = "get_analysis",
            description = "获取某次分析（analysisId 必填）的详情：libapp/libflutter 路径、类/方法/PP 计数、errorMessage，以及该分析涉及的 libraries 列表。分析 id 来自 list_analyses",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失或非法")
            val a = cachedAnalysis(id) ?: return@McpTool buildJsonObject { put("found", false) }
            val libs = libraryDao.getByAnalysisIdList(id)
            buildJsonObject {
                put("found", true)
                put("id", a.id)
                put("projectId", a.projectId)
                put("libappPath", a.libappPath ?: "")
                put("libflutterPath", a.libflutterPath ?: "")
                put("resultCode", a.resultCode)
                put("errorMessage", a.errorMessage ?: "")
                put("classesCount", a.classesCount)
                put("methodsCount", a.methodsCount)
                put("ppEntriesCount", a.ppEntriesCount)
                putJsonArray("libraries") {
                    libs.forEach { l ->
                        addJsonObject {
                            put("name", l.libraryName)
                            put("path", l.path)
                            put("isDartSnapshot", l.isDartSnapshot)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "list_projects",
            description = "列出所有被分析的项目：id/名称/apk 路径/dartVersion/引擎版本/状态/更新时间。项目是分析的容器，每次 Blutter 分析属于某个项目",
            inputSchema = buildJsonObject { put("type", "object"); put("properties", buildJsonObject {}) }
        ) { _ ->
            val projects = projectDao.getAll().first()
            buildJsonObject {
                put("count", projects.size)
                putJsonArray("projects") {
                    projects.forEach { pr ->
                        addJsonObject {
                            put("id", pr.id)
                            put("name", pr.name)
                            put("apkPath", pr.apkPath)
                            put("dartVersion", pr.dartVersion ?: "")
                            put("engineVersion", pr.engineVersion ?: "")
                            put("status", pr.status)
                            put("updatedAt", pr.updatedAt)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_project",
            description = "获取项目详情与该项目下的全部分析记录（含各分析 id/计数/状态）。项目 id 来自 list_projects",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("projectId") { put("type", "integer") } }
                putJsonArray("required") { add("projectId") }
            }
        ) { p ->
            val projectId = p.long("projectId") ?: throw McpToolException("projectId 缺失或非法")
            val pr = projectDao.getById(projectId) ?: return@McpTool buildJsonObject { put("found", false) }
            val analyses = analysisDao.getByProjectIdList(projectId)
            buildJsonObject {
                put("found", true)
                put("id", pr.id)
                put("name", pr.name)
                put("apkPath", pr.apkPath)
                put("dartVersion", pr.dartVersion ?: "")
                put("engineVersion", pr.engineVersion ?: "")
                put("status", pr.status)
                put("updatedAt", pr.updatedAt)
                put("analysisCount", analyses.size)
                putJsonArray("analyses") {
                    analyses.forEach { a ->
                        addJsonObject {
                            put("id", a.id)
                            put("libappPath", a.libappPath ?: "")
                            put("resultCode", a.resultCode)
                            put("errorMessage", a.errorMessage ?: "")
                            put("classesCount", a.classesCount)
                            put("methodsCount", a.methodsCount)
                            put("ppEntriesCount", a.ppEntriesCount)
                            put("startedAt", a.startedAt)
                        }
                    }
                }
            }
        },
    )

    // ========== 浏览工具 ==========

    private fun buildBrowseTools(): List<McpTool> = listOf(
        McpTool(
            name = "list_classes",
            description = "列出某次分析（analysisId 必填）的全部 Dart 类：id/className/superClass/方法数。Dart 类层级是 Blutter 恢复的 Dart 语义结构（区别于 ELF 符号）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("analysisId") { put("type", "integer") } }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失或非法")
            val classes = dartClassDao.getByAnalysisIdList(id)
            // SQL 下推：方法数在 DB 侧 GROUP BY 统计，避免全量载入方法表
            val methodCounts = dartMethodDao.countMethodsGroupedByClass(id)
                .associate { it.classId to it.methodCount }
            buildJsonArray {
                classes.forEach { c ->
                    addJsonObject {
                        put("id", c.id)
                        put("className", c.className)
                        put("superClass", c.superClass ?: "")
                        put("methodCount", methodCounts[c.id] ?: 0)
                    }
                }
            }
        },
        McpTool(
            name = "list_methods",
            description = "分页列出某次分析（analysisId 必填）的 Dart 方法；可按 classId/名称过滤。返回方法 id/className/methodName/functionOffset（vaddr）/fileOffset（若 vaddr≠文件偏移则换算）/functionSize。functionOffset 恒为 vaddr，作反汇编偏移时需经 translate_address 或 fileOffset 字段。分页默认 page=1 / pageSize=200（上限 1000）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("classId") { put("type", "integer") }
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("page") { put("type", "integer") }
                    putJsonObject("pageSize") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失或非法")
            val classId = p.long("classId")
            val name = p.str("name")
            val page = (p.int("page") ?: 1).coerceAtLeast(1)
            val pageSize = (p.int("pageSize") ?: 200).coerceIn(1, 1000)

            // SQL 下推：DB 侧过滤 + 分页，避免全表载入内存
            val total = dartMethodDao.countMethodsWithClass(id, name, classId)
            val offset = ((page - 1) * pageSize).coerceAtMost(total)
            val rows = dartMethodDao.searchMethodsWithClass(id, name, classId, pageSize, offset)
            val soPath = libAppPath(id)

            buildJsonObject {
                put("total", total)
                put("page", page)
                put("pageSize", pageSize)
                put("truncated", offset + rows.size < total)
                putJsonArray("methods") {
                    rows.forEach { r ->
                        addJsonObject {
                            put("id", r.method.id)
                            put("classId", r.method.classId)
                            put("className", r._className)
                            put("methodName", r.method.methodName)
                            put("functionOffset", r.method.functionOffset ?: 0)
                            fileOffsetOf(soPath, r.method.functionOffset ?: 0)?.let { put("fileOffset", it) }
                            put("functionSize", r.method.functionSize ?: 0)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_method",
            description = "获取单个 Dart 方法详情与 Blutter 反汇编伪代码（src_code 大字段默认截断，includeSrc=true 返回完整）。用 methodId 或 name 定位；methodId 来自 list_methods。src_code 为 Blutter 恢复的反汇编，适合读业务逻辑/关键算法",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("methodId") { put("type", "integer") }
                    putJsonObject("name") { put("type", "string") }
                    putJsonObject("includeSrc") { put("type", "boolean") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失或非法")
            val methodId = p.long("methodId")
            val name = p.str("name")
            val full = p.str("includeSrc") == "true"
            // SQL 命中：methodId 精确 / name 精确，避免全量载入
            val match = when {
                methodId != null -> dartMethodDao.getMethodWithClassById(methodId)
                name != null -> dartMethodDao.getMethodWithClassByName(id, name)
                    ?: dartMethodDao.searchMethodsWithClass(id, name, null, 1, 0).firstOrNull()
                else -> null
            } ?: return@McpTool buildJsonObject { put("found", false) }

            val m = match.method
            val src = m.srcCode ?: ""
            val capped = if (!full && src.length > MAX_SRC) src.take(MAX_SRC) else src
            val soPath = libAppPath(id)
            buildJsonObject {
                put("found", true)
                put("id", m.id)
                put("classId", m.classId)
                put("className", match._className)
                put("methodName", m.methodName)
                put("functionOffset", m.functionOffset ?: 0)
                fileOffsetOf(soPath, m.functionOffset ?: 0)?.let { put("fileOffset", it) }
                put("functionSize", m.functionSize ?: 0)
                put("srcTruncated", !full && src.length > MAX_SRC)
                put("srcCode", capped)
            }
        },
        McpTool(
            name = "get_pp_entry",
            description = "按 pp 偏移（vmOffset）查 Dart 对象池条目：type/description（可读描述）/fileOffset/引用它的方法数。pp 对象池是 Dart AOT 的数据区，ppOffset 常出现在 get_pp_references 或方法 src_code 的 [pp+0x..] 中。ppOffset 支持十进制或 0x 十六进制",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("ppOffset") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId"); add("ppOffset") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失")
            val off = p.long("ppOffset") ?: throw McpToolException("ppOffset 缺失")
            val rows = ppEntryDao.getPpByVmOffset(id, off)
            buildJsonObject {
                put("count", rows.size)
                putJsonArray("entries") {
                    rows.forEach { e ->
                        addJsonObject {
                            put("ppOffset", e.vmOffset)
                            put("type", e.type)
                            put("description", e.description ?: "")
                            put("fileOffset", e.fileOffset)
                            put("callerCount", e.callerCount)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "search_strings",
            description = "在某次分析的字符串常量中搜索子串（query 必填，不区分大小写）。返回 ppOffset/description/fileOffset。用于按关键词定位 Dart 字符串及其文件位置（如渠道、URL、错误提示）。注意：部分大 Flutter 包的对象池未含 String 类型条目，此时返回 0 属正常，可改查 engine_scan_strings",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("query") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId"); add("query") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失")
            val q = p.str("query") ?: throw McpToolException("query 缺失")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
            val rows = ppEntryDao.searchStrings(id, q, limit)
            buildJsonObject {
                put("count", rows.size)
                put("truncated", rows.size == limit)
                putJsonArray("strings") {
                    rows.forEach { e ->
                        addJsonObject {
                            put("ppOffset", e.vmOffset)
                            put("description", e.description ?: "")
                            put("fileOffset", e.fileOffset)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_class",
            description = "获取某个 Dart 类详情（classId 或 className 二选一，分析 id 必填）：superClass + 该类全部方法（methodName/functionOffset/fileOffset/size）。用于按类梳理业务方法面",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("classId") { put("type", "integer") }
                    putJsonObject("className") { put("type", "string") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失或非法")
            val classId = p.long("classId")
            val className = p.str("className")
            val cls = dartClassDao.getByAnalysisIdList(id).firstOrNull {
                (classId != null && it.id == classId) ||
                    (className != null && it.className.equals(className, ignoreCase = true))
            } ?: return@McpTool buildJsonObject { put("found", false) }
            val methods = dartMethodDao.getMethodsByClassIdWithClass(id, cls.id)
            val soPath = libAppPath(id)
            buildJsonObject {
                put("found", true)
                put("id", cls.id)
                put("className", cls.className)
                put("superClass", cls.superClass ?: "")
                put("methodCount", methods.size)
                putJsonArray("methods") {
                    methods.forEach { m ->
                        addJsonObject {
                            put("id", m.method.id)
                            put("methodName", m.method.methodName)
                            put("functionOffset", m.method.functionOffset ?: 0)
                            fileOffsetOf(soPath, m.method.functionOffset ?: 0)?.let { put("fileOffset", it) }
                            put("functionSize", m.method.functionSize ?: 0)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "list_strings",
            description = "分页列出某次分析的全部字符串常量（SQL 下推）：ppOffset/description/fileOffset。数据量大（数万条）请用 search_strings 按关键词定位，或用本工具分页浏览。分页默认 page=1 / pageSize=200（上限 1000）。注意：部分大 Flutter 包无 String 类型条目时 total=0 属正常",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("page") { put("type", "integer") }
                    putJsonObject("pageSize") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失")
            val page = (p.int("page") ?: 1).coerceAtLeast(1)
            val pageSize = (p.int("pageSize") ?: 200).coerceIn(1, 1000)
            // SQL 下推：只取当前页数据，避免全表载入内存（大 SO 的字符串可达数万条）
            val total = ppEntryDao.countStringsByAnalysisId(id)
            val offset = ((page - 1) * pageSize).coerceAtMost(total)
            val rows = ppEntryDao.getStringsByAnalysisIdPaged(id, pageSize, offset)
            buildJsonObject {
                put("total", total)
                put("page", page)
                put("pageSize", pageSize)
                put("truncated", offset + rows.size < total)
                putJsonArray("strings") {
                    rows.forEach { e ->
                        addJsonObject {
                            put("ppOffset", e.vmOffset)
                            put("description", e.description ?: "")
                            put("fileOffset", e.fileOffset)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_method_callers",
            description = "反查哪些 Dart 方法调用了目标方法（methodName 为被调方法名子串，不区分大小写，基于真实调用图 dart_call_edges）。返回调用方 name=类.方法 / callerVaddr / callTargetVaddr / siteVaddr，附 graphBuilt/edgeCount/isBuilding。适合从被调方法向上找真实调用链。analysisId 无效时返回 analysisExists=false；图未就绪时返回 graphBuilt=false + isBuilding，请稍后重查",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("methodName") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId"); add("methodName") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失")
            val name = p.str("methodName") ?: throw McpToolException("methodName 缺失")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
            ensureGraph(id)
            val progress = currentProgress()
            progress.report(0.1f, "开始查询调用方: $name")
            val analysisExists = cachedAnalysis(id) != null
            val res = callGraphBuilder.findCallersByName(id, name, limit)
            if (!analysisExists) {
                return@McpTool buildJsonObject {
                    put("count", 0)
                    put("graphBuilt", false)
                    put("analysisExists", false)
                    put("message", "分析不存在（analysisId=$id），请用 list_analyses 获取有效 id")
                }
            }
            if (res == null) {
                return@McpTool buildJsonObject {
                    put("count", 0)
                    put("graphBuilt", false)
                    put("analysisExists", true)
                    put("isBuilding", callGraphBuilder.isBuilding(id))
                    put("message", "调用图未就绪（analysisId=$id），请稍后重查")
                }
            }
            val infos = res.callers
            buildJsonObject {
                put("count", infos.size)
                put("truncated", infos.size == limit)
                put("graphSource", "DART_CALL_GRAPH")
                put("graphBuilt", true)
                put("edgeCount", res.edgeCount)
                put("isBuilding", callGraphBuilder.isBuilding(id))
                putJsonArray("callers") {
                    infos.forEach { c ->
                        addJsonObject {
                            put("caller", c.name)
                            put("callerVaddr", c.vaddr)
                            put("callTargetVaddr", c.targetVaddr)
                            put("siteVaddr", c.siteVaddr)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_method_callees",
            description = "列出某 Dart 方法内部真实调用的子方法（基于调用图 dart_call_edges）。methodId 或 methodName 二选一（methodName 为精确匹配，如 UserHome._fetchList）。返回被调 name/类名/calleeVaddr/调用类型（DIRECT_CALL/DIRECT_BRANCH），附 graphBuilt/edgeCount/isBuilding。analysisId 无效时返回 analysisExists=false；图未就绪时返回 graphBuilt=false + isBuilding，请稍后重查",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("methodId") { put("type", "integer") }
                    putJsonObject("methodName") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失")
            val methodId = p.long("methodId")
            val methodName = p.str("methodName")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
            val callerId = when {
                methodId != null -> methodId
                methodName != null -> dartMethodDao.getMethodWithClassByName(id, methodName)?.method?.id
                    ?: throw McpToolException("未找到方法: $methodName")
                else -> throw McpToolException("需提供 methodId 或 methodName")
            }
            ensureGraph(id)
            currentProgress().report(0.1f, "开始查询被调方法")
            if (cachedAnalysis(id) == null) {
                return@McpTool buildJsonObject {
                    put("count", 0)
                    put("graphBuilt", false)
                    put("analysisExists", false)
                    put("message", "分析不存在（analysisId=$id），请用 list_analyses 获取有效 id")
                }
            }
            if (!callGraphBuilder.isBuilt(id)) {
                return@McpTool buildJsonObject {
                    put("count", 0)
                    put("graphBuilt", false)
                    put("analysisExists", true)
                    put("isBuilding", callGraphBuilder.isBuilding(id))
                    put("message", "调用图未就绪（analysisId=$id），请稍后重查")
                }
            }
            val callees = dartCallGraphDao.calleesOf(id, callerId, limit)
            val edgeCount = callGraphBuilder.edgeCount(id)
            buildJsonObject {
                put("count", callees.size)
                put("truncated", callees.size == limit)
                put("graphSource", "DART_CALL_GRAPH")
                put("graphBuilt", true)
                put("edgeCount", edgeCount)
                put("isBuilding", callGraphBuilder.isBuilding(id))
                putJsonArray("callees") {
                    callees.forEach { c ->
                        addJsonObject {
                            put("callee", c.name)
                            put("calleeVaddr", c.vaddr)
                            put("kind", c.kind)
                            put("siteVaddr", c.siteVaddr)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "dart_call_graph_status",
            description = "查询某次分析 Dart 调用图（真实交叉引用）的构建状态：built=是否已建完（含 0 边情形）、edgeCount=边数、building=是否正在后台构建。用于确认建图是否完成 / 何时可查 get_method_callers/get_method_callees",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失")
            currentProgress().report(0.2f, "查询调用图构建状态")
            val count = dartCallGraphDao.countByAnalysisId(id)
            buildJsonObject {
                put("analysisId", id)
                put("analysisExists", cachedAnalysis(id) != null)
                put("built", callGraphBuilder.hasCompleted(id) || count > 0)
                put("edgeCount", count)
                put("building", callGraphBuilder.isBuilding(id))
            }
        },
        McpTool(
            name = "get_pp_references",
            description = "反查哪些 Dart 方法在 src_code 中引用了指定 pp 偏移（自动拼 [pp+0x..] 匹配）。返回引用方法 id/className/methodName/functionOffset/fileOffset + target 串。用于从对象池数据追到使用方",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("ppOffset") { put("type", "integer") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId"); add("ppOffset") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失")
            val off = p.long("ppOffset") ?: throw McpToolException("ppOffset 缺失")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
            val target = "[pp+0x" + off.toString(16) + "]"
            val rows = dartMethodDao.searchSrcWithClass(id, target, limit)
            val soPath = libAppPath(id)
            buildJsonObject {
                put("target", target)
                put("count", rows.size)
                put("truncated", rows.size == limit)
                putJsonArray("references") {
                    rows.forEach { r ->
                        addJsonObject {
                            put("id", r.method.id)
                            put("className", r._className)
                            put("methodName", r.method.methodName)
                            put("functionOffset", r.method.functionOffset ?: 0)
                            fileOffsetOf(soPath, r.method.functionOffset ?: 0)?.let { put("fileOffset", it) }
                        }
                    }
                }
            }
        },
    )

    // ========== 反汇编 / ELF / 地址工具 ==========

    private fun buildDisasmTools(): List<McpTool> = listOf(
        McpTool(
            name = "disassemble_range",
            description = "用 Capstone 反汇编 so 文件指定【文件偏移】范围的 ARM64 代码（独立于分析会话，给定 soPath 即可）。offset 为文件偏移（非 vaddr）；不可解码字显示为 .word，结果不截断。返回 baseAddress/count/instructions（address/size/mnemonic/opStr/bytes）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string") }
                    putJsonObject("offset") { put("type", "integer") }
                    putJsonObject("size") { put("type", "integer") }
                }
                putJsonArray("required") { add("soPath"); add("offset") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val offset = p.long("offset") ?: throw McpToolException("offset 缺失")
            val size = (p.long("size") ?: 4096L).coerceIn(4, 65536)
            val bytes = readFileBytes(so, offset, size)
            if (bytes.isEmpty()) return@McpTool buildJsonObject { put("empty", true); put("reason", "偏移越界或文件不可读") }
            val insns = CapstoneBindings.disassembleWithCapstone(bytes, offset)
                ?: return@McpTool buildJsonObject { put("empty", true); put("reason", "Capstone 反汇编不可用") }
            buildJsonObject {
                put("baseAddress", offset)
                put("count", insns.size)
                putJsonArray("instructions") {
                    insns.forEach { it ->
                        addJsonObject {
                            put("address", it.address)
                            put("size", it.size)
                            put("mnemonic", it.mnemonic)
                            put("opStr", it.opStr)
                            put("bytes", it.bytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                        }
                    }
                }
            }
        },
        McpTool(
            name = "list_elf_sections",
            description = "列出 so 文件的 ELF 节头（独立于会话，给定 soPath 即可）：name/type/address(vaddr)/offset(文件偏移)/size/flags。address 与 offset 的差即该节的 vaddr 偏差",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("soPath") { put("type", "string") } }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val sections = ElfParserBindings().use { parser ->
                if (!parser.open(so)) throw McpToolException("无法打开 $so")
                parser.getSections()
            }
            buildJsonArray {
                sections.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("type", s.type)
                        put("address", s.address)
                        put("offset", s.offset)
                        put("size", s.size)
                        put("flags", s.flags)
                    }
                }
            }
        },
        McpTool(
            name = "list_elf_symbols",
            description = "列出 so 文件符号（独立于会话）：默认动态符号表（dynamic 默认 true；传 false 取 .symtab）。返回 name/address(vaddr)/size/type/binding",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string") }
                    putJsonObject("dynamic") { put("type", "boolean") }
                }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val dynamic = p.str("dynamic") != "false"
            val symbols = ElfParserBindings().use { parser ->
                if (!parser.open(so)) throw McpToolException("无法打开 $so")
                if (dynamic) parser.getDynamicSymbols() else parser.getSymbols()
            }
            buildJsonArray {
                symbols.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("address", s.address)
                        put("size", s.size)
                        put("type", s.type)
                        put("binding", s.binding)
                    }
                }
            }
        },
        McpTool(
            name = "find_symbol_offset",
            description = "按符号名（精确匹配）解析 ELF 动态符号的地址。返回 found/address(vaddr)/size。用于定位 JNI/导出函数入口后再反汇编",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string") }
                    putJsonObject("name") { put("type", "string") }
                }
                putJsonArray("required") { add("soPath"); add("name") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val name = p.str("name") ?: throw McpToolException("name 缺失")
            val sym = ElfParserBindings().use { parser ->
                if (!parser.open(so)) throw McpToolException("无法打开 $so")
                parser.getDynamicSymbols().firstOrNull { it.name == name }
            }
            buildJsonObject {
                put("found", sym != null)
                put("address", sym?.address ?: 0)
                put("size", sym?.size ?: 0)
            }
        },
        McpTool(
            name = "translate_address",
            description = "地址坐标换算：判断一个数值是文件偏移还是 vaddr，并给出另一个坐标与所在节、偏差 bias。当数值同时是某段 vaddr 与另一段文件偏移（带偏差的 so）时 ambiguous=true 并返回 altVaddr/altFileOffset——此时 read_bytes/write_bytes 会报歧义，需用本工具确认后再把明确的 fileOffset 传入。address 传十进制或 0x 十六进制字符串均可",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string") }
                    putJsonObject("address") { put("type", "integer") }
                }
                putJsonArray("required") { add("soPath"); add("address") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val addr = p.long("address") ?: throw McpToolException("address 缺失")
            val res = axisResolver.resolve(so, addr)
            val ctx = addressTranslator.getContext(addr)
            buildJsonObject {
                put("input", addr)
                put("axis", res?.inputAxis?.name ?: "NONE")
                put("interpretedAsFileOffset", res?.inputAxis == AddressAxis.FILE_OFFSET)
                put("ambiguous", res?.ambiguous == true)
                put("fileOffset", res?.fileOffset ?: addr)
                put("vaddr", res?.vaddr ?: addr)
                put("bias", res?.bias ?: 0)
                put("section", res?.section ?: "")
                if (res?.altVaddr != null) put("altVaddr", res.altVaddr)
                if (res?.altFileOffset != null) put("altFileOffset", res.altFileOffset)
                put("symbol", ctx.symbol ?: "")
                put("mappingFound", ctx.found)
            }
        },
        McpTool(
            name = "assemble_instruction",
            description = "用 Keystone 汇编一条 ARM64 指令并返回机器码（预览，不写文件）。assembly 如 'MOV W0, #1' / 'BL #0x4000'；address 为指令所在地址（PC 相对分支需要）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("assembly") { put("type", "string") }
                    putJsonObject("address") { put("type", "integer") }
                }
                putJsonArray("required") { add("assembly") }
            }
        ) { p ->
            val asm = p.str("assembly") ?: throw McpToolException("assembly 缺失")
            val address = p.long("address") ?: 0L
            val bytes = KeystoneBindings.asm(asm, address)
            if (bytes == null || bytes.isEmpty()) {
                buildJsonObject {
                    put("ok", false)
                    put("message", "Keystone 无法汇编: $asm")
                }
            } else {
                buildJsonObject {
                    put("ok", true)
                    put("size", bytes.size)
                    put("bytes", bytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                }
            }
        },
        McpTool(
            name = "read_so_bytes",
            description = "读取 so 文件指定【文件偏移】的原始字节（hex dump，独立于会话）。offset 为文件偏移（非 vaddr）；返回 baseOffset/size/bytes",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string") }
                    putJsonObject("offset") { put("type", "integer") }
                    putJsonObject("size") { put("type", "integer") }
                }
                putJsonArray("required") { add("soPath"); add("offset") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val offset = p.long("offset") ?: throw McpToolException("offset 缺失")
            val size = (p.long("size") ?: 256L).coerceIn(1, 65536)
            val bytes = readFileBytes(so, offset, size)
            buildJsonObject {
                put("empty", bytes.isEmpty())
                put("baseOffset", offset)
                put("size", bytes.size)
                if (bytes.isNotEmpty()) {
                    put("bytes", bytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                }
            }
        },
        McpTool(
            name = "search_elf_symbols",
            description = "在 so 动态符号表中按名称子串（不区分大小写）搜索符号。返回 count/truncated/symbols（name/address(vaddr)/size）。用于模糊定位符号",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string") }
                    putJsonObject("query") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                putJsonArray("required") { add("soPath"); add("query") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val query = p.str("query") ?: throw McpToolException("query 缺失")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 1000)
            val symbols = ElfParserBindings().use { parser ->
                if (!parser.open(so)) throw McpToolException("无法打开 $so")
                parser.getDynamicSymbols().filter { it.name.contains(query, ignoreCase = true) }
            }.take(limit)
            buildJsonObject {
                put("count", symbols.size)
                put("truncated", symbols.size == limit)
                putJsonArray("symbols") {
                    symbols.forEach { s ->
                        addJsonObject {
                            put("name", s.name)
                            put("address", s.address)
                            put("size", s.size)
                        }
                    }
                }
            }
        },
    )

    // ========== 补丁工具（默认关闭，客户端决定） ==========

    private fun buildPatchTools(): List<McpTool> = listOf(
        McpTool(
            name = "patch_instruction",
            description = "用 Keystone 汇编一条指令并【写入】so 文件【文件偏移】（破坏性补丁，默认关闭；写前自动备份+CRC 校验+可撤销）。offset 为文件偏移；写前建议 read_so_bytes 确认原值。需先在设置启用补丁",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string" ) }
                    putJsonObject("offset") { put("type", "integer") }
                    putJsonObject("assembly") { put("type", "string") }
                }
                putJsonArray("required") { add("soPath"); add("offset"); add("assembly") }
            }
        ) { p ->
            requirePatchEnabled()
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val offset = p.long("offset") ?: throw McpToolException("offset 缺失")
            val asm = p.str("assembly") ?: throw McpToolException("assembly 缺失")
            val newBytes = KeystoneBindings.asm(asm, offset)
                ?: throw McpToolException("Keystone 无法汇编: $asm")
            val result = patchService.apply(so, offset, newBytes)
            buildJsonObject {
                put("ok", result.ok)
                put("offset", offset)
                put("size", newBytes.size)
                put("bytes", newBytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                put("message", result.message)
            }
        },
        McpTool(
            name = "undo_patch",
            description = "撤销该 so 最近一次补丁，恢复原字节（默认关闭；需先启用补丁）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("soPath") { put("type", "string") } }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            requirePatchEnabled()
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val result = patchService.undo(so)
            buildJsonObject {
                put("ok", result.ok)
                put("message", result.message)
            }
        },
        McpTool(
            name = "list_patches",
            description = "列出指定 so 的全部补丁记录（offset/oldBytes/newBytes/timestamp，默认关闭；需先启用补丁）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("soPath") { put("type", "string") } }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            requirePatchEnabled()
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val records = patchService.list(so)
            buildJsonObject {
                put("count", records.size)
                putJsonArray("records") {
                    records.forEach { r ->
                        addJsonObject {
                            put("offset", r.address)
                            put("oldBytes", r.oldBytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                            put("newBytes", r.newBytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                            put("timestamp", r.timestamp)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "patch_bytes",
            description = "写任意原始字节到 so 文件指定【文件偏移】（破坏性，默认关闭；写前自动备份+可撤销）。offset 为文件偏移；hex 为空格分隔十六进制如 '1F 20 03 D5'。写前建议先 read_so_bytes 确认原值，需先启用补丁",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string") }
                    putJsonObject("offset") { put("type", "integer") }
                    putJsonObject("hex") { put("type", "string") }
                }
                putJsonArray("required") { add("soPath"); add("offset"); add("hex") }
            }
        ) { p ->
            requirePatchEnabled()
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val offset = p.long("offset") ?: throw McpToolException("offset 缺失")
            val hex = p.str("hex") ?: throw McpToolException("hex 缺失")
            val bytes = decodeHex(hex) ?: throw McpToolException("hex 格式非法（如 1F 20 03 D5）")
            if (bytes.isEmpty()) throw McpToolException("hex 为空")
            val result = patchService.apply(so, offset, bytes)
            buildJsonObject {
                put("ok", result.ok)
                put("offset", offset)
                put("size", bytes.size)
                put("message", result.message)
            }
        },
        McpTool(
            name = "export_patched_so",
            description = "把（已补丁的）so 文件复制/导出到指定目录，供用户获取。目标目录缺省用设置里用户选定的 SAF 导出文件夹；若无则落到 App 缓存 cacheDir/so_export——该目录会经 MCP 服务器暴露为 GET /export/<文件名>，可用 curl http://<手机IP>:<端口>/export/<destName> 直接下载。可传 destDir 绝对路径覆盖（需 App 有写入权限，否则报错）。destName 缺省用源文件名",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("soPath") { put("type", "string") }
                    putJsonObject("destDir") { put("type", "string") }
                    putJsonObject("destName") { put("type", "string") }
                }
                putJsonArray("required") { add("soPath") }
            }
        ) { p ->
            val so = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val destDir = p.str("destDir").orEmpty()
            val src = java.io.File(so)
            if (!src.exists() || !src.isFile) throw McpToolException("源 so 不存在: $so")
            val destName = p.str("destName")?.takeIf { it.isNotBlank() } ?: src.name

            val result = withContext(kotlinx.coroutines.Dispatchers.IO) {
                exportPatchedSo(src, destName, destDir)
            }
            buildJsonObject {
                put("ok", result.ok)
                put("fileName", destName)
                put("size", result.size)
                put("destPath", result.path)
                put("message", result.message)
            }
        },
    )

    private data class ExportResult(val ok: Boolean, val path: String, val size: Long = 0L, val message: String = "")

    /** 从当前协程上下文读取请求级进度通道（无则返回空实现）。 */
    private suspend fun currentProgress(): ProgressSink =
        kotlinx.coroutines.currentCoroutineContext()[McpRequestContext]?.progress ?: NoopProgressSink

    /** 把补丁后的 so 复制到目标。优先 destDir（绝对路径），否则用户选定的 SAF 目录，兜底 cacheDir/so_export。 */
    private suspend fun exportPatchedSo(src: java.io.File, destName: String, destDir: String): ExportResult {
        return try {
            val progress = currentProgress()
            // 各分支进度百分比在 10%~95% 之间按复制进度推进，最后置 100%
            progress.report(0.1f, "准备导出 $destName")
            if (destDir.isNotBlank()) {
                val dir = java.io.File(destDir)
                if (!dir.isDirectory && !dir.mkdirs()) {
                    return ExportResult(false, "", 0, "目标目录无法创建: $destDir")
                }
                val out = java.io.File(dir, destName)
                val size = copyFile(src, out) { p -> progress.report(rangeProgress(p, 0.1f, 0.95f), "复制 $destName ${(p * 100).toInt()}%") }
                progress.report(1f, "已复制到 $out")
                return ExportResult(true, out.absolutePath, size, "已复制到 $out")
            }

            val treeUri = config.exportTreeUri.value
            if (treeUri.isNotBlank()) {
                val uri = android.net.Uri.parse(treeUri)
                val doc = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)
                if (doc != null && doc.canWrite()) {
                    val child = doc.createFile("application/octet-stream", destName)
                    if (child != null) {
                        context.contentResolver.openOutputStream(child.uri)?.use { os ->
                            copyStream(src, os) { p -> progress.report(rangeProgress(p, 0.1f, 0.95f), "复制 $destName ${(p * 100).toInt()}%") }
                            os.flush()
                        } ?: return ExportResult(false, "", 0, "无法打开 SAF 输出流")
                        progress.report(1f, "已导出到 SAF 目录")
                        return ExportResult(true, child.uri.toString(), src.length(), "已导出到 SAF 目录")
                    }
                }
            }

            val fallbackDir = java.io.File(context.cacheDir, "so_export").apply { mkdirs() }
            val out = java.io.File(fallbackDir, destName)
            val size = copyFile(src, out) { p -> progress.report(rangeProgress(p, 0.1f, 0.95f), "复制 $destName ${(p * 100).toInt()}%") }
            progress.report(1f, "已复制到 App 缓存目录")
            ExportResult(true, out.absolutePath, size, "已复制到 App 缓存目录")
        } catch (e: Exception) {
            ExportResult(false, "", 0, "导出失败: ${e.message}")
        }
    }

    /** 把 [0,1] 相对进度映射到 [min,max] 区间（避免与大阶段百分比重叠）。 */
    private fun rangeProgress(p: Float, min: Float, max: Float): Float = min + (max - min) * p.coerceIn(0f, 1f)

    /** 分块复制文件，以块完成比回调 inProgress(0..1)。 */
    private fun copyFile(src: java.io.File, out: java.io.File, onProgress: (Float) -> Unit): Long {
        out.outputStream().use { o ->
            copyStream(src, o, onProgress)
            o.flush()
        }
        return src.length()
    }

    /** 分块复制流，每 ≥5% 间距回调一次相对进度（0..1）。 */
    private fun copyStream(src: java.io.File, o: java.io.OutputStream, onProgress: (Float) -> Unit) {
        val buf = ByteArray(64 * 1024)
        val total = src.length()
        var copied = 0L
        var lastBucket = -1
        src.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                o.write(buf, 0, n)
                copied += n
                if (total > 0) {
                    val bucket = (copied * 20 / total).toInt() // 0..19（每 5% 一档）
                    if (bucket != lastBucket) {
                        lastBucket = bucket
                        onProgress(bucket / 20f)
                    }
                }
            }
        }
        if (total <= 0) onProgress(1f)
    }

    private fun requirePatchEnabled() {
        if (!config.patchEnabled.value) {
            throw McpToolException("补丁工具未启用（需在设置页开启并确认客户端调用）")
        }
    }

    // ========== 文件读取 ==========

    private fun readFileBytes(path: String, offset: Long, size: Long): ByteArray {
        return try {
            RandomAccessFile(path, "r").use { raf ->
                val fileLen = raf.length()
                val start = offset.coerceAtLeast(0)
                if (start >= fileLen) return ByteArray(0)
                val len = size.coerceIn(1, fileLen - start).toInt()
                raf.seek(start)
                val buf = ByteArray(len)
                raf.readFully(buf)
                buf
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }

    /** 解码 hex 字符串（空格分隔）为字节数组；非法格式返回 null。 */
    private fun decodeHex(hex: String): ByteArray? {
        return try {
            val cleaned = hex.trim().replace(Regex("\\s+"), "")
            if (cleaned.isEmpty()) return ByteArray(0)
            if (cleaned.length % 2 != 0 || !cleaned.all { it in "0123456789abcdefABCDEF" }) return null
            cleaned.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    // ========== MCP resources 数据源 ==========

    override suspend fun listResources(): List<McpResource> {
        return analysisDao.getRecentList(200).map { a ->
            McpResource(
                uri = "fler://analysis/${a.id}",
                name = "analysis-${a.id}",
                mimeType = "application/json",
            )
        }
    }

    override suspend fun readResource(uri: String): String? {
        val analysisMatch = Regex("^fler://analysis/(\\d+)$").find(uri)
        val methodMatch = Regex("^fler://method/(\\d+)/(\\d+)$").find(uri)
        return when {
            analysisMatch != null -> {
                val id = analysisMatch.groupValues[1].toLongOrNull() ?: return null
                val a = cachedAnalysis(id) ?: return null
                "analysis $id\nprojectId=${a.projectId}\nlibapp=${a.libappPath ?: ""}\n" +
                    "resultCode=${a.resultCode}\nclasses=${a.classesCount} methods=${a.methodsCount} pp=${a.ppEntriesCount}"
            }
            methodMatch != null -> {
                val methodId = methodMatch.groupValues[2].toLongOrNull() ?: return null
                val m = dartMethodDao.getMethodWithClassById(methodId) ?: return null
                "${m._className}.${m.method.methodName}\n${m.method.srcCode ?: ""}"
            }
            else -> null
        }
    }

    companion object {
        private const val MAX_SRC = 100_000
    }
}

/** 工具内部业务错误：会转成 JSON-RPC -32000 段错误。 */
class McpToolException(message: String) : Exception(message)

/** JsonArrayBuilder 增加 String 便捷重载（schema 的 required 列表）。 */
private fun JsonArrayBuilder.add(value: String) {
    add(JsonPrimitive(value))
}
