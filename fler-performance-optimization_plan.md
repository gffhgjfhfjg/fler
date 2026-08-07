# fler 全方位性能优化方案

> 项目：Android 逆向分析工具（Kotlin + Compose + Hilt + Room + NDK C++ + Rizin）
> 仓库：`c:\Users\Len\AndroidStudioProjects\fler`
> 制定日期：2026-08-07

---

## 一、概要

本方案基于对 UI 层、数据库/ViewModel 层、JNI/原生层三个维度的系统性探索，识别出 **30+ 处具体性能瓶颈**，按 ROI 排序分四个阶段实施。

**核心目标**：
1. 消除大 SO（40MB+ libapp.so）分析时的 UI 卡死（scanStrings / 哈希流式读链路）
2. 消除 Compose 全 UI 层强制重组（零稳定性注解根因）
3. 释放 Dispatchers.IO 64 线程池的并发能力（单一 Mutex 反模式）
4. 修复主线程重活（5 万函数排序、SharedPreferences 同步读）

**预期收益**：
- scanStrings 提速 10-50 倍（消除 160 次全文件 mmap）
- UI 滚动/切换掉帧显著减少（@Immutable 让 Compose skippable 生效）
- 引擎查询并发度从 1 → 多（释放纯内存读路径）

---

## 二、当前状态分析

### 2.1 UI 层瓶颈（根因：零稳定性注解）

**全项目搜索 `@Immutable|@Stable` 返回 0 处匹配**，导致所有 `collectAsStateWithLifecycle` 触发的状态变化、所有父 Composable 重组时，子 Composable 因参数 unstable 无法 skippable，强制重组。

受影响的 11 个数据类：
- 5 个 UiState 类：`SoEditorUiState`、`HexDataState`、`DisassemblyDataState`、`XrefDataState`、`EmulationUiState`
- 6 个分析数据类：`DisasmInstruction`、`SectionInfo`、`SymbolInfo`、`FunctionInfo`、`StringInfo`、`Xref`

