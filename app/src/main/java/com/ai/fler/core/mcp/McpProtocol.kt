package com.ai.fler.core.mcp

import kotlinx.coroutines.withContext
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
    private val sessions: McpSessions,
    private val stats: McpCallStats,
    private val resourceProvider: McpResourceProvider? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 处理一条 JSON-RPC 消息。
     * @param sessionId 当前 MCP 会话 ID（Streamable `Mcp-Session-Id` 或 legacy 会话），用于推送进度通知；内联请求可省略。
     * @return 需要回发的响应；通知类（无 id）返回 null。
     */
    suspend fun handle(request: JsonObject, sessionId: String? = null): JsonObject? {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: return McpErrors.errorJson(id, McpErrors.INVALID_REQUEST, "缺少 method")

        val params = request["params"]?.jsonObject ?: JsonObject(emptyMap())

        return when {
            method.startsWith("notifications/") -> { handleNotification(method); null }
            method == "initialize" -> result(id, initializeResult(params))
            method == "ping" -> result(id, buildJsonObject {})
            method == "tools/list" -> result(id, toolsList())
            method == "tools/call" -> toolsCall(id, params, sessionId)
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
                // 声明支持服务端→客户端 notifications（Progress）。
                putJsonObject("notifications") { }
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
                if (!handlers.isToolExposed(t.name)) return@forEach
                addJsonObject {
                    put("name", t.name)
                    put("description", t.description)
                    put("inputSchema", t.inputSchema)
                }
            }
        }
    }

private suspend fun toolsCall(id: JsonElement?, params: JsonObject, sessionId: String?): JsonObject {
        val name = params["name"]?.jsonPrimitive?.contentOrNull
            ?: return McpErrors.errorJson(id, McpErrors.INVALID_PARAMS, "缺少工具名")
        val tool = handlers.tools[name]
            ?: return McpErrors.errorJson(id, McpErrors.TOOL_NOT_FOUND, "工具不存在: $name")
        if (!handlers.isToolExposed(name)) {
            return McpErrors.errorJson(id, McpErrors.TOOL_NOT_FOUND, "工具已隐藏: $name")
        }
        val arguments = params["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val start = System.currentTimeMillis()

        // 解析客户端请求进度：_meta.progressToken（MCP 规范：位于 params 顶层，与 arguments 平级；
        // 部分实现也放 arguments 内，二者都兼容读）。
        // progressToken 可为数字或字符串，保留原始 Primitive 用于回传。
        val progressToken: JsonPrimitive? =
            params["_meta"]?.jsonObject?.get("progressToken")?.jsonPrimitive
                ?: arguments["_meta"]?.jsonObject?.get("progressToken")?.jsonPrimitive
        val hasProgress = progressToken != null && sessionId != null
        val progressSink: ProgressSink = if (hasProgress) {
            object : ProgressSink {
                override fun report(progress: Float?, message: String?) {
                    sendProgressNotification(sessionId!!, progressToken!!, progress, message)
                }
            }
        } else {
            NoopProgressSink
        }

        logger.info("工具调用: $name args=${arguments.toString().take(500)}" + if (hasProgress) " [progress]" else "")

        return try {
            // 把请求上下文（sessionId + progressToken + sink）放进协程 context，
            // 长任务工具可经 McpRequestContext.current() 上报进度。
            val data = withContext(McpRequestContext(sessionId, progressToken, progressSink)) {
                tool.handler(arguments)
            }
            val elapsed = System.currentTimeMillis() - start
            logger.logToolResult(name, "完成", elapsed, isError = false, paramsJson = arguments.toString().take(120))
            stats.record(name, isError = false, elapsed)
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
            val elapsed = System.currentTimeMillis() - start
            logger.logToolResult(name, "失败: ${e.message}", elapsed, isError = true, paramsJson = arguments.toString().take(120))
            stats.record(name, isError = true, elapsed)
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
            val elapsed = System.currentTimeMillis() - start
            logger.error("工具调用异常: $name - ${e.message}")
            stats.record(name, isError = true, elapsed)
            McpErrors.errorJson(id, McpErrors.SERVER_ERROR, "工具执行异常: ${e.message}")
        }
    }

    /** 向指定会话推送 `notifications/progress`。 */
    private fun sendProgressNotification(sessionId: String, token: JsonPrimitive, progress: Float?, message: String?) {
        val notification = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/progress")
            putJsonObject("params") {
                put("progressToken", token)
                progress?.let { put("progress", it) }
                message?.let { put("message", it) }
            }
        }
        val sent = sessions.writeNotification(sessionId, notification.toString())
        val pct = progress?.let { ((it * 100).toInt()) } ?: -1
        logger.debug("进度: ${message ?: "n/a"} (${if (pct >= 0) "$pct%" else "阶段"})" + if (sent) "" else " [会话已断开，丢弃]")
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
