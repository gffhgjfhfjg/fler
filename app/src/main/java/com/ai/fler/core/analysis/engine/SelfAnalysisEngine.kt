package com.ai.fler.core.analysis.engine

import com.ai.fler.core.analysis.*
import com.ai.fler.core.analysis.assembler.KeystoneAssembler
import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.ElfParserBindings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.CRC32

/**
 * 自研分析引擎（旧 ElfParserBindings + CapstoneBindings + KeystoneAssembler 适配层）。
 *
 * 这是默认 fallback 引擎：Rizin 还没集成好时，它承担 100% 功能；后续 Rizin 到位后，
 * 它变成低优先级的 fallback 引擎（[AnalysisEnginePriority.SELF_ANALYSIS]），
 * 在 Rizin 不可用或加载失败时兜底。
 *
 * ElfParser 长驻：open 时创建 ElfParserBindings 并保持打开，close 时统一释放，
 * 避免每次查询都重新解析 ELF 头+节区表+符号表（提速 10-100 倍，仅 fallback 路径）。
 * [AnalysisSession] 对外部调用者做会话合并。
 */
class SelfAnalysisEngine(
    private val keystone: KeystoneAssembler
) : BinaryAnalysisEngine {

    override val engineId: String = "self"
    override val displayName: String = "自研 (ElfParser + Capstone + Keystone)"
    override val isAvailable: Boolean = true   // 始终可用（JNI 编进 libfler.so）

    override val capabilities: Set<AnalysisCapability> = setOf(
        AnalysisCapability.ELF_PARSING,
        AnalysisCapability.DISASSEMBLY,
        AnalysisCapability.ASSEMBLY,
        AnalysisCapability.BYTE_EDIT,
        AnalysisCapability.ADDRESS_TRANSLATION,
        AnalysisCapability.BINARY_HASH
    )

    // handle.value -> (ElfParserBindings, filePath)；parser 在 open 时创建并保持打开，
    // close 时释放。避免每次查询都 open+close 重新解析 ELF 头+节区表+符号表。
    private val openSessions = mutableMapOf<Long, ElfParserBindings>()
    private val filePaths = mutableMapOf<Long, String>()
    private var nextHandle = 1L

    override suspend fun open(filePath: String, options: OpenOptions): OpenResult {
        val exists = withContext(Dispatchers.IO) { File(filePath).exists() }
        if (!exists) return OpenResult.Failure("文件不存在: $filePath")
        val parser = ElfParserBindings()
        if (!parser.open(filePath)) {
            parser.close()
            return OpenResult.Failure("ElfParser 打开失败: $filePath")
        }
        val h = nextHandle++
        openSessions[h] = parser
        filePaths[h] = filePath
        return OpenResult.Success(AnalysisHandle(h), filePath, engineId)
    }

    override suspend fun close(handle: AnalysisHandle) {
        openSessions.remove(handle.value)?.close()
        filePaths.remove(handle.value)
    }
    override suspend fun isHandleValid(handle: AnalysisHandle): Boolean = openSessions.containsKey(handle.value)

    private inline fun <R> withParser(handle: AnalysisHandle, block: (ElfParserBindings, String) -> R): R? {
        val parser = openSessions[handle.value] ?: return null
        val path = filePaths[handle.value] ?: return null
        return block(parser, path)
    }

    // ------------------------------------------------------------------
    // ELF 结构
    // ------------------------------------------------------------------

    override suspend fun getFileInfo(handle: AnalysisHandle): FileInfo? = withParser(handle) { parser, path ->
        val file = File(path)
        // 把 .gnu_stack / .ARM.attributes 推断架构；自研 ElfParser 暂未提供 elfheader 的 e_flags，
        // 因此仅用 "arm"/64/little 等保守默认值，UI 展示时有即可。
        val hasArm64Sections = try {
            parser.getSections().any { s ->
                s.name == ".text" && (s.flags and com.ai.fler.core.jni.ElfSection.SHF_EXECINSTR) != 0L
            }
        } catch (_: Throwable) { false }
        FileInfo(
            arch = "arm",
            bits = 64,
            endian = "little",
            machine = if (hasArm64Sections) "AArch64" else "ARM",
            classType = "ELFCLASS64",
            os = "linux",
            stripped = false,
            fileSize = file.length()
        )
    }

    override suspend fun getSections(handle: AnalysisHandle): List<SectionInfo> =
        withParser(handle) { p, _ -> p.getSections().map { SectionInfo.fromElfSection(it) } }
            ?: emptyList()

    override suspend fun getSymbols(handle: AnalysisHandle, includeDynamic: Boolean): List<SymbolInfo> =
        withParser(handle) { p, _ ->
            val list = p.getSymbols().map { SymbolInfo.fromElfSymbol(it) }.toMutableList()
            if (includeDynamic) list.addAll(p.getDynamicSymbols().map { SymbolInfo.fromElfSymbol(it) })
            list
        } ?: emptyList()

    override suspend fun getImports(handle: AnalysisHandle): List<ImportInfo> = emptyList()
    override suspend fun getRelocs(handle: AnalysisHandle): List<RelocInfo> = emptyList()

    override suspend fun scanStrings(handle: AnalysisHandle, options: StringScanOptions): List<StringInfo> {
        val path = filePaths[handle.value] ?: return emptyList()
        val fileSize = withContext(Dispatchers.IO) { runCatching { File(path).length() }.getOrNull() } ?: 0L
        val data = withParser(handle) { p, _ ->
            when {
                options.scanSections.isNotEmpty() -> {
                    var out: ByteArray? = null
                    for (sn in options.scanSections) {
                        val bytes = p.getSectionData(sn)
                        if (bytes.isNotEmpty()) { out = bytes; break }
                    }
                    out ?: ByteArray(0)
                }
                fileSize <= 0L -> ByteArray(0)
                else -> p.readBytes(0, fileSize)
            }
        } ?: return emptyList()

        val result = mutableListOf<StringInfo>()
        var i = 0
        while (i < data.size) {
            if (data[i] in 0x20..0x7E) {
                val start = i
                while (i < data.size && data[i] in 0x20..0x7E) i++
                val len = i - start
                if (len >= options.minLen && len <= options.maxLen) {
                    val s = String(data, start, len)
                    if (s.any { !it.isISOControl() }) {
                        result.add(
                            StringInfo(
                                string = s,
                                address = start.toLong(),
                                paddr = start.toLong(),
                                size = len,
                                section = ".rodata"
                            )
                        )
                    }
                }
            } else i++
        }
        return result.take(2048)
    }

    // ------------------------------------------------------------------
    // 函数 / 分析（自研实现中只按符号推断 FUNC 类型，没有真正的 aaa 识别能力）
    // ------------------------------------------------------------------

    override suspend fun listFunctions(handle: AnalysisHandle): List<FunctionInfo> =
        getSymbols(handle, true)
            .filter { it.type == SymbolType.FUNC && it.address != 0L && it.size > 0 }
            .map { sym ->
                FunctionInfo(
                    name = sym.name,
                    offset = sym.address,
                    vaddr = sym.address,
                    size = sym.size,
                    signature = sym.demangledName ?: sym.name,
                    callConvention = "aapcs"
                )
            }

    override suspend fun findFunctionContaining(handle: AnalysisHandle, address: Long): FunctionInfo? =
        listFunctions(handle).firstOrNull { address in it.vaddr..(it.vaddr + it.size) }

    override suspend fun findFunctionsByName(handle: AnalysisHandle, query: String): List<FunctionInfo> {
        if (query.isBlank()) return emptyList()
        val q = query.lowercase()
        return listFunctions(handle).filter {
            it.name.lowercase().contains(q) ||
                    (it.signature.isNotBlank() && it.signature.lowercase().contains(q))
        }
    }

    override suspend fun getFunctionCfg(handle: AnalysisHandle, functionOffset: Long): List<BasicBlock> =
        emptyList()

    // ------------------------------------------------------------------
    // 反汇编 / 汇编
    // ------------------------------------------------------------------

    override suspend fun disassemble(handle: AnalysisHandle, offset: Long, size: Long): List<DisasmInstruction> {
        if (openSessions[handle.value] == null) return emptyList()
        val bytes = withParser(handle) { p, _ -> p.readBytes(offset, size) } ?: return emptyList()
        if (bytes.isEmpty()) return emptyList()
        return try {
            CapstoneBindings.disassembleWithCapstone(bytes, offset)
                ?.map { DisasmInstruction.fromJni(it) }
                ?: emptyList()
        } catch (_: Throwable) { emptyList() }
    }

    override suspend fun assemble(handle: AnalysisHandle, assembly: String, address: Long): ByteArray? =
        keystone.assemble(assembly, address)

    // ------------------------------------------------------------------
    // Xref（暂不支持）
    // ------------------------------------------------------------------
    override suspend fun xrefsTo(handle: AnalysisHandle, target: Long): List<Xref> = emptyList()
    override suspend fun xrefsFrom(handle: AnalysisHandle, from: Long): List<Xref> = emptyList()

    // ------------------------------------------------------------------
    // ByteEdit
    // ------------------------------------------------------------------

    override suspend fun readBytes(handle: AnalysisHandle, offset: Long, size: Long): ByteArray =
        withParser(handle) { p, _ -> p.readBytes(offset, size) } ?: ByteArray(0)

    override suspend fun writeBytes(handle: AnalysisHandle, offset: Long, data: ByteArray): Boolean =
        withParser(handle) { p, _ -> p.writeBytes(offset, data) } ?: false

    // ------------------------------------------------------------------
    // AddressTranslation（自研引擎不区分 vaddr/paddr，直接返回原值）
    // ------------------------------------------------------------------

    override suspend fun paddrToVaddr(handle: AnalysisHandle, paddr: Long): Long = paddr
    override suspend fun vaddrToPaddr(handle: AnalysisHandle, vaddr: Long): Long = vaddr

    // ------------------------------------------------------------------
    // Hash
    // ------------------------------------------------------------------

    override suspend fun md5(handle: AnalysisHandle): String? = null
    override suspend fun sha256(handle: AnalysisHandle): String? = null

    override suspend fun crc32(handle: AnalysisHandle, offset: Long?, size: Long?): Long? {
        val bytes = if (offset == null) {
            val path = filePaths[handle.value] ?: return null
            val f = withContext(Dispatchers.IO) { File(path) }
            withParser(handle) { p, _ -> p.readBytes(0L, f.length()) }
        } else {
            val s = (size ?: 1024L * 1024L).coerceAtLeast(1L)
            readBytes(handle, offset, s)
        } ?: return null
        val crc = CRC32()
        crc.update(bytes)
        return crc.value
    }

    companion object {
        const val TAG = "SelfAnalysisEngine"
    }
}
