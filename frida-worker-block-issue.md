# Frida worker 线程阻塞问题 — 诊断记录

> 项目：fler（Android 逆向分析工具）
> 模块：`app/src/main/cpp/jni_bridge/frida_jni.cpp` — frida-core 客户端 worker 线程
> 状态：已定位，待修复

---

## 一、问题现象

在真机（root + frida-server 17.17.0）上反复出现，与「MCP 动态加载 JS / hook」强相关：

1. **MCP 调用整体超时挂死**
   - `frida_eval`、`frida_use_script` 经常超过 60~120s 无响应（HTTP 超时），随后连
     `frida_ready` 这种轻量探测也一并超时 → 整个 MCP server 表现为不可用。
   - 不是目标进程崩溃：danbo / frida-server / fler 进程都存活。

2. **偶发性**
   - 同一份脚本、同一 session，有时 `createScript → loadScript ok` 一条龙成功（事件正常回传），
     有时直接 HUNG。与脚本内容无关（连 3 行的 bootstrap 预设也失败过）。
   - 重装/重启 fler App（kill worker 线程 + 重建 GMainContext）后恢复，但下一次高风险操作又可能复现。

3. **错误细节被吞**
   - 修复前：`runHook` 失败只抛 `McpToolException("脚本加载失败")`，真实 cause 只在 native
     `Log.d/Log.e("FlerFridaJNI")`，MCP 侧完全不可见。
   - 已加 `nativeLastScriptError` 透传后能拿到 `GDBus.Error:...InvalidArgument: Script(line N): SyntaxError`
     （这是**脚本语法错**，是合理诊断，不是阻塞根因）。

---

## 二、根因定位

### 2.1 `syncOnWorker` 在「活跃 hook」场景下死锁

`frida_jni.cpp` 的 worker 线程是**单线程 GMainLoop**，所有 frida 同步调用经
`g_main_context_invoke` 投递到 worker 执行，调用方线程 `future.get()` 阻塞等待（见
`syncOnWorker`，行 ~89-105）。

注释里本来就明文警告过这个模式：

```
// unload/detach 一律异步：*_sync 在 worker 的 invoke job 里会嵌套泵同一个
// GMainContext（目标进程有活跃 hook 时尤其容易阻塞），改成 *_async + *_finish
// 回调式，worker 主循环不阻塞，回调在 worker context 上收尾清理。
```

但**只有 `unloadScript`/`detach` 改成了 async**，而两条高频路径仍是 `*_sync`：

| 函数 | 调用 | 模式 | 风险 |
|---|---|---|---|
| `nativeCreateScript` | `frida_session_create_script_sync` | **sync** | 高 |
| `nativeLoadScript` | `frida_script_load_sync` | **sync** | 最高 |
| `nativeUnloadScript` | `frida_script_unload`（async） | async | 低 |
| `nativeDetach` | `frida_session_detach`（async） | async | 低 |

机制：`frida_session_create_script_sync` / `frida_script_load_sync` 在 frida-core 内部
会**主动 pump 当前线程的 GMainContext** 等待 agent 回复；而 job 又恰好运行在 worker 的
main context 上。当目标进程存在**之前 attach/未完全卸载的 Interceptor** 时，这一嵌套
pump 会重入 worker 主循环并争抢同一批 source，导致 message 派发次序错乱 / job 永远等不到
自己的 reply → `future.get()` 永不返回。

### 2.2 为什么 MCP 表现为「全挂」

`ensureReady()` / `attach()` / `runHook()` 全都走同一个 worker 的 `syncOnWorker`。
一旦某次 `loadScript` 的 job 卡死在 worker 队列里，后续所有投递的 job（包括 `frida_ready`
的探测、下一次 attach）都会被排到它后面，**全部无限阻塞** → MCP server 整体失去响应，
只有强杀 fler 进程（worker 线程随之销毁）才恢复。

