# Rizin 静态库集成方案（fler 项目）v2

> 目标：将 Rizin 框架以**静态库 `.a` 打包进 APK** 方式集成到 fler 项目，替代自研 ELF 解析器和 ARM64 解码器；同时**为 MCP 服务和后续 Unicorn/unidbg 集成预留架构空间**，避免每次新增功能大幅删改代码。
> 输出日期：2026-08-02
> 基础调研：[rizin-integration-research.md](file:///c:/Users/Len/AndroidStudioProjects/fler/rizin-integration-research.md)
> 上一版：[rizin-integration-plan.md](file:///c:/Users/Len/AndroidStudioProjects/fler/rizin-integration-plan.md)（v1，未考虑 MCP/Unicorn/unidbg 扩展）

---

## 0. 与 v1 方案的核心差异

| 维度 | v1 方案 | v2 方案（本文档） |
|---|---|---|
| **架构** | 直接在 ViewModel/MCP 调用 RizinBindings | 引入 `BinaryAnalysisEngine` 抽象层 + 工厂模式 |
| **MCP 集成** | 未考虑 | 工具自动注册机制，Rizin 能力自动暴露给 MCP |
| **Unicorn/unidbg 预留** | 未考虑 | 预留 `EmulationEngine` 接口 + 插件式注册 |
| **扩展性** | 新增引擎需改 ViewModel/MCP | 新增引擎只需实现接口 + 注册，零改动现有代码 |
| **废弃策略** | 直接删除旧模块 | 保留旧接口作为 fallback，渐进式迁移 |
| **Capstone 共用** | 未明确 | **三方共用同一份 libcapstone.so，零冲突**（见 §9） |

---

## 1. 架构总览

### 1.1 分层架构设计

```
┌─────────────────────────────────────────────────────────────┐
│  UI 层（Compose）                                            │
│  - SoEditorScreen / DisassemblyTab / StructureTab           │
│  - McpLogScreen / SettingsScreen                             │
└──────────────────────┬──────────────────────────────────────┘
                       │ 调用
┌──────────────────────▼──────────────────────────────────────┐
│  ViewModel 层                                                │
│  - SoEditorViewModel（持有 AnalysisSession，不直接调 Rizin）│
│  - ProjectViewModel                                          │
└──────────────────────┬──────────────────────────────────────┘
                       │ 调用
┌──────────────────────▼──────────────────────────────────────┐
│  AnalysisSession 层（统一会话，App 唯一入口）               │
│  - 持有 BinaryAnalysisEngine 实例                            │
│  - 持有 EmulationEngine 实例（可选，按需创建）              │
│  - 提供 sessionId，MCP/UI 共用同一会话                       │
└──────────────────────┬──────────────────────────────────────┘
                       │ 委托
┌──────────────────────▼──────────────────────────────────────┐
│  Engine 抽象层（接口）                                       │
│  ┌─────────────────────────┐ ┌──────────────────────────┐  │
│  │ BinaryAnalysisEngine    │ │ EmulationEngine          │  │
│  │ (静态分析能力)           │ │ (动态仿真能力，预留)     │  │
│  └────────┬────────────────┘ └────────┬─────────────────┘  │
└───────────┼──────────────────────────┼─────────────────────┘
            │ 实现                      │ 实现
┌───────────▼──────────────────────────▼─────────────────────┐
│  Engine 实现层（可插拔）                                     │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────┐ ┌──────────┐ │
│  │ RizinEngine │ │ (Fallback)  │ │Unicorn  │ │ unidbg   │ │
│  │ (主引擎)    │ │ SelfEngine  │ │Engine   │ │ Engine   │ │
│  │             │ │ (旧自研)    │ │ (预留)  │ │ (预留)   │ │
│  └─────────────┘ └─────────────┘ └─────────┘ └──────────┘ │
└──────────────────────┬─────────────────────────────────────┘
                       │ 调用
┌──────────────────────▼─────────────────────────────────────┐
│  Native 层（JNI + 静态库）                                  │
│  - rizin_jni.cpp → librz_*.a（静态链接）                    │
│  - keystone_jni.cpp → libkeystone.a                         │
│  - blutter_jni.cpp → blutter（动态加载）                    │
│  - unicorn_jni.cpp（预留）→ libunicorn.a                    │
│  - unidbg_jni.cpp（预留）→ unidbg-android.so                │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 核心设计原则
1. **依赖倒置**：ViewModel/MCP 依赖接口（`BinaryAnalysisEngine`），不依赖具体实现（`RizinEngine`）
2. **开闭原则**：新增引擎（Unicorn/unidbg）只需新建类实现接口 + 注册，不修改现有代码
3. **单一会话**：`AnalysisSession` 统一管理文件句柄、分析状态、撤销栈，UI 和 MCP 共用
4. **能力声明**：每个 Engine 声明自己支持的能力（Capability），调用方按能力调度
5. **MCP 自动暴露**：Engine 实现的公共方法自动注册为 MCP 工具，无需手写 handler

---

## 2. Engine 抽象层设计

### 2.1 BinaryAnalysisEngine 接口（静态分析）

```kotlin
// core/analysis/BinaryAnalysisEngine.kt
package com.ai.fler.core.analysis

/**
 * 二进制静态分析引擎抽象接口。
 *
 * 所有 SO 编辑器、MCP 工具、批处理脚本都通过此接口访问分析能力，
 * 不直接依赖 Rizin/Capstone/自研解码器等具体实现。
 *
 * 实现类：
 * - [RizinEngine]：主引擎，基于 Rizin 框架
 * - [SelfAnalysisEngine]：fallback，保留旧自研实现（过渡期使用）
 *
 * 扩展点：新增分析引擎只需实现此接口 + 在 [EngineRegistry] 注册。
 */
interface BinaryAnalysisEngine {

    /** 引擎标识（如 "rizin"、"self"） */
    val engineId: String

    /** 引擎能力声明，调用方据此判断是否支持某功能 */
    val capabilities: Set<AnalysisCapability>

    /** 打开 SO 文件，返回会话句柄（0 表示失败） */
    suspend fun open(filePath: String, options: OpenOptions = OpenOptions()): AnalysisHandle

    /** 关闭会话 */
    suspend fun close(handle: AnalysisHandle)

    // ========== ELF 结构 ==========

    /** 获取节区列表 */
    suspend fun getSections(handle: AnalysisHandle): List<SectionInfo>

    /** 获取符号列表（含 .symtab + .dynsym，已 demangle） */
    suspend fun getSymbols(handle: AnalysisHandle): List<SymbolInfo>

    /** 获取导入符号 */
    suspend fun getImports(handle: AnalysisHandle): List<ImportInfo>

    /** 获取入口点 */
    suspend fun getEntries(handle: AnalysisHandle): List<Long>

    /** 获取重定位 */
    suspend fun getRelocs(handle: AnalysisHandle): List<RelocInfo>

    /** 扫描字符串 */
    suspend fun getStrings(handle: AnalysisHandle, options: StringScanOptions = StringScanOptions()): List<StringInfo>

    /** 获取文件基本信息（arch/bits/endian） */
    suspend fun getFileInfo(handle: AnalysisHandle): FileInfo

    // ========== 函数分析（需 AnalysisCapability.FUNCTION_ANALYSIS） ==========

    /** 列出所有识别的函数 */
    suspend fun listFunctions(handle: AnalysisHandle): List<FunctionInfo>

    /** 获取指定地址所在函数 */
    suspend fun getFunctionAt(handle: AnalysisHandle, addr: Long): FunctionInfo?

    /** 获取函数的基本块 */
    suspend fun getBasicBlocks(handle: AnalysisHandle, funcAddr: Long): List<BasicBlock>

    // ========== 交叉引用（需 AnalysisCapability.XREF） ==========

    /** 查询谁调用了我（xref to） */
    suspend fun getXrefsTo(handle: AnalysisHandle, addr: Long): List<Xref>

    /** 查询我调用了谁（xref from） */
    suspend fun getXrefsFrom(handle: AnalysisHandle, addr: Long): List<Xref>

    // ========== 反汇编（需 AnalysisCapability.DISASSEMBLY） ==========

    /** 反汇编 N 条指令 */
    suspend fun disassemble(handle: AnalysisHandle, addr: Long, count: Int): List<DisasmInstruction>

    /** 反汇编指定地址范围 */
    suspend fun disassembleRange(handle: AnalysisHandle, addr: Long, size: Long): List<DisasmInstruction>

    // ========== 汇编（需 AnalysisCapability.ASSEMBLY） ==========

    /** 汇编单条指令为机器码 */
    suspend fun assemble(handle: AnalysisHandle, instruction: String, addr: Long): ByteArray?

    // ========== 字节读写 ==========

    /** 读取字节 */
    suspend fun readBytes(handle: AnalysisHandle, offset: Long, size: Long): ByteArray

    /** 写入字节（记录撤销） */
    suspend fun writeBytes(handle: AnalysisHandle, offset: Long, data: ByteArray): Boolean

    // ========== 地址转换 ==========

    /** 虚拟地址 → 文件偏移 */
    suspend fun vaddrToPaddr(handle: AnalysisHandle, vaddr: Long): Long

    /** 文件偏移 → 虚拟地址 */
    suspend fun paddrToVaddr(handle: AnalysisHandle, paddr: Long): Long
}

/** 分析能力枚举，Engine 声明自己支持哪些能力 */
enum class AnalysisCapability {
    ELF_PARSING,           // ELF 结构解析
    DISASSEMBLY,           // 反汇编
    ASSEMBLY,              // 汇编（指令编码）
    FUNCTION_ANALYSIS,     // 函数识别
    XREF,                  // 交叉引用
    CFG,                   // 控制流图
    STRING_SCAN,           // 字符串扫描
    DEMANGLE,              // 符号 demangle
    BYTE_EDIT,             // 字节级编辑
    ADDRESS_TRANSLATION    // 地址转换
}

/** 分析会话句柄（jlong 包装类，避免基本类型滥用） */
@JvmInline value class AnalysisHandle(val value: Long)

/** 打开选项 */
data class OpenOptions(
    val autoAnalyze: Boolean = true,    // 执行 aaa 自动分析
    val analysisLevel: AnalysisLevel = AnalysisLevel.STANDARD
)

enum class AnalysisLevel { QUICK, STANDARD, DEEP }
```

### 2.2 EmulationEngine 接口（动态仿真，预留）

```kotlin
// core/analysis/EmulationEngine.kt
package com.ai.fler.core.analysis

/**
 * 动态仿真引擎抽象接口（为 Unicorn / unidbg 预留）。
 *
 * 此接口设计兼容两种仿真模式：
 * - **裸 CPU 仿真**（Unicorn）：加载 SO 到虚拟内存，按地址执行指令
 * - **Android 模拟**（unidbg）：模拟 JNI 调用、Android API、文件系统
 *
 * 实现类：
 * - [UnicornEngine]（未实现）：基于 Unicorn 的纯 CPU 仿真
 * - [UnidbgEngine]（未实现）：基于 unidbg 的 Android 完整模拟
 *
 * 设计原则：
 * - 接口只暴露通用语义，不绑定具体仿真器
 * - 复用 [AnalysisHandle]，可在同一会话内切换分析/仿真
 * - 通过 [EmulationCapability] 声明能力，调用方按需调度
 */
interface EmulationEngine {

    val engineId: String
    val capabilities: Set<EmulationCapability>

    /**
     * 创建仿真实例。
     *
     * @param analysisHandle 关联的静态分析会话（用于获取 SO 字节、入口地址等）
     * @param options 仿真选项（架构/内存布局/Android 版本等）
     * @return 仿真实例句柄
     */
    suspend fun createEmulator(
        analysisHandle: AnalysisHandle,
        options: EmulatorOptions
    ): EmulatorHandle

    /** 加载 SO 到仿真器 */
    suspend fun loadModule(emulator: EmulatorHandle, soPath: String, baseAddr: Long? = null): ModuleHandle

    /** 调用函数（按地址或符号名） */
    suspend fun callFunction(
        emulator: EmulatorHandle,
        function: FunctionRef,    // 地址 or 符号名
        args: List<EmuValue>
    ): EmuValue

    /** 单步执行 */
    suspend fun step(emulator: EmulatorHandle, count: Int = 1): StepResult

    /** 连续运行直到断点/退出 */
    suspend fun run(emulator: EmulatorHandle): RunResult

    /** 设置断点 */
    suspend fun setBreakpoint(emulator: EmulatorHandle, addr: Long): Boolean

    /** 移除断点 */
    suspend fun removeBreakpoint(emulator: EmulatorHandle, addr: Long): Boolean

    /** 读寄存器 */
    suspend fun readRegister(emulator: EmulatorHandle, name: String): EmuValue

    /** 写寄存器 */
    suspend fun writeRegister(emulator: EmulatorHandle, name: String, value: EmuValue): Boolean

    /** 读内存 */
    suspend fun readMemory(emulator: EmulatorHandle, addr: Long, size: Long): ByteArray

    /** 写内存 */
    suspend fun writeMemory(emulator: EmulatorHandle, addr: Long, data: ByteArray): Boolean

    /** 获取调用栈 */
    suspend fun getCallStack(emulator: EmulatorHandle): List<StackFrame>

    /** 销毁仿真器 */
    suspend fun destroy(emulator: EmulatorHandle)
}

enum class EmulationCapability {
    CPU_EMULATION,       // 纯 CPU 仿真（Unicorn）
    JNI_SIMULATION,      // JNI 调用模拟（unidbg）
    SYSCALL_SIMULATION,  // 系统调用模拟
    FILE_SYSTEM,         // 文件系统模拟
    BREAKPOINT,          // 断点支持
    MEMORY_WATCH,        // 内存观察点
    BACKTRACE            // 调用栈
}

@JvmInline value class EmulatorHandle(val value: Long)
@JvmInline value class ModuleHandle(val value: Long)

/** 函数引用：地址或符号名 */
sealed class FunctionRef {
    data class ByAddr(val addr: Long) : FunctionRef()
    data class ByName(val name: String) : FunctionRef()
}

/** 仿真值（寄存器/内存值的多态表示） */
sealed class EmuValue {
    data class LongVal(val value: Long) : EmuValue()
    data class BytesVal(val value: ByteArray) : EmuValue()
    data class StringVal(val value: String) : EmuValue()
    object Void : EmuValue()
}

data class EmulatorOptions(
    val arch: String = "arm64",         // arm64/arm/x86
    val androidVersion: String? = null, // null=纯 CPU 仿真；"8.0"=unidbg 模拟
    val heapSize: Long = 32 * 1024 * 1024,
    val verbose: Boolean = false
)
```

### 2.3 EngineRegistry 注册中心

```kotlin
// core/analysis/EngineRegistry.kt
package com.ai.fler.core.analysis

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine 注册中心（Singleton）。
 *
 * 职责：
 * 1. 维护所有已注册的 [BinaryAnalysisEngine] 和 [EmulationEngine] 实例
 * 2. 按能力查询：找出支持 FUNCTION_ANALYSIS 的引擎
 * 3. 按优先级查询：多引擎时返回优先级最高的
 *
 * 扩展点：新增引擎只需在 [CoreModule] 中 provideEngine 注册，无需改动调用方。
 */
@Singleton
class EngineRegistry @Inject constructor() {

    private val analysisEngines = mutableMapOf<String, BinaryAnalysisEngine>()
    private val emulationEngines = mutableMapOf<String, EmulationEngine>()

    /** 注册静态分析引擎 */
    fun registerAnalysisEngine(engine: BinaryAnalysisEngine) {
        analysisEngines[engine.engineId] = engine
    }

    /** 注册仿真引擎 */
    fun registerEmulationEngine(engine: EmulationEngine) {
        emulationEngines[engine.engineId] = engine
    }

    /** 按能力查询静态分析引擎（按优先级返回第一个匹配的） */
    fun findAnalysisEngine(capability: AnalysisCapability): BinaryAnalysisEngine? {
        return analysisEngines.values.firstOrNull { capability in it.capabilities }
    }

    /** 按能力查询仿真引擎 */
    fun findEmulationEngine(capability: EmulationCapability): EmulationEngine? {
        return emulationEngines.values.firstOrNull { capability in it.capabilities }
    }

    /** 获取默认静态分析引擎（优先 Rizin，fallback 自研） */
    fun defaultAnalysisEngine(): BinaryAnalysisEngine? {
        return analysisEngines["rizin"] ?: analysisEngines["self"]
    }

    /** 获取所有已注册引擎信息（供设置页展示） */
    fun listEngines(): List<EngineInfo> {
        return analysisEngines.values.map { EngineInfo(it.engineId, it.capabilities, "analysis") } +
               emulationEngines.values.map { EngineInfo(it.engineId, it.capabilities, "emulation") }
    }
}

data class EngineInfo(
    val id: String,
    val capabilities: Set<Enum<*>>,
    val type: String  // "analysis" / "emulation"
)
```

### 2.4 AnalysisSession 会话管理

```kotlin
// core/analysis/AnalysisSession.kt
package com.ai.fler.core.analysis

import com.ai.fler.core.service.BackupManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 分析会话（Singleton）。
 *
 * 统一管理一个 SO 文件的分析上下文，UI 层和 MCP 工具共用同一会话。
 *
 * 职责：
 * 1. 委托 [BinaryAnalysisEngine] 执行具体分析操作
 * 2. 管理文件句柄生命周期
 * 3. 整合 [BackupManager] 撤销栈
 * 4. 为 MCP 提供 sessionId，使外部脚本能引用 UI 当前打开的文件
 *
 * 关键：调用方（ViewModel/MCP）只依赖此会话，不直接依赖具体 Engine。
 */
@Singleton
class AnalysisSession @Inject constructor(
    private val registry: EngineRegistry,
    private val backupManager: BackupManager
) {
    private var handle: AnalysisHandle = AnalysisHandle(0L)
    private var currentFilePath: String = ""
    private var engine: BinaryAnalysisEngine? = null

    /** 当前会话 ID（MCP 用） */
    val sessionId: String
        get() = if (handle.value == 0L) "" else "session_${handle.value}"

    /** 打开 SO 文件 */
    suspend fun open(filePath: String, options: OpenOptions = OpenOptions()): Result<String> {
        close()
        val eng = registry.defaultAnalysisEngine()
            ?: return Result.failure(IllegalStateException("无可用分析引擎"))
        val h = eng.open(filePath, options)
        if (h.value == 0L) return Result.failure(IllegalStateException("打开失败: $filePath"))
        handle = h
        engine = eng
        currentFilePath = filePath
        backupManager.createBackupIfNeeded(java.io.File(filePath))
        return Result.success(sessionId)
    }

    /** 关闭会话 */
    suspend fun close() {
        engine?.close(handle)
        handle = AnalysisHandle(0L)
        engine = null
        currentFilePath = ""
    }

    /** 委托调用（便捷方法） */
    suspend fun getSections() = engine!!.getSections(handle)
    suspend fun getSymbols() = engine!!.getSymbols(handle)
    suspend fun disassemble(addr: Long, count: Int) = engine!!.disassemble(handle, addr, count)
    suspend fun disassembleRange(addr: Long, size: Long) = engine!!.disassembleRange(handle, addr, size)
    suspend fun listFunctions() = engine!!.listFunctions(handle)
    suspend fun getXrefsTo(addr: Long) = engine!!.getXrefsTo(handle, addr)
    suspend fun readBytes(offset: Long, size: Long) = engine!!.readBytes(handle, offset, size)

    /** 写入字节（自动记录撤销） */
    suspend fun writeBytes(offset: Long, data: ByteArray): Boolean {
        val eng = engine ?: return false
        val oldBytes = eng.readBytes(handle, offset, data.size.toLong())
        val ok = eng.writeBytes(handle, offset, data)
        if (ok) {
            backupManager.recordPatch(offset, oldBytes, data, currentFilePath.substringAfterLast('/'))
        }
        return ok
    }

    /** 当前路径（MCP 工具展示用） */
    fun currentPath(): String = currentFilePath
}
```

---

## 3. RizinEngine 实现

### 3.1 RizinEngine 类

```kotlin
// core/analysis/rizin/RizinEngine.kt
package com.ai.fler.core.analysis.rizin

import com.ai.fler.core.analysis.*
import com.ai.fler.core.jni.RizinBindings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Rizin 框架的 [BinaryAnalysisEngine] 实现。
 *
 * 替代旧版 ElfParserBindings + CapstoneBindings，统一由 Rizin 处理：
 * - ELF 解析（librz_bin）
 * - 反汇编（librz_arch，集成 Capstone）
 * - 函数识别（librz_analysis）
 * - 交叉引用、CFG
 *
 * 汇编方向委托给 [KeystoneAssembler]（保留 Keystone，Rizin 的 rz_asm 汇编能力较弱）。
 */
@Singleton
class RizinEngine @Inject constructor(
    private val keystone: KeystoneAssembler
) : BinaryAnalysisEngine {

    override val engineId = "rizin"

    override val capabilities = setOf(
        AnalysisCapability.ELF_PARSING,
        AnalysisCapability.DISASSEMBLY,
        AnalysisCapability.ASSEMBLY,             // 委托 Keystone
        AnalysisCapability.FUNCTION_ANALYSIS,
        AnalysisCapability.XREF,
        AnalysisCapability.CFG,
        AnalysisCapability.STRING_SCAN,
        AnalysisCapability.DEMANGLE,
        AnalysisCapability.BYTE_EDIT,
        AnalysisCapability.ADDRESS_TRANSLATION
    )

    override suspend fun open(filePath: String, options: OpenOptions): AnalysisHandle {
        val h = RizinBindings.open(filePath, options.autoAnalyze)
        return AnalysisHandle(h)
    }

    override suspend fun close(handle: AnalysisHandle) {
        RizinBindings.close(handle.value)
    }

    override suspend fun getSections(handle: AnalysisHandle) =
        RizinBindings.getSections(handle.value)

    override suspend fun getSymbols(handle: AnalysisHandle) =
        RizinBindings.getSymbols(handle.value)

    override suspend fun getImports(handle: AnalysisHandle) =
        RizinBindings.getImports(handle.value)

    override suspend fun getEntries(handle: AnalysisHandle) =
        RizinBindings.getEntries(handle.value)

    override suspend fun getRelocs(handle: AnalysisHandle) =
        RizinBindings.getRelocs(handle.value)

    override suspend fun getStrings(handle: AnalysisHandle, options: StringScanOptions) =
        RizinBindings.getStrings(handle.value)

    override suspend fun getFileInfo(handle: AnalysisHandle) =
        RizinBindings.getFileInfo(handle.value)!!

    override suspend fun listFunctions(handle: AnalysisHandle) =
        RizinBindings.listFunctions(handle.value)

    override suspend fun getFunctionAt(handle: AnalysisHandle, addr: Long) =
        RizinBindings.getFunction(handle.value, addr)

    override suspend fun getBasicBlocks(handle: AnalysisHandle, funcAddr: Long) =
        RizinBindings.getBasicBlocks(handle.value, funcAddr)

    override suspend fun getXrefsTo(handle: AnalysisHandle, addr: Long) =
        RizinBindings.getXrefsTo(handle.value, addr)

    override suspend fun getXrefsFrom(handle: AnalysisHandle, addr: Long) =
        RizinBindings.getXrefsFrom(handle.value, addr)

    override suspend fun disassemble(handle: AnalysisHandle, addr: Long, count: Int) =
        RizinBindings.disassemble(handle.value, addr, count)

    override suspend fun disassembleRange(handle: AnalysisHandle, addr: Long, size: Long) =
        RizinBindings.disassembleRange(handle.value, addr, size)

    override suspend fun assemble(handle: AnalysisHandle, instruction: String, addr: Long): ByteArray? {
        // 汇编方向委托给 Keystone（Rizin 的 rz_asm 汇编能力较弱）
        return keystone.assemble(instruction, addr)
    }

    override suspend fun readBytes(handle: AnalysisHandle, offset: Long, size: Long) =
        RizinBindings.readBytes(handle.value, offset, size)

    override suspend fun writeBytes(handle: AnalysisHandle, offset: Long, data: ByteArray) =
        RizinBindings.writeBytes(handle.value, offset, data)

    override suspend fun vaddrToPaddr(handle: AnalysisHandle, vaddr: Long) =
        RizinBindings.vaddrToPaddr(handle.value, vaddr)

    override suspend fun paddrToVaddr(handle: AnalysisHandle, paddr: Long) =
        RizinBindings.paddrToVaddr(handle.value, paddr)
}
```

### 3.2 KeystoneAssembler（保留汇编方向）

```kotlin
// core/analysis/keystone/KeystoneAssembler.kt
package com.ai.fler.core.analysis.keystone

import com.ai.fler.core.jni.KeystoneBindings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keystone 汇编器封装（仅汇编方向）。
 *
 * Rizin 的 rz_asm 反汇编优秀，但汇编能力弱，因此保留 Keystone。
 * 后续可考虑切换为 rz_asm 的汇编功能，调用方无感知（通过 BinaryAnalysisEngine.assemble）。
 */
@Singleton
class KeystoneAssembler @Inject constructor() {
    fun assemble(instruction: String, addr: Long): ByteArray? {
        return KeystoneBindings.asm(instruction, addr)
    }
}
```

### 3.3 SelfAnalysisEngine（旧实现 fallback，过渡期保留）

```kotlin
// core/analysis/self/SelfAnalysisEngine.kt
package com.ai.fler.core.analysis.self

import com.ai.fler.core.analysis.*
import com.ai.fler.core.jni.Arm64EncoderBindings
import com.ai.fler.core.jni.ElfParserBindings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 旧版自研引擎（fallback，过渡期保留）。
 *
 * 在 Rizin 不可用（如引擎包未下载、库加载失败）时自动启用。
 * 仅支持基础 ELF 解析 + 反汇编（Capstone）+ 汇编（Keystone），
 * 不支持函数识别、xref、CFG 等高级能力。
 *
 * 迁移完成后可删除此类。
 */
@Singleton
class SelfAnalysisEngine @Inject constructor() : BinaryAnalysisEngine {

    override val engineId = "self"

    override val capabilities = setOf(
        AnalysisCapability.ELF_PARSING,
        AnalysisCapability.DISASSEMBLY,
        AnalysisCapability.BYTE_EDIT,
        AnalysisCapability.ADDRESS_TRANSLATION
        // 不包含 FUNCTION_ANALYSIS / XREF / CFG / STRING_SCAN
    )

    override suspend fun open(filePath: String, options: OpenOptions): AnalysisHandle {
        val parser = ElfParserBindings()
        return if (parser.open(filePath)) {
            // 用 parser 的 hashCode 作为句柄（简化实现）
            AnalysisHandle(parser.hashCode().toLong())
        } else {
            AnalysisHandle(0L)
        }
    }

    // ... 其他方法委托给 ElfParserBindings + CapstoneBindings（略）
}
```

### 3.4 UnicornEngine / UnidbgEngine（预留骨架）

```kotlin
// core/analysis/unicorn/UnicornEngine.kt
package com.ai.fler.core.analysis.unicorn

import com.ai.fler.core.analysis.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Unicorn CPU 仿真引擎（预留骨架，未实现）。
 *
 * 集成 Unicorn 静态库后，实现此类的所有方法。
 * 当前仅在 [EngineRegistry] 注册一个 disabled 占位实例，运行时报"未实现"。
 *
 * 集成步骤（未来）：
 * 1. NDK 交叉编译 libunicorn.a → app/libs/arm64-v8a/
 * 2. 新建 unicorn_jni.cpp 桥接 uc_open / uc_mem_map / uc_emu_start 等
 * 3. 实现此类所有方法
 * 4. 在 CoreModule.provideEmulationEngine() 注册
 * 5. MCP 工具自动暴露（见 §4）
 */
@Singleton
class UnicornEngine @Inject constructor() : EmulationEngine {

    override val engineId = "unicorn"

    override val capabilities = setOf(
        EmulationCapability.CPU_EMULATION,
        EmulationCapability.BREAKPOINT,
        EmulationCapability.MEMORY_WATCH,
        EmulationCapability.BACKTRACE
    )

    override suspend fun createEmulator(
        analysisHandle: AnalysisHandle,
        options: EmulatorOptions
    ): EmulatorHandle {
        throw NotImplementedError("Unicorn 引擎未集成，预计 v0.4.0 实现")
    }

    // ... 其他方法均 throw NotImplementedError
}
```

```kotlin
// core/analysis/unidbg/UnidbgEngine.kt
package com.ai.fler.core.analysis.unidbg

import com.ai.fler.core.analysis.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * unidbg Android 模拟引擎（预留骨架，未实现）。
 *
 * 与 [UnicornEngine] 的区别：
 * - Unicorn 是裸 CPU 仿真，不模拟 Android 运行时
 * - unidbg 模拟 JNI 调用、Android API、文件系统、Binder 等
 *
 * 集成步骤（未来）：
 * 1. 引入 unidbg-android 依赖（或编译为 .so）
 * 2. 新建 unidbg_jni.cpp 桥接
 * 3. 实现此类所有方法
 * 4. 注册到 EngineRegistry
 */
@Singleton
class UnidbgEngine @Inject constructor() : EmulationEngine {

    override val engineId = "unidbg"

    override val capabilities = setOf(
        EmulationCapability.CPU_EMULATION,
        EmulationCapability.JNI_SIMULATION,
        EmulationCapability.SYSCALL_SIMULATION,
        EmulationCapability.FILE_SYSTEM,
        EmulationCapability.BREAKPOINT,
        EmulationCapability.BACKTRACE
    )

    override suspend fun createEmulator(
        analysisHandle: AnalysisHandle,
        options: EmulatorOptions
    ): EmulatorHandle {
        throw NotImplementedError("unidbg 引擎未集成，预计 v0.5.0 实现")
    }

    // ... 其他方法均 throw NotImplementedError
}
```

### 3.5 Hilt DI 注册

```kotlin
// core/di/AnalysisModule.kt
package com.ai.fler.core.di

import com.ai.fler.core.analysis.*
import com.ai.fler.core.analysis.keystone.KeystoneAssembler
import com.ai.fler.core.analysis.rizin.RizinEngine
import com.ai.fler.core.analysis.self.SelfAnalysisEngine
import com.ai.fler.core.analysis.unicorn.UnicornEngine
import com.ai.fler.core.analysis.unidbg.UnidbgEngine
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AnalysisModule {

    /** 注册所有静态分析引擎（按优先级顺序） */
    @Provides @Singleton
    fun provideEngineRegistry(
        rizinEngine: RizinEngine,
        selfEngine: SelfAnalysisEngine,
        unicornEngine: UnicornEngine,
        unidbgEngine: UnidbgEngine
    ): EngineRegistry {
        return EngineRegistry().apply {
            registerAnalysisEngine(rizinEngine)        // 主引擎
            registerAnalysisEngine(selfEngine)         // fallback
            registerEmulationEngine(unicornEngine)      // 预留
            registerEmulationEngine(unidbgEngine)       // 预留
        }
    }

    /** AnalysisSession 单例，UI 和 MCP 共用 */
    @Provides @Singleton
    fun provideAnalysisSession(
        registry: EngineRegistry,
        backupManager: com.ai.fler.core.service.BackupManager
    ): AnalysisSession = AnalysisSession(registry, backupManager)
}
```

**关键收益**：新增引擎只需新建类 + 在 `AnalysisModule` 加一行 `registerXxxEngine()`，**不修改任何调用方代码**。

---

## 4. MCP 自动暴露机制

### 4.1 设计目标
- **零改动**：新增 Rizin 能力后，MCP 工具自动暴露，无需修改 `McpToolHandlers`
- **声明式**：Engine 通过注解或元数据声明可暴露的方法
- **按能力过滤**：未实现的 Engine 能力不暴露对应工具

### 4.2 EngineMcpToolRegistry 自动注册

```kotlin
// core/mcp/EngineMcpToolRegistry.kt
package com.ai.fler.core.mcp

import com.ai.fler.core.analysis.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Engine 能力 → MCP 工具的自动映射注册中心。
 *
 * 设计：
 * - 维护一份 capability → McpTool 的映射表
 * - Engine 注册到 [EngineRegistry] 后，自动扫描其 capabilities
 * - 对每个支持的 capability，把对应的 McpTool 加入工具集
 * - 调用时委托给 [AnalysisSession]（共享 UI 当前会话）
 *
 * 收益：新增 Engine 实现后，MCP 工具集自动更新，零改动 McpToolHandlers。
 */
@Singleton
class EngineMcpToolRegistry @Inject constructor(
    private val session: AnalysisSession,
    private val registry: EngineRegistry
) {
    /**
     * 根据当前可用的 Engine 能力，生成对应的 MCP 工具列表。
     */
    fun buildEngineTools(): List<McpToolHandlers.McpTool> {
        val tools = mutableListOf<McpToolHandlers.McpTool>()
        val engine = registry.defaultAnalysisEngine() ?: return tools

        if (AnalysisCapability.ELF_PARSING in engine.capabilities) {
            tools += buildSectionTool()
            tools += buildSymbolTool()
            tools += buildImportTool()
            tools += buildEntriesTool()
            tools += buildRelocTool()
            tools += buildFileInfoTool()
        }
        if (AnalysisCapability.FUNCTION_ANALYSIS in engine.capabilities) {
            tools += buildListFunctionsTool()
            tools += buildGetFunctionTool()
            tools += buildBasicBlocksTool()
        }
        if (AnalysisCapability.XREF in engine.capabilities) {
            tools += buildXrefToTool()
            tools += buildXrefFromTool()
        }
        if (AnalysisCapability.DISASSEMBLY in engine.capabilities) {
            tools += buildDisasmTool()
            tools += buildDisasmRangeTool()
        }
        if (AnalysisCapability.STRING_SCAN in engine.capabilities) {
            tools += buildStringsTool()
        }
        if (AnalysisCapability.BYTE_EDIT in engine.capabilities) {
            tools += buildReadBytesTool()
            tools += buildWriteBytesTool()
        }
        if (AnalysisCapability.ADDRESS_TRANSLATION in engine.capabilities) {
            tools += buildVaddrToPaddrTool()
            tools += buildPaddrToVaddrTool()
        }
        // 汇编能力始终暴露（委托 Keystone）
        tools += buildAssembleTool()

        return tools
    }

    // ========== 工具构建示例 ==========

    private fun buildSectionTool() = McpToolHandlers.McpTool(
        name = "so_sections",
        description = "列出当前会话 SO 的所有节区（需先调用 so_open 打开文件）",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("sessionId", buildJsonObject {
                    put("type", "string")
                    put("description", "会话 ID（so_open 返回）")
                })
            })
        }
    ) { params ->
        val sections = session.getSections()
        buildJsonArray {
            sections.forEach { s ->
                addJsonObject {
                    put("name", s.name)
                    put("vaddr", s.vaddr)
                    put("paddr", s.paddr)
                    put("size", s.size)
                    put("perm", s.perm)
                }
            }
        }
    }

    private fun buildDisasmTool() = McpToolHandlers.McpTool(
        name = "so_disasm",
        description = "反汇编指定地址的 N 条指令",
        inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("addr", buildJsonObject { put("type", "integer"); put("description", "起始地址（hex 或 dec）") })
                put("count", buildJsonObject { put("type", "integer"); put("description", "指令条数（默认 16）") })
            })
        }
    ) { params ->
        val addr = params.long("addr") ?: 0L
        val count = params.int("count") ?: 16
        val instructions = session.disassemble(addr, count)
        buildJsonArray {
            instructions.forEach { ins ->
                addJsonObject {
                    put("addr", ins.address)
                    put("mnemonic", ins.mnemonic)
                    put("opStr", ins.opStr)
                    put("bytes", ins.bytes.joinToString(" ") { "%02x".format(it) })
                }
            }
        }
    }

    // ... 其他工具构建方法略（结构相同：委托 session.xxx()）
}
```

### 4.3 McpToolHandlers 集成

```kotlin
// core/mcp/McpToolHandlers.kt（修改后）
@Singleton
class McpToolHandlers @Inject constructor(
    private val analysisDao: AnalysisDao,
    // ... 其他 DAO
    private val engineMcpToolRegistry: EngineMcpToolRegistry,  // 新增
    private val session: AnalysisSession                        // 新增
) : McpResourceProvider {

    val tools: Map<String, McpTool> = buildList {
        addAll(buildAnalysisTools())      // 原有：blutter 分析相关
        addAll(buildBrowseTools())        // 原有：DB 浏览
        addAll(buildPatchTools())         // 原有：补丁
        addAll(engineMcpToolRegistry.buildEngineTools())  // 新增：Engine 能力自动暴露
        addAll(buildEngineSessionTools())                  // 新增：会话管理（so_open/so_close）
    }.associateBy { it.name }

    /** 新增：会话管理工具 */
    private fun buildEngineSessionTools(): List<McpTool> = listOf(
        McpTool(
            name = "so_open",
            description = "打开 SO 文件进入分析会话，返回 sessionId 供后续工具使用",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("filePath", buildJsonObject { put("type", "string"); put("description", "SO 文件绝对路径") })
                    put("autoAnalyze", buildJsonObject { put("type", "boolean"); put("description", "是否执行 aaa 自动分析（默认 true）") })
                })
            }
        ) { params ->
            val filePath = params.str("filePath") ?: return@McpTool buildJsonObject { put("error", "filePath 必填") }
            val autoAnalyze = params["autoAnalyze"]?.let { (it as JsonPrimitive).content.toBoolean() } ?: true
            val result = session.open(filePath, OpenOptions(autoAnalyze = autoAnalyze))
            if (result.isSuccess) {
                buildJsonObject {
                    put("sessionId", session.sessionId)
                    put("filePath", session.currentPath())
                    put("engine", session.engineId)
                }
            } else {
                buildJsonObject { put("error", result.exceptionOrNull()?.message) }
            }
        },

        McpTool(
            name = "so_close",
            description = "关闭当前分析会话",
            inputSchema = buildJsonObject { put("type", "object") }
        ) { _ ->
            session.close()
            buildJsonObject { put("status", "closed") }
        },

        McpTool(
            name = "so_engines",
            description = "列出所有已注册的分析引擎及其能力（用于查询可用功能）",
            inputSchema = buildJsonObject { put("type", "object") }
        ) { _ ->
            val engines = engineMcpToolRegistry.registry.listEngines()
            buildJsonArray {
                engines.forEach { e ->
                    addJsonObject {
                        put("id", e.id)
                        put("type", e.type)
                        put("capabilities", e.capabilities.joinToString(",") { it.name })
                    }
                }
            }
        }
    )
}
```

### 4.4 关键收益
- **新增 Rizin 能力** → 只需在 `RizinEngine` 实现新方法 + 在 `EngineMcpToolRegistry` 加 `buildXxxTool()` 一行
- **集成 Unicorn/unidbg** → 实现各自 Engine 类，MCP 工具自动暴露仿真能力
- **切换引擎** → MCP 工具集按当前 Engine 能力动态生成，无需手写 if/else

---

## 5. SoEditorViewModel 重构

### 5.1 改动要点
- 依赖 `AnalysisSession`（Singleton），不直接持有 Engine 引用
- 不再依赖 `ElfParserBindings` / `CapstoneBindings` / `Arm64EncoderBindings`
- UI 状态从 session 获取，与 MCP 共享同一会话

```kotlin
// features/so_editor/SoEditorViewModel.kt（重构后）
@HiltViewModel
class SoEditorViewModel @Inject constructor(
    private val session: AnalysisSession,  // 新增：注入会话
    savedStateHandle: SavedStateHandle,
    private val addressMappingDao: AddressMappingDao,
    private val backupManager: BackupManager,
    private val patchExporter: PatchExporter
) : ViewModel() {

    suspend fun openFile(filePath: String) {
        _uiState.value = _uiState.value.copy(isLoading = true)
        try {
            session.open(filePath, OpenOptions(autoAnalyze = true))

            val sections = session.getSections()
            val symbols = session.getSymbols()
            val imports = session.getImports()
            val entries = session.getEntries()
            val strings = session.getStrings()
            val functions = session.listFunctions()  // 新能力
            val fileInfo = session.getFileInfo()

            _uiState.value = SoEditorUiState(
                filePath = filePath,
                fileName = File(filePath).name,
                fileSize = File(filePath).length(),
                fileInfo = fileInfo,
                sections = sections,
                symbols = symbols,
                imports = imports,
                entries = entries,
                strings = strings,
                functions = functions,  // 新能力
                isLoading = false,
                isFileOpen = true
            )
        } catch (e: Exception) {
            _uiState.value = _uiState.value.copy(
                isLoading = false,
                errorMessage = e.message
            )
        }
    }

    fun loadDisassembly(addr: Long, size: Long = 0) {
        viewModelScope.launch {
            _disassemblyData.value = _disassemblyData.value.copy(isLoading = true)
            try {
                val instructions = if (size > 0) {
                    session.disassembleRange(addr, size)
                } else {
                    session.disassemble(addr, 64)
                }
                _disassemblyData.value = DisassemblyDataState(
                    baseAddress = addr,
                    loadedSize = size,
                    instructions = instructions,
                    isLoading = false
                )
            } catch (e: Exception) {
                _disassemblyData.value = DisassemblyDataState(
                    isLoading = false, errorMessage = e.message
                )
            }
        }
    }

    /** 汇编指令编辑：委托 session.writeBytes，自动记录撤销 */
    suspend fun applyInstructionPatch(offset: Long, instruction: String, args: String): Boolean {
        val asmText = if (args.isBlank()) instruction else "$instruction $args"
        val bytes = session.assemble(asmText, offset) ?: return false
        return session.writeBytes(offset, bytes)
    }

    /** 新增：查询 xref */
    fun loadXrefsTo(addr: Long) {
        viewModelScope.launch {
            _xrefs.value = session.getXrefsTo(addr)
        }
    }
}
```

### 5.2 关键收益
- ViewModel 不再依赖任何具体 Engine 类
- 新增引擎（Unicorn/unidbg）时 ViewModel 零改动
- MCP 与 UI 共享同一 session，外部脚本可操作 UI 当前打开的 SO

---

## 6. UI 层改造

### 6.1 新增 FunctionsTab（第 4 个 Tab）
- 列出所有识别的函数（名称/偏移/大小/基本块数）
- 搜索过滤
- 点击 → 跳转 DisassemblyTab 并定位到函数起始地址
- 长按 → 上下文菜单：复制地址/查看 xref/查看 CFG

### 6.2 DisassemblyTab 改造
- 反汇编来源改为 `session.disassemble`
- 新增 **xref 面板**：点击指令显示该地址的 xref to/from
- 新增 **函数列表抽屉**：左侧滑出显示函数列表

### 6.3 StructureTab 增强
- 文件信息卡片（arch/bits/endian/machine）
- 节区列表（perm 着色：r-x 绿色、rw- 黄色）
- 符号列表（支持 demangle 切换）
- 导入表
- 入口点
- 重定位表

### 6.4 新增 EmulationTab（预留，v0.4.0+）
- 第 5 个 Tab「仿真」
- 选择 Unicorn / unidbg 引擎
- 设置入口函数 + 参数
- 单步/连续运行
- 寄存器/内存查看
- 断点管理

---

## 7. Native 层架构

### 7.1 目录结构（扩展后）
```
app/src/main/cpp/
├── include/
│   ├── rizin/           # Rizin 头文件
│   ├── capstone/       # Capstone 头文件
│   ├── unicorn/        # Unicorn 头文件（预留）
│   └── unidbg/         # unidbg 头文件（预留）
├── jni_bridge/
│   ├── blutter_jni.cpp        # 保留
│   ├── keystone_jni.cpp       # 保留
│   ├── rizin_jni.cpp          # 新增（替代 elf_parser_jni + capstone_jni）
│   ├── unicorn_jni.cpp        # 预留（v0.4.0）
│   └── unidbg_jni.cpp         # 预留（v0.5.0）
└── CMakeLists.txt
```

### 7.2 CMakeLists.txt 设计（可扩展）
```cmake
# 路径定义
set(NATIVE_DIR ${CMAKE_CURRENT_SOURCE_DIR})
set(LIB_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/arm64-v8a)

