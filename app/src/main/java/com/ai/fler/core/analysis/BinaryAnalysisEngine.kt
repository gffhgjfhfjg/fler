package com.ai.fler.core.analysis

/**
 * 二进制分析引擎（核心抽象接口）。
 *
 * 所有具体实现（RizinEngine / SelfAnalysisEngine / CapstoneAnalysisEngine…）
 * 都必须实现本接口。接口方法**全部声明为 suspend**，由调用方（ViewModel /
 * MCP Handler）在协程中调用；引擎实现内部可以自由切换到 Dispatchers.IO 或
 * 继续挂起。所有查询返回 null/empty 表示「不可用或失败」，避免抛异常。
 *
 * **引擎生命周期**：
 * ```
 * open(file) -> [分析类方法] -> close()
 * ```
 * open 返回的 [AnalysisHandle] 在本实例内全局唯一；同一路径多次 open
 * 会获得不同的 handle（便于会话独立管理 patch 栈）。
 *
 * **MCP 自动暴露**：[EngineMcpToolRegistry] 依据 [capabilities]
 * 自动生成对应 HTTP/RPC 工具。新增 Engine 只需要实现本接口，
 * 不需要手写 MCP 暴露代码。
 *
 * @see [AnalysisSession] 用户层统一门面，在 handle 之上提供更高层封装。
 */
interface BinaryAnalysisEngine {

    /** 引擎唯一 ID（小写英文、下划线分隔）。用于 EngineRegistry 注册键与 MCP 工具前缀。 */
    val engineId: String

    /** 引擎展示名（用于 UI 设置页与 MCP 工具说明）。 */
    val displayName: String

    /** 当前实例运行时状态：静态库已加载 / JNI 已初始化 / 具备执行某类分析的前提。 */
    val isAvailable: Boolean

    /** 本引擎支持的能力集合。能力之外的方法调用会直接返回 null/empty。 */
    val capabilities: Set<AnalysisCapability>

    /**
     * 检查某个能力是否可用（含运行时检查）。
     *
     * 默认实现按 capabilities 判断；子类可针对运行时额外条件（如未检测到 Rizin .a 则 DISASSEMBLY=false）
     * 做更细粒度判断。
     */
    fun supports(cap: AnalysisCapability): Boolean = capabilities.contains(cap) && isAvailable

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /**
     * 打开文件并按需启动分析。
     *
     * @return Success 时 handle 有效；Failure 时返回原因。
     */
    suspend fun open(filePath: String, options: OpenOptions = OpenOptions()): OpenResult

    /** 关闭会话句柄。调用后 handle 失效。 */
    suspend fun close(handle: AnalysisHandle)

    /** handle 是否当前属于本引擎且未关闭。 */
    suspend fun isHandleValid(handle: AnalysisHandle): Boolean

    // ------------------------------------------------------------------
    // ELF 结构（capability = ELF_PARSING）
    // ------------------------------------------------------------------

    suspend fun getFileInfo(handle: AnalysisHandle): FileInfo?

    suspend fun getSections(handle: AnalysisHandle): List<SectionInfo>

    suspend fun getSymbols(handle: AnalysisHandle, includeDynamic: Boolean = true): List<SymbolInfo>

    suspend fun getImports(handle: AnalysisHandle): List<ImportInfo>

    suspend fun getRelocs(handle: AnalysisHandle): List<RelocInfo>

    suspend fun scanStrings(handle: AnalysisHandle, options: StringScanOptions = StringScanOptions()): List<StringInfo>

    // ------------------------------------------------------------------
    // 函数 / 分析（capability = FUNCTION_ANALYSIS）
    // ------------------------------------------------------------------

    /** 列出已识别函数。 */
    suspend fun listFunctions(handle: AnalysisHandle): List<FunctionInfo>

    /** 按地址查找包含该地址的函数。 */
    suspend fun findFunctionContaining(handle: AnalysisHandle, address: Long): FunctionInfo?

