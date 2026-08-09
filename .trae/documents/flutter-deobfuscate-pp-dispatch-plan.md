# fler 反混淆增强方案：Dart 对象池（PP 槽）派发解析（v2）

> 目标：解析 Dart AOT 间接调用（`blr xN` + PP 槽派发），补全调用图，并保留 UNRESOLVED 边。
> 方向：仅静态分析增强（不涉及动态仿真）。
> v2 改进：基于实际 Blutter src_code 格式和 PP 条目数据结构调研，修正解析策略。

---

## 一、现状分析

### 1.1 当前调用图构建的缺口

[DartCallGraphBuilder.collectEdges](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/DartCallGraphBuilder.kt#L238) 解析 Blutter 反汇编伪代码，只提取 `bl`/`b`/`b.*` 的直接调用。间接调用 `blr x16` 因无 `#0x` 立即数被跳过（L264 `hashIdx < 0 → continue`）。

**Dart AOT 大量方法调用走间接派发**（虚方法、接口方法、闭包、动态分发），当前调用图严重不完整。

### 1.2 Blutter src_code 实际格式（调研确认）

来自 [方案.md](file:///c:/Users/Len/AndroidStudioProjects/fler/方案.md) L852-868 的真实样例：

```
// 0x829bbc: ldr             x0, [PP, #0x428]  ; [pp+0x428] TypeArguments: <void?>
// 0x829bc0: bl              #0x34edd8  ; InitAsyncStub
// 0x829bc4: bl              #0x3a5ce8  ; [package:...] WidgetsFlutterBinding::ensureInitialized
```

关键格式：
- **PP 加载指令**：`ldr x0, [PP, #0x428]`（大写 `PP`，`#` 前缀）
- **PP 注释**：`; [pp+0x428] TypeArguments: <void?>`（分号后，小写 `[pp+0x...]`）
- **直接调用注释**：`bl #0x...  ; 方法名`（Blutter 主动恢复的语义）
- **伪操作行**：`AllocStack(0x20)`、`EnterFrame` 等会插入到 ldr 和 blr 之间

### 1.3 PP 条目实际数据结构（调研确认）

来自 [AnalysisImporter.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/AnalysisImporter.kt) L223-238 的导入 SQL：

| Room 列 | Blutter DB 字段 | 实际内容样例 |
|---------|-----------------|-------------|
| `vm_offset` | `pp_offset` | PP 槽偏移（如 0x428） |
| `file_offset` | `so_addr` | Stub 类型的 ELF 地址（如 0x3324e0），其他类型为 0 |
| `description` | `value`（缺失→`type`） | `Stub: Subtype7TestCache (0x3324e0)` / `String: "file:///..."` / `Field <ClassName.fieldName@hash>: ...` |
| `type` | `type` | String / Stub / Type / Field |

**关键发现**：
- **Stub 类型**的 `file_offset` 列已含 ELF 地址，可直接二分 `funcs` 定位方法
- **PP 条目 methodId 全部指向兜底 `<unknown>` 方法**（Blutter DB 不含方法关联），无法直接用 methodId 关联
- **caller_count 全为 0**（从未计算），可在本次方案中顺带修复

### 1.4 已有但未利用的数据

| 数据源 | 现状 |
|--------|------|
| src_code 中的 `[pp+0x...]` 注释 | `get_pp_references` MCP 工具已用它反查引用方法，但调用图构建未利用 |
| pp_entries 的 `file_offset`（Stub 的 so_addr） | 已入库但从未用于调用图解析 |
| DartCallEdge 的 UNRESOLVED 边类型 | 表设计已支持（`callee_method_id` 可空），但 `collectEdges` 找不到 callee 就 `continue`，从未产生 |

---

## 二、方案设计（v2 改进）

### 2.1 三级解析策略（按可靠性排序）

```
遇到 blr xN / br xN 间接调用
  │
  ├─ 策略 1（最优）：从 blr 行注释直接提取目标方法名
  │   Blutter 对 bl 行加了 "; 方法名" 注释，blr 行可能也有
  │   匹配格式：blr x16  ; ClassName::methodName 或 ; [package:...] ClassName::methodName
  │   命中 → 按方法名匹配 funcs → INDIRECT_CALL 边
  │
  ├─ 策略 2（次优）：向上回溯找 [pp+0x...] PP 槽引用
  │   回溯窗口 16 行（覆盖 AllocStack/EnterFrame 等伪操作插入）
  │   匹配注释中的 [pp+0x...] 或指令操作数中的 [PP, #0x...]
  │   提取 PP 槽偏移 → 查 ppIndex
  │     ├─ Stub 类型：file_offset 列有 ELF 地址 → 二分 funcs → INDIRECT_CALL 边
  │     ├─ Stub 类型：description 含 (0x...) 地址 → 二分 funcs → INDIRECT_CALL 边
  │     ├─ Field 类型：description 含 ClassName.fieldName → 按类名匹配 funcs → INDIRECT_CALL 边
  │     └─ String/Type 类型：数据非方法 → UNRESOLVED 边（标注 PP 内容）
  │
  └─ 策略 3（兜底）：无 PP 槽引用 → UNRESOLVED 边
      callee_method_id=null, callee_name="[unresolved@0x<site>]"
```

### 2.2 与 v1 方案的关键差异

| 改进点 | v1 方案 | v2 方案 | 依据 |
|--------|---------|---------|------|
| blr 行注释 | 未考虑 | **策略 1 优先从 blr 行注释提取目标** | Blutter 对 bl 行加了 `; 方法名`，blr 行可能也有 |
| 回溯窗口 | 8 行 | **16 行** | 伪操作行（AllocStack/EnterFrame）会插入到 ldr 和 blr 之间 |
| PP 注释格式匹配 | 仅 `[pp+0x...]` | **同时匹配 `[pp+0x...]`（注释）和 `[PP, #0x...]`（指令操作数）** | 实际指令用大写 `PP` + `#`，注释用小写 `pp+0x` |
| PP 目标解析 | 从 description 提取地址 | **优先用 file_offset 列（so_addr），其次从 description 提取** | Stub 类型的 file_offset 已含 ELF 地址，无需解析文本 |
| Stub 地址提取 | 仅从 description | **file_offset 优先，description 的 `(0x...)` 兜底** | file_offset 是结构化数据，description 是文本 |
| caller_count | 未涉及 | **build 完成后回写 pp_entries.caller_count** | 当前全为 0，PpBrowserViewModel.TOP_CALLERS 筛选失效 |
| PP 正则 | `\[pp\+0x[0-9a-fA-F]+\]` | **增加 `\[PP,\s*#0x[0-9a-fA-F]+\]` 匹配指令操作数** | 指令操作数用大写 PP + # 前缀 |

### 2.3 新增边类型

```kotlin
private const val KIND_INDIRECT_CALL = "INDIRECT_CALL"      // blr xN + 目标解析成功
private const val KIND_INDIRECT_BRANCH = "INDIRECT_BRANCH"  // br xN + 目标解析成功
private const val KIND_UNRESOLVED = "UNRESOLVED"            // 间接调用目标未解析
// 现有：KIND_DIRECT_CALL / KIND_DIRECT_BRANCH
```

---

## 三、具体改动

### 3.1 DartCallGraphBuilder.kt — 间接调用解析（核心）

**文件**：[DartCallGraphBuilder.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/DartCallGraphBuilder.kt)

#### 改动 1：构造函数注入 PpEntryDao

```kotlin
@Singleton
class DartCallGraphBuilder @Inject constructor(
    private val dartMethodDao: DartMethodDao,
    private val dartCallGraphDao: DartCallGraphDao,
    private val ppEntryDao: PpEntryDao,  // 新增
    private val appDatabase: AppDatabase
)
```

#### 改动 2：collectEdges 改为按行索引遍历 + 间接调用解析

当前用 `src.lineSequence()` 无法回溯。改为 `src.lines()` 按索引遍历，新增间接调用分支：

```kotlin
private fun collectEdges(
    analysisId: Long,
    src: String,
    caller: Func,
    funcs: List<Func>,
    edges: HashMap<Long, DartCallEdge>,
    ppIndex: Map<Long, PpEntry>  // 新增
) {
    val lo = caller.offset
    val hi = caller.offset + caller.size
    val lines = src.lines()  // 改为 List 支持索引回溯

    for (i in lines.indices) {
        val line = lines[i]
        if (line.length < 4 || line[0] != '/' || line[1] != '/') continue
        val colon = line.indexOf(':', 2)
        if (colon <= 2) continue
        val site = strip0x(line.substring(2, colon).trim()).toLongOrNull(16) ?: continue

        val rest = line.substring(colon + 1).trim()
        if (rest.isEmpty()) continue
        val sp = nextSpace(rest)
        val mnemonic = if (sp < 0) rest else rest.substring(0, sp)

        val isCall = mnemonic == "bl"
        val isBranch = mnemonic == "b" || mnemonic.startsWith("b.")
        val isIndirectCall = mnemonic == "blr"
        val isIndirectBranch = mnemonic == "br"
        if (!isCall && !isBranch && !isIndirectCall && !isIndirectBranch) continue

        // === 直接调用（现有逻辑不变）===
        if (isCall || isBranch) {
            val op = if (sp < 0) "" else rest.substring(sp + 1)
            val hashIdx = op.indexOf('#')
            if (hashIdx < 0) continue
            // ... 现有立即数提取 + findContaining 逻辑不变 ...
        }

        // === 间接调用（新增）===
        if (isIndirectCall || isIndirectBranch) {
            val kind = if (isIndirectCall) KIND_INDIRECT_CALL else KIND_INDIRECT_BRANCH

            // 策略 1：从 blr 行注释直接提取目标方法名
            val commentTarget = extractCommentTarget(rest, funcs)
            if (commentTarget != null) {
                addEdge(analysisId, caller, commentTarget, site, kind, edges)
                continue
            }

            // 策略 2：向上回溯找 [pp+0x...] PP 槽引用
            val targetReg = extractTargetReg(rest)  // 提取 "x16"
            val ppOffset = findPpSlotRef(lines, i, targetReg)
            if (ppOffset != null) {
                val callee = resolvePpTarget(ppOffset, funcs, ppIndex)
                if (callee != null) {
                    addEdge(analysisId, caller, callee, site, kind, edges)
                } else {
                    // PP 槽已知但目标未解析 → UNRESOLVED 边
                    addUnresolvedEdge(analysisId, caller, site, ppOffset, edges)
                }
            } else {
                // 无 PP 槽引用 → UNRESOLVED 边
                addUnresolvedEdge(analysisId, caller, site, null, edges)
            }
        }
    }
}
```

#### 改动 3：策略 1 — extractCommentTarget

```kotlin
/**
 * 策略 1：从 blr 行的注释提取目标方法名。
 * Blutter 对 bl 行加了 "; 方法名" 注释，blr 行可能也有。
 * 格式：blr x16  ; ClassName::methodName
 *       blr x16  ; [package:...] ClassName::methodName
 */
private fun extractCommentTarget(rest: String, funcs: List<Func>): Func? {
    val semi = rest.indexOf(';')
    if (semi < 0) return null
    val comment = rest.substring(semi + 1).trim()
    if (comment.isEmpty() || comment.startsWith("[pp+")) return null  // PP 注释不是方法名
    // 去掉 [package:...] 前缀
    val name = comment.substringAfter("] ").trim()
    // 按 :: 分隔类名和方法名，拼成 ClassName.methodName 匹配 funcs
    val normalizedName = name.replace("::", ".")
    return funcs.firstOrNull { it.name == normalizedName }
}
```

#### 改动 4：策略 2 — findPpSlotRef（回溯窗口 16 行）

```kotlin
companion object {
    // 匹配注释格式：[pp+0x428]
    private val PP_COMMENT_REGEX = Regex("""\[pp\+0x([0-9a-fA-F]+)]""")
    // 匹配指令操作数格式：[PP, #0x428]
    private val PP_INSN_REGEX = Regex("""\[PP,\s*#0x([0-9a-fA-F]+)]""")

    private const val PP_BACKTRACK_WINDOW = 16  // 伪操作行可能插入
}

/**
 * 策略 2：向上回溯（最多 16 行）找加载目标寄存器的 PP 槽引用。
 * 同时匹配注释 [pp+0x...] 和指令操作数 [PP, #0x...]。
 */
private fun findPpSlotRef(lines: List<String>, idx: Int, reg: String): Long? {
    for (i in (idx - 1) downTo maxOf(0, idx - PP_BACKTRACK_WINDOW)) {
        val line = lines[i]
        // 确认是加载到目标寄存器（ldr x16, ...）
        if (!line.contains("ldr $reg,") && !line.contains("ldr $reg,")) continue

        // 优先匹配注释 [pp+0x...]
        PP_COMMENT_REGEX.find(line)?.let {
            return it.groupValues[1].toLongOrNull(16)
        }
        // 其次匹配指令操作数 [PP, #0x...]
        PP_INSN_REGEX.find(line)?.let {
            return it.groupValues[1].toLongOrNull(16)
        }
    }
    return null
}
```

#### 改动 5：策略 2 — resolvePpTarget（优先 file_offset）

```kotlin
/**
 * 从 PP 槽偏移解析间接调用目标。
 * 优先级 1：file_offset 列（Stub 类型的 so_addr = ELF 地址）→ 二分 funcs
 * 优先级 2：description 中的 (0x...) 地址 → 二分 funcs
 * 优先级 3：Field 类型的 ClassName.fieldName → 按类名匹配 funcs
 */
private fun resolvePpTarget(
    ppOffset: Long,
    funcs: List<Func>,
    ppIndex: Map<Long, PpEntry>
): Func? {
    val entry = ppIndex[ppOffset] ?: return null

    // 优先级 1：file_offset 列有 ELF 地址（Stub 类型）
    if (entry.fileOffset > 0) {
        val callee = findContaining(funcs, entry.fileOffset)
        if (callee != null) return callee
    }

    val desc = entry.description ?: return null

    // 优先级 2：description 中的 (0x...) 地址
    val addrMatch = Regex("""\(0x([0-9a-fA-F]+)\)""").find(desc)
    if (addrMatch != null) {
        val addr = addrMatch.groupValues[1].toLongOrNull(16)
        if (addr != null && addr > 0) {
            val callee = findContaining(funcs, addr)
            if (callee != null) return callee
        }
    }

    // 优先级 3：Field 类型按类名匹配
    // description 格式：Field <ClassName.fieldName@hash>: ...
    if (entry.type == "Field") {
        val fieldMatch = Regex("""Field <(\w+)\.""").find(desc)
        if (fieldMatch != null) {
            val className = fieldMatch.groupValues[1]
            // 返回该类的第一个方法（粗粒度匹配）
            return funcs.firstOrNull { it.name.startsWith("$className.") }
        }
    }

    return null  // String/Type 类型 → UNRESOLVED 边
}
```

#### 改动 6：addEdge / addUnresolvedEdge

```kotlin
/** 添加已解析的间接调用边（按 caller|callee 去重）。 */
private fun addEdge(
    analysisId: Long, caller: Func, callee: Func, site: Long,
    kind: String, edges: HashMap<Long, DartCallEdge>
) {
    if (callee.id == caller.id) return
    val key = (caller.id shl 32) or callee.id
    if (edges.containsKey(key)) return
    edges[key] = DartCallEdge(
        analysisId = analysisId,
        callerMethodId = caller.id,
        callerName = caller.name,
        callerVaddr = caller.offset,
        calleeMethodId = callee.id,
        calleeName = callee.name,
        calleeVaddr = callee.offset,
        calleeKind = kind,
        siteVaddr = site
    )
}

/** 产生 UNRESOLVED 边（按 site 去重，callee_method_id=null）。 */
private fun addUnresolvedEdge(
    analysisId: Long, caller: Func, site: Long,
    ppOffset: Long?, edges: HashMap<Long, DartCallEdge>
) {
    val key = site or (1L shl 63)  // 用 site 作 key，最高位置 1 避免与直接边 key 冲突
    if (edges.containsKey(key)) return
    edges[key] = DartCallEdge(
        analysisId = analysisId,
        callerMethodId = caller.id,
        callerName = caller.name,
        callerVaddr = caller.offset,
        calleeMethodId = null,
        calleeName = if (ppOffset != null) "[pp+0x${ppOffset.toString(16)}]" else "[unresolved]",
        calleeVaddr = ppOffset ?: 0L,
        calleeKind = KIND_UNRESOLVED,
        siteVaddr = site
    )
}
```

#### 改动 7：build 方法加载 PP 索引 + 回写 caller_count

```kotlin
suspend fun build(analysisId: Long): Int = withContext(Dispatchers.Default) {
    invalidate(analysisId)
    // ... 现有 funcs 加载 ...

    // 新增：加载 PP 条目索引（vmOffset → PpEntry）
    val ppEntries = ppEntryDao.getByAnalysisIdList(analysisId)
    val ppIndex = ppEntries.associateBy { it.vmOffset }

    // ... 现有分页解析，collectEdges 传入 ppIndex ...
    for (row in rows) {
        val caller = byOffset[row.functionOffset] ?: continue
        val src = row.srcCode ?: continue
        collectEdges(analysisId, src, caller, funcs, edges, ppIndex)
    }

    dartCallGraphDao.deleteByAnalysisId(analysisId)
    if (edges.isNotEmpty()) bulkInsert(edges.values)

    // 新增：回写 pp_entries.caller_count（统计每个 PP 槽被多少方法引用）
    updatePpCallerCounts(analysisId, ppEntries)

    builtIds.add(analysisId)
    edges.size
}

/** 统计每个 PP 槽被多少方法的 src_code 引用，回写 caller_count。 */
private suspend fun updatePpCallerCounts(analysisId: Long, ppEntries: List<PpEntry>) {
    if (ppEntries.isEmpty()) return
    val counts = HashMap<Long, Int>(ppEntries.size)
    for (pp in ppEntries) {
        val target = "[pp+0x${pp.vmOffset.toString(16)}]"
        val count = dartMethodDao.countSrcReferences(analysisId, target)
        if (count > 0) counts[pp.id] = count
    }
    if (counts.isNotEmpty()) {
        ppEntryDao.updateCallerCounts(counts)
    }
}
```

#### 改动 8：新增辅助方法 extractTargetReg

```kotlin
/** 从 "blr x16" 提取目标寄存器 "x16"。 */
private fun extractTargetReg(rest: String): String {
    val sp = nextSpace(rest)
    if (sp < 0) return ""
    val op = rest.substring(sp + 1).trim()
    return op.substringBefore(',').trim()  // "x16" 或 "x16,x17"
}
```

### 3.2 PpEntryDao.kt — 新增 caller_count 批量更新

**文件**：[PpEntryDao.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/data/dao/PpEntryDao.kt)

```kotlin
/** 批量更新 PP 条目的 caller_count。 */
@Query("UPDATE pp_entries SET caller_count = :count WHERE id = :id")
suspend fun updateCallerCount(id: Long, count: Int)

/** 批量更新（逐条 UPDATE，在单事务中执行）。 */
suspend fun updateCallerCounts(counts: Map<Long, Int>) {
    counts.forEach { (id, count) -> updateCallerCount(id, count) }
}
```

### 3.3 DartMethodDao.kt — 新增 src_code 引用计数

**文件**：[DartMethodDao.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/data/dao/DartMethodDao.kt)

```kotlin
/** 统计 src_code 中引用指定 PP 槽的方法数（用于 caller_count 回写）。 */
@Query(
    "SELECT COUNT(*) FROM dart_methods WHERE analysis_id = :analysisId " +
        "AND src_code LIKE '%' || :target || '%'"
)
suspend fun countSrcReferences(analysisId: Long, target: String): Int
```

### 3.4 DartCallGraphDao.kt — UNRESOLVED 边查询 + 边类型统计

**文件**：[DartCallGraphDao.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/data/dao/DartCallGraphDao.kt)

```kotlin
/** 查询某分析的 UNRESOLVED 边。 */
@Query(
    "SELECT * FROM dart_call_edges WHERE analysis_id = :analysisId " +
        "AND callee_kind = 'UNRESOLVED' ORDER BY site_vaddr LIMIT :limit"
)
suspend fun getUnresolvedEdges(analysisId: Long, limit: Int = 200): List<DartCallEdge>

/** 统计各类型边数量。 */
@Query(
    "SELECT callee_kind AS kind, COUNT(*) AS count FROM dart_call_edges " +
        "WHERE analysis_id = :analysisId GROUP BY callee_kind"
)
suspend fun getEdgeKindStats(analysisId: Long): List<EdgeKindStat>

/** 按边类型查询被调方（支持 INDIRECT_CALL 筛选）。 */
@Query(
    "SELECT * FROM dart_call_edges WHERE analysis_id = :analysisId " +
        "AND caller_method_id = :callerId AND callee_kind IN (:kinds) " +
        "ORDER BY site_vaddr LIMIT :limit"
)
suspend fun calleesOfByKind(
    analysisId: Long, callerId: Long, kinds: List<String>, limit: Int
): List<DartCallEdge>
```

```kotlin
data class EdgeKindStat(val kind: String, val count: Int)
```

### 3.5 SoEditorViewModel.kt — 展示间接调用边

**文件**：[SoEditorViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt)

在 `supplementDartCallGraph` 中查间接调用边：

```kotlin
private suspend fun supplementDartCallGraph(
    address: Long, to: MutableList<Xref>, from: MutableList<Xref>
) {
    // ... 现有 callersOfMethod/calleesOf 逻辑 ...

    // 新增：查间接调用边
    val dartMethodId = dartMethodIndex?.binarySearchBy(address) { it.functionOffset ?: 0 }?.let {
        dartMethodIndex!![it].id
    }
    if (dartMethodId != null && _graphAnalysisId > 0) {
        val indirectCallees = dartCallGraphDao.calleesOfByKind(
            _graphAnalysisId, dartMethodId,
            listOf("INDIRECT_CALL", "INDIRECT_BRANCH"), 50
        )
        for (edge in indirectCallees) {
            from.add(Xref(
                type = XrefType.CALL,
                fromAddr = edge.callerVaddr,
                toAddr = edge.calleeVaddr,
                fromName = edge.callerName,
                toName = "${edge.calleeName} [indirect]"
            ))
        }
    }
}
```

### 3.6 McpToolHandlers.kt — 增强 + 新增工具

**文件**：[McpToolHandlers.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/McpToolHandlers.kt)

**改动 1：`dart_call_graph_status` 增加边类型统计**

在返回的 JSON 中新增 `edgeKindStats` 字段，包含各边类型的数量。

**改动 2：新增 `list_unresolved_calls` 工具**

```kotlin
McpTool(
    name = "list_unresolved_calls",
    description = "列出某次分析中未解析的间接调用（blr xN + PP 槽），含调用点地址和 PP 槽偏移。" +
        "用于定位调用图缺口，辅助人工分析间接派发目标。analysisId 可省略"
) { p ->
    val id = resolveAnalysisId(p, "list_unresolved_calls")
    val limit = (p.int("limit") ?: 100).coerceIn(1, 500)
    val edges = dartCallGraphDao.getUnresolvedEdges(id, limit)
    // 返回 caller/callee_name(含[pp+0x...])/site_vaddr
}
```

### 3.7 DisassemblyTab.kt — 间接调用标注

**文件**：[DisassemblyTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt)

在反汇编指令行中，如果该地址是 UNRESOLVED 边的调用点，标注 `[indirect]`：

```kotlin
// 在指令渲染中检查 unresolvedSites
val unresolvedSite = unresolvedSites[instruction.address]
if (unresolvedSite != null) {
    Text(
        text = "[indirect] ${unresolvedSite.calleeName}",
        color = MaterialTheme.colorScheme.tertiary,
        style = MaterialTheme.typography.labelSmall
    )
}
```

---

## 四、数据流

```
Blutter 分析
  → AnalysisImporter 导入 pp_entries（已有，vmOffset/file_offset/type/description）
  → DartCallGraphBuilder.build
      → 加载 pp_entries 构建 ppIndex: Map<vmOffset, PpEntry>（新增）
      → collectEdges 逐方法解析 src_code
          → 直接调用 bl #0x... → DIRECT_CALL 边（已有）
          → 间接调用 blr xN
              → 策略 1：blr 行注释 ; 方法名 → INDIRECT_CALL 边（新增）
              → 策略 2：回溯找 [pp+0x...] → 查 ppIndex
                  → Stub: file_offset 或 description(0x...) 地址 → INDIRECT_CALL 边
                  → Field: 类名匹配 → INDIRECT_CALL 边
                  → String/Type: → UNRESOLVED 边
              → 策略 3：无 PP 引用 → UNRESOLVED 边
      → 回写 pp_entries.caller_count（新增）
  → dart_call_edges 表（5 种边类型）
  → MCP 工具：dart_call_graph_status（增强）/ list_unresolved_calls（新增）
  → SoEditorViewModel.supplementDartCallGraph（展示间接边）
  → DisassemblyTab（间接调用标注）
```

---

## 五、假设与决策

### 5.1 关键假设

1. **Blutter 可能在 blr 行加了 `; 方法名` 注释**：基于 Blutter 对 bl 行的注释行为推断。若 blr 行无注释，策略 1 不命中，降级到策略 2。
2. **回溯 16 行覆盖伪操作插入**：实际样例显示 `AllocStack`/`EnterFrame` 等伪操作行会插入到 ldr 和 blr 之间，16 行应足够覆盖。
3. **Stub 类型 file_offset 含 ELF 地址**：[AnalysisImporter.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/AnalysisImporter.kt) L237 确认 `COALESCE(p.so_addr, 0)` 映射到 file_offset，Stub 类型应有值。
4. **PP 槽偏移在注释和指令操作数中格式一致**：注释用 `[pp+0x428]`，指令用 `[PP, #0x428]`，正则分别匹配。

### 5.2 决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 解析策略优先级 | blr 注释 > PP 回溯 > UNRESOLVED | 注释最可靠，PP 回溯次之，UNRESOLVED 兜底 |
| 回溯窗口 | 16 行 | 覆盖 AllocStack/EnterFrame 等伪操作插入 |
| PP 目标解析优先级 | file_offset > description(0x...) > 类名匹配 | 结构化数据优先于文本解析 |
| UNRESOLVED 边 key | `site or (1L shl 63)` | 避免与直接边的 `caller|callee` key 冲突 |
| caller_count 回写 | build 完成后逐条 UPDATE | 激活 PpBrowserViewModel.TOP_CALLERS 筛选 |
| 不用 Capstone 指令级反汇编 | 保持纯 DB 离线构建 | DartCallGraphBuilder 设计前提是不依赖 Rizin 会话 |

### 5.3 不做的事

- **不解析 ICData 多态缓存**：静态分析无法获取运行时调用历史
- **不引入 Capstone 指令级反汇编**：保持 `DartCallGraphBuilder` 纯 DB 离线构建的设计前提
- **不修改 Blutter 引擎**：PP 槽解析在 Kotlin 层完成

---

## 六、验证步骤

### 6.1 编译验证

```powershell
.\gradlew.bat assembleDebug
```

重点关注：
- `DartCallGraphBuilder` 构造函数新增 `PpEntryDao`，Hilt 注入正确
- `collectEdges` 签名变化（新增 `ppIndex` 参数），所有调用点更新
- `PpEntryDao` / `DartMethodDao` 新增查询方法的 SQL 正确

### 6.2 功能验证

1. **调用图边数对比**：对同一 libapp.so 重新构建调用图：
   - 直接调用边数量不变（未破坏现有逻辑）
   - 新增 INDIRECT_CALL/INDIRECT_BRANCH 边
   - 新增 UNRESOLVED 边
   - 总边数应显著增加

2. **caller_count 回写验证**：
   - `PpBrowserViewModel` 的 TOP_CALLERS 筛选不再全为 0
   - 高频 PP 槽（如类型检查 stub）应有较高 caller_count

3. **MCP 工具验证**：
   - `dart_call_graph_status` 返回 `edgeKindStats` 含 5 种边类型
   - `list_unresolved_calls` 返回 UNRESOLVED 边列表

4. **UI 验证**：
   - DisassemblyTab 中间接调用指令旁标注 `[indirect]`
   - XrefBottomSheet 中间接调用边正确展示

5. **回归验证**：
   - `get_method_callers`/`get_method_callees` 正常返回（含间接边）
   - `get_pp_references` 仍正常工作

### 6.3 性能验证

- `build` 耗时：PP 索引加载 + 间接调用解析 + caller_count 回写的增量（预计 < +50%）
- 内存：`ppIndex` Map 约数千条，增量可忽略

---

## 七、实施顺序

1. **PpEntryDao.kt** — 新增 `updateCallerCount` / `updateCallerCounts`（无依赖）
2. **DartMethodDao.kt** — 新增 `countSrcReferences`（无依赖）
3. **DartCallGraphDao.kt** — 新增 UNRESOLVED 查询 / 边类型统计 / `calleesOfByKind` + `EdgeKindStat`
4. **DartCallGraphBuilder.kt** — 核心改动（构造函数 + collectEdges + 策略 1/2/3 + build 加载 ppIndex + 回写 caller_count）
5. **McpToolHandlers.kt** — `dart_call_graph_status` 增强 + 新增 `list_unresolved_calls`
6. **SoEditorViewModel.kt** — `supplementDartCallGraph` 展示间接边
7. **DisassemblyTab.kt** — 间接调用标注
8. **编译验证** — `gradlew assembleDebug`
9. **功能验证** — 真机跑 Blutter 分析 + 调用图构建 + MCP 工具调用
