---
kind: error_handling
name: Fler Android 项目错误处理体系
category: error_handling
scope:
    - '**'
source_files:
    - app/src/main/java/com/ai/fler/core/mcp/McpErrors.kt
    - app/src/main/java/com/ai/fler/core/mcp/McpHttpServer.kt
    - app/src/main/java/com/ai/fler/ui/components/ErrorState.kt
    - app/src/main/java/com/ai/fler/core/analysis/engine/RizinJsonParser.kt
    - app/src/main/java/com/ai/fler/core/analysis/engine/PlaceholderEngines.kt
---

## 错误处理体系概述

Fler 项目采用分层错误处理策略，根据错误来源和严重性选择不同的处理方式：

### 1. JSON-RPC 协议层错误（McpErrors）
位于 `core/mcp/McpErrors.kt`，定义了标准的 JSON-RPC 2.0 错误码：
- 标准错误码：PARSE_ERROR(-32700)、INVALID_REQUEST(-32600)、METHOD_NOT_FOUND(-32601)、INVALID_PARAMS(-32602)
- 服务器自定义错误码：SERVER_ERROR(-32000)、TOOL_NOT_FOUND(-32003)
- 提供统一的 `errorJson()` 方法构造错误响应

### 2. HTTP 服务器层错误处理
`McpHttpServer.kt` 实现了完整的异常捕获机制：
- 连接级异常：客户端断开、网络异常等通过 try-catch 静默处理
- 请求解析失败：返回对应的 JSON-RPC 错误码
- 未授权访问：返回 401 状态码
- 资源不存在：返回 404 状态码

### 3. 数据解析层容错
`RizinJsonParser.kt` 采用防御性编程模式：
- 所有 JSON 解析操作都包裹在 try-catch 中
- 解析失败时返回空集合或 null，而非抛出异常
- 使用 `ignoreUnknownKeys = true` 容忍字段变化

### 4. UI 层错误展示
`ui/components/ErrorState.kt` 提供统一的错误界面组件：
- 支持错误图标、消息显示和重试按钮
- 与 Material Design 主题集成
- 可配置的重试回调机制

### 5. 占位实现错误
`PlaceholderEngines.kt` 使用 `NotImplementedError` 标记未实现的引擎功能：
- Unicorn 引擎：`throw NotImplementedError("Unicorn 尚未集成")`
- Unidbg 引擎：`throw NotImplementedError("Unidbg 尚未集成")`

### 6. 协程和异步操作错误
广泛使用 `runCatching` 处理可能失败的异步操作：
- 文件操作：`runCatching { File(path).length() }.getOrNull()`
- JSON 反序列化：`runCatching { json.decodeFromString(...) }.getOrNull()`
- 网络请求：`runCatching { socket.inetAddress?.hostAddress ?: "?" }.getOrDefault("?")`

### 7. 资源清理错误处理
在资源关闭时使用 try-catch 确保清理操作不会中断主流程：
- 分析会话关闭：`try { registry.getAnalysis(entry.engineId)?.close(...) } catch (_: Throwable) { /* noop */ }`
- Socket 关闭：`try { serverSocket?.close() } catch (_: Exception) {}`

## 设计原则

1. **分层处理**：不同层级采用适合的处理方式，协议层返回结构化错误，UI 层提供友好展示
2. **防御性编程**：外部输入和不可靠操作都进行异常捕获
3. **渐进式降级**：解析失败返回空结果而非崩溃
4. **用户友好**：UI 层提供清晰的错误信息和重试选项
5. **资源安全**：确保资源正确释放，即使发生异常