    /** 按名称查找函数（支持 demangle 名模糊匹配）。 */
    suspend fun findFunctionsByName(handle: AnalysisHandle, query: String): List<FunctionInfo>

    /** 某函数的基本块 CFG。 */
    suspend fun getFunctionCfg(handle: AnalysisHandle, functionOffset: Long): List<BasicBlock>

    /**
     * 在指定地址定义函数并命名（用于注入外部分析结果，如 Blutter 的 Dart 方法名）。
     * 定义后，交叉引用分析可将对该地址的引用识别为函数调用。
     *
     * 默认实现返回 false（不支持）。RizinEngine 等支持函数分析的引擎可覆盖。
     */
    suspend fun defineFunction(handle: AnalysisHandle, address: Long, name: String): Boolean = false

    /**
     * 重新分析交叉引用（补充 xref 表）。
     *
     * defineFunction 已不再调用 af（改为只设 flag），因此不会破坏 xref 表。
     * 本方法主要用于其他场景下的 xref 重建。
     *
     * 默认实现 no-op。RizinEngine 覆盖为 `aac` 命令。
     */
    suspend fun reanalyzeXrefs(handle: AnalysisHandle): Boolean = false

    // ------------------------------------------------------------------
    // 反汇编（capability = DISASSEMBLY）
    // ------------------------------------------------------------------

    /**
     * 反汇编 [offset, offset+size) 字节。
     *
     * @param offset 文件偏移（若实现内部是 vaddr 语义，引擎需自行转换）。
     */
    suspend fun disassemble(
        handle: AnalysisHandle,
        offset: Long,
        size: Long
    ): List<DisasmInstruction>

    // ------------------------------------------------------------------
    // 汇编（capability = ASSEMBLY）
    // ------------------------------------------------------------------

    /**
     * 把指令文本 -> 机器码。
     *
     * @param assembly 完整指令（如 "MOV W0, #1" / "bl #0x4010"）。
     * @param address 指令目标地址（分支指令 PC-rel 计算需要）。
     * @return 机器码字节；编码失败 / 引擎不支持 返回 null。
     */
    suspend fun assemble(
        handle: AnalysisHandle,
        assembly: String,
        address: Long = 0L
    ): ByteArray?

    // ------------------------------------------------------------------
    // 交叉引用（capability = XREF）
    // ------------------------------------------------------------------

    /** 查询哪些地址引用了 target（axt）。 */
    suspend fun xrefsTo(handle: AnalysisHandle, target: Long): List<Xref>

    /** 查询该地址引用了哪些目标（axf）。 */
    suspend fun xrefsFrom(handle: AnalysisHandle, from: Long): List<Xref>

    // ------------------------------------------------------------------
    // 字节读 / 写（capability = BYTE_EDIT）
    // ------------------------------------------------------------------

    suspend fun readBytes(handle: AnalysisHandle, offset: Long, size: Long): ByteArray

    suspend fun writeBytes(handle: AnalysisHandle, offset: Long, data: ByteArray): Boolean

    // ------------------------------------------------------------------
    // 地址转换（capability = ADDRESS_TRANSLATION）
    // ------------------------------------------------------------------

    /** 文件偏移 -> 虚拟地址。 */
    suspend fun paddrToVaddr(handle: AnalysisHandle, paddr: Long): Long

    /** 虚拟地址 -> 文件偏移。 */
    suspend fun vaddrToPaddr(handle: AnalysisHandle, vaddr: Long): Long

    // ------------------------------------------------------------------
    // 哈希（capability = BINARY_HASH）
    // ------------------------------------------------------------------

    suspend fun md5(handle: AnalysisHandle): String?
    suspend fun sha256(handle: AnalysisHandle): String?
    suspend fun crc32(handle: AnalysisHandle, offset: Long? = null, size: Long? = null): Long?
}
