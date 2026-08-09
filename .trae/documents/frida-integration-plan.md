# Frida 动态分析集成计划（阶段1：纯 Kotlin MCP 工具）

> 范围：在 fler 中集成 Frida 客户端能力，用于同设备 hook 运行中的目标 App（如 Image Search），辅助定位混淆代码中的运行时门控点（如 `is_premium` 读取）。
> 不涉及 native 改动、不实现 frida-server 分发、不注册为 EmulationEngine。

---

## 一、Summary 摘要

新增一个**独立的 Frida MCP 工具集**，通过 OkHttp WebSocket/TCP 连接本机 `127.0.0.1:27042` 上由用户 root 启动的 frida-server，暴露 `frida_*` 系列 MCP 工具（连接探测、枚举进程、spawn/attach、加载 JS 脚本、调用 RPC 导出、detach）。

- **零 native 改动**：不动 CMakeLists.txt、不加 .so
- **零 EmulationEngine 注册**：Frida 的进程附加模型与 `EmulationEngine.open(filePath)` 语义不匹配，独立工具集更清晰（探索报告第 8 节明确建议）
- **复用现有 MCP 扩展点**：`McpToolHandlers` 已为外挂 Registry 设计了 `xxxMcp.buildTools().forEach` 模式，零冲突接入
- **frida-server 由用户自启**：fler 仅作客户端，不处理 root/SELinux/frida-server 启动（绕过 Android 沙箱限制）

集成后可解决上一会话的核心痛点：静态 `scan_pool_refs`/`string_xrefs` 无法反查高偏移（>0x7FF8）的 PP 字符串引用方法（如 `is_premium`@pp+0x6d530），改由 Frida 运行时 Hook SharedPreferences.getBoolean / 目标 getter 直接定位门控点。

---

## 二、Current State Analysis 现状分析

### 2.1 可复用的基础设施（探索确认）

| 设施 | 位置 | 复用方式 |
|---|---|---|
| MCP 工具扩展点 | [McpToolHandlers.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpToolHandlers.kt) 第 157–169 行 `tools` buildMap | 仿 `engineMcp.buildTools().forEach` 加一行 `fridaMcp.buildTools().forEach` |
| McpTool 标准签名 | McpToolHandlers.kt 第 68–73 行 | `suspend (JsonObject) -> JsonElement`，错误抛 `McpToolException` |
| 独立 Registry 范式 | [EmulationMcpToolRegistry.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/EmulationMcpToolRegistry.kt) | `@Singleton class XxxMcpToolRegistry @Inject constructor(...)` + `fun buildTools(): Map<String, McpTool>` |
| OkHttp 单例 | [CoreModule.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/di/CoreModule.kt) | 直接 `@Inject` 注入 `OkHttpClient` |
| 进度通知 | McpProtocol.toolsCall 第 109–121 行 `_meta.progressToken` | Frida 长任务（如全进程扫描）可上报进度 |
| Schema helpers | EmulationMcpToolRegistry 内 `objProps`/`strType`/`intType` 等 | 复制到 FridaMcpToolRegistry（现有 Registry 各自重复实现，保持一致） |
| 设置页模式 | [McpSettingsCard.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/settings/McpSettingsCard.kt) 第 112 行已有 `adb reverse` 提示文本 | Frida 连接状态卡片照抄此模式 |

### 2.2 关键技术约束

1. **frida-server 通信协议**：frida-server 默认监听 `127.0.0.1:27042`，协议是 frida-core 自定义的二进制 RPC（非标准 JSON-RPC）。纯 Kotlin 实现需基于 frida 公开的协议规范重写一个最小化客户端。
   - **MVP 策略**：优先实现「连接探测 + 枚举进程 + spawn/attach + 加载脚本 + 接收 message」5 个核心能力，覆盖门控定位场景所需；复杂 RPC export 调用作为后续扩展。
   - **降级方案**：若协议实现成本超出预期，降级为「Frida 脚本生成器 + 结果解析器」——fler 通过 MCP 生成 .js 脚本，用户用 frida CLI 执行，结果粘贴回 fler 解析。本计划默认走主方案，降级在阶段1末评估。

2. **root 权限**：attach 同设备其他 App 需要 root。fler 不处理 root，文档引导用户在 root shell 启动 `frida-server &`。

3. **同设备 loopback**：fler 与 frida-server 同设备，连 `127.0.0.1:27042`，无需设备管理基础设施。

### 2.3 不做的事（边界）

