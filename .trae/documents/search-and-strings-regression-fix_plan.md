# 搜索功能失效 + 字符串 Tab 加载不出：回归 bug 修复计划

## 问题描述
上一轮「函数 Tab → 汇编 → 返回 → 闪烁」流畅度优化上线后产生 2 条回归：
1. **搜索功能不行了**：在符号/动态符号/函数/字符串子 Tab 输入关键词回车后列表完全不变（过滤无效，原 `currentQuery` 改变没有触发任何过滤重算）。
2. **字符串 Tab 加载不了字符串**：点击「字符串」子 Tab 后一直看到 `StringsList` 内部的 `strings.isEmpty()` 占位（Box + "加载中..."），哪怕 strings.size 在 Tab 文本上已经显示成 `(12345)`，列表还是空。

---

## 根因（100% 定位，且两条 bug 同源）

### 共同根因：`remember` 不带 key，且 derivedStateOf block 内部未读取任何 State 对象

上一轮在 [StructureTab.kt#L252-L263](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L252-L263) 加了：
```kotlin
val filteredFunctions by remember {           // ← 问题 1：remember 没有 key
    derivedStateOf {                          // ← 问题 2：derivedStateOf block 里全是普通 val，没有读取 State
        filterFunctions(functions, currentQuery)
    }
}
```
对符号/动态符号/字符串同样 4 份。

#### Compose 语义冲突：
1. **`remember { }`（无 key）**：只在 StructureTab **首次组合**时执行一次 lambda 创建对象；**之后任何重组都复用这个对象**。
   - 这意味着 `functions` / `strings` / `symbols` 参数（都是普通 `val List<*>`，引用类型）在首次 remember 时被**捕获为当时的引用**。
   - 后续 ViewModel 发新 List（引用变了，比如字符串首次加载时从 emptyList → listOf(12345 条)）传给 StructureTab，但 remember 不重跑 → derivedStateOf 里的 `strings` 仍指向第一次的 `emptyList`。
2. **`derivedStateOf { ... }` block 内没读 State**：snapshot 系统只跟踪 `State` 对象的读。`functions / currentQuery` 全是局部 `val`（纯值），snapshot 记录不到任何 dependency → derivedStateOf 认为依赖没变，一直返回第一次的缓存值。

→ **最终效果**：4 个过滤结果"焊死"在第一次组合时的状态，用户改 `currentQuery`（搜索词）完全没反应；`strings` 即使加载成功，过滤结果仍是 emptyList。

### Bug 1「搜索失效」为什么这样？
- 用户输入 `Home` 时 `searchQueries[FUNCTIONS]`（`SnapshotStateMap` 是 State 类型）改变 → 触发重组没问题；
- 重组后 `currentQuery` 的**值**变了，但 remember 不带 key 不重跑 init → `derivedStateOf` 里的 lambda 虽然每次读都执行，但它**捕获的 `currentQuery` 仍然是第一次组合时的局部变量值**；
- （Kotlin 中 `val currentQuery = map[key].orEmpty()` 是个每次重组重新赋值的新 String 引用，但 remember 内部 lambda 在第一次被创建时把 `currentQuery` 作为**那个时间点的快照值**固化在闭包里了——因为 remember 的 lambda 只跑一次，之后永远用那个闭包。）

### Bug 2「字符串不出数据」为什么这样？
- 首次进结构 Tab 默认 SECTIONS，`strings` 参数此时是 `emptyList()`（字符串懒加载，切到字符串 Tab 才调 `viewModel.loadStrings()` 异步拉）。
- remember 捕获的 `strings` 就是 `emptyList`。
- 切到 STRINGS Tab → onClick 触发 `onStringsTabSelected()` → ViewModel 加载 strings → `uiState.strings` 变非空 → 重组。
- remember 不带 key → derivedStateOf 仍用最开始的 emptyList → `filterStrings(emptyList, "") = emptyList` → `StringsList` 进 `isEmpty()` 分支。完美复现。

---

## 修复方案（最小侵入，1 个文件 1 处改）

### 文件：[StructureTab.kt#L252-L263](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L252-L263)

把 4 个 remember 改成 **remember(key = 对应的源 List + currentQuery)**：

```kotlin
// remember(key1, key2) = 任何一个 key 通过 equals 比较发现变化就重跑 init block
// 解决：
//   - 源 List 引用变了（strings 首次加载/函数列表增量更新）→ 重新捕获闭包
//   - currentQuery 值变了（用户输入搜索词）→ 重新捕获 + derivedStateOf 重算
// 同时仍保留 derivedStateOf：pulseAlpha 每帧变化触发的重组，remember keys 全相等
// → 不重跑 init，derivedStateOf 直接回缓存，不会每帧过滤 2w 条函数（保持原有性能优化目的）
val filteredSymbols by remember(symbols, currentQuery) {
    derivedStateOf { filterSymbols(symbols, currentQuery) }
}
val filteredDynamicSymbols by remember(dynamicSymbols, currentQuery) {
    derivedStateOf { filterSymbols(dynamicSymbols, currentQuery) }
}
val filteredFunctions by remember(functions, currentQuery) {
    derivedStateOf { filterFunctions(functions, currentQuery) }
}
val filteredStrings by remember(strings, currentQuery) {
    derivedStateOf { filterStrings(strings, currentQuery) }
}
```

**对比选择（为什么保留 derivedStateOf 不直接退化成每次重组过滤？）**
- 如果直接写 `val filteredFunctions = filterFunctions(functions, currentQuery)`（不加任何缓存），用户搜索词/源 List 改变时当然立刻生效 —— 但呼吸脉冲（800ms 共 50 帧左右）期间每次重组仍然会重跑 `filterFunctions(2w 条)`，在低端 ARM64 机上搜索态 `Home` 关键词每帧 2w 次 lower-case contains 就是 ~1.2M/s 字符串比较，**立即回退到优化前的掉帧水平**（用户马上又会说"闪烁时又卡了"）。
- remember(key)+derivedStateOf 同时满足：① key 变 → 重新创建闭包（修复回归）；② key 不变 → derivedStateOf 缓存（pulseAlpha 不触发重算）—— 完美两边都要。

### 其他不改动的点：
- `filterSymbols / filterFunctions / filterStrings` 实现本体不动；
- `onStringsTabSelected` 调用时机不动（本来就是 Tab onClick 里调用，没错）；
- `flashAddress` 直接传递、呼吸 fast=200ms、scrollToItem、AnimatedContent fade fast 全部保留。

---

## 文件与模块修改清单

| # | 文件 | 修改点 |
|---|------|--------|
| 1 | [StructureTab.kt#L252-L263](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L252-L263) | 4 个 `remember { derivedStateOf ... }` → `remember(源List, currentQuery) { derivedStateOf ... }` 加 key |

共 4 行替换，没有新增 import，不改 ViewModel / data / engine 层。

---

## 验证步骤（手动真机）

### Bug 1 验证：搜索功能恢复
1. 进入 SO 编辑器 → 函数子 Tab（2w 条）；
2. 打开搜索框（或直接输入），输入 `get`、`Home`、`init` 任一关键词；
3. 期望：输入一个字符后 1-2 帧列表立刻缩短，且 Tab 标题上的"函数 (xxxxx)"数字仍然是总数（正确），而列表长度下降（过滤生效）；
4. 清空搜索框 → 列表立刻恢复全量（如果不行，说明 remember 没生效，当前仍用旧闭包）；
5. 同样对符号/动态符号/字符串做一次，每个子 Tab 搜索结果独立，切 Tab 时保留自己的搜索词（`searchQueries[ordinal]` 这一逻辑未动，应仍然正常）。

### Bug 2 验证：字符串首次加载
1. 进入 SO 编辑器 → 默认在节区 Tab，不要点字符串；
2. 点「字符串」Tab；
3. 期望：Tab 标题上 `字符串 (0)` 短暂后 → `字符串 (xxxx)` 更新数字；
4. 列表内容**同时出现**字符串行（不再一直是「加载中...」空 Box）；
5. 再回到节区 Tab，重新进入字符串 Tab → 立刻显示已加载过的列表，不重复白屏。

### 回归验证：流畅度优化不退化
1. 函数子 Tab 中后段选一个函数 → 跳汇编 → 返回；
2. 过程中无"先停在原位置→再被拉→再闪"分离感；
3. logcat 过滤 `Choreographer: Skipped frames`：skipped <10（和上一轮修复后同一水平即可）。

---

## 风险与回退

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| `remember(A, B)` 每次过滤都因为 List 引用变化而重跑 init block，用户在搜索框打字每帧产生大量垃圾（如果 ViewModel 冷流每次 emit 新 List） | 中 | 打字时搜索可能丢帧，但这是原有 viewmodel 行为 bug，和本修复无关 | 若实测打字卡，再在 ViewModel 层把 `uiState.strings`/`functions` 做 `distinctUntilChanged`（这次不做，仅针对本次回归） |
| 忘记给某个过滤分支加 key，某一 Tab 搜索仍坏 | 极低 | 单一子 Tab 搜索失效 | 修改提交前核对 4 个 remember 行逐一检查 |

**回退方案**：直接把 4 行退化成 `val filteredXxx = filterXxx(xxx, currentQuery)`，放弃性能优化但立即修复 bug；若后续用户再说"闪烁时又卡"，再回头重新做 remember(key)+derivedStateOf。

---

## 不选择的备选方案（弃用）

| 方案 | 问题 |
|------|------|
| 「完全去掉 derivedStateOf，每次重组直接过滤」 | 搜索态呼吸脉冲期间每帧重跑 2w 条 contains → 直接回退到优化前掉帧水平，用户会再报告"闪烁卡顿" |
| 「把 currentQuery / functions 全提到 ViewModel 用 StateFlow 暴露，在 StructureTab 用 `collectAsStateWithLifecycle` 转 State，然后 derivedStateOf block 里真的读 State」 | 改动大，影响 ViewModel 层；当前 StructureTab 的 functions/symbols 参数本来是调用方透传的纯值，改成 StateFlow 会破坏 SoEditorScreen/DetailScreen 现有签名 |
| 「用 `LaunchedEffect(currentQuery, functions) { filtered = filter(...) }` 保存到一个 mutableStateOf」 | 更绕，多一层异步，首次显示空一帧再回填，闪一下；不如 remember(key)+derivedStateOf 同步返回直接 |