# ========== Feature 开关 ==========
option(ENABLE_RIZIN "Enable Rizin static analysis engine" ON)
option(ENABLE_UNICORN "Enable Unicorn CPU emulation engine" OFF)  # 预留
option(ENABLE_UNIDBG "Enable unidbg Android emulation engine" OFF) # 预留

# ========== Engine 注册宏（C++ 侧）==========
add_compile_definitions(
    FLER_ENABLE_RIZIN=$<BOOL:${ENABLE_RIZIN}>
    FLER_ENABLE_UNICORN=$<BOOL:${ENABLE_UNICORN}>
    FLER_ENABLE_UNIDBG=$<BOOL:${ENABLE_UNIDBG}>
)

# ========== Rizin 静态库（条件编译）==========
if(ENABLE_RIZIN)
    # 见 v1 方案的 Rizin 静态库导入，此处略
    set(RIZIN_LIBS rz_core rz_analysis rz_bin rz_arch rz_il rz_io rz_flag
                   rz_demangler rz_hash rz_crypto rz_magic rz_sign rz_util)
    foreach(lib ${RIZIN_LIBS})
        add_library(${lib} STATIC IMPORTED)
        set_target_properties(${lib} PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/lib${lib}.a)
    endforeach()
endif()

# ========== Unicorn 静态库（预留，OFF 时不编译）==========
if(ENABLE_UNICORN)
    add_library(unicorn STATIC IMPORTED)
    set_target_properties(unicorn PROPERTIES IMPORTED_LOCATION ${LIB_DIR}/libunicorn.a)
