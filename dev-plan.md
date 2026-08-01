# fler — AI 开发方案（dev-plan）

> 本文档供 AI 编码助手参考，基于 [方案.md](file:///e:/111/bitcontrol/fler/方案.md) v3 提炼。
> 单包引擎分发已由 [fler-dart](file:///e:/111/bitcontrol/out/fler-dart) 项目实现（CI + 动态链接 + 7z 打包）。
> 本文档聚焦 **App 端开发**：从零搭建 Android 工程 → 接入引擎包 → 完成全功能。

---

## 0. 当前状态

| 模块 | 状态 | 位置 |
|------|------|------|
| **引擎包 CI** | ✅ 已实现 | [build-dartvm.yml](file:///e:/111/bitcontrol/out/fler-dart/.github/workflows/build-dartvm.yml) |
| **dartvm.so 构建** | ✅ 已实现 | [build-dartvm.sh](file:///e:/111/bitcontrol/out/fler-dart/scripts/build-dartvm.sh) |
| **CMake 动态链接** | ✅ 已实现 | [CMakeLists.txt](file:///e:/111/bitcontrol/out/fler-dart/dartvm/CMakeLists.txt) |
| **blutter_entry.cpp** | ✅ 已实现 | [blutter_entry.cpp](file:///e:/111/bitcontrol/out/fler-dart/dartvm/src/blutter_entry.cpp) |
| **App 端代码** | ❌ 未开始 | `E:\111\bitcontrol\fler\` (空) |

**引擎包产物**：`fler-engines.7z` (~10-14MB)，包含 12 个 Dart 版本 + 共享库，GitHub Release 托管。

---

## 1. 工程初始化

### 1.1 技术栈

```
Kotlin 2.0+ | Jetpack Compose (BOM) | Material Design 3
Gradle 8.x | AGP 8.x | minSdk 26 | targetSdk 34
Hilt (DI) | Room (DB) | Navigation Compose | OkHttp | Coroutines
NDK r27 | CMake 3.22 | C++20
```

### 1.2 模块结构

```
fler/
├── app/                          ← Android 应用模块
│   ├── build.gradle.kts
│   └── src/main/
│       ├── java/com/fler/app/   ← Kotlin 源码
│       ├── cpp/                  ← C++ JNI 桥接（打包进 APK）
│       │   ├── CMakeLists.txt
│       │   ├── elf_parser/       ← 自研 ELF 解析器
│       │   ├── arm64_encoder/    ← 自研 ARM64 编码器
│       │   └── jni_bridge/       ← JNI 入口
│       ├── jniLibs/arm64-v8a/   ← 预编译 .so（随 APK 发布）
│       └── res/
└── native/                       ← 独立 C++ 原生源码（可被 CMake 引用）
    ├── elf_parser/
    └── arm64_encoder/
```

### 1.3 build.gradle.kts 关键配置

```kotlin
android {
    namespace = "com.fler.app"
    compileSdk = 34
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++20", "-fvisibility=hidden")
            }
        }
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-compiler:2.51.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    kapt("androidx.room:room-compiler:2.6.1")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

---

## 2. 分阶段开发路线

### P0: 骨架搭建（1-2 周）

**目标**：可运行的空壳 App + 导航 + 主题

**任务清单**：
1. 创建 Android 工程，配置 Gradle + Hilt + Compose
2. 实现 [FlerApplication.kt](file:///e:/111/bitcontrol/fler/方案.md#L50) + [MainActivity.kt](file:///e:/111/bitcontrol/fler/方案.md#L51)
3. 实现 [AppNavGraph.kt](file:///e:/111/bitcontrol/fler/方案.md#L55) — 4 Tab 导航
4. 实现 [Theme.kt](file:///e:/111/bitcontrol/fler/方案.md#L59) — Material 3 主题
5. 创建 4 个空 Tab 页面（项目/产物/SO/设置）
6. 实现通用组件：[CardListTile.kt](file:///e:/111/bitcontrol/fler/方案.md#L142) + [ShimmerPlaceholder.kt](file:///e:/111/bitcontrol/fler/方案.md#L147)

**验收标准**：
- App 可安装运行，4 Tab 可切换
- 主题切换正常（浅色/深色）
- Hilt 注入链路畅通

---

### P1: 引擎包管理（3-4 周）

**目标**：下载 → 解压 → 加载引擎

**这是核心阻塞项**，后续所有分析功能依赖引擎可用。

#### P1.1 EnginePackManager

文件：`core/service/EnginePackManager.kt`

```kotlin
@Singleton
class EnginePackManager @Inject constructor(
    private val context: Context,
    private val downloader: DualSourceDownloader,
    private val extractor: SevenZipExtractor,
) {
    private val engineDir = File(context.filesDir, "engines")

    /** 引擎目录布局（解压后） */
    // filesDir/engines/
    // ├── lib/                          ← 共享库（所有版本）
    // │   ├── libc++_shared.so
    // │   ├── libcapstone.so
    // │   ├── libicuuc.so              ← 可选，见 §0.5
    // │   └── libicudata.so            ← 可选
    // ├── dartvm_3.13.0.so             ← 12 个版本引擎
    // ├── dartvm_3.12.2.so
    // └── ...

    fun isEnginePackReady(): Boolean
    fun listInstalledVersions(): List<String>
    suspend fun ensureEnginesReady(): Flow<EngineProgress>
    suspend fun checkForUpdates(): EngineUpdate?
}
```

**关键实现要点**：
- 检查 `engineDir/dartvm_3.12.2.so` 是否存在来判断是否就绪
- 下载流程使用前台服务（Android 14+ 合规）
- SHA256 校验下载的 7z 完整性
- 7z 解压使用 `ProcessBuilder` 调用内置 7z 二进制

#### P1.2 SevenZipExtractor

文件：`core/service/SevenZipExtractor.kt`

```kotlin
object SevenZipExtractor {
    /**
     * 7z 解压方案选择：
     * 方案 A：内置 7zr 静态二进制（~1MB）到 jniLibs，ProcessBuilder 调用
     * 方案 B：使用 Java 7z 库（如 Apache Commons Compress，但不支持 LZMA2 全部特性）
     * 推荐：方案 A（最可靠，与 CI 使用的 7z 完全兼容）
     */
    suspend fun extract(archive: File, targetDir: File, onProgress: (Float) -> Unit)
}
```

#### P1.3 DualSourceDownloader

文件：`core/service/DualSourceDownloader.kt`

```kotlin
@Singleton
class DualSourceDownloader @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    companion object {
        // Gitee 镜像（国内优先）
        const val GITEE_URL = "https://gitee.com/fler/fler-dart/releases/download/latest/fler-engines.7z"
        // GitHub（回退）
        const val GITHUB_URL = "https://github.com/fler/fler-dart/releases/download/latest/fler-engines.7z"
    }

    suspend fun downloadEnginePack(
        target: File,
        onProgress: (downloaded: Long, total: Long, speed: String) -> Unit
    ): File
}
```

#### P1.4 EngineLoader（动态链接加载器）

文件：`core/service/EngineLoader.kt`

```kotlin
@Singleton
class EngineLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val engineDir by lazy { File(context.filesDir, "engines") }
    private val loadedLibs = mutableSetOf<String>()
    private val loadLock = Any()

    /**
     * 必须严格按依赖顺序加载（见方案 §4.5）
     * 如果 ICU 最终不需要（见 §0.5），则移除 libicuuc/libicudata 行
     */
    private val sharedLibs = listOf(
        "lib/libc++_shared.so",
        "lib/libicuuc.so",           // ← 如果 Blutter 不需要 ICU，删除此行
        "lib/libicudata.so",         // ← 同上
        "lib/libcapstone.so",
    )

    fun ensureSharedLibsLoaded() {
        synchronized(loadLock) {
            for (libPath in sharedLibs) {
                val libName = File(libPath).name
                if (libName !in loadedLibs) {
                    val libFile = File(engineDir, libPath)
                    if (libFile.exists()) {
                        System.load(libFile.absolutePath)
                        loadedLibs.add(libName)
                    }
                }
            }
        }
    }

    fun loadEngine(dartVersion: String): BlutterEngine {
        ensureSharedLibsLoaded()
        val engineFile = File(engineDir, "dartvm_${dartVersion}_android_arm64.so")
        if (!engineFile.exists()) throw EngineNotReadyException(dartVersion)

        synchronized(loadLock) {
            val engineName = "dartvm_${dartVersion}_android_arm64.so"
            if (engineName !in loadedLibs) {
                System.load(engineFile.absolutePath)
                loadedLibs.add(engineName)
            }
        }
        return BlutterEngine(dartVersion)
    }
}
```

#### P1.5 BlutterEngine（JNI 封装）

文件：`core/service/BlutterEngine.kt`

```kotlin
/**
 * 封装 blutter_entry.cpp 导出的 blutter_analyze() 函数。
 * 引擎 so 通过 EngineLoader 加载后，使用 dlsym 查找符号。
 *
 * 实际实现方案：
 * 1. EngineLoader.loadEngine() 执行 System.load() 后
 * 2. 本类通过 JNI native 方法调用 blutter_analyze()
 * 3. blutter_analyze() 在 C++ 侧已实现（见 blutter_entry.cpp）
 */
class BlutterEngine(private val dartVersion: String) {

    companion object {
        init {
            // BlutterEngine 类加载时，确保 native 库已加载
            // 实际的 System.load 由 EngineLoader 完成
        }

        @JvmStatic
        private external fun nativeBlutterAnalyze(soPath: String, dbPath: String): Int
    }

    /** 运行分析，结果直接写入 SQLite */
    fun analyze(soPath: String, dbPath: String): AnalyzeResult {
        val ret = nativeBlutterAnalyze(soPath, dbPath)
        return when (ret) {
            0 -> AnalyzeResult.Success
            -1 -> AnalyzeResult.TempDirError
            -2 -> AnalyzeResult.AnalysisError
            -3 -> AnalyzeResult.DbError
            else -> AnalyzeResult.UnknownError(ret)
        }
    }
}
```

**JNI 桥接** — `src/main/cpp/jni_bridge/blutter_jni.cpp`：

```cpp
#include <jni.h>
#include <dlfcn.h>

// blutter_analyze 由 dartvm_*.so 导出（visibility("default")）
typedef int (*blutter_analyze_fn)(const char* so_path, const char* db_path);

extern "C" JNIEXPORT jint JNICALL
Java_com_fler_app_core_service_BlutterEngine_nativeBlutterAnalyze(
    JNIEnv* env, jobject thiz, jstring soPath, jstring dbPath) {

    const char* so = env->GetStringUTFChars(soPath, nullptr);
    const char* db = env->GetStringUTFChars(dbPath, nullptr);

    // dartvm_*.so 已由 System.load 加载，dlsym 查找符号
    void* handle = dlopen(nullptr, RTLD_DEFAULT);  // 已加载的库中查找
    auto fn = (blutter_analyze_fn)dlsym(handle, "blutter_analyze");

    int ret = -999;
    if (fn) {
        ret = fn(so, db);
    } else {
        // 尝试按版本名显式打开
        // handle = dlopen("libdartvm_3.12.2.so", RTLD_LAZY);
    }

    env->ReleaseStringUTFChars(soPath, so);
    env->ReleaseStringUTFChars(dbPath, db);
    return ret;
}
```

**验收标准**：
- [x] 首次启动 → 检测无引擎 → 下载 fler-engines.7z → SHA256 校验 → 7z 解压
- [x] 引擎就绪后，loadEngine("3.12.2") 成功返回
- [x] 二次启动 → 检测引擎已就绪 → 跳过下载
- [x] 前台服务通知显示下载进度
- [x] Gitee 优先 → GitHub 回退

---

### P2: 原生库开发（3-5 周，与 P1 并行）

**目标**：自研 ELF 解析器 + ARM64 编码器，打包进 APK

#### P2.1 ELF 解析器

**C++ 头文件** — `native/elf_parser/include/elf_parser.h`：

```cpp
#pragma once
#include <cstdint>
#include <string>
#include <vector>

class ElfParser {
public:
    static ElfParser* open(const char* path);
    ~ElfParser();

    // === 只读 ===
    struct Section {
        std::string name;
        uint32_t type;
        uint64_t offset;
        uint64_t size;
        uint64_t address;
        uint64_t flags;
    };

    struct Symbol {
        std::string name;
        uint64_t address;
        uint64_t size;
        uint8_t type;
        uint8_t binding;
    };

    std::vector<Section> getSections() const;
    std::vector<Symbol> getSymbols() const;
    std::vector<uint8_t> getSectionData(const char* name) const;
    uint64_t findSymbolAddress(const char* name) const;
    std::vector<uint8_t> readBytes(uint64_t offset, size_t size) const;

    // === 写入 ===
    bool writeBytes(uint64_t offset, const uint8_t* data, size_t size);
    bool flush();

    // === CRC32 ===
    uint32_t computeCRC32(uint64_t offset, size_t size) const;

private:
    int fd_ = -1;
    void* mmap_ = nullptr;
    size_t fileSize_ = 0;
    // ... ELF 头、节头缓存
};
```

**实现要点**：
- 使用 `mmap` 映射文件（只读模式），写入使用 `pwrite`
- 解析 `.symtab` + `.dynsym` 两个符号表
- 解析 `.strtab` + `.dynstr` 字符串表
- 目标编译后 stripped < 400KB

**JNI 桥接** — `src/main/cpp/jni_bridge/elf_parser_jni.cpp`：
- 每个 Java 方法对应一个 JNI 函数
- Java 层传递 `long handle`（C++ 指针）
- `open()` 返回 handle，`close()` 释放

**Java 侧接口** — `core/jni/ElfParserBindings.kt`：
- 对应方案 §6.2 的 API 定义
- 使用 `data class` 封装返回值

#### P2.2 ARM64 编码器

**C++ 头文件** — `native/arm64_encoder/include/arm64_encoder.h`：

```cpp
#pragma once
#include <cstdint>
#include <string>
#include <functional>
#include <unordered_map>

class Arm64Encoder {
public:
    using EncoderFn = std::function<bool(const char* args, uint32_t& encoding)>;

    static Arm64Encoder& instance();

    // 注册指令编码器
    bool registerInstruction(const char* name, EncoderFn fn);

    // 编码指令
    // name: "ADD", "BL", "MOV" 等
    // args: "x0, x1, #4" 等
    // encoding: 输出的 4 字节机器码
    bool encode(const char* name, const char* args, uint32_t& encoding);

    // 列出所有已注册指令
    std::vector<std::string> listInstructions() const;

private:
    Arm64Encoder();
    std::unordered_map<std::string, EncoderFn> registry_;

    // 内置注册：~50 条常用指令
    void registerBuiltins();
};
```

**需支持的指令**（方案 §10.1）：

| 类别 | 指令 |
|------|------|
| 加载/存储 | LDR, LDRB, LDRH, LDRSW, STR, STRB, STRH, LDP, STP |
| 整数运算 | ADD, ADDS, SUB, SUBS, CMP, CMN, AND, ORR, EOR, BIC, ORN, MVN |
| 移位/扩展 | LSL, LSR, ASR, ROR, SXTB, SXTH, SXTW, UXTB, UXTH |
| 移动 | MOV, MOVZ, MOVN, MOVK |
| 地址 | ADRP, ADR |
| 分支 | B, BL, BLR, BR, RET, CBZ, CBNZ, TBZ, TBNZ |
| 条件选择 | CSEL, CSINC, CSET, CSETM |
| 系统 | NOP, HLT |

**编码示例**（BL 指令）：
```cpp
// BL <offset>
// 编码: 100101 imm26
// imm26 = (target - pc) >> 2
static bool encodeBL(const char* args, uint32_t& encoding) {
    uint64_t target;
    if (sscanf(args, "%lx", &target) == 1 || sscanf(args, "%lu", &target) == 1) {
        // 需要调用者提供 PC
        // 实际实现：BL 编码 = 0x94000000 | (imm26 & 0x3FFFFFF)
        encoding = 0x94000000 | ((target >> 2) & 0x3FFFFFF);
        return true;
    }
    return false;
}
```

**验收标准**：
- [x] ELF 解析器可解析 `libapp.so` 的所有节头 + 符号表
- [x] ELF 写入：修改字节 → flush → 重读验证
- [x] ARM64 编码器支持 50+ 指令
- [x] 编译后 stripped < 500KB

---

### P3: 项目管理 + 分析流程（3-4 周）

**目标**：选 APK → 检测版本 → 运行分析 → 写入 SQLite

#### P3.1 数据库设计

文件：`core/db/AppDatabase.kt`

```kotlin
@Database(
    entities = [
        ProjectEntity::class,
        PpEntryEntity::class,
        ObjInstanceEntity::class,
        AsmFunctionEntity::class,
        AddressMappingEntity::class,
        UndoRecordEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun ppEntryDao(): PpEntryDao
    abstract fun objInstanceDao(): ObjInstanceDao
    abstract fun asmFunctionDao(): AsmFunctionDao
    abstract fun addressMappingDao(): AddressMappingDao
    abstract fun undoRecordDao(): UndoRecordDao
}
```

**Blutter 已有的 SQLite Schema**（来自 [blutter_entry.cpp](file:///e:/111/bitcontrol/out/fler-dart/dartvm/src/blutter_entry.cpp#L67-L108)）：

```sql
-- blutter_entry.cpp 已创建以下表：
CREATE TABLE classes (id INTEGER PRIMARY KEY, name TEXT, super_cls TEXT, fields TEXT);
CREATE TABLE methods (id INTEGER PRIMARY KEY, class_id INTEGER, name TEXT, address INTEGER, size INTEGER, src_code TEXT);
CREATE TABLE strings (id INTEGER PRIMARY KEY, pp_offset INTEGER UNIQUE, value TEXT, ref_count INTEGER DEFAULT 0);
CREATE TABLE pp_entries (pp_offset INTEGER PRIMARY KEY, type TEXT, value TEXT, so_addr INTEGER);
CREATE TABLE string_refs (string_id INTEGER, method_address INTEGER, PRIMARY KEY (string_id, method_address));
```

**App 端需要额外创建的 Room 实体**：

```kotlin
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val apkUri: String,          // SAF URI
    val libappSoPath: String,    // 提取后的 libapp.so 路径
    val libflutterSoPath: String,
    val dartVersion: String,
    val architecture: String,
    val analysisDbPath: String?, // Blutter 分析结果 SQLite 路径
    val createdAt: Long,
    val analyzedAt: Long?,
)

@Entity(tableName = "address_mappings")
data class AddressMappingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val vmOffset: Long,     // Dart VM 堆偏移
    val fileOffset: Long,   // ELF 文件偏移
    val elfAddress: Long,   // ELF 虚拟地址
    val section: String,
    val symbol: String?,
)

@Entity(tableName = "undo_records")
data class UndoRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projectId: Long,
    val soName: String,
    val address: Long,
    val oldBytes: ByteArray,
    val newBytes: ByteArray,
    val timestamp: Long,
)
```

#### P3.2 Dart 版本检测

文件：`core/service/DartVersionDetector.kt`

```kotlin
class DartVersionDetector @Inject constructor(
    private val elfParser: ElfParserBindings,
) {
    private val supportedVersions = listOf(
        "3.13.0", "3.12.2", "3.12.1", "3.12.0",
        "3.11.5", "3.11.4", "3.10.7", "3.10.6",
        "3.9.2", "3.9.1", "3.8.1", "3.8.0",
    )

    /**
     * 从 libflutter.so 中提取 Dart 版本
     * 1. 用 ElfParser 读取 .rodata 段
     * 2. 搜索 "Dart SDK version: X.Y.Z" 字符串
     * 3. 匹配兼容版本列表
     */
    fun detect(libflutterSoPath: String): String?
}
```

#### P3.3 分析流程

文件：`features/project/ProjectViewModel.kt`

```kotlin
class ProjectViewModel @Inject constructor(
    private val engineLoader: EngineLoader,
    private val projectDao: ProjectDao,
) : ViewModel() {

    fun runAnalysis(project: ProjectEntity) = viewModelScope.launch {
        // 1. 确保引擎就绪
        if (!enginePackManager.isEnginePackReady()) {
            emit(UiState.NeedEnginePack)
            return@launch
        }

        // 2. 加载引擎
        val engine = engineLoader.loadEngine(project.dartVersion)

        // 3. 创建分析数据库路径
        val dbPath = File(context.getDatabasePath("analysis_${project.id}.db").path)

        // 4. 运行分析（直接写 SQLite）
        val result = engine.analyze(project.libappSoPath, dbPath.path)

        // 5. 更新项目记录
        projectDao.update(project.copy(
            analyzedAt = System.currentTimeMillis(),
            analysisDbPath = dbPath.path,
        ))
    }
}
```

**验收标准**：
- [x] SAF 选择 APK → 解压提取 libapp.so + libflutter.so
- [x] ElfParser 从 libflutter.so 中检测 Dart 版本
- [x] 运行分析 → blutter_analyze() 返回 0 → SQLite 文件生成
- [x] 数据库可查询 classes/methods/pp_entries 表

---

### P4: 产物浏览（3-4 周）

**目标**：UI 展示 Blutter 分析结果

#### P4.1 产物浏览页面

```
产物主页 /output/:id
├── 分析摘要卡片（类数/函数数/Stub 数）
├── pp.txt 列表 /output/:id/pp → PpDetailScreen /output/:id/pp/:offset
├── objs.txt 列表 /output/:id/objs → ObjsDetailScreen
└── asm 文件树 /output/:id/asm → AsmViewerScreen
```

**关键实现**：
- 读取 Blutter 生成的 SQLite 数据库（通过 Room `databaseBuilder` 动态打开）
- pp_entries 表 → PpBrowserScreen（LazyColumn + 搜索）
- classes + methods 表 → AsmBrowserScreen（树形结构 + 函数搜索）
- 分页加载（Paging 3 或手动 LIMIT/OFFSET）

#### P4.2 AddressTranslator

文件：`core/service/AddressTranslator.kt`

```kotlin
class AddressTranslator @Inject constructor(
    private val addressMappingDao: AddressMappingDao,
    private val elfParser: ElfParserBindings,
) {
    /**
     * Blutter 输出格式分析（方案 §7.1）：
     *
     * pp.txt 首行: "pool heap offset: 0xa00080" → Dart VM 堆基址
     * pp.txt Stub: [pp+0x10] Stub: Name (0x3324e0) → 直接含 ELF 地址
     * asm/*.dart: "// 0x829bbc: ldr x0, [PP, #0x428]; [pp+0x428] ..." → 双向映射
     */

    suspend fun initialize(analysisDbPath: String, asmDir: File)
    suspend fun vmOffsetToFileOffset(vmOffset: Long, soName: String): Long?
    suspend fun getContext(address: Long, soName: String): AddressContext
}
```

**验收标准**：
- [x] 产物主页显示分析摘要统计
- [x] pp.txt 列表可搜索、可滚动
- [x] asm 文件树可按库/类展开
- [x] 点击条目可跳转详情页

---

### P5: SO 编辑器（4-5 周）

**目标**：ELF 结构查看 + Hex 编辑 + 反汇编 + 指令补丁

#### P5.1 SO 编辑器主界面

文件：`features/so_editor/SoEditorScreen.kt`

```kotlin
@Composable
fun SoEditorScreen(
    soFile: File,
    addressTranslator: AddressTranslator,
    onEditInstruction: (address: Long, bytes: ByteArray) -> Unit,
) {
    // 3 个 Tab（HorizontalPager 或 TabRow）
    val tabs = listOf("结构", "Hex", "反汇编")
    // Tab 1: ELF 结构 — 节头表 + 符号表
    // Tab 2: Hex 编辑器 — 字节级查看 + 编辑
    // Tab 3: 反汇编 — Capstone 反汇编 + 函数标签
}
```

#### P5.2 Hex 编辑器

```kotlin
@Composable
fun HexEditorTab(
    elfParser: ElfParserBindings,
    onByteClick: (offset: Long, byte: Int) -> Unit,
) {
    // 传统 Hex 布局: 偏移 | 00 01 02...0F | ASCII
    // 分段加载（大文件不能一次性加载）
    // 选中字节 → 弹出编辑选项
}
```

#### P5.3 反汇编 Tab

```kotlin
@Composable
fun DisassemblyTab(
    soFile: File,
    capstone: CapstoneBindings,
    onInstructionClick: (address: Long, bytes: ByteArray) -> Unit,
) {
    // 三列: 地址 | 字节码 | 指令 + 注释
    // 函数标签切换
    // Capstone cs_disasm 迭代
}
```

**Capstone JNI 封装** — `core/jni/CapstoneBindings.kt`：

```kotlin
/**
 * Capstone 反汇编引擎 JNI 封装
 * 注意：libcapstone.so 在引擎包中（动态加载），不在 APK 内
 * 需要先调用 EngineLoader.ensureSharedLibsLoaded()
 */
class CapstoneBindings {
    companion object {
        init {
            // 确保 libcapstone.so 已加载
            // 由 EngineLoader 管理
        }
    }

    external fun csOpen(arch: Int, mode: Int): Long  // 返回 handle
    external fun csDisasm(handle: Long, code: ByteArray, address: Long, count: Int): Array<DisasmResult>
    external fun csClose(handle: Long)
}
```

#### P5.4 BackupManager

文件：`core/service/BackupManager.kt`

```kotlin
@Singleton
class BackupManager @Inject constructor(
    private val undoRecordDao: UndoRecordDao,
) {
    private val undoStack = ArrayDeque<PatchRecord>()
    private val MAX_UNDO = 50

    /**
     * 安全流程（方案 §8.2）：
     * 1. 首次编辑 → 创建 .bak 全量备份
     * 2. 应用补丁前 → CRC32 校验原字节
     * 3. 写入新字节 → 验证 CRC
     * 4. 记录到撤销栈 + Room
     */
    suspend fun createBackupIfNeeded(soFile: File)
    suspend fun recordPatch(address: Long, oldBytes: ByteArray, newBytes: ByteArray, soName: String)
    suspend fun undo(soName: String): Boolean
    suspend fun restoreFromBackup(soFile: File)
}
```

#### P5.5 产物 ↔ SO 联动

```kotlin
/**
 * 产物页 [SO中定位] 按钮 → 调用 AddressTranslator
 * → vmOffsetToFileOffset() → 跳转到 Hex 编辑器对应偏移
 * → 显示 ContextPanel（关联的函数/pp/objs 信息）
 */
fun onLocateInSo(vmOffset: Long, soName: String) {
    val fileOffset = addressTranslator.vmOffsetToFileOffset(vmOffset, soName)
    if (fileOffset != null) {
        navController.navigate("so/${projectId}/${soName}?offset=${fileOffset}")
    }
}
```

**验收标准**：
- [x] ELF 结构 Tab 显示节头表 + 符号表
- [x] Hex Tab 可查看/编辑字节
- [x] 反汇编 Tab 显示 ARM64 指令
- [x] 指令编辑 → 应用补丁 → Hex 刷新
- [x] 撤销操作可恢复
- [x] 产物页 [SO中定位] 可跳转到 SO 编辑器

---

### P6: 集成收尾（2-3 周）

**目标**：引擎包 CI → 版本更新 → 新手引导 → 测试

1. 引擎包自动构建 CI（已在 fler-dart 项目实现）
2. App 端版本更新检测（checkForUpdates）
3. 新手引导交互式向导
4. 导出功能（导出补丁 .patch 文件）
5. 完整流程联调
6. 性能优化（懒加载 + 分页 + 流式解析）
7. 测试覆盖

---

### P7: MCP Server（内嵌，2-3 周）

**目标**：App 内嵌 MCP 服务器，开放逆向能力给 AI 代理（Claude Desktop SSE 会话模式优先）

| # | 任务 | 状态 | 备注 |
|---|------|------|------|
| P7-1 | McpConfig 配置（端口/绑定/Token/补丁开关） | [ ] | SharedPreferences |
| P7-2 | JSON-RPC 协议层（initialize/tools/resources/prompts/ping/notifications） | [ ] | kotlinx-serialization |
| P7-3 | 双模式传输：HTTP+SSE（Claude Desktop）+ Streamable HTTP + 会话 | [ ] | JDK HttpServer |
| P7-4 | 工具注册（分析/类/方法/对象池/字符串/反汇编/ELF/地址） | [ ] | 复用 DAO + JNI |
| P7-5 | 补丁工具（McpPatchService：.bak+CRC+撤销栈持久化）+ destructiveHint 门控 | [ ] | 默认关闭 |
| P7-6 | McpServerManager + 前台服务保活（局域网常驻通知） | [ ] | |
| P7-7 | 设置页 UI + 连接 URL + adb reverse 提示 | [ ] | |
| P7-8 | 安全与并发（Token/局域网显式开启/错误隔离/SSE 会话/补丁锁） | [ ] | |
| P7-9 | 文档 + 验收 | [ ] | 方案.md / dev-progress.md |

**验收标准**：
- [ ] Claude Desktop SSE 会话模式可连接、握手、工具调用
- [ ] MCP Inspector（Streamable HTTP）可连接
- [ ] 工具异常返回结构化 JSON-RPC 错误，不崩服务
- [ ] 补丁默认不可用；开启后可备份/写盘/CRC 校验/撤销，App 重启后撤销栈仍在
- [ ] 局域网前台服务保活；端口冲突自动回退

---

## 3. 关键设计决策

### 3.1 引擎包加载方式

```kotlin
// ✅ 正确：System.load() 加载绝对路径
val libFile = File(engineDir, "lib/libcapstone.so")
System.load(libFile.absolutePath)

// ❌ 错误：System.loadLibrary() 只在 jniLibs 中查找
// System.loadLibrary("capstone")  // 找不到，因为 .so 不在 APK 中
```

### 3.2 Blutter 分析结果读取

Blutter 的 `blutter_analyze()` 直接写 SQLite 文件到指定路径。App 端有两种读取方案：

```kotlin
// 方案 A：直接用 Room 打开 Blutter 生成的 SQLite（推荐）
val db = Room.databaseBuilder(context, AnalysisDatabase::class.java, analysisDbPath)
    .allowMainThreadQueries()  // 仅查询用
    .build()

// 方案 B：读取后导入 App 的 Room 数据库（数据量太大时不推荐）
```

### 3.3 ELF 解析器 vs LIEF

- **选择自研**：体积 < 500KB，LIEF 编译后 ~5MB
- **备选**：如果自研解析器功能不足，可降级到 LIEF
- **关键 API**：`getSectionData(".rodata")` 用于 Dart 版本检测

### 3.4 ICU 依赖

根据代码分析（[blutter_entry.cpp](file:///e:/111/bitcontrol/out/fler-dart/dartvm/src/blutter_entry.cpp)），Blutter 的 Android 构建显式排除了 ICU 依赖（[build-dartvm.sh](file:///e:/111/bitcontrol/out/fler-dart/scripts/build-dartvm.sh) 中 `set(ICU_LIBRARIES "")`）。

**建议**：引擎包中不包含 ICU 库，EngineLoader 中移除 `libicuuc.so` / `libicudata.so` 加载。如果运行时出现 `UnsatisfiedLinkError` 指向 ICU 符号，再添加回共享库列表。

### 3.5 MCP Server 内嵌设计

- **数据源**：App 内 Room（dart_classes/dart_methods/pp_entries）+ 引擎 SQLite src_code，零导出
- **传输**：HTTP+SSE（Claude Desktop 兼容目标）+ Streamable HTTP（JDK HttpServer，零新增传输依赖）
- **JSON**：kotlinx-serialization-json（可靠 JSON-RPC/参数校验）
- **保活**：局域网模式前台服务（常驻通知）
- **补丁门控**：默认关闭，客户端决定；撤销栈持久化 undo.log
- **安全**：默认本机；LAN/Token 显式开启

---

## 4. 文件清单（需创建的文件）

### P0 骨架（~10 个文件）

```
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/fler/app/FlerApplication.kt
app/src/main/java/com/fler/app/MainActivity.kt
app/src/main/java/com/fler/app/app/navigation/AppNavGraph.kt
app/src/main/java/com/fler/app/app/navigation/Screen.kt
app/src/main/java/com/fler/app/app/theme/Theme.kt
app/src/main/java/com/fler/app/app/theme/Color.kt
app/src/main/java/com/fler/app/app/theme/Type.kt
app/src/main/java/com/fler/app/ui/components/CardListTile.kt
app/src/main/java/com/fler/app/ui/components/ShimmerPlaceholder.kt
```

### P1 引擎管理（~8 个文件）

```
app/src/main/java/com/fler/app/core/service/EnginePackManager.kt
app/src/main/java/com/fler/app/core/service/EngineLoader.kt
app/src/main/java/com/fler/app/core/service/BlutterEngine.kt
app/src/main/java/com/fler/app/core/service/SevenZipExtractor.kt
app/src/main/java/com/fler/app/core/service/DualSourceDownloader.kt
app/src/main/java/com/fler/app/core/service/EngineDownloadService.kt
app/src/main/java/com/fler/app/features/engine/EngineDownloadScreen.kt
app/src/main/java/com/fler/app/features/engine/EngineViewModel.kt
```

### P2 原生库（~15 个文件）

```
native/elf_parser/include/elf_parser.h
native/elf_parser/include/elf_section.h
native/elf_parser/include/elf_symbol.h
native/elf_parser/include/elf_writer.h
native/elf_parser/src/elf_parser.cpp
native/elf_parser/src/elf_section.cpp
native/elf_parser/src/elf_symbol.cpp
native/elf_parser/src/elf_writer.cpp
native/arm64_encoder/include/arm64_encoder.h
native/arm64_encoder/src/encoder.cpp
native/arm64_encoder/src/dp.cpp
native/arm64_encoder/src/load_store.cpp
native/arm64_encoder/src/branch.cpp
app/src/main/cpp/jni_bridge/elf_parser_jni.cpp
app/src/main/cpp/jni_bridge/arm64_encoder_jni.cpp
app/src/main/java/com/fler/app/core/jni/ElfParserBindings.kt
app/src/main/java/com/fler/app/core/jni/Arm64EncoderBindings.kt
app/src/main/java/com/fler/app/core/jni/CapstoneBindings.kt
app/src/main/cpp/CMakeLists.txt
```

### P3-P5（~30 个文件）

```
# 数据库
app/src/main/java/com/fler/app/core/db/AppDatabase.kt
app/src/main/java/com/fler/app/core/db/dao/*.kt (6 个)
app/src/main/java/com/fler/app/core/db/entity/*.kt (6 个)

# 服务
app/src/main/java/com/fler/app/core/service/AddressTranslator.kt
app/src/main/java/com/fler/app/core/service/BackupManager.kt
app/src/main/java/com/fler/app/core/service/DartVersionDetector.kt

# ViewModel
app/src/main/java/com/fler/app/features/project/ProjectViewModel.kt
app/src/main/java/com/fler/app/features/output/OutputViewModel.kt
app/src/main/java/com/fler/app/features/so_editor/SoEditorViewModel.kt

# UI 页面（~18 个）
app/src/main/java/com/fler/app/features/project/*.kt (4)
app/src/main/java/com/fler/app/features/output/*.kt (8)
app/src/main/java/com/fler/app/features/so_editor/*.kt (7)
```

---

## 5. 开发顺序优先级

```
P0 骨架 ──────→ P1 引擎管理 ──────→ P3 分析流程 ──────→ P4 产物浏览
                        │                                       │
                        └── P2 原生库 ──────────────────────────┤
                                                                ↓
                                                         P5 SO 编辑器
                                                                ↓
                                                         P6 集成收尾
```

**关键路径**：P0 → P1 → P3 → P4（最小可用版本）
**并行项**：P2 可与 P1 并行开发（不同文件，无依赖）

---

## 6. 常见陷阱

### 6.1 System.load 加载顺序

```kotlin
// ❌ 错误：先加载引擎，共享库未加载 → UnsatisfiedLinkError
System.load("dartvm_3.12.2.so")  // 找不到 libcapstone.so

// ✅ 正确：先加载共享库，再加载引擎
System.load("lib/libc++_shared.so")
System.load("lib/libcapstone.so")
System.load("dartvm_3.12.2.so")
```

### 6.2 Blutter SQLite 与 Room 共存

Blutter 的 `blutter_analyze()` 创建的 SQLite 表没有 Room 注解。App 端需要：
- **方案 A**（推荐）：用 `SupportSQLiteDatabase` 直接查询，不通过 Room
- **方案 B**：定义 `@Entity` 数据类映射到 Blutter 的表，Room 只读打开

```kotlin
// 方案 A 示例
val db = SQLiteDatabase.openDatabase(analysisDbPath, null, SQLiteDatabase.OPEN_READONLY)
val cursor = db.rawQuery("SELECT * FROM pp_entries WHERE type = 'String' LIMIT 100", null)
```

### 6.3 7z 解压在 Android 上的实现

```kotlin
// 7zr 静态二进制需要放在 app 可执行的目录
// 位置：context.applicationContext.nativeLibraryDir + "/lib7zr.so"
// 注意：Android 10+ 限制直接执行 ELF，需使用 AppProcessBuilder

// 替代方案：使用纯 Java 7z 库
// implementation("org.apache.commons:commons-compress:1.26.1")
// 但 LZMA2 解压可能不完全兼容
```

### 6.4 内存管理

```kotlin
// Blutter 分析大型 APK 可能产生数百 MB 的临时文件
// pp.txt 可能 50MB+, objs.txt 20MB+, asm/ 100MB+
// blutter_entry.cpp 已处理：分析完成后自动删除临时目录

// App 端读取分析结果时：
// ✅ 分页查询（LIMIT/OFFSET）
// ✅ LazyColumn 虚拟滚动
// ❌ 不要一次性 loadAll() 到内存
```
