# Hex Tab 字节宽度修复方案

## 问题

上一轮优化中将字节 Box 宽度从 14dp 缩到 12dp，导致 `bodySmall`(~14sp) 等宽字体下 "FF" (2字符) 无法在 10dp(12dp-2dp padding) 内完整显示，出现字节竖着排列的问题。

## 修改方案

### 修改 1：HexEditorTab.kt — 字节宽度 + 水平滚动

**1.1 字节 Box 宽度 12dp → 16dp**

每个字节 Box 宽度从 12dp 增至 16dp，确保 "FF" 在 bodySmall 等宽字体下有足够空间（16dp - 2dp padding = 14dp 可用宽度）。

**1.2 字节列 Row 固定宽度 196dp → 移除固定宽度**

改为 `Modifier.width(IntrinsicSize.Max)` 或自然包裹，让内容决定宽度。

**1.3 恢复 LazyColumn 的 horizontalScroll**

字节列总宽变为 `16 × 16dp + 13 × 1dp + 4dp ≈ 273dp`，加上偏移 64dp 和 ASCII，超出屏幕宽度，需恢复水平滚动。

**1.4 字节列 Row 宽度调整**

| 改动项 | 当前值 | 新值 |
|--------|--------|------|
| 字节 Box 宽度 | 12dp | 16dp |
| 字节列 Row 宽度 | 196dp (固定) | 无固定宽（自然包裹） |
| 字节列实际总宽 | ~209dp | ~273dp |
| 水平滚动 | 无 | 恢复 |

### 修改 2：SoEditorViewModel.kt — HEX_PAGE_SIZE 保持 2048

无需改动。

## 涉及文件

- [HexEditorTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt)

## 验证步骤

1. 构建 APK 后打开 SO 文件 → Hex Tab
2. 检查字节是否正常显示（"FF" 两个字符完整可见，不竖排）
3. 检查水平滚动是否正常工作
4. 检查字节点击选中、高亮、修改标记等功能