- ❌ 不注册 `EmulationEngine` 实现（语义不匹配）
- ❌ 不下载/分发 frida-server（阶段1不做，用户自启）
- ❌ 不改 CMakeLists.txt / 不加 native 模块
- ❌ 不实现 ADB / 远程设备连接管理
- ❌ 不实现 frida-gadget 嵌入式 hook

---

## 三、Proposed Changes 拟定改动

### 改动 1：新建 `FridaClient.kt` — frida-server 客户端

**文件**：`app/src/main/java/com/ai/fler/core/frida/FridaClient.kt`（新建目录 `core/frida/`）

**职责**：封装与 frida-server 的连接、进程枚举、spawn/attach、脚本加载、message 接收。

**设计要点**：
- `@Singleton class FridaClient @Inject constructor(@ApplicationContext ctx: Context, okHttp: OkHttpClient)`
- `MutableStateFlow<FridaConnectionState>` 暴露连接状态（Disconnected/Connecting/Connected/Error），供 UI 与 MCP 工具共用
- 连接参数：`host`（默认 `127.0.0.1`）、`port`（默认 `27042`），存 SharedPreferences（参考 `EngineSourceConfig` 模式）
- 核心 API（全部 `suspend`）：
  ```kotlin
  suspend fun ping(): Boolean                          // 探测 frida-server 存活
  suspend fun listProcesses(): List<FridaProcess>      // 枚举设备进程
  suspend fun spawn(packageName: String): Int          // spawn 目标 App，返回 PID
  suspend fun resume(pid: Int): Boolean
  suspend fun attach(pid: Int): FridaSession           // attach 已运行进程，返回会话句柄
  suspend fun createScript(session: FridaSession, source: String): FridaScript
  suspend fun loadScript(script: FridaScript): Boolean // 加载 JS，开始接收 message
  suspend fun detach(session: FridaSession): Boolean
  // message 回调通过 SharedFlow<FridaMessage> 暴露，MCP 工具按需 collect
  val messages: SharedFlow<FridaMessage>
  ```
- 协议层：新建 `FridaProtocol.kt` 封装 frida 线协议的帧编解码（基于 frida 公开规范）；若协议过重，先实现 `ping`（轻量握手）验证连通性，其余逐步补全

**配套数据类**（同文件或 `FridaTypes.kt`）：
```kotlin
sealed class FridaConnectionState { object Disconnected; object Connecting; data class Connected(val version: String); data class Error(val msg: String) }
data class FridaProcess(val pid: Int, val name: String, val packageName: String?)
data class FridaSession(val id: String, val pid: Int)
data class FridaScript(val id: String, val sessionId: String)
data class FridaMessage(val type: String, val payload: JsonElement, val scriptId: String)  // type=send/error/log
```

### 改动 2：新建 `FridaMcpToolRegistry.kt` — MCP 工具注册

**文件**：`app/src/main/java/com/ai/fler/core/mcp/FridaMcpToolRegistry.kt`（新建）

**职责**：仿 `EmulationMcpToolRegistry`，暴露 `frida_*` 工具集。

**设计要点**：
- `@Singleton class FridaMcpToolRegistry @Inject constructor(private val fridaClient: FridaClient)`
- `fun buildTools(): Map<String, McpTool>` 返回以下工具：

| 工具名 | 入参 | 出参 | 说明 |
|---|---|---|---|
| `frida_ping` | host?, port? | `{connected, version}` | 探测 frida-server，验证连通性 |
| `frida_list_processes` | query?(按名过滤) | `[{pid, name, packageName}]` | 枚举设备进程 |
| `frida_spawn` | packageName | `{pid}` | spawn 目标 App（挂起态） |
| `frida_resume` | pid | `{ok}` | 恢复 spawn 的进程 |
| `frida_attach` | pid 或 packageName | `{sessionId}` | attach 已运行进程 |
| `frida_load_script` | sessionId, source | `{scriptId, loaded}` | 加载 JS 脚本 |
| `frida_get_messages` | sessionId, sinceMs?, limit? | `[{type, payload, scriptId, ts}]` | 拉取脚本 send 的 message |
| `frida_detach` | sessionId | `{ok}` | 断开会话 |

