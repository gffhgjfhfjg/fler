package com.ai.fler.core.mcp

import com.ai.fler.core.analysis.AnalysisCapability
import com.ai.fler.core.analysis.AnalysisSession
import com.ai.fler.core.analysis.EngineRegistry
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine 能力自动暴露给 MCP 服务。
 *
 * 本类读取 [EngineRegistry] 中所有引擎的 [AnalysisCapability]，
 * 按下列规则生成 MCP 工具：
 *
 * | 能力 | MCP 工具前缀 | 示例 |
 * |------|--------------|------|
 * | ELF_PARSING | engine.list_sections / engine.list_symbols / engine.get_info ... | |
 * | FUNCTION_ANALYSIS | engine.list_functions / engine.find_function ... | |
 * | XREF | engine.xrefs_to / engine.xrefs_from | |
 * | DISASSEMBLY | engine.disassemble | |
 * | ASSEMBLY | engine.assemble | |
 * | BYTE_EDIT | engine.read_bytes / engine.write_bytes | |
 * | STRING_SCAN | engine.scan_strings | |
 * | BINARY_HASH | engine.md5 / engine.sha256 / engine.crc32 | |
 *
 * 调用流程：
 * 1. 先用 `engine.open(soPath)` 拿到会话（sessionId = path hash；内部用 AnalysisSession）
 * 2. 后续工具调用都带上 `soPath` 或者不填时使用最近一次 open 的会话
 * 3. 调用 `engine.close(soPath)` 释放
 */
