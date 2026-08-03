# SoEditorDetailScreen 交叉引用加载动画缺失修复计划

## 问题描述

从**项目详情页**（`ProjectDetailScreen`）点击 APK 提取出的 SO 文件进入 SO 编辑器时，**没有显示"正在分析交叉引用..."加载动画**。而从顶层 Tab「SO 编辑器」直接打开同一份 SO 文件时，加载动画正常显示。

用户感知：进入页面后看到空白结构页 / 空数据，等 xref 分析跑完才突然出现内容，没有进度反馈。

---

## 根因分析

### 两个入口的差异

| 入口 | 目标页面 | 是否有 isAnalyzing 判断 |
|------|---------|------------------------|
| 顶层 Tab「SO 编辑器」 | [SoEditorScreen](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt#L282-L319) | ✅ `when { ... uiState.isAnalyzing -> AnalyzingContent() ... }` 完整分支 |
| 项目详情 → 点击 SO 文件 | [SoEditorDetailScreen](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorDetailScreen.kt#L235-L318) | ❌ Scaffold content 直接渲染 `Column + TabRow`，**没有任何状态分支** |

### SoEditorViewModel.openFile 的时序

```
openFile(filePath)
  ├─ ① isLoading = true
  ├─ ② session.open + sections/symbols/functions 查询
  ├─ ③ isLoading=false, isFileOpen=true, isAnalyzing=true   ← SoEditorScreen 在这里显示 AnalyzingContent
  ├─ ④ delay(50)                                         ← 给 UI 一帧时间渲染动画
  ├─ ⑤ loadDartFunctionLabels(filePath)                   ← Blutter 标签注入 + aar xref 扫描（耗时）
  └─ ⑥ isAnalyzing = false
```

`SoEditorDetailScreen` 跳过了 ③ → ⑥ 期间的 UI 状态渲染，用户看到的是未完成加载的结构/汇编页。

---

## 修复方案

**最小改动原则**：在 `SoEditorDetailScreen` 的 Scaffold content 里补上与 `SoEditorScreen` 等价的状态分支结构（`isLoading → error → isAnalyzing → 正常内容`），不拆分文件、不消解得太细。

### 文件修改清单

#### 1. [SoEditorDetailScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorDetailScreen.kt)

**修改位置**：`Scaffold` 的 `content` lambda（`innerPadding` 开始的 `Box { Column { ... } }` 外层包一层 `when`）。

**修改前**：
```kotlin
) { innerPadding ->
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (uiState.errorMessage != null) { Text(...) }
            TabRow(...) { ... }
            Box { when (currentTab) { ... } }
        }
    }
}
```

**修改后**：
```kotlin
) { innerPadding ->
    Box(
        modifier = Modifier.fillMaxSize().padding(innerPadding),
    ) {
        when {
            // ① 文件未打开 + 加载中 → 显示通用 LoadingContent
            uiState.isLoading && !uiState.isFileOpen -> {
                LoadingContent(modifier = Modifier.align(Alignment.Center))
            }
            // ② 错误 → 显示 ErrorContent
            uiState.errorMessage != null -> {
                ErrorContent(
                    message = uiState.errorMessage!!,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            // ③ 正在分析交叉引用 → 显示 AnalyzingContent（本计划的核心修复点）
            uiState.isAnalyzing -> {
                AnalyzingContent(modifier = Modifier.align(Alignment.Center))
            }
            // ④ 正常状态 → 原有 Column + TabRow + Tab 内容
            else -> {
                Column(modifier = Modifier.fillMaxSize()) {
                    //（原 errorMessage 显示保留，作为非致命错误兜底；也可删除，避免与 ② 重复）
                    TabRow(...)
                    Box { when (currentTab) { ... } }
                }
            }
        }
    }
}
```

**需要补的 Composable**（从 `SoEditorScreen.kt` 复制三份私有组件，`SoEditorDetailScreen` 自身已有 `CircularProgressIndicator` / `MaterialTheme` 等 import，补 import 即可）：
- `LoadingContent`：与 SoEditorScreen 完全一致
- `AnalyzingContent`：与 SoEditorScreen 完全一致（"正在分析交叉引用..."）
- `ErrorContent`：与 SoEditorScreen 完全一致

**可选的 import（若缺）**：
```kotlin
import androidx.compose.material3.CircularProgressIndicator   // 已有
import androidx.compose.ui.Alignment                         // 已有
```

---

## 验证步骤

1. **项目详情页入口验证（必过）**
   - 打开一个分析完成的项目 → 项目详情 → 点击「SO 文件」区域的 `libapp.so`
   - 预期：进入 SO 编辑器前先看到「正在分析交叉引用...」spinner，xref 扫描完成后才显示结构/Hex/汇编 Tab
   - 如果该 SO 已有 Dart 标签缓存（logcat 能看到 `Dart 方法标签 + Rizin 注入均已缓存`），delay(50) 后 isAnalyzing=false 动画会一闪而过但仍存在（肉眼可见 spinner）

2. **方法列表入口验证（覆盖 SoEditorDetailScreen 另一调用点）**
   - 项目详情 → 分析记录 → ASM 浏览 → 某方法 → 「在 SO 中编辑」
   - 预期：同样先出现 AnalyzingContent，再显示方法范围内的汇编页

3. **错误场景兜底**：故意给一个不存在的 filePath，看是否走 `ErrorContent` 分支（非必须）

---

## 风险与回退

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 加了 when 分支后 Tab 首次进入的 LaunchedEffect(Unit) 在 isAnalyzing 期间不触发，切到 else 分支才触发，时序更合理 | 低 | - | 预期行为，不需要处理 |
| AnalyzingContent / LoadingContent / ErrorContent 从 SoEditorScreen 复制时参数不一致 | 极低 | 编译错误或 UI 错位 | 直接对照 SoEditorScreen.kt 的实现复制 |
| 用户等待 isAnalyzing=false 过程中按系统返回键 → BackHandler 触发 onBack，不会卡死 | 低 | - | BackHandler 在 Scaffold 外层，不依赖 content 分支，行为正确 |

**回退方案**：删除 when 分支，恢复直接渲染 Column。
