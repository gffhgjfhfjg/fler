# fler — Code Wiki

> **Android 逆向分析工具** | Kotlin + Jetpack Compose + Hilt + Room + NDK C++
>
> 版本：1.3 (versionCode 4) | 更新日期：2026-08-07

---

## 目录

1. [项目概览](#1-项目概览)
2. [工程结构](#2-工程结构)
3. [架构总览](#3-架构总览)
4. [核心引擎层](#4-核心引擎层-coreanalysis)
5. [JNI 桥接层](#5-jni-桥接层-corejni--cpp)
6. [DI 依赖注入层](#6-di-依赖注入层-coredi)
7. [MCP 协议层](#7-mcp-协议层-coremcp)
8. [Service 服务层](#8-service-服务层-coreservice)
9. [数据层](#9-数据层-data)
10. [特性层](#10-特性层-features)
11. [应用层](#11-应用层-app)
12. [构建与运行](#12-构建与运行)
13. [关键设计决策](#13-关键设计决策)

---

## 1. 项目概览

### 1.1 项目定位

fler 是一款 Android 平台逆向分析工具，专注于 Flutter/Dart APK 的逆向工程。核心能力包括：

- **APK 解包**：自动提取 libapp.so / libflutter.so 及其他 native 库
- **Blutter 分析**：Dart 版本检测 → 引擎匹配 → Blutter 反编译 → 结果导入 Room 数据库
- **SO 编辑器**：结构浏览 / 十六进制编辑 / 反汇编编辑 / Unicorn 仿真（4 Tab）
- **MCP Server**：内置 HTTP+SSE / Streamable HTTP 双协议 MCP 服务器，供 AI 工具远程调用分析能力
- **地址映射**：Dart VM 偏移 ↔ ELF 文件偏移 ↔ 虚拟地址三向映射

### 1.2 技术栈

| 领域 | 技术选型 |
|------|---------|
| 语言 | Kotlin 2.0.21 / C++20 |
| UI | Jetpack Compose + Material 3 + Navigation Compose |
| 架构 | MVVM + 单 Activity + Hilt 依赖注入 |
| 持久化 | Room 2.7.1 (version=6, 8 Entity) |
| 原生层 | NDK r27, arm64-v8a only, CMake |
| 逆向引擎 | Rizin v0.9.x + Capstone 5.0.9 + Keystone 0.9.2 + Unicorn 2.x（均静态链接） |
| 网络 | OkHttp 4.12.0 + kotlinx.serialization 1.7.3 |
| 压缩 | Apache Commons Compress 1.26.1 + XZ 1.10 (7z 解压) |
| 编译 | KSP (非 kapt), Java 17, AGP 9.3.1 |

### 1.3 版本信息

| 属性 | 值 |
|------|-----|
| applicationId | `com.ai.fler` |
| compileSdk | 36 |
| minSdk | 26 |
| targetSdk | 36 |
| versionCode | 4 |
| versionName | "1.3" |
| ABI | `arm64-v8a` (仅) |

---

## 2. 工程结构

### 2.1 模块组织

单模块项目（`:app`），无多模块拆分。

### 2.2 包结构目录树

```
com.ai.fler
├── FlerApplication.kt           # @HiltAndroidApp, 加载原生库
├── MainActivity.kt              # 唯一 Activity, @AndroidEntryPoint
│
├── app/                         # 应用骨架
│   ├── navigation/              # AppNavGraph.kt + Screen.kt (路由定义)
│   └── theme/                   # Color.kt + Theme.kt + Type.kt
│
├── core/                        # 核心业务能力
│   ├── analysis/                # ★ 引擎抽象层
│   │   ├── assembler/           # KeystoneAssembler.kt
│   │   ├── engine/              # RizinEngine / SelfAnalysisEngine / UnicornEngine / PlaceholderEngines / RizinJsonParser
│   │   ├── AnalysisSession.kt   # 分析会话门面 (@Singleton)
│   │   ├── EmulationSession.kt  # 仿真会话门面 (@Singleton)
│   │   ├── BinaryAnalysisEngine.kt  # 核心接口
│   │   ├── EmulationEngine.kt   # 仿真接口 + 数据类型
│   │   ├── EngineRegistry.kt    # 引擎注册中心
│   │   ├── SoEditorCache.kt     # SO 编辑器元数据缓存 (@Singleton)
│   │   ├── DartCallGraphBuilder.kt  # Dart 调用图构建器 (@Singleton)
│   │   └── *Types.kt            # SectionInfo / SymbolInfo / FunctionInfo / DisasmInstruction / AnalysisTypes
│   ├── di/                      # Hilt 模块 (AnalysisModule / CoreModule / DatabaseModule)
│   ├── editor/                  # SoEditorSessionHolder.kt
│   ├── jni/                     # JNI 桥接声明 (8 个 Binding 类)
│   ├── log/                     # AppLogger.kt
│   ├── mcp/                     # MCP 服务 (11 个类)
│   └── service/                 # 应用服务 (12 个服务类)
│
├── data/                        # Room 持久层
│   ├── AppDatabase.kt           # @Database(version=6, 8 entities)
│   ├── dao/                     # 8 个 DAO
│   └── entity/                  # 8 个 Entity
│
├── feature/                     # ViewModel + State (单数命名)
│   ├── project/                 # ProjectViewModel / ProjectDetailViewModel / ProjectState
│   ├── settings/                # SettingsViewModel
│   └── output/                  # AsmBrowserViewModel / AsmListViewModel / PpBrowserViewModel
│
├── features/                    # Composable Screen + Tab (复数命名)
│   ├── engine/                  # EngineViewModel.kt (UI 嵌入 SettingsScreen)
│   ├── mcp/                     # McpServerService / McpServerManager / McpLogScreen / McpLogViewModel
│   ├── onboarding/              # OnboardingScreen.kt
│   ├── output/                  # AsmBrowserScreen / AsmListScreen / PpBrowserScreen
│   ├── project/                 # ProjectScreen / ProjectDetailScreen
│   ├── settings/                # SettingsScreen / McpSettingsScreen / AboutScreen
│   └── so_editor/               # SoEditorScreen / SoEditorViewModel / StructureTab / DisassemblyTab / HexEditorTab / EmulationTab
│
└── ui/                          # UI 公共组件
    ├── animation/               # AnimationConstants.kt + Animations.kt
    └── components/              # EmptyState / ErrorState / LoadingOverlay / ShimmerBox / CardListTile / FastSnackbarHost
```

> **命名约定**：`feature/`（单数）放 ViewModel + State，`features/`（复数）放 Composable Screen。

### 2.3 构建系统

| 文件 | 作用 |
|------|------|
| `build.gradle.kts` | 顶层插件声明（均 `apply false`） |
| `app/build.gradle.kts` | 应用模块配置 + 依赖清单 + `fetchKeystone` 任务 |
| `settings.gradle.kts` | 仓库配置（阿里云镜像优先）+ foojay JDK 工具链 |
| `gradle/libs.versions.toml` | 版本目录（AGP 9.3.1 / Kotlin 2.0.21 / KSP 2.0.21-1.0.27） |
| `app/src/main/cpp/CMakeLists.txt` | NDK 构建配置（静态库导入 + 条件编译） |

---

## 3. 架构总览

### 3.1 分层架构

```
┌─────────────────────────────────────────────────────────┐
│                     UI 层 (Compose)                       │
│  ProjectScreen │ SoEditorScreen │ SettingsScreen │ ...  │
├─────────────────────────────────────────────────────────┤
│                  ViewModel 层 (Hilt)                     │
│  ProjectViewModel │ SoEditorViewModel │ EngineViewModel │
├─────────────────────────────────────────────────────────┤
│              Session 门面层 (@Singleton)                 │
│        AnalysisSession      EmulationSession             │
├───────────────────┬─────────────────────────────────────┤
│  EngineRegistry   │     Service 层                       │
│  ├─ RizinEngine   │  BackupManager / EngineLoader        │
│  ├─ SelfAnalysis  │  ApkExtractor / AnalysisImporter     │
│  ├─ UnicornEngine │  PatchExporter / AddressTranslator   │
│  └─ Unidbg(占位)  │  EnginePackManager / ...             │
├───────────────────┴─────────────────────────────────────┤
│              JNI 桥接层 (Kotlin ↔ C++)                   │
│  RizinBindings │ CapstoneBindings │ KeystoneBindings     │
│  ElfParserBindings │ UnicornBindings │ BlutterEngine     │
├─────────────────────────────────────────────────────────┤
│              Native 层 (C++ / 静态库)                    │
│  rizin_jni.cpp │ capstone_jni.cpp │ keystone_jni.cpp     │
│  elf_parser_jni.cpp │ unicorn_jni.cpp │ blutter_jni.cpp  │
│  elf_parser.cpp                                         │
│  [librz_*.a] [libcapstone.a] [libkeystone.a] [libunicorn.a] │
├─────────────────────────────────────────────────────────┤
│              数据层 (Room)                               │
│  AppDatabase (v6) → 8 Entity + 8 DAO                     │
└─────────────────────────────────────────────────────────┘
```

### 3.2 单例依赖图（Hilt）

```
AnalysisSession (@Singleton)
  ├─ EngineRegistry (@Singleton)
  │   ├─ RizinEngine (priority=100)
  │   ├─ SelfAnalysisEngine (priority=10)
  │   ├─ UnicornEngine (priority=50)
  │   └─ UnidbgEnginePlaceholder (priority=100, isAvailable=false)
  ├─ BackupManager (@Singleton)
  ├─ SoEditorCache (@Singleton)
  └─ AppLogger

EmulationSession (@Singleton)
  ├─ EngineRegistry (@Singleton)
  └─ SoEditorCache (@Singleton)

DartCallGraphBuilder (@Singleton)
  ├─ DartMethodDao
  ├─ DartCallGraphDao
  └─ AppDatabase

KeystoneAssembler (@Singleton)
```

### 3.3 引擎优先级

| 引擎类型 | 引擎 | 优先级 | 状态 |
|---------|------|--------|------|
| 分析引擎 | Rizin | 100 | ✅ 可用 |
| 分析引擎 | SelfAnalysis (ElfParser+Capstone+Keystone) | 10 | ✅ 可用 (fallback) |
| 仿真引擎 | Unidbg | 100 | ❌ 占位符 |
| 仿真引擎 | Unicorn | 50 | ✅ 可用 |

---

## 4. 核心引擎层 (core/analysis)

### 4.1 BinaryAnalysisEngine 接口

**文件**：[BinaryAnalysisEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/BinaryAnalysisEngine.kt)

所有具体引擎实现的顶层抽象接口。全部方法声明为 `suspend`，返回 `null/empty` 表示不可用或失败。

**属性**：

| 属性 | 类型 | 说明 |
|------|------|------|
| `engineId` | `String` | 引擎唯一 ID（如 "rizin"、"self"） |
| `displayName` | `String` | UI 展示名 |
| `isAvailable` | `Boolean` | 运行时是否可用 |
| `capabilities` | `Set<AnalysisCapability>` | 支持的能力集合 |

**能力枚举** (`AnalysisCapability`)：
```
ELF_PARSING, DISASSEMBLY, ASSEMBLY, FUNCTION_ANALYSIS, XREF, CFG,
STRING_SCAN, DEMANGLE, BYTE_EDIT, ADDRESS_TRANSLATION, BINARY_HASH,
SIGNATURE_MATCH, PDB_DWARF
```

**核心方法分组**：

| 分组 | 方法 | 所需能力 |
|------|------|---------|
| 生命周期 | `open(filePath, options)` / `close(handle)` / `isHandleValid(handle)` | - |
| ELF 结构 | `getFileInfo()` / `getSections()` / `getSymbols()` / `getImports()` / `getRelocs()` / `scanStrings()` | ELF_PARSING |
| 函数分析 | `listFunctions()` / `findFunctionContaining(addr)` / `findFunctionsByName(query)` / `getFunctionCfg(offset)` / `defineFunction(addr, name)` / `defineFunctions(list)` / `reanalyzeXrefs()` | FUNCTION_ANALYSIS |
| 反汇编 | `disassemble(offset, size)` | DISASSEMBLY |
| 汇编 | `assemble(assembly, address)` | ASSEMBLY |
| 交叉引用 | `xrefsTo(target)` / `xrefsFrom(from)` | XREF |
| 字节读写 | `readBytes(offset, size)` / `writeBytes(offset, data)` | BYTE_EDIT |
| 地址转换 | `paddrToVaddr(paddr)` / `vaddrToPaddr(vaddr)` | ADDRESS_TRANSLATION |
| 哈希 | `md5()` / `sha256()` / `crc32(offset, size)` | BINARY_HASH |

### 4.2 EngineRegistry

**文件**：[EngineRegistry.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/EngineRegistry.kt)

`@Singleton` 引擎注册中心。维护所有已注册的 `BinaryAnalysisEngine` 与 `EmulationEngine`，提供按能力/ID 查询。

**关键方法**：
- `registerAnalysis(engine, priority)` / `registerEmulation(engine, priority)`
- `pickAnalysisFor(vararg caps)` — 按优先级返回支持指定能力的最高优先引擎
- `listAnalysis()` / `listEmulation()` — 按优先级降序列出

### 4.3 AnalysisSession

**文件**：[AnalysisSession.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/AnalysisSession.kt)

`@Singleton` — **UI/MCP 调用分析引擎的唯一官方入口**。

**核心职责**：
1. 封装 EngineRegistry 挑选引擎流程，调用方不关心使用哪个引擎
2. 维护「文件路径 → 会话 handle」映射，同 SO 多次调用复用同一 handle（避免重复 open/aaa）
3. 集成 BackupManager，实现 ByteEdit 级别的 patch 栈
4. 最多 3 个并发会话，LRU 淘汰

**线程安全**：所有操作通过 `Mutex` 串行化（RzCore 命令非线程安全）。

**关键方法**：
- `open(filePath, options, requireCaps)` — 按优先级挑选引擎，同路径复用
- `openWithEngine(filePath, engineId, options)` — 显式指定引擎
- `writeBytes(offset, data, soNameHint)` — 引擎写盘 + 记录 patch + 落盘校验
- `writeRawBytes(offset, data)` — 直接写盘不记录（用于 undo）
- `closeAll()` — 关闭所有会话（仅 App 退出时调用）

### 4.4 EmulationSession

**文件**：[EmulationSession.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/EmulationSession.kt)

`@Singleton` — 仿真会话统一门面。

**关键方法**：
- `open(filePath)` — 加载 SO 到仿真引擎
- `resolveFunction(filePath, name)` — 函数名 → 地址（优先缓存，回退 ElfParser）
- `callFunction(filePath, functionName, args, timeoutMs, maxInstrs)` — 高层调用：写参 x0-x7 → LR=哨兵 → PC=函数 → run → 读 x0

### 4.5 RizinEngine

**文件**：[RizinEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt)

通过 JNI 调用 Rizin 静态库（26 个 librz_*.a + libcapstone.a 静态链接进 libfler_jni.so）。

**能力**：ELF_PARSING / DISASSEMBLY / ASSEMBLY / FUNCTION_ANALYSIS / XREF / CFG / STRING_SCAN / DEMANGLE / BYTE_EDIT / ADDRESS_TRANSLATION / BINARY_HASH / SIGNATURE_MATCH

**关键实现**：
- `open()` → `rz_core_new` + `rz_core_file_open` + `rz_core_bin_load` + 可选 `aaa`
- Rizin Project 持久化：`{cacheDir}/rizin_projects/{SO名}_{size}_{mtime}.rzdb`，避免重复 aaa
- `defineFunction()` — 仅 `f name @ addr` 设 flag 名，**不调 `af`**（避免清除 xref）
- `defineFunctions()` — 批量注入，每批 200 条复合命令，名字清洗
- `reanalyzeXrefs()` — `aar` 主命令（扫描整个二进制），失败回退 `aac`
- `scanStrings()` — 不用 `izzj`（Dart AOT 大库返回空），改用 `readBytes` 流式扫描
- `md5()`/`sha256()` — 不用 Rizin `ph`（语义错误），改用 `streamDigest` 分块流式计算

### 4.6 SelfAnalysisEngine

**文件**：[SelfAnalysisEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/SelfAnalysisEngine.kt)

默认 fallback 引擎（priority=10）。基于 ElfParser + Capstone + Keystone，始终可用。

**能力**：ELF_PARSING / DISASSEMBLY / ASSEMBLY / BYTE_EDIT / ADDRESS_TRANSLATION / BINARY_HASH

**特点**：不做缓存，每次查询用 `ElfParserBindings.open+close` 重新解析，由 AnalysisSession 对外做会话合并。不支持 FUNCTION_ANALYSIS / XREF / CFG。

### 4.7 UnicornEngine

**文件**：[UnicornEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/UnicornEngine.kt)

仿真引擎（priority=50），静态链接 libunicorn.a。支持 INSTRUCTION_EMU / CODE_HOOK / MEMORY_HOOK / SINGLE_STEP。

**ELF 装载**：按 PT_LOAD 段页对齐映射，重定位到 baseAddress。栈 0x40000000+1MB，heap 0x50000000+8MB，哨兵 0xDEADBEEF0000。

### 4.8 数据类型

| 类型 | 文件 | 说明 |
|------|------|------|
| `SectionInfo` | [SectionInfo.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/SectionInfo.kt) | ELF 节区（name/offset/size/address/paddr/perm） |
| `SymbolInfo` | [SymbolInfo.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/SymbolInfo.kt) | ELF 符号 + ImportInfo / RelocInfo / StringInfo / FileInfo |
| `FunctionInfo` | [FunctionInfo.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/FunctionInfo.kt) | 函数信息 + BasicBlock + Xref + XrefType |
| `DisasmInstruction` | [DisasmInstruction.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/DisasmInstruction.kt) | 反汇编指令（address/mnemonic/opStr/bytes） |
| `AnalysisTypes` | [AnalysisTypes.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/AnalysisTypes.kt) | AnalysisCapability / AnalysisLevel / OpenOptions / OpenResult / AnalysisHandle |

### 4.9 SoEditorCache

**文件**：[SoEditorCache.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/SoEditorCache.kt)

`@Singleton` — SO 编辑器元数据缓存。App 进程内常驻，LRU 上限 8 个 SO。

缓存内容：`SoMetadata`（sections/symbols/functions/fileInfo）、`DartLabels`（Blutter 方法标签）、注入状态、xref 状态。

### 4.10 DartCallGraphBuilder

**文件**：[DartCallGraphBuilder.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/DartCallGraphBuilder.kt)

`@Singleton` — Dart 方法调用图构建器。从 Blutter 反汇编伪代码解析 `bl #0x...` / `b #0x...` 直接调用边，落库 `DartCallEdge`。纯 DB 离线构建，不依赖 Rizin。

### 4.11 KeystoneAssembler

**文件**：[KeystoneAssembler.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/assembler/KeystoneAssembler.kt)

`@Singleton` — 独立汇编器。`assemble(assembly, address): ByteArray?`，Keystone 0.9.2 大小写敏感，先试原文再试小写。

---

## 5. JNI 桥接层 (core/jni + cpp)

### 5.1 Kotlin 侧 (8 个 Binding 类)

| 类 | 文件 | 职责 |
|----|------|------|
| `NativeLoader` | [NativeLoader.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/jni/NativeLoader.kt) | 幂等加载 `libfler_jni.so` |
| `ElfParserBindings` | [ElfParserBindings.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/jni/ElfParserBindings.kt) | 自研 ELF 解析器（AutoCloseable），含 ElfSection / ElfSymbol / ElfLoadSegment 数据类 |
| `CapstoneBindings` | [CapstoneBindings.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/jni/CapstoneBindings.kt) | Capstone 反汇编 ARM64（`disassembleWithCapstone`） |
| `KeystoneBindings` | [KeystoneBindings.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/jni/KeystoneBindings.kt) | Keystone 汇编 ARM64（`asm`） |
| `RizinBindings` | [RizinBindings.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/jni/RizinBindings.kt) | Rizin 命令执行（`open/close/analyze/cmdStr/readBytes/writeBytes/paddrToVaddr/projectSave/projectLoad`） |
| `UnicornBindings` | [UnicornBindings.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/jni/UnicornBindings.kt) | Unicorn 仿真（14 个 JNI 方法，含 `isAvailable` 编译期降级开关） |
| `BlutterEngine` | [BlutterEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/jni/BlutterEngine.kt) | Blutter Dart 分析（`analyze(soPath, dbPath, cacheDir)`，含 `AnalyzeResult` 枚举） |
| `DisasmInstruction` | [DisasmInstruction.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/jni/DisasmInstruction.kt) | JNI 层反汇编指令数据模型 |

### 5.2 C++ 侧 (6 个 JNI 桥接 + 1 个解析器)

| 文件 | 职责 |
|------|------|
| [elf_parser_jni.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/elf_parser_jni.cpp) | ElfParser JNI 桥（sections/symbols/segments/read/write） |
| [capstone_jni.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/capstone_jni.cpp) | Capstone 反汇编 JNI（`cs_disasm_iter` 循环，不可解码字输出 `.word`） |
| [keystone_jni.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/keystone_jni.cpp) | Keystone 汇编 JNI（以 `encoding_size` 判成败） |
| [rizin_jni.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/rizin_jni.cpp) | Rizin 命令执行 JNI（`rz_core_cmd_str`，含 `raw_pread/pwrite` 兜底） |
| [unicorn_jni.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/unicorn_jni.cpp) | Unicorn 仿真 JNI（条件编译 `#ifdef FLER_ENABLE_UNICORN`，缺失时降级为 stub） |
| [blutter_jni.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/blutter_jni.cpp) | Blutter 分析 JNI（`sigsetjmp/siglongjmp` 信号保活，捕获 SIGSEGV/SIGBUS） |
| [elf_parser.h/.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/elf_parser/elf_parser.cpp) | 自研 ELF64 解析器（mmap + fstat，命名空间 `fler::elf`） |

### 5.3 CMakeLists.txt

**文件**：[CMakeLists.txt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/CMakeLists.txt)

| 构建目标 | 类型 | 源文件 | 链接库 |
|---------|------|--------|--------|
| `fler_native` | STATIC | elf_parser.cpp | log |
| `fler_jni` | SHARED | 6 个 jni_bridge/*.cpp | fler_native + keystone + capstone + librz_*.a + log + android (+ unicorn 条件) |

**静态库**（位于 `app/libs/arm64-v8a/`）：
- `libkeystone.a` — 由 `fetchKeystone` Gradle 任务本地交叉编译
- `libcapstone.a` — build-rizin workflow 产物
- `librz_*.a`（26 个） — build-rizin workflow 产物
- `libunicorn.a` — build-unicorn workflow 产物（`ENABLE_UNICORN=ON` 时链接，缺失自动降级）

---

## 6. DI 依赖注入层 (core/di)

### 6.1 AnalysisModule

**文件**：[AnalysisModule.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/di/AnalysisModule.kt)

`@Module @InstallIn(SingletonComponent::class)`

**职责**：注册分析引擎到 EngineRegistry，提供 AnalysisSession 门面。

| 方法 | 提供 | 说明 |
|------|------|------|
| `provideEngineRegistry(context, keystone)` | `EngineRegistry @Singleton` | 注册 RizinEngine(100) + SelfAnalysisEngine(10) + UnicornEngine(50) + UnidbgPlaceholder(100) |
| `provideKeystoneAssembler()` | `KeystoneAssembler @Singleton` | 独立汇编器 |
| `provideAnalysisSession(registry, backupManager, soEditorCache, appLogger)` | `AnalysisSession @Singleton` | UI/MCP 统一会话门面 |

### 6.2 CoreModule

**文件**：[CoreModule.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/di/CoreModule.kt)

提供 `OkHttpClient @Singleton`（30s 超时）。其他 service 通过构造函数注入由 Hilt 自动构造。

### 6.3 DatabaseModule

**文件**：[DatabaseModule.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/di/DatabaseModule.kt)

提供 `AppDatabase @Singleton` + 8 个 DAO + 3 级 schema 迁移（3→4 / 4→5 / 5→6）。

---

## 7. MCP 协议层 (core/mcp)

### 7.1 McpHttpServer

**文件**：[McpHttpServer.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpHttpServer.kt)

自实现 ServerSocket HTTP 服务器，无第三方依赖。8 线程固定池，5 秒心跳。

| 路由 | 方法 | 协议 |
|------|------|------|
| `/sse` | GET | Legacy HTTP+SSE (Claude Desktop) |
| `/mcp` | GET | MCP Streamable HTTP (事件流) |
| `/message` | POST | Legacy 消息端点 |
| `/mcp` | POST | MCP Streamable HTTP (JSON-RPC) |

**安全**：Token 鉴权使用 `MessageDigest.isEqual` 恒定时间比较防时序侧信道。

### 7.2 McpProtocol

**文件**：[McpProtocol.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpProtocol.kt)

JSON-RPC 2.0 协议处理。支持协议版本：`2025-03-26` / `2024-11-05` / `2025-06-18`。serverInfo: `fler-mcp v1.3.0`。

### 7.3 McpToolHandlers

**文件**：[McpToolHandlers.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpToolHandlers.kt)

`@Singleton` — MCP 工具处理器，聚合 4 大类共 26+ 个工具：

| 类别 | 工具数 | 示例 |
|------|--------|------|
| 分析工具 | 4 | `list_analyses` / `get_analysis` / `list_projects` / `get_project` |
| 浏览工具 | 10 | `list_classes` / `list_methods` / `get_method` / `search_strings` / `get_method_callers` |
| 反汇编/ELF | 8 | `disassemble_range` / `list_elf_sections` / `assemble_instruction` / `read_so_bytes` |
| 补丁工具 | 4 | `patch_instruction` / `undo_patch` / `list_patches` / `patch_bytes`（默认关闭） |

### 7.4 EngineMcpToolRegistry

**文件**：[EngineMcpToolRegistry.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/EngineMcpToolRegistry.kt)

`@Singleton` — 引擎层 MCP 工具注册器。`engine_` 前缀，19 个工具。

关键：`dartFunctions()` 合并 Blutter 恢复的数万级 Dart 方法（按 soPath 缓存），`engine_list_functions` 优先 Rizin 后合并 Blutter。

### 7.5 EmulationMcpToolRegistry

**文件**：[EmulationMcpToolRegistry.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/EmulationMcpToolRegistry.kt)

`@Singleton` — 仿真层 MCP 工具注册器。`emu_` 前缀，13 个工具。

### 7.6 AddressAxisResolver

**文件**：[AddressAxisResolver.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/AddressAxisResolver.kt)

`@Singleton` — 地址坐标轴解析器。解决 Blutter vaddr 与文件偏移的歧义问题。

### 7.7 其他 MCP 类

| 类 | 文件 | 职责 |
|----|------|------|
| `McpConfig` | [McpConfig.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpConfig.kt) | SharedPreferences 持久化 MCP 配置 |
| `McpLogger` | [McpLogger.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpLogger.kt) | MCP 日志队列（上限 500 条） |
| `McpSessions` | [McpSessions.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpSessions.kt) | SSE 会话管理（ConcurrentHashMap） |
| `McpErrors` | [McpErrors.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpErrors.kt) | JSON-RPC 错误码常量 |
| `McpResource` | [McpResource.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpResource.kt) | 资源接口定义 |

---

## 8. Service 服务层 (core/service)

### 8.1 服务类一览

| 类 | 文件 | 职责 |
|----|------|------|
| `EngineLoader` | [EngineLoader.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/EngineLoader.kt) | `@Singleton` 加载 Blutter 引擎 SO（System.load + 符号链接管理） |
| `EngineExtractor` | [EngineExtractor.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/EngineExtractor.kt) | `@Singleton` 7z 解压引擎包（Apache Commons Compress） |
| `EnginePackManager` | [EnginePackManager.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/EnginePackManager.kt) | `@Singleton` 引擎包管理（下载/校验/安装/清理） |
| `BackupManager` | [BackupManager.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/BackupManager.kt) | `@Singleton` SO 编辑器撤销栈（每文件独立，持久化到 JSON） |
| `PatchExporter` | [PatchExporter.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/PatchExporter.kt) | `@Singleton` 补丁导出（.patch 文本格式 + SO 二进制导出） |
| `AddressTranslator` | [AddressTranslator.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/AddressTranslator.kt) | `@Singleton` Dart VM ↔ ELF ↔ 虚拟地址三向映射 |
| `ApkExtractor` | [ApkExtractor.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/ApkExtractor.kt) | `@Singleton` APK 解包（Flutter 优先 arm64，回退全量 .so） |
| `DartVersionDetector` | [DartVersionDetector.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/DartVersionDetector.kt) | `@Singleton` Dart SDK 版本检测（正则 + .rodata 扫描） |
| `AnalysisImporter` | [AnalysisImporter.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/AnalysisImporter.kt) | `@Singleton` Blutter SQLite → Room 导入（ATTACH + 表对表直搬） |
| `DualSourceDownloader` | [DualSourceDownloader.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/DualSourceDownloader.kt) | `@Singleton` 双源下载（代理 + GitHub 直连） |
| `EngineSourceConfig` | [EngineSourceConfig.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/EngineSourceConfig.kt) | `@Singleton` 下载源配置（SharedPreferences） |
| `EngineManifest` | [EngineManifest.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/EngineManifest.kt) | 引擎清单数据类（packVersion / engines / runtimeLibs） |

---

## 9. 数据层 (data)

### 9.1 AppDatabase

**文件**：[AppDatabase.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/data/AppDatabase.kt)

```kotlin
@Database(entities = [Project, Analysis, DartClass, DartMethod, PpEntry, Library, AddressMapping, DartCallEdge], version = 6)
abstract class AppDatabase : RoomDatabase()
```

**级联删除**：SQLite 默认不开启外键约束，必须在应用层显式级联：
- `cascadeDeleteProject(projectId)` — 删除顺序：叶子表 → analyses → address_mappings → projects
- `cascadeDeleteAnalysis(analysisId)` — 删除分析记录及其子数据

### 9.2 Entity（8 个）

| 实体 | 表名 | 外键 | 关键字段 |
|------|------|------|---------|
| `Project` | `projects` | 无 | apk_path(unique) / status / package_name / version |
| `Analysis` | `analyses` | project_id → Project(CASCADE) | result_code / libapp_path / libflutter_path / classes_count |
| `Library` | `libraries` | analysis_id → Analysis(CASCADE) | library_name / load_address / size |
| `AddressMapping` | `address_mappings` | 无 | project_id / vm_offset / file_offset / elf_address |
| `DartClass` | `dart_classes` | analysis_id → Analysis(CASCADE) | class_name / super_class / is_abstract / method_count |
| `DartMethod` | `dart_methods` | class_id → DartClass(CASCADE), analysis_id → Analysis(CASCADE) | method_name / function_offset / function_size / src_code / signature |
| `PpEntry` | `pp_entries` | method_id → DartMethod(CASCADE), analysis_id → Analysis(CASCADE) | vm_offset / file_offset / type / is_leaf |
| `DartCallEdge` | `dart_call_edges` | caller_method_id → DartMethod(CASCADE), analysis_id → Analysis(CASCADE) | callee_method_id / callee_vaddr / kind |

### 9.3 DAO（8 个）

| DAO | 特殊方法 |
|-----|---------|
| `ProjectDao` | `getAll(): Flow<List<Project>>` (按 updated_at DESC) |
| `AnalysisDao` | `getByLibappPath(path)` / `completeAnalysis(id, resultCode, completedAt)` / `updateCounts(...)` |
| `AddressMappingDao` | `insertAllInTransaction(mappings)` — 分批 500 条单事务（避免 SQLite 999 绑定变量限制） |
| `DartMethodDao` | `getByAnalysisIdLight()` — 不含 src_code 大字段 / `searchMethodsWithClass()` — 组合过滤分页 / `getMethodPage()` — Keyset 分页 / `getSrcPage()` — 调用图分页 |
| `DartCallGraphDao` | `callersOfMethod()` / `calleesOf()` — 投影查询免 join / `getAllByAnalysisId()` — 一次加载供内存索引 |
| `DartClassDao` | `countMethodsGroupedByClass()` — GROUP BY 统计 |
| `PpEntryDao` | `getTopCallersByAnalysisId(limit=50)` / `searchStrings()` / `getPpPage()` — Keyset 分页 |
| `LibraryDao` | 基础 CRUD |

### 9.4 数据库迁移

| 迁移 | 内容 |
|------|------|
| `MIGRATION_3_4` | 新建 `dart_call_edges` 表（含外键 + 4 个索引） |
| `MIGRATION_4_5` | 重建 `dart_call_edges`（修复早期缺外键） |
| `MIGRATION_5_6` | `dart_methods` 增加 `(analysis_id, function_offset)` 复合索引 |

---

## 10. 特性层 (features)

### 10.1 项目管理

| 文件 | 类/Composable | 职责 |
|------|-------------|------|
| [ProjectScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/project/ProjectScreen.kt) | `ProjectScreen` | 项目列表主界面，SAF 选择 APK（IO 协程复制），新建/删除/分析 |
| [ProjectDetailScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/project/ProjectDetailScreen.kt) | `ProjectDetailScreen` | 项目详情，分析记录列表，SO 文件入口 |
| [ProjectViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/feature/project/ProjectViewModel.kt) | `ProjectViewModel` | 5 阶段分析流程：Extracting → DetectingVersion → LoadingEngine → Analyzing → SavingResults |
| [ProjectDetailViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/feature/project/ProjectDetailViewModel.kt) | `ProjectDetailViewModel` | 项目详情数据加载 |
| [ProjectState.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/feature/project/ProjectState.kt) | `ProjectListState` / `AnalysisProgress` / `AnalysisStage` | 状态数据类 |

### 10.2 SO 编辑器

| 文件 | 类/Composable | 职责 |
|------|-------------|------|
| [SoEditorScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt) | `SoEditorScreen` | 双模式入口（Tab 模式 + 沉浸模式），4 Tab 切换 |
| [SoEditorViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt) | `SoEditorViewModel` | 核心状态管理，文件打开/缓存/Dart 标签加载/反汇编/Hex/撤销/导出 |
| [StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt) | `StructureTab` | ELF 结构浏览（5 子 Tab：节区/符号/动态符号/函数/字符串） |
| [DisassemblyTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt) | `DisassemblyTab` | 反汇编编辑（指令编辑/长按菜单/交叉引用面板/向上无限滚动） |
| [HexEditorTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt) | `HexEditorTab` | 十六进制编辑（每行 8 字节，点击选中+写入） |
| [EmulationTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/EmulationTab.kt) | `EmulationTab` / `EmulationViewModel` | Unicorn 仿真（断点/单步/寄存器/内存查看） |

**SoEditorViewModel 关键方法**：

| 方法 | 说明 |
|------|------|
| `openFile(filePath)` | 打开 SO → 缓存检查 → 元数据查询/缓存 → vaddr→paddr 映射 → Dart 标签加载 → xref 分析 |
| `loadDartFunctionLabels(soPath)` | Blutter 方法标签加载（仅 libapp.so），缓存 + Rizin flag 注入 + xref 重建 |
| `loadDisassembly(offset, size, highlightAfterLoad)` | 坐标保护 → 上下文加载（前 512B）→ Capstone 解码 |
| `loadMoreBefore()` | 向前追加加载（独立 `isLoadMoreInProgress` 标志位） |
| `applyInstructionPatch(offset, instruction, args)` | Keystone 编码 → applyPatch 写盘 |
| `loadXrefs(address)` | xrefsTo + xrefsFrom + dart_call_edges 补充 + 函数名解析 |
| `writeByte(offset, value)` | 写入单个字节（suspend，返回 Boolean） |
| `undo(onResult)` | BackupManager.undo → writeRawBytes（不记录 patch） |
| `commitChanges()` | 标记已保存，清除红色高亮 |
| `exportPatchesToUri(uri)` / `exportSoToUri(uri)` | 导出 .patch 文件 / 导出修改后 SO |

### 10.3 引擎下载

| 文件 | 类 | 职责 |
|------|-----|------|
| [EngineViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/engine/EngineViewModel.kt) | `EngineViewModel` | v0.4.0 按版本按需下载，UI 嵌入 SettingsScreen 的 `EngineVersionCard` |

### 10.4 设置与 MCP

| 文件 | 类/Composable | 职责 |
|------|-------------|------|
| [SettingsScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/settings/SettingsScreen.kt) | `SettingsScreen` | 引擎包/下载源/MCP/缓存清理/关于 |
| [McpSettingsScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/settings/McpSettingsScreen.kt) | `McpSettingsScreen` | MCP 配置详情 |
| [SettingsViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/feature/settings/SettingsViewModel.kt) | `SettingsViewModel` | MCP 启停/缓存清理/更新检查 |
| [McpLogScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/mcp/McpLogScreen.kt) | `McpLogScreen` | 双 Tab 日志（MCP 日志 + 应用日志） |
| [McpServerService.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/mcp/McpServerService.kt) | `McpServerService` | MCP 前台服务（dataSync 类型，START_STICKY 保活） |

### 10.5 浏览器

| 文件 | 职责 |
|------|------|
| [PpBrowserScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/output/PpBrowserScreen.kt) | 补丁点浏览（Keyset 分页 + 类型筛选） |
| [AsmListScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/output/AsmListScreen.kt) | 方法列表（GROUP BY 统计 + 搜索） |
| [AsmBrowserScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/output/AsmBrowserScreen.kt) | 方法反汇编浏览（src_code 展示） |

---

## 11. 应用层 (app)

### 11.1 FlerApplication

**文件**：[FlerApplication.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/FlerApplication.kt)

`@HiltAndroidApp` — onCreate 中调用 `NativeLoader.load()` 加载 `libfler_jni.so`。

### 11.2 MainActivity

**文件**：[MainActivity.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/MainActivity.kt)

`@AndroidEntryPoint` — 唯一 Activity。`enableEdgeToEdge()` 沉浸式。启动时自动拉起 MCP 服务器（前台服务）。首次启动显示新手引导。

### 11.3 主题

**文件**：[Theme.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/app/theme/Theme.kt)

Android 12+ 默认 Material You 动态取色，老设备回退品牌蓝调色板。`FlerShapes` 统一圆角（8/12/16dp）。

### 11.4 导航

**文件**：[AppNavGraph.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/app/navigation/AppNavGraph.kt) + [Screen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/app/navigation/Screen.kt)

**3 个顶层 Tab**（Bottom Navigation）：Projects / McpLog / Settings

**子页面路由**（隐藏底栏）：
- `ProjectDetail` → `PpBrowser` / `AsmList` → `AsmBrowser` / `SoEditor`
- `PpBrowser` / `AsmBrowser` → `SoEditor`（沉浸模式，自动换算文件偏移）
- `Settings` → `McpSettings` / `About`

**路由参数**：`SoEditor.createRoute` 使用 URL-safe base64 编码文件路径。

---

## 12. 构建与运行

### 12.1 环境要求

| 要求 | 版本 |
|------|------|
| Android Studio | Hedgehog 2024.1+ (AGP 9.3.1) |
| JDK | 17 (foojay-resolver 自动解析) |
| Kotlin | 2.0.21 |
| KSP | 2.0.21-1.0.27 |
| NDK | r27 (27.0.12077973) |
| CMake | 3.22.1+ |
| ABI | arm64-v8a (仅) |

### 12.2 原生库准备

以下静态库需放置在 `app/libs/arm64-v8a/`：

| 文件 | 来源 | 说明 |
|------|------|------|
| `libkeystone.a` | `scripts/build-keystone.sh` 本地交叉编译 | `fetchKeystone` Gradle 任务检查存在性 |
| `libcapstone.a` | build-rizin GitHub Action Release | 36MB |
| `librz_*.a` (26 个) | build-rizin GitHub Action Release | ~40MB |
| `libunicorn.a` | build-unicorn GitHub Action Release | 可选（`ENABLE_UNICORN=OFF` 降级） |

头文件放置在 `app/src/main/cpp/include/`：
- `include/capstone/`
- `include/rizin/` + `include/rizin/rz_util/` + `include/rizin/sdb/`

### 12.3 构建步骤

```bash
cd c:\Users\Len\AndroidStudioProjects\fler

# Debug 构建
gradlew assembleDebug

# 或在 Android Studio 中直接 Run
```

### 12.4 权限声明

| 权限 | 用途 |
|------|------|
| `INTERNET` | MCP HTTP Server / 引擎下载 |
| `ACCESS_NETWORK_STATE` | 网络状态检测 |
| `FOREGROUND_SERVICE` | MCP 前台服务 |
| `FOREGROUND_SERVICE_DATA_SYNC` | 前台服务类型 |

### 12.5 依赖清单

| 依赖 | 版本 | 用途 |
|------|------|------|
| `core-ktx` | 1.13.1 | AndroidX Core |
| `compose-bom` | 2024.09.00 | Compose 版本管理 |
| `material3` | (BOM) | Material 3 |
| `navigation-compose` | 2.7.7 | 导航 |
| `hilt-android` | 2.60.1 | 依赖注入 |
| `hilt-navigation-compose` | 1.2.0 | ViewModel 注入 |
| `room-runtime` / `room-ktx` | 2.7.1 | 数据库 |
| `okhttp` | 4.12.0 | HTTP 客户端 |
| `commons-compress` | 1.26.1 | 7z 解压 |
| `xz` | 1.10 | LZMA2 |
| `kotlinx-serialization-json` | 1.7.3 | MCP JSON-RPC |
| `documentfile` | 1.0.1 | SAF 文件访问 |

---

## 13. 关键设计决策

### 13.1 静态链接策略

Capstone / Keystone / Rizin (26 个 librz_*.a) / Unicorn 全部静态链接进 `libfler_jni.so`。SO 编辑器反汇编/汇编/仿真零引擎依赖，运行时不需要下载引擎包。只有 Blutter 引擎因体积大而动态加载。

### 13.2 三层缓存机制

1. **AnalysisSession 层**：`pathToHandle` → Rizin `RzCore*` 指针（避免重复 open/load/aaa）
2. **`@Singleton SoEditorCache` 层**：`SoMetadata` / `DartLabels` / 注入状态（App 进程内常驻，LRU 上限 8）
3. **`@Singleton SoEditorSessionHolder`**：完整 `SessionState`（切 Tab 秒恢复）

ViewModel 不调用 `session.closeAll()`，App 退出时随进程回收。

### 13.3 坐标系统

- **vaddr**（虚拟地址）：Rizin/仿真工作空间；符号 `address`、函数 `vaddr`、xref 查询
- **paddr**（文件偏移）：反汇编/Hex 视图工作空间；节区 `paddr`、函数 `offset`
- **PIE 库 vaddr ≠ paddr**：用 `buildVaddrToPaddrMapper(sections)` 节区映射批量换算
- **`resolveJumpAddress(input)`**：粘贴虚拟地址超出文件大小时自动换算 paddr
- **`AddressAxisResolver`**：双轴判定（FILE_OFFSET / VADDR / AMBIGUOUS），歧义时拒绝猜测

### 13.4 撤销系统

- `BackupManager` 按文件路径管理独立 undoStack，持久化到 `filesDir/undo/{md5(filePath)}.json`
- `AnalysisSession.writeBytes()` 记录补丁；`writeRawBytes()` 直接写盘不记录（用于 undo 回滚）
- 首次编辑创建 `.bak` 全量备份，最多 50 步撤销
- 返回后重新进入 SO 编辑器，修改过的指令仍显示红色高亮

### 13.5 Rizin xref 保护

- `defineFunction` 仅设 flag 名（`f name @ addr`），不调 `af`（避免清除 xref）
- `reanalyzeXrefs` 用 `aar` 主命令（扫描整个二进制不依赖函数边界），失败回退 `aac`
- `defineFunctions` 批量注入，每批 200 条复合命令

### 13.6 Blutter 信号保活

`blutter_jni.cpp` 用 `sigsetjmp/siglongjmp` + `thread_local jmp_buf` 捕获 SIGSEGV/SIGBUS/SIGFPE/SIGABRT/SIGILL，分析崩溃时返回 -997 而非杀进程。

### 13.7 MCP 双协议

`McpHttpServer` 同时支持：
- **Legacy HTTP+SSE**（`/sse` + `/message`）— Claude Desktop 兼容
- **MCP Streamable HTTP**（`/mcp` GET/POST）— 最新协议

心跳用专用 daemon 线程，避免 SSE 长连接占满 8 线程池。

### 13.8 常见陷阱

| 陷阱 | 解决方案 |
|------|---------|
| Hilt 增量编译缓存损坏 | `gradlew clean` 后重新构建 |
| Android NDK 无 execinfo.h | `backtrace` 提供空实现 stub |
| LazyColumn 重复 key 崩溃 | 用索引入 key（非 name_address） |
| 7z 压缩 ARM64 BCJ filter | `-mf=off` 禁用全部 filter |
| Keystone 0.9.2 返回值 | 用 `encoding_size` 判断成败（非 count） |
| Rizin io.va 地址映射 | `raw_pread/pwrite` 兜底 mmap 直读直写 |
| Compose 动画 MonotonicFrameClock | `Animatable.animateTo` 在 UI 层通过 `animateFloatAsState` 实现 |
| ContentResolver Binder 阻塞 | 用 `query(OpenableColumns.SIZE)` 替代 `openFileDescriptor` |
| LazyColumn + horizontalScroll | 移除 horizontalScroll，否则失去 lazy 特性 |
| derivedStateOf 闭包失效 | `remember` 添加源 List + currentQuery 作为 key |
