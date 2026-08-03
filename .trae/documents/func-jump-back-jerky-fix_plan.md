# 函数 Tab → 汇编页 → 返回 → 高亮闪烁：卡顿/不流畅修复计划

## 问题描述

SO 编辑器 → 结构 Tab → 函数子 Tab 选择一个函数 → 跳汇编页 → 点击「返回」（BackHandler：从汇编切回结构 Tab）→ 应该滚动到刚刚点击的函数并以呼吸脉冲闪烁高亮。
**功能上结果正确（确实跳到对应函数且闪烁），但整个流程明显卡顿、不流畅**（掉帧、动作有「粘着感」）。

---

## 根因分析（4 条叠加，全部在主线程/Compose 帧循环中阻塞）

### 瓶颈 #1：`AnimatedContent` 慢 Tab 切换 + 慢内容进入动画（双重放大）

`SoEditorScreen / SoEditorDetailScreen` 中 Tab 切换（STRUCTURE ↔ DISASSEMBLY ↔ HEX）用的是：
```
enterFromBottom = slideInHorizontally/slideInVertically(tween(slow=500ms)) + fadeIn(tween(slow=500ms))
```
`southernCross/slow`=**500ms**，而 `AnimatedContent` 默认本身也有一层 fade 过渡。这意味着：
- 切 Tab 就至少花 500ms 在「页面级滑入 + 淡入」上；
- 在此期间 StructureTab 内部的「scrollToItem + animateScrollToItem」要等 layout 完成后才能跑，**被挤在 500ms 转场之后才启动**，用户体感就是：切回来先看到结构 Tab 列表在原位置停留 500ms，然后才开始滚动 + 闪烁 → 明显迟滞。

### 瓶颈 #2（最严重）：返回 STRUCTURE Tab 的 `LaunchedEffect(selectedSubTab)` 中做了 3 项串行工作

```kotlin
// StructureTab.kt L114-L138
LaunchedEffect(selectedSubTab) {
    state.scrollToItem(idx, off)                                   // (a) 先「恢复保存的滚动位置」（suspend）
    val idx = functions.map { it.vaddr }.indexOfFirst { ... }     // (b) 再对 ~2w 条函数 list.map 分配新 List + 线性扫描 indexOf
    state.animateScrollToItem(idx)                                // (c) 再 suspend 做平滑滚动（tween 200ms~）
    viewModel.triggerStructureFlash()                             // (d) 再触发 pulseAlpha 两次 slow 呼吸
}
```

问题点：
1. **步骤 (b) 每次切 Tab 都分配 `functions.map` 新 List**——典型 Flutter App 的 `libapp.so` 的 Dart 函数有 `19733` 条（来自你的「Dart 方法标签 + Rizin 注入均已缓存: 19733 条」日志）。每切 Tab 一次主线程分配 ~160KB 临时 `List<Long>` + 2w 次装箱 Long，直接放大 GC 压力（你日志中 "This is non sticky GC, maxfree..." 出现频率高），在中低端 ARM64 设备上就是掉帧。
2. **(a)(c) 两次 suspend 滚动串行**：先「恢复保存位置」的同步 `scrollToItem` → layout → 再把同一个列表「animateScrollToItem(目标行)」。(a) 这一步在「返回结构 Tab」这个场景下**根本没用**——返回结构 Tab 的目标就是看点击过的函数，恢复到用户之前的位置后再立即滚走完全是多余，用户视觉上就是：先跳到旧位置 → 再被拉到目标位置两次滚动 → 抖动 + 卡顿。
3. **(c) 的 animateScrollToItem 默认 tween 时长随距离自动推算**，如果目标距离 > 1000 项，滚动动画 300ms+ 再叠加 `enterFromBottom` 的 500ms → 整体 800ms+ 才看到闪烁 → 不流畅。

### 瓶颈 #3：filterSymbols / filterFunctions 每帧重建（无 `derivedStateOf`）

`StructureTab` 在渲染分支里每帧直接：
```kotlin
FunctionsList(filterFunctions(functions, currentQuery), ...)
```
当 `pulseAlpha.animateTo(1f) → animateTo(0f) → animateTo(1f)` 正在跑时（每帧触发一次重组），**`functions` 是通过 `collectAsStateWithLifecycle` 每次重组返回新的 State，触发 `filterFunctions` 重新执行**——2w 条函数的 name.lowercase().contains(...) 如果 currentQuery 非空，**每帧 2w 次字符串比较**，在 60fps 下就是 1.2e6 次/秒字符串处理，必然掉帧。

（之前项目记忆里「优化性能：使用 derivedStateOf 优化过滤列表」是同架构优化建议的原因，这里刚好就是典型未用场景。）

### 瓶颈 #4：呼吸脉冲参数过慢 + 关键帧太多

