# 设置页面「清理项目缓存不彻底」修复计划

## 问题描述

用户点击设置页 → 「项目缓存」→ 「清理」后：
1. 仍然能看到残留（如最近文件列表依旧指向已删除的 `cacheDir/so_import_*`，下次再点会找不到文件报错；节区 metadata 还在 `SoEditorCache` 内存缓存里，重新打开同一 SO 看到旧脏数据，而文件字节已被删除的话会矛盾）。
2. 实际只删了 `cacheDir/` 下带前缀的部分文件，**`filesDir/` 下还有一堆与「项目分析」强相关的持久化数据未清理**：SO 编辑器 undo 栈、MCP 补丁服务的 backup/undo、MCP 导出的 patches。

用户预期：清理项目缓存 = 把所有**非引擎、非 Room DB** 的 App 私有目录下项目分析相关产物全部清掉，同时同步清空**内存中**与之绑定的缓存（Rizin 会话、SoEditorCache、最近文件列表）。

---

## 根因分析

### 当前 cleanProjectCache() 清理范围（不足）

| 类别 | 目录/对象 | 当前是否清理 | 说明 |
|------|----------|--------------|------|
| APK 导入副本 | `cacheDir/apk_import_*/` | ✅ `name.startsWith("apk_import_")` | 干净 |
| SO 导入副本 | `cacheDir/so_import_*/` | ✅ `name.startsWith("so_import_")` | 干净 |
| APK 提取 so 目录 | `cacheDir/extracted_<projectId>/` | ✅ `name.startsWith("extracted_")` | 干净 |
| Blutter 分析临时文件 | `cacheDir/blutter_tmp/` | ✅ `name == "blutter_tmp"` | 干净 |
| 分析 SQLite DB | `cacheDir/analysis_<id>.db{,-wal,-shm}` | ✅ 前缀匹配 | 干净 |
| 引擎压缩包 | `cacheDir/fler-engines.7z` | ✅ 精确名匹配 | 干净 |
| **MCP 导出的补丁目录** | `cacheDir/patches/` | ✅ `name == "patches"` | 干净 |
| ---- | ---- | ---- | ---- |
| **SO 编辑器 undo 栈持久化** | `filesDir/undo/{md5(path)}.json` | ❌ 遗漏 | [BackupManager.UNDO_DIR](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/BackupManager.kt#L43-L59) |
| **MCP 补丁 backup/undo** | `filesDir/mcp_patches/{safe_so_path}/backup.bak` `undo.json` | ❌ 遗漏 | [McpPatchService.patchDir](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/mcp/McpPatchService.kt#L43-L47) |
| ---- | ---- | ---- | ---- |
| **SoEditorCache 内存缓存**（元数据 / 注入标记 / Dart 标签） | `@Singleton` 中 `soMetadataCache` / `injectedSoPaths` / `dartLabelsCache` | ❌ 遗漏 | 清理完文件后，SO 编辑器打开同路径还能从内存拿到旧 sections/functions，但文件字节可能是新的，会出现「地址对不上 / 偏移错 / 大小错」 |
| **AnalysisSession 内存会话**（Rizin `RzCore*` 指针映射 `sessions / pathToHandle`） | `@Singleton` 中 `sessions` / `pathToHandle` Map | ❌ 遗漏 | 缓存 RzCore\* 指向的是已被删除文件路径的 handle，下次再访问路径 → 复用已失效 handle → Rizin 内部读写失效文件，偶发 crash 或读旧字节 |
| **最近文件列表**（`SoEditorViewModel._recentFiles`） | ViewModel 层（但 SettingsViewModel 是另一个 ViewModel，**两个 VM 间完全无感知**） | ❌ 遗漏 | 清理后「最近文件」还在，用户点最近文件 → openFile(path) → 但文件已被删 → 异常 toast 或白屏 |

### 关键发现：SettingsViewModel 与 SoEditorViewModel 内存状态隔离

- SettingsViewModel 只做了磁盘 IO 删除；**它没有 SoEditorCache / AnalysisSession / BackupManager / SoEditorViewModel 的引用**（Hilt 下它也不应该依赖这些）。
- 所以要让清理完整，不能只在 SettingsViewModel 里写文件删除逻辑，**必须下沉出一个「项目缓存管理器」组件**（或者在已有的单例里加清理方法），SettingsViewModel 调用它触发「磁盘+内存统清」。

---

## 修复方案

**目标**：清理后：
- `cacheDir/` 中所有与项目/SO 分析相关的文件 + `filesDir/undo/` + `filesDir/mcp_patches/` 全部删掉（引擎文件 `filesDir/engines/` 保留）
- `SoEditorCache` / `AnalysisSession`（Rizin 会话）/ `BackupManager` 三 @Singleton 内存态清空
- 最近文件列表清空
- 清理字节数统计包含 filesDir 下新增的两部分（更真实的释放量）

### 架构选型

**不做**：新建 CacheManager 类 → 需要 Hilt 注入改一波 + 调用路径较长。
**做**：在 `EnginePackManager` 这个已经有 `@ApplicationContext context` 注入且已被 SettingsViewModel 引用的单例里，**加一个 `cleanProjectCaches()` suspend 方法**，内部接收 `AnalysisSession`、`SoEditorCache`、`BackupManager` 做统清。

理由：
1. SettingsViewModel 已经 `@Inject enginePackManager: EnginePackManager`（[L34](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/feature/settings/SettingsViewModel.kt#L34)），**无需改 SettingsViewModel 的构造签名**；
2. `EnginePackManager` 本身在 `CoreModule` 已经是 `@Singleton`，可以再通过 Hilt 注入其他 `@Singleton` 组件（`AnalysisSession`、`SoEditorCache`、`BackupManager`）；
3. 这个方法本质是「App 级缓存清理入口」，放在 EnginePackManager 内语义合理（EnginePackManager 已经有 `clearEngines()` 做引擎清理，加个兄弟方法放项目缓存清理，对称美观）。

### 修改点清单

#### 1. [EnginePackManager.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/EnginePackManager.kt)

**构造器**新增 3 个注入：
```kotlin
@Inject constructor(
    @ApplicationContext private val context: Context,
    private val extractor: EngineExtractor,
    private val engineLoader: EngineLoader,
    private val analysisSession: AnalysisSession,   // ← 新增
    private val soEditorCache: SoEditorCache,       // ← 新增
    private val backupManager: BackupManager,       // ← 新增
)
```

**新增 `suspend fun cleanProjectCaches(): Long`**：
```kotlin
suspend fun cleanProjectCaches(): Long = withContext(Dispatchers.IO) {
    var freed = 0L

    // -------- 1. cacheDir 层：原有 8 类 + 通配兜底 --------
    val cache = context.cacheDir
    cache.listFiles()?.forEach { f ->
        val name = f.name
        val isAnalysisDb = f.isFile && name.startsWith("analysis_") &&
            (name.endsWith(".db") || name.endsWith("-wal") || name.endsWith("-shm"))
        val shouldDelete = name.startsWith("apk_import_") ||
            name.startsWith("so_import_") ||
            name.startsWith("extracted_") ||
            name == "patches" ||
            name == "blutter_tmp" ||
            name == "fler-engines.7z" ||
            name == "address_mappings" ||   // 地址映射临时目录（若有）
            isAnalysisDb
        if (shouldDelete) {
            freed += f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            f.deleteRecursively()
        }
    }

    // -------- 2. filesDir 层：新增 2 类（undo / mcp_patches） --------
    val files = context.filesDir
    files.listFiles()?.forEach { f ->
        val name = f.name
        if (name == "undo" || name == "mcp_patches") {
            freed += f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            f.deleteRecursively()
        }
    }

    // -------- 3. 内存层：清空 @Singleton 态 --------
    // 3.1 SoEditor 元数据 / Dart 标签 / Rizin 注入标记
    soEditorCache.clearAll()
    // 3.2 关闭所有 Rizin 打开的 handle（pathToHandle/sessions 清空，path 失效后下次再开自动新 handle）
    analysisSession.closeAll()
    // 3.3 BackupManager 内存 undo 栈 + seq 计数器
    backupManager.clearAllInMemory()

    freed
}
```

#### 2. [BackupManager.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/BackupManager.kt)

新增公共内存清理方法（因为 `fileStacks` / `fileSeqs` / `currentFilePath` 是 private var，EnginePackManager 外部碰不到）：

```kotlin
/** 清空内存中所有文件的撤销栈（用户「清理项目缓存」后调用）。 */
fun clearAllInMemory() {
    fileStacks.clear()
    fileSeqs.clear()
    currentFilePath = ""
}
```

（注：不提供磁盘删除方法，磁盘 `undo/` 目录的删除由 EnginePackManager 统一处理，职责更清晰。）

#### 3. [BackupManager.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/BackupManager.kt) — 可选附加

如果希望调用 `clearAllInMemory()` 时**同步把磁盘 `filesDir/undo/*.json`** 也删掉（而不是依赖 EnginePackManager 那一层 forEach 扫描），可以追加一个 `deletePersistence()` 方法。**但为了清理范围统一可追踪，计划里不做这层**，磁盘删除仍统一放在 EnginePackManager 的 forEach 扫描里。

#### 4. [SettingsViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/feature/settings/SettingsViewModel.kt#L136-L162)

把原来 25 行的 `cleanProjectCache()` 重写为调用新的下沉方法：

```kotlin
/**
 * 清理项目缓存文件 + 内存态。
 *  - 磁盘：cacheDir/{apk_import_, so_import_, extracted_, analysis_*.db, patches, blutter_tmp, fler-engines.7z}
 *          + filesDir/{undo/, mcp_patches/}
 *  - 内存：SoEditorCache（sections/symbols/functions/注入标记/Dart 标签）
 *          + AnalysisSession（所有 Rizin open handle）
 *          + BackupManager（撤销栈内存）
 *  - 不清理：Room 数据库 / engines 引擎文件 / MCP 配置。
 */
fun cleanProjectCache() {
    viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) {
            enginePackManager.cleanProjectCaches()
        }
        // 同时通知全局：最近文件列表清空（通过 Flow 广播即可；但 SoEditorViewModel._recentFiles 是另一 VM 的私有 StateFlow，无法直接改。
        // 处理方式：SoEditorViewModel 里监听一个全局事件流 —— 但本计划要最少改动，所以用更简单的办法：
        // 清理完成后，给用户 Toast 提示「最近文件请手动重开」或者干脆在 SoEditorScreen 启动时
        // 过滤掉不存在的文件。
        // 这里选择：在 SoEditorScreen 里做「启动时验证文件是否存在，不存在自动移除」，
        // 具体改 SoEditorViewModel 初始化时或 NoFileContent 渲染前。）
        _cacheCleanResult.value = freed
    }
}
```

#### 5. [SoEditorViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L106-L107) — 最近文件列表自动清理

在 `init { }` 里（**如果已有 init 就追加**）或者在 `openFile` 被拒绝（文件不存在）时，自动过滤最近文件列表里不存在的文件：

```kotlin
init {
    // （已有的其他 init 逻辑保留）
    // 最近文件列表在进入页面时做一次存在性校验：缓存清理后指向的文件已被删，直接自动移除
    viewModelScope.launch {
        val existing = _recentFiles.value.filter { File(it.path).exists() }
        if (existing.size != _recentFiles.value.size) {
            _recentFiles.value = existing
        }
    }
}
```

这样 SettingsViewModel 与 SoEditorViewModel 之间不需要事件流连接（否则要加 @Singleton 事件总线，改更多文件）。

---

## 验证步骤

1. **构造脏数据场景**（必过）：
   - 导入 APK 项目 → 进入 SO 编辑器 → 在 Hex Tab 改几字节（产生 undo 栈持久化到 `filesDir/undo/*.json`）
   - 开 MCP 服务器 → 用 `engine_write_bytes` 写几处（产生 `filesDir/mcp_patches/{safe}/backup.bak undo.json`）
   - 顶部 Tab「SO 编辑器」 → 最近文件列表应该有 2-3 个条目
   - 节区子 tab 点击查看数据（触发 Rizin open，AnalysisSession 的 pathToHandle 里有该 SO）
2. **触发清理**：设置 → 项目缓存 → 清理
3. **验证磁盘删除**（logcat 或 AS Device Explorer 查看）：
   - `cacheDir/` 下 `apk_import_* / so_import_* / extracted_* / analysis_*.db* / patches / blutter_tmp / fler-engines.7z` → 全部消失
   - `filesDir/undo/` → 空或不存在
   - `filesDir/mcp_patches/` → 空或不存在
   - `filesDir/engines/` → 仍然有引擎文件（**正确，未误删**）
4. **验证内存状态**：
   - 返回顶部 Tab「SO 编辑器」：最近文件列表应过滤到只剩 0 条（因为缓存清理后对应文件被删 → init 过滤）
   - 重新打开最近被清理过的项目 → SO 编辑器 → 节区数据应该是新查的（不会拿旧 metadata → 因为 `soEditorCache.clearAll()` 了）
   - 改字节 → undo 按钮灰色（撤销栈已清空 → 正确，因为 `backupManager.clearAllInMemory()` + 磁盘 `undo/` 删干净）
5. **误删保护验证**：`引擎版本检测 / 引擎包下载 / 引擎包列表` 正常工作 → `filesDir/engines/` 未被删。

---

## 风险与回退

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| `EnginePackManager` 构造签名改了（新增 3 个注入）→ Hilt 编译期能否正常生成 Dagger 代码 | 低 | 编译失败 | 所有 3 个参数都是 `@Singleton` 且已在 DI 图中（AnalysisSession 在 AnalysisModule 提供 / SoEditorCache 自身 @Inject 空构造 + @Singleton / BackupManager 在 AnalysisModule 提供），Hilt 能解析 |
| `analysisSession.closeAll()` 在协程中调用，mutex 没竞争但如果某个 Rizin handle 正在用（比如 MCP TCP 长连接请求中），会不会互等 | 极低 | Rizin 那一次请求返回 null 或失败（下次重新 open 即可） | Settings 清理缓存属于用户主动行为，这种竞态可接受；若要绝对安全可在 EnginePackManager 外层加 `Mutex` 包一次，但计划中不做，收益低 |
| 最近文件过滤用 `File(it.path).exists()` 在 init 里跑，如果最近文件数多会卡主线程？ | 低 | 轻微 UI 卡顿 | `MAX_RECENT_FILES` 若不大（默认是个位数），可以不切 IO；若担心，改 `viewModelScope.launch(Dispatchers.IO)` 后再赋值即可，计划中默认 IO 即可（上面第 5 条 init 代码已写在 `viewModelScope.launch{}` 中，不强制指定，实际文件 exists 调用很快） |
| 上游调用 `BackupManager.clearAllInMemory()` 后，`currentFilePath=""`，如果用户还在编辑页，`writeBytes` 里用到 currentFilePath 会写入空字符串路径 → ？ | 低 | 写入路径错误的 undo.json | 但 Settings 页面与 SoEditor 页面切换时，用户清理缓存后应当**主动离开**再重新进入；如果要更稳妥，`clearAllInMemory()` 改为只清 `fileStacks/fileSeqs`，保留 `currentFilePath` 让后续 writeBytes 正常重建 undo 持久化（磁盘 undo 目录被删，`backupManager.setCurrentFile(currentFilePath)` 会重新 `mkdirs`，不会失败）。**计划中采用此方案**。 |

### 修正后的 BackupManager.clearAllInMemory

```kotlin
/** 清空内存中所有撤销栈（保留当前文件路径，以便用户后续写入时自动重建持久化）。 */
fun clearAllInMemory() {
    fileStacks.clear()
    fileSeqs.clear()
    // currentFilePath 保留：以防 UI 上用户还停留在编辑页，后续 writeBytes 时 undo.json 能重建到正确路径。
}
```

**回退方案**：
- 撤回 EnginePackManager 构造签名和 `cleanProjectCaches()` 方法
- SettingsViewModel.cleanProjectCache 还原为原来的 forEach 遍历实现
- BackupManager 去掉 `clearAllInMemory()`
- SoEditorViewModel 去掉 init 中最近文件过滤逻辑
