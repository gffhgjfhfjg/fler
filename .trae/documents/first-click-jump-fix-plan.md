# 修复计划：首次点击函数跳转位置错误 + 高亮文字颜色优化

## 摘要

两个独立问题：1）首次从结构Tab函数/符号跳转到汇编Tab时跳转位置错误（第二次点击正确）；2）汇编Tab行高亮时文字颜色也随背景变化，用户要求只保留背景色和动画。

---

## 问题1：首次点击跳转位置错误

### 当前状态分析

**根因**：`onFunctionClick` / `onSymbolClick` 未调用 `setSelectedOffset`，导致 `selectedOffset` 保持默认值 0。当 `DisassemblyTab` 首次被 `AnimatedContent` 创建时，`LaunchedEffect(Unit)` 检测到 `instructions.isEmpty()` 为 true，触发默认加载 `loadDisassembly(selectedOffset)`，即加载地址 0，覆盖了 `onFunctionClick` 中发起的正确加载。

**关键代码路径**：
- [SoEditorScreen.kt:L392-L396](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt#L392-L396) — `onFunctionClick` 调用 `setTab(DISASSEMBLY)` + `loadDisassembly(func.vaddr)`，但**缺少** `setSelectedOffset(func.vaddr)`
- [SoEditorScreen.kt:L387-L391](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt#L387-L391) — `onSymbolClick` 同理，**缺少** `setSelectedOffset(symbol.address)`
- [DisassemblyTab.kt:L122-L126](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt#L122-L126) — `LaunchedEffect(Unit)` 使用 `selectedOffset` 加载默认页
- [SoEditorViewModel.kt:L322-L369](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L322-L369) — `loadDisassembly` 异步加载，两个并发调用互相覆盖

**第二次点击正常的原因**：第二次点击时 `DisassemblyTab` 已被 `AnimatedContent` 重建，但 `LaunchedEffect(Unit)` 检测到 `instructions.isEmpty()` 为 false（第一次加载的数据还在），所以跳过默认加载，只执行 `onFunctionClick` 中正确的 `loadDisassembly(func.vaddr)`。

### 修复方案

在 `onFunctionClick` 和 `onSymbolClick` 中添加 `setSelectedOffset` 调用，确保 `selectedOffset` 与跳转目标一致，这样 `LaunchedEffect(Unit)` 的默认加载也会使用正确地址。

**涉及文件**：
- `app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt`

**具体修改**：
1. `onFunctionClick` 中，在 `setStructureFlashAddress(func.vaddr)` 前添加 `viewModel.setSelectedOffset(func.vaddr)`
2. `onSymbolClick` 中，在 `setStructureFlashAddress(symbol.address)` 前添加 `viewModel.setSelectedOffset(symbol.address)`

---

## 问题2：高亮时文字颜色不变

### 当前状态分析

**当前行为**：`DisassemblyRow` 在 `isFlash` 为 true 时，三列文字颜色均改变：
- 地址列：`color = MaterialTheme.colorScheme.error`（红色）
- 字节码列：`color = MaterialTheme.colorScheme.error`（红色）
- 指令助记符：`color = highlightTextColor`（`onError`）
- 操作数：`color = highlightTextColor`（`onError`）

**用户要求**：只保留背景色脉冲动画，文字颜色保持正常状态。

**关键代码**：[DisassemblyTab.kt:L1002-L1090](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt#L1002-L1090)

### 修复方案

从地址列、字节码列、指令列、操作数列的 `color` 条件中移除 `isFlash` 分支，使文字颜色在高亮时保持与非高亮相同的颜色。

**涉及文件**：
- `app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt`

**具体修改**：
1. 地址列（L1031-1034）：移除 `isFlash ||` 条件，仅保留 `isPatched` 判断
2. 字节码列（L1045-1048）：移除 `isFlash ||` 条件，仅保留 `isPatched` 判断
3. 指令助记符（L1064-1072）：移除 `isFlash -> highlightTextColor` 分支
4. 操作数（L1081-1087）：移除 `isFlash -> highlightTextColor` 分支
5. 可选的：移除不再使用的 `highlightTextColor` 变量（L1011）

---

## 验证步骤

1. `gradlew assembleDebug` 构建 APK
2. 真机验证：打开 SO 文件 → 结构Tab → 函数子Tab → 点击任意函数 → 确认跳转到正确地址
3. 返回结构Tab → 再次点击同一函数 → 仍跳转到正确地址
4. 验证高亮时文字颜色不变，只有背景色脉冲动画