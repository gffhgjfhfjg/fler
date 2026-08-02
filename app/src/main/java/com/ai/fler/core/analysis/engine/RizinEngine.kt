package com.ai.fler.core.analysis.engine

import com.ai.fler.core.analysis.*
import com.ai.fler.core.jni.RizinBindings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Rizin 分析引擎。
 *
 * 通过 JNI 调用 Rizin 静态库（librz_core.a 等 26 个 .a 静态链接进 libfler_jni.so），
 * 提供完整的二进制分析能力：
 * - ELF 结构解析（节区、符号、导入、重定位）
 * - 函数识别（aaa 自动分析）
 * - 交叉引用（xref）
 * - 控制流图（基本块）
 * - 字符串扫描
 * - 反汇编 / 汇编
 * - 字节级编辑
 * - 地址转换（vaddr↔paddr）
 * - 二进制哈希
 *
 * 数据查询通过 `rz_core_cmd_str(core, "命令j")` 执行并返回 JSON，
 * 由 [RizinJsonParser] 解析为 Engine 抽象层数据模型。
 *
 * 生命周期：
 * 1. [open] → `rz_core_new` + `rz_core_file_open` + `rz_core_bin_load`
 * 2. [autoAnalyze] → `rz_core_analysis_all`（aaa，可选但推荐）
 * 3. 各种查询方法 → `rz_core_cmd_str` + JSON 解析
 * 4. [close] → `rz_core_free`
 */
class RizinEngine : BinaryAnalysisEngine {

    override val engineId: String = "rizin"
    override val displayName: String = "Rizin v0.9.x"
    override val isAvailable: Boolean = true

    override val capabilities: Set<AnalysisCapability> = setOf(
        AnalysisCapability.ELF_PARSING,
        AnalysisCapability.DISASSEMBLY,
        AnalysisCapability.ASSEMBLY,
        AnalysisCapability.FUNCTION_ANALYSIS,
        AnalysisCapability.XREF,
        AnalysisCapability.CFG,
        AnalysisCapability.STRING_SCAN,
        AnalysisCapability.DEMANGLE,
        AnalysisCapability.BYTE_EDIT,
        AnalysisCapability.ADDRESS_TRANSLATION,
        AnalysisCapability.BINARY_HASH,
        AnalysisCapability.SIGNATURE_MATCH
    )

    /** handle.value → RzCore* 指针。 */
    private val openHandles = mutableMapOf<Long, Long>()
    private var nextHandle = 1L

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    override suspend fun open(filePath: String, options: OpenOptions): OpenResult {
        val exists = withContext(Dispatchers.IO) { File(filePath).exists() }
        if (!exists) return OpenResult.Failure("文件不存在: $filePath")

        val corePtr = withContext(Dispatchers.IO) { RizinBindings.open(filePath) }
        if (corePtr == 0L) {
            return OpenResult.Failure("Rizin 打开文件失败: $filePath")
        }

        val h = nextHandle++
        openHandles[h] = corePtr

        // 自动分析（默认 STANDARD 级别）
        if (options.autoAnalyze) {
            withContext(Dispatchers.IO) { RizinBindings.analyze(corePtr) }
        }

        return OpenResult.Success(AnalysisHandle(h), filePath, engineId)
    }

    override suspend fun close(handle: AnalysisHandle) {
        val corePtr = openHandles.remove(handle.value) ?: return
        withContext(Dispatchers.IO) { RizinBindings.close(corePtr) }
    }

    override suspend fun isHandleValid(handle: AnalysisHandle): Boolean =
        openHandles.containsKey(handle.value)