endif()

# ========== 主 JNI 库 ==========
set(JNI_SOURCES
    jni_bridge/blutter_jni.cpp
    jni_bridge/keystone_jni.cpp
)
if(ENABLE_RIZIN)
    list(APPEND JNI_SOURCES jni_bridge/rizin_jni.cpp)
endif()
if(ENABLE_UNICORN)
    list(APPEND JNI_SOURCES jni_bridge/unicorn_jni.cpp)
endif()

add_library(fler_jni SHARED ${JNI_SOURCES})

# 链接（条件）
if(ENABLE_RIZIN)
    target_link_libraries(fler_jni PRIVATE ${RIZIN_LIBS})
endif()
if(ENABLE_UNICORN)
    target_link_libraries(fler_jni PRIVATE unicorn)
endif()

# 统一的符号导出控制
target_link_options(fler_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/fler_jni.map
)
```

**关键收益**：
- 集成 Unicorn 时只需 `-DENABLE_UNICORN=ON` + 新增 `unicorn_jni.cpp`
- 集成 unidbg 时只需 `-DENABLE_UNIDBG=ON` + 新增 `unidbg_jni.cpp`
- 不修改任何现有代码（CMakeLists 只追加，不改动）

### 7.3 C++ 侧 Engine 注册（可选，预留）
未来若需要 C++ 层的 Engine 抽象（如多 Engine 共享内存），可定义：

```cpp
// include/AnalysisEngine.h（预留）
class BinaryAnalysisEngine {
public:
    virtual ~BinaryAnalysisEngine() = default;
    virtual const char* engineId() = 0;
    virtual uint32_t capabilities() = 0;
    // ...
};