其他 UI 问题：
- [HexEditorTab.kt:284](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt#L284) LazyColumn 完全无 key
- [HexEditorTab.kt:261](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt#L261) `ByteArray` 参数 unstable
- [DisassemblyTab.kt:1236](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt#L1236) `pointerInput(Unit)` 使用 stale lambda
- [StructureTab.kt:89](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt#L89) 5 个 `List<T>` 参数全部 unstable
- 4 处 LazyColumn 无 key：HexEditorTab 主列表（高）、EmulationTab 日志列表（中）、AsmHelp（低）、SectionJumpDialog（低-中）

### 2.2 ViewModel/数据库层瓶颈

- [SoEditorViewModel.kt:784](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L784) `buildXrefFunctionNames` 主线程对 5 万级函数列表反复 `sortedBy` —— **P0**
- [PpEntry.kt:29-34](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/data/entity/PpEntry.kt#L29-L34) 缺 `(analysis_id, type)` 复合索引，6 个查询受影响
- [SoEditorViewModel.kt:180](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L180) `loadRecentFiles` 主线程同步读 SharedPreferences
- [AnalysisSession.kt:223-231](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/AnalysisSession.kt#L223-L231) `withEngine` 不兜底切 IO，安全性依赖各引擎自律
- [DartMethodDao.kt:30,118,159](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/data/dao/DartMethodDao.kt) 3 个无调用方的全量 `SELECT *` 重载（含 src_code 大字段）

### 2.3 JNI/原生层瓶颈（P0 灾难级）

**最严重链路**：`RizinEngine.scanStrings` → 256KB chunk × `readBytes` → `nativeReadBytes` → `rz_io_pread_at` 失败 → `raw_pread_all` 全文件 mmap

对 40MB libapp.so：
- scanStrings 需 ~160 次循环
- 每次 `readBytes` 触发 native 兜底路径
- 兜底路径每次：`open` + `fstat` + `mmap 40MB` + `memcpy` + `munmap 40MB` + `close`
- 累计 160 次完整 open/mmap/munmap/close 周期

具体问题：
- [rizin_jni.cpp:51-72](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/rizin_jni.cpp#L51-L72) `raw_pread_all` 每次全文件 mmap —— **P0**
- [RizinEngine.kt:274-318](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt#L274-L318) `scanStrings` 流式 256KB chunk 触发上述链路 —— **P0**
- [AnalysisSession.kt:43](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/AnalysisSession.kt#L43) 单一 Mutex 串行化所有 30+ 引擎操作 —— **P0**
- [SelfAnalysisEngine.kt:55-60](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/SelfAnalysisEngine.kt#L55-L60) 每次查询重新 open/close ElfParser —— P1
- [AnalysisSession.kt:300-332](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/AnalysisSession.kt#L300-L332) `writeBytes` 三次磁盘 I/O + 全程持锁 —— P1
- [RizinEngine.kt:574-609](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt#L574-L609) 哈希流式小 chunk 同 scanStrings 问题 —— P1
- [rizin_jni.cpp:334](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/rizin_jni.cpp#L334) `nativeWriteBytes` 每次发送 `io.cache=false` 命令 —— P2

---

## 三、优化方案（分阶段实施）

### 阶段 1：P0 关键瓶颈（最高 ROI）

#### 1.1 修复 nativeReadBytes 兜底路径全文件 mmap

**文件**：`app/src/main/cpp/jni_bridge/rizin_jni.cpp`

**问题**：`raw_pread_all` 每次调用都执行 `open` + `fstat` + `mmap 全文件` + `memcpy` + `munmap` + `close`。

**修改**：引入进程级 mmap 缓存，按 `RzCore*` 句柄复用已映射内存。

```cpp
// 在文件顶部添加全局缓存
static std::unordered_map<RzCore*, std::pair<void*, size_t>> g_coreMmaps;
static std::mutex g_mmapMutex;

// 修改 raw_pread_all 为复用 mmap
static ssize_t raw_pread_all(RzCore* core, const char* path, ut64 off, uint8_t* buf, size_t size) {
    std::lock_guard<std::mutex> lock(g_mmapMutex);
    
    auto it = g_coreMmaps.find(core);
    void* map = nullptr;
    size_t fileSize = 0;
    
    if (it != g_coreMmaps.end()) {
        map = it->second.first;
        fileSize = it->second.second;
    } else {
        int fd = ::open(path, O_RDONLY);
        if (fd < 0) return -1;
        struct stat st;
        if (::fstat(fd, &st) != 0) { ::close(fd); return -1; }
        fileSize = static_cast<size_t>(st.st_size);
        map = ::mmap(nullptr, fileSize, PROT_READ, MAP_SHARED, fd, 0);
        ::close(fd);
        if (map == MAP_FAILED) return -1;
        g_coreMmaps[core] = {map, fileSize};
    }
    
    if (off >= fileSize) return 0;
    size_t n = std::min(size, fileSize - static_cast<size_t>(off));
    std::memcpy(buf, static_cast<const uint8_t*>(map) + off, n);
    return static_cast<ssize_t>(n);
}
```

**配套修改**：在 `nativeClose`（同文件）中释放对应 mmap：

```cpp
// nativeClose 末尾添加
{
    std::lock_guard<std::mutex> lock(g_mmapMutex);
    auto it = g_coreMmaps.find(core);
    if (it != g_coreMmaps.end()) {
        ::munmap(it->second.first, it->second.second);
        g_coreMmaps.erase(it);
    }
}
```

**调用点修改**：`nativeReadBytes`（L267-277）兜底分支改为传 `core` 给 `raw_pread_all`。

**预期收益**：scanStrings 从 160 次全文件 mmap → 1 次，提速 10-50 倍。

#### 1.2 RizinEngine.scanStrings / 哈希改流式 RandomAccessFile

**文件**：`app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt`

**问题**：`scanStrings`（L274-318）和 `streamDigest`/`crc32`（L574-609）通过 `readBytes` 逐块读，即使 1.1 修复了 mmap，仍有 160 次 JNI 往返开销。

**修改**：哈希（md5/sha256/crc32）完全绕过 Rizin io，直接用 `RandomAccessFile` 流式读文件（文件内容不变，不需要走 RzCore）。

```kotlin
// 替换 streamDigest 实现（L574-609 附近）
private suspend fun streamDigest(handle: AnalysisHandle, algorithm: String): String? {
    val path = openSessions[handle.value] ?: return null
    return withContext(Dispatchers.IO) {
        try {
            val md = java.security.MessageDigest.getInstance(algorithm)
            java.io.RandomAccessFile(path, "r").use { raf ->
                val buffer = ByteArray(64 * 1024)  // 64KB
                while (true) {
                    val read = raf.read(buffer)
                    if (read <= 0) break
                    md.update(buffer, 0, read)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }
}
```

**scanStrings 修改**：保留现有 Rizin `izzj` 命令优先路径（对非 Dart AOT 库有效），仅当 `izzj` 返回空且为 Dart AOT 时回退到分块扫描；分块扫描时也改为 `RandomAccessFile` 流式读，绕过 Rizin io。

```kotlin
// scanStrings 回退路径（L304 附近）
val data = withContext(Dispatchers.IO) {
    java.io.RandomAccessFile(path, "r").use { raf ->
        raf.seek(pos)
        val buf = ByteArray(n.toInt())
        raf.readFully(buf)
        buf
    }
}
```

**预期收益**：哈希计算从 160 次 JNI + mmap → 单次 64KB 流式读，提速 5-10 倍。

#### 1.3 AnalysisSession 拆分读写锁

**文件**：`app/src/main/java/com/ai/fler/core/analysis/AnalysisSession.kt`

**问题**：[L43](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/AnalysisSession.kt#L43) 单一 `Mutex` 串行化所有 30+ 引擎操作，纯内存读取（`currentHandle`/`currentEngine`/`currentFilePath`）也被阻塞。Dispatchers.IO 64 线程池只有 1 个能跑引擎工作。

**修改**：

1. 纯内存读取方法（L54-60）改为直接读 `@Volatile` 字段，不走 Mutex：

```kotlin
// 删除 currentHandle/currentEngine/currentFilePath 内的 withLock
// 直接读 volatile 字段
fun currentHandle(): AnalysisHandle? = currentHandleValue  // @Volatile
fun currentEngine(): BinaryAnalysisEngine? = currentEngineValue  // @Volatile
fun currentFilePath(): String? = currentFilePathValue  // @Volatile
```

2. 引擎查询层保持 Mutex（RzCore 非线程安全），但 `writeBytes` 的落盘校验移出锁外（见 1.4）。

3. `withEngine` 增加兜底 dispatcher：

```kotlin
private suspend fun <R> withEngine(
    handle: AnalysisHandle? = null,
    block: suspend (BinaryAnalysisEngine, AnalysisHandle) -> R
): R? = withContext(Dispatchers.IO) {  // 兜底切 IO
    mutex.withLock {
        val actual = handle.takeIf { it != null && it.isValid } ?: currentHandleValue
        val pair = resolve(actual) ?: return@withLock null
        pair.second.lastAccess = now()
        block(pair.first, actual)
    }
}
```

**注意**：不引入 per-handle 细粒度锁（实现复杂、收益不确定），保留单一 Mutex 但移除纯内存读路径即可释放大部分并发能力。

**预期收益**：纯内存查询零阻塞，UI 状态查询并发度提升。

#### 1.4 AnalysisSession.writeBytes 去重复校验

**文件**：`app/src/main/java/com/ai/fler/core/analysis/AnalysisSession.kt`

**问题**：[L300-332](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/AnalysisSession.kt#L300-L332) 单次写触发 3 次磁盘 I/O（读旧值 + 写 + 落盘校验），native 层还有 1 次 readback，共 4 次。

**修改**：删除 Kotlin 层的落盘校验（L313-326），保留 native 层 readback 即可。

```kotlin
suspend fun writeBytes(offset: Long, data: ByteArray, soNameHint: String = ""): Boolean {
    val result = withEngine { e, h ->
        val old = e.readBytes(h, offset, data.size.toLong())
        val ok = e.writeBytes(h, offset, data)
        Triple(ok, old, h)  // 保留 handle 用于 patch 记录
    } ?: return false
    
    if (result.first && data.isNotEmpty()) {
        backupManager.recordPatch(/* 参数同原逻辑 */)
    }
    return result.first
}
```

**预期收益**：写操作 I/O 从 4 次降到 2 次，Hex 编辑器连续写入卡顿减少。

#### 1.5 SoEditorViewModel.buildXrefFunctionNames 缓存排序快照

**文件**：`app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt`

**问题**：[L784](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L784) `buildXrefFunctionNames` 每次调用都对 5 万级函数列表 `sortedBy { it.vaddr }`，且在主线程（`viewModelScope.launch` 默认 Main）。

**修改**：在 ViewModel 添加缓存字段，函数列表合并完成后构建一次排序快照。

```kotlin
// 添加字段（靠近 _dartFunctionLabels 声明处）
private var functionsByVaddr: List<FunctionInfo> = emptyList()

// 在函数列表合并完成处（openFile / loadDartFunctionLabels 合并后）构建快照
private fun rebuildFunctionsIndex() {
    functionsByVaddr = _uiState.value.functions.sortedBy { it.vaddr }
}

// 修改 buildXrefFunctionNames（L776-813）
private fun buildXrefFunctionNames(addresses: Set<Long>): Map<Long, String> {
    if (addresses.isEmpty()) return emptyMap()
    val sorted = functionsByVaddr  // 直接读缓存
    if (sorted.isEmpty()) return emptyMap()
    // 后续二分查找逻辑不变
    ...
}
```

**额外保障**：整个方法包 `withContext(Dispatchers.Default)`，避免缓存未命中时主线程排序。

```kotlin
private suspend fun buildXrefFunctionNames(addresses: Set<Long>): Map<Long, String> =
    withContext(Dispatchers.Default) {
        // 现有逻辑
    }
```

**调用点**：`loadXrefs`（L686）已用 `viewModelScope.launch`，改为 `suspend` 调用即可。

**预期收益**：xref 面板加载从 5-15ms 排序 → 0ms 缓存命中，掉帧消除。

---

### 阶段 2：UI 稳定性注解（高 ROI，零逻辑改动）

#### 2.1 为 11 个数据类添加 @Immutable / @Stable

**根因修复**：全项目零稳定性注解，导致 Compose 无法 skippable。

**修改清单**：

| 文件 | 数据类 | 注解 | 说明 |
|------|--------|------|------|
| `core/analysis/SectionInfo.kt` | `SectionInfo` | `@Immutable` | 纯值类型 |
| `core/analysis/SymbolInfo.kt` | `SymbolInfo` | `@Immutable` | 纯值类型 |
| `core/analysis/SymbolInfo.kt` | `StringInfo` | `@Immutable` | 纯值类型 |
| `core/analysis/SymbolInfo.kt` | `ImportInfo` | `@Immutable` | 纯值类型 |
| `core/analysis/SymbolInfo.kt` | `RelocInfo` | `@Immutable` | 纯值类型 |
| `core/analysis/SymbolInfo.kt` | `FileInfo` | `@Immutable` | 纯值类型 |
| `core/analysis/FunctionInfo.kt` | `FunctionInfo` | `@Immutable` | 纯值类型 |
| `core/analysis/FunctionInfo.kt` | `Xref` | `@Immutable` | 纯值类型 |
| `core/analysis/DisasmInstruction.kt` | `DisasmInstruction` | `@Immutable` | 含 ByteArray，需约定不可变 |
| `features/so_editor/SoEditorViewModel.kt` | `SoEditorUiState` | `@Immutable` | 约定 List 字段只读 |
| `features/so_editor/SoEditorViewModel.kt` | `HexDataState` | `@Stable` | 含 ByteArray + 自定义 equals |
| `features/so_editor/SoEditorViewModel.kt` | `DisassemblyDataState` | `@Immutable` | 约定 List 只读 |
| `features/so_editor/SoEditorViewModel.kt` | `XrefDataState` | `@Immutable` | 约定 List/Map 只读 |
| `features/so_editor/EmulationViewModel.kt` | `EmulationUiState` | `@Immutable` | 约定 List/Map 只读 |

**示例**：

```kotlin
// SectionInfo.kt
import androidx.compose.runtime.Immutable

@Immutable
data class SectionInfo(
    val name: String,
    val vaddr: Long,
    ...
)
```

```kotlin
// HexDataState 用 @Stable（因为有自定义 equals，Compose 会按 equals 比较）
@Stable
data class HexDataState(
    val offset: Long = 0,
    val data: ByteArray = ByteArray(0),
    ...
)
```

**约定**：所有被标注 `@Immutable` 的类，其 List/Map 字段视为只读（ViewModel 中只做整体替换，不就地修改）。

**预期收益**：UI 层 skippable 生效，状态变化时只重组真正依赖该状态的子树。

#### 2.2 HexEditorTab LazyColumn 加 key + ByteArray 包装

**文件**：`app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt`

**问题 1**：[L284](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/HexEditorTab.kt#L284) `items(count = rows)` 无 key。

**修改**：

```kotlin
// 替换 L288
items(
    count = rows,
    key = { rowIndex -> 
        val rowOffset = baseOffset + rowIndex * bytesPerRow
        rowOffset  // 用文件偏移作 key，翻页时身份明确
    }
) { rowIndex ->
    ...
}
```

**问题 2**：每行 `sliceArray` + 4 次 remember 产生 128+ 小对象分配（32 可见行）。

**修改**：在 `HexDataView` 外层一次性预计算所有行的字符串缓存：

```kotlin
// HexDataView 内，LazyColumn 之前
val rowCache = remember(data, baseOffset) {
    val bytesPerRow = 8
    val rows = (data.size + bytesPerRow - 1) / bytesPerRow
    Array(rows) { rowIndex ->
        val start = rowIndex * bytesPerRow
        val end = minOf(start + bytesPerRow, data.size)
        val rowBytes = data.sliceArray(start until end)
        HexRowCache(
            offset = baseOffset + start,
            hex = rowBytes.map { it.toUByte().toString(16).uppercase().padStart(2, '0') },
            ascii = rowBytes.map { if (it in 32..126) it.toChar().toString() else "." }.joinToString("")
        )
    }
}
```

`HexRowCache` 用 `@Immutable` data class。`HexRow` 改为接收 `HexRowCache` 而非 `ByteArray`，消除每行 remember。

**预期收益**：Hex 列表滚动帧率提升，data 变化时可见行重组开销减半。

#### 2.3 DisassemblyTab lambda 稳定化 + pointerInput 修复

**文件**：`app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt`

**问题 1**：[L1236](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt#L1236) `pointerInput(Unit)` 使用 stale lambda。

**修改**：

```kotlin
// L1236 附近
.pointerInput(onClick, onLongClick) {
    detectTapGestures(
        onTap = { onClick() },
        onLongPress = { onLongClick() }
    )
}
```

**问题 2**：[L207-217](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt#L207-L217) 内联 lambda 每次重组重新分配。

**修改**：用 `remember` 持有稳定 lambda：

```kotlin
// 在 DisassemblyTab 顶部
val onInstructionClickRemembered = remember(onInstructionClick) { 
    { instruction: DisasmInstruction ->
        onInstructionClick(instruction.address)
        editingInstruction = instruction
    }
}
// 下传给子组件
```

**预期收益**：减少 lambda 分配，pointerInput 回调始终走最新闭包。

#### 2.4 其余 LazyColumn 补 key

| 文件 | 位置 | key 策略 |
|------|------|----------|
| `EmulationTab.kt:765` | 日志列表 | `key = { index, log -> "${index}_${log.hashCode()}" }` |
| `AsmHelp.kt:125` | 帮助对话框 | 静态内容，加 `key = { it }` |
| `DisassemblyTab.kt:806` | SectionJumpDialog | `key = { it.name }` |

**预期收益**：动态列表（日志）项数变化时动画/状态正确。

---

### 阶段 3：数据库索引 + ViewModel 主线程修复

#### 3.1 PpEntry 添加 (analysis_id, type) 复合索引

**文件**：
- `app/src/main/java/com/ai/fler/data/entity/PpEntry.kt`
- `app/src/main/java/com/ai/fler/data/AppDatabase.kt`
- `app/src/main/java/com/ai/fler/core/di/DatabaseModule.kt`

**问题**：[PpEntry.kt:29-34](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/data/entity/PpEntry.kt#L29-L34) 缺索引，6 个查询同时过滤 `analysis_id = ? AND type = 'String'`。

**修改 1**：PpEntry 实体添加索引：

```kotlin
indices = [
    Index(value = ["method_id"]),
    Index(value = ["analysis_id"]),
    Index(value = ["vm_offset"]),
    Index(value = ["file_offset"]),
    Index(value = ["analysis_id", "type"]),  // 新增
    Index(value = ["analysis_id", "caller_count"])  // 新增，覆盖 getTopCallersByAnalysisId 排序
]
```

**修改 2**：AppDatabase 版本升到 7：

```kotlin
// AppDatabase.kt:29
@Database(
    entities = [...],
    version = 7,  // 原 6
    ...
)
```

**修改 3**：DatabaseModule 添加迁移：

```kotlin
// DatabaseModule.kt，在 MIGRATION_5_6 之后
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pp_entries_analysis_id_type` ON `pp_entries` (`analysis_id`, `type`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_pp_entries_analysis_id_caller_count` ON `pp_entries` (`analysis_id`, `caller_count`)")
    }
}

// 修改 .addMigrations(...)
.addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
```

**预期收益**：PP 字符串查询、top caller 查询走覆盖索引，扫描行数大幅减少。

#### 3.2 SoEditorViewModel.loadRecentFiles 移到 IO 协程

**文件**：`app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt`

**问题**：[L180](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt#L180) 主线程同步读 SharedPreferences。

**修改**：

```kotlin
// init 块内（L180 附近）
init {
    // ... 会话恢复逻辑不变 ...
    
    // 修改：先给空列表，IO 读完再更新
    viewModelScope.launch(Dispatchers.IO) {
        val files = loadRecentFiles()
        val existing = files.filter { java.io.File(it.path).exists() }
        if (existing.size != files.size) {
            saveRecentFiles(existing)
        }
        _recentFiles.value = existing
    }
}
```

删除原 L180 的同步调用和 L181-190 的二次过滤协程（已合并）。

**预期收益**：ViewModel 构造不再阻塞主线程磁盘读。

#### 3.3 删除无调用方的全量 SELECT * DAO

**文件**：`app/src/main/java/com/ai/fler/data/dao/DartMethodDao.kt`

**问题**：3 个方法返回含 `src_code` 大字段的全量实体，经 grep 确认无调用方，是潜在地雷。

**修改**：删除以下方法：
- `getByAnalysisIdList`（L29-30）
- `getMethodsWithClass`（L112-118）
- `getMethodsBySoPath`（L152-159）

保留 `getByAnalysisIdLight`、`getMethodsBySoPathLight`、`searchSrcWithClass`（有分页/有调用方）。

**预期收益**：消除未来误用导致数百 MB 内存占用的风险。

#### 3.4 SelfAnalysisEngine ElfParser 长驻

**文件**：`app/src/main/java/com/ai/fler/core/analysis/engine/SelfAnalysisEngine.kt`

**问题**：[L55-60](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/SelfAnalysisEngine.kt#L55-L60) 每次查询重新 `open` + `close` ElfParser。

**修改**：在 `openSessions` 中存储 `ElfParserBindings` 实例（保持打开），`close` 时统一释放。

```kotlin
// 替换 openSessions 的类型
private val openSessions = mutableMapOf<Long, ElfParserBindings>()
private var nextHandle = 1L

override suspend fun open(path: String): AnalysisHandle {
    val parser = ElfParserBindings()
    if (!parser.open(path)) {
        parser.close()
        throw IOException("ElfParser open failed: $path")
    }
    val handle = AnalysisHandle(nextHandle++, path)
    openSessions[handle.value] = parser
    return handle
}

override suspend fun close(handle: AnalysisHandle) {
    openSessions.remove(handle.value)?.close()
}

private inline fun <R> withParser(handle: AnalysisHandle, block: (ElfParserBindings, String) -> R): R? {
    val parser = openSessions[handle.value] ?: return null
    return block(parser, handle.filePath)
}
```

**注意**：`ElfParserBindings` 文档（L95）已声明「每个实例独占自己的 native handle」，支持长生命周期。

**预期收益**：SelfAnalysisEngine 查询从每次解析 ELF 头+节区表+符号表 → 直接复用，提速 10-100 倍（仅 fallback 路径）。

---

### 阶段 4：局部优化（低 ROI，按需实施）

#### 4.1 rizin_jni.cpp nativeWriteBytes 优化

**文件**：`app/src/main/cpp/jni_bridge/rizin_jni.cpp`

**修改 1**：[L334](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/rizin_jni.cpp#L334) 删除每次写都发送的 `io.cache=false`，改为在 `nativeOpen` 时设一次：

```cpp
// nativeOpen 末尾添加
rz_config_set(core->config, "io.cache", "false");
```

**修改 2**：[L352-357](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/rizin_jni.cpp#L352-L357) readback 改为可选（默认关闭，仅 DEBUG 开启），或直接删除（Kotlin 层已删除重复校验，见 1.4）。

#### 4.2 RizinEngine.getSymbols 合并命令

**文件**：`app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt`

**问题**：[L243-262](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt#L243-L262) 三次 JNI + 三次 JSON 解析。

**修改**：用 `iaj` 单命令返回 symbols+imports+sections（需验证 Rizin 支持），或在 `SoEditorCache` 中缓存首次结果（符号表会话期内不变）。

#### 4.3 EngineLoader symlink 准备加标志位

**文件**：`app/src/main/java/com/ai/fler/core/service/EngineLoader.kt`

**问题**：[L60-79](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/EngineLoader.kt#L60-L79) 每次 `ensureSharedLibsLoaded` 都全量扫描。

**修改**：添加 `private var symlinksPrepared = false`，已准备则跳过；合并 `readElfSoname` 和 `readElfNeeded` 为单次 `readDynamicInfo` 调用。

#### 4.4 elf_parser_jni.cpp 缓存 jclass/jmethodID

**文件**：`app/src/main/cpp/jni_bridge/elf_parser_jni.cpp`

**问题**：每次 `nativeGetSections`/`nativeGetSymbols` 都 `FindClass` + `GetMethodID`。

**修改**：在 `JNI_OnLoad` 时用 `NewGlobalRef` 缓存 `jclass`，`jmethodID` 存全局变量。

#### 4.5 EngineExtractor SHA256 buffer 提升

**文件**：`app/src/main/java/com/ai/fler/core/service/EngineExtractor.kt`

**修改**：[L281](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/service/EngineExtractor.kt#L281) buffer 从 8KB 提升到 64KB 或 256KB。

---

## 四、假设与决策

### 4.1 关键假设

1. **RzCore 非线程安全**：保留单一 Mutex 串行化引擎查询，仅释放纯内存读路径。不引入 per-handle 细粒度锁（实现复杂、收益不确定、易引入数据竞争）。
2. **ByteArray 不可变约定**：`@Immutable` 标注的含 ByteArray 类（如 `DisasmInstruction`），约定 ViewModel 只做整体替换，不就地修改数组内容。
3. **List 字段只读约定**：`@Immutable` 标注的 UiState 类，所有 List/Map 字段视为只读。
4. **native mmap 缓存生命周期**：mmap 与 RzCore 句柄绑定，`nativeClose` 时释放，App 进程退出时随 RzCore 一起回收。

### 4.2 关键决策

1. **不引入 kotlinx.collections.immutable**：避免新增依赖，改用 `@Immutable` 注解 + 约定。Compose 编译器会信任注解。
2. **不启用 compose stability config 文件**：注解方式更精确，全局 config 可能误伤。
3. **哈希完全绕过 Rizin io**：文件内容不变，不需要走 RzCore，直接 `RandomAccessFile` 流式读。
4. **保留 Rizin `izzj` 命令优先路径**：对非 Dart AOT 库有效，仅 Dart AOT 回退到分块扫描。
5. **SelfAnalysisEngine ElfParser 长驻**：不合并到 RizinEngine（Self 是 fallback，独立性更重要）。
6. **数据库版本 6→7**：新增两个索引，写迁移脚本，不破坏现有数据。

### 4.3 不做的事

1. 不引入 FTS4/FTS5 虚拟表（当前规模非必要）
2. 不重写 Rizin io map 逻辑（深入 Rizin 内部，风险高）
3. 不引入 per-handle 细粒度锁（实现复杂）
4. 不删除 `fallbackToDestructiveMigration`（保留兜底，但需评估 v1/v2 存量用户影响）

---

## 五、验证步骤

### 5.1 阶段 1 验证（P0 关键瓶颈）

1. **scanStrings 性能**：在 libapp.so（40MB）上执行字符串扫描，对比优化前后耗时（预期从 10+ 秒 → <1 秒）
2. **md5/sha256 性能**：对 libapp.so 计算哈希，对比耗时（预期从 5+ 秒 → <1 秒）
3. **写操作 I/O 次数**：Hex 编辑器连续写入 10 个字节，观察是否卡顿（预期无明显卡顿）
4. **xref 面板加载**：点击反汇编指令查看 xref，观察是否掉帧（预期无掉帧）
5. **并发查询**：在 MCP 客户端同时发起多个查询，观察是否串行阻塞

### 5.2 阶段 2 验证（UI 稳定性）

1. **Compose 重组次数**：开启 Compose 修饰符检查（`Modifier.composed` + Layout Inspector），观察状态变化时重组范围是否收窄
2. **Hex 列表滚动**：快速滚动 Hex 列表，观察帧率（预期稳定 60fps）
3. **StructureTab 搜索**：在 5 万级函数列表搜索，观察过滤响应（预期 <100ms）
4. **pointerInput 回调**：长按反汇编指令，确认弹出的菜单指向正确指令（非旧闭包）

### 5.3 阶段 3 验证（数据库 + ViewModel）

1. **PP 字符串查询**：用 `EXPLAIN QUERY PLAN` 验证走 `(analysis_id, type)` 索引
2. **数据库迁移**：从 v6 升级到 v7，验证索引创建成功且数据无损
3. **ViewModel 构造**：首启进入 SO 编辑器，观察是否卡顿（预期无主线程磁盘读）
4. **SelfAnalysisEngine 查询**：禁用 Rizin 引擎后（或对 Rizin 不支持的格式），验证 Self 查询响应时间

### 5.4 整体回归

1. **构建验证**：`gradlew clean` + 全量构建，确保无编译错误
2. **真机测试**：在中端设备上对 libapp.so 执行完整分析流程，对比优化前后总耗时
3. **内存监控**：用 Android Studio Profiler 监控内存峰值，确保 mmap 缓存未导致内存泄漏
4. **稳定性**：长时间操作（连续切换 SO、反复搜索），观察是否崩溃或内存溢出

---

## 六、实施顺序建议

1. **阶段 1.5 + 1.4**：buildXrefFunctionNames 缓存 + writeBytes 去重复校验（最小改动，立竿见影）
2. **阶段 1.1 + 1.2**：native mmap 缓存 + scanStrings/哈希流式读（核心瓶颈，需重新编译 NDK）
3. **阶段 1.3**：AnalysisSession 拆锁（需谨慎，影响全局）
4. **阶段 2.1**：11 个数据类加注解（零逻辑改动，全 UI 受益）
5. **阶段 2.2-2.4**：LazyColumn key + lambda 稳定化
6. **阶段 3.1**：PpEntry 索引 + 迁移（数据库改动，需测试）
7. **阶段 3.2-3.4**：ViewModel 主线程 + DAO 清理 + Self 长驻
8. **阶段 4**：按需实施局部优化

每个阶段完成后执行对应验证步骤，确认无回归再进入下一阶段。
