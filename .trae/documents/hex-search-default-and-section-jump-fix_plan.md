# Hex 搜索框默认 "0x" 删除 + 节区跳转修复计划

## 问题

1. **Hex 子 Tab 搜索框默认值仍为 "0x"**：`HexEditorTab.kt` 第 69 行 `mutableStateOf("0x")` 未清除，用户需要手动删除"0x"才能输入搜索地址。
2. **节区点击跳转目标错误**：当前两个 SO 编辑器页面的 `onSectionClick` 跳转到 `EditorTab.HEX` + `loadHexData(section.offset)`，用户要求改为跳转到**汇编 Tab**，并带上高亮闪烁。

---

## 根因与修改

### 修改 1：HexEditorTab.kt — 删除默认 "0x"

**位置**：[HexEditorTab.kt#L69](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt#L69)

```diff
- var inputOffset by remember { mutableStateOf("0x") }
+ var inputOffset by remember { mutableStateOf("") }
```

### 修改 2：SoEditorScreen.kt — 节区改为跳转汇编 Tab + 高亮

**位置**：[SoEditorScreen.kt#L421-L425](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt#L421-L425)

```diff
 onSectionClick = { section ->
-    viewModel.setSelectedOffset(section.offset)
-    viewModel.setTab(EditorTab.HEX)
-    viewModel.loadHexData(section.offset)
+    viewModel.setSelectedOffset(section.address)
+    viewModel.setStructureFlashAddress(section.address)
+    viewModel.setTab(EditorTab.DISASSEMBLY)
+    viewModel.loadDisassembly(section.address, highlightAfterLoad = section.address)
 },
```

### 修改 3：SoEditorDetailScreen.kt — 同上

**位置**：[SoEditorDetailScreen.kt#L325-L329](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorDetailScreen.kt#L325-L329)

```diff
 onSectionClick = { section ->
-    viewModel.setSelectedOffset(section.offset)
-    viewModel.setTab(EditorTab.HEX)
-    viewModel.loadHexData(section.offset)
+    viewModel.setSelectedOffset(section.address)
+    viewModel.setStructureFlashAddress(section.address)
+    viewModel.setTab(EditorTab.DISASSEMBLY)
+    viewModel.loadDisassembly(section.address, highlightAfterLoad = section.address)
 },
```

---

## 修改清单

| # | 文件 | 行号 | 改动 |
|---|------|------|------|
| 1 | [HexEditorTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt) | L69 | `"0x"` → `""` |
| 2 | [SoEditorScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt) | L421-L425 | 节区跳转 HEX → DISASSEMBLY + 高亮 |
| 3 | [SoEditorDetailScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorDetailScreen.kt) | L325-L329 | 同上 |

共 3 个文件，无需新增 import，不改 ViewModel 或引擎层。

---

## 验证步骤

1. 进入 SO 编辑器 → Hex Tab → 搜索框默认没有文字，光标直接可输入
2. 结构 Tab → 节区子 Tab → 点击任意节区 → 跳转到汇编 Tab，滚动到该节区起始地址，并呼吸脉冲闪烁
3. 项目详情页进入方法 → 同样试节区点击 → 行为同上