    /** 自动分析（aaa）。通常在 open 时已自动执行，也可手动触发重新分析。 */
    suspend fun autoAnalyze(handle: AnalysisHandle): Boolean {
        val corePtr = openHandles[handle.value] ?: return false
        return withContext(Dispatchers.IO) { RizinBindings.analyze(corePtr) }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    private suspend fun cmd(handle: AnalysisHandle, command: String): String? {
        val corePtr = openHandles[handle.value] ?: return null
        return withContext(Dispatchers.IO) { RizinBindings.cmdStr(corePtr, command) }
    }

    private fun corePtr(handle: AnalysisHandle): Long =
        openHandles[handle.value] ?: 0L

    // ------------------------------------------------------------------
    // ELF 结构
    // ------------------------------------------------------------------

    override suspend fun getFileInfo(handle: AnalysisHandle): FileInfo? {
        val json = cmd(handle, "ij") ?: return null
        val path = openHandles[handle.value]
        val fileSize = if (path != 0L) {
            // corePtr 对应的文件大小通过 fileSize 命令获取
            val sizeStr = cmd(handle, "i~size[1]") ?: ""
            sizeStr.trim().toLongOrNull() ?: 0L
        } else 0L
        return RizinJsonParser.parseFileInfo(json, fileSize)
    }

    override suspend fun getSections(handle: AnalysisHandle): List<SectionInfo> {
        val json = cmd(handle, "iSj") ?: return emptyList()
        return RizinJsonParser.parseSections(json)
    }

    override suspend fun getSymbols(handle: AnalysisHandle, includeDynamic: Boolean): List<SymbolInfo> {
        // isj = 静态符号；isJj = 动态符号（大小写 J 表示动态）
        // Rizin isj 包含所有符号，isJj 是动态符号
        val json = if (includeDynamic) {
            cmd(handle, "isj") ?: return emptyList()
        } else {
            cmd(handle, "isj") ?: return emptyList()
        }
        return RizinJsonParser.parseSymbols(json)
    }

    override suspend fun getImports(handle: AnalysisHandle): List<ImportInfo> {
        val json = cmd(handle, "iij") ?: return emptyList()
        return RizinJsonParser.parseImports(json)
    }

    override suspend fun getRelocs(handle: AnalysisHandle): List<RelocInfo> {
        val json = cmd(handle, "irj") ?: return emptyList()
        return RizinJsonParser.parseRelocs(json)
    }

    override suspend fun scanStrings(handle: AnalysisHandle, options: StringScanOptions): List<StringInfo> {
        // izzj 扫描所有字符串
        val json = cmd(handle, "izzj") ?: return emptyList()
        val all = RizinJsonParser.parseStrings(json)
        // 按选项过滤
        return all.filter {
            it.string.length in options.minLen..options.maxLen &&
            (options.scanSections.isEmpty() || it.section in options.scanSections)
        }
    }

    // ------------------------------------------------------------------
    // 函数分析
    // ------------------------------------------------------------------

    override suspend fun listFunctions(handle: AnalysisHandle): List<FunctionInfo> {
        val json = cmd(handle, "aflj") ?: return emptyList()
        return RizinJsonParser.parseFunctions(json)
    }

    override suspend fun findFunctionContaining(handle: AnalysisHandle, address: Long): FunctionInfo? {
        // afi. 查找包含指定地址的函数
        val hexAddr = "0x${address.toString(16)}"
        val json = cmd(handle, "afij @ $hexAddr") ?: return null
        val list = RizinJsonParser.parseFunctions(json)
        return list.firstOrNull { address in it.vaddr..(it.vaddr + it.size) }
    }

    override suspend fun findFunctionsByName(handle: AnalysisHandle, query: String): List<FunctionInfo> {
        val all = listFunctions(handle)
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return all.filter {
            it.name.lowercase().contains(q) ||
            (it.signature.isNotBlank() && it.signature.lowercase().contains(q))
        }
    }

    override suspend fun getFunctionCfg(handle: AnalysisHandle, functionOffset: Long): List<BasicBlock> {
        val hexAddr = "0x${functionOffset.toString(16)}"
        val json = cmd(handle, "afbj @ $hexAddr") ?: return emptyList()
        return RizinJsonParser.parseBasicBlocks(json)
    }

    // ------------------------------------------------------------------
    // 反汇编 / 汇编
    // ------------------------------------------------------------------

    override suspend fun disassemble(handle: AnalysisHandle, offset: Long, size: Long): List<DisasmInstruction> {
        // pdj N @ offset：反汇编 N 条指令
        // 先计算指令条数（size / 4，ARM64 固定 4 字节指令）
        val count = (size / 4).toInt().coerceIn(1, 4096)
        val hexAddr = "0x${offset.toString(16)}"
        val json = cmd(handle, "pdj $count @ $hexAddr") ?: return emptyList()
        val list = RizinJsonParser.parseDisassembly(json)
        // 如果 pdj 结果不够，可能是不连续的；用 pDj 按 size 反汇编
        if (list.isEmpty()) {
            val json2 = cmd(handle, "pDj ${size.toInt()} @ $hexAddr") ?: return emptyList()
            return RizinJsonParser.parseDisassembly(json2)
        }
        return list
    }

    override suspend fun assemble(handle: AnalysisHandle, assembly: String, address: Long): ByteArray? {
        // rasm - assemble 指令
        // Rizin 的 pa 命令：pa "mov x0, #1" 返回 hex 字符串
        val hexAddr = "0x${address.toString(16)}"
        val result = cmd(handle, "pa \"$assembly\" @ $hexAddr") ?: return null
        val hex = result.trim()
        if (hex.isEmpty()) return null
        // hex 字符串 → ByteArray
        return hex.chunked(2).mapNotNull {
            it.toInt(16).toByte()
        }.toByteArray().takeIf { it.isNotEmpty() }
    }

    // ------------------------------------------------------------------
    // 交叉引用
    // ------------------------------------------------------------------

    override suspend fun xrefsTo(handle: AnalysisHandle, target: Long): List<Xref> {
        val hexAddr = "0x${target.toString(16)}"
        val json = cmd(handle, "axtj @ $hexAddr") ?: return emptyList()
        return RizinJsonParser.parseXrefs(json, isFrom = false)
    }

    override suspend fun xrefsFrom(handle: AnalysisHandle, from: Long): List<Xref> {
        val hexAddr = "0x${from.toString(16)}"
        val json = cmd(handle, "axfj @ $hexAddr") ?: return emptyList()
        return RizinJsonParser.parseXrefs(json, isFrom = true)
    }

    // ------------------------------------------------------------------
    // 字节编辑
    // ------------------------------------------------------------------

    override suspend fun readBytes(handle: AnalysisHandle, offset: Long, size: Long): ByteArray {
        val ptr = corePtr(handle)
        if (ptr == 0L) return ByteArray(0)
        return withContext(Dispatchers.IO) {
            RizinBindings.readBytes(ptr, offset, size.toInt()) ?: ByteArray(0)
        }
    }

    override suspend fun writeBytes(handle: AnalysisHandle, offset: Long, data: ByteArray): Boolean {
        val ptr = corePtr(handle)
        if (ptr == 0L) return false
        return withContext(Dispatchers.IO) {
            RizinBindings.writeBytes(ptr, offset, data)
        }
    }

    // ------------------------------------------------------------------
    // 地址转换
    // ------------------------------------------------------------------

    override suspend fun paddrToVaddr(handle: AnalysisHandle, paddr: Long): Long {
        val hexAddr = "0x${paddr.toString(16)}"
        val result = cmd(handle, "s $hexAddr; pi 1") ?: return paddr
        // 用 v. 命令获取当前地址的虚拟地址
        val vaddr = cmd(handle, "v.") ?: return paddr
        return vaddr.trim().toLongOrNull(16) ?: paddr
    }

    override suspend fun vaddrToPaddr(handle: AnalysisHandle, vaddr: Long): Long {
        val hexAddr = "0x${vaddr.toString(16)}"
        val result = cmd(handle, "s $hexAddr; vp") ?: return vaddr
        // vp 输出 "vaddr => paddr" 格式
        val parts = result.trim().split("=>")
        return parts.getOrNull(1)?.trim()?.toLongOrNull(16) ?: vaddr
    }

    // ------------------------------------------------------------------
    // 二进制哈希
    // ------------------------------------------------------------------

    override suspend fun md5(handle: AnalysisHandle): String? {
        val result = cmd(handle, "~ee") ?: return null
        // Rizin 不直接提供 md5 命令，用 ph 命令
        val md5 = cmd(handle, "ph md5") ?: return null
        return md5.trim().takeIf { it.isNotEmpty() }
    }

    override suspend fun sha256(handle: AnalysisHandle): String? {
        val sha256 = cmd(handle, "ph sha256") ?: return null
        return sha256.trim().takeIf { it.isNotEmpty() }
    }

    override suspend fun crc32(handle: AnalysisHandle, offset: Long?, size: Long?): Long? {
        val cmd = if (offset != null) {
            "ph crc32 ${size ?: 1024} @ 0x${offset.toString(16)}"
        } else {
            "ph crc32"
        }
        val result = this.cmd(handle, cmd) ?: return null
        return result.trim().toLongOrNull(16)
    }

    companion object {
        private const val TAG = "RizinEngine"
    }
}
