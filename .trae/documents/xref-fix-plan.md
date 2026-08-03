# 交叉引用优化 + 加载动画 + 搜索框默认值 修改计划

## 概述

针对用户提出的 4 个问题制定修改方案：
1. 交叉引用窗口增加函数名列
2. 系统返回后重新进入，交叉引用消失
3. 进入 SO 编辑页增加加载动画，交叉引用完成后才允许操作
4. 删除搜索框默认的 "0x"

---

## 当前状态分析

### 1. 交叉引用面板（XrefBottomSheet）

- 文件：`app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt`
- `XrefBottomSheet` 接收 `xrefData: XrefDataState`，包含 `xrefsTo`（谁调用了我）和 `xrefsFrom`（我调用了谁）
- `XrefRow` 仅显示地址和类型，无函数名
- `DisassemblyTab` 中已有 `functionOverlay: Map<Long, String>`（地址→函数名），但未传递到 `XrefBottomSheet`

### 2. 重新进入后交叉引用消失

- 文件：`app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt`
- `loadDartFunctionLabels()` 方法中，当 `cachedLabels != null && soEditorCache.isInjected(soPath)` 时，直接跳过所有操作（不调用 `defineFunctions` 和 `reanalyzeXrefs()`）
- 系统返回键退出再进入时，`AnalysisSession` 单例的 Rizin 会话可能已失效（handle 被重新创建），但缓存标记了「已注入」，导致 `reanalyzeXrefs()` 被跳过 → xref 表为空
- 日志：`"Dart 方法标签 + Rizin 注入均已缓存: 19733 条 (跳过全部)"` 但 xref 为 0

### 3. 加载动画

- 当前 `SoEditorScreen` 仅在 `uiState.isLoading` 时显示 `LoadingContent()`
- `openFile()` 设置 `isLoading = true` → 调用 `loadDartFunctionLabels()`（异步 `viewModelScope.launch`）→ 立即设置 `isLoading = false` → 显示编辑器内容
- `loadDartFunctionLabels()` 中的 `reanalyzeXrefs()` 在后台异步执行，用户可能在 xref 完成前就开始操作

### 4. 搜索框默认值

- 当前 `DisassemblyTab.kt` 第 112 行：`var inputAddress by remember { mutableStateOf("0x") }`
- 用户希望搜索框完全为空

---

## 修改方案

### 修改 1：交叉引用窗口增加函数名列

**涉及文件：**
- `app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt`

**修改内容：**

1.1 修改 `XrefBottomSheet` 参数签名，增加 `functionOverlay: Map<Long, String>` 参数

```kotlin
private fun XrefBottomSheet(
    xrefData: XrefDataState,
    functionOverlay: Map<Long, String>,  // 新增
    onDismiss: () -> Unit,
    onXrefClick: (Long) -> Unit
)
```

1.2 修改 `XrefRow` 参数签名，增加 `functionName: String?` 参数，显示函数名

```kotlin
private fun XrefRow(
    address: Long,
    type: XrefType,
    functionName: String?,  // 新增
    onClick: () -> Unit
)
```

布局改为两行显示：
- 第一行：类型标签 + 地址（与现有相同）
- 第二行（可选）：函数名（灰色小字，仅当 `functionName != null` 时显示）

如果该地址没有对应的函数名，不显示第二行。

1.3 在 `XrefBottomSheet` 的 `flatItems` 构建中，为每个 xref 条目查找对应的函数名

- 对于 `xrefsTo`（调用方）：用 `xref.from` 在 `functionOverlay` 中查找
- 对于 `xrefsFrom`（被调用）：用 `xref.to` 在 `functionOverlay` 中查找

1.4 在 `DisassemblyTab` 调用 `XrefBottomSheet` 处传入 `functionOverlay`

```kotlin
XrefBottomSheet(
    xrefData = xrefData,
    functionOverlay = functionOverlay,  // 传入
    onDismiss = { showXrefSheet = false },
    onXrefClick = { addr -> ... }
)
```

---

### 修改 2：修复系统返回后交叉引用消失

**涉及文件：**
- `app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt`

**根因：**
系统返回键退出再进入时，`AnalysisSession` 单例的 Rizin 会话可能已重新创建（handle 失效重建），但 `SoEditorCache` 标记了「已注入」，导致 `loadDartFunctionLabels()` 跳过 `reanalyzeXrefs()`，xref 表为空。

**修改内容：**

2.1 在 `loadDartFunctionLabels()` 的缓存命中 + 已注入分支中，始终调用 `reanalyzeXrefs()`

```kotlin
if (soEditorCache.isInjected(soPath)) {
    Log.i(TAG, "Dart 方法标签 + Rizin 注入均已缓存: ${cachedLabels.labels.size} 条")
    // 始终调用 reanalyzeXrefs()，确保 xref 表存在
    // 即使 Rizin 会话被重建，也能重新建立 xref 表
    val rebuilt = session.reanalyzeXrefs()
    Log.i(TAG, "xref 补充扫描: $rebuilt")
} else {
    // ... 现有逻辑不变 ...
}
```

