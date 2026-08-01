package com.ai.fler.core.mcp

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * JSON-RPC 2.0 错误码与响应构造。
 */
object McpErrors {
    // JSON-RPC 2.0 标准错误码
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
    // 服务器自定义错误段（-32000..-32099）
    const val SERVER_ERROR = -32000
    const val UNAUTHORIZED = -32001
    const val TOOL_NOT_ENABLED = -32002
    const val TOOL_NOT_FOUND = -32003
    const val INVALID_TOOL_PARAMS = -32004

    const val NOT_INITIALIZED = -32005

    fun errorJson(id: kotlinx.serialization.json.JsonElement?, code: Int, message: String) =
        buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id) else put("id", kotlinx.serialization.json.JsonNull)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }
}
