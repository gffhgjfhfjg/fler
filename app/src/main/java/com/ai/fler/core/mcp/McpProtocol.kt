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
    private val logger: McpLogger,
    private val resourceProvider: McpResourceProvider? = null,
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
            method == "resources/list" -> resourcesList(id)
            method == "resources/read" -> resourcesRead(id, params)
            method == "prompts/list" -> result(id, promptsList())
            method == "prompts/get" -> promptsGet(id, params)
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
        val start = System.currentTimeMillis()
        logger.info("工具调用: $name args=${arguments.toString().take(500)}")

        return try {
            val data = tool.handler(arguments)
            val elapsed = System.currentTimeMillis() - start
            logger.info("工具完成: $name 耗时=${elapsed}ms")
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
            logger.warn("工具调用失败: $name - ${e.message}")
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
            logger.error("工具调用异常: $name - ${e.message}")
            McpErrors.errorJson(id, McpErrors.SERVER_ERROR, "工具执行异常: ${e.message}")
        }
    }

    // ========== resources ==========

    private suspend fun resourcesList(id: JsonElement?): JsonObject {
        val resources = resourceProvider?.listResources().orEmpty()
        return result(id, buildJsonObject {
            putJsonArray("resources") {
                resources.forEach { r ->
                    addJsonObject {
                        put("uri", r.uri)
                        put("name", r.name)
                        put("mimeType", r.mimeType)
                    }
                }
            }
        })
    }

    private suspend fun resourcesRead(id: JsonElement?, params: JsonObject): JsonObject {
        val uri = params["uri"]?.jsonPrimitive?.contentOrNull
            ?: return McpErrors.errorJson(id, McpErrors.INVALID_PARAMS, "缺少 uri")
        val text = resourceProvider?.readResource(uri)
            ?: return McpErrors.errorJson(id, McpErrors.INVALID_PARAMS, "资源不存在: $uri")
        return result(id, buildJsonObject {
            putJsonArray("contents") {
                addJsonObject {
                    put("uri", uri)
                    put("mimeType", "text/plain")
                    put("text", text)
                }
            }
        })
    }

    // ========== prompts ==========

    private fun promptsList(): JsonObject = buildJsonObject {
        putJsonArray("prompts") {
            addJsonObject {
                put("name", "analyze_method")
                put("description", "引导分析指定 Dart 方法（反汇编 + 调用关系 + PP 引用）")
                putJsonArray("arguments") {
                    addJsonObject {
                        put("name", "analysisId")
                        put("description", "分析记录 ID")
                        put("required", true)
                    }
                    addJsonObject {
                        put("name", "methodName")
                        put("description", "方法名")
                        put("required", true)
                    }
                }
            }
        }
    }

    private fun promptsGet(id: JsonElement?, params: JsonObject): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
        if (name != "analyze_method") {
            return McpErrors.errorJson(id, McpErrors.INVALID_PARAMS, "提示不存在: $name")
        }
        val arguments = params["arguments"]?.jsonObject
        val analysisId = arguments?.get("analysisId")?.jsonPrimitive?.contentOrNull ?: ""
        val methodName = arguments?.get("methodName")?.jsonPrimitive?.contentOrNull ?: ""
        val promptText = buildString {
            append("请分析以下 Dart 方法（App 内分析 ID: $analysisId，方法名: $methodName）。\n")
            append("步骤：\n")
            append("1) 用 get_method 获取反汇编 src_code；\n")
            append("2) 用 get_method_callers 找调用者；\n")
            append("3) 用 get_pp_references / get_pp_entry 查相关 PP 条目；\n")
            append("4) 结合 search_strings 定位关键字符串；\n")
            append("5) 如需修改，用 assemble_instruction 预览机器码、patch_instruction 写补丁。\n")
            append("目标：解释方法逻辑、指出可改写的指令，并说明风险。")
        }
        return result(id, buildJsonObject {
            putJsonObject("prompt") {
                put("name", "analyze_method")
                put("description", "引导分析指定 Dart 方法")
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        putJsonObject("content") {
                            put("type", "text")
                            put("text", promptText)
                        }
                    }
                }
            }
        })
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
