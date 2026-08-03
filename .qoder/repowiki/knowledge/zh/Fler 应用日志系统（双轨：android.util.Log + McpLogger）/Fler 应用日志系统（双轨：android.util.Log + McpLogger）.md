---
kind: logging_system
name: Fler 应用日志系统（双轨：android.util.Log + McpLogger）
category: logging_system
scope:
    - '**'
source_files:
    - app/src/main/java/com/ai/fler/core/mcp/McpLogger.kt
    - app/src/main/java/com/ai/fler/features/mcp/McpLogScreen.kt
    - app/src/main/java/com/ai/fler/features/mcp/McpLogViewModel.kt
    - app/src/main/java/com/ai/fler/core/mcp/McpHttpServer.kt
    - app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt
    - app/src/main/java/com/ai/fler/core/jni/BlutterEngine.kt
    - app/src/main/java/com/ai/fler/core/jni/NativeLoader.kt
---

## 1. 使用的系统与框架
- **通用业务日志**：直接使用 Android 平台 `android.util.Log`，以每个类定义 `TAG` 常量并通过 `Log.i/w/e/v` 输出。
- **MCP 服务器专用日志**：自研 `McpLogger`（`com.ai.fler.core.mcp.McpLogger`），基于 `StateFlow` 维护有界内存日志队列，提供 `info/debug/warn/error/logRequest` 等 API，并通过 Compose UI 在设置页实时展示。
- **无第三方日志库**：工程未引入 Timber、SLF4J、Logback、log4j 等外部日志框架，依赖仅包含 Hilt、Room、OkHttp、Compose、kotlinx-serialization 等。

## 2. 核心文件与位置
- `app/src/main/java/com/ai/fler/core/mcp/McpLogger.kt` — MCP 日志记录器单例，定义 `McpLogEntry` 数据结构，维护最多 500 条的有界列表。
- `app/src/main/java/com/ai/fler/features/mcp/McpLogScreen.kt` — Compose 日志查看页面，支持按级别过滤（ALL/I/W/E）、自动滚底、清空。
- `app/src/main/java/com/ai/fler/features/mcp/McpLogViewModel.kt` — 暴露 `entries` StateFlow 并提供过滤与清空操作。
- `app/src/main/java/com/ai/fler/core/mcp/McpHttpServer.kt` — MCP HTTP/SSE 服务器，通过注入的 `logger` 实例记录请求、会话建立/断开、JSON-RPC 错误等。
- 各业务类（如 `RizinEngine.kt`、`BlutterEngine.kt`、`NativeLoader.kt`、`AddressTranslator.kt`、`AnalysisImporter.kt`、`ApkExtractor.kt`、`BackupManager.kt`、`DartVersionDetector.kt`、`DualSourceDownloader.kt`、`EngineExtractor.kt`、`EngineLoader.kt`、`EnginePackManager.kt`、`ProjectDetailViewModel.kt`、`ProjectViewModel.kt`、`SoEditorViewModel.kt` 等）均使用 `android.util.Log` 配合类内 `TAG` 常量输出。

## 3. 架构与约定
- **双轨并行**：
  - 传统模块统一走 `android.util.Log`，每个类自行声明 `private const val TAG = "ClassName"`，调用 `Log.i/w/e(TAG, ...)`。
  - MCP 子系统通过 Hilt 注入 `McpLogger`，所有网络请求、SSE 会话、JSON-RPC 处理都经其记录，结构化字段包括 `seq`、`timestamp`、`level`、`message`、`method`、`paramsJson`、`remote`。
- **有界内存缓冲**：`McpLogger` 内部使用 `MutableStateFlow<List<McpLogEntry>>`，每次追加后 `takeLast(MAX_ENTRIES=500)` 丢弃最旧条目，避免内存增长。
- **UI 驱动消费**：`McpLogScreen` 通过 `collectAsStateWithLifecycle()` 订阅 `entries`，新日志自动滚动到底部；支持按级别筛选和一键清空。
- **结构化请求日志**：`logRequest(method, paramsJson, remote, level)` 专门记录 JSON-RPC 调用的方法名与参数 JSON，便于在 UI 中二次展开查看。

## 4. 约定与约束
- **TAG 命名约定**：每个使用 `android.util.Log` 的类都声明一个 `private const val TAG`，值为类名（如 `RizinEngine`、`BlutterEngine`、`NativeLoader`、`FlerEngine` 等），并在所有 `Log.*` 调用中作为第一个参数传入。
- **日志级别使用**：`android.util.Log` 主要使用 `i`（信息）、`w`（警告）、`e`（错误）三级；`McpLogger` 对应 `I/D/W/E` 四级（含 debug）。
- **MCP 日志可见性**：MCP 日志仅保存在内存中，不持久化到文件或数据库，进程重启即丢失；UI 提供“清空”按钮主动清理。
- **无全局初始化**：未发现类似 `Timber.plant(...)` 的全局日志初始化代码，`McpLogger` 通过 Hilt 按需注入，`android.util.Log` 直接调用无需配置。
- **构建配置无日志依赖**：`app/build.gradle.kts` 未声明任何日志库依赖，确认项目未集成第三方日志框架。

## 5. 缺失与改进空间
- 缺少统一的日志门面或抽象层，`android.util.Log` 与 `McpLogger` 两套 API 并存，新增模块需自行选择。
- 没有日志级别开关或采样机制，无法在 release 版本中动态关闭调试日志。
- 未实现日志落盘（文件/远程收集），不利于线上问题回溯。
- 未对敏感信息进行脱敏处理（如 `paramsJson` 可能包含用户数据）。