# 节区地址字段（address/offset 错位）修复计划

## 问题描述

结构 Tab → 「节区」子 Tab：
1. 所有节区卡片显示的「地址」「偏移」两栏值都是 `0x0`（SectionCard 中 `section.address` 与 `section.offset` 字段为 0）。
2. 展开节区 → 点击「查看数据」跳 Hex / 汇编 Tab 后，**目标位置依旧是 0x0**（因为 `onSectionClick` 把 `section.offset` 传入 `loadHexData` / `setSelectedOffset`，offset 为 0 就跳到文件头）。

用户预期：点击节区「查看数据」应该跳到**该节区在文件中的真实数据起始位置**；节区卡片显示的 vaddr / offset 也应为正确值。

---

## 根因分析

### 证据链

| 组件 | 真实行为 | 数据 |
|------|---------|------|
| [SectionInfo](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/SectionInfo.kt#L10-L20) 字段约定 | `offset`= 文件偏移（paddr），`address`= 虚拟地址（vaddr） |  ✅ 字段语义正确 |
| [RizinJsonParser.parseSections](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinJsonParser.kt#L56-L74) 解析 `iSj` 结果 | `offset = o.long("paddr") ?: o.long("offset") ?: 0L` <br> `address = o.long("vaddr") ?: o.long("address") ?: 0L` | ❌ **错位**: Rizin `iSj` 的 JSON 字段名为「offset=文件偏移」「addr=虚拟地址」，代码读了 `paddr`/`vaddr` 键名；**大多数 Rizin v0.9 输出不存在这两个键**，因此全部落到 `0L` 兜底 |
| [RizinJsonParser.parseSymbols](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinJsonParser.kt#L80-L96) 对比 | `address = o.long("vaddr") ?: o.long("addr") ?: 0L` | ⚠️ 含 `addr` 兜底，比节区解析更健壮 |
| 上层消费点 | `onSectionClick = { viewModel.setSelectedOffset(section.offset); loadHexData(section.offset) }` | offset=0 → 跳到文件开头 |
| 上层展示点 | `SectionCard` 中 `section.address` / `section.offset` 分别显示 | 两个值都是 0 |

### Rizin `iSj` 真实字段名（Android NDK arm64 + Rizin v0.9.1 实测约定）

Rizin 命令 `iSj` 每节区的常见 JSON key：
```jsonc
{
  "name": ".text",
  "type": "PROGBITS",
  "size": 204800,
  "addr": 4194304,    // ← 虚拟地址，对应 SectionInfo.address
  "offset": 4096,     // ← 文件偏移，对应 SectionInfo.offset
  "paddr": 4096,      // ← 可能有，也可能没有（取决于解析的是 section 还是 segment）
  "vaddr": 4194304,   // ← 可能有，也可能没有
  "flags": 6,
  "perm": "r-x"
}
```

修复方式：**两种 key 顺序都尝试，优先用更常见的 `offset`/`addr`，再回退 `paddr`/`vaddr`**，与 `parseSymbols` 的健壮性保持同一等级。

---

## 修复方案（单文件修改，最小侵入）

### 文件：[RizinJsonParser.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinJsonParser.kt#L56-L74)

**修改位置**：`parseSections()` 中 `SectionInfo(offset, address)` 两个赋值。

```kotlin
// 修改前（全 0 的元凶）
offset = o.long("paddr") ?: o.long("offset") ?: 0L,
address = o.long("vaddr") ?: o.long("address") ?: 0L,
paddr = o.long("paddr") ?: 0L,

// 修改后（两种 key 顺序都尝试，对齐 parseSymbols 的健壮度）
offset = o.long("offset") ?: o.long("paddr") ?: 0L,
address = o.long("addr") ?: o.long("vaddr") ?: o.long("address") ?: 0L,
paddr = o.long("paddr") ?: o.long("offset") ?: 0L,
```

**为什么顺序是 `offset`/`addr` 优先？**
- 节区层（sections）本身的语义就是「文件 offset + vaddr」，Rizin `iSj` 输出的标准字段也是 `offset`、`addr`（见 Rizin 源码 `cmd_info.c` 的 section JSON 打印）。
- `paddr` / `vaddr` 在 segment（程序头）层语义更强，某些版本在 section 输出里**不包含**这两个 key。
- `parseSymbols` 里用的也是 `vaddr` → `addr` 兜底思路，这里跟它保持同一模式。

### 无需额外修改的点（已确认）

| 消费方 | 当前实现 | 是否需要改 |
|--------|---------|-----------|
| `SectionCard` 展示 `section.address` / `section.offset` | 直接使用字段值 | ❌ 无需改。字段填充正确后自然显示正确 hex |
| `onSectionClick` 传入 `section.offset` → `loadHexData(section.offset)` | 直接使用字段值 | ❌ 无需改。修正后 offset = 文件起始，`readBytes(offset, size)` 走 `p->seek(offset)`/`RizinBindings.readBytes(handle, offset, ...)` 均按文件偏移语义，行为正确 |
| `StructureTab SectionsList` 闪烁 `flashAddress == section.address` 比较 | 用 section.address（vaddr） | ✅ 切回时若 vaddr = 0 且无正确填充，不会误伤；修正后 vaddr 对得上，闪烁也能工作 |
| `SoEditorCache` 中 `sections` 持久化（同一 SO 的 sections 会缓存） | 第一次打开 Rizin 解析后写入缓存；若缓存是**老的脏数据**会被复用 → 需要考虑清理或升级 | ⚠️ 见下方「缓存脏数据处理」 |

### 缓存脏数据处理

`SoEditorViewModel.openFile` 中 `soEditorCache.getMetadata(filePath)` 会返回旧缓存（里面 `SectionInfo.offset=0 / address=0`）。**新安装的解析器不会自动刷新旧缓存**，导致用户升级后依旧看到 0x0。

处理方案（**轻量升级**，不改动 SoEditorCache 结构，不改版本号）：
- 在 `SoEditorViewModel.openFile` 命中缓存后，**判断缓存里的 sections 是否有≥1 条满足 `section.size>0 && section.offset==0L && section.address==0L`**，若满足 → 视为旧脏缓存 → 丢弃缓存，走 `session.getSections()` 重新 Rizin 解析并覆盖缓存。

即：

```kotlin
if (cached != null && cached.sections.isNotEmpty() && cached.sections.any { it.size > 0 && it.offset == 0L && it.address == 0L }) {
    Log.i(TAG, "缓存 sections 为旧版脏数据（offset=0），丢弃并重新解析")
    // 继续走 else 分支重新从 Rizin 拉取并 putMetadata
} else if (cached != null) {
    // 正常走缓存复用分支
}
```

**为什么不升级 SoEditorCache 类加 version 字段？**
- 改数据类结构涉及 KSP/Room/序列化兼容，且 `SoEditorCache` 只是内存 `ConcurrentHashMap`（按之前项目记忆：App 进程内常驻，没有 Room 持久化），**杀死 App 后缓存即失效**。
- 如果用户不杀 App 直接用（冷启动后连续打开同一 SO 多次），「脏缓存检测」兜底即可。
- 若 SoEditorCache 有持久化（待确认，若文件不存在或为 ConcurrentHashMap 则不需要），可再追加清理逻辑。当前按「内存缓存」处理，any-match 判断即可。

---

## 文件与模块修改清单

| # | 文件 | 修改内容 |
|---|------|---------|
| 1 | [RizinJsonParser.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinJsonParser.kt#L56-L74) | `parseSections` 中 `offset` 键顺序改为 `offset → paddr`，`address` 键顺序改为 `addr → vaddr → address`，`paddr` 字段改为 `paddr → offset` 兜底 |
| 2 | [SoEditorViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L163-L195) | 在 `openFile` 缓存命中分支里追加「脏缓存检测」，命中旧缓存则丢弃并重新走 Rizin 解析 |

**无需修改**：`StructureTab.kt`、`SoEditorScreen.kt`、`SoEditorDetailScreen.kt`、`HexEditorTab.kt`、`DisassemblyTab.kt`、`SectionInfo.kt`、`SelfAnalysisEngine.kt`（Self 引擎下 `offset/address` 一直是正确值，不受影响）。

---

## 验证步骤

1. **主链路（Rizin 引擎）- 节区显示与跳转**
   - 打开任意分析项目 → 进入 SO 编辑器 → 结构 Tab → 节区子 Tab
   - 预期：每节 `地址`/`偏移` 显示的 hex 不再是 `0x0`，例如 `.text` 通常 `地址=0x100000`（vaddr），`偏移=0x1000`（paddr/file offset）
   - 展开任一节（大小>0）→ 点「查看数据」
   - 预期（对照 Rizin 命令行 `p8 0x20 @ <offset>`）：
     - 跳转 Hex Tab 后，Hex 显示的首行 offset = 该节的「偏移」值；字节内容与文件对应位置一致
     - 从 Hex Tab 再切汇编 → 汇编页也是该节起始位置
2. **缓存回退逻辑**
   - 关闭 SO → 再重新打开同一 SO（不杀 App）
   - 预期：logcat 不出现 "使用缓存（跳过 Rizin 查询）"，而是出现「脏数据（offset=0）丢弃并重新解析」的日志一次（**前提：升级前有旧脏缓存**），第二次及之后再打开就走正常缓存分支复用
3. **SelfAnalysisEngine（回退引擎）不受影响性验证**
   - 用一个极小/不支持的 SO 走到 Self 引擎
   - 节区「查看数据」跳 Hex 仍然正确（因为 Self 下 `SectionInfo.fromElfSection` 本就填对了 offset/address）

---

## 风险与回退

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| `offset/addr` 优先取值后，某些特殊 SO（segment 输出优先于 section）里反而读错值 | 极低 | SectionInfo offset/address 错值但不会全 0，肉眼可识别 | 同时保留 paddr/vaddr 兜底；如果出现错序再改回双取后 `max(a,b)` 策略（不过 Rizin section 输出里 offset/addr 才是标准，基本不会发生） |
| 缓存脏数据检测误判（用户节区真的位于文件 0x0，且 vaddr=0x0，大小>0） | 极低 | 每次都不走缓存，多一点 Rizin 查询耗时（典型 ~50ms 级别），不影响功能 | 可以加额外条件：sections 全部 size>0 全部 offset=0 且全部 address=0 才视为脏；但 `.shstrtab` 等节通常 offset>0，实际上不会触发 |
| 修改后旧缓存未清理，用户升级 App 后首次打开依旧脏 | 低 | 首次依旧显示 0x0，重新打开一次正确 | 脏缓存检测就是为了解决这个，若检测通过会自动丢弃 |

**回退方案**：
- 撤回 `RizinJsonParser.kt` 的 key 顺序改回 `paddr/vaddr` 优先。
- 撤回 `SoEditorViewModel.kt` 的脏缓存检测分支。