// 在 rizin_jni.cpp / unicorn_jni.cpp 各自定义子类
// C++ 侧通过工厂注册，Kotlin 侧通过 EngineRegistry 查询
```

当前阶段不需要，Kotlin 侧的 `BinaryAnalysisEngine` 接口足够。

---

## 8. 后续集成路线图

### 8.1 v0.3.x：Rizin 集成（本方案）
- 编译 Rizin 静态库
- 实现 `RizinEngine` + `rizin_jni.cpp`
- 重构 ViewModel + MCP
- 删除自研 ELF/反汇编模块

### 8.2 v0.4.0：Unicorn 集成（预留）
- 交叉编译 libunicorn.a
- 实现 `UnicornEngine` + `unicorn_jni.cpp`
- 新增 EmulationTab（基础版：CPU 仿真 + 单步 + 寄存器查看）
- MCP 自动暴露仿真工具（`emu_create` / `emu_step` / `emu_read_register` 等）
- **改动量**：新增 2 个文件（`UnicornEngine.kt` + `unicorn_jni.cpp`）+ 修改 1 行 CMake
- **零改动**：ViewModel、McpToolHandlers、UI 现有代码

### 8.3 v0.5.0：unidbg 集成（预留）
- 引入 unidbg-android 依赖
- 实现 `UnidbgEngine` + `unidbg_jni.cpp`
- EmulationTab 增强：JNI 调用模拟、Android API 模拟
- MCP 自动暴露：`emu_call_jni` / `emu_load_dex` 等
- **改动量**：新增 2 个文件 + 修改 1 行 CMake
- **零改动**：现有代码

### 8.4 v0.6.0+：其他引擎扩展（开放扩展）
可能的扩展：
- **rz-ghidra**：反编译能力（如果体积可接受）
- **angr**：符号执行（需移植到 Android）
- **miasm**：符号执行 + 中间表示

每种新引擎只需：
1. 实现 `BinaryAnalysisEngine` 或 `EmulationEngine` 接口
2. 在 `AnalysisModule` 加一行 `registerXxxEngine()`
3. 新增 `.cpp` 文件 + CMake `option`

---

## 9. Capstone 共用方案（零冲突，v2 核心）

### 9.1 设计目标

**blutter、Rizin、App 三方共用同一份 `libcapstone.so`，无重复实例，零冲突。**

这是集成方案的关键决策，直接影响 APK 体积、运行时稳定性和维护成本。

### 9.2 当前架构（已验证）

经 [capstone_jni.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/capstone_jni.cpp) 与 [EngineLoader.kt#L41-L46](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/EngineLoader.kt#L41-L46) 验证，当前已实现：

```
引擎包 lib/libcapstone.so（Capstone 4.x，唯一一份）
    ↑ System.load(absolutePath)  由 EngineLoader.ensureSharedLibsLoaded()
    ↓
