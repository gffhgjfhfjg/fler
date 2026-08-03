# Unicorn / unidbg 集成开发进度

> 配套文档：[unicorn-unidbg-dev-plan.md](unicorn-unidbg-dev-plan.md)（开发方案）、[unicorn-unidbg-integration-plan.md](unicorn-unidbg-integration-plan.md)（调研）
> 状态图例：⬜ 未开始 ｜ 🟡 进行中 ｜ ✅ 完成 ｜ ❌ 受阻 ｜ ⏸ 暂缓

## 总览

| 阶段 | 内容 | 状态 | 开始 | 完成 | 备注 |
|---|---|---|---|---|---|
| M0 | 环境 + 源码 + libunicorn.a | ✅ | 2026-08-03 | 2026-08-03 | CI 交叉编译 |
| M1 | 静态库集成 + PT_LOAD 解析 | ✅ | 2026-08-03 | 2026-08-03 | 含 crc32 符号冲突修复 |
| M2 | unicorn_jni.cpp + Bindings | ✅ | 2026-08-03 | 2026-08-03 | |
| M3 | UnicornEngine + DI 注册 | ✅ | 2026-08-03 | 2026-08-03 | |
| M4 | EmulationTab UI | ✅ | 2026-08-03 | 2026-08-03 | |
| M5 | MCP emu_* 工具 | ✅ | 2026-08-03 | 2026-08-03 | |
| M6 | 真机回归验证 | ✅ | 2026-08-03 | 2026-08-03 | 6/6 测试通过 |

---

## 开发日志

### 2026-08-03

#### M0 进行中：构建方案切换 本地 Windows → GitHub Actions 交叉编译

**环境检查结果**：
- NDK 28.2.13676358 ✅、SDK cmake 3.22.1 + ninja ✅、git 2.54 ✅
- 系统 cmake 4.3.1（未用，避 4.x 对 cmake_minimum_required<3.5 的兼容问题）
- 网络：本机 DNS 故障，GitHub 需走 SOCKS5 代理 127.0.0.1:7897（HTTP CONNECT 可用但 schannel TLS 握手失败）

**源码获取**：`git clone --depth 1 --branch 2.0.1` 经 socks5h 代理成功 → `vendor/unicorn-src`（已入 .gitignore `/vendor/`）

**本地构建尝试（失败，已定位根因）**：
1. `scripts/build-unicorn.ps1` + SDK cmake：configure 通过，但 `config-host.h` 未生成 → 编译全挂
2. 根因链：Unicorn CMake 在 configure 期调 `sh qemu/configure` 生成 config-host.h；Windows 上：
   - PATH 无 sh → 手动补 Git 的 sh.exe 后仍失败
   - `qemu/configure` 报 `strings: command not found`、`pkg-config not found`（Git usr/bin 不全）
   - 且位置参数 `aarch64-softmmu,` 被报 `unknown option`（脚本只认 `--target-list=`，CMake 传参方式存疑，上游 CI 均在 macOS/Linux 跑未暴露）
3. 结论：**本地 Windows 构建不可行，切换 CI 交叉编译**（与 keystone/rizin 静态库的既有模式一致）

**新方案（已落地）**：
- 新增 workflow：`E:\111\bitcontrol\out\fler-dart\.github\workflows\build-unicorn.yml`（副本：`scripts/workflow-build-unicorn.yml`）
- 触发：`workflow_dispatch` 手动；输入项：unicorn-version(2.0.1) / unicorn-arch(aarch64) / android-platform(android-26) / ndk-version(r27)
- 产物：artifact `unicorn-static-2.0.1-arm64-v8a`，布局与 rizin 包一致：
  - `lib/arm64-v8a/libunicorn.a` → `app/libs/arm64-v8a/`
  - `include/unicorn/*.h` → `app/src/main/cpp/include/unicorn/`
- 校验步骤：config-host.h 生成检查 + `file` 架构检查 + `nm` 验证 uc_open/uc_emu_start 等 10 个关键符号

**待办**：用户在 GitHub Actions 手动运行 workflow，下载 artifact 解压入库

**已推送**：fler-dart 仓库 commit `6d70112` ci: add manual workflow to cross-compile Unicorn static lib (arm64-v8a) → origin/main（经 socks5h 代理；推送前 rebase 了远端新提交 2253eed，无冲突）

**CI 首跑结果（2026-08-03）**：编译 74/74 全部成功，但打包步骤失败——Unicorn 静态库产物名是 `libunicorn-static.a`（CMakeLists L1380 对静态库设了 OUTPUT_NAME=unicorn-static）而非 `libunicorn.a`。已修复（commit `5c37b18`）：三处引用改为 libunicorn-static.a，打包时 cp 重命名为 libunicorn.a 对齐 fler 布局。待重跑 workflow