- Schema helpers（`objProps`/`strType`/`intType`）从 `EmulationMcpToolRegistry` 复制，保持风格一致
- 错误处理：连接失败/超时统一抛 `McpToolException`（已有，McpToolHandlers.kt 第 1957 行）
- **门控定位场景的示例脚本**：在工具 description 中附一段定位 `is_premium` 的 JS 模板，引导 AI 生成 hook 脚本：
  ```js
  // frida_load_script 的 source 参数示例
  Java.perform(() => {
    const SP = Java.use('android.app.SharedPreferencesImpl');
    SP.getBoolean.overload('java.lang.String','boolean').implementation = function(k, def) {
      const v = this.getBoolean(k, def);
      if (k.includes('premium')) send({key:k, value:v, stack:Java.use('android.util.Log').getStackTraceString(Java.use('java.lang.Exception').$new())});
      return v;
    };
  });
  ```

### 改动 3：修改 `McpToolHandlers.kt` — 注入并聚合

**文件**：[McpToolHandlers.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpToolHandlers.kt)

**改动点**（2 处）：
1. 构造函数加参数：`@Inject constructor(... private val fridaMcp: FridaMcpToolRegistry, ...)`
2. `tools` buildMap 内（第 157–169 行）追加一行：
   ```kotlin
   fridaMcp.buildTools().forEach { (k, v) -> this[k] = v }
   ```

无需改 McpProtocol / McpHttpServer / McpServerService。

### 改动 4：新建 `FridaSettingsCard.kt` — 连接配置 UI（最小）

**文件**：`app/src/main/java/com/ai/fler/features/settings/FridaSettingsCard.kt`（新建）

**职责**：设置页加一张卡片，显示 frida-server 连接状态，可编辑 host/port，"测试连接"按钮调 `fridaClient.ping()`。

**设计要点**：
- `@Composable fun FridaSettingsCard(viewModel: FridaSettingsViewModel = hiltViewModel())`
- UI 元素：host 输入框（默认 127.0.0.1）、port 输入框（默认 27042）、连接状态指示灯、测试连接按钮、引导文本（"需先在 root shell 执行 `./frida-server &`"）
- 参考 [McpSettingsCard.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/settings/McpSettingsCard.kt) 的结构与样式

**配套 ViewModel**：`FridaSettingsViewModel.kt`（同目录），`@HiltViewModel`，持有 `FridaClient` 的 `connectionState` Flow，提供 `testConnection()` / `saveHostPort(host, port)`。

**接入设置页**：在 SettingsScreen 的卡片列表中插入 `FridaSettingsCard()`（具体插入位置实施时定位 SettingsScreen 组合处）。

### 改动 5：SharedPreferences 持久化连接配置

**文件**：`app/src/main/java/com/ai/fler/core/frida/FridaConfig.kt`（新建，仿 `EngineSourceConfig` 模式）

**职责**：用 SharedPreferences 存 `frida_host` / `frida_port`，提供 `host(): String` / `port(): Int` / `update(host, port)`。

---

## 四、Assumptions & Decisions 假设与决策

### 决策
1. **Frida 独立为 MCP 工具集，不注册 EmulationEngine**：进程附加模型与 `EmulationEngine.open(filePath)` 语义不匹配，强行实现会污染接口（探索报告第 8 节）。
2. **同设备 loopback 连接**：fler 与 frida-server 同设备，连 `127.0.0.1:27042`，无需设备管理。
3. **frida-server 用户自启**：fler 不处理 root/SELinux/启动，文档引导用户 root shell 启动。
4. **MVP 工具集 8 个**：ping / list_processes / spawn / resume / attach / load_script / get_messages / detach，覆盖门控定位全流程。
5. **协议层独立文件**：`FridaProtocol.kt` 封装 frida 线协议，便于后续扩展或替换实现。

### 假设
- 用户设备已 root，且能自行启动 frida-server（fler 不验证 root）。
- frida-server 版本与 fler 客户端协议兼容（MVP 不做版本协商，ping 探测失败即报错）。
- OkHttp 可用于 frida 协议（若 frida 线协议非 WebSocket，改用原生 `Socket`，OkHttp 仅用于配置页连通性测试）。
- 目标 App 的 Java 层 hook（SharedPreferences.getBoolean 等）足以定位门控；若需 native hook（如 hook libapp.so 的 Dart getter），扩展 JS 脚本即可，不改 fler 客户端。

### 风险与缓解
| 风险 | 概率 | 缓解 |
|---|---|---|
| frida 线协议纯 Kotlin 实现成本高 | 中 | MVP 先实现 ping；若 2 个工作日内无法完成 list_processes，降级为脚本生成器方案 |
| 目标 App 反 Frida 检测 | 中 | 不在 fler 侧处理，由 JS 脚本侧对抗（如 frida-anti-anti） |
| 同设备 attach 被 SELinux 拦截 | 低 | 文档引导用户 `setenforce 0` 或正确配置 frida-server 上下文 |