进程全局（default namespace）
    ↓                    ↓
blutter (dartvm_*.so)   App 的 capstone_jni.cpp
DT_NEEDED libcapstone.so  dlopen(path, RTLD_NOLOAD) + dlsym
```

**关键事实**：
- blutter 是**动态链接** libcapstone.so（DT_NEEDED 声明），不是静态链接
- capstone_jni.cpp 通过 `dlopen(RTLD_NOLOAD)` 复用已加载的 libcapstone.so
- 三方已经共用同一份，模式成熟

### 9.3 集成 Rizin 后的三方共用架构

```
引擎包 lib/libcapstone.so（Capstone，唯一一份）
    ↑ System.load(absolutePath)
    ↓
进程全局（default namespace）
    ↓                  ↓                  ↓
blutter             fler_jni.so         （废弃 capstone_jni.cpp）
DT_NEEDED           Rizin 内部           反汇编统一走 Rizin
libcapstone.so      cs_open/cs_disasm
                    ↓ 运行时符号查找
                    libcapstone.so
```

**Rizin 通过 `-Duse_sys_capstone=enabled` 动态链接 Capstone**：
- Rizin 的 .a 文件**不含** Capstone 代码（体积零增量）
- Rizin 内部调用 `cs_open` / `cs_disasm` 时，符号是 `undefined`
- 运行时由 linker 从已加载的 libcapstone.so 解析（通过 dlopen + dlsym 或直接符号查找）

### 9.4 为什么 blutter 不能改用 Rizin 内的 Capstone？

**不能**。三个本质原因：

1. **blutter 是预编译的 .so**：`DT_NEEDED libcapstone.so` 已固化在 ELF 头，linker 启动时按 SONAME 查找文件，无法重定向到 Rizin 内的符号。

2. **Android linker namespace 隔离**：fler_jni.so 内的符号（即使导出）在 `default` namespace，但 blutter 通过 `System.load(dartvm_path)` 加载时，DT_NEEDED 解析走 SONAME 查找，不会去 fler_jni.so 里找符号。

3. **即使强行让 Rizin 导出 Capstone 符号**（破坏 `-fvisibility=hidden`），blutter 的 `DT_NEEDED libcapstone.so` 仍要求文件系统存在这个文件 → 仍需引擎包提供。

**结论**：blutter 必须依赖引擎包的 libcapstone.so。因此**三方共用的最佳路径是让 Rizin 也用同一份**。

### 9.5 方案对比

| 方案 | Rizin 内的 Capstone | blutter 用的 Capstone | 冲突风险 | APK 体积增量 | LGPL 合规 |
|---|---|---|---|---|---|
| **A. 动态链接共用（推荐 ✅）** | 无，运行时 dlopen | 引擎包 libcapstone.so | **无** | **0** | 简单（仅 Rizin 代码静态链接） |
| B. 静态链接隔离 | 静态链接 + 符号隐藏 | 引擎包 libcapstone.so | 无 | +2MB | 较复杂（含 Capstone 代码） |
| C. 静态链接导出给 blutter | 静态链接 + 导出符号 | 希望用 Rizin 的 | **不可行** | +2MB | - |

**选定方案 A**：Rizin 编译时 `-Duse_sys_capstone=enabled`，与 blutter 共用引擎包的 libcapstone.so。

### 9.6 实现细节

#### 9.6.1 Rizin 编译选项
```bash
meson setup build-android \
  --cross-file .github/meson-android-aarch64.ini \
  --buildtype=release \
  --default-library=static \
  -Dstatic_runtime=true \
  -Duse_sys_capstone=enabled \   # 关键：动态链接 Capstone，.a 不含其代码
  ...
