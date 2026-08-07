package com.ai.fler.core.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpErrorsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `errorJson 携带 id、错误码与信息`() {
        val id = JsonPrimitive(7)
        val err = McpErrors.errorJson(id, McpErrors.TOOL_NOT_FOUND, "工具不存在: x")
        assertEquals(id, err["id"])
        val e = err["error"]?.jsonObject!!
        assertEquals(McpErrors.TOOL_NOT_FOUND, e["code"]?.jsonPrimitive?.contentOrNull?.toInt())
        assertEquals("工具不存在: x", e["message"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun `id 为 null 时写入 JsonNull`() {
        val err = McpErrors.errorJson(null, McpErrors.METHOD_NOT_FOUND, "x")
        assertTrue(err["id"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `jsonrpc 版本固定为 2-0`() {
        val err = McpErrors.errorJson(JsonPrimitive(1), McpErrors.PARSE_ERROR, "x")
        assertEquals("2.0", err["jsonrpc"]?.jsonPrimitive?.content)
    }
}