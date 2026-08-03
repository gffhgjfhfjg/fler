# Unicorn / unidbg 仿真引擎集成调研与计划

> 状态：调研完成，方案确认，**尚未开始开发**。
> 范围：在 fler 中引入 Unicorn（v2.0.1）与 unidbg（v0.5.0，spike 先行）两个仿真引擎，
> 复用现有 `EmulationEngine` 抽象与 `EngineMcpToolRegistry`，UI 与 MCP 均无需重构即可消费新引擎能力。

---

## 一、调研结论

### 1.1 方向确认

| 决策点 | 结论 |
|---|---|
| 目标能力 | 两者都要：指令级仿真（Unicorn）+ 调用含 JNI 依赖的函数（unidbg） |
| unidbg 推进方式 | **先 spike 后集成**（arm64 真机 PoC 验证 ART 兼容性，通过再全量） |
| APK 体积权衡 | **仿真优先，暂缓瘦身**（瘦身 A 档推迟到仿真稳定后） |
| M4 UI 结构 | 独立 `EmulationViewModel`（不并入 SoEditorViewModel） |

### 1.2 Unicorn Engine（可做，成本低）

- **本质**：CPU 级仿真器（C 库，QEMU TCG 解释执行）。
- **构建**：`unicorn-engine/unicorn` v2.0.1 官方支持 NDK cmake 交叉编译 arm64-v8a；产物 `libunicorn.a` 静态链接进 `libfler_jni.so`，与现有 Rizin/Capstone/Keystone 工作流完全一致。
- **能力**：寄存器/内存读写、指令级 code/mem hook、单步、断点——正好匹配现有 `EmulationEngine` 接口。
- **局限**：不含 ELF 加载器/动态链接/JNI/Android 框架；**只适合纯计算函数**（无 JNI、无 syscall 依赖）。
- **集成路径**：C API（`uc_open/uc_mem_map/uc_emu_start/uc_hook_add`）+ JNI 桥。无 JNA/ART 兼容风险。

### 1.3 Unidbg（能做，但高风险）

- **本质**：纯 Java 框架（`unidbg-api` + `unidbg-android`，231+ java 文件），在 Unicorn/Unicorn2(经 JNA)/Dynarmic 后端之上实现：ELF 加载器+动态链接器、syscall 处理、DalvikVM/JNIEnv 模拟（约 150 个 JNI 函数）、AndroidResolver 系统库、Android 框架 stub、GDB stub、指令 trace。
- **On-device 可行性**：官方与社区均在**桌面 JVM** 运行；Android ART 内无成熟案例。风险点：
  1. Unicorn 后端经 **JNA** 绑定（`libjnidispatch.so`，需 `net.java.dev.jna:jna@aar`，有配置坑）；
  2. 传递依赖大（slf4j/commons-io/apache commons/xz 等），R8 需 keep 规则；
  3. ART 与桌面 JVM 的 API 差异（logging、java.util/java.nio 用法）需实测；
  4. Maven 中央仓更新滞后，作者推荐 git clone 源码（submodule 或 vendored）。
- **对策**：先做 **PoC spike**——arm64 真机上用 `AndroidEmulatorBuilder.for64Bit() + Unicorn2Factory + AbstractJni` 跑通一个 `Java_xxx` 签名函数，验证 ART 内可运行，再决定全量集成。
- **后端选择**：分析场景用 **Unicorn2**（全 hook/trace）；Dynarmic 无指令级 hook，不适用。

### 1.4 原规划修正

`rizin-integration-plan.md` 中 "unidbg 编译为 `.so` + `unidbg_jni.cpp`" 的前提**错误**。
unidbg 是 **Java 库**，不是 C 库；其原生部分仅是 CPU 后端（JNA 绑定的 libunicorn / libdynarmic）。

### 1.5 现状核对（代码已确认）

| 项 | 现状 |
|---|---|
| `EmulationEngine` 接口 | 已完整（`EmulationEngine.kt:76-125`） |
| 占位引擎 | `UnicornEnginePlaceholder` / `UnidbgEnginePlaceholder`（`isAvailable=false`） |
| 注册 | `AnalysisModule.kt:49-56`，优先级 UNICORN=50 / UNIDBG=100 |
| 原生栈 | `libfler_jni.so` 静态链接 keystone/capstone/26×`librz_*.a`；`NativeLoader.kt:26` 启动加载 |
| 静态库 | `app/libs/arm64-v8a/`（gitignored，本地/CI 交叉编译）；头文件 vendor 入库（`include/capstone`、`include/rizin` 模式） |
| UI tab | `EditorTab` 枚举 `SoEditorViewModel.kt:753`；tab 栏 `SoEditorScreen.kt:377-402`、`SoEditorDetailScreen.kt:284-300`；内容分支 `SoEditorScreen.kt:416-450`、`SoEditorDetailScreen.kt:321-349` |
| MCP | `EngineMcpToolRegistry.buildTools()` 暴露 `engine_*`；`list_engines` 已含 emulation 段 |
| 缺口 | `AnalysisSession` 只管分析引擎，**无仿真会话管理** → 需新增 `EmulationSession` |

