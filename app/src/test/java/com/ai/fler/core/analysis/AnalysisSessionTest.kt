package com.ai.fler.core.analysis

import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.service.BackupManager
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AnalysisSession 会话层回归测试。
 *
 * 覆盖本次优化（拆锁后 volatile 字段）的语义：open 后 currentHandle/currentFilePath
 * 正确反映当前会话，closeAll 后清零，重复 open 复用同一会话句柄。
 */
class AnalysisSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakeEngine(
        override val engineId: String,
    ) : BinaryAnalysisEngine {
        private val handles = mutableListOf<Long>()
        private var next = 1L

        override val capabilities: Set<AnalysisCapability> = setOf(
            AnalysisCapability.ELF_PARSING,
            AnalysisCapability.DISASSEMBLY,
            AnalysisCapability.BINARY_HASH,
        )
        override val displayName: String get() = "fake-$engineId"
        override val isAvailable: Boolean get() = true

        override suspend fun open(filePath: String, options: OpenOptions): OpenResult {
            val h = next++
            handles.add(h)
            return OpenResult.Success(AnalysisHandle(h), filePath, engineId)
        }

        override suspend fun close(handle: AnalysisHandle) {
            handles.remove(handle.value)
        }

        override suspend fun isHandleValid(handle: AnalysisHandle): Boolean = handle.value in handles

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

    private fun newSession(): Triple<AnalysisSession, EngineRegistry, BackupManager> {
        val registry = EngineRegistry()
        val backup = mockk<BackupManager>(relaxed = true)
        val cache = mockk<SoEditorCache>(relaxed = true)
        val logger = mockk<AppLogger>(relaxed = true)
        val session = AnalysisSession(registry, backup, cache, logger)
        return Triple(session, registry, backup)
    }

    private fun sampleFilePath(): String {
        val f = tmp.newFile("sample.so")
        f.writeBytes(ByteArray(4096))
        return f.absolutePath
    }

    @Test
    fun `open 成功后 currentHandle 有效且 currentFilePath 正确`() = runBlocking {
        val (session, registry, _) = newSession()
        registry.registerAnalysis(FakeEngine("rizin"), priority = 100)
        val path = sampleFilePath()
        assertEquals(AnalysisHandle.INVALID, session.currentHandle())

        val result = session.open(path)
        assertTrue(result is OpenResult.Success)
        assertEquals(path, session.currentFilePath())
        val success = result as OpenResult.Success
        assertEquals(success.handle, session.currentHandle())
        assertNotNull(session.currentEngine())
    }

    @Test
    fun `相同路径再次 open 复用同一会话句柄`() = runBlocking {
        val (session, registry, _) = newSession()
        val engine = FakeEngine("riz")
        registry.registerAnalysis(engine, priority = 100)
        val path = sampleFilePath()

        val first = session.open(path)
        assertTrue(first is OpenResult.Success)
        val firstHandle = (first as OpenResult.Success).handle.value

        val second = session.open(path)
        assertTrue(second is OpenResult.Success)
        assertEquals(firstHandle, (second as OpenResult.Success).handle.value)
    }

    @Test
    fun `不存在的文件 open 返回失败且 current 不变`() = runBlocking {
        val (session, registry, _) = newSession()
        registry.registerAnalysis(FakeEngine("riz"), priority = 100)
        val result = session.open(tmp.root.absolutePath + "/not_exists.so")
        assertTrue(result is OpenResult.Failure)
    }

    @Test
    fun `closeAll 后 current 清零为 INVALID`() = runBlocking {
        val (session, registry, _) = newSession()
        registry.registerAnalysis(FakeEngine("riz"), priority = 100)
        val result = session.open(sampleFilePath())
        assertTrue(result is OpenResult.Success)
        assertTrue(session.currentHandle().isValid)
        assertNotNull(session.currentFilePath())

        session.closeAll()
        assertEquals(AnalysisHandle.INVALID, session.currentHandle())
        assertNull(session.currentFilePath())
    }

    @Test
    fun `无引擎时 open 返回失败`() = runBlocking {
        val (session, _, _) = newSession()
        val result = session.open(sampleFilePath())
        assertTrue(result is OpenResult.Failure)
        assertFalse(session.currentHandle().isValid)
    }
}