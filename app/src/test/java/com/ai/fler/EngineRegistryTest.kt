package com.ai.fler

import com.ai.fler.core.analysis.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlinx.coroutines.runBlocking

/**
 * 引擎注册中心单元测试：注册 / 优先级挑选 / 能力过滤 / 反注册。
 */
class EngineRegistryTest {

    private class FakeEngine(
        override val engineId: String,
        override val displayName: String = engineId,
        override val isAvailable: Boolean = true,
        override val capabilities: Set<AnalysisCapability> = emptySet(),
    ) : BinaryAnalysisEngine {
        override suspend fun open(filePath: String, options: OpenOptions): OpenResult =
            OpenResult.Failure("fake engine")
        override suspend fun close(handle: AnalysisHandle) {}
        override suspend fun isHandleValid(handle: AnalysisHandle): Boolean = true
        override suspend fun getFileInfo(handle: AnalysisHandle): FileInfo? = null
        override suspend fun getSections(handle: AnalysisHandle) = emptyList<SectionInfo>()
        override suspend fun getSymbols(handle: AnalysisHandle, includeDynamic: Boolean) = emptyList<SymbolInfo>()
        override suspend fun getImports(handle: AnalysisHandle) = emptyList<ImportInfo>()
        override suspend fun getRelocs(handle: AnalysisHandle) = emptyList<RelocInfo>()
        override suspend fun scanStrings(handle: AnalysisHandle, options: StringScanOptions) = emptyList<StringInfo>()
        override suspend fun listFunctions(handle: AnalysisHandle) = emptyList<FunctionInfo>()
        override suspend fun findFunctionContaining(handle: AnalysisHandle, address: Long): FunctionInfo? = null
        override suspend fun findFunctionsByName(handle: AnalysisHandle, query: String) = emptyList<FunctionInfo>()
        override suspend fun getFunctionCfg(handle: AnalysisHandle, functionOffset: Long) = emptyList<BasicBlock>()
        override suspend fun disassemble(handle: AnalysisHandle, offset: Long, size: Long) = emptyList<DisasmInstruction>()
        override suspend fun assemble(handle: AnalysisHandle, assembly: String, address: Long): ByteArray? = null
        override suspend fun xrefsTo(handle: AnalysisHandle, target: Long) = emptyList<Xref>()
        override suspend fun xrefsFrom(handle: AnalysisHandle, from: Long) = emptyList<Xref>()
        override suspend fun readBytes(handle: AnalysisHandle, offset: Long, size: Long) = ByteArray(0)
        override suspend fun writeBytes(handle: AnalysisHandle, offset: Long, data: ByteArray) = false
        override suspend fun paddrToVaddr(handle: AnalysisHandle, paddr: Long) = paddr
        override suspend fun vaddrToPaddr(handle: AnalysisHandle, vaddr: Long) = vaddr
        override suspend fun md5(handle: AnalysisHandle): String? = null
        override suspend fun sha256(handle: AnalysisHandle): String? = null
        override suspend fun crc32(handle: AnalysisHandle, offset: Long?, size: Long?): Long? = null
    }

    @Test
    fun `注册后按优先级返回最高引擎`() = runBlocking {
        val registry = EngineRegistry()
        val low = FakeEngine("low", capabilities = setOf(AnalysisCapability.ELF_PARSING))
        val high = FakeEngine("high", capabilities = setOf(AnalysisCapability.ELF_PARSING))
        registry.registerAnalysis(low, priority = 10)
        registry.registerAnalysis(high, priority = 100)

        assertSame(high, registry.pickAnalysisFor(AnalysisCapability.ELF_PARSING))
        assertEquals(listOf("high", "low"), registry.listAnalysis().map { it.engineId })
    }

    @Test
    fun `能力过滤要求支持全部能力`() = runBlocking {
        val registry = EngineRegistry()
        val onlyElf = FakeEngine(
            "only_elf",
            capabilities = setOf(AnalysisCapability.ELF_PARSING),
        )
        val full = FakeEngine(
            "full",
            capabilities = setOf(AnalysisCapability.ELF_PARSING, AnalysisCapability.DISASSEMBLY),
        )
        registry.registerAnalysis(onlyElf, priority = 100)
        registry.registerAnalysis(full, priority = 10)

        // 需要 ELF_PARSING + DISASSEMBLY 时，高优先级但不满足的引擎被跳过
        assertSame(full, registry.pickAnalysisFor(
            AnalysisCapability.ELF_PARSING, AnalysisCapability.DISASSEMBLY
        ))
        // listAnalysisSupporting 返回全部满足的引擎（按优先级排序）
        assertEquals(listOf("full"), registry.listAnalysisSupporting(
            AnalysisCapability.ELF_PARSING, AnalysisCapability.DISASSEMBLY
        ).map { it.engineId })
    }

    @Test
    fun `不可用引擎不参与挑选`() = runBlocking {
        val registry = EngineRegistry()
        val down = FakeEngine("down", isAvailable = false, capabilities = setOf(AnalysisCapability.ELF_PARSING))
        registry.registerAnalysis(down, priority = 100)

        assertNull(registry.pickAnalysisFor(AnalysisCapability.ELF_PARSING))
        assertEquals(emptyList<String>(), registry.listAnalysisSupporting(AnalysisCapability.ELF_PARSING).map { it.engineId })
    }

    @Test
    fun `反注册后引擎不可再获取`() = runBlocking {
        val registry = EngineRegistry()
        val e = FakeEngine("tmp")
        registry.registerAnalysis(e, priority = 1)
        assertSame(e, registry.getAnalysis("tmp"))
        registry.unregisterAnalysis("tmp")
        assertNull(registry.getAnalysis("tmp"))
    }
}
