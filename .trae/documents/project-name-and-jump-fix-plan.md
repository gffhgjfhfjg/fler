# 修复计划：项目命名优化 + 首次跳转位置修复

## 摘要

两个独立问题：1）打开 APK 时默认项目名仅为文件名，多个项目难以区分；2）首次从结构Tab点击函数跳转到汇编Tab位置仍然错误（上次修复无效，需重新分析根因）。

---

## 问题1：默认项目名不直观

### 当前状态分析

**创建流程**：`NewProjectDialog` 中，选择 APK 文件后自动从文件名生成项目名：

```kotlin
if (name.isBlank()) {
    name = localFile?.name ?: getFileNameFromUri(context, it)
}
```

默认名称为 `app.apk`、`base.apk` 等无区分度的文件名。

**Project 实体**（[Project.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/data/entity/Project.kt)）：
- `name` — 项目名称（用户可改）
- `apkPath` — APK 完整路径（可用于提取文件名）
- `packageName` — 包名（分析后填充）
- `apkVersion` — 版本号（分析后填充）
- `dartVersion` — Dart 版本（分析后填充）

**项目列表卡片**（[ProjectScreen.kt:L278-L292](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/project/ProjectScreen.kt#L278-L292)）：只显示 `project.name` 和更新日期，没有 APK 文件名、包名等辅助信息。

**项目详情卡片**（[ProjectDetailScreen.kt:L232-L268](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/project/ProjectDetailScreen.kt#L232-L268)）：显示 `project.name`、APK 路径、Dart 版本，信息较完整但缺少包名和版本号。

### 修复方案

**改动 1：NewProjectDialog 默认名称优化**
- 去掉 APK 文件扩展名（`.apk`），仅保留文件名主体作为默认项目名
- 例如 `app.apk` → `app`，`base.apk` → `base`

**改动 2：ProjectCard 增加辅助信息行**
- 在项目名称下方添加第二行文本，显示：
  - APK 文件短名（从 `apkPath` 提取 `File(apkPath).name`）
  - 如果 `packageName` 不为空，显示 `packageName`
  - 如果 `apkVersion` 不为空，显示 `v${apkVersion}`
- 格式：`APK 文件名 | 包名 v版本号`（或只显示非空字段）

**改动 3：ProjectInfoCard 增加包名和版本号**
- 在 APK 路径下方添加 `packageName` 和 `apkVersion` 显示

**涉及文件**：
- `app/src/main/java/com/ai/fler/features/project/ProjectScreen.kt` — ProjectCard 组件 + NewProjectDialog
- `app/src/main/java/com/ai/fler/features/project/ProjectDetailScreen.kt` — ProjectInfoCard 组件

---

## 问题2：首次点击函数跳转位置仍然错误

### 当前状态分析

**上次修复**：在 `onFunctionClick`/`onSymbolClick` 中添加了 `setSelectedOffset`，确保 `selectedOffset` 与被点击的函数地址一致。

**仍然无效的原因**：`LaunchedEffect(Unit)` 在 `DisassemblyTab` 首次创建时运行，检查 `instructions.isEmpty()` 为 true（数据尚未加载），因此触发 `loadDisassembly(selectedOffset)`。这次调用与 `onFunctionClick` 中的 `loadDisassembly(func.vaddr, highlightAfterLoad = func.vaddr)` 并发执行。

关键区别：
- `onFunctionClick` 调用：`loadDisassembly(func.vaddr, highlightAfterLoad = func.vaddr)` → 有 `highlightAfterLoad`，会设置高亮和滚动
- `LaunchedEffect` 调用：`loadDisassembly(selectedOffset)` → **无** `highlightAfterLoad`，不会设置高亮和滚动

两个协程并发执行，后完成的覆盖前一个的结果。`LaunchedEffect` 的调用因为没有 `highlightAfterLoad`，最终状态中 `highlightAddress = null`，导致页面不滚动到目标地址。

**关键代码**：[DisassemblyTab.kt:L121-L125](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt#L121-L125)

```kotlin
LaunchedEffect(Unit) {
    if (!isMethodMode && disassemblyData.instructions.isEmpty()) {
        viewModel.loadDisassembly(selectedOffset)
    }
}
```

### 修复方案

在 `LaunchedEffect` 的条件中增加 `!disassemblyData.isLoading` 检查。当 `onFunctionClick` 发起的 `loadDisassembly` 已开始执行（`isLoading = true`）时，`LaunchedEffect` 跳过默认加载。

**时序分析**：
1. `onFunctionClick` → `loadDisassembly(func.vaddr, ...)` → `viewModelScope.launch` 调度协程 A
2. `setTab(DISASSEMBLY)` → Compose 调度重组
3. 协程 A 开始执行：设置 `isLoading = true`
4. 重组运行，创建 `DisassemblyTab`，`LaunchedEffect(Unit)` 运行
5. 此时 `isLoading = true`，`LaunchedEffect` 跳过默认加载 ✓

**涉及文件**：
- `app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt`

**具体修改**：
```kotlin
LaunchedEffect(Unit) {
    if (!isMethodMode && disassemblyData.instructions.isEmpty() && !disassemblyData.isLoading) {
        viewModel.loadDisassembly(selectedOffset)
    }
}
```

---

## 验证步骤

1. `gradlew assembleDebug` 构建 APK
2. 验证项目命名：新建项目 → 确认默认名称不含 `.apk` 后缀
3. 验证项目列表：项目卡片显示 APK 文件名 + 包名 + 版本号
4. 验证首次跳转：打开 SO → 结构Tab → 点击函数 → 确认跳转到正确地址并高亮闪烁
5. 返回结构Tab → 再次点击同一函数 → 仍然正确