---

## 二、集成计划

### 里程碑总览

| 里程碑 | 内容 | 交付 |
|---|---|---|
| M1 | Unicorn 静态库 + CMake 集成 | 编译通过 |
| M2 | native 层 `unicorn_jni.cpp` + `UnicornBindings.kt` | 原生 smoke |
| M3 | `UnicornEngine` 替换占位 + `EmulationSession` | 引擎可用 |
| M4 | UI 仿真 tab（独立 `EmulationViewModel` + `EmulationTab`） | UI 可用 |
| M5 | MCP `emu_*` 工具 | MCP 可用 |
| M6 | 真机回归验证 | 验收通过 |

### M1 前置：Unicorn 静态库

- 用 NDK cmake 交叉编译 `unicorn-engine/unicorn` v2.0.1（仅 arm64，`UNICORN_ARCH_ARM64`，shared=OFF）→ `libunicorn.a` 放 `app/libs/arm64-v8a/`（gitignored）。
- vendor 头到 `app/src/main/cpp/include/unicorn/`（提交入库，仿 capstone/rizin 模式）。
- `scripts/build-unicorn.ps1` 记录构建步骤。
- `CMakeLists.txt`：`include_directories(include/unicorn)` + `add_library(unicorn STATIC IMPORTED)` + `fler_jni` 链接 + 源列表加 `jni_bridge/unicorn_jni.cpp`；加 `option(ENABLE_UNICORN ON)` 与 `FLER_ENABLE_UNICORN` 宏，便于日后瘦身关闭。

### M1 前置依赖：ELF PT_LOAD 段解析接口

现有 `elf_parser` 仅解析 Section（节头表）与 Symbol（.symtab/.dynsym），**缺少程序头（Program Header）解析**。Unicorn 映射 ELF 到仿真内存需要 PT_LOAD 段信息（vaddr/offset/filesz/memsz/flags），需新增：

- `elf_parser.h`：`ElfProgramHeader` 结构体（`type/offset/vaddr/paddr/filesz/memsz/flags/align`）+ `getProgramHeaders()/getLoadSegments()` 方法
- `elf_parser.cpp`：解析 ELF64 Program Header Table（`e_phoff/e_phnum`），过滤 `PT_LOAD` 段
- `ElfParserBindings` / `elf_parser_jni.cpp`：新增 `nativeGetLoadSegments(handle)` — 返回段数组（vaddr/offset/memsz/filesz/prot）
- JNI 桥：`unicorn_jni.cpp` 调用此接口拿到段列表 → `uc_mem_map` + `uc_mem_write` 逐段载入

### M2 native 层：`cpp/jni_bridge/unicorn_jni.cpp`

每个 uc_handle 一个上下文结构，句柄 = 指针值。native 方法：

- `nativeOpen(path, loadBase)`：创建 `UC_ARCH_ARM64` 句柄，解析 ELF **PT_LOAD 段**（复用/扩展 `elf_parser`）→ `uc_mem_map` + 写入；映射默认栈（如 0x40000000, 1MB）与 heap 预留；失败返回 0。
- `nativeClose` / `nativeMapMemory` / `nativeWriteMemory` / `nativeReadMemory`。
- `nativeRead/WriteRegister`（x0-x30/sp/pc）、`nativeReadAllRegisters`（固定 32 值数组）。
- **浮点寄存器**：`UC_ARM64_REG_V0–V31` / `UC_ARM64_REG_FPCR` / `UC_ARM64_REG_FPSR`（ARM64 调用约定 d0-d7 传浮点参数、d0 返回；第一版可仅暴露读/写，第二版接入浮点传参）。
- `nativeRun(handle, instrCount, timeoutMs)`：`UC_HOOK_CODE` 计数 + 断点匹配 + 超时 → 返回 {pc, count, stopReason}。
- `nativeStep`（HOOK_CODE 内 `uc_emu_stop`）、`nativeSetPc`、断点增删/列表。
- `UC_HOOK_MEM_READ/WRITE` trace 开关；`uc_err` 转可读字符串。
- 未映射内存访问 → 抛清晰错误（纯函数边界）。
- 线程模型：与 Rizin 一致，`uc_emu_start` 同步执行，同 handle 操作串行。
- **⚠️ Unicorn `uc_emu_start` 内部使用 `setjmp/longjmp` 实现 timeout**。若多线程并发仿真（同进程、不同 handle），`jmp_buf` 必须是线程局部存储，否则与 blutter 此前 `static sigjmp_buf` 的并发崩溃同源。方案：确认 Unicorn 2.0.1 在 NDK 构建下 `uc_emu_start` 是否已 `thread_local`；若无，JNI 层加 `std::mutex` 串行化所有 `nativeRun/nativeStep`。

