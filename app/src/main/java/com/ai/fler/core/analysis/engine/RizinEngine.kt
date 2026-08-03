package com.ai.fler.core.analysis.engine

import android.util.Log
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
    // 诊断工具
    // ------------------------------------------------------------------

    /** 查询 Rizin 配置项的值（e key 命令）。 */
    override suspend fun getConfig(handle: AnalysisHandle, key: String): String? {
        val result = cmd(handle, "e $key") ?: return null
        return result.trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 诊断地址空间状态，确认 Blutter 的 vaddr 与 Rizin 地址空间是否一致。
     * 打印日志供 logcat 分析。
     */
    override suspend fun checkAddressSpace(handle: AnalysisHandle, testAddr: Long) {
        // 1) 查询 io.va 配置
        val va = cmd(handle, "e io.va")?.trim() ?: "null"
        Log.i(TAG, "===== 地址空间诊断 =====")
        Log.i(TAG, "io.va = $va")

        // 2) 测试 afo（该地址是否被函数包含）
        val hexAddr = "0x${testAddr.toString(16)}"
        val afo = cmd(handle, "afo @ $hexAddr")?.trim() ?: "null"
        Log.i(TAG, "afo @ $hexAddr = $afo")

        // 3) 测试 vaddrToPaddr
        val paddr = vaddrToPaddr(handle, testAddr)
        val paddrHex = "0x${paddr.toString(16)}"
        val isSame = paddr == testAddr
        Log.i(TAG, "vaddrToPaddr($hexAddr) = $paddrHex (与原地址相同=$isSame)")

        // 4) 测试 axtj（注入前的 xref 数量）
        val xrefJson = cmd(handle, "axtj @ $hexAddr") ?: "null"
        Log.i(TAG, "注入前 axtj @ $hexAddr 返回长度 = ${xrefJson.length}")
        Log.i(TAG, "===== 诊断结束 =====")
    }

    /**
     * 注入后诊断：确认 defineFunction + reanalyzeXrefs 后 xref 是否重建成功。
     */
    override suspend fun diagnosticAfterInjection(handle: AnalysisHandle, testAddr: Long) {
        val hexAddr = "0x${testAddr.toString(16)}"
        Log.i(TAG, "===== 注入后诊断 =====")

        // 1) 检查 flag 是否已设置
        val flag = cmd(handle, "f @ $hexAddr")?.trim() ?: "null"
        Log.i(TAG, "f @ $hexAddr 返回 = $flag")

        // 2) 检查该地址是否已被注册为函数（afij）
        val afij = cmd(handle, "afij @ $hexAddr")?.trim() ?: "null"
        Log.i(TAG, "afij @ $hexAddr = ${afij.take(200)}")

        // 3) 关键对比：注入后的 xref 数量
        val xrefJson = cmd(handle, "axtj @ $hexAddr") ?: "null"
        Log.i(TAG, "注入后 axtj @ $hexAddr 返回长度 = ${xrefJson.length}")
        Log.i(TAG, "===== 注入后诊断结束 =====")
    }

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
        // isj = 静态符号；isJj = 动态符号（大小写 J 表示动态），两者字段结构一致
        if (includeDynamic) {
            // 契约：includeDynamic=true 时需包含动态符号，两条命令分别查询后合并去重
            val staticJson = cmd(handle, "isj") ?: return emptyList()
            val dynamicJson = cmd(handle, "isJj") ?: return emptyList()
            val merged = RizinJsonParser.parseSymbols(staticJson).toMutableList()
            val seen = merged.map { it.address }.toMutableSet()
            for (sym in RizinJsonParser.parseSymbols(dynamicJson)) {
                if (sym.address !in seen) { merged.add(sym); seen.add(sym.address) }
            }
            return merged
        }
        return RizinJsonParser.parseSymbols(cmd(handle, "isj") ?: return emptyList())
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

    override suspend fun defineFunction(handle: AnalysisHandle, address: Long, name: String): Boolean {
        val hexAddr = "0x${address.toString(16)}"
        // 只设 flag 名（UI 反汇编显示用），不调 af。
        //
        // 为什么不用 af 注册到 Rizin 函数 DB：
        //   af 会清除该地址范围内的已有 xref 条目，然后需要 aar 重建。
        //   批量注入（见 [defineFunctions]）是对每个函数只设 flag，不碰函数 DB，
        //   因此不会触发 xref 重建，也无时序问题。
        //
        // 为什么不调 af 也没问题：
        //   reanalyzeXrefs 用 aar 扫描整个二进制，不依赖函数边界，
        //   Dart 函数不在 Rizin 函数 DB 中也能被 aar 找到引用。
        //   aac 需要函数边界，但我们用 aar 而不是 aac。
        cmd(handle, "f $name @ $hexAddr")
        return true
    }

    /**
     * 批量设置 flag：多条 `f name @ addr` 用分号拼成一条命令，一次 JNI 调用完成。
     * 相比逐条 [defineFunction]，省去 N 次跨 JNI 的字符串往返（万级函数时收益明显）。
     */
    override suspend fun defineFunctions(handle: AnalysisHandle, functions: List<Pair<Long, String>>): Int {
        var defined = 0
        // 分批执行，避免单条命令过长（每批 500 条，命令约 20KB）
        for (batch in functions.chunked(500)) {
            val command = batch.joinToString(";") { (addr, name) ->
                "f $name @ 0x${addr.toString(16)}"
            }
            cmd(handle, command) ?: break
            defined += batch.size
        }
        return defined
    }

    override suspend fun reanalyzeXrefs(handle: AnalysisHandle): Boolean {
        // 用 aar 作为主命令：扫描整个二进制所有引用，不依赖函数边界。
        // 为什么不用 aac：
        //   aac 只扫描 Rizin 函数 DB 中已注册的函数体内的调用指令。
        //   Blutter 分析的 Dart 函数由 defineFunction 通过 af 注册到 DB 后，
        //   aac 理论上可以扫描它们，但实际测试中 aac 对某些 SO 文件可能
        //   输出 "unknown" 或空结果，导致 xref 表不完整。
        //   aar 更全面，扫描所有可执行代码段中的引用，不依赖函数边界识别。
        //
        // 副作用：defineFunction 中 af 会清除该地址范围内的 xref 条目，
        // 但 aar 会重新扫描整个二进制并重建所有 xref 表，覆盖 af 的副作用。
        val r = cmd(handle, "aar") ?: return false
        val trimmed = r.trim().lowercase()
        if (trimmed.startsWith("error", ignoreCase = true)) {
            Log.w(TAG, "aar 不可用，回退到 aac")
            val r2 = cmd(handle, "aac") ?: return false
            return !r2.trim().startsWith("unknown", ignoreCase = true)
        }
        return true
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
        if (cmd(handle, "s $hexAddr") == null) return paddr
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
