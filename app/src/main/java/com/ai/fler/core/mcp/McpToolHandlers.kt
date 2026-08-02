package com.ai.fler.core.mcp

import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.ElfParserBindings
import com.ai.fler.core.jni.KeystoneBindings
import com.ai.fler.core.service.AddressTranslator
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.features.mcp.McpPatchService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.SupervisorJob
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
) : McpResourceProvider {

    class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val handler: suspend (JsonObject) -> JsonElement,
    )

    private val mcpScope = CoroutineScope(SupervisorJob())

    val tools: Map<String, McpTool> = buildMap {
        buildList {
            addAll(buildAnalysisTools())
            addAll(buildBrowseTools())
            addAll(buildDisasmTools())
            addAll(buildPatchTools())
        }.forEach { this[it.name] = it }
        // Engine 能力自动暴露的工具（带 engine_ 前缀）
        engineMcp.buildTools(mcpScope).forEach { (k, v) -> this[k] = v }
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
            description = "列出 App 内所有分析记录（含类/方法/PP 计数与 libapp.so 路径）",
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
            description = "获取一次分析的详情与 SO 文件列表",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失或非法")
            val a = analysisDao.getById(id) ?: return@McpTool buildJsonObject { put("found", false) }
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
            description = "列出所有项目（id/名称/apk/dartVersion/状态）",
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
            description = "获取项目详情与该项目下所有分析记录",
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
            description = "列出某次分析的所有类及方法数",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") { putJsonObject("analysisId") { put("type", "integer") } }
                putJsonArray("required") { add("analysisId") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失或非法")
            val classes = dartClassDao.getByAnalysisIdList(id)
            val methodCounts = dartMethodDao.getByAnalysisIdList(id).groupingBy { it.classId }.eachCount()
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
            description = "列出某次分析的方法（可按类/名称过滤，分页）",
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
                            put("functionSize", r.method.functionSize ?: 0)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_method",
            description = "获取方法详情与完整反汇编（src_code），大字段默认截断",
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
            buildJsonObject {
                put("found", true)
                put("id", m.id)
                put("classId", m.classId)
                put("className", match._className)
                put("methodName", m.methodName)
                put("functionOffset", m.functionOffset ?: 0)
                put("functionSize", m.functionSize ?: 0)
                put("srcTruncated", !full && src.length > MAX_SRC)
                put("srcCode", capped)
            }
        },
        McpTool(
            name = "get_pp_entry",
            description = "按 pp 偏移查对象池条目",
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
            description = "搜索分析中的字符串常量",
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
            name = "search_calls",
            description = "反查哪些方法调用了目标（按方法全名/名称，SQL 扫描 src_code）",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("analysisId") { put("type", "integer") }
                    putJsonObject("target") { put("type", "string") }
                    putJsonObject("limit") { put("type", "integer") }
                }
                putJsonArray("required") { add("analysisId"); add("target") }
            }
        ) { p ->
            val id = p.long("analysisId") ?: throw McpToolException("analysisId 缺失")
            val target = p.str("target") ?: throw McpToolException("target 缺失")
            val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
            val rows = dartMethodDao.searchSrcWithClass(id, target, limit)
            buildJsonObject {
                put("count", rows.size)
                put("truncated", rows.size == limit)
                putJsonArray("callers") {
                    rows.forEach { r ->
                        addJsonObject {
                            put("id", r.method.id)
                            put("className", r._className)
                            put("methodName", r.method.methodName)
                            put("functionOffset", r.method.functionOffset ?: 0)
                            put("functionSize", r.method.functionSize ?: 0)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_class",
            description = "获取类详情与该类的方法列表（classId 或 className）",
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
                            put("functionSize", m.method.functionSize ?: 0)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "list_strings",
            description = "列出某次分析的全部字符串常量（分页）",
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
            val all = ppEntryDao.getStringsByAnalysisIdList(id)
            val total = all.size
            val start = ((page - 1) * pageSize).coerceAtMost(total)
            val end = (start + pageSize).coerceAtMost(total)
            buildJsonObject {
                put("total", total)
                put("page", page)
                put("pageSize", pageSize)
                put("truncated", end < total)
                putJsonArray("strings") {
                    for (i in start until end) {
                        val e = all[i]
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
            description = "反查哪些方法在 src_code 中引用了指定方法名（调用关系）",
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
            val rows = dartMethodDao.searchSrcWithClass(id, name, limit)
            buildJsonObject {
                put("count", rows.size)
                put("truncated", rows.size == limit)
                putJsonArray("callers") {
                    rows.forEach { r ->
                        addJsonObject {
                            put("id", r.method.id)
                            put("className", r._className)
                            put("methodName", r.method.methodName)
                            put("functionOffset", r.method.functionOffset ?: 0)
                        }
                    }
                }
            }
        },
        McpTool(
            name = "get_pp_references",
            description = "反查哪些方法引用了指定 pp 偏移（[pp+0x..]）",
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
            description = "用 Capstone 反汇编 so 文件指定偏移范围（不可解码字显示为 .word，不截断）",
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
            description = "列出 so 文件的 ELF 节头",
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
            description = "列出 so 文件符号（默认动态符号表）",
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
            description = "按符号名解析 ELF 动态符号的地址",
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
            description = "换算地址：vaddr ↔ file offset（经 ELF 节头）+ 返回符号上下文",
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
            val sections = ElfParserBindings().use { parser ->
                if (!parser.open(so)) throw McpToolException("无法打开 $so")
                parser.getSections().filter { it.offset > 0 && it.size > 0 }
            }
            var fileOffset = addr
            var vaddr = addr
            var sectionName = ""
            // 已落在某节的文件偏移范围 → 视为文件偏移
            val inFile = sections.firstOrNull { addr >= it.offset && addr < it.offset + it.size }
            val inVaddr = sections.firstOrNull { addr >= it.address && addr < it.address + it.size }
            when {
                inFile != null -> { fileOffset = addr; vaddr = inFile.address + (addr - inFile.offset); sectionName = inFile.name }
                inVaddr != null -> { vaddr = addr; fileOffset = inVaddr.offset + (addr - inVaddr.address); sectionName = inVaddr.name }
            }
            val ctx = addressTranslator.getContext(addr)
            buildJsonObject {
                put("input", addr)
                put("interpretedAsFileOffset", inFile != null)
                put("fileOffset", fileOffset)
                put("vaddr", vaddr)
                put("section", sectionName)
                put("symbol", ctx.symbol ?: "")
                put("mappingFound", ctx.found)
            }
        },
        McpTool(
            name = "assemble_instruction",
            description = "用 Keystone 汇编一条 ARM64 指令并返回机器码（不写盘，预览用）",
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
            description = "读取 so 文件指定偏移的原始字节（hex dump）",
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
            description = "在 so 动态符号表中按名称子串搜索符号",
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
            description = "用 Capstone cs_asm 汇编一条指令并写入 so 文件（破坏性，默认关闭；写前备份+CRC+可撤销）",
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
            description = "撤销最后一次补丁（默认关闭）",
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
            description = "列出指定 so 的补丁记录（默认关闭）",
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
            description = "写任意原始字节到 so 文件指定偏移（破坏性，默认关闭；写前备份+可撤销）",
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
    )

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
                val a = analysisDao.getById(id) ?: return null
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
