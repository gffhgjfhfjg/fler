# Hex Tab UI 优化 + 写入修复方案

## 问题概述

1. **UI 布局**：地址列、字节列、ASCII 列之间有大块空白，未充满屏幕；字节列固定 20dp 宽度未自适应
2. **写入无反应**：用户选中字节、输入 hex 值、点击"写入"按钮后无任何反馈

## 根因分析

### UI 布局
- `HexRow` 使用 `Arrangement.SpaceBetween` 将三列推到两端，中间产生空白
- 字节 Box 固定 `width(20.dp)`，未利用屏幕剩余宽度
- 当前总宽度 ~280dp，在 360dp 屏幕上浪费 ~80dp

### 写入无反应（★ 核心）
- **"0x" 前缀导致解析失败**：`SelectedByteInfo` 中所有值都显示 "0x" 前缀（如 `0xFF`），用户在输入框中也输入 "0xFF"
- Kotlin 的 `"0xFF".toIntOrNull(16)` 返回 **null**（不支持 "0x" 前缀）
- `if (value != null && value in 0..255)` 条件不满足 → 写入被静默跳过，无任何反馈
- 次要问题：`writeByte` 未调用 `backupManager.createBackupIfNeeded()`，与 `applyPatch` 不一致

## 修改方案

### 修改 1：HexEditorTab.kt — 重写 HexRow 布局

**目标**：三列充满屏幕宽度，字节列不换行，每行 8 字节

**布局策略**：
- 地址列：固定 `width(52.dp)`
- 字节列：`Row(Modifier.weight(1f))`，每个字节 Box 使用 `weight(1f)` 自适应宽度
- ASCII 列：固定 `width(48.dp)`，每个字符使用 `weight(1f)` 与字节对齐
- 移除 `Arrangement.SpaceBetween`，改用 `padding + Spacer` 控制间距

```kotlin
@Composable
private fun HexRow(...) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 地址列 - 固定宽度
        Text(
            text = rowOffset.toString(16).uppercase().padStart(8, '0'),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(52.dp)
        )

        Spacer(Modifier.width(4.dp))

        // 字节列 - weight(1f) 填充剩余空间，每个字节 weight(1f) 均分
        Row(modifier = Modifier.weight(1f)) {
            for (i in 0 until 8) {
                if (i < bytes.size) {
                    val byteIndex = startIndex + i
                    val isSelected = byteIndex == selectedByteIndex
                    val byteValue = bytes[i]
                    val isPatched = rowOffset + i in patchedOffsets
                    val isFlash = rowOffset + i == flashOffset

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(22.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                when {
                                    isFlash -> Color(0xFFD32F2F)
                                    isSelected -> MaterialTheme.colorScheme.primary
                                    isPatched -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f)
                                    else -> Color.Transparent
                                }
                            )
                            .clickable { onByteClick(byteIndex) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = byteValue.toUByte().toString(16).uppercase().padStart(2, '0'),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                isSelected -> MaterialTheme.colorScheme.onPrimary
                                else -> {
                                    if (byteValue in 32..126) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                }
                            }
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.width(4.dp))

        // ASCII 列 - 固定宽度，每个字符 weight(1f) 与字节对齐
        Row(modifier = Modifier.width(48.dp)) {
            for (i in 0 until 8) {
                Text(
                    text = if (i < bytes.size) {
                        val b = bytes[i]
                        if (b in 32..126) b.toChar().toString() else "."
                    } else " ",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
```

### 修改 2：HexEditorTab.kt — 回退为 LazyColumn

**原因**：`Column + verticalScroll` 对 2048 字节（256行）数据全部一次性渲染，性能差。`LazyColumn`（无 `horizontalScroll`）不会干扰 `clickable`。

