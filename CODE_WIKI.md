# fler — Android 逆向分析工具 Code Wiki

> 项目源码：`c:\Users\Len\AndroidStudioProjects\fler`
> 包名：`com.ai.fler` | 最小 SDK：26 | 目标 SDK：36 | 编译 SDK：36
> 构建系统：Gradle KTS + KSP | Kotlin 2.0.21 | Hilt 2.60.1 | Room 2.7.1

---

## 目录

1. [项目概述](#1-项目概述)
2. [项目架构总览](#2-项目架构总览)
3. [包结构详解](#3-包结构详解)
   - [3.1 app/ — 应用入口与导航](#31-app--应用入口与导航)
   - [3.2 core/analysis/ — 引擎抽象层（核心）](#32-coreanalysis--引擎抽象层核心)
   - [3.3 core/analysis/engine/ — 引擎实现](#33-coreanalysisengine--引擎实现)
   - [3.4 core/analysis/assembler/ — 汇编器](#34-coreanalysisassembler--汇编器)
   - [3.5 core/jni/ — JNI 绑定层](#35-corejni--jni-绑定层)
   - [3.6 core/service/ — 服务层](#36-coreservice--服务层)
   - [3.7 core/di/ — 依赖注入模块](#37-coredi--依赖注入模块)
   - [3.8 core/mcp/ — MCP 服务层](#38-core-mcp--mcp-服务层)
   - [3.9 data/ — 数据持久化层](#39-data--数据持久化层)
   - [3.10 features/ — UI 功能模块](#310-features--ui-功能模块)
4. [Native C++ 层](#4-native-c-层)
5. [依赖关系总览](#5-依赖关系总览)
6. [项目运行方式](#6-项目运行方式)
7. [关键设计决策](#7-关键设计决策)

---

## 1. 项目概述

**fler** 是一款 Android 平台的 **Flutter 应用逆向分析工具**，专注于对 Android APK 中 Flutter 引擎生成的 `libapp.so` 进行静态分析、反汇编、汇编和字节级编辑。

核心功能链：

```
APK 导入 → 解包提取 libapp.so → Blutter 分析（Dart 方法/类/PP 条目）
                                → Rizin 深度分析（函数识别/交叉引用/CFG）
                                → SO 编辑器（结构查看/反汇编/十六进制编辑/字节修改/撤销）
                                → MCP 服务（AI 工具链远程调用分析能力）
```

### 技术栈

| 层级 | 技术 |
|------|------|
| UI 层 | Jetpack Compose + Material 3 + Navigation Compose |
| 状态管理 | ViewModel + StateFlow + SavedStateHandle |
| 依赖注入 | Hilt (KSP) |
| 数据持久化 | Room (SQLite) |
| 网络 | OkHttp |
| 序列化 | kotlinx.serialization (JSON) |
| 反汇编/二进制分析 | Rizin v0.9.x (静态链接) + Capstone 5.0.9 (静态链接) |
| 汇编 | Keystone (静态链接) |
| Flutter 分析 | Blutter 引擎 (动态加载) |
| 原生代码 | C++20 + NDK r27 (仅 arm64-v8a) |

---

## 2. 项目架构总览

### 分层架构图

```
┌─────────────────────────────────────────────────────────────────┐
│  UI 层 (features/)                                              │
│  ProjectScreen / SoEditorScreen / SettingsScreen / EngineScreen  │
│  ┌──────────────────────────────────┐  ┌──────────────────────┐  │
│  │  SoEditorScreen                  │  │  ProjectScreen       │  │
│  │  ├─ StructureTab (节区/符号/功能) │  │  ProjectDetailScreen │  │
│  │  ├─ DisassemblyTab (反汇编)      │  │  PpBrowserScreen     │  │
│  │  └─ HexEditorTab (十六进制编辑)   │  │  AsmBrowserScreen    │  │
│  └──────────────────────────────────┘  └──────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│  ViewModel 层 (features/*/ViewModel)                             │
│  SoEditorViewModel / ProjectViewModel / EngineViewModel / ...    │
│  (通过 Hilt 注入 AnalysisSession, DAO, BackupManager 等)        │
├─────────────────────────────────────────────────────────────────┤
│  核心抽象层 (core/)                                              │
│  ┌─────────────────────────────────────────────────────────────┐│
│  │  analysis/           analysis/engine/                       ││
│  │  BinaryAnalysisEngine ◄── RizinEngine                       ││
│  │  EmulationEngine     ◄── SelfAnalysisEngine                  ││
│  │  AnalysisSession     ◄── UnicornEnginePlaceholder            ││
│  │  EngineRegistry      ◄── UnidbgEnginePlaceholder             ││
│  │  SoEditorCache             assembler/KeystoneAssembler       ││
│  └─────────────────────────────────────────────────────────────┘│
│  ┌──────────┬──────────┬──────────────┬──────────────────────┐  │
│  │ jni/     │ service/ │ mcp/         │ di/                  │  │
│  │ JNI 绑定 │ 引擎加载 │ MCP HTTP     │ Hilt Module          │  │
│  │          │ 备份管理 │ 服务         │                      │  │
│  └──────────┴──────────┴──────────────┴──────────────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│  数据层 (data/)                                                 │
│  AppDatabase (Room) → 7 Entity + 7 DAO                          │
│  Repository (ProjectRepository / AnalysisRepository)             │
├─────────────────────────────────────────────────────────────────┤
│  原生层 (cpp/)                                                  │
│  elf_parser/ → ELF 文件解析器 (C++20)                            │
│  jni_bridge/ → JNI 桥接 (blutter / capstone / keystone / rizin) │
│  静态库: libkeystone.a + libcapstone.a + 26× librz_*.a           │
└─────────────────────────────────────────────────────────────────┘
```

### 调用流程示例

```
用户打开 SO 文件
  → SoEditorViewModel.openFile(filePath)
    → AnalysisSession.open(filePath)         // 会话层
      → EngineRegistry.pickAnalysisFor()     // 按优先级选引擎
        → RizinEngine.open()                 // 高优先级引擎
          → RizinBindings.open() → JNI → rizin_jni.cpp
      → 缓存 handle 到 pathToHandle
      → BackupManager.setCurrentFile()
    → 加载缓存或全量查询
      → session.getSections() / getSymbols() / listFunctions()
    → 加载 Blutter 标签
      → DartMethodDao + defineFunctions()
  → UI 更新 StateFlow
```

---

## 3. 包结构详解

### 3.1 app/ — 应用入口与导航

#### FlerApplication.kt

```kotlin
@HiltAndroidApp
class FlerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NativeLoader.load()  // 加载 libfler_jni.so
    }
}
```

- **职责**：Hilt 应用入口，在 `onCreate` 中加载 JNI 原生库。
- **关键依赖**：`NativeLoader`

#### MainActivity.kt

```kotlin
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setContent {
            FlerTheme {
                // 首次启动显示 OnboardingScreen
                if (showOnboarding) OnboardingScreen(...)
                else AppNavGraph()
            }
        }
    }
}
```

- **职责**：唯一 Activity，装载 Compose 主题和根导航图。首次启动显示新手引导页。

#### app/navigation/Screen.kt

定义所有路由：

| Screen 对象 | 路由 | 说明 |
|------------|------|------|
| `Projects` | `projects` | 项目管理 Tab（首页） |
| `SoEditor` | `so_editor` | SO 编辑器 Tab |
| `McpLog` | `mcp_log` | MCP 日志 Tab |
| `Settings` | `settings` | 设置 Tab |
| `SoEditorDetail` | `so_editor/{filePath}?offset=&length=` | SO 编辑器详情（Base64 编码路径） |
| `ProjectDetail` | `project_detail/{projectId}` | 项目详情页 |
| `PpBrowser` | `pp_browser/{analysisId}` | PP 条目浏览 |
| `AsmList` | `asm_list/{analysisId}` | ASM 方法列表 |
| `AsmBrowser` | `asm_browser/{analysisId}/{methodId}` | ASM 方法详情 |
| `McpSettings` | `mcp_settings` | MCP 配置 |
| `About` | `about` | 关于页 |

Tab 顺序：Projects → SoEditor → McpLog → Settings

#### app/navigation/AppNavGraph.kt

- 底部导航栏 4 个 Tab
- 子页面（ProjectDetail / SoEditorDetail / PpBrowser / AsmBrowser）隐藏底部导航栏
- 通过 Hilt `EntryPoint` 在 Composable 中获取 DAO 和 Service
- 关键导航回调：
  - `editMethodInSo`: ASM 方法 → SO 编辑器（地址换算 + 跳转）
  - `onLocateInSo`: PP 条目 → SO 编辑器定位

#### app/theme/

- Theme.kt / Color.kt / Type.kt — Material 3 主题定义

---

### 3.2 core/analysis/ — 引擎抽象层（核心）

这是整个项目的核心架构层，定义了所有分析引擎的统一接口和数据模型。

#### BinaryAnalysisEngine.kt — 核心接口文件

```kotlin
interface BinaryAnalysisEngine {
    val engineId: String
    val displayName: String
    val isAvailable: Boolean
    val capabilities: Set<AnalysisCapability>

    // 生命周期
    suspend fun open(filePath: String, options: OpenOptions): OpenResult
    suspend fun close(handle: AnalysisHandle)
    suspend fun isHandleValid(handle: AnalysisHandle): Boolean

    // ELF 结构 (capability = ELF_PARSING)
    suspend fun getFileInfo(handle: AnalysisHandle): FileInfo?
    suspend fun getSections(handle: AnalysisHandle): List<SectionInfo>
    suspend fun getSymbols(handle: AnalysisHandle, includeDynamic: Boolean): List<SymbolInfo>
    suspend fun getImports(handle: AnalysisHandle): List<ImportInfo>
    suspend fun getRelocs(handle: AnalysisHandle): List<RelocInfo>
    suspend fun scanStrings(handle: AnalysisHandle, options: StringScanOptions): List<StringInfo>

    // 函数分析 (capability = FUNCTION_ANALYSIS)
    suspend fun listFunctions(handle: AnalysisHandle): List<FunctionInfo>
    suspend fun findFunctionContaining(handle: AnalysisHandle, address: Long): FunctionInfo?
    suspend fun findFunctionsByName(handle: AnalysisHandle, query: String): List<FunctionInfo>
    suspend fun getFunctionCfg(handle: AnalysisHandle, functionOffset: Long): List<BasicBlock>
    suspend fun defineFunction(handle: AnalysisHandle, address: Long, name: String): Boolean
    suspend fun reanalyzeXrefs(handle: AnalysisHandle): Boolean

    // 反汇编 (capability = DISASSEMBLY)
    suspend fun disassemble(handle: AnalysisHandle, offset: Long, size: Long): List<DisasmInstruction>

    // 汇编 (capability = ASSEMBLY)
    suspend fun assemble(handle: AnalysisHandle, assembly: String, address: Long): ByteArray?

    // 交叉引用 (capability = XREF)
    suspend fun xrefsTo(handle: AnalysisHandle, target: Long): List<Xref>
    suspend fun xrefsFrom(handle: AnalysisHandle, from: Long): List<Xref>

    // 字节读写 (capability = BYTE_EDIT)
    suspend fun readBytes(handle: AnalysisHandle, offset: Long, size: Long): ByteArray
    suspend fun writeBytes(handle: AnalysisHandle, offset: Long, data: ByteArray): Boolean

    // 地址转换 (capability = ADDRESS_TRANSLATION)
    suspend fun paddrToVaddr(handle: AnalysisHandle, paddr: Long): Long
    suspend fun vaddrToPaddr(handle: AnalysisHandle, vaddr: Long): Long

    // 哈希 (capability = BINARY_HASH)
    suspend fun md5(handle: AnalysisHandle): String?
    suspend fun sha256(handle: AnalysisHandle): String?
    suspend fun crc32(handle: AnalysisHandle, offset: Long? = null, size: Long? = null): Long?
}
```

- **设计原则**：所有方法 `suspend`，支持协程；引擎实现内部可切到 `Dispatchers.IO`
- **能力枚举**：通过 `capabilities` 声明自身能力，`EngineMcpToolRegistry` 据此自动生成 MCP 工具
- **MCP 自动暴露**：新增 Engine 只需实现接口，无需手写 MCP 暴露代码

#### AnalysisSession.kt — 统一会话层

```kotlin
@Singleton
class AnalysisSession @Inject constructor(
    private val registry: EngineRegistry,
    private val backupManager: BackupManager
)
```

- **职责**：UI/MCP 调用引擎的唯一入口
- 封装引擎挑选流程（按优先级逐个尝试）
- 维护 `pathToHandle` 映射，同路径复用会话
- 统一注入 `BackupManager`，实现字节编辑撤销栈
- `writeBytes()` 记录补丁；`writeRawBytes()` 直接写盘不记录（用于撤销）

#### EngineRegistry.kt — 引擎注册中心

```kotlin
@Singleton
class EngineRegistry @Inject constructor()
```

- 维护 `analysisEngines` 和 `emulationEngines` 两个 Map
- 按优先级排序（Rizin=100 > Capstone=20 > SelfAnalysis=10）
- 提供 `pickAnalysisFor()` 按能力挑最高优先级引擎
- 提供 `listAnalysisSupporting()` 列出所有支持某能力的引擎（降级用）

#### 数据模型类（AnalysisTypes.kt 中定义）

```kotlin
enum class AnalysisCapability { ELF_PARSING, DISASSEMBLY, ASSEMBLY, FUNCTION_ANALYSIS, XREF, CFG, STRING_SCAN, DEMANGLE, BYTE_EDIT, ADDRESS_TRANSLATION, BINARY_HASH, SIGNATURE_MATCH, PDB_DWARF }
enum class AnalysisLevel { QUICK, STANDARD, DEEP }
data class OpenOptions(val autoAnalyze: Boolean = true, val analysisLevel: AnalysisLevel = AnalysisLevel.STANDARD)
sealed class OpenResult { class Success(handle, filePath, engineId) : OpenResult(); class Failure(reason, cause) : OpenResult() }
@JvmInline value class AnalysisHandle(val value: Long)
data class StringScanOptions(val minLen: Int = 4, val maxLen: Int = 4096, val scanSections: List<String> = emptyList())
```

#### SectionInfo.kt / SymbolInfo.kt / FunctionInfo.kt / DisasmInstruction.kt / AnalysisTypes.kt

这些是引擎抽象层的数据模型，与旧版 `core.jni` 的数据类等价，但定义在抽象层避免依赖具体 JNI 实现。

- `SectionInfo` — ELF 节区（name, type, offset, size, vaddr, paddr, flags, perm）
- `SymbolInfo` — ELF 符号（name, demangledName, address, size, type, bind, shndx, sectionName）
- `FunctionInfo` — 函数（name, offset/vaddr, size, nargs, nlocals, nbbs, callType, edges, signature, callConvention）
- `BasicBlock` — 基本块（addr, size, nInstr, succs, preds）
- `Xref` — 交叉引用（from, to, type, perm）
- `DisasmInstruction` — 反汇编指令（address, size, mnemonic, opStr, bytes）
- `StringInfo` / `ImportInfo` / `RelocInfo` / `FileInfo` — 其他分析结果类型

#### SoEditorCache.kt — 跨 ViewModel 缓存

```kotlin
@Singleton
class SoEditorCache @Inject constructor()
```

- 缓存内容：
  - `SoMetadata` — sections/symbols/functions/fileInfo（避免重复 Rizin 查询）
  - `DartLabels` — Blutter 分析的 Dart 方法标签（避免重复 DAO 查询）
  - `injectedSoPaths` — 已注入 Rizin 的 SO 路径（避免重复 defineFunctions）
- 生命周期与 `AnalysisSession` 一致（App 进程内常驻）
- 仅由 `AnalysisSession.closeAll()` 触发清理

#### EmulationEngine.kt — 仿真引擎接口

```kotlin
interface EmulationEngine {
    val engineId: String
    val displayName: String
    val isAvailable: Boolean
    val capabilities: Set<EmulationCapability>

    suspend fun open(filePath: String, options: EmulationOptions): EmulationHandle
    suspend fun close(handle: EmulationHandle)
    suspend fun loadLibrary(handle: EmulationHandle, libraryPath: String, baseAddress: Long): Long?
    suspend fun mapMemory(handle: EmulationHandle, baseAddress: Long, size: Long, perms: Int): Boolean
    suspend fun readMemory(handle: EmulationHandle, address: Long, size: Long): ByteArray
    suspend fun writeMemory(handle: EmulationHandle, address: Long, data: ByteArray): Boolean
    suspend fun readRegister(handle: EmulationHandle, name: String): Long?
    suspend fun writeRegister(handle: EmulationHandle, name: String, value: Long): Boolean
    suspend fun readAllRegisters(handle: EmulationHandle): RegisterSnapshot
    suspend fun run(handle: EmulationHandle, instrCount: Long, timeoutMs: Long): EmuStepResult
    suspend fun step(handle: EmulationHandle): EmuStepResult
    suspend fun setPc(handle: EmulationHandle, pc: Long)
    suspend fun addBreakpoint(handle: EmulationHandle, address: Long): Boolean
    suspend fun removeBreakpoint(handle: EmulationHandle, address: Long): Boolean
    suspend fun listBreakpoints(handle: EmulationHandle): List<Long>
}
```

- 为 Unicorn/Unidbg 预留的仿真接口，暂未实现

---

### 3.3 core/analysis/engine/ — 引擎实现

#### RizinEngine.kt — 主分析引擎（高优先级）

```kotlin
class RizinEngine : BinaryAnalysisEngine {
    engineId = "rizin"
    displayName = "Rizin v0.9.x"
    isAvailable = true
    capabilities = setOf(ELF_PARSING, DISASSEMBLY, ASSEMBLY, FUNCTION_ANALYSIS, XREF, CFG, STRING_SCAN, DEMANGLE, BYTE_EDIT, ADDRESS_TRANSLATION, BINARY_HASH, SIGNATURE_MATCH)
}
```

- **核心机制**：通过 `RizinBindings.cmdStr(handle, "命令j")` 执行 Rizin 命令并解析 JSON 输出
- **关键命令**：
  - `ij` → 文件信息
  - `iSj` → 节区
  - `isj` → 符号
  - `iij` → 导入
  - `irj` → 重定位
  - `aflj` → 函数列表
  - `izzj` → 字符串扫描
  - `pdj N @ addr` → 反汇编 N 条指令
  - `axtj @ addr` → 交叉引用（到）
  - `axfj @ addr` → 交叉引用（从）
  - `afbj @ addr` → 函数基本块 CFG
  - `pa "指令"` → 汇编指令→机器码
  - `ph md5/sha256/crc32` → 哈希
- **文件打开**：先尝试 RW（mode 6），失败降级到只读（mode 4），适应 Android SELinux 策略
- **函数注入**：`defineFunction()` 通过 `f name @ addr` 设置 flag，`af @ addr` 定义函数
- **xref 重建**：`reanalyzeXrefs()` 调用 `aar` 命令

#### SelfAnalysisEngine.kt — 自研引擎（fallback，低优先级）

```kotlin
class SelfAnalysisEngine(private val keystone: KeystoneAssembler) : BinaryAnalysisEngine {
    engineId = "self"
    displayName = "自研 (ElfParser + Capstone + Keystone)"
    isAvailable = true
    capabilities = setOf(ELF_PARSING, DISASSEMBLY, ASSEMBLY, BYTE_EDIT, ADDRESS_TRANSLATION, BINARY_HASH)
}
```

- 使用 `ElfParserBindings` + `CapstoneBindings` + `KeystoneAssembler` 适配
- 不做缓存，每次查询都 `open+close` 重新解析
- 不支持：函数分析、交叉引用、CFG、字符串扫描、demangle、签名匹配
- 字符串扫描：手动扫描 ASCII 可打印字符序列

#### RizinJsonParser.kt — Rizin JSON 解析器

```kotlin
internal object RizinJsonParser
```

- 解析 `ij` / `iSj` / `isj` / `iij` / `irj` / `aflj` / `izzj` / `pdj` / `axtj` / `axfj` / `afbj` 的输出
- 使用 `kotlinx.serialization.json` 手动解析（非反射）
- 对 Rizin 的 hex 地址格式（`"0x1234"` 或十进制）统一处理
- `parseDisassembly()` 对 `"invalid"` 指令改写为 `.word 0x{hex}` 显示

#### PlaceholderEngines.kt — 占位引擎

- `UnicornEnginePlaceholder` — Unicorn 仿真引擎骨架（未实现）
- `UnidbgEnginePlaceholder` — Unidbg 仿真引擎骨架（未实现）
- 两者 `isAvailable = false`，方法默认抛 `NotImplementedError`

---

### 3.4 core/analysis/assembler/ — 汇编器

#### KeystoneAssembler.kt

```kotlin
@Singleton
class KeystoneAssembler @Inject constructor() {
    fun assemble(assembly: String, address: Long = 0L): ByteArray?
}
```

- 封装 `KeystoneBindings.asm()` 调用
- 对大小写不敏感的指令，先试原文再试小写
- 汇编优先 Keystone，Rizin 的 `assemble()` 仅作 MCP fallback

---

### 3.5 core/jni/ — JNI 绑定层

所有 JNI 绑定通过 `System.loadLibrary("fler_jni")` 加载，对应 `cpp/jni_bridge/` 中的 C++ 实现。

#### NativeLoader.kt

```kotlin
object NativeLoader {
    @Synchronized fun load()  // 加载 libfler_jni.so，幂等
}
```

#### RizinBindings.kt

```kotlin
object RizinBindings {
    fun open(path: String): Long          // 创建 RzCore + 打开文件
    fun close(handle: Long)               // 释放 RzCore
    fun analyze(handle: Long): Boolean    // aaa 自动分析
    fun cmdStr(handle: Long, cmd: String): String?  // 执行 Rizin 命令
    fun readBytes(handle: Long, offset: Long, size: Int): ByteArray?
    fun writeBytes(handle: Long, offset: Long, data: ByteArray): Boolean
}
```

#### CapstoneBindings.kt

```kotlin
object CapstoneBindings {
    fun disassembleWithCapstone(code: ByteArray, address: Long): Array<DisasmInstruction>?
}
```

#### KeystoneBindings.kt

```kotlin
object KeystoneBindings {
    fun asm(assembly: String, address: Long): ByteArray?
}
```

#### ElfParserBindings.kt

```kotlin
class ElfParserBindings : AutoCloseable {
    fun open(path: String): Boolean
    fun close()
    fun getSections(): Array<ElfSection>
    fun getSymbols(): Array<ElfSymbol>
    fun getDynamicSymbols(): Array<ElfSymbol>
    fun getSectionData(name: String): ByteArray
    fun readBytes(offset: Long, size: Long): ByteArray
    fun writeBytes(offset: Long, data: ByteArray): Boolean
}
```

#### BlutterEngine.kt

```kotlin
class BlutterEngine(val dartVersion: String, val soPath: String) {
    fun analyze(libappPath: String, libflutterPath: String): BlutterResult
    fun release()
}
```

- 动态加载 `dartvm_${dartVersion}.so` 引擎包
- 调用 JNI 的 `blutter_analyze` 执行 Flutter 分析
- 返回 `BlutterResult`（含类/方法/PP 条目/地址映射）

#### DisasmInstruction.kt (core.jni)

JNI 层的反汇编指令数据类（与 `core.analysis.DisasmInstruction` 字段一致）

---

### 3.6 core/service/ — 服务层

#### EngineLoader.kt — 动态引擎加载器

```kotlin
@Singleton
class EngineLoader @Inject constructor(@ApplicationContext private val context: Context)
```

- **核心职责**：按依赖顺序加载共享库（ICU → dartvm）
- `ensureSharedLibsLoaded()` — 加载 `libc++_shared.so` / `libicudata.so` / `libicuuc.so`
- `loadEngine(dartVersion)` — 加载 `dartvm_${dartVersion}.so` 并返回 `BlutterEngine` 实例
- 自动处理 SONAME 符号链接：读取 ELF `.dynamic` 段，为版本化库名创建 symlink

#### BackupManager.kt — 撤销管理器

```kotlin
@Singleton
class BackupManager @Inject constructor(@ApplicationContext private val context: Context)
```

- **按文件管理**：每个 SO 文件有独立的 undoStack
- **持久化**：JSON 文件存储到 `filesDir/undo/{md5(filePath)}.json`
- 关键方法：
  - `setCurrentFile(filePath)` — 设置当前文件，加载持久化撤销栈
  - `createBackupIfNeeded(soFile)` — 首次编辑前创建 `.bak` 全量备份
  - `recordPatch(address, oldBytes, newBytes, soName)` — 记录补丁
  - `undo()` — 撤销（返回被撤销的 `PatchRecord`）
  - `getPatchRecords()` — 获取所有补丁记录（用于高亮）
- 最大撤销栈深度：50

#### EngineExtractor.kt — 引擎包解压

```kotlin
@Singleton
class EngineExtractor @Inject constructor(...)
```

- 解压 7z 格式引擎包到 `filesDir/engines/`
- 使用 Apache Commons Compress 库
- 禁用 ARM64 BCJ filter（`-mf=off`）

#### EnginePackManager.kt — 引擎包管理器

管理引擎包的下载、解压、版本控制

#### DualSourceDownloader.kt — 双源下载器

依次尝试主源与备用源下载引擎包

#### EngineSourceConfig.kt — 引擎源配置

配置引擎下载 URL 和版本信息

#### PatchExporter.kt — 补丁导出

导出 SO 修改记录

#### AddressTranslator.kt — 地址转换器

```kotlin
@Singleton
class AddressTranslator @Inject constructor(...)
```

- `elfAddressToFileOffsetFromElf(vaddr, soPath)` — 通过解析 ELF 节头表将虚拟地址换算为文件偏移
- 用于 ASM 方法跳转到 SO 编辑器时做地址转换

#### ApkExtractor.kt — APK 提取器

从 APK 中提取目标 so 文件（libapp.so / libflutter.so）

#### AnalysisImporter.kt — 分析结果导入

将 Blutter 分析结果（JSON 格式）导入到 Room 数据库

#### DartVersionDetector.kt — Dart 版本检测

从 `libflutter.so` 中检测 Dart 版本

---

### 3.7 core/di/ — 依赖注入模块

#### CoreModule.kt

```kotlin
@Module @InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides @Singleton fun provideOkHttpClient(): OkHttpClient
}
```

#### AnalysisModule.kt

```kotlin
@Module @InstallIn(SingletonComponent::class)
object AnalysisModule {
    @Provides @Singleton fun provideEngineRegistry(...): EngineRegistry
    @Provides @Singleton fun provideKeystoneAssembler(): KeystoneAssembler
    @Provides @Singleton fun provideAnalysisSession(...): AnalysisSession
}
```

引擎注册（按优先级）：
- `RizinEngine` — 优先级 100
- `SelfAnalysisEngine` — 优先级 10
- `UnicornEnginePlaceholder` — 优先级 50（isAvailable=false）
- `UnidbgEnginePlaceholder` — 优先级 100（isAvailable=false）

#### DatabaseModule.kt

```kotlin
@Module @InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides @Singleton fun provideAppDatabase(...): AppDatabase
    @Provides fun provideProjectDao(db: AppDatabase): ProjectDao
    @Provides fun provideAnalysisDao(db: AppDatabase): AnalysisDao
    // ... 其他 DAO
}
```

---

### 3.8 core/mcp/ — MCP 服务层

#### McpHttpServer.kt — 内嵌 HTTP 服务器

```kotlin
class McpHttpServer(
    private val protocol: McpProtocol,
    private val config: McpConfig,
    private val sessions: McpSessions,
    private val logger: McpLogger,
    private val exportRootProvider: () -> ExportRoot,
)
```

- 基于 `ServerSocket` 自实现，无第三方依赖
- 路由：
  - `GET /sse` — legacy HTTP+SSE（Claude Desktop 兼容）
  - `POST /message` — legacy 消息端点
  - `POST /mcp` — MCP Streamable HTTP JSON-RPC
  - `GET /mcp` — 服务器→客户端事件流
  - `GET /export` — 列出导出根（工作目录或默认缓存）内的文件
  - `GET /export/<file>` — 流式下载导出根内的文件（attachment）
- `exportRootProvider` 由 `McpServerManager` 注入，**每请求动态解析**：已设置工作目录 → `SafExportRoot`（SAF tree）；否则回退 `FileExportRoot(context.cacheDir/so_export)`。工作目录变更无需重启服务器即生效
- 下载路由防路径穿越：拒绝 `../`、`/`、`\`，并校验目标限定在导出根内
- 支持 Bearer Token 认证（下载路由同样受 token 保护）

#### WorkDirectory.kt — 工作目录配置（App 级）

```kotlin
@Singleton
class WorkDirectory @Inject constructor(
    @ApplicationContext private val context: Context,
)
```

- 独立 SharedPreferences `work_directory`；首次读取时把旧版 McpConfig 的 `export_tree_uri`（`mcp_server` prefs）迁移过来，老用户不丢
- `treeUri: StateFlow<String>`（SAF tree URI，空 = 未设置）；`asDocumentFile()` 解析 SAF `DocumentFile`；`displayName()` 供 UI 副标题
- 未设置时各消费方回退 App 缓存（cacheDir/so_export）：PatchExporter / McpToolHandlers.exportPatchedSo / McpHttpServer /export 根

#### ExportRoot.kt — /export 下载根抽象

```kotlin
interface ExportRoot {
    fun list(): List<ExportFileInfo>
    fun open(name: String): InputStream?
    fun prepare()
}
```

- `FileExportRoot`：本地目录实现（canonical 路径校验）
- `SafExportRoot`：SAF tree 实现（DocumentFile 遍历 + ContentResolver 流，与工作目录解耦）
- 设置页一级「工作目录」卡片选择/更换/清除；MCP 设置页不再含导出文件夹项

#### McpToolHandlers.kt — MCP 工具处理器

定义 MCP 工具的标准结构和处理逻辑

#### EngineMcpToolRegistry.kt — 引擎能力自动暴露

```kotlin
@Singleton
class EngineMcpToolRegistry @Inject constructor(
    private val registry: EngineRegistry,
    private val session: AnalysisSession
)
```

- 根据 `EngineRegistry` 中所有引擎的 `capabilities` 自动生成 `engine_*` 前缀的 MCP 工具
- 自动生成的工具列表：

| MCP 工具 | 说明 |
|----------|------|
| `engine_list_engines` | 列出注册引擎及能力 |
| `engine_open` | 打开 SO 会话 |
| `engine_close` | 关闭会话 |
| `engine_get_info` | 文件信息 |
| `engine_list_sections` | 节区列表 |
| `engine_list_symbols` | 符号列表 |
| `engine_list_functions` | 函数列表 |
| `engine_find_function_at` | 按地址查找函数 |
| `engine_function_cfg` | 函数 CFG |
| `engine_xrefs_to` | 查询引用到目标 |
| `engine_xrefs_from` | 查询从源引用 |
| `engine_disassemble` | 反汇编 |
| `engine_assemble` | 汇编 |
| `engine_read_bytes` | 读字节 |
| `engine_write_bytes` | 写字节（可撤销） |
| `engine_scan_strings` | 字符串扫描 |
| `engine_md5` / `engine_sha256` / `engine_crc32` | 哈希 |

#### McpConfig.kt — MCP 配置

```kotlin
data class McpConfig(
    val enabled: Boolean = false,
    val host: String = "127.0.0.1",
    val port: Int = 8765,
    val token: String = "",
    val autoStart: Boolean = false,
)
```

#### McpLogger.kt / McpSessions.kt / McpErrors.kt / McpResource.kt / McpProtocol.kt

MCP 基础设施：日志记录、会话管理、错误处理、资源暴露、协议实现

---

### 3.9 data/ — 数据持久化层

#### AppDatabase.kt — Room 数据库

```kotlin
@Database(
    entities = [Project, Analysis, DartClass, DartMethod, PpEntry, Library, AddressMapping],
    version = 3, exportSchema = true
)
abstract class AppDatabase : RoomDatabase()
```

- 7 个 Entity + 7 个 DAO
- 提供 `cascadeDeleteProject(projectId)` 和 `cascadeDeleteAnalysis(analysisId)` 事务方法

#### 实体 (Entity)

| 实体 | 表名 | 说明 |
|------|------|------|
| `Project` | `projects` | 项目（apk_path 唯一索引，含状态机） |
| `Analysis` | `analyses` | 分析记录（外键→projects） |
| `DartClass` | `dart_classes` | Dart 类信息 |
| `DartMethod` | `dart_methods` | Dart 方法（含函数偏移/大小/伪代码） |
| `PpEntry` | `pp_entries` | PP（Pool Pointer）条目 |
| `Library` | `libraries` | 库文件路径（libapp.so/libflutter.so） |
| `AddressMapping` | `address_mappings` | 地址映射（虚拟地址↔文件偏移） |

#### DAO

| DAO | 关键方法 |
|-----|---------|
| `ProjectDao` | `insert`, `getById`, `getAll`, `updateStatus`, `deleteById` |
| `AnalysisDao` | `insert`, `getByProjectId`, `getById`, `updateLibPaths`, `deleteByProjectId` |
| `DartClassDao` | `insertAll`, `getByAnalysisId`, `deleteByAnalysisId` |
| `DartMethodDao` | `insertAll`, `getByAnalysisId`, `getById`, `getWithClass`, `deleteByAnalysisId` |
| `PpEntryDao` | `insertAll`, `getByAnalysisId`, `deleteByAnalysisId` |
| `LibraryDao` | `insert`, `getByAnalysisIdList`, `deleteByAnalysisId` |
| `AddressMappingDao` | `insertAll`, `getByProjectId`, `deleteByProjectId` |

#### Repository

- `ProjectRepository` — 项目相关的业务逻辑封装
- `AnalysisRepository` — 分析相关的业务逻辑封装

---

### 3.10 features/ — UI 功能模块

#### project/ — 项目管理

| 文件 | 说明 |
|------|------|
| `ProjectScreen.kt` | 项目列表页（Card 布局，状态指示器） |
| `ProjectDetailScreen.kt` | 项目详情页（分析记录列表 + SO 文件入口） |
| `ProjectViewModel.kt` | 项目列表 ViewModel（CRUD + APK 导入 + 分析触发） |
| `ProjectDetailViewModel.kt` | 项目详情 ViewModel |
| `ProjectState.kt` | 项目状态数据类 |

#### so_editor/ — SO 编辑器（核心功能）

| 文件 | 说明 |
|------|------|
| `SoEditorScreen.kt` | 顶层 SO 编辑器页（文件选择/打开） |
| `SoEditorDetailScreen.kt` | SO 编辑器详情页（Tab 布局 + 工具栏） |
| `SoEditorViewModel.kt` | 核心 ViewModel（状态管理 + 引擎调用 + 补丁管理） |
| `StructureTab.kt` | 结构 Tab（5 个子 Tab：Sections/Symbols/Dynamic Symbols/Functions/Strings） |
| `DisassemblyTab.kt` | 反汇编 Tab（无限滚动 + 点击编辑/长按交叉引用） |
| `HexEditorTab.kt` | 十六进制编辑 Tab |
| `CompactTextField.kt` | 紧凑型文本输入组件 |
| `AsmHelp.kt` | 汇编指令帮助 |

**SoEditorViewModel 关键状态**：

| StateFlow | 类型 | 说明 |
|-----------|------|------|
| `uiState` | `SoEditorUiState` | 文件信息/节区/符号/函数/字符串/加载状态 |
| `hexData` | `HexDataState` | 十六进制数据 |
| `disassemblyData` | `DisassemblyDataState` | 反汇编指令列表 |
| `currentTab` | `EditorTab` | 当前 Tab |
| `selectedOffset` | `Long` | 选中偏移 |
| `patchedOffsets` | `Set<Long>` | 已修改的偏移集合 |
| `flashOffset` | `Long?` | 闪烁高亮偏移 |
| `flashAlpha` | `Float` | 呼吸脉冲动画 alpha |
| `xrefData` | `XrefDataState` | 交叉引用数据 |

**StructureTab 特性**：
- 5 个子 Tab 各自独立 LazyListState，保持滚动位置
- 搜索按钮（FloatingActionButton），带红点过滤器指示
- 点击函数在反汇编 Tab 中高亮
- 闪烁动画（橙色）标记跳转目标

**DisassemblyTab 特性**：
- 无限向上滚动加载（`loadMoreBefore()`）
- `combinedClickable`：单击编辑指令，长按查看交叉引用
- 红色高亮已修改指令
- 搜索框快速定位

#### engine/ — 引擎管理

| 文件 | 说明 |
|------|------|
| `EngineViewModel.kt` | 引擎下载/管理 ViewModel |

#### settings/ — 设置

| 文件 | 说明 |
|------|------|
| `SettingsScreen.kt` | 设置主页 |
| `McpSettingsScreen.kt` | MCP 配置页 |
| `McpSettingsCard.kt` | MCP 配置卡片 |
| `AboutScreen.kt` | 关于页 |

#### mcp/ — MCP 运行时

| 文件 | 说明 |
|------|------|
| `McpServerService.kt` | 前台 Service（`FOREGROUND_SERVICE_DATA_SYNC`） |
| `McpServerManager.kt` | 服务器生命周期管理 |
| `McpLogScreen.kt` | MCP 日志查看页 |
| `McpLogViewModel.kt` | MCP 日志 ViewModel |
| `McpPatchService.kt` | MCP 补丁服务 |

#### onboarding/ — 新手引导

| 文件 | 说明 |
|------|------|
| `OnboardingScreen.kt` | 新手引导页（首次启动展示） |

#### output/ — 分析产物浏览

| 文件 | 说明 |
|------|------|
| `PpBrowserScreen.kt` | PP 条目浏览页 |
| `AsmBrowserScreen.kt` | ASM 方法详情页（伪代码查看） |
| `AsmListScreen.kt` | ASM 方法列表页 |
| `PpBrowserViewModel.kt` | PP 浏览 ViewModel |
| `AsmBrowserViewModel.kt` | ASM 浏览 ViewModel |
| `AsmListViewModel.kt` | ASM 列表 ViewModel |

#### ui/components/ — 通用 UI 组件

| 文件 | 说明 |
|------|------|
| `CardListTile.kt` | 卡片列表项 |
| `ShimmerBox.kt` | 骨架屏加载动画 |

---

## 4. Native C++ 层

### CMakeLists.txt — 构建配置

```cmake
# 静态库目录
set(FLER_LIBS_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a")

# 静态库
add_library(keystone STATIC IMPORTED)   # libkeystone.a
add_library(capstone STATIC IMPORTED)   # libcapstone.a
file(GLOB RIZIN_STATIC_LIBS "${FLER_LIBS_DIR}/librz_*.a")  # 26 个 librz_*.a

# 原生静态库
add_library(fler_native STATIC elf_parser/elf_parser.cpp)

# JNI 桥接库（共享库）
add_library(fler_jni SHARED
    jni_bridge/blutter_jni.cpp
    jni_bridge/elf_parser_jni.cpp
    jni_bridge/capstone_jni.cpp
    jni_bridge/keystone_jni.cpp
    jni_bridge/rizin_jni.cpp
)

target_link_libraries(fler_jni fler_native keystone capstone ${RIZIN_STATIC_LIBS} log android)
```

### 源文件说明

| 文件 | 包 | 说明 |
|------|------|------|
| `elf_parser/elf_parser.cpp` | — | 自研 ELF 解析器（mmap 内存映射） |
| `jni_bridge/elf_parser_jni.cpp` | `com_ai_fler_core_jni_ElfParserBindings` | ELF 解析 JNI 桥接 |
| `jni_bridge/capstone_jni.cpp` | `com_ai_fler_core_jni_CapstoneBindings` | Capstone 反汇编 JNI 桥接 |
| `jni_bridge/keystone_jni.cpp` | `com_ai_fler_core_jni_KeystoneBindings` | Keystone 汇编 JNI 桥接 |
| `jni_bridge/rizin_jni.cpp` | `com_ai_fler_core_jni_RizinBindings` | Rizin 引擎 JNI 桥接 |
| `jni_bridge/blutter_jni.cpp` | `com_ai_fler_core_jni_BlutterEngine` | Blutter 分析 JNI 桥接 |

### rizin_jni.cpp 核心实现

- `nativeOpen()` — 创建 RzCore，先尝试 RW 模式打开文件，失败降级到只读
- `nativeClose()` — 释放 RzCore
- `nativeAnalyze()` — 执行 `rz_core_analysis_all`（aaa）
- `nativeCmdStr()` — 执行 `rz_core_cmd_str` 返回字符串
- `nativeReadBytes()` / `nativeWriteBytes()` — 直接字节 IO

### blutter_jni.cpp 核心实现

- `nativeBlutterAnalyze()` — 通过 `dlopen` 动态加载引擎 so
- 调用 `blutter_analyze` 函数指针
- 包含 stderr 重定向和信号捕捉

### elf_parser.cpp 核心实现

- 使用 `mmap` 内存映射读取 ELF 文件
- 解析 ELF 文件头、节区头表、符号表
- 提供读写字节功能

---

## 5. 依赖关系总览

### 模块依赖图

```
FlerApplication
  └─ MainActivity
       └─ AppNavGraph
            ├─ ProjectScreen → ProjectViewModel
            │    ├─ ProjectRepository
            │    │    ├─ ProjectDao
            │    │    └─ AnalysisDao
            │    └─ ApkExtractor / AnalysisImporter / EngineLoader / BlutterEngine
            │
            ├─ SoEditorScreen → SoEditorViewModel
            │    ├─ AnalysisSession (Singleton)
            │    │    ├─ EngineRegistry
            │    │    │    ├─ RizinEngine → RizinBindings → JNI → rizin_jni.cpp
            │    │    │    └─ SelfAnalysisEngine → ElfParserBindings + CapstoneBindings + KeystoneAssembler
            │    │    └─ BackupManager (persist to filesDir/undo/)
            │    ├─ SoEditorCache (Singleton)
            │    ├─ KeystoneAssembler → KeystoneBindings → JNI → keystone_jni.cpp
            │    ├─ PatchExporter
            │    └─ DartMethodDao
            │
            ├─ SettingsScreen → SettingsViewModel
            │    └─ McpServerManager → McpHttpServer
            │
            └─ EngineScreen → EngineViewModel
                 └─ EnginePackManager / DualSourceDownloader / EngineExtractor / EngineLoader
```

### 关键依赖注入关系

```
@Singleton 级别:
  ├─ EngineRegistry
  ├─ AnalysisSession
  ├─ SoEditorCache
  ├─ BackupManager
  ├─ KeystoneAssembler
  ├─ EngineLoader
  ├─ EnginePackManager
  ├─ EngineExtractor
  ├─ BackupManager
  ├─ PatchExporter
  ├─ McpServerManager
  └─ Room Database (AppDatabase + 7 DAO)

@HiltViewModel 级别:
  ├─ SoEditorViewModel
  ├─ ProjectViewModel
  ├─ ProjectDetailViewModel
  ├─ EngineViewModel
  ├─ SettingsViewModel
  ├─ McpLogViewModel
  ├─ AsmBrowserViewModel
  ├─ AsmListViewModel
  └─ PpBrowserViewModel
```

---

## 6. 项目运行方式

### 环境要求

| 工具 | 版本 |
|------|------|
| Android Studio | Ladybug 或更新 |
| JDK | 21 |
| NDK | r27 (27.0.12077973) |
| Gradle | 9.5+ |
| Kotlin | 2.0.21 |
| Android SDK | 36 |

### 构建步骤

1. **克隆项目**
   ```bash
   git clone <repo-url>
   cd fler
   ```

2. **准备 Keystone 静态库**
   ```bash
   # 运行交叉编译脚本
   scripts/build-keystone.sh
   # 产物放到 app/libs/arm64-v8a/libkeystone.a
   ```

3. **准备 Rizin + Capstone 静态库**
   - 执行 build-rizin workflow 编译 26 个 `librz_*.a` + `libcapstone.a`
   - 产物放到 `app/libs/arm64-v8a/`

4. **构建 APK**
   ```bash
   # Debug 构建
   ./gradlew assembleDebug
   ```

5. **安装到设备**
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

### 运行流程

1. 首次启动显示新手引导
2. 点击 **+** 导入 APK 文件
3. 等待 APK 解包 → 提取 libapp.so/libflutter.so → 检测 Dart 版本
4. 下载对应版本的 Blutter 引擎包（如未下载）
5. 执行 Blutter 分析（Dart 方法/类/PP 条目）
6. 分析完成后，可浏览 PP 条目、ASM 伪代码
7. 在 SO 编辑器中打开 libapp.so 进行深度分析（Rizin 自动分析）
8. 查看结构、反汇编、交叉引用、十六进制编辑
9. 修改字节后保存，可撤销

---

## 7. 关键设计决策

### 7.1 引擎抽象层策略

- **所有引擎实现 `BinaryAnalysisEngine` 接口**，UI/MCP 通过 `AnalysisSession` 间接调用
- **引擎优先级**：Rizin（高）> SelfAnalysisEngine（fallback，始终可用）
- **新增引擎**：实现接口 → 在 `AnalysisModule` 注册 → 完成，不需要改 ViewModel/MCP/UI
- **MCP 自动暴露**：`EngineMcpToolRegistry` 根据 `capabilities` 自动生成 `engine_*` MCP 工具

### 7.2 Capstone 共用方案

- **blutter + Rizin + App 三方零冲突**：用户手动编译 Rizin v0.9.1 + Capstone 5.0.9 静态库
- 产物：26 个 `librz_*.a`（~40MB）+ `libcapstone.a`（36MB），放在 `app/libs/arm64-v8a/`
- 运行时不需要引擎包即可使用 SO 查看/反汇编/编辑（Capstone + Keystone 静态链接进 `libfler_jni.so`）

### 7.3 SO 编辑器撤回系统

- `BackupManager` 按文件路径管理独立 undoStack，持久化到 `filesDir/undo/{md5(filePath)}.json`
- `AnalysisSession.writeBytes()` 记录补丁；`AnalysisSession.writeRawBytes()` 直接写盘不记录
- 重新进入 SO 编辑器，修改过的指令仍显示红色高亮

### 7.4 SO 编辑器缓存方案

- **三层缓存机制**：AnalysisSession 层缓存 `pathToHandle` → Rizin `RzCore*` 指针；`@Singleton SoEditorCache` 层缓存元数据/注入状态/Dart 标签；ViewModel 层通过注入 `SoEditorCache` 访问
- **缓存策略**：`SoEditorCache` 生命周期与 `AnalysisSession` 一致，App 进程内常驻

### 7.5 汇编优先 Keystone

- ViewModel `encodeInstruction` → `keystoneAssembler.assemble`
- RizinEngine.assemble 仅作 MCP/Session 层 fallback

### 7.6 文件打开权限降级

- Rizin 先尝试 RW（mode 6）打开文件
- 失败则降级到只读（mode 4）
- 适应 Android SELinux 策略可能拒绝可执行权限的情况

### 7.7 SONAME 符号链接处理

- `EngineLoader.prepareVersionedSymlinks()` 读取 ELF `.dynamic` 段
- 为版本化库名（如 `libicudata.so.73`）自动创建 symlink 到实际文件名
- 解决 `dlopen` 加载时找不到 NEEDED 依赖的问题