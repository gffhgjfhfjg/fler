# Frida 动态分析集成 — 落地开发方案

> 项目：fler（Android 逆向分析工具）
> 目标：集成 Frida 客户端能力，同设备 hook 运行中的目标 App，通过 MCP 暴露 `frida_*` 工具，辅助定位混淆代码的运行时门控点。
> 范围：阶段1 — 纯 Kotlin，零 native 改动，frida-server 由用户 root 自启。

---

## 一、协议技术分析（已验证）

### 1.1 传输层：WebSocket

frida-server v15+ 默认监听 `127.0.0.1:27042`，提供 WebSocket 端点 `ws://127.0.0.1:27042/ws`。

```
fler App (Kotlin/OkHttp WebSocket)  ←—— ws://127.0.0.1:27042/ws ——→  frida-server (root 启动)
```

- 二进制帧传输 DBus 消息
- 无需认证（frida DBus direct 模式，`authMethods: []`）
- 参考实现：[httptoolkit/frida-js](https://github.com/httptoolkit/frida-js) `src/index.ts` 第 14-22 行

### 1.2 协议层：DBus（direct 模式）

frida 在 WebSocket 之上跑 DBus 协议（点对点直连，非标准 daemon 模式）：

| 特征 | 值 |
|---|---|
| 模式 | `direct: true`（无 daemon 中转，无 Hello 握手） |
| 认证 | `authMethods: []`（无 SASL） |
| 序列化 | DBus 二进制类型系统（小端序） |
| 交互 | `method_call` → `method_return`（仅用方法调用，不用 signal） |

### 1.3 接口规格（frida v16/v17 兼容）

frida-server 暴露 3 个 DBus 接口，fler 需调用其中 8 个方法：

#### HostSession（路径 `/re/frida/HostSession`，服务 `re.frida.HostSession{16|17}`）

| 方法 | 入参 | 返回 | 用途 |
|---|---|---|---|
| `QuerySystemParameters` | `{}` | variant dict | 系统元数据（os/version/arch） |
| `EnumerateProcesses` | `{}` | `[[pid:uint32, name:string]]` | 枚举运行中进程 |
| `EnumerateApplications` | `{}` | `[[id:string, name:string, pid:uint32]]` | 枚举已安装 App（pid=0 表示未运行） |
| `Attach` | `(pid:uint32, options:{})` | `[sessionId:string]` | attach 进程，返回会话 ID |
| `Spawn` | `(program:string, options:struct)` | `pid:uint32` | spawn 进程（挂起态） |
| `Resume` | `(pid:uint32)` | `void` | 恢复 spawn 的进程 |
| `Kill` | `(pid:uint32)` | `void` | 杀死进程 |

Spawn 的 options 结构体（frida-js 第 46-56 行）：
```
[hasArgv:bool, argv:[string], hasEnvP:bool, envp:[string],
 hasEnv:bool, env:[string], cwd:string, stdio:uint32, aux:[]]
```

#### AgentSession（路径 `/re/frida/AgentSession/{sessionId}`，服务 `re.frida.AgentSession{16|17}`）

| 方法 | 入参 | 返回 | 用途 |
|---|---|---|---|
| `CreateScript` | `(source:string, options:{})` | `[scriptId:[uint32]]` | 创建脚本（未加载） |
| `LoadScript` | `(scriptId:[uint32])` | `void` | 加载脚本到目标进程 |
| `PostMessages` | `(messages:[AgentMessage], batchId:uint32)` | `void` | 向脚本发送消息 |

#### AgentMessageSink（路径 `/re/frida/AgentMessageSink/{sessionId}`，服务 `re.frida.AgentMessageSink{16|17}`）

frida-server **主动调用** host 端此方法推送脚本消息：

| 方法 | 入参 | 返回 |
|---|---|---|
| `PostMessages` | `(messages:[AgentMessage])` | `void` |

AgentMessage 结构体（frida-js 第 74 行）：
```
[kind:uint32, scriptId:[uint32], text:string, hasData:bool, data:byte[]]
```
- `kind`：1=Script 消息，2=Debugger 消息
- `text`：JSON 字符串，格式为 `{type:"send"|"error"|"log", payload:..., ...}`
- `data`：可选二进制附件

### 1.4 版本协商

frida v16/v17 的服务名带版本后缀（`re.frida.HostSession16` / `re.frida.HostSession17`）。fler 需依次尝试两个版本，取首个成功的（参考 frida-js 第 121-129 行 `SUPPORTED_API_VERSIONS = ['17', '16']`）。

---

## 二、DBus 编解码方案

### 2.1 方案选型

| 方案 | 优点 | 缺点 | 决策 |
|---|---|---|---|
| A. 用 `hypfvieh/dbus-java` 库 | 成熟、完整 | 庞大（带 SPI/transport 层），需自定义 WebSocket transport 注入，面向 daemon 模式 | ❌ 过重 |
| B. 自实现极简 DBus 编解码器 | 精确控制、体积小、只实现 frida 子集 | 需自己处理 DBus 类型系统 | ✅ 采用 |

**理由**：frida 只用 DBus 的 `method_call`/`method_return`（无 signal/property/introspection），direct 模式无 SASL 握手。frida-js 的整个 DBus 交互层（含 WebSocket 连接）仅 339 行，证明子集很小。自实现可控且无外部依赖。

### 2.2 需实现的 DBus 类型子集

frida 接口用到以下 DBus 类型签名：

| 签名 | 类型 | 用途 |
|---|---|---|
| `s` | string | 进程名/包名/sessionId/脚本源码 |
| `u` | uint32 | pid/batchId/kind/stdio |
| `b` | boolean | hasArgv/hasData |
| `ay` | byte array | 二进制 data |
| `a{sv}` | dict<string,variant> | options 参数（空字典） |
| `a(si)` | array of struct | EnumerateProcesses 返回 |
| `a(ssu)` | array of struct | EnumerateApplications 返回 |
| `a(usbbay)` | array of struct (AgentMessage) | PostMessages 入参 |
| `(...)` | struct | Spawn options / AgentMessage |

### 2.3 DBus 消息帧结构（小端序）

```
Header (12 bytes):
  endian       : 1 byte  ('l' = little-endian)
  msg_type     : 1 byte  (1=method_call, 2=method_return, 3=error)
  flags        : 1 byte  (3 = no-reply-expected | no-auto-start)
  protocol     : 1 byte  (1)
  body_len     : 4 bytes (uint32 LE)
  serial       : 4 bytes (uint32 LE, 自增序列号)

Header fields (array of struct(byte, variant)):
  padding to 8-byte boundary
  array_len    : 4 bytes
  [field_code:1, signature:1, padding, value]...
    field_code 1 = PATH    (value type 'o')
    field_code 2 = INTERFACE (value type 's')
    field_code 3 = MEMBER  (value type 's')
    field_code 6 = DESTINATION (value type 's')
    field_code 8 = SIGNATURE (value type 'g')

Body:
  padding to 8-byte boundary
  method arguments (按签名序列化)

整帧 padding 到 8 字节对齐
```

---

## 三、Kotlin 类设计

基于 frida-js 339 行参考实现映射，包路径 `com.ai.fler.core.frida`。

### 3.1 类结构

```
com.ai.fler.core.frida
├── FridaConfig.kt              # host/port 持久化
├── FridaTypes.kt               # 数据类
├── dbus
│   ├── DbusType.kt             # DBus 类型签名枚举
│   ├── DbusMessage.kt          # 消息帧编解码
│   ├── DbusReader.kt           # 二进制流读取（对齐/类型反序列化）
│   ├── DbusWriter.kt           # 二进制流写入（对齐/类型序列化）
│   └── FridaDBusClient.kt      # DBus 方法调用封装（method_call/return 匹配）
├── FridaClient.kt              # 主客户端（连 WebSocket + DBus）
├── FridaHostSession.kt         # HostSession 接口封装
├── FridaAgentSession.kt        # AgentSession 接口封装
└── FridaScript.kt              # Script 封装

com.ai.fler.core.mcp
└── FridaMcpToolRegistry.kt     # MCP 工具注册

com.ai.fler.features.settings
├── FridaSettingsViewModel.kt
└── FridaSettingsCard.kt
```

### 3.2 核心类骨架

#### FridaClient.kt — 主入口

```kotlin
@Singleton
class FridaClient @Inject constructor(
    @ApplicationContext private val ctx: Context,
    private val config: FridaConfig,
) {
    private val _connectionState = MutableStateFlow<FridaConnectionState>(FridaConnectionState.Disconnected)
    val connectionState: StateFlow<FridaConnectionState> = _connectionState.asStateFlow()

    private val _messages = MutableSharedFlow<FridaMessage>(extraBufferCapacity = 256)
    val messages: SharedFlow<FridaMessage> = _messages.asSharedFlow()

    private var webSocket: WebSocket? = null
    private var dbusClient: FridaDBusClient? = null
    private val sessions = mutableMapOf<String, FridaAgentSession>()  // sessionId → session
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 探测 frida-server 并获取版本 */
    suspend fun ping(): FridaConnectionState { ... }

    /** 枚举进程 */
    suspend fun listProcesses(query: String? = null): List<FridaProcess> { ... }

    /** 枚举已安装 App */
    suspend fun listApplications(query: String? = null): List<FridaApp> { ... }

    /** spawn 进程（挂起态），返回 pid */
    suspend fun spawn(packageName: String): Int { ... }

    /** 恢复 spawn 的进程 */
    suspend fun resume(pid: Int): Boolean { ... }

    /** attach 已运行进程 */
    suspend fun attach(pid: Int): FridaAgentSession { ... }

    /** 断开 */
    suspend fun disconnect() { ... }
}
```

#### FridaDBusClient.kt — DBus 方法调用封装

```kotlin
class FridaDBusClient(
    private val sendFrame: (ByteArray) -> Unit,
    private val onFrame: Flow<ByteArray>,
) {
    private val serialCounter = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<DbusMessage>>()

    /** 发起 method_call，等待 method_return */
    suspend fun callMethod(
        destination: String,      // "re.frida.HostSession17"
        path: String,             // "/re/frida/HostSession"
        interface: String,        // "re.frida.HostSession17"
        member: String,           // "EnumerateProcesses"
        signature: String,        // "a{sv}"
        args: List<Any>,          // [emptyMap<String,Any>()]
    ): DbusMessage { ... }

    /** 注册 method_call handler（用于接收 AgentMessageSink.PostMessages） */
    fun registerMethodHandler(
        path: String,
        interface: String,
        member: String,
        handler: suspend (List<Any>) -> Unit,
    ) { ... }

    /** 处理收到的 WebSocket 二进制帧 */
    suspend fun handleFrame(bytes: ByteArray) {
        val msg = DbusMessage.decode(bytes)
        when (msg.type) {
            2 -> { /* method_return → 匹配 pending */ }
            3 -> { /* error → 匹配 pending 并抛异常 */ }
            1 -> { /* method_call → 匹配 handler */ }
        }
    }
}
```

#### FridaHostSession.kt — HostSession 封装

```kotlin
class FridaHostSession(
    private val dbus: FridaDBusClient,
    private val apiVersion: String,  // "16" or "17"
) {
    private val service = "re.frida.HostSession$apiVersion"
    private val path = "/re/frida/HostSession"
    private val iface = "re.frida.HostSession$apiVersion"

    suspend fun querySystemParameters(): Map<String, Any> {
        val reply = dbus.callMethod(service, path, iface, "QuerySystemParameters", "a{sv}", listOf(emptyMap()))
        return reply.body[0] as Map<String, Any>
    }

    suspend fun enumerateProcesses(): List<FridaProcess> {
        val reply = dbus.callMethod(service, path, iface, "EnumerateProcesses", "a{sv}", listOf(emptyMap()))
        @Suppress("UNCHECKED_CAST")
        return (reply.body[0] as List<Pair<Int, String>>).map { (pid, name) -> FridaProcess(pid, name) }
    }

    suspend fun enumerateApplications(): List<FridaApp> {
        val reply = dbus.callMethod(service, path, iface, "EnumerateApplications", "a{sv}", listOf(emptyMap()))
        @Suppress("UNCHECKED_CAST")
        return (reply.body[0] as List<Triple<String, String, Int>>).map { (id, name, pid) ->
            FridaApp(id, name, if (pid == 0) null else pid)
        }
    }

    suspend fun attach(pid: Int): String {
        val reply = dbus.callMethod(service, path, iface, "Attach", "ua{sv}", listOf(pid, emptyMap()))
        return reply.body[0] as String
    }

    suspend fun spawn(program: String): Int {
        // options: [hasArgv:true, [program], false, [], false, [], "", 0, []]
        val options = listOf(true, listOf(program), false, emptyList<String>(), false, emptyList<String>(), "", 0, emptyList<Any>())
        val reply = dbus.callMethod(service, path, iface, "Spawn", "s(basbaasasiasuay...)...)", listOf(program, options))
        return reply.body[0] as Int
    }

    suspend fun resume(pid: Int) {
        dbus.callMethod(service, path, iface, "Resume", "u", listOf(pid))
    }

    suspend fun kill(pid: Int) {
        dbus.callMethod(service, path, iface, "Kill", "u", listOf(pid))
    }
}
```

#### FridaAgentSession.kt — AgentSession 封装

```kotlin
class FridaAgentSession(
    private val dbus: FridaDBusClient,
    private val apiVersion: String,
    private val hostSession: FridaHostSession,
    val pid: Int,
    val sessionId: String,
) {
    private val service = "re.frida.AgentSession$apiVersion"
    private val path = "/re/frida/AgentSession/$sessionId"
    private val iface = "re.frida.AgentSession$apiVersion"
    private val sinkPath = "/re/frida/AgentMessageSink/$sessionId"
    private val sinkIface = "re.frida.AgentMessageSink$apiVersion"

    /** 创建脚本 */
    suspend fun createScript(source: String): FridaScript {
        val reply = dbus.callMethod(service, path, iface, "CreateScript", "sa{sv}", listOf(source, emptyMap()))
        val scriptId = reply.body[0] as List<Int>  // [uint32]
        return FridaScript(dbus, this, scriptId)
    }

    /** 注册 message 接收 handler（frida-server 主动调用 AgentMessageSink.PostMessages） */
    fun setupMessageSink(onMessage: suspend (FridaMessage) -> Unit) {
        dbus.registerMethodHandler(sinkPath, sinkIface, "PostMessages") { args ->
            @Suppress("UNCHECKED_CAST")
            val messages = args[0] as List<AgentMessageRaw>
            messages.forEach { msg ->
                if (msg.kind == 1) {  // AgentMessageKind.Script
                    val parsed = Json.decodeFromString<FridaMessagePayload>(msg.text)
                    onMessage(FridaMessage(parsed, msg.scriptId))
                }
            }
        }
    }

    suspend fun resume() = hostSession.resume(pid)
    suspend fun kill() = hostSession.kill(pid)
}
```

### 3.3 数据类

```kotlin
// FridaTypes.kt
sealed class FridaConnectionState {
    object Disconnected : FridaConnectionState()
    object Connecting : FridaConnectionState()
    data class Connected(val apiVersion: String) : FridaConnectionState()
    data class Error(val message: String) : FridaConnectionState()
}

data class FridaProcess(val pid: Int, val name: String)
data class FridaApp(val id: String, val name: String, val pid: Int?)  // pid=null 表示未运行
data class FridaMessage(val payload: FridaMessagePayload, val scriptId: List<Int>)

@Serializable
data class FridaMessagePayload(
    val type: String,  // "send" | "error" | "log"
    val payload: JsonElement? = null,
    val description: String? = null,  // error
    val stack: String? = null,        // error
    val level: String? = null,        // log
)

data class AgentMessageRaw(
    val kind: Int,           // 1=Script, 2=Debugger
    val scriptId: List<Int>,
    val text: String,        // JSON
    val hasData: Boolean,
    val data: ByteArray?,
)
```

---

## 四、MCP 工具规格

### 4.1 工具清单

`FridaMcpToolRegistry` 暴露 8 个工具，仿 `EmulationMcpToolRegistry` 模式：

| 工具名 | 入参 schema | 出参 | 实现 |
|---|---|---|---|
| `frida_ping` | `{}` | `{connected:bool, apiVersion?:string, error?:string}` | `client.ping()` |
| `frida_list_processes` | `{query?:string}` | `{count:int, processes:[{pid:int,name:string}]}` | `client.listProcesses(query)` |
| `frida_list_apps` | `{query?:string}` | `{count:int, apps:[{id:string,name:string,pid:int|null}]}` | `client.listApplications(query)` |
| `frida_spawn` | `{packageName:string}` | `{pid:int}` | `client.spawn(packageName)` |
| `frida_resume` | `{pid:int}` | `{ok:bool}` | `client.resume(pid)` |
| `frida_attach` | `{pid:int}` | `{sessionId:string}` | `client.attach(pid)` |
| `frida_load_script` | `{sessionId:string, source:string}` | `{scriptId:string, loaded:bool}` | `session.createScript(source)` + `script.load()` |
| `frida_get_messages` | `{sessionId:string, limit?:int}` | `{count:int, messages:[FridaMessagePayload]}` | 从 `client.messages` Flow 按 sessionId 过滤拉取 |
| `frida_detach` | `{sessionId:string}` | `{ok:bool}` | `client.detach(sessionId)` |

### 4.2 注册方式

```kotlin
// FridaMcpToolRegistry.kt
@Singleton
class FridaMcpToolRegistry @Inject constructor(
    private val fridaClient: FridaClient,
) {
    fun buildTools(): Map<String, McpTool> = buildMap {
        this["frida_ping"] = McpTool(
            name = "frida_ping",
            description = "探测 frida-server 连通性...",
            inputSchema = objProps(),
            handler = { _ ->
                val state = fridaClient.ping()
                buildJsonObject {
                    put("connected", state is FridaConnectionState.Connected)
                    if (state is FridaConnectionState.Connected) put("apiVersion", state.apiVersion)
                    if (state is FridaConnectionState.Error) put("error", state.message)
                }
            }
        )
        // ... 其余 7 个工具
    }
}
```

### 4.3 McpToolHandlers 接入（2 处改动）

```kotlin
// McpToolHandlers.kt
@Singleton
class McpToolHandlers @Inject constructor(
    // ... 现有参数
    private val fridaMcp: FridaMcpToolRegistry,  // ← 新增
) {
    val tools: Map<String, McpTool> = buildMap {
        // ... 现有工具
        fridaMcp.buildTools().forEach { (k, v) -> this[k] = v }  // ← 新增
    }
}
```

---

## 五、UI 设计

### 5.1 FridaSettingsCard（设置页卡片）

```
┌─ Frida 动态分析 ─────────────────────────┐
│  ● 已连接 (API v17)                       │
│                                           │
│  Host:  [127.0.0.1          ]             │
│  Port:  [27042              ]             │
│                                           │
│  [测试连接]                                │
│                                           │
│  ℹ 需先在 root shell 执行:                │
│    ./frida-server &                       │
└───────────────────────────────────────────┘
```

- 状态灯：绿=已连接 / 灰=未连接 / 红=错误
- 复用 `McpSettingsCard` 的 Compose 样式

### 5.2 ViewModel

```kotlin
@HiltViewModel
class FridaSettingsViewModel @Inject constructor(
    private val fridaClient: FridaClient,
    private val config: FridaConfig,
) : ViewModel() {
    val state: StateFlow<FridaSettingsUiState> = fridaClient.connectionState
        .map { it.toUiState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), FridaSettingsUiState())

    fun testConnection() = viewModelScope.launch { fridaClient.ping() }
    fun saveHostPort(host: String, port: Int) = config.update(host, port)
}
```

---

## 六、端到端门控定位流程（核心场景）

以 Image Search App 的 `is_premium` 门控为例：

```
1. 设备 root shell 启动 frida-server
   $ ./frida-server &

2. MCP 调用 frida_ping
   → {connected: true, apiVersion: "17"}

3. MCP 调用 frida_list_apps query="imagesearch"
   → {apps: [{id:"com.freresmensah.imagesearch", name:"Image Search", pid:null}]}

4. MCP 调用 frida_spawn packageName="com.freresmensah.imagesearch"
   → {pid: 12345}

5. MCP 调用 frida_attach pid=12345
   → {sessionId: "abc-def-123"}

6. MCP 调用 frida_load_script，source 为以下 JS：
   ┌──────────────────────────────────────────────────────────┐
   │ Java.perform(() => {                                     │
   │   const SP = Java.use('android.app.SharedPreferencesImpl');│
   │   SP.getBoolean.implementation = function(k, def) {      │
   │     const v = this.getBoolean(k, def);                   │
   │     if (k.includes('premium'))                           │
   │       send({                                             │
   │         key: k, value: v,                                │
   │         stack: Java.use('android.util.Log')               │
   │           .getStackTraceString(Java.use('java.lang.Exception').$new())│
   │       });                                                │
   │     return v;                                            │
   │   };                                                     │
   │ });                                                      │
   └──────────────────────────────────────────────────────────┘
   → {scriptId: "[1]", loaded: true}

7. MCP 调用 frida_resume pid=12345
   → {ok: true}

8. 用户在目标 App 内触发 premium 功能入口

9. MCP 调用 frida_get_messages sessionId="abc-def-123"
   → {messages: [{
       type: "send",
       payload: {
         key: "is_premium",
         value: false,
         stack: "at xxx.yyy.zzz(SourceFile:123)\n..."
       }
     }]}
   ← stack 直接定位读取 is_premium 的方法
```

---

## 七、完整文件清单

| 操作 | 文件路径 | 行数估计 | 说明 |
|---|---|---|---|
| 新建 | `core/frida/FridaConfig.kt` | ~40 | SharedPreferences 存 host/port |
| 新建 | `core/frida/FridaTypes.kt` | ~50 | 数据类 |
| 新建 | `core/frida/dbus/DbusType.kt` | ~30 | 类型签名枚举 |
| 新建 | `core/frida/dbus/DbusReader.kt` | ~150 | 二进制读取+对齐+反序列化 |
| 新建 | `core/frida/dbus/DbusWriter.kt` | ~150 | 二进制写入+对齐+序列化 |
| 新建 | `core/frida/dbus/DbusMessage.kt` | ~80 | 消息帧编解码 |
| 新建 | `core/frida/dbus/FridaDBusClient.kt` | ~120 | method_call/return 匹配+handler |
| 新建 | `core/frida/FridaClient.kt` | ~200 | WebSocket 连接+主入口 |
| 新建 | `core/frida/FridaHostSession.kt` | ~80 | HostSession 封装 |
| 新建 | `core/frida/FridaAgentSession.kt` | ~70 | AgentSession 封装 |
| 新建 | `core/frida/FridaScript.kt` | ~40 | Script 封装 |
| 新建 | `core/mcp/FridaMcpToolRegistry.kt` | ~200 | 8 个 MCP 工具 |
| 修改 | `core/mcp/McpToolHandlers.kt` | +2 行 | 注入+聚合 |
| 新建 | `features/settings/FridaSettingsViewModel.kt` | ~50 | 设置页 VM |
| 新建 | `features/settings/FridaSettingsCard.kt` | ~100 | 设置页卡片 |
| 修改 | 设置页组合处 | +1 行 | 插入 FridaSettingsCard |

**零改动**：CMakeLists.txt / cpp/ / EngineRegistry / AnalysisModule / McpProtocol / McpHttpServer / NativeLoader

**总新增代码**：~1300 行 Kotlin

---

## 八、实施步骤

### Step 1：DBus 编解码层（核心基础）
1. 新建 `core/frida/dbus/` 目录
2. 实现 `DbusReader`（从 ByteBuffer 按类型签名读取，处理 8 字节对齐）
3. 实现 `DbusWriter`（按类型签名写入，处理对齐 padding）
4. 实现 `DbusMessage`（header 编解码 + body 拆包）
5. 单元测试：编解码 `EnumerateProcesses` 的 `a(si)` 返回值

### Step 2：FridaDBusClient
1. 实现 `callMethod`（发送 method_call，serial 匹配 method_return）
2. 实现 `registerMethodHandler`（接收 AgentMessageSink.PostMessages）
3. 实现 `handleFrame`（分发 return/error/call）

### Step 3：FridaClient + HostSession
1. OkHttp WebSocket 连接 `ws://127.0.0.1:27042/ws`
2. 版本协商（尝试 v17 → v16）
3. 实现 `ping` / `listProcesses` / `listApplications` / `spawn` / `resume` / `attach`

### Step 4：AgentSession + Script + Message 接收
1. 实现 `createScript` / `loadScript`
2. 注册 AgentMessageSink handler，解析 AgentMessage，推入 SharedFlow

### Step 5：MCP 工具注册
1. 新建 `FridaMcpToolRegistry`，实现 8 个工具 handler
2. 修改 `McpToolHandlers` 注入并聚合

### Step 6：编译验证
- `gradlew assembleDebug` 通过

### Step 7：UI
1. `FridaSettingsViewModel` + `FridaSettingsCard`
2. 接入设置页

### Step 8：端到端验证
- frida-server 启动 → frida_ping → list_apps → spawn → attach → load_script → resume → get_messages

---

## 九、验证标准

### 9.1 编译
- `gradlew assembleDebug` 通过，无新增编译错误

### 9.2 MCP 工具暴露
- `tools/list` 出现 9 个 `frida_*` 工具（含 frida_list_apps）
- 工具 schema 合法

### 9.3 连通性（需 frida-server）
- `frida_ping` → `{connected:true, apiVersion:"17"}`
- `frida_list_apps` → 能找到 `com.freresmensah.imagesearch`

### 9.4 门控定位端到端
- spawn → attach → load_script → resume → get_messages
- **成功标准**：get_messages 返回的 stack 能定位到读取 `is_premium` 的具体方法

### 9.5 DBus 单元测试
- `DbusReader/Writer` 对 `s/u/b/ay/a{sv}/a(si)/a(ssu)` 的编解码正确
- 往返测试：encode → decode 值相等

---

## 十、风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|---|---|---|---|
| DBus 类型系统自实现有 bug | 中 | 协议交互失败 | Step 1 单元测试覆盖所有用到的类型签名 |
| frida v16/v17 协议差异 | 低 | 版本协商失败 | 参考 frida-js 依次尝试两个版本 |
| WebSocket 二进制帧处理 | 低 | 连接不稳定 | OkHttp WebSocket 原生支持二进制帧 |
| AgentMessageSink 回调线程 | 中 | 消息丢失/阻塞 | 用 SharedFlow + extraBufferCapacity 缓冲 |
| 目标 App 反 Frida | 中 | attach 失败 | JS 脚本侧对抗，不改 fler |
| SELinux 拦截 | 低 | attach 被拒 | 文档引导 `setenforce 0` |

---

## 十一、降级方案

若 Step 1-2（DBus 编解码）实现成本超出预期，降级为：

- `frida_*` 工具仅保留 `frida_generate_script`（生成 JS 脚本）+ `frida_parse_output`（解析用户粘贴的 frida CLI 输出）
- 用户用 `frida -U -n com.xxx.app -l script.js` 执行，结果回贴 fler 解析
- 此降级不影响 MCP 扩展架构，仅工具集缩减，但仍能完成门控定位（需手动中转）

**触发条件**：Step 1 单元测试无法在 1 个工作日内通过，或 Step 3 的 `frida_ping` 无法连通。

---

## 附录 A：参考资源

- [httptoolkit/frida-js](https://github.com/httptoolkit/frida-js) — 纯 JS frida 客户端，339 行，本方案的直接参考
- [frida-core DBus 接口](https://deepwiki.com/frida/frida-core/2.3-message-transport-and-communication) — 协议架构文档
- [frida RPC 系统](https://deepwiki.com/frida/frida-python/2.3.5-rpc-system) — RPC 消息格式 `["frida:rpc", id, "call", method, args]`
- [Frida Messages 文档](https://frida.re/docs/messages/) — send/recv 消息机制
- [fler MCP 扩展点](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpToolHandlers.kt) — `McpToolHandlers` 第 157-169 行

## 附录 B：frida-js 关键代码片段（Kotlin 实现的直接参考）

**WebSocket 连接**（frida-js 第 14-22 行）：
```typescript
const socket = new WebSocket(`ws://${fridaHost}/ws`, options);
socket.binaryType = 'arraybuffer';
```

**DBus 客户端创建**（frida-js 第 34-38 行）：
```typescript
const bus = dbus.createClient({
    stream: createWebSocketStream(webSocket),
    direct: true,
    authMethods: []
});
```

**版本协商**（frida-js 第 121-129 行）：
```typescript
for (let version of ['17', '16']) {
    const hostSession = await this.bus
        .getService(`re.frida.HostSession${version}`)
        .getInterface('/re/frida/HostSession', `re.frida.HostSession${version}`);
    if (hostSession) return hostSession;
}
```

**消息接收 handler**（frida-js 第 274-289 行）：
```typescript
this.bus.setMethodCallHandler(
    `/re/frida/AgentMessageSink/${this.sessionId}`,
    `re.frida.AgentMessageSink${this.hostVersion}`,
    "PostMessages",
    [(messages: AgentMessage[]) => {
        for(const message of messages) {
            if (message[0] === AgentMessageKind.Script) {  // kind=1
                cb(JSON.parse(message[2]));  // text → Message
            }
        }
    }, null]
);
```
