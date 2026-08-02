package com.ai.fler.core.analysis.engine

import com.ai.fler.core.analysis.*
import kotlinx.serialization.json.*

/**
 * Rizin JSON 输出解析器。
 *
 * 解析 Rizin 命令的 JSON 输出到 Engine 抽象层数据模型：
 * - `ij`     → [FileInfo]（含 bin 部分）
 * - `iSj`    → [SectionInfo] 列表
 * - `isj`    → [SymbolInfo] 列表
 * - `iij`    → [ImportInfo] 列表
 * `irj`    → [RelocInfo] 列表
 * - `aflj`   → [FunctionInfo] 列表
 * - `izzj`   → [StringInfo] 列表
 * - `pdj`    → [DisasmInstruction] 列表
 * - `axtj`   → [Xref] 列表（to → from）
 * - `axfj`   → [Xref] 列表（from → to）
 *
 * Rizin JSON 格式参考：https://rizin.re/docs/rizin_commands.html
 */
internal object RizinJsonParser {

    private val json = Json { ignoreUnknownKeys = true }

    // ==================================================================
    // ij → FileInfo
    // ==================================================================

    fun parseFileInfo(jsonStr: String, fileSize: Long): FileInfo? {
        return try {
            val obj = json.parseToJsonElement(jsonStr).jsonObject
            val bin = obj["bin"]?.jsonObject ?: obj
            FileInfo(
                arch = bin.str("arch") ?: obj.str("arch") ?: "",
                bits = bin.int("bits") ?: obj.int("bits") ?: 0,
                endian = bin.str("endian") ?: obj.str("endian") ?: "",
                machine = bin.str("machine") ?: obj.str("machine") ?: "",
                classType = bin.str("class") ?: obj.str("class") ?: "",
                os = bin.str("os") ?: obj.str("os") ?: "",
                canary = bin.bool("canary") ?: false,
                nx = bin.bool("nx") ?: false,
                pie = bin.bool("pie") ?: false,
                relro = bin.str("relro") ?: "none",
                stripped = bin.bool("stripped") ?: false,
                fileSize = fileSize
            )
        } catch (_: Throwable) { null }
    }

    // ==================================================================
    // iSj → List<SectionInfo>
    // ==================================================================