```kotlin
@Composable
private fun HexDataView(...) {
    val bytesPerRow = 8
    val rows = (data.size + bytesPerRow - 1) / bytesPerRow
    val listState = rememberLazyListState()

    LaunchedEffect(flashOffset) {
        val fo = flashOffset ?: return@LaunchedEffect
        val idx = (fo - baseOffset).toInt()
        if (idx in 0 until data.size) {
            listState.animateScrollToItem(idx / bytesPerRow)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.padding(vertical = 2.dp)
    ) {
        items(count = rows) { rowIndex ->
            val startIndex = rowIndex * bytesPerRow
            val endIndex = minOf(startIndex + bytesPerRow, data.size)
            val rowOffset = baseOffset + startIndex

            HexRow(
                rowOffset = rowOffset,
                bytes = data.sliceArray(startIndex until endIndex),
                startIndex = startIndex,
                selectedByteIndex = selectedByteIndex,
                patchedOffsets = patchedOffsets,
                flashOffset = flashOffset,
                onByteClick = onByteClick
            )
        }
    }
}
```

### 修改 3：HexEditorTab.kt — 修复写入逻辑

**问题**：`toIntOrNull(16)` 不支持 "0x" 前缀

**修复**：写入按钮 onClick 中先去除 "0x"/"0X" 前缀再解析

```kotlin
TextButton(
    onClick = {
        val cleanInput = newByteValue.removePrefix("0x").removePrefix("0X").trim()
        val value = cleanInput.toIntOrNull(16)
        if (value != null && value in 0..255) {
            viewModel.writeByte(byteOffset, value.toByte())
            newByteValue = ""
        }
    },
    enabled = newByteValue.isNotBlank()
) {
    Text("写入")
}
```

### 修改 4：SoEditorViewModel.kt — writeByte 添加备份创建

**问题**：`writeByte` 未调用 `backupManager.createBackupIfNeeded()`，与 `applyPatch` 不一致

**修复**：在 `writeByte` 中添加备份创建逻辑

```kotlin
fun writeByte(offset: Long, newValue: Byte) {
    viewModelScope.launch {
        try {
            val ok = withContext(Dispatchers.IO) {
                // 首次编辑前创建 .bak（与 applyPatch 一致）
                val f = File(_uiState.value.filePath)
                if (f.exists()) backupManager.createBackupIfNeeded(f)
                session.writeBytes(offset, byteArrayOf(newValue), _uiState.value.fileName)
            }
            if (ok) {
                refreshPatchedOffsets()
                loadHexData(_hexData.value.offset, _hexData.value.data.size.toLong())
            }
        } catch (e: Exception) {
            Log.e(TAG, "写入字节失败", e)
        }
    }
}
```

### 修改 5：HexEditorTab.kt — 优化输入键盘类型

将 `KeyboardType.Text` 改为 `KeyboardType.Ascii`，更适合 hex 输入：

```kotlin
CompactTextField(
    value = newByteValue,
    onValueChange = { newByteValue = it.take(2).uppercase() },
    modifier = Modifier.weight(1f),
    placeholder = "新字节 (如 FF)",
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
)
```

- `it.take(2).uppercase()` 自动转大写，限制 2 字符
- placeholder 改为 "新字节 (如 FF)" 引导用户直接输入 hex 值

## 涉及文件

| 文件 | 修改内容 |
|------|---------|
| `HexEditorTab.kt` | 重写 HexRow 布局、回退 LazyColumn、修复写入解析、优化输入框 |
| `SoEditorViewModel.kt` | writeByte 添加 createBackupIfNeeded |

## 验证步骤

1. 构建 APK：`cd c:\Users\Len\AndroidStudioProjects\fler && gradlew assembleDebug`
2. 打开 SO 编辑器 → Hex Tab
3. 验证 UI：三列充满屏幕，无空白，字节不换行
4. 点击任意字节 → 底部显示选中信息 + 输入框
5. 输入 "FF" → 点击"写入" → 字节值变化，红色高亮显示已修改
6. 输入 "0xFF" → 点击"写入" → 同样成功（去除 0x 前缀后解析）
7. 输入 "ff" → 点击"写入" → 同样成功（自动转大写）