**CI 二跑结果（重要发现）**：artifact 到手后本地验证发现问题——`libunicorn-static.a` 是**薄库**（仅 uc.c/vl.c/cpu.c 3 个对象，316KB，67 个符号，无 tcg_*）！Unicorn 的 CMake 用 `bundle_static_library`（ar -M 脚本）把 unicorn 薄库 + unicorn-common + aarch64-softmmu 合并成 build 根目录的 **libunicorn.a**（bundling_target，属默认 all 目标）；之前 `--target unicorn` 只构建了薄库。本地用 llvm-nm 确认下载的库缺 tcg_exec/cpu_exec，链接必失败。

**修复（commit `cbe151a`，已推送）**：
- 构建改默认目标（含 bundling_target），取 build 根目录合并后的 libunicorn.a
- 新增双重防伪校验：归档对象数 ≥30（ar t）+ nm 验证 tcg_exec/cpu_exec 已定义
- 顺带屏蔽上游 QEMU 无害 warning（-Wno-unused-but-set-variable -Wno-unused-variable）

**待办**：用户重跑 workflow（第三次），下载后本地复验（对象数/符号）再入库

**CI 三跑 artifact 入库（2026-08-03，M1 完成）**：
- 复验通过：28.4MB、71 个对象、5998 个定义符号；TCG 核心符号带 `_aarch64` 后缀（tcg_gen_code_aarch64 / cpu_exec_aarch64 等，unicorn 修改版 qemu 防多架构冲突的设计）；未定义符号仅 bionic libc/libm
- 入库：libunicorn.a → app/libs/arm64-v8a/；12 个头文件 → app/src/main/cpp/include/unicorn/
- **链接冲突修复**：首链报 `duplicate symbol: crc32`——unicorn 内嵌 QEMU 的 crc32/crc32_table/sm4_sbox 与 librz_io/librz_crypto 同名。全量符号交集扫描后确认仅这 3 个（capstone/keystone 无冲突）。用 NDK llvm-objcopy --localize-symbol 降为本地后链接成功；同步固化进 CI workflow（commit `20b1c10`，含 TCG 校验符号名修正）

**M1 交付（assembleDebug 构建通过，unicorn 已静态链入 fler_jni.so）**：
1. elf_parser：ProgramHeader 结构 + parseProgramHeaders（带边界防御）+ getProgramHeaders/getLoadSegments/getEntry
2. JNI：nativeGetLoadSegments / nativeGetEntry；Kotlin 侧 ElfLoadSegment data class（含 isExecutable/isWritable/isReadable 便捷属性）
3. CMakeLists：ENABLE_UNICORN 选项 + 库缺失优雅降级（WARNING 不阻断构建）+ FLER_ENABLE_UNICORN 宏注入
4. unicorn_jni.cpp 骨架（能力探测 nativeIsAvailable/nativeGetVersion，#ifdef 降级 stub）+ UnicornBindings.kt 骨架

#### M2 完成：unicorn_jni.cpp 完整实现（522 行）+ UnicornBindings.kt（169 行）

- EmuContext：uc_engine + breakpoints(bpMutex) + opMutex + stopRequested/stopReason(atomic) + instrCount/maxInstrs/deadline
- codeHook 优先级：取消 > 哨兵(0xDEADBEEF0000) > 断点 > 指令计数 > 超时；memInvalidHook 把非法访存从默认"继续执行"改为 ERROR 停止
- 地址空间：栈 0x40000000+1MB（SP 自动指顶）、heap 0x50000000+8MB、哨兵页
- 寄存器：arm64RegId 名字映射（x0-x30/fp/lr/sp/pc/nzcv），readAllRegisters 批量一次 JNI 往返
- run/step 返回 [stopReason, pc, instrCount] 三元组；requestStop 跨线程（uc_emu_stop 线程安全）
- #else 分支全套安全 stub，编译期禁用不产生 UnsatisfiedLinkError

#### M3 完成：UnicornEngine（297 行）+ DI 注册

- UnicornEngine 实现 EmulationEngine 全部接口；suspend 方法统一 withContext(Dispatchers.Default)
- 会话管理：ConcurrentHashMap<handle.value, nativeHandle> + AtomicLong 序号
- open 流程：uc_open → 按 PT_LOAD 页对齐映射（perms=p_flags）→ 写 filesz 字节（BSS 由 uc_mem_map 零填充覆盖）→ PC=e_entry、LR=哨兵
- loadLibrary 支持 baseAddress 重定位（delta = base - minVaddr），返回重定位后入口
- StopReason 新增 FUNCTION_RETURN（ordinal=6 与 native StopCode 严格对齐）
- AnalysisModule 换注册 UnicornEngine；UnicornEnginePlaceholder 删除（Unidbg 占位保留）
- 降级链完整：ENABLE_UNICORN=OFF（编译期）→ isAvailable=false（运行时）→ 注册中心 pickEmulationFor 自动跳过

**M0-M3 全部构建验证通过（assembleDebug），待 M4 UI + M5 MCP + M6 真机回归。**

#### M4 完成：EmulationSession + EmulationViewModel + EmulationTab + 双 Screen 接线

