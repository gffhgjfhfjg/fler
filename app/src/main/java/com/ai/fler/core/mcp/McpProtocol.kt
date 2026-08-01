package com.ai.fler.core.mcp

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.addJsonObject

/**
 * MCP JSON-RPC 协议分发。
 *
 * 处理 initialize / notifications / ping / tools.* / resources.* / prompts.*，
 * 工具处理器在调用方（HTTP 线程）以 IO 调度执行。
 */
class McpProtocol(
    private val handlers: McpToolHandlers,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 处理一条 JSON-RPC 消息。
     * @return 需要回发的响应；通知类（无 id）返回 null。
     */
    suspend fun handle(request: JsonObject): JsonObject? {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: return McpErrors.errorJson(id, McpErrors.INVALID_REQUEST, "缺少 method")

        val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())

        return when {
            method.startsWith("notifications/") -> { handleNotification(method); null }
            method == "initialize" -> result(id, initializeResult(params))
            method == "ping" -> result(id, buildJsonObject {})
            method == "tools/list" -> result(id, toolsList())
            method == "tools/call" -> toolsCall(id, params)
            method == "resources/list" -> result(id, buildJsonObject { putJsonArray("resources") {} })
            method == "resources/read" ->
                McpErrors.errorJson(id, McpErrors.INVALID_PARAMS, "resources/read 未提供（请使用工具）")
            method == "prompts/list" -> result(id, buildJsonObject { putJsonArray("prompts") {} })
            method == "prompts/get" ->
                McpErrors.errorJson(id, McpErrors.INVALID_PARAMS, "prompts/get 未提供")
            else -> McpErrors.errorJson(id, McpErrors.METHOD_NOT_FOUND, "方法未找到: $method")
        }
    }

    private fun handleNotification(method: String) {
        // initialized / cancelled / roots.list_changed 等：无需回发
    }

    // ========== initialize ==========

    private fun initializeResult(params: JsonObject): JsonObject {
        val requested = params["protocolVersion"]?.jsonPrimitive?.contentOrNull
        val protocolVersion = if (requested != null && requested in SUPPORTED_VERSIONS) requested else SUPPORTED_VERSIONS.first()
        return buildJsonObject {
            put("protocolVersion", protocolVersion)
            putJsonObject("capabilities") {
                putJsonObject("tools") { put("listChanged", false) }
                putJsonObject("resources") { put("subscribe", false) }
                putJsonObject("prompts") { put("listChanged", false) }
            }
            putJsonObject("serverInfo") {
                put("name", "fler-mcp")
                put("version", "1.3.0")
            }
        }
    }

    // ========== tools ==========

    private fun toolsList(): JsonObject = buildJsonObject {
        putJsonArray("tools") {
            handlers.tools.values.forEach { t ->
                addJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("inputSchema", t.inputSchema)
                }
            }
        }
    }

    private suspend fun toolsCall(id: JsonElement?, params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return McpErrors.errorJson(id, McpErrors.INVALID_PARAMS, "缺少工具名")
        val tool = handlers.tools[name]
            ?: return McpErrors.errorJson(id, McpErrors.TOOL_NOT_FOUND, "工具不存在: $name")
        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())

        return try {
            val data = tool.handler(arguments)
            // 把工具返回的 JSON 作为 text content 回传给客户端
            val text = json.encodeToString(data)
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonPrimitive(0))
                putJsonObject("result") {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", text)
                        }
                    }
                    put("isError", false)
                }
            }
        } catch (e: McpToolException) {
            buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonPrimitive(0))
                putJsonObject("result") {
                    putJsonArray("content") {
                        addJsonObject {
                            put("type", "text")
                            put("text", e.message ?: "工具调用失败")
                        }
                    }
                    put("isError", true)
                }
            }
        } catch (e: Exception) {
            McpErrors.errorJson(id, McpErrors.SERVER_ERROR, "工具执行异常: ${e.message}")
        }
    }

    // ========== 辅助 ==========

    private fun result(id: JsonElement?, result: JsonObject): JsonObject = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id ?: JsonPrimitive(0))
        put("result", result)
    }

    companion object {
        val SUPPORTED_VERSIONS = listOf("2025-03-26", "2024-11-05", "2025-06-18")
    }
}