---

## 五、Verification 验证步骤

### 5.1 编译验证
- `gradlew assembleDebug` 通过，无新增编译错误
- 新增 `core/frida/` 包下 5 个文件，`core/mcp/FridaMcpToolRegistry.kt` 1 个文件，`features/settings/` 下 2 个文件

### 5.2 MCP 工具暴露验证
- 启动 App，开启 MCP 服务
- PC 端 MCP 客户端调 `tools/list`，确认出现 `frida_ping` / `frida_list_processes` / ... / `frida_detach` 8 个工具
- 工具 schema 合法（inputSchema 字段类型正确）

### 5.3 连通性验证（需 frida-server 运行）
1. 设备 root shell 启动 `./frida-server &`
2. fler 设置页点"测试连接"，状态灯变绿
3. MCP 调 `frida_ping` → 返回 `{connected:true, version:"16.x"}`
4. MCP 调 `frida_list_processes` → 返回进程列表，能找到 `com.freresmensah.imagesearch`

### 5.4 门控定位端到端验证（核心场景）
1. MCP 调 `frida_spawn` packageName=`com.freresmensah.imagesearch` → 返回 pid
2. MCP 调 `frida_load_script` source=SharedPreferences.getBoolean hook 脚本 → loaded:true
3. MCP 调 `frida_resume` → 进程运行
4. 在 App 内触发 premium 功能入口
5. MCP 调 `frida_get_messages` → 收到 `{key:"is_premium", value:false, stack:"..."}`，stack 中含调用方类名/方法
6. **成功标准**：message 中的 stack 能定位到读取 `is_premium` 的具体方法，验证上一会话静态分析无法定位的门控点

### 5.5 降级方案触发条件
若 5.3 的 `frida_ping` 因协议实现问题无法在合理时间内通过，降级为：
- `frida_*` 工具仅保留 `frida_generate_script`（生成 JS）+ `frida_parse_messages`（解析用户粘贴的 frida 输出）
- 用户用 frida CLI 执行脚本，结果回贴 fler 解析
- 此降级不影响 MCP 扩展架构，仅工具集缩减

---

## 六、实施顺序（TodoList 参考）

1. 新建 `core/frida/` 目录 + `FridaConfig.kt` + `FridaTypes.kt`（数据类）
2. 实现 `FridaClient.kt` + `FridaProtocol.kt`（先 ping，再 list_processes，逐步补全）
3. 新建 `FridaMcpToolRegistry.kt`，实现 8 个工具的 handler
4. 修改 `McpToolHandlers.kt` 注入并聚合（2 行改动）
5. 编译验证 `gradlew assembleDebug`
6. MCP tools/list 暴露验证
7. 新建 `FridaSettingsViewModel.kt` + `FridaSettingsCard.kt`，接入设置页
8. 端到端门控定位验证（需 frida-server + 目标 App）

---

## 七、文件清单

| 操作 | 文件 | 说明 |
|---|---|---|
| 新建 | `app/src/main/java/com/ai/fler/core/frida/FridaConfig.kt` | 连接配置持久化 |
| 新建 | `app/src/main/java/com/ai/fler/core/frida/FridaTypes.kt` | 数据类 |
| 新建 | `app/src/main/java/com/ai/fler/core/frida/FridaProtocol.kt` | frida 线协议封装 |
| 新建 | `app/src/main/java/com/ai/fler/core/frida/FridaClient.kt` | 客户端主类 |
| 新建 | `app/src/main/java/com/ai/fler/core/mcp/FridaMcpToolRegistry.kt` | MCP 工具注册 |
| 修改 | `app/src/main/java/com/ai/fler/core/mcp/McpToolHandlers.kt` | 注入+聚合（2 处） |
| 新建 | `app/src/main/java/com/ai/fler/features/settings/FridaSettingsViewModel.kt` | 设置页 VM |
| 新建 | `app/src/main/java/com/ai/fler/features/settings/FridaSettingsCard.kt` | 设置页卡片 |
| 修改 | 设置页组合处（实施时定位） | 插入 FridaSettingsCard |

**零改动**：CMakeLists.txt / cpp/ / NativeLoader / EngineRegistry / AnalysisModule / McpProtocol / McpHttpServer