- EmulationSession（253 行，@Singleton 门面）：按 so 路径管理会话（同路径复用）；resolveFunction 三级查找（Rizin 函数表 → 符号表 → ElfParser 兜底，支持 hex 地址直调）；callFunction 高层调用（x0-x7 写参 → LR=哨兵 → PC=函数 → run → 读 x0）；run/step/寄存器/内存/断点直通方法
- EmulationViewModel（312 行，@HiltViewModel）：EmulationUiState（会话/运行/参数/结果/寄存器/断点/日志）；openSession 自动开会话；hex/十进制参数解析；最近 200 条操作日志
- EmulationTab（565 行）：函数调用区（可搜索下拉 + 8 参数）/控制区（Run/Step/Stop + 断点增删 + 状态 chip）/结果区（返回值卡片 + 全寄存器网格点击编辑）/日志区；引擎不可用整页降级提示
- EditorTab 枚举新增 EMULATION；SoEditorScreen + SoEditorDetailScreen 均接入「仿真」Tab（hiltViewModel() 获取独立 EmulationViewModel）
- 修复编译错误：verticalScroll 辅助函数非法写法（非 Composable 上下文调 rememberScrollState）改标准用法；ControlSection 补 @OptIn(ExperimentalLayoutApi)
- assembleDebug 构建通过

#### M5 完成：EmulationMcpToolRegistry（426 行，14 个 emu_* 工具）

- 会话：emu_open / emu_close（soPath 空=关全部）/ emu_list_sessions
- 高层调用：emu_call_function（function 名或 hex 地址，args JSON 数组 hex/十进制，timeoutMs/maxInstrs 可调）；会话未打开时自动 open
- 执行：emu_run / emu_step / emu_stop
- 寄存器：emu_read_registers（全快照 hex）/ emu_write_register
- 内存：emu_read_memory（≤ 64KB）/ emu_write_memory / emu_map_memory（perms 位掩码）
- 断点：emu_breakpoint_add / emu_breakpoint_remove / emu_breakpoint_list
- 全部复用 EmulationSession 门面（与 UI 共享会话表）；修复 suspend 在 buildJsonObject 内调用错误（openPaths 提前取值）
- McpToolHandlers 构造注入 EmulationMcpToolRegistry 并 merge 进 tools；assembleDebug 构建通过

**M0-M5 全部完成，仅剩 M6 真机回归验证。**

#### M6 完成：真机回归 6/6 通过，拦截并修复单步语义 bug

- 冒烟：真机（ec9d5eda，arm64-v8a，Android 16）安装启动，fler_jni 加载成功、无崩溃
- 新增 instrumented test：UnicornEmulationInstrumentedTest（6 用例）——引擎可用/哨兵函数调用（MOV X0,#42; RET → FUNCTION_RETURN，x0=42，指令数=2）/单步 PC 前进/断点命中与移除/内存读写往返+未映射区安全失败/寄存器快照
- **首轮跑出真 bug**：单步后 PC 未前进（expected 0x1004, got 0x1000）。根因：指令数限制在 codeHook 里实现，而 hook 在指令执行**前**触发，`instrCount>=maxInstrs` 停机时第 N 条永远不执行，单步（count=1）变成零步。修复：改用 uc_emu_start 第 4 参 count（执行完 N 条后停在下一边界），删除 hook 内 maxInstrs 检查与字段
- 修复后 6/6 通过，总耗时 0.224s（connectedDebugAndroidTest）
- 性能基线已测（perfBaseline 用例）：10 万条指令 17.2ms ≈ 5.8 MIPS，见底部基线表

**🎉 M0-M6 全部完成：Unicorn 仿真引擎集成端到端验证通过。**

---

## 关键决策记录

| 日期 | 决策 | 理由 |
|---|---|---|
| 2026-08-03 | 第一期仅 Unicorn，unidbg 独立第七期 | 见集成计划调研结论 |
| 2026-08-03 | libunicorn.a 改走 GitHub Actions 交叉编译（fler-dart 仓库新增 build-unicorn.yml），放弃本地 Windows 构建 | Windows 缺 sh/pkg-config 环境，qemu/configure 无法生成 config-host.h；与 keystone/rizin 静态库模式对齐 |

## 性能基线（M6.3）

| 指标 | 数值 | 设备 |
|---|---|---|
| 10 万条指令 run 耗时 | **17.2ms（≈5.8 MIPS）**，stop=NONE（count 上限正常停止） | Xiaomi ec9d5eda，Android 16，arm64-v8a |
| libapp.so open 耗时 | —（待真实 so 样本接入后补测） | — |

- 测试方法：perfBaseline 用例（NOP×2 + B -8 回跳循环，instrCount=100000，timeout 10s），含逐指令 codeHook 开销（断点表 mutex + 哨兵/超时检查）
- 5.8 MIPS 含 hook 开销，对典型函数仿真（万级指令、30s 超时上限 2000 万条）余量充足