```

#### 9.6.2 CMakeLists.txt 链接策略
```cmake
# fler_jni.so 链接 Rizin .a 时，Capstone 符号是 undefined
# 用 --allow-shlib-undefined 让链接器不报错（运行时由 dlopen 解决）
target_link_options(fler_jni PRIVATE
    -Wl,--allow-shlib-undefined
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/fler_jni.map
)
```

#### 9.6.3 头文件准备
```bash
# 从 Capstone 源码获取头文件（版本需与引擎包 libcapstone.so 匹配）
# 当前引擎包为 Capstone 4.x，Rizin v0.9.x 期望 5.0+，需版本兼容性验证（见 §9.8）
git clone --depth 1 --branch 5.0.3 https://github.com/capstone-engine/capstone
mkdir -p app/src/main/cpp/include/capstone
cp capstone/include/capstone/*.h app/src/main/cpp/include/capstone/
```

#### 9.6.4 加载顺序保证（关键）

必须确保 libcapstone.so 在 Rizin 首次调用 `cs_open` 之前已加载。

| 时机 | 加载内容 | 状态 |
|---|---|---|
| App 启动 | `System.loadLibrary("fler_jni")` → fler_jni.so（含 Rizin .a） | 自动 |
| 用户进入 SO 编辑器/分析 | `EngineLoader.ensureSharedLibsLoaded()` → libcapstone.so | **必须先执行** |
| RizinEngine.open() | `rz_core_new()` → 内部 `cs_open()` → 查找 libcapstone.so 符号 | 依赖上一步 |

**问题**：fler_jni.so 在 App 启动时就加载，但 libcapstone.so 要等引擎包下载后才加载。若用户在引擎包未就绪时调用 Rizin，`cs_open` 会失败。

**解决：延迟初始化 Rizin**

```kotlin
// RizinEngine.kt
@Singleton
class RizinEngine @Inject constructor(
    private val keystone: KeystoneAssembler,
    private val engineLoader: EngineLoader  // 新增：注入 EngineLoader
) : BinaryAnalysisEngine {

    override suspend fun open(filePath: String, options: OpenOptions): AnalysisHandle {
        // 关键：确保 libcapstone.so 已加载，再调用 rz_core_new
        engineLoader.ensureSharedLibsLoaded()

        val h = RizinBindings.open(filePath, options.autoAnalyze)
        return AnalysisHandle(h)
    }
    // ...
}
```

```kotlin
// CoreModule.kt - 注入 EngineLoader
@Provides @Singleton
fun provideRizinEngine(
    keystone: KeystoneAssembler,
    engineLoader: EngineLoader
): RizinEngine = RizinEngine(keystone, engineLoader)
```

#### 9.6.5 EngineLoader 增强（可选）

为确保 libcapstone.so 加载成功，可在 `ensureSharedLibsLoaded()` 增加校验：

```kotlin
// EngineLoader.kt
fun ensureSharedLibsLoaded() {
    synchronized(loadLock) {
        prepareVersionedSymlinks()
        for (libPath in sharedLibs) {
            val libName = File(libPath).name
            if (libName !in loadedLibs) {
                val libFile = File(engineDir, libPath)
                if (libFile.exists()) {
                    try {
                        System.load(libFile.absolutePath)
                        loadedLibs.add(libName)
                    } catch (e: UnsatisfiedLinkError) {
                        Log.w(TAG, "共享库加载失败: ${libFile.name} - ${e.message}")
                    }
                } else {
                    Log.w(TAG, "共享库缺失: ${libFile.absolutePath}")
                }
            }
        }
        // 新增：校验 libcapstone.so 是否真的可用
        if (!isCapstoneAvailable()) {
            Log.w(TAG, "libcapstone.so 加载失败，Rizin 反汇编将不可用")
        }
    }
}

/** 探测 libcapstone.so 是否已成功加载且 cs_open 符号可解析 */
private fun isCapstoneAvailable(): Boolean {
    return try {
        // 通过 CapstoneBindings 试调一次（轻量探测）
        CapstoneBindings.probeAvailable()
    } catch (e: Exception) {
        false
    }
}
```

### 9.7 三方共用的运行时流程

```
用户点击「打开 SO 文件」
    ↓