### M3 Kotlin 绑定 + 引擎

- `UnicornBindings.kt`（仿 `RizinBindings.kt` 的 object，TAG=Unicorn）。
- `UnicornEngine.kt` 替换占位：`isAvailable=true`；内部 mutex 串行化；run/step 把 native stopReason 映射为 `StopReason`；`loadLibrary` 返回 null（Unicorn 手工 map）。
- **新增 `EmulationSession`**（@Singleton）：复用 UI 已 open 的 `AnalysisSession.getSymbols()` 拿 `name→vaddr`；封装 `openForPath / close / resolveFunction / callFunction(name,args) / setRegister / readRegisters / run / step / breakpoint* / readMemory / writeMemory`——UI 与 MCP 统一入口。
  - **仿真状态快照**：内部维护 `filePath → EmuSnapshot` 映射（LRU ≤3），快照含寄存器快照、断点列表、上次 PC、trace 开关。不保存全量内存（重新映射），仅保存用户手动 `writeMemory` 修改过的页（diff map）。用户切 tab 后返回仿真 tab 时自动恢复，避免状态丢失。
  - **磁盘一致性**：`EmulationSession.writeMemory(addr, data)` 仅写仿真内存（uc 内部），不触发 `BackupManager`；如需写回磁盘（如"应用仿真结果到 SO"），调用 `AnalysisSession.writeBytes()` 走完整撤销栈。
- `AnalysisModule` 注册 `EmulationSession`。

### M4 UI：仿真 tab（独立 EmulationViewModel）

- `EditorTab` 加 `EMULATION`；`SoEditorScreen.kt` 与 `SoEditorDetailScreen.kt` 的 tab 栏/内容分支各加一处。
- 新文件 `EmulationTab.kt` + **独立 `EmulationViewModel`**：
  - 函数选择（FUNC 符号下拉/搜索）→ 参数输入（≤8 个 hex/dec，或字符串自动写内存传指针）→ 内存设置辅助。
  - 按钮：Run / Step / 断点增删 / 暂停；结果区：x0 返回值、全寄存器表、内存查看、指令 trace 列表、执行日志（含停止原因）。
  - `isRunning` 防重入。

### M5 MCP：`emu_*` 工具（`EngineMcpToolRegistry.buildTools()` 追加）

`emu.open / emu.close / emu.map_memory / emu.set_register / emu.read_register / emu.read_registers / emu.write_memory / emu.read_memory / emu.run / emu.step / emu.set_pc / emu.breakpoint_add|remove|list / emu.call_function(name,args)`，复用现有 schema 辅助（`strOrLongType/strType/intType`）。

### M6 真机回归验证

- 构造/选取无外部依赖的 arm64 测试 so：`add(a,b)`、字符串处理、AES 等纯函数。
  - `call_function("add",[1,2])` 返回 3；单步指令数与寄存器正确；断点命中 `BREAKPOINT`；写内存字符串→函数读回。
- 带 JNI/外部调用 → 返回 `ERROR`（预期，Unicorn 边界，留给 unidbg）。
- 回归：结构/hex/disasm/补丁不受影响；`engine.list_engines` 显示 unicorn `isAvailable=true`。

---

## 三、边界与风险

| 风险 | 缓解 |
|---|---|
| unidbg ART 兼容性（JNA/日志/java.nio） | spike 先行验证；JNA aar 正确配置；R8 补 keep 规则 |
| Unicorn 仅限纯计算函数 | UI/MCP 描述注明；未映射地址异常给清晰报错 |
| 调用约定 | 第一版 x0-x7 整数传参、x0 返回；浮点 d0-d7 第二版 |
| APK 体积增长（Unicorn +2~4MB；unidbg +5~20MB） | `ENABLE_UNICORN / ENABLE_UNIDBG` 编译开关默认关、发布择一；仿真稳定后再做瘦身 A 档 |
| 静态库构建环境 | 本机 NDK 交叉编译脚本 + gitignored libs 目录（与 keystone/capstone/rizin 一致） |
| Unicorn `setjmp/longjmp` 多线程并发 | 与 blutter 此前 `static sigjmp_buf` 同源风险；确认 Unicorn 2.0.1 NDK 构建是否 `thread_local`，否则 JNI 层 `std::mutex` 串行化 `nativeRun/nativeStep` |
| JNA `libjnidispatch.so` 与已有 JNI 库符号冲突 | 独立 `System.load()` 预加载顺序；R8/proguard 需 keep JNA 反射类（`com.sun.jna.*`） |

