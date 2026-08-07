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
class RizinEngine(
    /** 缓存目录（用于持久化 Rizin Project 文件），为 null 时跳过项目持久化。 */
    private val cacheDir: String? = null
) : BinaryAnalysisEngine {

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

    /** handle.value → 对应的项目文件路径（用于 close 时清理）。 */
    private val projectPaths = mutableMapOf<Long, String>()

    /** handle.value → 打开的 so 文件路径（用于 fileSize 等取真实文件信息）。 */
    private val filePaths = mutableMapOf<Long, String>()

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
        filePaths[h] = filePath

        // 尝试从 Rizin Project 恢复（跳过 aaa 全量分析）
        var projectLoaded = false
        val projectPath = cacheDir?.let { computeProjectPath(filePath, it) }
        if (projectPath != null) {
            val loaded = withContext(Dispatchers.IO) { RizinBindings.projectLoad(corePtr, projectPath) }
            if (loaded) {
                projectPaths[h] = projectPath
                projectLoaded = true
                Log.i(TAG, "Rizin Project 加载成功: $projectPath")
            } else {
                Log.i(TAG, "Rizin Project 不存在或加载失败，将执行 aaa 分析: $projectPath")
            }
        }

        // 项目加载失败或无项目文件 → 执行 aaa 全量分析
        if (!projectLoaded && options.autoAnalyze) {
            withContext(Dispatchers.IO) { RizinBindings.analyze(corePtr) }
            // 分析完成后保存项目文件（供下次进程启动后复用）
            if (projectPath != null) {
                val saved = withContext(Dispatchers.IO) { RizinBindings.projectSave(corePtr, projectPath) }
                if (saved) {
                    projectPaths[h] = projectPath
                    Log.i(TAG, "Rizin Project 保存成功: $projectPath")
                } else {
                    Log.w(TAG, "Rizin Project 保存失败: $projectPath")
                }
            }
        }

        // 项目恢复成功。轻量探针：记录恢复态是否真的带 xref 表（.rzdb 是否序列化 xref）。
        // 只做日志、不做自动修复：在已加载项目之上再叠 aar/aaa 会让分析态内存翻倍，
        // 实测 libapp.so 恢复后补扫 aar 触发原生 OOM（Scudo Out of memory → SIGABRT）。
        // xref 的修复由「无项目时的首开全量 aaa」保证，或由上层按需触发（loadDartFunctionLabels）。
        if (projectLoaded) {
            val hasXrefs = projectHasXrefs(AnalysisHandle(h))
            Log.i(TAG, "Rizin Project 恢复完成，xref 探针=$hasXrefs（false=项目未序列化 xref 表）")
        }

        return OpenResult.Success(AnalysisHandle(h), filePath, engineId)
    }

    /**
     * 探测恢复态是否带 xref：对入口点（[iEj] 首个 vaddr）查一次 axtj/axfj 是否非空。
     * 仅用于诊断日志，不做任何分析，代价为两次轻量查询。
     */
    private suspend fun projectHasXrefs(handle: AnalysisHandle): Boolean {
        val entries = cmd(handle, "iEj") ?: return false
        val m = Regex("\"vaddr\":(\\d+)").find(entries) ?: return false
        val vaddr = m.groupValues[1].toLongOrNull() ?: return false
        val hex = "0x${vaddr.toString(16)}"
        val to = cmd(handle, "axtj @ $hex") ?: return false
        val from = cmd(handle, "axfj @ $hex") ?: return false
        return (to.isNotBlank() && to.trim() != "[]") ||
            (from.isNotBlank() && from.trim() != "[]")
    }

    override suspend fun close(handle: AnalysisHandle) {
        val corePtr = openHandles.remove(handle.value) ?: return
        projectPaths.remove(handle.value)
        filePaths.remove(handle.value)
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
        // fileSize 直接用真实文件长度（此前用「i~size[1]」命令解析不稳定，返回 0）
        val fileSize = filePaths[handle.value]?.let { withContext(Dispatchers.IO) { File(it).length() } } ?: 0L
        return RizinJsonParser.parseFileInfo(json, fileSize)
    }

    override suspend fun getSections(handle: AnalysisHandle): List<SectionInfo> {
        val json = cmd(handle, "iSj") ?: return emptyList()
        return RizinJsonParser.parseSections(json)
    }

    override suspend fun getSymbols(handle: AnalysisHandle, includeDynamic: Boolean): List<SymbolInfo> {
        // isj = 全部符号；iej = 导出（.dynsym 已定义）；iij = 导入（.dynsym 未定义）
        val merged = RizinJsonParser.parseSymbols(cmd(handle, "isj") ?: return emptyList()).toMutableList()
        if (!includeDynamic) return merged
        val seen = merged.map { it.address to it.name }.toMutableSet()
        for (sym in RizinJsonParser.parseSymbols(cmd(handle, "iej") ?: "")) {
            if (sym.address to sym.name !in seen) { merged.add(sym); seen.add(sym.address to sym.name) }
        }
        for (imp in RizinJsonParser.parseImports(cmd(handle, "iij") ?: "")) {
            val sym = SymbolInfo(
                name = imp.name,
                address = imp.address,
                size = 0,
                type = RizinJsonParser.parseSymbolType(imp.type),
                bind = if (imp.bind.lowercase() == "weak") SymbolBind.WEAK else SymbolBind.GLOBAL
            )
            if (sym.address to sym.name !in seen) { merged.add(sym); seen.add(sym.address to sym.name) }
        }
        return merged
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
        // izzj 对 Dart AOT 大库返回空（io map 枚举异常），改为 RandomAccessFile 流式整文件扫描。
        // 原实现走 readBytes → JNI → nativeReadBytes，40MB 文件需 160 次 JNI 往返 +
        // 160 次 native 兜底路径；改为单次 RandomAccessFile 256KB 流式读，绕过 Rizin io。
        val path = filePaths[handle.value] ?: return emptyList()
        val file = File(path)
        val fileSize = withContext(Dispatchers.IO) { file.length() }
        if (fileSize <= 0) return emptyList()
        val sections = try { getSections(handle) } catch (_: Exception) { emptyList() }

        val out = mutableListOf<StringInfo>()
        var runStart = -1L
        var run = StringBuilder()
        var pos = 0L

        fun flushRun() {
            if (run.isNotEmpty()) {
                if (run.length >= options.minLen && run.length <= options.maxLen) {
                    val paddr = runStart
                    val sec = sections.lastOrNull {
                        it.offset > 0 && paddr >= it.offset && paddr < it.offset + it.size
                    }
                    val vaddr = if (sec != null) sec.address + (paddr - sec.offset) else paddr
                    out += StringInfo(run.toString(), vaddr, paddr, run.length, sec?.name ?: "")
                }
                run.clear()
                runStart = -1L
            }
        }

        withContext(Dispatchers.IO) {
            java.io.RandomAccessFile(path, "r").use { raf ->
                val buffer = ByteArray(CHUNK.toInt())
                while (pos < fileSize) {
                    val want = minOf(CHUNK, fileSize - pos).toInt()
                    val read = raf.read(buffer, 0, want)
                    if (read <= 0) break
                    for (i in 0 until read) {
                        val c = buffer[i].toInt() and 0xff
                        if (c in 0x20..0x7e) {
                            if (runStart < 0) runStart = pos + i
                            if (run.length < options.maxLen) run.append(c.toChar())
                        } else {
                            flushRun()
                        }
                    }
                    pos += read
                }
            }
        }
        flushRun()

        return out
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
        // 名字清洗 + 同址去重：
        // - Rizin flag 名只接受安全字符，而 Blutter 类名含 ':'（dart:core）、'$'（内部类）、
        //   '<'/'>'（泛型化名）等，直接拼接会让整条复合命令解析失败（cmdStr 返回 null）
        //   → 整批放弃 → 注入 0 个。
        // - 同一地址重复定义 flag 会互相覆盖，先去重避免无谓命令。
        val sanitized = functions.mapNotNull { (addr, name) ->
            if (addr <= 0) null else addr to sanitizeFlagName(name)
        }.distinctBy { it.first }

        var defined = 0
        // 每批 200 条（约 8KB）：复合命令过长同样会导致 rz_core_cmd_str 失败
        for (batch in sanitized.chunked(200)) {
            val command = batch.joinToString(";") { (addr, name) ->
                "f $name @ 0x${addr.toString(16)}"
            }
            if (cmd(handle, command) != null) {
                defined += batch.size
            } else {
                // 复合命令失败：降级为逐条注入，坏名字只影响自身，不拖垮整批
                for ((addr, name) in batch) {
                    if (cmd(handle, "f $name @ 0x${addr.toString(16)}") != null) {
                        defined++
                    } else {
                        Log.w(TAG, "defineFunctions 单条注入失败: $name @ 0x${addr.toString(16)}")
                    }
                }
            }
        }
        return defined
    }

    /** Rizin flag 名清洗：只保留字母/数字/下划线/点，其余字符替换为下划线（保持可读性）。 */
    private fun sanitizeFlagName(name: String): String {
        val sb = StringBuilder(name.length)
        var changed = false
        for (c in name) {
            if (c.isLetterOrDigit() || c == '_' || c == '.') {
                sb.append(c)
            } else {
                sb.append('_')
                changed = true
            }
        }
        return if (changed) sb.toString() else name
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
        //
        // 注意：这里不再追加 e anal.datarefs=true / e anal.xrefs=true。
        // anal.datarefs 在 Rizin 中默认已开启，且对数据密集的 libapp.so（Dart 对象池）
        // 显式开启会让 aar 在「已加载项目」之上叠加分析时把内存打爆（实测 Scudo OOM 崩溃）。
        // 保持默认配置，让 aar 行为与首开 aaa 一致、可存活。

        val r = cmd(handle, "aar") ?: return false
        val trimmed = r.trim().lowercase()
        if (trimmed.startsWith("error", ignoreCase = true)) {
            Log.w(TAG, "aar 不可用，回退到 aac")
            val r2 = cmd(handle, "aac") ?: return false
            if (r2.trim().startsWith("unknown", ignoreCase = true)) return false
        }

        // 探针校验：命令是否报错不能证明 xref 真的建出来了。
        // 取第一个已知函数，确认 axtj/axfj 确实能查到 xref 才算成功。
        // 函数为空（无法探针）时宽松地视为成功，避免无谓重扫。
        val probeOk = xrefsProbeOk(handle)
        if (!probeOk) Log.w(TAG, "reanalyzeXrefs 探针未检出 xref，判定为失败")
        return probeOk
    }

    /**
     * 探针校验 xref 表已可查询。
     * 取 `aflj` 第一个函数的 vaddr，查 `axtj`/`axfj` 是否非空。
     * 无函数可探时返回 true（无法验证，交由外层放行）。
     */
    private suspend fun xrefsProbeOk(handle: AnalysisHandle): Boolean {
        val funcs = listFunctions(handle)
        if (funcs.isEmpty()) return true
        val probe = funcs.first().vaddr
        val hex = "0x${probe.toString(16)}"
        val to = cmd(handle, "axtj @ $hex") ?: return false
        val from = cmd(handle, "axfj @ $hex") ?: return false
        val hasTo = to.isNotBlank() && to.trim() != "[]"
        val hasFrom = from.isNotBlank() && from.trim() != "[]"
        return hasTo || hasFrom
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
        val core = corePtr(handle)
        if (core == 0L) return paddr
        // 原生 rz_io_p2v 按段 map 换算：io.va=true 时「s <paddr>; v.」命令
        // 会把入参当虚拟地址解释，v. 原样返回 seek，恒等失效（PIE 库差
        // 0x4000 时永远失败）；p2v 与 vp 对称，与 vaddrToPaddr 语义一致。
        val v = withContext(Dispatchers.IO) { RizinBindings.paddrToVaddr(core, paddr) }
        if (v != paddr) return v
        // 映射外回退：命令路径（io.va=false 会话的 v. 语义正确）
        if (cmd(handle, "s 0x${paddr.toString(16)}") == null) return paddr
        return cmd(handle, "v.")?.trim()?.toLongOrNull(16) ?: paddr
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

    override suspend fun md5(handle: AnalysisHandle): String? =
        streamDigest(handle, "MD5")

    override suspend fun sha256(handle: AnalysisHandle): String? =
        streamDigest(handle, "SHA-256")

    override suspend fun crc32(handle: AnalysisHandle, offset: Long?, size: Long?): Long? {
        val path = filePaths[handle.value] ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val file = File(path)
                val fileSize = file.length()
                val start = offset ?: 0L
                val length = size ?: (fileSize - start)
                if (length <= 0 || start >= fileSize) return@withContext null
                val crc = java.util.zip.CRC32()
                java.io.RandomAccessFile(path, "r").use { raf ->
                    raf.seek(start)
                    val end = minOf(start + length, fileSize)
                    val buffer = ByteArray(STREAM_BUFFER)
                    var pos = start
                    while (pos < end) {
                        val want = minOf(STREAM_BUFFER.toLong(), end - pos).toInt()
                        val read = raf.read(buffer, 0, want)
                        if (read <= 0) break
                        crc.update(buffer, 0, read)
                        pos += read
                    }
                }
                crc.value
            } catch (e: Exception) {
                Log.e(TAG, "crc32 失败: $path", e)
                null
            }
        }
    }

    /**
     * 以 RandomAccessFile 流式计算整文件摘要。
     *
     * 完全绕过 Rizin io + JNI 往返：文件内容不变，不需要走 RzCore。
     * 原实现走 readBytes → JNI → nativeReadBytes，40MB 文件需 160 次 JNI 往返；
     * 改为单次 RandomAccessFile 64KB 流式读，提速 5-10 倍。
     */
    private suspend fun streamDigest(handle: AnalysisHandle, alg: String): String? {
        val path = filePaths[handle.value] ?: return null
        return withContext(Dispatchers.IO) {
            try {
                val md = java.security.MessageDigest.getInstance(alg)
                java.io.RandomAccessFile(path, "r").use { raf ->
                    val buffer = ByteArray(STREAM_BUFFER)
                    while (true) {
                        val read = raf.read(buffer)
                        if (read <= 0) break
                        md.update(buffer, 0, read)
                    }
                }
                md.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Log.e(TAG, "streamDigest($alg) 失败: $path", e)
                null
            }
        }
    }

    companion object {
        private const val TAG = "RizinEngine"
        private const val CHUNK = 256L * 1024L
        private const val STREAM_BUFFER = 64 * 1024  // 哈希/CRC 流式读 buffer（64KB）

        /**
         * 计算 Rizin Project 文件路径。
         *
         * 格式：{cacheDir}/rizin_projects/{SO文件名}_{fileSize}_{fileMtime}.rzdb
         * 文件变更（size 或 mtime 变化）时自动生成新路径，旧项目自然失效。
         */
        private fun computeProjectPath(soPath: String, cacheDir: String): String {
            val file = File(soPath)
            val name = file.name
            val size = file.length()
            val mtime = file.lastModified()
            val projectDir = File(cacheDir, "rizin_projects")
            projectDir.mkdirs()
            return File(projectDir, "${name}_${size}_${mtime}.rzdb").absolutePath
        }
    }
}