SoEditorViewModel.openFile()
    ↓
AnalysisSession.open()
    ↓
RizinEngine.open()
    ↓
engineLoader.ensureSharedLibsLoaded()    ← ① 加载 libcapstone.so 到进程
    ↓
RizinBindings.open(filePath, autoAnalyze)
    ↓ (JNI)
rz_core_new()
    ↓
rz_core_file_open()
    ↓ (用户触发 aaa 分析时)
rz_core_analysis_all()
    ↓
内部调用 cs_open(CS_ARCH_ARM64, ...)    ← ② 查找已加载的 libcapstone.so 符号 ✓
    ↓
cs_disasm_iter(...)                      ← ③ 反汇编指令
    ↓
blutter（独立流程，DT_NEEDED libcapstone.so 已由 ① 加载）✓
```

### 9.8 唯一风险：Capstone 版本匹配

**风险**：
- 引擎包的 libcapstone.so 是 **Capstone 4.x**（从 [capstone_jni.cpp#L11](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/capstone_jni.cpp#L11) 注释确认）
- Rizin v0.9.x 期望 **Capstone 5.0+**
- **可能不兼容**：cs_insn 结构体布局、API 签名在 5.0 有破坏性变更

**验证步骤（集成阶段必做）**：
```bash
# 1. 查看引擎包 libcapstone.so 的版本
strings libcapstone.so | grep -i "capstone.*version"
# 预期：4.0.x 或 5.0.x

# 2. 查看导出符号版本
nm -D libcapstone.so | grep cs_open
# 预期：T cs_open（无版本后缀）