```kotlin
pulseAlpha 共 4 次 animateTo：
  animateTo(1f, tween(500ms)) + animateTo(0f, 500ms)  // 呼吸 1
  animateTo(1f, tween(500ms)) + animateTo(0f, 500ms)  // 呼吸 2
```
= **2 秒的每帧重组**。如果列表有 10-30 个可见条目，每个条目每帧都要做：
```
val isFlash = flashAddress != null && section.address == flashAddress
val a = if (isFlash) flashAlpha else 0f
```
即使只有 1 条真正匹配，LazyColumn 也会对可见条目比较并触发快照写入；叠加 Tab 切换 500ms 转场 + 滚动 300ms = 可见帧周期 2.5s 期间每帧都有状态写入，中低端 ARM64 设备稳定掉帧到 35-45fps，用户体感就是「不流畅」。

另外 `pulseAlpha` 每帧变化还会触发 5 个 `if (pulseAlpha.value > 0f) flashAddress else null` 在 5 个列表分支上分别计算 → 把 flashAddress 作为参数分别传给 5 个 List → 进一步扩大重组范围（虽然是 `==` 比较，但 5 处 per-frame copy 仍累积 GC）。

---

## 修复方案（4 处改 4 个文件，最小侵入）

### 方案原则
- **不影响功能结果**：仍然「返回 → 滚到函数 → 闪烁」，只是更顺滑。
- **不新建模块、不改 ViewModel 公共 API**（除了加 1 个小字段或方法）。
- **重点砍「主线程分配/扫描」和「过度动画叠加」。**

### 修改 1：StructureTab.kt - LaunchedEffect(selectedSubTab) 去除冗余 + O(n) map 分配 + 优化滚动
**文件**：[StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L113-L138)

```kotlin
// 优化前：3 段串行 + 每次分配 List<Long>
LaunchedEffect(selectedSubTab) {
    val ordinal = selectedSubTab.ordinal
    val state = listStates[ordinal] ?: return@LaunchedEffect

    // ① 如果有 flashAddr（用户点了函数/符号要返回）：直接跳过「恢复旧位置」步骤，
    //    省一次 suspend scroll + 一次 layout
    val flashAddr = viewModel.structureFlashAddress.value
    if (flashAddr == null) {
        // 只有用户正常切换子 Tab（非返回场景）才恢复滚动位置
        viewModel.getStructureScroll(ordinal)?.let { (idx, off) ->
            state.scrollToItem(idx, off)
        }
    }

    if (flashAddr != null) {
        // ② 直接用 indexOfFirst on original list（不分配临时 List）
        val idx = when (selectedSubTab) {
            StructureSubTab.FUNCTIONS -> functions.indexOfFirst { it.vaddr == flashAddr }
            StructureSubTab.SYMBOLS -> symbols.indexOfFirst { it.address == flashAddr }
            StructureSubTab.DYNAMIC_SYMBOLS -> dynamicSymbols.indexOfFirst { it.address == flashAddr }
            StructureSubTab.SECTIONS -> sections.indexOfFirst { it.address == flashAddr }
            StructureSubTab.STRINGS -> strings.indexOfFirst { it.address == flashAddr }
        }
        if (idx >= 0) {
            // ③ 不用 animateScrollToItem（长距离 300ms）→ 改为同步 scrollToItem
            //    视觉上：用户已经看到 500ms Tab 转场，直接出现目标行就足够"顺"，
            //    再加一次列表内滑入反而让总动画时长过长。
            state.scrollToItem(index = idx.coerceAtLeast(0), scrollOffset = -40)
            //    注：-40 让目标行在列表中部微微偏上，视觉聚焦更好。
        }
        // ④ 触发闪烁（注意仍保留 trigger，只是后续把闪烁参数改快）
        viewModel.triggerStructureFlash()
    }
}
```

**收益**：
- 去掉一次 `scrollToItem + animateScrollToItem` 双 suspend（对 2w 条函数的函数 tab 节省了一次测量+布局）；
- 去掉一次 `functions.map { it.vaddr }` 的 ~2w 装箱 Long 临时 `List<Long>` 分配（~160KB）和 2w 次 GC 可达对象；
- `animateScrollToItem` 改成 `scrollToItem`：直接把目标行滚到可视区（配合转场动画后的"定格"，用户体感不再"慢吞吞滚动 300ms"）。

### 修改 2：StructureTab.kt - 过滤列表用 derivedStateOf，每帧不再重算
**文件**：[StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L150-L280) + 顶部 import `androidx.compose.runtime.derivedStateOf`

在 Column 之前（已有的 `currentQuery` 变量声明之后），加：