---

## 四、后续（v0.5.0）unidbg Spike

1. 独立分支/模块：最小 PoC，引入 unidbg（git submodule 优先）+ `net.java.dev.jna:jna@aar` + libunicorn，用 bhamza `HelloSignJNI` 模式在 arm64 真机 ART 内跑通 `Java_xxx` 签名调用。
2. 验证点：ART 兼容（jnidispatch 加载、java.util.logging/java.nio 用法）、`JNI_OnLoad`、DalvikVM、`AndroidResolver` 系统库。
3. **通过** → 全量集成 `UnidbgEngine`（直接调 unidbg Java API，无需 unidbg JNI；经 `EmulationSession` 隔离，UI/MCP 复用）。
4. **失败** → 降级为自建最小 JNIEnv/DalvikVM stub，或仅交付 Unicorn。

---

## 五、补充分析

### 5.1 Rizin 分析→仿真协作路径

当前 Rizin 分析产出（函数边界/CFG/xref）未流入仿真。可做两处协作：

- **最小映射范围**：`callFunction(name, args)` 时，用 Rizin `afbj`（basic block JSON）分析目标函数，**只映射该函数及被调用子函数所需的最小内存页**，而非 PT_LOAD 全量映射。对超大 .so（20MB+）有显著加速，同时天然提供仿真边界（函数外的未映射地址自动触发 `UC_ERR_FETCH_UNMAPPED` → 清晰报错）。
- **跳过系统调用**：Rizin `isj` 标记 import 函数（`memcpy`/`open`/`malloc` 等），仿真时遇 `call → import stub` 自动跳过或 stub 替换（返回 0 / -1），而非触发 `UC_ERR_FETCH_UNMAPPED`。第一版可跳过前 20 个高频 import。

### 5.2 unidbg JNA 集成细节展开

计划 1.3 提到"JNA aar 有配置坑"，具体风险与对策：

| 风险 | 对策 |
|---|---|
| `net.java.dev.jna:jna:5.14.0@aar` 含 `libjnidispatch.so`（arm64-v8a），需确认与 `libfler_jni.so` 无符号冲突 | 对比 `readelf -s` 导出符号表；如有冲突，改 unidbg 后端为纯 Unicorn2 JNI（绕开 JNA）或静态剥离冲突符号 |
| JNA `Native.load()` 在 Android `/data/app/` 路径下可能加载失败 | `NativeLoader` 显式 `System.loadLibrary("jnidispatch")` 预加载，不依赖 JNA 自动发现 |
| unidbg 依赖 slf4j → Android 无默认实现，日志静默丢失或 `ClassNotFoundException` | 桥接 `slf4j-android` 或自定义 `ILoggerFactory` 转发到 `android.util.Log`（TAG=Unidbg） |
| unidbg 源码 `java.util.logging` 用法在 ART 上行为差异 | spike 阶段用 `UnidbgLogger.setLevel(OFF)` 关闭，待验证后逐模块开启 |

### 5.3 内存工具增强

计划 M5 MCP 工具缺少以下仿真专属能力：

- **`emu.mem_search(pattern, start, end)`**：仿真内存模式搜索（hex 字符串 / UTF-8 字符串 / little-endian int32），类似 Cheat Engine。Native 层 `uc_mem_read` + KMP/Boyer-Moore 匹配。
- **`emu.hook_add(type, addr, callback)`**：不限于断点（exec hook）。`UC_HOOK_MEM_READ/WRITE` 可实现**内存污点追踪雏形**（标记某地址被写过 → 触发回调）。第一版暴露 code hook（覆盖率）+ mem write hook（定位哪个函数写了某地址）。
- **UI 侧**：`EmulationTab` 加"内存映射图"（类似 Rizin `dm` 命令的可视化块），显示已映射区域（stack/code/heap/用户手动 map）的起止与权限。

### 5.4 测试策略补充

M6 仅覆盖功能正确性（add/AES 纯函数），建议追加：