注意：`reanalyzeXrefs()` 内部调用 `aar`，如果 Rizin 会话已有 xref 表，`aar` 会增量扫描，开销可控。

---

### 修改 3：加载动画直到交叉引用完成

**涉及文件：**
- `app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt`
- `app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt`

**修改内容：**

3.1 在 `SoEditorUiState` 增加 `isAnalyzing: Boolean` 字段

```kotlin
data class SoEditorUiState(
    // ... 现有字段 ...
    val isAnalyzing: Boolean = false,  // 新增：分析中（xref 等）
)
```

3.2 在 `openFile()` 中，将 `loadDartFunctionLabels()` 改为同步调用（或等待其完成）

方案：将 `loadDartFunctionLabels()` 改为 suspend 函数，在 `openFile()` 中顺序调用，这样 `isLoading = true/false` 自动覆盖整个流程。

```kotlin
suspend fun openFile(filePath: String) {
    // ... 现有逻辑 ...
    _uiState.value = SoEditorUiState(
        // ... 现有字段 ...
        isLoading = false,  // 文件已打开，但分析中
        isFileOpen = true,
        isAnalyzing = true  // 进入分析阶段
    )
    // 同步加载 Dart 标签 + xref
    loadDartFunctionLabels(filePath)  // 改为 suspend
    _uiState.value = _uiState.value.copy(isAnalyzing = false)
}
```

3.3 将 `loadDartFunctionLabels()` 改为 suspend 函数，移除内部的 `viewModelScope.launch`

```kotlin
private suspend fun loadDartFunctionLabels(soPath: String) {
    // 移除 viewModelScope.launch，直接执行
    // ... 现有逻辑 ...
}
```

3.4 在 `SoEditorScreen` 中，当 `isAnalyzing` 为 true 时显示加载动画

```kotlin
when {
    uiState.isLoading && !uiState.isFileOpen -> {
        LoadingContent(modifier = Modifier.align(Alignment.Center))
    }
    uiState.isAnalyzing -> {  // 新增
        AnalyzingContent(modifier = Modifier.align(Alignment.Center))
    }
    uiState.errorMessage != null -> {
        ErrorContent(...)
    }
    !uiState.isFileOpen -> {
        NoFileContent(...)
    }
    else -> {
        SoEditorContent(...)
    }
}
```

3.5 新增 `AnalyzingContent` 组件，显示加载动画和提示文字

```kotlin
@Composable
private fun AnalyzingContent(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator()
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "正在分析交叉引用...",
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
```

---

### 修改 4：删除搜索框默认的 "0x"

**涉及文件：**
- `app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt`

**修改内容：**

4.1 将第 112 行的 `"0x"` 改为空字符串

```kotlin
// 修改前
var inputAddress by remember { mutableStateOf("0x") }

// 修改后
var inputAddress by remember { mutableStateOf("") }
```

---

## 涉及文件汇总

| 文件 | 修改内容 |
|------|---------|
| `DisassemblyTab.kt` | 1. XrefBottomSheet + XrefRow 增加函数名列；2. 搜索框默认值改为空 |
| `SoEditorViewModel.kt` | 1. 缓存命中时始终调用 `reanalyzeXrefs()`；2. `loadDartFunctionLabels()` 改为 suspend；3. 增加 `isAnalyzing` 状态 |
| `SoEditorScreen.kt` | 增加 `AnalyzingContent` 加载动画，`isAnalyzing` 时阻塞操作 |

## 假设与决策

- 函数名查找使用已有的 `functionOverlay: Map<Long, String>`（地址→函数名），该数据已在 `DisassemblyTab` 中可用
- 对于 xrefsTo（调用方），用 `xref.from` 查函数名；对于 xrefsFrom（被调用），用 `xref.to` 查函数名
- 如果某地址没有对应的函数名，函数名列显示为空（不隐藏整行，保证布局一致性）
- 加载动画显示在文件内容上方，完成后才展示编辑器内容，确保用户在 xref 就绪后才可操作
- `reanalyzeXrefs()` 调用 `aar` 命令，开销可控（增量扫描，非全量重建）

## 验证步骤

1. 构建 APK：`gradlew assembleDebug`
2. 打开一个 SO 文件，检查交叉引用面板是否显示函数名
3. 长按指令 → 查看 xref 面板 → 确认每行地址旁显示对应的函数名（如果有）
4. 检查搜索框初始状态是否为空（不再显示 "0x"）
5. 打开 SO 文件时，确认显示「正在分析交叉引用...」加载动画，完成后才显示编辑器内容
6. 系统返回键退出 → 重新进入同一 SO 文件 → 长按指令 → 确认交叉引用数据正常显示