package com.ai.fler.core.mcp

import android.content.Context
import com.ai.fler.core.analysis.AnalysisReportGenerator
import com.ai.fler.core.analysis.DartCallGraphBuilder
import com.ai.fler.core.analysis.FunctionIndex
import com.ai.fler.core.analysis.IndirectCallScanner
import com.ai.fler.core.analysis.MethodStringLabels
import com.ai.fler.core.analysis.StringXrefScanner
import com.ai.fler.core.gadget.GadgetRepacker
import com.ai.fler.core.service.AddressTranslator
import com.ai.fler.core.service.ApkRepacker
import com.ai.fler.core.service.WorkDirectory
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.AsmBlockDao
import com.ai.fler.data.dao.DartCallGraphDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.DartObjectDao
import com.ai.fler.data.dao.EnumMapDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import com.ai.fler.features.mcp.McpPatchService
import io.mockk.mockk
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具 inputSchema 契约回归测试。
 *
 * MCP 客户端（官方 SDK ToolSchema，zod 校验）对 tools/list 响应有硬性要求：
 * - inputSchema.type 必须是字面量 "object"（缺失即整个 tools/list 校验失败，
 *   客户端将连接标记为 failed 并断开——曾导致 opencode 连不上）；
 * - properties 若存在，其值必须是对象；
 * - required 若存在，必须是字符串数组。
 *
 * 曾因 repack_apk / gadget_repack_apk 缺 "type":"object" 导致全部客户端连不上，
 * 此测试防止回归。
 */
class McpToolSchemaContractTest {

    private fun realHandlers(): McpToolHandlers = McpToolHandlers(
        analysisDao = mockk(relaxed = true),
        libraryDao = mockk(relaxed = true),
        dartClassDao = mockk(relaxed = true),
        dartMethodDao = mockk(relaxed = true),
        ppEntryDao = mockk(relaxed = true),
        asmBlockDao = mockk(relaxed = true),
        dartObjectDao = mockk(relaxed = true),
        enumMapDao = mockk(relaxed = true),
        projectDao = mockk(relaxed = true),
        addressTranslator = mockk(relaxed = true),
        config = mockk(relaxed = true),
        patchService = mockk(relaxed = true),
        engineMcp = mockk(relaxed = true),
        emulationMcp = mockk(relaxed = true),
        axisResolver = mockk(relaxed = true),
        dartCallGraphDao = mockk(relaxed = true),
        callGraphBuilder = mockk(relaxed = true),
        functionIndex = mockk(relaxed = true),
        stringXrefScanner = mockk(relaxed = true),
        methodStringLabels = mockk(relaxed = true),
        indirectCallScanner = mockk(relaxed = true),
        fridaTools = mockk(relaxed = true),
        semanticIndexManager = mockk(relaxed = true),
        reportGenerator = mockk(relaxed = true),
        workDirectory = mockk(relaxed = true),
        apkRepacker = mockk(relaxed = true),
        gadgetRepacker = mockk(relaxed = true),
        context = mockk(relaxed = true),
    )

    @Test
    fun `所有工具 inputSchema 符合 MCP ToolSchema 契约`() {
        val handlers = realHandlers()
        val tools = handlers.tools.values
        assertTrue("工具表不应为空", tools.isNotEmpty())

        val violations = mutableListOf<String>()
        for (t in tools) {
            val name = t.name
            if (t.description.isBlank()) violations += "$name: description 为空"

            val schema: JsonObject = t.inputSchema
            // 关键契约：type 必须是字面量 "object"
            val type = schema["type"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }
            if (type != "object") violations += "$name: inputSchema.type 必须为 \"object\"（当前: $type）"

            // properties 若存在：值必须是对象
            schema["properties"]?.let { props ->
                val propsObj = runCatching { props.jsonObject }.getOrElse {
                    violations += "$name: properties 不是对象"; return@let
                }
                propsObj.forEach { (k, v) ->
                    if (v !is JsonObject) violations += "$name: properties.$k 不是对象"
                }
            }

            // required 若存在：必须是字符串数组
            schema["required"]?.let { req ->
                val arr = runCatching { req.jsonArray }.getOrElse {
                    violations += "$name: required 不是数组"; return@let
                }
                arr.forEach { item ->
                    if (item !is JsonPrimitive || item.contentOrNull == null) {
                        violations += "$name: required 含非字符串项"
                    }
                }
            }
        }
        assertTrue("MCP ToolSchema 契约违反:\n${violations.joinToString("\n")}", violations.isEmpty())
    }

    @Test
    fun `曾回归的 repack_apk 与 gadget_repack_apk 均含 type object`() {
        val handlers = realHandlers()
        for (name in listOf("repack_apk", "gadget_repack_apk")) {
            val tool = handlers.tools[name]
            assertTrue("工具应存在: $name", tool != null)
            assertEquals(
                "$name inputSchema.type 必须为 object",
                "object",
                tool!!.inputSchema["type"]!!.jsonPrimitive.content,
            )
        }
    }
}
