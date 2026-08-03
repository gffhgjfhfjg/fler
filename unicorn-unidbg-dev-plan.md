# Unicorn / unidbg 集成开发方案（可执行版）

> 状态：待开发。本文档是 `unicorn-unidbg-integration-plan.md`（调研版）的落地执行方案。
> 范围：第一期仅 Unicorn（M0–M6，约 12 工作日）；unidbg spike 为独立第七期，spike 通过后再排期。
> 原则：**每个阶段有独立验收标准，任何阶段失败可回滚且不影响现有分析功能**（`ENABLE_UNICORN` 编译开关兜底）。

---

## 总览：文件清单

### 新增文件

| 文件 | 阶段 | 说明 |
|---|---|---|
| `scripts/build-unicorn.ps1` | M0 | Unicorn 静态库交叉编译脚本 |
| `app/src/main/cpp/include/unicorn/*.h` | M1 | vendor 头文件（入库） |
| `app/src/main/cpp/jni_bridge/unicorn_jni.cpp` | M2 | JNI 桥（核心） |
| `core/jni/UnicornBindings.kt` | M2 | external fun 声明 |
| `core/analysis/engine/UnicornEngine.kt` | M3 | 实现 EmulationEngine |
| `core/analysis/EmulationSession.kt` | M3 | 仿真会话门面（@Singleton） |
| `features/so_editor/EmulationViewModel.kt` | M4 | 仿真 UI 状态 |
| `features/so_editor/EmulationTab.kt` | M4 | 仿真 tab Composable |

### 修改文件

| 文件 | 阶段 | 改动 |
|---|---|---|
| `cpp/elf_parser/elf_parser.h/.cpp` | M1 | 新增 `ProgramHeader` 结构 + `getLoadSegments()` |
| `cpp/jni_bridge/elf_parser_jni.cpp` + `ElfParserBindings.kt` | M1 | 新增 `nativeGetLoadSegments` |
| `cpp/CMakeLists.txt` | M1 | unicorn 静态库链接 + `ENABLE_UNICORN` 开关 |
| `core/analysis/EmulationEngine.kt` | M3 | `StopReason` 增加 `FUNCTION_RETURN` |
| `core/di/AnalysisModule.kt` | M3 | 注册 `UnicornEngine`（替换占位）+ `EmulationSession` |
| `features/so_editor/SoEditorViewModel.kt` | M4 | `EditorTab` 增加 `EMULATION` |
| `features/so_editor/SoEditorScreen.kt` + `SoEditorDetailScreen.kt` | M4 | tab 栏 + 内容分支各加一处 |
| `core/mcp/EngineMcpToolRegistry.kt` + `McpToolHandlers.kt` | M5 | `emu_*` 工具 |

---

## M0 前置准备（0.5 天）