### 2.3 诱因排序（实际观察）

1. 同一 session 上多次 create/load，且前面脚本产生过 `enter/leave`（含 `tamper` 类改
   `this.context.pc`）事件 —— 目标进程里 interceptor 越多，sync 重入越容易挂。
2. `frida_eval` 频繁注入短脚本后 unload 不彻底，留下半卸载状态。
3. attach 到**函数体中部指令**（非函数入口，如 `0xbe9380` tbnz）也可能提高嵌套 pump 概率
   （与本问题同次观察，尚需复现确认）。

---

## 三、已做的改进

### 3.1 错误透传（已完成，含在本次改动）

- `frida_jni.cpp`：新增全局 `g_lastScriptError` + `takeLastScriptError()`；
  `createScript`/`loadScript` 失败时记录 `GError` 文本（原只打 logcat）。
- 新增 JNI `nativeLastScriptError`（含 stub 分支）。
- `FridaBindings.kt`：`takeLastScriptError()`。
- `FridaMcpToolRegistry.kt`：`frida_eval` / `frida_use_script` / `frida_hook`
  失败时把原错误拼进 `McpToolException`。

效果：**语法错**能被看到（`SyntaxError: unexpected token ...`）；但**阻塞类**问题仍无解
（超时那类不返回任何 MCP 响应）。

---

## 四、待修复方向

### 4.1 首选：create/load 也改 async（对齐 unload/detach）

把 `nativeCreateScript` / `nativeLoadScript` 改为 `*_async` + `*_finish` 回调，回调里
`storeScript` / 检查错误 / 通过 promise 回传，**worker 主循环永不阻塞**。这是根治。

改动点（`frida_jni.cpp`）：
- `frida_session_create_script_async(session, source, opts, onCreated, ...)`
- `frida_script_load_async(script, onLoaded, ...)`
- 回调里 `frida_*_finish` 后写 g_lastScriptError 并 `g_main_context_invoke` 回主调用方。
- `syncOnWorker` 改为「submit + future」，但 job 本身绝不再做嵌套 pump 调用。

### 4.2 兜底：worker 健康检测 + 看门狗

- `postToWorker`/`syncOnWorker` 增加超时：若 `future.get_for(timeout)` 超时，标记 worker
  dead，返回明确的「worker blocked」错误（而不是无限挂）。
- `frida_ready` 返回里带 worker liveness 字段，MCP 超时可 fast-fail。

### 4.3 辅助：会话复用策略收紧

- `frida_eval` 注入的脚本使用后立即 async unload，不留残影。
- 同 session 限制并发脚本数；脏 session 检测（detached 事件）后自动重建。

---

## 五、复现路径（回归验证用）

1. root 设备，fler MCP 就绪，attach 到运行中的目标（如 danbo）。
2. `frida_eval` 注入一个含 `Interceptor.attach` 的脚本（hook 目标任意地址），确认事件回传。
3. 持续多次 `frida_eval`（每次 attach 到不同指令位置，含函数中部如 tbnz 处），高概率触发。
4. 观察：某次调用 > 10s 无返回 → 后续 `frida_ready` 也超时 → 判定 worker 阻塞。
5. 修复后断言：同样操作全部快速返回，且错误（若有）带具体 GError。

---

## 六、相关文件

- `app/src/main/cpp/jni_bridge/frida_jni.cpp` — worker/GMainLoop、syncOnWorker、create/load/unload/detach、新增 lastError
- `app/src/main/java/com/ai/fler/core/jni/FridaBindings.kt` — JNI 绑定 + `takeLastScriptError`
- `app/src/main/java/com/ai/fler/core/frida/FridaEngine.kt` — `runHook`/`unloadScript` 编排
- `app/src/main/java/com/ai/fler/core/mcp/FridaMcpToolRegistry.kt` — `frida_eval`/`frida_use_script`/`frida_hook` handler，错误透传