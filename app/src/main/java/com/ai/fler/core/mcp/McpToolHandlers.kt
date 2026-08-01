package com.ai.fler.core.mcp

import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.ElfParserBindings
import com.ai.fler.core.jni.KeystoneBindings
import com.ai.fler.core.service.AddressTranslator
import com.ai.fler.core.service.EngineLoader
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.features.mcp.McpPatchService
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
    private val addressTranslator: AddressTranslator,
    private val engineLoader: EngineLoader,
    private val config: McpConfig,
    private val patchService: McpPatchService,
) {

    class McpTool(
        val name: String,
        val description: String,
        val inputSchema: JsonObject,
        val handler: suspend (JsonObject) -> JsonElement,
    )

    private val capstonePath: String
        get() = engineLoader.engineDirectory().resolve("lib/libcapstone.so").absolutePath

    val tools: Map<String, McpTool> = buildList {
        addAll(buildAnalysisTools())
        addAll(buildBrowseTools())
        addAll(buildDisasmTools())
        addAll(buildPatchTools())
    }.associateBy { it.name }

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

            val rows = dartMethodDao.getMethodsWithClass(id)
            val filtered = rows.filter { r ->
                (classId == null || r.method.classId == classId) &&
                    (name == null || r.method.methodName.contains(name, ignoreCase = true))
            }.sortedWith(compareBy({ it._className }, { it.method.methodName }))

            val total = filtered.size
            val start = ((page - 1) * pageSize).coerceAtMost(total)
            val end = (start + pageSize).coerceAtMost(total)
            buildJsonObject {
                put("total", total)
                put("page", page)
                put("pageSize", pageSize)
                putJsonArray("methods") {
                    for (i in start until end) {
                        val r = filtered[i]
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
            val rows = dartMethodDao.getMethodsWithClass(id)
            val match = when {
                methodId != null -> rows.firstOrNull { it.method.id == methodId }
                name != null -> rows.firstOrNull {
                    it.method.methodName.equals(name, ignoreCase = true)
                } ?: rows.firstOrNull { it.method.methodName.contains(name, ignoreCase = true) }
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
            val rows = ppEntryDao.getByAnalysisIdList(id).filter { it.vmOffset == off }
            buildJsonArray {
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
            val rows = ppEntryDao.getByAnalysisIdList(id)
                .filter { it.type == "String" && (it.description ?: "").contains(q, ignoreCase = true) }
                .take(limit)
            buildJsonArray {
                rows.forEach { e ->
                    addJsonObject {
                        put("ppOffset", e.vmOffset)
                        put("description", e.description ?: "")
                        put("fileOffset", e.fileOffset)
                    }
                }
            }
        },
        McpTool(
            name = "search_calls",
            description = "反查哪些方法调用了目标（按方法全名/名称，扫描 src_code 注释）",
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
            val rows = dartMethodDao.getByAnalysisIdList(id)
                .filter { (it.srcCode ?: "").contains(target, ignoreCase = true) }
                .take(limit)
            buildJsonArray {
                rows.forEach { m ->
                    addJsonObject {
                        put("id", m.id)
                        put("methodName", m.methodName)
                        put("functionOffset", m.functionOffset ?: 0)
                        put("functionSize", m.functionSize ?: 0)
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
            val insns = CapstoneBindings.disassembleWithCapstone(capstonePath, bytes, offset)
                ?: return@McpTool buildJsonObject { put("empty", true); put("reason", "Capstone 不可用（请先下载引擎包）") }
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
            buildJsonArray {
                records.forEach { r ->
                    addJsonObject {
                        put("offset", r.address)
                        put("oldBytes", r.oldBytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                        put("newBytes", r.newBytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') })
                        put("timestamp", r.timestamp)
                    }
                }
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