**任务**
1. 确认本机 NDK r27 + cmake ≥3.22 可用（现有 rizin 构建环境已验证）。
2. 下载 `unicorn-engine/unicorn` v2.0.1 源码（release tarball）。
3. 编写 `scripts/build-unicorn.ps1`：
   ```powershell
   cmake -B build-android -S . `
     -DCMAKE_TOOLCHAIN_FILE=$NDK/build/cmake/android.toolchain.cmake `
     -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 `
     -DUNICORN_ARCH=arm64 -DBUILD_SHARED_LIBS=OFF -DUNICORN_BUILD_TESTS=OFF
   cmake --build build-android --target unicorn -j8
   ```
   产物 `libunicorn.a` → 拷贝到 `app/libs/arm64-v8a/`。

**验收**：`libunicorn.a` 存在且 `llvm-nm libunicorn.a | grep uc_open` 有符号。

**风险**：Unicorn 2.0.1 的 cmake 选项名可能因版本而异（`UNICORN_ARCH` vs `UNICORN_ARCH_ARM64`），以源码 `CMakeLists.txt` 实际为准。

---

## M1 静态库集成 + ELF 程序头解析（1 天）

### M1.1 vendor 头文件

拷贝 `unicorn/include/unicorn/*.h` → `app/src/main/cpp/include/unicorn/`（提交入库，仿 capstone/rizin 模式）。

### M1.2 CMakeLists.txt 修改

```cmake
option(ENABLE_UNICORN "Build with Unicorn emulation" ON)
if(ENABLE_UNICORN)
    add_library(unicorn STATIC IMPORTED)
    set_target_properties(unicorn PROPERTIES
        IMPORTED_LOCATION "${FLER_LIBS_DIR}/libunicorn.a")
    # 源列表加 jni_bridge/unicorn_jni.cpp（用 target_compile_definitions FLER_ENABLE_UNICORN 条件编译）
    target_compile_definitions(fler_jni PRIVATE FLER_ENABLE_UNICORN)
endif()
target_link_libraries(fler_jni ... unicorn)   # ENABLE_UNICORN 时
```

`unicorn_jni.cpp` 用 `#ifdef FLER_ENABLE_UNICORN` 包裹全部实现，关闭时提供空 JNI stub（返回 0/false），保证编译不断。

### M1.3 elf_parser 增加 PT_LOAD 段解析

`elf_parser.h` 新增：
```cpp
struct ProgramHeader {
    uint32_t type = 0;       // PT_LOAD=1
    uint32_t flags = 0;      // PF_X=1 PF_W=2 PF_R=4
    uint64_t offset = 0;
    uint64_t vaddr = 0;
    uint64_t paddr = 0;
    uint64_t filesz = 0;
    uint64_t memsz = 0;
    uint64_t align = 0;
};

// ElfParser 类内新增：
std::vector<ProgramHeader> getProgramHeaders() const;
std::vector<ProgramHeader> getLoadSegments() const;  // 过滤 PT_LOAD
uint64_t getEntry() const { return entry_; }
```

`elf_parser.cpp`：读 ELF64 header 的 `e_phoff/e_phnum/e_phentsize`，逐项解析 56 字节程序头。

`elf_parser_jni.cpp` + `ElfParserBindings.kt` 新增：
```kotlin
external fun nativeGetLoadSegments(handle: Long): Array<LoadSegment>
// LoadSegment data class: vaddr/offset/filesz/memsz/perms(int)
```

### 验收
- `:app:compileDebugKotlin` + `assembleDebug` 通过（unicorn 已链接但尚无调用方，体积增长可观察）。
- 单测/instrumented：对 libapp.so 调 `nativeGetLoadSegments` 返回 ≥1 个段且 vaddr/filesz 合理（与 `readelf -l` 对比）。

---

## M2 native 层 unicorn_jni.cpp（3 天）

### M2.1 EmuContext 设计

```cpp
struct EmuContext {
    uc_engine* uc = nullptr;
    std::set<uint64_t> breakpoints;
    std::atomic<bool> stopRequested{false};
    std::atomic<long long> instrCount{0};
    long long instrLimit = 0;
    long long deadlineMs = 0;               // 超时墙钟（0=不限）
    uint64_t sentinelAddr = 0;              // callFunction 返回哨兵
    int stopReason = 0;                     // 映射 StopReason.ordinal
    std::string lastError;
    std::mutex opMutex;                     // 串行化 run/step（含 setjmp 风险兜底）
};
```

### M2.2 JNI 方法表（全量）

| native 方法 | 签名 | 说明 |
|---|---|---|
| `nativeOpen(path, loadBase)` | `(jstring, jlong) → jlong` | 见 M2.3 流程 |
| `nativeClose(handle)` | `(jlong) → void` | `uc_close` + delete context |
| `nativeMapMemory` | `(jlong, jlong, jlong, jint) → jboolean` | `uc_mem_map`（perms 位 r=1 w=2 x=4） |
| `nativeReadMemory` | `(jlong, jlong, jint) → jbyteArray` | `uc_mem_read` |
| `nativeWriteMemory` | `(jlong, jlong, jbyteArray) → jboolean` | `uc_mem_write` |
| `nativeReadRegister` | `(jlong, jint) → jlong` | regId = Unicorn 寄存器枚举值 |
| `nativeWriteRegister` | `(jlong, jint, jlong) → jboolean` | |
| `nativeReadAllRegisters` | `(jlong) → jlongArray` | x0-x30 + sp + pc = 33 值 |
| `nativeRun` | `(jlong, jlong, jlong) → jintArray` | 返回 [pc_lo,pc_hi,count,stopReason] |
| `nativeStep` | `(jlong) → jintArray` | 同上（limit=1） |
| `nativeSetPc` | `(jlong, jlong) → void` | |
| `nativeAddBreakpoint` | `(jlong, jlong) → jboolean` | context.breakpoints.insert |
| `nativeRemoveBreakpoint` | `(jlong, jlong) → jboolean` | |
| `nativeListBreakpoints` | `(jlong) → jlongArray` | |
| `nativeSetSentinel` | `(jlong, jlong) → void` | 设置返回哨兵地址 |
| `nativeRequestStop` | `(jlong) → void` | stopRequested=true（跨线程取消） |
| `nativeLastError` | `(jlong) → jstring` | uc_err → 可读文本 |

### M2.3 nativeOpen 流程（关键）

```
1. uc_open(UC_ARCH_ARM64, UC_MODE_ARM, &uc)
2. ElfParser::open(path) → getLoadSegments()
3. 对每个 PT_LOAD 段：
   - base = loadBase>0 ? loadBase + seg.vaddr : seg.vaddr
   - uc_mem_map(uc, page_align(base), page_align_up(memsz), perms)
   - uc_mem_write(uc, base, file+seg.offset, filesz)   // 从磁盘读（含用户补丁，§6.2）
4. 栈：uc_mem_map(0x40000000, 1MB, RW)；SP = 0x40000000 + 1MB - 0x100
5. 栈顶写哨兵返回地址：mem_write(SP, 0xDEADBEEF0000)，sentinelAddr = 0xDEADBEEF0000
6. heap 预留：uc_mem_map(0x50000000, 8MB, RW)（供字符串参数等手动写入）
7. uc_hook_add(UC_HOOK_CODE, codeHookCallback)
8. 返回 reinterpret_cast<jlong>(context)；失败清理返回 0
```

### M2.4 codeHookCallback（每指令触发）

```cpp
static void codeHook(uc_engine* uc, uint64_t addr, uint32_t size, void* user) {
    auto* ctx = static_cast<EmuContext*>(user);
    // 优先级：取消 > 哨兵 > 断点 > 计数上限 > 超时
    if (ctx->stopRequested.load())            { stop(ctx, uc, INTERRUPTED); return; }
    if (ctx->sentinelAddr && addr == ctx->sentinelAddr) { stop(ctx, uc, FUNCTION_RETURN); return; }
    if (ctx->breakpoints.count(addr))         { stop(ctx, uc, BREAKPOINT); return; }
    if (ctx->instrLimit && ++ctx->instrCount > ctx->instrLimit) { stop(ctx, uc, COUNT_LIMIT→NONE); return; }
    if (ctx->deadlineMs && now() > ctx->deadlineMs) { stop(ctx, uc, TIMEOUT); return; }
}
// stop(): uc_emu_stop(uc); ctx->stopReason = reason;
```

### M2.5 线程安全

- `nativeRun/nativeStep` 入口 `std::lock_guard<std::mutex>(ctx->opMutex)`——兜底 Unicorn 内部 `setjmp/longjmp` 非线程局部的风险（§计划 M2 警告项）。
- `nativeRequestStop` 用 atomic，供 UI 取消按钮跨线程调用。

### M2.6 UnicornBindings.kt

仿 `RizinBindings` 的 object：`external fun` 声明 + `init { NativeLoader.load() }`（libfler_jni 已加载，无需二次 load）。
寄存器名 → regId 映射表放在 Kotlin 层（`"x0"→UC_ARM64_REG_X0` … `"sp"→UC_ARM64_REG_SP`、`"pc"→UC_ARM64_REG_PC`），native 只收 int regId，避免 JNI 字符串开销。

### 验收
- `assembleDebug` 通过。
- adb 手动 smoke（临时入口或 instrumentation）：open 测试 so → mapMemory → writeMemory 写入 `mov x0,#3; ret` 机器码 → setPc → run → x0==3、stopReason 正确。

---

## M3 UnicornEngine + EmulationSession（2 天）

### M3.1 EmulationEngine.kt 小改

`StopReason` 增加 `FUNCTION_RETURN`（哨兵返回）。

### M3.2 UnicornEngine.kt

```kotlin
class UnicornEngine : EmulationEngine {
    override val engineId = "unicorn"
    override val displayName = "Unicorn v2.0.1"
    override val isAvailable = true        // ENABLE_UNICORN 编译期已保证符号存在
    override val capabilities = setOf(INSTRUCTION_EMU, CODE_HOOK, MEMORY_HOOK, SINGLE_STEP)
    // open/close/mapMemory/readMemory/writeMemory/readRegister/writeRegister/
    // readAllRegisters/run/step/setPc/breakpoint* → 全部转发 UnicornBindings
    // loadLibrary → null（Unicorn 无动态链接，M2.3 已手工 map）
    // run(): nativeRun 返回数组 → EmuStepResult(pc, count, readAllRegisters(), stopReason)
}
```
所有方法在 `Dispatchers.Default` 上执行（JNI 阻塞调用），engine 内部对同一 handle 用 Mutex 串行。

### M3.3 EmulationSession.kt（新）

```kotlin
@Singleton
class EmulationSession @Inject constructor(
    private val registry: EngineRegistry,
    private val analysisSession: AnalysisSession
) {
    // 会话管理
    suspend fun openForPath(filePath: String, options: EmulationOptions = EmulationOptions()): OpenResult
    suspend fun close(filePath: String)
    suspend fun closeAll()

    // 符号解析（复用分析会话的符号缓存）
    suspend fun resolveFunction(filePath: String, name: String): Long?

    // 高层调用（核心 API）
    suspend fun callFunction(filePath: String, funcName: String, args: List<Long>): CallResult
    // 流程：resolveFunction → setPc(vaddr) → args 写 x0-x7 →
    //       run(timeout=30s) → FUNCTION_RETURN 时读 x0 作为返回值

    // 低层转发
    suspend fun run/step/setPc(filePath, ...)
    suspend fun setRegister/readRegister/readAllRegisters(filePath, ...)
    suspend fun readMemory/writeMemory/mapMemory(filePath, ...)
    suspend fun addBreakpoint/removeBreakpoint/listBreakpoints(filePath, ...)
    fun requestStop(filePath: String)          // 非挂起，UI 取消按钮用

    // 快照（§M3 补充项）：filePath → EmuSnapshot LRU≤3
    // 快照含寄存器/断点/PC/trace 开关 + writeMemory diff map（页级）
}
data class CallResult(val success: Boolean, val returnValue: Long?, val stopReason: StopReason,
                      val instrCount: Long, val error: String? = null)
```

- 会话 map：`filePath → EmulationHandle`，重复 open 同路径复用（与 AnalysisSession 语义一致）。
- 引擎选择：`registry.pickEmulationFor(INSTRUCTION_EMU)`（unidbg 就绪后按优先级自动切换）。

### M3.4 DI 注册

`AnalysisModule`：
```kotlin
reg.registerEmulation(UnicornEngine(), EmulationEnginePriority.UNICORN)  // 替换 UnicornEnginePlaceholder()
// UnidbgEnginePlaceholder 保留（用户界定：占位暂不动）

@Provides @Singleton
fun provideEmulationSession(registry: EngineRegistry, analysisSession: AnalysisSession): EmulationSession
```

### M3.5 单测

- `EmulationSessionTest`：快照 LRU 淘汰、diff map 合并逻辑（mock engine）。
- 寄存器名映射表完整性（x0-x30/sp/pc/fp/lr 别名）。

### 验收
- `list_engines`（MCP）显示 unicorn `isAvailable=true`。
- instrumentation：callFunction("add",[1,2]) == 3（测试 so 由 M6 提供，此阶段可用临时 adb push 验证）。

---

## M4 UI：EmulationTab（3 天）

### M4.1 状态与 ViewModel

`SoEditorViewModel.EditorTab` 增加 `EMULATION`（ordinal 末尾，不影响已有 tab 持久化索引语义）。

`EmulationViewModel`（@HiltViewModel，注入 EmulationSession + SoEditorCache）：

```kotlin
data class EmulationUiState(
    val isSessionOpen: Boolean = false,
    val isRunning: Boolean = false,
    val executedCount: Long = 0,
    val selectedFunctionName: String = "",
    val argInputs: List<String> = List(8) { "" },   // hex/dec 文本
    val lastCallResult: CallResult? = null,
    val registers: RegisterSnapshot? = null,
    val breakpoints: List<Long> = emptyList(),
    val logs: List<String> = emptyList(),           // 环形上限 200 条
    val errorMessage: String? = null
)
```

方法：`openSession(filePath)` / `selectFunction(name)` / `callSelectedFunction()` / `runFromPc(count)` / `stepOnce()` / `requestStop()` / `toggleBreakpoint(addr)` / `setRegisterValue(name, text)` / `readMemoryRange(addr, size)`。

- `isRunning` 期间禁用 Run/Step/callFunction 按钮（防重入）；取消按钮调 `requestStop`。
- run 带 `instrCount` 时把 `executedCount` 节流更新到 state（每 100ms 一次，避免高频重组）。

### M4.2 EmulationTab.kt 布局

```
┌ 函数选择行：OutlinedTextField(可搜索下拉) + 参数输入 8 格 + [Call] 按钮
├ 控制行：[Run] [Step] [Stop] | 断点输入 + [Add] | 执行计数 / 停止原因 chip
├ 结果区：返回值 x0 高亮卡片 + 全寄存器 LazyGrid（33 项，点击可编辑）
├ 内存查看：地址 + 长度输入 → hex dump（复用 hex tab 的 HexRow 组件）
└ 日志区：LazyColumn(logs)，自动滚底
```

- 首次进入 tab 且未 open 会话 → 自动 `openSession(currentFilePath)`（IO 协程 + loading 态）。
- 进度反馈：run 期间显示 `LinearProgressIndicator`（indeterminate）+ 已执行指令数文本（§6.5）。

### M4.3 接线

`SoEditorScreen.kt` 与 `SoEditorDetailScreen.kt`：
- tab 栏增加 `EditorTab.EMULATION` 项（图标 `Icons.Default.PlayArrow`，文案"仿真"）。
- 内容分支增加 `EditorTab.EMULATION -> EmulationTab(viewModel = hiltViewModel(), filePath = uiState.filePath)`。

### 验收
- 打开 libapp.so → 仿真 tab 自动开会话；选择纯函数 → Call 返回正确值；Step 单步寄存器变化正确；断点命中停止；Stop 按钮可中断长 run。

---

## M5 MCP：emu_* 工具（1 天）

`EngineMcpToolRegistry.buildTools()` 追加（复用现有 schema 辅助函数）：

| 工具 | 参数 | 说明 |
|---|---|---|
| `emu_open` | `so_path` | 开仿真会话 |
| `emu_close` | `so_path` | |
| `emu_call_function` | `so_path, name, args[]` | 高层调用（最常用） |
| `emu_run` | `so_path, count?, timeout_ms?` | |
| `emu_step` | `so_path` | |
| `emu_stop` | `so_path` | 中断 |
| `emu_set_pc` / `emu_set_register` / `emu_read_registers` | | |
| `emu_read_memory` / `emu_write_memory` / `emu_map_memory` | | |
| `emu_breakpoint_add` / `emu_breakpoint_remove` / `emu_breakpoint_list` | | |

- handler 放 `McpToolHandlers.kt`，全部转发 `EmulationSession`。
- `emu_run` 支持 `timeout_ms`（默认 30000），超时返回 `stopReason=TIMEOUT` 而非阻塞（§6.5）。
- `list_engines` 已自动含 emulation 段，无需改。

### 验收
MCP 客户端（curl/inspector）走通 `emu_open → emu_call_function("add",[1,2]) → 返回 3 → emu_close`。

---

## M6 真机回归验证（1.5 天）

### M6.1 测试 so 构建

`scripts/build-test-so.sh`（NDK clang，arm64）：
```c
long add(long a, long b) { return a + b; }
long sum_array(const long* arr, int n) { long s=0; for(int i=0;i<n;i++) s+=arr[i]; return s; }
size_t str_copy(char* dst, const char* src) { ... }   // 指针参数场景
double fma_test(double a,double b,double c){ return a*b+c; }  // 浮点（第二版，先验证不崩）
```
`-O2 -shared -o libfler_emu_test.so`，adb push 到 `/sdcard/`。

### M6.2 测试用例清单

| # | 用例 | 预期 |
|---|---|---|
| 1 | call add(1,2) | 返回 3，stopReason=FUNCTION_RETURN |
| 2 | sum_array：writeMemory 写数组 → 传指针 | 返回和 |
| 3 | str_copy：目标 buffer 预写 0 → 调用 → readMemory | dst 含 src 内容 |
| 4 | 断点：add 内第二条指令 | stopReason=BREAKPOINT，PC 精确 |
| 5 | Step ×10 | 指令数=10，寄存器连续变化 |
| 6 | 死循环 so + timeout=2000 | stopReason=TIMEOUT，进程不卡死 |
| 7 | 跳入未映射地址 | stopReason=ERROR + 可读错误（不 crash） |
| 8 | 两个会话 open 不同 so | 互不串扰（§5.4 并发项） |
| 9 | open→close 循环 ×50 | 无 native 泄漏（logcat 无持续增长） |
| 10 | Hex tab 补丁后 open 仿真 | 仿真内存含补丁字节（§6.2） |
| 11 | 回归：结构/hex/disasm/补丁/MCP engine_* | 全部不受影响 |

### M6.3 性能基线
记录：1000 条指令 run 耗时、libapp.so（~15MB）open 耗时。写入本文档作为后续回归基线。

---

## 时间估算与依赖

| 阶段 | 工作量 | 前置依赖 | 可并行 |
|---|---|---|---|
| M0 准备 | 0.5d | 无 | — |
| M1 静态库+程序头 | 1d | M0 | M1.3（elf_parser）可与 M1.1/1.2 并行 |
| M2 native 层 | 3d | M1 | — |
| M3 引擎+会话 | 2d | M2 | M3.5 单测可与 M4 并行 |
| M4 UI | 3d | M3 | — |
| M5 MCP | 1d | M3 | 可与 M4 并行 |
| M6 回归 | 1.5d | M4+M5 | — |
| **合计** | **≈12d** | | |

第七期（unidbg spike）独立排期：PoC 2d → 通过则全量集成 5-8d；失败则按调研文档降级方案。

---

## 回滚策略

1. **编译级**：`-DENABLE_UNICORN=OFF` → unicorn_jni.cpp 编译为空 stub，APK 不含 Unicorn 代码。
2. **运行级**：`UnicornEngine.isAvailable` 改为读 `BuildConfig`/设置项，可热关闭（引擎回退为占位，仿真 tab 显示"未启用"）。
3. **代码级**：所有改动集中在新增文件 + 6 处小接线点，git revert 单 commit 即可完整移除。