| 测试项 | 验证内容 |
|---|---|
| 性能基准 | 仿真 1000 条 ARM64 指令耗时（目标 <50ms），作为回归基线 |
| 内存泄漏 | `open → run → step×100 → close` 循环 100 次，`dumpsys meminfo` 确认 RzCore 无堆积 |
| 异常路径 | 非法 PC（跳入未映射地址）、死循环（超时 >5s）、`uc_mem_map` 重叠区域 → 返回清晰错误码而非 crash |
| 并发 | 两个 `EmulationSession` 同时 open 不同 SO → 互相隔离、不串扰（验证 `std::mutex` 或 `thread_local` 生效） |

### 5.5 补充项优先级

| 优先级 | 内容 | 阻塞里程碑 |
|---|---|---|
| **P0** | ELF PT_LOAD 段解析接口（§M1 前置依赖） | M2 |
| **P0** | Unicorn `setjmp/longjmp` 线程安全确认（§M2） | M2 |
| **P1** | 浮点寄存器读/写（§M2） | M3 |
| **P1** | JNA 集成细节验证（§5.2） | 四、unidbg Spike |
| **P1** | 仿真状态快照（§M3 `EmuSnapshot`） | M4 |
| **P2** | Rizin 最小映射范围（§5.1） | M3 |
| **P2** | 磁盘一致性策略（§M3 writeMemory vs writeBytes） | M3 |
| **P3** | 内存搜索 / hook 增强（§5.3） | M5 |
| **P3** | 性能基准 / 内存泄漏测试（§5.4） | M6 |

---

## 六、进一步补充

### 6.1 栈帧初始化（callFunction 关键细节）

`callFunction(name, args)` 仿真目标函数时，函数 `RET` 指令从栈弹出返回地址。若栈顶无有效返回地址，PC 跳入未映射内存 → `UC_ERR_FETCH_UNMAPPED`。

**方案**：`nativeOpen` 映射栈后，在 SP 位置预写哨兵返回地址（如 `0xDEADBEEF`）。`nativeRun` 的 `UC_HOOK_CODE` 检测 PC == 哨兵地址 → 自动 `uc_emu_stop`，stopReason = `FUNCTION_RETURN`。`EmulationSession.callFunction` 封装此逻辑，UI/MCP 无感知。

### 6.2 补丁-仿真内存一致性

用户在 Hex tab 通过 `AnalysisSession.writeBytes()` 修改字节（BackupManager 撤销栈），切到仿真 tab 时：

- **仿真内存应反映磁盘当前内容（含未撤销补丁）**，与 Rizin 分析视图一致
- `nativeOpen` 从磁盘 `mmap`/`read` 读取文件（非缓存），确保包含最新补丁
- 若用户在仿真中 `writeMemory` 修改了仿真内存，**不回写磁盘**（仅仿真态）；需显式"应用到文件"才触发 `AnalysisSession.writeBytes()`

### 6.3 TLS 寄存器（TPIDR_EL0）

部分 SO 使用 `__thread` 变量，通过 `MRS Xn, TPIDR_EL0` 读取 TLS 基址：

- 第一版：不处理 TLS，遇到 `MRS TPIDR_EL0` 指令时日志警告（不影响纯计算函数）
- 第二版：`nativeOpen` 映射 TLS 块（从 ELF `.tdata`/`.tbss` 段读取），设置 `UC_ARM64_REG_TPIDR_EL0` 指向该块

### 6.4 Dart 调用约定（fler 特殊场景）

fler 核心是 Flutter 逆向，Blutter 分析产出 Dart 方法地址。Dart 编译后的机器码**不遵循标准 AAPCS64**：

- Dart VM 调用约定：R0-R3 传参（与 AAPCS64 相同），但**栈帧布局不同**（Dart 使用 R29/FP 作为帧指针，且局部变量偏移由 Dart 编译器决定）
- Dart 方法可能调用 Dart VM runtime stub（如 `AllocateObject`），这些 stub 不在目标 SO 内 → 仿真必失败
- **建议**：UI 提供"Dart 模式"开关，启用时：(1) 参数传递仍用 x0-x7；(2) 跳过 Dart runtime stub 调用（类似 §5.1 跳过 import）；(3) 结果解释为 Dart Object 指针而非原始值

### 6.5 性能预期与 UI 进度

- Unicorn TCG 解释执行 ARM64 约 **10-100 MIPS**（取决于宿主机 CPU 与指令复杂度）
- 大函数（10000+ 指令）仿真可能耗时 **1-10 秒**
- M4 `EmulationTab` 需要：
  - 进度指示器（已执行指令数 / 预估总数）
  - 取消按钮（调用 `uc_emu_stop` 中断）
  - 超时保护（默认 30s，可配置）
- MCP `emu.run` 需支持 `timeout` 参数，超时返回 `TIMEOUT` stopReason 而非阻塞
