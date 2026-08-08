package com.ai.fler.core.mcp

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MCP JSON-RPC 协议分发回归测试（mock 工具注册表，覆盖路由与错误处理）。
 */
class McpProtocolTest {

    private fun request(method: String, params: JsonObject? = null, id: Int = 1): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            if (params != null) put("params", params)
        }

    private fun makeProtocol(tools: Map<String, McpToolHandlers.McpTool> = emptyMap()): McpProtocol {
        val handlers = mockk<McpToolHandlers>()
        every { handlers.tools } returns tools
        coEvery { handlers.listResources() } returns emptyList()
        coEvery { handlers.readResource(any()) } returns null
        return McpProtocol(handlers, mockk(relaxed = true), mockk(relaxed = true))
    }

    @Test
    fun `缺少 method 返回 INVALID_REQUEST`() = runBlocking {
        val protocol = makeProtocol()
        val resp = protocol.handle(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1) })
        val err = resp!!["error"]!!.jsonObject
        assertEquals(McpErrors.INVALID_REQUEST, err["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `未知方法返回 METHOD_NOT_FOUND`() = runBlocking {
        val resp = makeProtocol().handle(request("not/a/real/method"))
        val err = resp!!["error"]!!.jsonObject
        assertEquals(McpErrors.METHOD_NOT_FOUND, err["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `initialize 返回支持的协议版本与能力`() = runBlocking {
        val resp = makeProtocol().handle(request("initialize"))!!
        val result = resp["result"]!!.jsonObject
        assertTrue(result["protocolVersion"]!!.jsonPrimitive.contentOrNull in listOf("2025-03-26", "2024-11-05", "2025-06-18"))
        assertNotNull(result["capabilities"]!!.jsonObject["tools"])
    }

    @Test
    fun `ping 返回空 result`() = runBlocking {
        val resp = makeProtocol().handle(request("ping"))!!
        assertNotNull(resp["result"])
        assertNull(resp["error"])
    }

    @Test
    fun `notification 不返回响应`() = runBlocking {
        val resp = makeProtocol().handle(
            buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") }
        )
        assertNull(resp)
    }

    @Test
    fun `tools-list 返回已注册工具`() = runBlocking {
        val echo = McpToolHandlers.McpTool("echo", "回显", buildJsonObject {}) { JsonPrimitive("") }
        val resp = makeProtocol(mapOf("echo" to echo)).handle(request("tools/list"))!!
        val tools = resp["result"]!!.jsonObject["tools"]!!.jsonArray
        assertTrue(tools.any { it.jsonObject["name"]!!.jsonPrimitive.contentOrNull == "echo" })
    }

    @Test
    fun `tools-call 成功回传文本内容且 isError 为 false`() = runBlocking {
        val echo = McpToolHandlers.McpTool("echo", "回显", buildJsonObject {}) { JsonPrimitive("pong") }
        val resp = makeProtocol(mapOf("echo" to echo)).handle(
            request("tools/call", buildJsonObject {
                put("name", "echo")
                putJsonObject("arguments") { put("x", "1") }
            })
        )!!
        assertNull(resp["error"])
        val result = resp["result"]!!.jsonObject
        assertEquals(false, result["isError"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(result["content"]!!.jsonArray.size >= 1)
    }

    @Test
    fun `tools-call 未知工具返回 TOOL_NOT_FOUND`() = runBlocking {
        val resp = makeProtocol().handle(
            request("tools/call", buildJsonObject { put("name", "nope") })
        )!!
        val err = resp["error"]!!.jsonObject
        assertEquals(McpErrors.TOOL_NOT_FOUND, err["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `tools-call 缺少工具名返回 INVALID_PARAMS`() = runBlocking {
        val resp = makeProtocol().handle(request("tools/call", buildJsonObject {}))!!
        val err = resp["error"]!!.jsonObject
        assertEquals(McpErrors.INVALID_PARAMS, err["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `resources-read 缺 uri 返回 INVALID_PARAMS`() = runBlocking {
        val resp = makeProtocol().handle(request("resources/read", buildJsonObject {}))!!
        val err = resp["error"]!!.jsonObject
        assertEquals(McpErrors.INVALID_PARAMS, err["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `prompts-get 未知 prompt 返回 INVALID_PARAMS`() = runBlocking {
        val resp = makeProtocol().handle(
            request("prompts/get", buildJsonObject { put("name", "foo") })
        )!!
        val err = resp["error"]!!.jsonObject
        assertEquals(McpErrors.INVALID_PARAMS, err["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `prompts-get analyze-method 返回提示`() = runBlocking {
        val resp = makeProtocol().handle(
            request("prompts/get", buildJsonObject {
                put("name", "analyze_method")
                putJsonObject("arguments") {
                    put("analysisId", "1")
                    put("methodName", "m")
                }
            })
        )!!
        assertNull(resp["error"])
        assertNotNull(resp["result"]!!.jsonObject["prompt"])
    }
}