    fun parseSections(jsonStr: String): List<SectionInfo> {
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                SectionInfo(
                    name = o.str("name") ?: "",
                    type = o.str("type") ?: "",
                    typeInt = o.int("type") ?: 0,
                    offset = o.long("paddr") ?: o.long("offset") ?: 0L,
                    size = o.long("size") ?: 0L,
                    address = o.long("vaddr") ?: o.long("address") ?: 0L,
                    paddr = o.long("paddr") ?: 0L,
                    flags = o.long("flags") ?: 0L,
                    perm = o.str("perm") ?: permFromFlags(o.long("flags") ?: 0L)
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ==================================================================
    // isj → List<SymbolInfo>
    // ==================================================================

    fun parseSymbols(jsonStr: String): List<SymbolInfo> {
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                SymbolInfo(
                    name = o.str("name") ?: "",
                    demangledName = o.str("demangled") ?: o.str("demname"),
                    address = o.long("vaddr") ?: o.long("addr") ?: 0L,
                    size = o.long("size") ?: 0L,
                    type = parseSymbolType(o.str("type")),
                    bind = parseSymbolBind(o.str("bind")),
                    shndx = o.int("shndx") ?: 0,
                    sectionName = o.str("section") ?: ""
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ==================================================================
    // iij → List<ImportInfo>
    // ==================================================================

    fun parseImports(jsonStr: String): List<ImportInfo> {
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                ImportInfo(
                    name = o.str("name") ?: "",
                    type = o.str("type") ?: "",
                    address = o.long("vaddr") ?: o.long("plt") ?: 0L,
                    bind = o.str("bind") ?: "GLOBAL"
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ==================================================================
    // irj → List<RelocInfo>
    // ==================================================================

    fun parseRelocs(jsonStr: String): List<RelocInfo> {
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                RelocInfo(
                    name = o.str("name") ?: "",
                    address = o.long("vaddr") ?: o.long("addr") ?: 0L,
                    type = o.str("type") ?: ""
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ==================================================================
    // aflj → List<FunctionInfo>
    // ==================================================================

    fun parseFunctions(jsonStr: String): List<FunctionInfo> {
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                FunctionInfo(
                    name = o.str("name") ?: o.str("signature") ?: "",
                    offset = o.long("offset") ?: o.long("paddr") ?: 0L,
                    vaddr = o.long("addr") ?: o.long("offset") ?: 0L,
                    size = o.long("size") ?: 0L,
                    nargs = o.int("nargs") ?: 0,
                    nlocals = o.int("nlocals") ?: 0,
                    nbbs = o.int("nbbs") ?: 0,
                    callType = o.str("calltype") ?: "",
                    edges = o.int("edges") ?: 0,
                    signature = o.str("signature") ?: "",
                    callConvention = o.str("cc") ?: ""
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ==================================================================
    // izzj → List<StringInfo>
    // ==================================================================

    fun parseStrings(jsonStr: String): List<StringInfo> {
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                StringInfo(
                    string = o.str("string") ?: "",
                    address = o.long("vaddr") ?: o.long("addr") ?: 0L,
                    paddr = o.long("paddr") ?: 0L,
                    size = o.int("size") ?: o.int("length") ?: 0,
                    section = o.str("section") ?: ""
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ==================================================================
    // pdj → List<DisasmInstruction>
    // Rizin pdj 格式：[{ "offset": 0x1234, "size": 4, "bytes": "0001c0d2", "opcode": "mov x0, #0", "disasm": "mov x0, #0" }, ...]
    // ==================================================================

    fun parseDisassembly(jsonStr: String): List<DisasmInstruction> {
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val addr = o.long("offset") ?: return@mapNotNull null
                val size = o.int("size") ?: 4
                // bytes 字段是 hex 字符串（如 "0001c0d2"），转 ByteArray
                val hexStr = o.str("bytes") ?: ""
                val bytes = hexStr.chunked(2).mapNotNull {
                    it.toInt(16).toByte()
                }.toByteArray().takeIf { it.size == size }
                    ?: ByteArray(size)
                // opcode 优先于 disasm
                val rawOpcode = o.str("opcode") ?: o.str("disasm") ?: ""
                // Rizin 对无法解码的字节返回 "invalid"，
                // 改为 .word 0x{hex} 显示，与旧 Capstone 行为一致
                val opcode = if (rawOpcode.isBlank() || rawOpcode == "invalid") {
                    ".word 0x${hexStr.takeIf { it.isNotBlank() } ?: "00000000"}"
                } else {
                    rawOpcode
                }
                val mnemonic: String
                val opStr: String
                val spaceIdx = opcode.indexOfFirst { it == ' ' || it == '\t' }
                if (spaceIdx < 0) {
                    mnemonic = opcode
                    opStr = ""
                } else {
                    mnemonic = opcode.substring(0, spaceIdx)
                    opStr = opcode.substring(spaceIdx + 1).trim()
                }
                DisasmInstruction(
                    address = addr,
                    size = size,
                    mnemonic = mnemonic,
                    opStr = opStr,
                    bytes = bytes
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ==================================================================
    // axtj / axfj → List<Xref>
    // axtj: references to address (to ← from)
    // axfj: references from address (from → to)
    // ==================================================================

    fun parseXrefs(jsonStr: String, isFrom: Boolean): List<Xref> {
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                val from = o.long("from") ?: o.long("addr") ?: 0L
                val to = o.long("to") ?: o.long("ref") ?: 0L
                val type = XrefType.fromString(o.str("type") ?: "")
                if (isFrom) {
                    Xref(from = from, to = to, type = type, perm = o.str("perm") ?: "")
                } else {
                    Xref(from = from, to = to, type = type, perm = o.str("perm") ?: "")
                }
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ==================================================================
    // afbj → List<BasicBlock>
    // ==================================================================

    fun parseBasicBlocks(jsonStr: String): List<BasicBlock> {
        return try {
            val arr = json.parseToJsonElement(jsonStr).jsonArray
            arr.mapNotNull { el ->
                val o = el.jsonObject
                BasicBlock(
                    addr = o.long("addr") ?: 0L,
                    size = o.long("size") ?: 0L,
                    nInstr = o.int("ninstr") ?: o.int("ninsns") ?: 0,
                    succs = (o["successors"] as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.content?.let { c ->
                            if (c.startsWith("0x")) c.toLongOrNull(16) else c.toLongOrNull()
                        }
                    } ?: emptyList(),
                    preds = (o["predecessors"] as? JsonArray)?.mapNotNull {
                        (it as? JsonPrimitive)?.content?.let { c ->
                            if (c.startsWith("0x")) c.toLongOrNull(16) else c.toLongOrNull()
                        }
                    } ?: emptyList()
                )
            }
        } catch (_: Throwable) { emptyList() }
    }

    // ==================================================================
    // 工具函数
    // ==================================================================

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.let {
            // Rizin 的地址可能是 "0x1234" 或十进制
            if (it.startsWith("0x") || it.startsWith("-0x")) {
                it.toLongOrNull(16) ?: it.removePrefix("-").toLongOrNull(16)?.let { v -> -v }
            } else {
                it.toLongOrNull()
            }
        } ?: (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonPrimitive.intOrNull(): Int? = when (isString) {
        true -> content.toIntOrNull() ?: content.toIntOrNull(16)
        false -> content.toIntOrNull()
    }

    private fun JsonPrimitive.longOrNull(): Long? = when (isString) {
        true -> content.toLongOrNull() ?: content.toLongOrNull(16)
        false -> content.toLongOrNull()
    }

    private fun parseSymbolType(s: String?): SymbolType = when (s?.lowercase()) {
        "func", "function" -> SymbolType.FUNC
        "object" -> SymbolType.OBJECT
        "section" -> SymbolType.SECTION
        "file" -> SymbolType.FILE
        "common" -> SymbolType.COMMON
        "tls" -> SymbolType.TLS
        "notype", "notype-" -> SymbolType.NOTYPE
        else -> SymbolType.NOTYPE
    }

    private fun parseSymbolBind(s: String?): SymbolBind = when (s?.lowercase()) {
        "local" -> SymbolBind.LOCAL
        "global" -> SymbolBind.GLOBAL
        "weak" -> SymbolBind.WEAK
        else -> SymbolBind.LOCAL
    }

    private fun permFromFlags(flags: Long): String {
        val sb = StringBuilder(3)
        sb.append('r')  // 默认可读
        sb.append(if ((flags and 0x1L) != 0L) 'w' else '-')
        sb.append(if ((flags and 0x4L) != 0L) 'x' else '-')
        return sb.toString()
    }
}