# 3. 编译 Rizin 后跑测试
# 在真机上调用 rz_core_new() + cs_open()，若崩溃则版本不兼容
```

**不兼容时的应对策略**：

| 情况 | 解决方案 |
|---|---|
| 引擎包是 Capstone 4.x，Rizin 要 5.0+ | **升级引擎包**：重新打包 libcapstone.so 5.0.3 + 重新编译 blutter 验证兼容 |
| 无法升级引擎包 | **切换方案 B**：Rizin 静态链接自己的 Capstone（符号隐藏，与 blutter 完全隔离） |
| 版本兼容 | ✅ 方案 A 直接可用 |

### 9.9 方案 A 的优势

| 优势 | 说明 |
|---|---|
| **零冲突** | blutter、Rizin、App 三方共用同一份 libcapstone.so，无重复实例 |
| **零体积增量** | Rizin .a 不含 Capstone 代码（相比方案 B 省 2MB） |
| **版本统一** | Capstone 版本由引擎包控制，三方一致 |
| **已验证模式** | 当前 [capstone_jni.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/capstone_jni.cpp) 已用 dlopen + dlsym 成功调用同一份 libcapstone.so |
| **LGPL 合规简单** | 仅 Rizin 代码静态链接，Capstone 是动态链接，不触发 LGPLv3 静态链接的 .o 文件公开要求 |

### 9.10 备选方案 B（触发条件）

**仅当方案 A 验证失败（Capstone 版本不兼容）时启用方案 B**：

- Rizin 编译改 `-Duse_sys_capstone=disabled`，静态链接 Capstone 5.0.3
- CMakeLists.txt 加 `-fvisibility=hidden` 隐藏 Rizin 内的 Capstone 符号
- APK 体积增加约 2MB
- 与 blutter 完全隔离，无版本耦合

**切换成本**：仅改 1 个编译开关 + 重新交叉编译 Rizin，Kotlin/C++ 业务代码零改动。

---

## 10. 风险与缓解（v2 新增）

### 10.1 架构风险
| 风险 | 缓解 |
|---|---|
| **抽象层性能开销**（多次接口调用） | 接口设计为 suspend + 批量操作；高频调用（反汇编）直接 JNI |
| **Session 单例与多窗口** | 当前 Android 单窗口，未来支持多文件时改为 sessionId Map |
| **Engine 状态不一致**（MCP 与 UI 并发） | Session 内部加 Mutex，所有操作串行化 |

### 10.2 兼容性风险
| 风险 | 缓解 |
|---|---|
| **MCP 客户端依赖旧工具名** | 保留旧工具名作为别名（`list_sections` = `so_sections`） |
| **blutter 与 Rizin 共用 Capstone 冲突** | **方案 A 三方共用同一份 libcapstone.so**（见 §9），零冲突 |
| **Capstone 版本不兼容**（4.x vs 5.0+） | 集成阶段验证；不兼容时升级引擎包或切方案 B（见 §9.8） |
| **Unicorn/unidbg 未来 API 变化** | 接口只暴露通用语义，不绑定具体仿真器 API |

### 10.3 LGPLv3 合规（静态链接）
- App "关于"页提供 Rizin 源码下载链接
- 提供 fler_jni 的 .o 文件下载（便于用户重新链接）
- 包含 LGPLv3 全文 + NOTICE 文件
- **Capstone 是动态链接**（方案 A），不触发 LGPL 静态链接合规要求
- 后续可切换为动态库方案（v0.4.0+）

---

## 11. 工作量预估（v2）

| 阶段 | 工作内容 | 工作量 |
|---|---|---|
| **1. CI 编译验证** | 交叉编译 Rizin arm64 静态库 | 3–5 人天 |
| **2. 抽象层设计** | BinaryAnalysisEngine / EmulationEngine / EngineRegistry / AnalysisSession | 2–3 人天 |
| **3. CMake + rizin_jni.cpp** | 静态库导入 + 15+ JNI 函数 | 5–7 人天 |
| **4. RizinEngine + RizinBindings.kt** | Engine 实现 + Kotlin 封装 + JSON 解析 | 3–4 人天 |
| **5. SelfAnalysisEngine** | 旧实现适配新接口（fallback） | 1–2 人天 |
| **6. AnalysisSession + DI** | 会话管理 + Hilt 模块 | 1–2 人天 |
| **7. ViewModel 重构** | SoEditorViewModel 改用 session | 2–3 人天 |
| **8. UI 改造** | StructureTab 增强 + FunctionsTab + xref 面板 | 4–6 人天 |
| **9. MCP 自动暴露** | EngineMcpToolRegistry + 会话工具 | 2–3 人天 |
| **10. Unicorn/unidbg 骨架** | 预留空实现 + 注册 | 1 人天 |
| **11. 废弃模块清理** | 删除 elf_parser / decoder / CapstoneBindings | 0.5 人天 |
| **12. 测试与优化** | 真机测试 + 性能调优 | 3–5 人天 |
| **总计** | | **28–42 人天**（约 1.5–2 人月） |

---

## 12. 验收标准

### 12.1 功能验收
- [ ] APK 体积增量 ≤ 10 MB
- [ ] 任意 SO 文件可打开、反汇编、编辑、撤销
- [ ] 函数识别、xref、字符串扫描功能正常
- [ ] blutter 分析流程不受影响
- [ ] MCP 工具集自动包含 Rizin 能力（`so_open` / `so_sections` / `so_disasm` 等）
- [ ] MCP 客户端可通过 sessionId 操作 UI 当前打开的 SO

### 12.2 架构验收（关键）
- [ ] `SoEditorViewModel` 不依赖任何具体 Engine 类（`RizinEngine` / `SelfAnalysisEngine`）
- [ ] `McpToolHandlers` 不含 Rizin 特定逻辑（通过 `EngineMcpToolRegistry` 自动生成）
- [ ] 新增 Engine 类（如 `UnicornEngine`）后，**零修改** ViewModel 和 McpToolHandlers
- [ ] CMake 新增 `option(ENABLE_UNICORN ...)` 后，**零修改** 现有 CMakeLists 代码

### 12.3 性能验收
- [ ] 打开 10MB SO < 2 秒
- [ ] `aaa` 分析 10MB SO < 30 秒
- [ ] 反汇编 64 条指令 < 100ms
- [ ] MCP 工具调用延迟 < 200ms

### 12.4 Capstone 共用验收（关键）
- [ ] blutter、Rizin、App 三方共用同一份 libcapstone.so，无重复实例
- [ ] libcapstone.so 在 Rizin 首次调用 cs_open 之前已加载
- [ ] Capstone 版本兼容性验证通过（4.x vs 5.0+）
- [ ] 若版本不兼容，方案 B 切换成本 ≤ 1 人天

---

## 13. 关键设计决策汇总

| 决策 | 理由 |
|---|---|
| 引入 `BinaryAnalysisEngine` 抽象层 | 解耦 ViewModel/MCP 与具体引擎，支持多引擎共存 |
| `EngineRegistry` 注册中心 | 新增引擎零改动调用方，按能力查询 |
| `AnalysisSession` 单例 | UI 与 MCP 共享会话，外部脚本可操作当前文件 |
| `EmulationEngine` 预留接口 | 为 Unicorn/unidbg 留好抽象，未来只需实现接口 |
| `EngineMcpToolRegistry` 自动暴露 | Engine 能力变化时 MCP 工具集自动更新 |
| CMake `option` 条件编译 | Unicorn/unidbg 集成时通过开关启用，不破坏现有构建 |
| 保留 `SelfAnalysisEngine` | 过渡期 fallback，Rizin 不可用时不影响基础功能 |
| 保留 Keystone | Rizin 汇编能力弱，Keystone 成熟可靠 |
| 静态库打包进 APK | 用户即装即用，无网络依赖（LGPL 合规需提供 .o） |
| **三方共用 libcapstone.so（方案 A）** | **零冲突、零体积增量、LGPL 合规简单**（见 §9） |
| **Rizin 延迟初始化** | 确保 libcapstone.so 先加载，避免 cs_open 失败 |

---

## 附录 A：与 v1 方案的迁移对照

| v1 | v2 |
|---|---|
| `RizinBindings.kt` 直接被 ViewModel 调用 | `RizinBindings.kt` 只被 `RizinEngine` 调用 |
| `SoEditorViewModel` 持有 `rizinHandle` | `SoEditorViewModel` 持有 `AnalysisSession` |
| `McpToolHandlers` 手写 `so_sections` 等工具 | `EngineMcpToolRegistry` 自动生成 |
| 无仿真接口 | `EmulationEngine` 预留 |
| CMake 固定编译 Rizin | CMake `option(ENABLE_RIZIN)` 条件编译 |
| 新增 Unicorn 需改 ViewModel + MCP | 新增 Unicorn 只需实现接口 + 注册 |

## 附录 B：后续开发清单（供实施参考）

### v0.3.x（Rizin 集成）
1. [ ] CI 交叉编译 Rizin arm64 静态库（`-Duse_sys_capstone=enabled`）
2. [ ] **验证 Capstone 版本兼容性**：引擎包 libcapstone.so vs Rizin 期望版本
3. [ ] 若不兼容 → 升级引擎包 libcapstone.so 到 5.0.3 + 重新编译 blutter
4. [ ] 若仍不兼容 → 切换方案 B（`-Duse_sys_capstone=disabled`）
5. [ ] 新建 `core/analysis/` 包，定义接口
6. [ ] 实现 `RizinEngine` + `rizin_jni.cpp`（含 `engineLoader.ensureSharedLibsLoaded()` 延迟初始化）
7. [ ] 实现 `SelfAnalysisEngine`（适配旧代码）
8. [ ] 实现 `AnalysisSession` + `EngineRegistry`
9. [ ] 重构 `SoEditorViewModel`
10. [ ] 新增 `EngineMcpToolRegistry` + 修改 `McpToolHandlers`
11. [ ] UI 改造（StructureTab/DisassemblyTab/FunctionsTab）
12. [ ] 删除废弃模块（elf_parser / decoder / capstone_jni.cpp / CapstoneBindings.kt）
13. [ ] 真机测试：三方共用 libcapstone.so 验证 + LGPL 合规

### v0.4.0（Unicorn 集成，预留）
1. [ ] 交叉编译 libunicorn.a
2. [ ] 实现 `UnicornEngine`（替换 `throw NotImplementedError`）
3. [ ] 新建 `unicorn_jni.cpp`
4. [ ] CMake `option(ENABLE_UNICORN ON)`
5. [ ] 新增 EmulationTab UI
6. [ ] MCP 自动暴露 `emu_*` 工具
7. [ ] 真机测试

### v0.5.0（unidbg 集成，预留）
1. [ ] 引入 unidbg-android 依赖
2. [ ] 实现 `UnidbgEngine`
3. [ ] 新建 `unidbg_jni.cpp`
4. [ ] CMake `option(ENABLE_UNIDBG ON)`
5. [ ] EmulationTab 增强（JNI 调用模拟）
6. [ ] MCP 自动暴露 unidbg 工具
7. [ ] 真机测试
