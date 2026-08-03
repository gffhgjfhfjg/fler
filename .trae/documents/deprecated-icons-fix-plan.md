# 弃用图标修复计划

## 概述

修复 Compose Material Icons 中 `Icons.Default.*`（即 `Icons.Filled.*`）弃用警告，替换为 `Icons.AutoMirrored.Filled.*`。

## 当前状态

| 文件 | 行号 | 当前代码 | 替换为 |
|------|------|---------|--------|
| `DisassemblyTab.kt` | 368 | `Icons.Default.HelpOutline` | `Icons.AutoMirrored.Filled.HelpOutline` |
| `DisassemblyTab.kt` | 483 | `Icons.Default.KeyboardArrowLeft` | `Icons.AutoMirrored.Filled.KeyboardArrowLeft` |
| `DisassemblyTab.kt` | 511 | `Icons.Default.KeyboardArrowRight` | `Icons.AutoMirrored.Filled.KeyboardArrowRight` |
| `DisassemblyTab.kt` | 537 | `Icons.Default.HelpOutline` | `Icons.AutoMirrored.Filled.HelpOutline` |
| `SoEditorScreen.kt` | 209 | `Icons.Default.Undo` | `Icons.AutoMirrored.Filled.Undo` |
| `SoEditorDetailScreen.kt` | 170 | `Icons.Default.Undo` | `Icons.AutoMirrored.Filled.Undo` |

## 修改内容

3 个文件，共 6 处替换，全部为机械替换，不涉及逻辑变更。

## 验证

构建 APK 确认无弃用警告。

## 涉及文件

- `app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt`
- `app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt`
- `app/src/main/java/com/ai/fler/features/so_editor/SoEditorDetailScreen.kt`