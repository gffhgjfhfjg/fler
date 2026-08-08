package com.ai.fler.core.mcp

import kotlin.coroutines.coroutineContext

/**
 * MCP 请求上下文（同时作为 CoroutineContext 元素，经 withContext 传播）。
 *
 * 服务于两件事：
 * 1. 长任务工具（engine_analyze / emu_run / build_call_graph / export_patched_so）
 *    在执行过程中向 `notifications/progress` 上报进度；
 * 2. 让工具知道本次请求所属的 MCP 会话（Streamable `Mcp-Session-Id`）。
 *
 * 工具端同步取值：
 * ```
 * McpRequestContext.current?.progress?.report(0.5f, "phase 2/3")
 * ```
 */
class McpRequestContext(
    val sessionId: String?,
    val progressToken: Any?,
    val progress: ProgressSink?,
) : kotlin.coroutines.AbstractCoroutineContextElement(Key) {

    override val key: kotlin.coroutines.CoroutineContext.Key<*>
        get() = Key

    companion object Key : kotlin.coroutines.CoroutineContext.Key<McpRequestContext> {

        /** 当前请求上下文；工具调用内有效，其他场景为 null。 */
        suspend fun current(): McpRequestContext? = coroutineContext[Key]
    }
}

/**
 * 进度上报通道：工具把完成度/阶段文案交给协议层，
 * 由协议层负责按 MCP 规范向客户端推送 `notifications/progress`。
 */
interface ProgressSink {
    fun report(progress: Float?, message: String?)
}

/** 空的进度通道（未携带 progressToken 时使用，工具调用无副作用）。 */
object NoopProgressSink : ProgressSink {
    override fun report(progress: Float?, message: String?) = Unit
}