# Hex Tab UI 紧凑化优化方案

## 问题

Hex Tab 布局松散，占用大量屏幕空间，需要水平滚动才能查看完整数据，且每次打开卡顿。

## 当前布局分析

| 区域 | 组件 | 尺寸 |
|------|------|------|
| 导航栏 | 3 个 IconButton | 36dp 每个 |
| HexRow 偏移列 | Text | 80dp 固定宽 |
| HexRow 字节列 | 16 × Box(14dp) + 14 × Spacer(2dp) + Spacer(8dp) | 260dp |
| HexRow ASCII 列 | 16 × Text | 100dp |
| HexRow 内边距 | horizontalPadding | 32dp |
| HexRow 总宽 | — | ~476dp（远超 360dp 手机宽度） |
| SelectedByteInfo | Card padding 16dp + inner 12dp | 松散 |
| 字号 | bodySmall | ~14sp |

## 优化方案

### 修改文件清单

**1. [HexEditorTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt)**

#### 1.1 移除 `horizontalScroll` + 组件合并

原因：`horizontalScroll` 包裹 `LazyColumn` 是 Compose 性能反模式，且紧凑布局后无需水平滚动。

#### 1.2 HexRow 紧凑化

| 改动项 | 优化前 | 优化后 |
|--------|--------|--------|
| 偏移列宽度 | 80dp | 64dp |
| 偏移格式 | `0x00000000` | `00000000`（去掉 0x 前缀） |
| 每字节宽度 | 14dp | 12dp |
| 字节间距 | 2dp | 1dp |
| 中间(第8字节)间距 | 8dp | 4dp |
| ASCII 列宽度 | 100dp | 80dp |
| 行水平内边距 | 16dp × 2 | 8dp × 2 |
| 字节列实现 | 16 × Box + Text | 1 × Text（所有字节空格拼接） |
| ASCII 列实现 | 16 × Text | 1 × Text |
| 合计宽度 | ~476dp | ~340dp（无需水平滚动） |

紧凑后总宽估算：64(offset) + 16×12+14×1+4(bytes) + 2(spacer) + 80(ascii) + 16(padding) = **340dp**，可适配 360dp+ 手机。

#### 1.3 字节高亮处理

字节列改为单个 `Text` 后，高亮/选中/修改标记通过 `AnnotatedString` + `SpanStyle` 实现：
- `isFlash` → `SpanStyle(background = Color(0xFFD32F2F))`
- `isSelected` → `SpanStyle(background = primary, color = onPrimary)`
- `isPatched` → `SpanStyle(background = errorContainer)`
- 点击事件通过 `ClickableText` 解析点击位置确定字节索引

#### 1.4 导航栏紧凑化

| 改动项 | 优化前 | 优化后 |
|--------|--------|--------|
| IconButton 尺寸 | 36dp | 28dp |
| 导航栏内边距 | 12dp horizontal, 4dp vertical | 8dp horizontal, 2dp vertical |

#### 1.5 SelectedByteInfo 紧凑化

| 改动项 | 优化前 | 优化后 |
|--------|--------|--------|
| Card 外间距 | 16dp | 4dp |
| Card 内边距 | 12dp | 8dp |
| 列间距 | 24dp | 8dp |
| 标签字号 | labelSmall | labelSmall（不变） |
| 值字号 | bodyMedium | labelLarge |
| 写入栏内边距 | 12dp horizontal, 4dp vertical | 8dp horizontal, 2dp vertical |

**2. [SoEditorViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L47)**

#### 2.1 减小 `HEX_PAGE_SIZE`

从 `4096L` 减小到 `2048L`，减少首次加载数据量。

## 预期效果

| 指标 | 优化前 | 优化后 |
|------|--------|--------|
| 行总宽度 | ~476dp（需水平滚动） | ~340dp（无需滚动） |
| 每行组件数 | ~34 | ~4 |
| 总组件数(2048字节) | ~8704 | ~512 |
| 导航栏高度 | 44dp | 32dp |
| 选中信息栏高度 | ~120dp | ~80dp |
| 数据密度 | 256 行/页 | 128 行/页 |
| 打开卡顿 | 严重 | 流畅 |

## 验证步骤

1. 构建 APK: `gradlew assembleDebug`
2. 打开任意 SO 文件 → 切换到 Hex Tab
3. 验证：数据完整显示，无需水平滚动
4. 验证：点击字节选中，高亮/修改标记正常
5. 验证：翻页、地址跳转功能正常
6. 验证：写入字节后刷新正常