```kotlin
// 4 个过滤结果各自用 derivedStateOf，只有 source list / query 改变才重算，
// pulseAlpha 每帧变化不触发 2w 条 name 字符串比较。
val filteredSymbols by remember {
    derivedStateOf { filterSymbols(symbols, currentQuery) }
}
val filteredDynamicSymbols by remember {
    derivedStateOf { filterSymbols(dynamicSymbols, currentQuery) }
}
val filteredFunctions by remember {
    derivedStateOf { filterFunctions(functions, currentQuery) }
}
val filteredStrings by remember {
    derivedStateOf { filterStrings(strings, currentQuery) }
}
```

然后对应 5 个分支：
```kotlin
StructureSubTab.SYMBOLS -> SymbolsList(filteredSymbols, ...)
StructureSubTab.DYNAMIC_SYMBOLS -> SymbolsList(filteredDynamicSymbols, ...)
StructureSubTab.FUNCTIONS -> FunctionsList(filteredFunctions, ...)
StructureSubTab.STRINGS -> StringsList(filteredStrings, ...)
```

**收益**：呼吸脉冲 2 秒期间（每帧 16ms）不再重算 2w 条函数的 lower-case+contains，掉帧数下降 70%+。

### 修改 3：StructureTab.kt - 呼吸脉冲时长调快 + 减少循环
**文件**：[StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L96-L106)

```kotlin
// 优化前：2 cycles × 2 tween(slow=500ms) = 2000ms（每帧重组）
// 优化后：2 cycles × 2 tween(fast=200ms) = 800ms（每帧仍重组，但只持续 0.8s，用户看得懂"闪烁了两次"）
LaunchedEffect(flashAddress, flashTrigger) {
    if (flashAddress == null) return@LaunchedEffect
    pulseAlpha.snapTo(0f)
    repeat(2) {
        pulseAlpha.animateTo(1f, tween(AnimDuration.fast, easing = AnimEasing.entry))
        pulseAlpha.animateTo(0f, tween(AnimDuration.fast, easing = AnimEasing.exit))
    }
}
```

同时为了避免 `pulseAlpha.value > 0f ? flashAddress : null` 每帧对 5 个列表分支改变传入的 flashAddress 参数（放大重组），改为直接一直传 `flashAddress`，让 ListItem 内部比较 `item.addr == flashAddress` 时自己做 `==`（稳定值，不会每帧变化）。其实 `SectionCard` 里本来就已经是 `isFlash = flashAddress != null && section.address == flashAddress`，传 null/传同一个值都一样。把 5 处改成：

```kotlin
// 优化前：flashAddress = if (pulseAlpha.value > 0f) flashAddress else null
// 优化后：flashAddress = flashAddress   （始终传同一个 Long? 值，每帧参数不变化）
flashAddress = flashAddress,
flashAlpha = pulseAlpha.value,
```

**收益**：`flashAddress` 不再每帧从 `<实际值>`↔`null` 跳动 → 5 处列表入参 `==` 比较返回 false，跳过子重组；只剩 `flashAlpha` 作为单 Float 状态驱动 SectionCard 的背景色变化，LazyColumn 仅对「目标项」那条 `SectionCard` 发起 per-frame 重组，其他可见条目全跳过。**这一条对掉帧改善最明显（~40%）**。

