# Hex Tab 性能优化方案

## 问题描述

用户反馈 Hex Tab 每次打开都非常卡顿，影响使用体验。

## 根因分析

通过代码审查发现 **3 个关键性能瓶颈**：

### 瓶颈 1（最严重）：`LazyColumn` + `horizontalScroll` 冲突

[HexEditorTab.kt#L252-L256](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt#L252-L256)

```kotlin
LazyColumn(
    state = listState,
    modifier = modifier
        .horizontalScroll(rememberScrollState())  // ← 反模式
        .padding(vertical = 4.dp)
)
```

`horizontalScroll` 包裹 `LazyColumn` 是 Compose 已知的性能反模式：
- `LazyColumn` 无法确定实际宽度，导致所有 items 提前全部测量和组合（失去 lazy 意义）
- 水平滚动事件与垂直 lazy 滚动冲突，产生大量不必要的重组
- 256 行 × 16 字节 = 4096 个 `Box` 组件被一次性全部渲染

### 瓶颈 2：每行 16 个独立的 `Box` + `Text` 组件

[HexEditorTab.kt#L302-L358](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt#L302-L358)

`HexRow` 为每个字节创建独立的 `Box(clip + background + clickable + padding)` + `Text` 组件。256 行 = 4096 个 `Box` 组件 + 4096 个 `Text` 组件。

ASCII 列同样每行 16 个独立的 `Text` 组件。

### 瓶颈 3：`HEX_PAGE_SIZE = 4096` 数据量偏大

[SoEditorViewModel.kt#L47](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L47)

`HEX_PAGE_SIZE = 4096L` 产生 256 行数据，结合瓶颈 1 和 2，渲染压力巨大。

## 优化方案

### 修改 1：移除 `horizontalScroll` + 合并字节渲染

**文件**：[HexEditorTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt)

**改动**：
1. **移除 `LazyColumn` 上的 `horizontalScroll`** — 从根本上解决反模式性能问题
2. **将字节列从 16 个 `Box` + `Text` 改为单个 `Text`** — 每行只渲染 1 个 Text 组件显示所有 16 字节（空格分隔），大幅减少组件数量
3. **将 ASCII 列从 16 个 `Text` 改为单个 `Text`** — 同理减少组件数量
4. **保留 `LazyColumn`** — 对于可能的大文件，lazy 加载仍然有价值

**为什么移除 horizontalScroll 可行**：Hex 布局（偏移 80dp + 字节列 200dp + ASCII 100dp ≈ 380dp）在大部分手机宽度（360dp+）上可以容纳，无需水平滚动。

### 修改 2：可选 - 减少 `HEX_PAGE_SIZE`

**文件**：[SoEditorViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L47)

**改动**：将 `HEX_PAGE_SIZE` 从 `4096L` 减小到 `2048L`

**原因**：配合修改 1 的优化，2048 字节 = 128 行，渲染负担更轻。

### 修改 3：保留高亮功能

优化后的 `HexRow` 仍需支持：
- `isFlash`（红色背景闪烁）
- `isSelected`（选中高亮）
- `isPatched`（修改标记）

与 `clickable`（字节点击选择）

通过将字节索引映射到 `Box` 中点击实现，但只在点击的字节使用 `Box`，其余字节用 `Text` 组合。

## 修改详情

### `HexDataView` 的改动

```kotlin
// 移除 horizontalScroll
LazyColumn(
    state = listState,
    modifier = modifier
        .padding(vertical = 4.dp)
) {
    items(count = rows) { rowIndex ->
        ...
    }
}
```

### `HexRow` 的改动

- 字节列：从 16 个 `Box` 循环改为 1 个 `Text` 显示 16 个 hex 值（空格分隔）
- 选中的字节：用单个 `Box` 包裹 `Text` 子串（仅选中字节用 Box）
- ASCII 列：从 16 个 `Text` 改为 1 个 `Text`

### 点击/选中处理

- 点击行时，通过计算点击位置相对于字节字符串的偏移来确定选中的字节索引
- 或者保留点击区域仅通过 `selectedByteIndex` 和 `flashOffset` 控制高亮显示

## 预期效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| 每行组件数 | ~34（16 Box + 16 Text + 2 Row） | ~4（3 Text + 1 Row） |
| 4096 字节总组件数 | ~8704 | ~1024 |
| LazyColumn 可见行渲染 | 全部 256 行（因 horizontalScroll） | 仅可见区域（约 10-15 行） |
| 打开 Hex Tab 卡顿 | 严重 | 流畅 |

## 验证步骤

1. 修改后执行 `gradlew assembleDebug` 构建 APK
2. 在真机上打开任意 SO 文件
3. 切换到 Hex Tab，观察打开速度
4. 测试翻页、地址跳转、字节选中、高亮等功能
5. 对比优化前后的操作流畅度