package com.ai.fler.core.mcp

import com.ai.fler.core.analysis.AnalysisCapability
import com.ai.fler.core.analysis.AnalysisSession
import com.ai.fler.core.analysis.EngineRegistry
import com.ai.fler.core.analysis.FunctionInfo
import com.ai.fler.data.dao.DartMethodDao
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
import java.util.concurrent.ConcurrentHashMap
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
    private val session: AnalysisSession,
    private val axisResolver: AddressAxisResolver,
    private val dartMethodDao: DartMethodDao
) {

    companion object {
        private const val TOOL_PREFIX = "engine_"
    }

    /**
     * disassemble 专用坐标归一：目标轴随当前引擎变化。
     * Rizin(pdj) 按 vaddr 寻址；自研引擎 Capstone 按文件偏移寻址。
     */
    private suspend fun normalizeForDisasm(input: Long): Long {
        val engineId = session.currentEngine()?.engineId
        if (engineId == "self") {
            val soPath = session.currentFilePath() ?: return input
            val res = axisResolver.resolve(soPath, input) ?: return input
            return when (res.inputAxis) {
                AddressAxis.VADDR -> res.fileOffset
                else -> input
            }
        }
        return normalizeToVaddr("disassemble", input)
    }

    /**
     * 把引擎地址类工具的入参统一归一到 vaddr（Rizin 的 pdj/afij/aflj/afbj/axtj/axfj
     * 均按 io.va 用 vaddr 寻址）。入参若被识别为文件偏移则自动换算；歧义时拒绝猜测。
     */
    private suspend fun normalizeToVaddr(owner: String, input: Long): Long {
        val soPath = session.currentFilePath()
        if (soPath == null) return input
        val res = axisResolver.resolve(soPath, input) ?: return input
        return when (res.inputAxis) {
            AddressAxis.VADDR, AddressAxis.NONE -> input
            AddressAxis.FILE_OFFSET -> res.vaddr
            AddressAxis.AMBIGUOUS -> throw McpToolException(
                "$owner: 地址 0x${input.toString(16)} 坐标歧义（既是 ${res.section} 的 vaddr 又是另一段文件偏移），" +
                    "请先用 translate_address(soPath, address) 明确坐标轴"
            )
        }
    }

    /**
     * read_bytes/write_bytes 专用坐标归一：目标轴为文件偏移。
     * 传入虚拟地址（如 .text vaddr 0x11665408）自动换算为文件偏移；
     * 已是文件偏移则原样放行；歧义时拒绝猜测。
     */
    private suspend fun normalizeForRead(input: Long): Long {
        val soPath = session.currentFilePath() ?: return input
        val res = axisResolver.resolve(soPath, input) ?: return input
        return when (res.inputAxis) {
            AddressAxis.VADDR -> res.fileOffset
            AddressAxis.FILE_OFFSET, AddressAxis.NONE -> input
            AddressAxis.AMBIGUOUS -> throw McpToolException(
                "read/write_bytes: 地址 0x${input.toString(16)} 坐标歧义（既是 ${res.section} 的 vaddr 又是另一段文件偏移），" +
                    "请先用 translate_address(soPath, address) 明确坐标轴"
            )
        }
    }

    // ------------------------------------------------------------------
    // Blutter Dart 方法合并（Dart AOT 上 Rizin 只识别 ~2 个函数，函数级
    // 工具靠合并 Blutter 恢复的 5 万级方法才可用）。缓存按 soPath 存。
    // ------------------------------------------------------------------
    private data class DartFunction(val vaddr: Long, val paddr: Long, val name: String, val size: Long)

    private val dartFunctionCache = ConcurrentHashMap<String, List<DartFunction>>()

    private suspend fun dartFunctions(): List<DartFunction> {
        val soPath = session.currentFilePath() ?: return emptyList()
        dartFunctionCache[soPath]?.let { return it }
        val methods = try {
            dartMethodDao.getMethodsBySoPathLight(soPath)
        } catch (_: Exception) {
            emptyList()
        }
        if (methods.isEmpty()) return emptyList()
        val funcs = methods.mapNotNull { m ->
            val vaddr = m.functionOffset ?: return@mapNotNull null
            if (vaddr <= 0L) return@mapNotNull null
            val name = if (m._className.isNotBlank()) "${m._className}.${m.methodName}" else m.methodName
            val paddr = axisResolver.resolve(soPath, vaddr)?.fileOffset ?: vaddr
            DartFunction(vaddr, paddr, name, m.functionSize ?: 0L)
        }.sortedBy { it.vaddr }
        dartFunctionCache[soPath] = funcs
        return funcs
    }

    /** 命中包含 addr 的 Dart 方法（按 functionOffset 升序；size 为 0 时以相邻方法起点为界）。 */
    private suspend fun dartFunctionAt(addr: Long): DartFunction? {
        val funcs = dartFunctions()
        if (funcs.isEmpty()) return null
        var idx = -1
        for (i in funcs.indices) {
            if (funcs[i].vaddr <= addr) idx = i else break
        }
        if (idx < 0) return null
        val f = funcs[idx]
        val end = when {
            f.size > 0L -> f.vaddr + f.size
            idx + 1 < funcs.size -> funcs[idx + 1].vaddr
            else -> Long.MAX_VALUE
        }
        return if (addr < end) f else null
    }

    private fun DartFunction.toFunctionInfo(): FunctionInfo =
        FunctionInfo(name = name, offset = paddr, vaddr = vaddr, size = size)

    fun buildTools(): Map<String, McpToolHandlers.McpTool> {
        val list = mutableListOf<McpToolHandlers.McpTool>()

        // 通用：引擎清单
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "list_engines",
            description = "列出已注册的分析引擎（rizin/self）与仿真引擎（unicorn）及其能力 capabilities，用于确认某能力由哪个引擎提供、是否可用。无需先打开会话",
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
            description = "打开 so 文件的分析会话；之后所有 engine_* 工具（除带独立 soPath 的）都作用于该会话，同一 soPath 复用。返回 handle/engineId/bias 摘要（各节 vaddr 与文件偏移的差值分组，0 表示 vaddr==偏移）。注意：Dart AOT 大库（libapp.so）Rizin 几乎识别不出函数，函数导航应依赖 list_functions/find_function_at 的 Blutter 合并结果；对普通 so 可用 autoAnalyze=true 自动 aaa",
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
                    put("bias", axisResolver.biasSummary(result.filePath))
                }
                is com.ai.fler.core.analysis.OpenResult.Failure -> buildJsonObject {
                    put("ok", false)
                    put("reason", result.reason)
                }
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "close",
            description = "关闭当前分析会话并释放引擎资源。切换分析对象前调用，避免后一个 engine_* 工具作用到残留会话",
            inputSchema = objProps()
        ) { _ ->
            // 同步等待关闭完成：工具 handler 是 suspend，fire-and-forget 会让
            // 客户端立刻收到成功但引擎仍在关闭，导致下一个工具调用撞上半关闭状态
            session.closeAll()
            buildJsonObject { put("ok", true) }
        }

        // 手动触发分析（Dart AOT 大库默认由项目恢复跳过 aaa，需要时手动跑）
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "analyze",
            description = "对当前会话执行 Rizin aaa 全量分析（识别函数/CFG/交叉引用）。仅对需要 Rizin 级函数/CFG/xref 的普通 so 使用；Dart AOT 大库（libapp.so）开销极高、可能 OOM，函数导航请用 list_functions/find_function_at 的 Blutter 合并结果",
            inputSchema = objProps()
        ) { _ ->
            val ok = session.analyze()
            buildJsonObject {
                put("ok", ok)
                if (ok) put("functions", session.listFunctions().size)
            }
        }

        // ELF_PARSING 能力工具
        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "get_info",
            description = "获取当前 so 的架构/位宽/端序/机器类型/类 + 保护属性（canary/nx/pie/relro/是否 stripped）+ 真实文件大小。用于研判文件类型与安全加固情况",
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
            description = "列出 so 节区：name/type/offset（文件偏移）/address（vaddr）/size/perm。Dart 库各节 vaddr 可能≠文件偏移（如 libflutter 的 .data.rel.ro 差 0x10000），此时同数值在 offset 与 vaddr 两个坐标系都成立，地址语义需用 translate_address 判明；perm 过滤写法如 r-x（Rizin 格式为 -r-x）",
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
            description = "列出 so 动态符号：name/demangled/type/bind/address（vaddr）/size/section，支持按名字、类型（FUNC/OBJECT/SECTION/...）、bind（GLOBAL/LOCAL/WEAK）过滤。用于定位导出符号与未剥离的调试信息",
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
            description = "列出当前 so 的函数。Dart AOT 库（libapp.so）自动合并 Blutter 恢复的 Dart 方法（数万条，名称=ClassName.methodName，offset/vaddr/size 齐全）；普通 so 返回 Rizin aflj 结果。query 按名称子串过滤，limit 限返回条数。Dart 库上结果可能以 Dart 方法为主，Rizin 函数极少",
            inputSchema = objProps(
                "query" to strType(false, "名字模糊匹配"),
                "limit" to intType(false, def = 5000, "返回条目上限")
            )
        ) { p ->
            val q = p.str("query")?.lowercase()
            val limit = (p.int("limit") ?: 5000).coerceAtLeast(1)
            val rizin = session.listFunctions()
            val seen = HashSet<Long>(rizin.size + 16)
            rizin.forEach { seen.add(it.vaddr) }
            val merged = buildList {
                addAll(rizin)
                // Dart AOT：合并 Blutter 方法（按 vaddr 去重，Blutter 优先于 Rizin 已识别项不冲突）
                addAll(dartFunctions().mapNotNull { d ->
                    if (seen.add(d.vaddr)) d.toFunctionInfo() else null
                })
            }
            val out = merged.asSequence()
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
            description = "查询包含指定地址（vaddr 或文件偏移，自动识别）的函数。先查 Rizin 函数，未命中则查 Blutter Dart 方法（支持命中方法内部任意指令地址）。返回 name/offset/vaddr/size。用于把任意代码地址定位到所属函数后再反汇编",
            inputSchema = objProps(
                "address" to strOrLongType(true, "hex 或十进制地址（vaddr 或文件偏移，自动识别）")
            )
        ) { p ->
            val addr = normalizeToVaddr("find_function_at", p.parseHexOrDec("address") ?: throw McpToolException("address 缺失或非法"))
            val f = session.findFunctionContaining(addr)
                ?: dartFunctionAt(addr)?.toFunctionInfo()
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
            description = "返回某函数的基本块 CFG：每块的 addr/size/指令数 nInstr/后继 succs/前驱 preds。functionOffset 为函数起始 vaddr 或文件偏移。仅对 Rizin 已分析（aaa 后）的普通 so 有效，Dart 方法无 CFG",
            inputSchema = objProps(
                "functionOffset" to strOrLongType(true, "函数起始地址（vaddr 或文件偏移，自动识别）")
            )
        ) { p ->
            val off = normalizeToVaddr("function_cfg", p.parseHexOrDec("functionOffset") ?: throw McpToolException("functionOffset 非法"))
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
            description = "查询哪些地址引用了 target（反向交叉引用）。target 为 vaddr 或文件偏移，自动识别；返回 from/to/type。仅对已执行 aaa 分析的会话有效（Dart 大库上基本为空），需交叉引用请先 engine.analyze",
            inputSchema = objProps(
                "target" to strOrLongType(true, "目标地址（vaddr 或文件偏移，自动识别）"),
                "limit" to intType(false, def = 200, "返回条目上限")
            )
        ) { p ->
            val t = normalizeToVaddr("xrefs_to", p.parseHexOrDec("target") ?: throw McpToolException("target 非法"))
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
            description = "查询指定地址引用了哪些目标（正向交叉引用）。from 为 vaddr 或文件偏移，自动识别；返回 from/to/type。仅对已执行 aaa 分析的会话有效（Dart 大库上基本为空）",
            inputSchema = objProps(
                "from" to strOrLongType(true, "源地址（vaddr 或文件偏移，自动识别）"),
                "limit" to intType(false, def = 200, "返回条目上限")
            )
        ) { p ->
            val from = normalizeToVaddr("xrefs_from", p.parseHexOrDec("from") ?: throw McpToolException("from 非法"))
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
            description = "从指定地址反汇编 N 字节（默认 4096，上限 65536）。offset 可传 vaddr 或文件偏移，自动识别（歧义时按当前引擎坐标轴归一）。返回 baseAddress/inputAddress/count/instructions，每条含 address/size/mnemonic/opStr/bytes，vaddr≠文件偏移时附 fileOffset。建议先用 find_function_at 定位函数起点再反汇编",
            inputSchema = objProps(
                "offset" to strOrLongType(true, "文件偏移或 vaddr（hex/dec），自动识别"),
                "size" to intType(false, def = 4096, "反汇编字节数，上限 65536")
            )
        ) { p ->
            val raw = p.parseHexOrDec("offset") ?: throw McpToolException("offset 非法")
            val offset = normalizeForDisasm(raw)
            val size = (p.int("size") ?: 4096).coerceIn(4, 65536)
            val soPath = session.currentFilePath()
            val insns = session.disassemble(offset, size.toLong())
            buildJsonObject {
                put("baseAddress", "0x${offset.toString(16)}")
                put("inputAddress", "0x${raw.toString(16)}")
                put("count", insns.size)
                putJsonArray("instructions") {
                    insns.forEach { i ->
                        addJsonObject {
                            put("address", "0x${i.address.toString(16)}")
                            if (soPath != null) {
                                val fileOff = axisResolver.resolve(soPath, i.address)?.fileOffset
                                if (fileOff != null && fileOff != i.address) {
                                    put("fileOffset", "0x${fileOff.toString(16)}")
                                }
                            }
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
            description = "用汇编器把单条 ARM64 指令编码为机器码（预览，不写文件）。assembly 如 'MOV W0, #1' / 'BL #0x4000'；address 为指令所在地址（PC 相对分支必需）。返回 hex 字节 + size",
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
            description = "按文件偏移读取 so 原始字节（hex 字符串，空格分隔）。offset 可传 vaddr 或文件偏移，自动归一为文件偏移；歧义地址直接报错，请先用 translate_address 明确坐标轴。用于查看原始字节/机器码/常量区",
            inputSchema = objProps(
                "offset" to strOrLongType(true, "文件偏移"),
                "size" to intType(false, def = 256, "字节数，上限 1MB")
            )
        ) { p ->
            val off = normalizeForRead(p.parseHexOrDec("offset") ?: throw McpToolException("offset 非法"))
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
            description = "写字节补丁到文件偏移（写前自动备份并记入撤销栈，可 undo_patch 回滚）。offset 归一规则同 read_bytes（歧义报错）；hex 为空格分隔十六进制如 'C0 03 5F D6'。写前建议先 read_bytes 确认原值",
            inputSchema = objProps(
                "offset" to strOrLongType(true, "文件偏移"),
                "hex" to strType(true, "以空格分隔的十六进制，如 C0 03 5F D6")
            )
        ) { p ->
            val off = normalizeForRead(p.parseHexOrDec("offset") ?: throw McpToolException("offset 非法"))
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
            description = "扫描 so 中的 ASCII 字符串（整文件流式扫描）。minLen 默认 4 / maxLen 512。返回 address（vaddr）/paddr（文件偏移）/size/section/string；query 做不区分大小写的子串过滤。适合搜 Dart 符号名、渠道标识、敏感字符串（如 'MethodChannel'、'http'）",
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
            description = "整个 so 文件的 MD5 摘要（流式计算，与设备 md5sum 一致）。用于校验文件完整性/确认当前分析的版本",
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
            description = "整个 so 文件的 SHA256 摘要（流式计算，与设备 sha256sum 一致）",
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
            description = "计算 so 的 CRC32。offset/size 都不传则整文件；传则计算文件偏移 [offset, offset+size) 范围（offset 为文件偏移，不做 vaddr 归一）",
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