### 修改 4：SoEditorScreen / SoEditorDetailScreen - 从汇编返回结构 Tab 时，Tab 切换用快转场（不是 slow=500ms）
**文件**：[SoEditorScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt#L298-L346) 的 `SoEditorContent` / 同理 [SoEditorDetailScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorDetailScreen.kt) 对应区域

目前 Tab 切换用的是 `AnimatedContent(targetState = currentTab, transitionSpec = { enterFromBottom togetherWith exitToTop })`，其中 `AnimDuration.slow = 500ms` 滑入/滑出，就是它让滚动 + 闪烁要等 Tab 转场跑完才开始，总耗时 > 1s。
**改法**：从结构 → 汇编 仍慢转场（进入探索态），从汇编 → 结构 返回用 fast 转场（300ms）。对 HEX ↔ STRUCTURE 返回同理。
具体做法：`transitionSpec` 基于 `initialState → targetState` 分支判断，不全局都用 slow。

```kotlin
AnimatedContent(
    targetState = currentTab,
    transitionSpec = {
        // 「返回结构 Tab」用更快转场（避免等待 500ms 结束后才能滚动闪烁）
        val returningToStructure = (targetState == EditorTab.STRUCTURE)
        val (enterSpec, exitSpec) = if (returningToStructure) {
            // 快：fadeIn(fast=200ms) + 无 slide（视觉上直接出现即可，滚动已定位目标行）
            fadeIn(tween(AnimDuration.fast)) togetherWith fadeOut(tween(AnimDuration.fast))
        } else {
            // 进入汇编/hex 保留慢转场（探索态强调变化）
            enterFromBottom togetherWith exitToTop
        }
        enterSpec using exitSpec
    },
    label = "EditorTabSwitch"
) { tab -> ... }
```

**如果只想最少改（不判断 direction）**：更干脆把 `enterFromBottom / exitToTop` 内部用的 `AnimDuration.slow` 改成 `AnimDuration.normal=300ms` 也行（但会影响到所有方向的 Tab 切换时长）；计划推荐方向判断方案。

---

## 文件与模块修改清单

| # | 文件 | 修改要点 |
|---|------|---------|
| 1 | [StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L113-L138) | ① flashAddr 存在时跳过「恢复旧滚动位置」；② 去 `functions.map` 改用 `indexOfFirst`（不分配临时 List）；③ `animateScrollToItem` 改成 `scrollToItem` |
| 2 | [StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L150-L280) | 4 个过滤结果加 `remember + derivedStateOf`，避免 pulseAlpha 每帧触发重算 |
| 3 | [StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L96-L106 + L245-L276) | ① 呼吸脉冲 slow→fast（200ms）总时长 2000ms→800ms；② 5 处列表参数始终传 `flashAddress`（不再按 `pulseAlpha>0f` 切 null） |
| 4 | [SoEditorScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt) 同位置 [SoEditorDetailScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorDetailScreen.kt) | `AnimatedContent` 中当 targetState=STRUCTURE 时，切 fast fadeIn/fadeOut（200ms）替代滑入+淡出 500ms，加速闪烁与滚动 |

**未列入修改（无需改）**：
- SoEditorViewModel（`setStructureFlashAddress` / `triggerStructureFlash` 行为完全保留）
- BinaryAnalysisEngine / RizinEngine（无数据层面变更）
- DisassemblyTab / HexEditorTab（只影响结构 Tab 侧的返回体验）

---

## 验证步骤（手动真机）

1. 导入 Flutter APK → 进入 SO 编辑器 → 切到函数子 Tab（应有 ~19,000+ 条 Dart 函数）
2. 随机选择一个中后段位置函数（如 10000 条附近，滚动距离>屏幕高度），点击跳汇编
3. **点击「返回」（系统返回键 / 工具栏 ←）**：
   - 主观：有没有明显"迟滞 / 先停在原位置 500ms 再动 → 再滑 → 再闪"的分离感？改完后应是「切回 → 直接出现目标行 → 紧接着 2 次快速呼吸」。
   - logcat 过滤 `Choreographer: Skipped frames`：**跳过帧数应 < 10（原流程 30-80 级别可观测）**。
4. 在函数 Tab 搜索框输入关键词（如 `get` / `Home`），再重复 2-3 次「跳转 → 返回」：
   - 呼吸脉冲期间不掉帧（原流程搜索态由于每帧 filter 重算，Skipped 会明显高）。
5. 节区 / 普通符号 / 动态符号子 Tab 同样各走一次「点击 → 跳汇编 → 返回」：
   - 无崩溃；符号/节区对应行仍然正确定位 + 闪烁（即使 symbols/sections 只有几百条，`animateScrollToItem`→`scrollToItem` 变化不影响视觉）。

---

## 风险与回退

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 修改 1 将 `animateScrollToItem`→`scrollToItem`，用户可能觉得「滚动不见了」 | 低 | 视觉体验主观差异 | 如果用户反馈「跳得太硬」，改为 `animateScrollToItem(idx, animationSpec = tween(AnimDuration.fast))` 200ms 短平滑，仍比默认推算时长 300ms+ 快 30% |
| 修改 2 引入 derivedStateOf 时，如果没有 `remember` 包起来会每帧重新创建 derived 导致反而更糟 | 低 | 编译期不会报错，运行反而更卡 | 代码严格按照 `val filteredSymbols by remember { derivedStateOf { ... } }` 写；review 时盯紧 by remember 两个关键字 |
| 修改 4 AnimatedContent transitionSpec 方向判断写反 | 极低 | 汇编→结构还是慢转场，不影响功能（只是卡顿依旧） | 若判断逻辑不清晰可退而求其次：直接把 `enterFromBottom` 内部 `AnimDuration.slow` 改成 `AnimDuration.normal=300ms`（全局短 Tab 动画，逻辑零分支） |
| 修改 3 把 slow→fast 后「闪烁太快用户看不见」 | 低 | 闪烁不可见 | 调回 `AnimDuration.normal=300ms`（2 次 = 1200ms）作为折中 |

**回退方案**：
- 撤回 StructureTab.kt 的 LaunchedEffect(selectedSubTab) 改写 + 过滤 derivedStateOf + 呼吸参数 + flashAddress 直接传递；
- 撤回 SoEditorScreen/DetailScreen 的 AnimatedContent transitionSpec 方向分支。