@Singleton
class EngineMcpToolRegistry @Inject constructor(
    private val registry: EngineRegistry,
    private val session: AnalysisSession
) {

    companion object {
        private const val TOOL_PREFIX = "engine_"
    }

    fun buildTools(): Map<String, McpToolHandlers.McpTool> {
        val list = mutableListOf<McpToolHandlers.McpTool>()

        // 通用：引擎清单
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "list_engines",
            description = "列出当前注册的分析/仿真引擎及各自能力",
            inputSchema = objProps()
        ) { _ ->
            val analysisEngines = registry.listAnalysis()
            val emuEngines = registry.listEmulation()
            buildJsonObject {
                putJsonArray("analysis") {
                    analysisEngines.forEach { e ->
                        addJsonObject {
                            put("id", e.engineId)
                            put("displayName", e.displayName)
                            put("isAvailable", e.isAvailable)
                            putJsonArray("capabilities") {
                                e.capabilities.forEach { add(it.name) }
                            }
                        }
                    }
                }
                putJsonArray("emulation") {
                    emuEngines.forEach { e ->
                        addJsonObject {
                            put("id", e.engineId)
                            put("displayName", e.displayName)
                            put("isAvailable", e.isAvailable)
                            putJsonArray("capabilities") {
                                e.capabilities.forEach { add(it.name) }
                            }
                        }
                    }
                }
            }
        }

        // 打开 so 会话
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "open",
            description = "打开 so 会话并准备分析；可选 autoAnalyze = true 自动 aaa",
            inputSchema = objProps(
                "soPath" to strType(schemaRequired = true, "so 文件绝对路径"),
                "engineId" to strType(schemaRequired = false, "使用指定引擎；空则按能力自动选"),
                "autoAnalyze" to boolType(schemaRequired = false, def = true, "启动后自动执行分析")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val engineId = p.str("engineId")
            val analyze = p.boolean("autoAnalyze") ?: true
            val result = if (engineId.isNullOrBlank()) {
                val caps = mutableListOf<AnalysisCapability>()
                if (analyze) caps += AnalysisCapability.FUNCTION_ANALYSIS
                caps += AnalysisCapability.ELF_PARSING
                caps += AnalysisCapability.BYTE_EDIT
                session.open(path, requireCaps = caps)
            } else {
                session.openWithEngine(path, engineId)
            }
            when (result) {
                is com.ai.fler.core.analysis.OpenResult.Success -> buildJsonObject {
                    put("ok", true)
                    put("handle", result.handle.value.toString())
                    put("engineId", result.engineId)
                    put("filePath", result.filePath)
                }
                is com.ai.fler.core.analysis.OpenResult.Failure -> buildJsonObject {
                    put("ok", false)
                    put("reason", result.reason)
                }
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "close",
            description = "关闭指定 so 的分析会话",
            inputSchema = objProps()
        ) { _ ->
            // 同步等待关闭完成：工具 handler 是 suspend，fire-and-forget 会让
            // 客户端立刻收到成功但引擎仍在关闭，导致下一个工具调用撞上半关闭状态
            session.closeAll()
            buildJsonObject { put("ok", true) }
        }

        // ELF_PARSING 能力工具
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "get_info",
            description = "获取 so 的架构/位宽/保护位（NX/PIE/RELRO/Canary 等）信息",
            inputSchema = objProps()
        ) { _ ->
            val info = session.getFileInfo()
                ?: return@McpTool buildJsonObject { put("found", false); put("reason", "未打开会话") }
            buildJsonObject {
                put("found", true)
                put("arch", info.arch)
                put("bits", info.bits)
                put("endian", info.endian)
                put("machine", info.machine)
                put("class", info.classType)
                put("os", info.os)
                putJsonObject("protection") {
                    put("canary", info.canary)
                    put("nx", info.nx)
                    put("pie", info.pie)
                    put("relro", info.relro)
                    put("stripped", info.stripped)
                }
                put("fileSize", info.fileSize)
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "list_sections",
            description = "列出 so 节区（名称/偏移/大小/权限/类型）",
            inputSchema = objProps(
                "perm" to strType(false, "权限过滤，如 r-x / rw-；空=不过滤"),
                "type" to strType(false, "节类型过滤，如 PROGBITS / NOBITS / SYMTAB / dynsym")
            )
        ) { p ->
            val perm = p.str("perm")
            val typeFilter = p.str("type")?.lowercase()
            val all = session.getSections()
            val filtered = all.filter { s ->
                if (perm != null && s.perm != perm) false
                else if (typeFilter != null) {
                    (s.type.lowercase().contains(typeFilter) ||
                            s.name.lowercase().contains(typeFilter) ||
                            sectionTypeIntName(s.typeInt).contains(typeFilter))
                } else true
            }
            buildJsonArray {
                filtered.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        put("type", s.type.ifBlank { sectionTypeIntName(s.typeInt) })
                        put("offset", "0x${s.offset.toString(16)}")
                        put("address", "0x${s.address.toString(16)}")
                        put("size", s.size)
                        put("perm", s.perm)
                        put("flags", "0x${s.flags.toString(16)}")
                    }
                }
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "list_symbols",
            description = "列出 so 符号（含 demangle 名称 / bind / type / size）",
            inputSchema = objProps(
                "query" to strType(false, "模糊匹配（名字/demangle）；空=全部"),
                "type" to strType(false, "FUNC / OBJECT / SECTION / FILE / TLS / COMMON"),
                "bind" to strType(false, "GLOBAL / LOCAL / WEAK"),
                "limit" to intType(false, def = 2000, "返回条目上限")
            )
        ) { p ->
            val q = p.str("query")?.lowercase()
            val t = p.str("type")?.uppercase()
            val b = p.str("bind")?.uppercase()
            val limit = (p.int("limit") ?: 2000).coerceAtLeast(1)
            val all = session.getSymbols(true)
            val out = all.asSequence()
                .filter { q == null || it.name.lowercase().contains(q) ||
                        (it.demangledName?.lowercase()?.contains(q) == true) }
                .filter { t == null || it.type.name == t }
                .filter { b == null || it.bind.name == b }
                .take(limit)
                .toList()
            buildJsonArray {
                out.forEach { s ->
                    addJsonObject {
                        put("name", s.name)
                        s.demangledName?.let { put("demangled", it) }
                        put("type", s.type.name)
                        put("bind", s.bind.name)
                        put("address", "0x${s.address.toString(16)}")
                        put("size", s.size)
                        s.sectionName.takeIf { it.isNotBlank() }?.let { put("section", it) }
                    }
                }
            }
        }

        // 函数分析
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "list_functions",
            description = "列出 Rizin 识别到的函数（aflj 结构）",
            inputSchema = objProps(
                "query" to strType(false, "名字模糊匹配"),
                "limit" to intType(false, def = 5000, "返回条目上限")
            )
        ) { p ->
            val q = p.str("query")?.lowercase()
            val limit = (p.int("limit") ?: 5000).coerceAtLeast(1)
            val all = session.listFunctions()
            val out = all.asSequence()
                .filter { q == null || it.name.lowercase().contains(q) || it.signature.lowercase().contains(q) }
                .take(limit)
            buildJsonArray {
                out.forEach { f ->
                    addJsonObject {
                        put("name", f.name)
                        if (f.signature.isNotBlank()) put("signature", f.signature)
                        put("offset", "0x${f.offset.toString(16)}")
                        put("vaddr", "0x${f.vaddr.toString(16)}")
                        put("size", f.size)
                        put("nargs", f.nargs)
                        put("nbbs", f.nbbs)
                        put("edges", f.edges)
                        if (f.callConvention.isNotBlank()) put("callConv", f.callConvention)
                    }
                }
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "find_function_at",
            description = "查找包含指定地址的函数",
            inputSchema = objProps(
                "address" to strOrLongType(true, "hex 或十进制地址")
            )
        ) { p ->
            val addr = p.parseHexOrDec("address") ?: throw McpToolException("address 缺失或非法")
            val f = session.findFunctionContaining(addr)
                ?: return@McpTool buildJsonObject { put("found", false) }
            buildJsonObject {
                put("found", true)
                put("name", f.name)
                if (f.signature.isNotBlank()) put("signature", f.signature)
                put("offset", "0x${f.offset.toString(16)}")
                put("vaddr", "0x${f.vaddr.toString(16)}")
                put("size", f.size)
                put("nargs", f.nargs)
                put("nbbs", f.nbbs)
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "function_cfg",
            description = "返回某函数的基本块 CFG（afbj 结构）",
            inputSchema = objProps(
                "functionOffset" to strOrLongType(true, "函数起始地址")
            )
        ) { p ->
            val off = p.parseHexOrDec("functionOffset") ?: throw McpToolException("functionOffset 非法")
            val bbs = session.getFunctionCfg(off)
            buildJsonArray {
                bbs.forEach { b ->
                    addJsonObject {
                        put("addr", "0x${b.addr.toString(16)}")
                        put("size", b.size)
                        put("nInstr", b.nInstr)
                        putJsonArray("succs") { b.succs.forEach { add("0x${it.toString(16)}") } }
                        putJsonArray("preds") { b.preds.forEach { add("0x${it.toString(16)}") } }
                    }
                }
            }
        }

        // 交叉引用
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "xrefs_to",
            description = "查询哪些地址引用了 target",
            inputSchema = objProps(
                "target" to strOrLongType(true, "目标地址"),
                "limit" to intType(false, def = 200, "返回条目上限")
            )
        ) { p ->
            val t = p.parseHexOrDec("target") ?: throw McpToolException("target 非法")
            val limit = (p.int("limit") ?: 200).coerceAtLeast(1)
            val list = session.xrefsTo(t).take(limit)
            buildJsonArray {
                list.forEach { x ->
                    addJsonObject {
                        put("from", "0x${x.from.toString(16)}")
                        put("to", "0x${x.to.toString(16)}")
                        put("type", x.type.name)
                        if (x.perm.isNotBlank()) put("perm", x.perm)
                    }
                }
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "xrefs_from",
            description = "查询某地址引用了哪些目标",
            inputSchema = objProps(
                "from" to strOrLongType(true, "源地址"),
                "limit" to intType(false, def = 200, "返回条目上限")
            )
        ) { p ->
            val from = p.parseHexOrDec("from") ?: throw McpToolException("from 非法")
            val limit = (p.int("limit") ?: 200).coerceAtLeast(1)
            val list = session.xrefsFrom(from).take(limit)
            buildJsonArray {
                list.forEach { x ->
                    addJsonObject {
                        put("from", "0x${x.from.toString(16)}")
                        put("to", "0x${x.to.toString(16)}")
                        put("type", x.type.name)
                    }
                }
            }
        }

        // 反汇编
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "disassemble",
            description = "从指定偏移反汇编 N 字节",
            inputSchema = objProps(
                "offset" to strOrLongType(true, "文件偏移（hex/dec）"),
                "size" to intType(false, def = 4096, "反汇编字节数，上限 65536")
            )
        ) { p ->
            val offset = p.parseHexOrDec("offset") ?: throw McpToolException("offset 非法")
            val size = (p.int("size") ?: 4096).coerceIn(4, 65536)
            val insns = session.disassemble(offset, size.toLong())
            buildJsonObject {
                put("baseAddress", "0x${offset.toString(16)}")
                put("count", insns.size)
                putJsonArray("instructions") {
                    insns.forEach { i ->
                        addJsonObject {
                            put("address", "0x${i.address.toString(16)}")
                            put("size", i.size)
                            put("mnemonic", i.mnemonic)
                            put("opStr", i.opStr)
                            put("bytes", i.bytes.joinToString(" ") { b ->
                                b.toUByte().toString(16).padStart(2, '0')
                            })
                        }
                    }
                }
            }
        }

        // 汇编
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "assemble",
            description = "把一条汇编指令文本编码为机器码",
            inputSchema = objProps(
                "assembly" to strType(true, "如 MOV W0, #1 / BL #0x4000"),
                "address" to strOrLongType(false, "指令所在地址，分支 PC-rel 需要")
            )
        ) { p ->
            val asm = p.str("assembly") ?: throw McpToolException("assembly 缺失")
            val addr = p.parseHexOrDec("address") ?: 0L
            val bytes = session.assemble(asm, addr)
                ?: return@McpTool buildJsonObject { put("ok", false); put("reason", "编码失败") }
            buildJsonObject {
                put("ok", true)
                put("hex", bytes.joinToString(" ") { b ->
                    b.toUByte().toString(16).padStart(2, '0')
                })
                put("size", bytes.size)
            }
        }

        // 字节读写
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "read_bytes",
            description = "从 so 文件按偏移读取字节",
            inputSchema = objProps(
                "offset" to strOrLongType(true, "文件偏移"),
                "size" to intType(false, def = 256, "字节数，上限 1MB")
            )
        ) { p ->
            val off = p.parseHexOrDec("offset") ?: throw McpToolException("offset 非法")
            val size = (p.int("size") ?: 256).coerceIn(1, 1024 * 1024)
            val b = session.readBytes(off, size.toLong())
            buildJsonObject {
                put("offset", "0x${off.toString(16)}")
                put("size", b.size)
                put("hex", b.joinToString(" ") { byte ->
                    byte.toUByte().toString(16).padStart(2, '0')
                })
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "write_bytes",
            description = "写入字节补丁（会自动加入撤销栈）。hex 字符串以空格分隔",
            inputSchema = objProps(
                "offset" to strOrLongType(true, "文件偏移"),
                "hex" to strType(true, "以空格分隔的十六进制，如 C0 03 5F D6")
            )
        ) { p ->
            val off = p.parseHexOrDec("offset") ?: throw McpToolException("offset 非法")
            val hex = p.str("hex")?.trim() ?: throw McpToolException("hex 缺失")
            val bytes = hex.split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { it.toUByte(16).toByte() }
                .toByteArray()
            if (bytes.isEmpty()) throw McpToolException("hex 为空")
            val ok = session.writeBytes(off, bytes, soNameHint = "")
            buildJsonObject { put("ok", ok); put("wrote", bytes.size) }
        }

        // 字符串扫描
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "scan_strings",
            description = "扫描 so 中的 ASCII 字符串",
            inputSchema = objProps(
                "minLen" to intType(false, def = 4, "最小长度"),
                "maxLen" to intType(false, def = 512, "最大长度"),
                "limit" to intType(false, def = 2000, "返回上限"),
                "query" to strType(false, "字符串内容过滤")
            )
        ) { p ->
            val res = session.scanStrings(
                com.ai.fler.core.analysis.StringScanOptions(
                    minLen = (p.int("minLen") ?: 4).coerceAtLeast(1),
                    maxLen = (p.int("maxLen") ?: 512).coerceAtLeast(1)
                )
            )
            val limit = p.int("limit") ?: 2000
            val q = p.str("query")?.lowercase()
            val out = res.asSequence()
                .filter { q == null || it.string.lowercase().contains(q) }
                .take(limit)
            buildJsonArray {
                out.forEach { s ->
                    addJsonObject {
                        put("address", "0x${s.address.toString(16)}")
                        if (s.paddr != s.address) put("paddr", "0x${s.paddr.toString(16)}")
                        put("size", s.size)
                        if (s.section.isNotBlank()) put("section", s.section)
                        put("string", s.string)
                    }
                }
            }
        }

        // 哈希
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "md5",
            description = "整个 so 文件的 MD5（引擎能力 BINARY_HASH）",
            inputSchema = objProps()
        ) { _ ->
            buildJsonObject {
                val md5 = session.md5()
                if (md5 != null) put("md5", md5)
                put("ok", md5 != null)
            }
        }
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "sha256",
            description = "整个 so 文件的 SHA256",
            inputSchema = objProps()
        ) { _ ->
            buildJsonObject {
                val v = session.sha256()
                if (v != null) put("sha256", v)
                put("ok", v != null)
            }
        }
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "crc32",
            description = "CRC32；offset/size 空则整文件",
            inputSchema = objProps(
                "offset" to strOrLongType(false, "起始偏移"),
                "size" to intType(false, def = 1024 * 1024, "字节数")
            )
        ) { p ->
            val off = p.parseHexOrDec("offset")
            val size = p.int("size")
            val v = if (off != null) session.crc32(off, size?.toLong()) else session.crc32()
            buildJsonObject { if (v != null) put("crc32", "0x${v.toString(16)}"); put("ok", v != null) }
        }

        return list.associateBy { it.name }
    }

    // ------------------------------------------------------------------
    // Schema helpers（简化 buildJsonObject 构造 JSON Schema）
    // ------------------------------------------------------------------

    private fun objProps(vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            props.forEach { (k, v) -> put(k, v) }
        }
        val required = props.mapNotNull { p ->
            val o = p.second
            val isRequired = when (val v = o["_required"]) {
                is JsonPrimitive -> {
                    (v.contentOrNull == "true") || (runCatching { (v as kotlinx.serialization.json.JsonPrimitive).content.toBooleanStrictOrNull() }.getOrNull() == true)
                }
                else -> false
            }
            if (isRequired) p.first else null
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }

    private fun strType(required: Boolean, description: String = ""): JsonObject = buildJsonObject {
        put("type", "string")
        if (description.isNotBlank()) put("description", description)
        if (required) put("_required", true)
    }
    private fun strType(schemaRequired: Boolean, def: String? = null, description: String = ""): JsonObject =
        buildJsonObject {
            put("type", "string")
            if (description.isNotBlank()) put("description", description)
            if (def != null) put("default", def)
            if (schemaRequired) put("_required", true)
        }
    private fun boolType(schemaRequired: Boolean, def: Boolean = false, description: String = ""): JsonObject =
        buildJsonObject {
            put("type", "boolean")
            if (description.isNotBlank()) put("description", description)
            put("default", def)
            if (schemaRequired) put("_required", true)
        }
    private fun intType(schemaRequired: Boolean, def: Int? = null, description: String = ""): JsonObject =
        buildJsonObject {
            put("type", "integer")
            if (description.isNotBlank()) put("description", description)
            if (def != null) put("default", def)
            if (schemaRequired) put("_required", true)
        }
    private fun strOrLongType(required: Boolean, description: String = ""): JsonObject =
        buildJsonObject {
            // 允许 hex 字符串或十进制整数；MCP 实际统一用 string 更稳
            put("type", "string")
            if (description.isNotBlank()) put("description", description)
            if (required) put("_required", true)
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    private fun JsonObject.boolean(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    private fun JsonObject.parseHexOrDec(key: String): Long? {
        val s = str(key) ?: return null
        return s.trim().let {
            if (it.startsWith("0x", ignoreCase = true)) it.drop(2).toLongOrNull(16)
            else it.toLongOrNull(16) ?: it.toLongOrNull()
        }
    }

    private fun sectionTypeIntName(typeInt: Int): String = when (typeInt) {
        1 -> "PROGBITS"
        2 -> "SYMTAB"
        3 -> "STRTAB"
        4 -> "RELA"
        5 -> "HASH"
        6 -> "DYNAMIC"
        7 -> "NOTE"
        8 -> "NOBITS"
        9 -> "REL"
        11 -> "DYNSYM"
        14 -> "INIT_ARRAY"
        15 -> "FINI_ARRAY"
        else -> "TYPE_$typeInt"
    }
}
