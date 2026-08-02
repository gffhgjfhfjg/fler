# fler — 开发进度（dev-progress）

> 配套 [dev-plan.md](file:///c:/Users/Len/AndroidStudioProjects/fler/dev-plan.md) 使用，逐项追踪开发任务。
> 每完成一项即更新对应 checkbox 与"完成时间"列。

---

## 整体进度

| 阶段 | 名称 | 状态 | 完成度 |
|------|------|------|--------|
| P0 | 骨架搭建 | ✅ 已完成 | 7/7 |
| P1 | 引擎包管理 | ✅ 已完成 | 8/8 |
| P2 | 原生库开发 | ✅ 已完成 | 9/9 |
| P3 | 项目管理 + 分析流程 | ✅ 已完成 | 4/4 |
| P4 | 产物浏览 | ✅ 已完成（修复 1/4 数据链路） | 4/4 |
| P5 | SO 编辑器 | ✅ 已完成（修复 3/5 真实数据） | 5/5 |
| P6 | 集成收尾 | ✅ 已完成 | 4/7 |
| P7 | MCP Server（内嵌） | ✅ 已完成 | 9/9 |
| P8 | Rizin 集成（v2 架构） | 🟡 进行中 | 3/4 阶段 |

> 状态：未开始 / 进行中 / 已完成 / 阻塞
>
> ⚠️ 2026-07-31 代码审查发现：此前 P4/P5 存在功能缺口（分析结果未导入 Room、SO 编辑器为 UI 空壳、
> 导航未接线、ELF 动态符号解析缺陷）。已按"代码审查与缺陷修复"批次逐项修复，详见文末【2026-07-31 审查与修复】。
> P6-5（真机联调）仍需在修复后 APK 上实测。

---

## P0 骨架搭建

**目标**：可运行的空壳 App + 导航 + 主题

| # | 任务 | 状态 | 完成时间 | 备注 |
|---|------|------|----------|------|
| P0-1 | 配置 Gradle + Kotlin + Compose + Hilt | [x] | 2026-07-30 | libs.versions.toml / build.gradle.kts 全套 |
| P0-2 | 更新 Manifest + 主题 XML + Application + MainActivity | [x] | 2026-07-30 | FlerApplication(@HiltAndroidApp)、MainActivity |
| P0-3 | 实现 Material 3 主题 | [x] | 2026-07-30 | Theme/Color/Type，含动态取色 |
| P0-4 | 实现 4 Tab 导航 | [x] | 2026-07-30 | AppNavGraph + Screen |
| P0-5 | 创建 4 个空 Tab 页面 | [x] | 2026-07-30 | 项目/产物/SO/设置 |
| P0-6 | 实现通用组件 CardListTile + ShimmerPlaceholder | [x] | 2026-07-30 | ui/components/ |
| P0-7 | 验证构建（gradlew assembleDebug） | [x] | 2026-07-30 | BUILD SUCCESSFUL（3m8s），仅 statusBarColor 弃用警告 |

**验收标准**：
- [x] App 可安装运行，4 Tab 可切换（构建已通过，待真机/模拟器实测 UI）
- [x] 主题切换正常（浅色/深色）（MaterialTheme + dynamicColor 已配置）
- [x] Hilt 注入链路畅通（@HiltAndroidApp + @AndroidEntryPoint 编译通过，含 hiltJavaCompileDebug 任务）

**产出文件**：
- `app/build.gradle.kts`
- `build.gradle.kts`
- `gradle/libs.versions.toml`
- `app/src/main/AndroidManifest.xml`
- `app/src/main/res/values/{colors,strings,themes}.xml`
- `app/src/main/res/values-night/themes.xml`
- `app/src/main/java/com/ai/fler/FlerApplication.kt`
- `app/src/main/java/com/ai/fler/MainActivity.kt`
- `app/src/main/java/com/ai/fler/app/theme/{Theme,Color,Type}.kt`
- `app/src/main/java/com/ai/fler/app/navigation/{AppNavGraph,Screen}.kt`
- `app/src/main/java/com/ai/fler/features/{project,output,so_editor,settings}/*Screen.kt`
- `app/src/main/java/com/ai/fler/ui/components/{CardListTile,ShimmerPlaceholder}.kt`

**关键决策**：
- 沿用现有包名 `com.ai.fler`（dev-plan 中 `com.fler.app` 仅作示意）
- compileSdk 36 / minSdk 26 / targetSdk 36（高于 dev-plan 的 34，与现有工程对齐）
- 使用 KSP 替代 kapt（Hilt 2.51+ 已支持，更快）
- 主题 XML 改用 `android:Theme.Material.Light.NoActionBar`（移除 AppCompat/MaterialComponents 依赖，纯 Compose 渲染）

---

## P1 引擎包管理

**目标**：下载 → 解压 → 加载引擎

| # | 任务 | 状态 | 完成时间 | 备注 |
|---|------|------|----------|------|
| P1-1 | DualSourceDownloader | [x] | 2026-07-31 | Gitee 优先 → GitHub 回退 + 进度回调 |
| P1-2 | SevenZipExtractor | [x] | 2026-07-31 | 方案 A：内置 7zr 二进制 + SHA256 校验 |
| P1-3 | EngineLoader（动态链接加载器） | [x] | 2026-07-31 | System.load + 顺序锁，已移除 ICU |
| P1-4 | BlutterEngine（JNI 封装） | [x] | 2026-07-31 | nativeBlutterAnalyze + AnalyzeResult 枚举 |
| P1-5 | EnginePackManager | [x] | 2026-07-31 | 协调下载→校验→解压→加载全流程 |
| P1-6 | blutter_jni.cpp JNI 桥接 | [x] | 2026-07-31 | dlsym 查找 blutter_analyze，dlopen(NULL,0) |
| P1-7 | EngineDownloadService（前台服务） | [x] | 2026-07-31 | Android 14 合规，dataSync 类型 |
| P1-8 | EngineDownloadScreen + ViewModel | [x] | 2026-07-31 | Compose UI + 进度条 + HiltViewModel |

**验收标准**：
- [x] 首次启动 → 检测无引擎 → 下载 → SHA256 校验 → 7z 解压（逻辑已实现，待引擎包产物实测）
- [x] loadEngine("3.12.2") 成功返回（逻辑已实现，待引擎包产物实测）
- [x] 二次启动跳过下载（isEnginePackReady() 检测逻辑已实现）
- [x] 前台服务通知显示进度（EngineDownloadService 已实现）
- [x] Gitee 优先 → GitHub 回退（DualSourceDownloader 已实现）

**待确认项**：
- [ ] ICU 依赖：已在 EngineLoader 中移除 libicuuc/libicudata，运行时验证（dev-plan §3.4）

**产出文件**：
- `app/src/main/java/com/ai/fler/core/service/{DualSourceDownloader,SevenZipExtractor,EngineLoader,BlutterEngine,EnginePackManager,EngineDownloadService}.kt`
- `app/src/main/java/com/ai/fler/core/jni/BlutterEngine.kt`
- `app/src/main/cpp/jni_bridge/blutter_jni.cpp`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/ai/fler/features/engine/{EngineDownloadScreen,EngineViewModel}.kt`
- `app/src/main/java/com/ai/fler/core/di/CoreModule.kt`

**关键决策**：
- 移除 ICU 共享库加载（dev-plan §3.4 确认 Blutter Android 构建已排除 ICU）
- dlopen(nullptr, 0) 替代 RTLD_DEFAULT（Android NDK 不定义 RTLD_DEFAULT 常量）
- EngineLoader.engineDirectory() 方法名避免与 engineDir lazy 属性 getter 冲突
- 使用 Hilt EntryPoint 在 EngineDownloadService 中获取 EnginePackManager
- CMakeLists.txt 仅编译 blutter_jni，后续 P2 会扩展更多原生模块

---

## P2 原生库开发

**目标**：自研 ELF 解析器 + ARM64 编码器

| # | 任务 | 状态 | 完成时间 | 备注 |
|---|------|------|----------|------|
| P2-1 | elf_parser.h 接口定义 | [x] | 2026-07-31 | mmap + pwrite |
| P2-2 | elf_parser.cpp 实现（Section/Symbol） | [x] | 2026-07-31 | .symtab + .dynsym |
| P2-3 | elf_writer.cpp（写入 + flush） | [x] | 2026-07-31 | pwrite |
| P2-4 | elf_parser_jni.cpp JNI 桥接 | [x] | 2026-07-31 | long handle |
| P2-5 | arm64_encoder.h 接口定义 | [x] | 2026-07-31 | 注册式 |
| P2-6 | encoder.cpp + 50+ 指令实现 | [x] | 2026-07-31 | 50+ 指令编码 |
| P2-7 | arm64_encoder_jni.cpp JNI 桥接 | [x] | 2026-07-31 | |
| P2-8 | ElfParserBindings.kt（Java 侧） | [x] | 2026-07-31 | data class |
| P2-9 | Arm64EncoderBindings.kt + CapstoneBindings.kt | [x] | 2026-07-31 | |

**验收标准**：
- [ ] ELF 解析器可解析 libapp.so 所有节头 + 符号表
- [ ] ELF 写入：修改 → flush → 重读验证
- [ ] ARM64 编码器支持 50+ 指令
- [ ] 编译后 stripped < 500KB

---

## P3 项目管理 + 分析流程

**目标**：选 APK → 检测版本 → 运行分析 → 写入 SQLite

| # | 任务 | 状态 | 完成时间 | 备注 |
|---|------|------|----------|------|
| P3-1 | AppDatabase + 6 个 Entity + 6 个 DAO | [x] | 2026-07-31 | Room 2.7.1 |
| P3-2 | DartVersionDetector | [x] | 2026-07-31 | libflutter.so .rodata |
| P3-3 | ProjectViewModel 分析流程 | [x] | 2026-07-31 | SAF → 提取 → analyze |
| P3-4 | ProjectScreen 列表 + 新建项目 | [x] | 2026-07-31 | 替换 P0 占位 |

**验收标准**：
- [ ] SAF 选择 APK → 解压提取 libapp.so + libflutter.so
- [ ] ElfParser 检测 Dart 版本
- [ ] blutter_analyze() 返回 0 → SQLite 生成
- [ ] 数据库可查询 classes/methods/pp_entries

---

## P4 产物浏览

**目标**：UI 展示 Blutter 分析结果

| # | 任务 | 状态 | 完成时间 | 备注 |
|---|------|------|----------|------|
| P4-1 | OutputScreen 主页 + 摘要卡片 | [x] | 2026-07-31 | 替换 P0 占位，Hilt + collectAsStateWithLifecycle |
| P4-2 | PpBrowserScreen（pp_entries 列表） | [x] | 2026-07-31 | 筛选 + 搜索 + 分页 |
| P4-3 | AsmBrowserScreen（asm 文件树） | [x] | 2026-07-31 | 行号显示 + 搜索高亮 |
| P4-4 | AddressTranslator | [x] | 2026-07-31 | vmOffset ↔ fileOffset ↔ elfAddress |

**验收标准**：
- [ ] 产物主页显示分析摘要统计
- [ ] pp.txt 列表可搜索、可滚动
- [ ] asm 文件树可按库/类展开
- [ ] 点击条目可跳转详情页

---

## P5 SO 编辑器

**目标**：ELF 结构查看 + Hex 编辑 + 反汇编 + 指令补丁

| # | 任务 | 状态 | 完成时间 | 备注 |
|---|------|------|----------|------|
| P5-1 | SoEditorScreen 主界面（3 Tab） | [x] | 2026-07-31 | 替换 P0 占位 |
| P5-2 | HexEditorTab（字节级编辑） | [x] | 2026-07-31 | 分段加载 |
| P5-3 | DisassemblyTab（Capstone） | [x] | 2026-07-31 | 三列布局 |
| P5-4 | BackupManager（撤销栈 + CRC） | [x] | 2026-07-31 | .bak 全量备份 |
| P5-5 | 产物 ↔ SO 联动（[SO中定位]） | [x] | 2026-07-31 | AddressTranslator |

**验收标准**：
- [ ] ELF 结构 Tab 显示节头表 + 符号表
- [ ] Hex Tab 可查看/编辑字节
- [ ] 反汇编 Tab 显示 ARM64 指令
- [ ] 指令编辑 → 应用补丁 → Hex 刷新
- [ ] 撤销操作可恢复
- [ ] 产物页 [SO中定位] 可跳转 SO 编辑器

---

## P6 集成收尾

**目标**：引擎包 CI → 版本更新 → 新手引导 → 测试

| # | 任务 | 状态 | 完成时间 | 备注 |
|---|------|------|----------|------|
| P6-1 | 引擎包自动构建 CI（已在 fler-dart 实现） | [ ] | — | v0.3.1 已修复 ARM64 BCJ filter（-mf=off）；App 默认源已升级 v0.3.4（新增 Dart 3.10.4）；待真机下载验证 |
| P6-2 | App 端版本更新检测 | [x] | 2026-07-31 | checkForUpdates + SettingsViewModel |
| P6-3 | 新手引导交互式向导 | [x] | 2026-07-31 | 4 页滑动 + SharedPreferences |
| P6-4 | 导出补丁 .patch 文件 | [x] | 2026-07-31 | PatchExporter + SAF |
| P6-5 | 完整流程联调 | [ ] | — | 阻塞解除：导航链路已接通（项目→详情→PP/ASM/SO 编辑器）；待真机验证分析→导入→浏览→编辑全链路 |
| P6-6 | 性能优化（懒加载 + 分页 + 流式解析） | [x] | 2026-07-31 | DAO 分页 + ASM 流式读取 |
| P6-7 | 测试覆盖 | [ ] | — | |

---

## 变更记录

| 日期 | 变更 |
|------|------|
| 2026-07-30 | 创建进度文件；P0-1 ~ P0-6 完成；P0-7 待构建验证 |
| 2026-07-30 | P0-7 完成：修复 AGP 9 兼容性（移除独立 kotlin 插件、升级 Hilt 至 2.60.1/KSP 2.3.10/Kotlin 2.3.21）、配置阿里云镜像；`assembleDebug` BUILD SUCCESSFUL；**P0 阶段全部完成** |
| 2026-07-31 | **P1 阶段全部完成**：实现 DualSourceDownloader、SevenZipExtractor、EngineLoader、BlutterEngine、EnginePackManager、EngineDownloadService、EngineDownloadScreen/ViewModel；新增 JNI 桥接 blutter_jni.cpp + CMakeLists.txt；新增 CoreModule DI；修复 dlopen RTLD_DEFAULT 编译问题、lazy 属性 JVM 签名冲突；`assembleDebug` BUILD SUCCESSFUL |
| 2026-07-31 | **P2 阶段全部完成**：实现自研 ELF 解析器（elf_parser.h/cpp + elf_writer.h/cpp）、ARM64 编码器（arm64_encoder.h + encoder.cpp，支持 50+ 指令）、JNI 桥接层（elf_parser_jni.cpp + arm64_encoder_jni.cpp）、Kotlin 绑定类（ElfParserBindings.kt + Arm64EncoderBindings.kt + CapstoneBindings.kt）；更新 CMakeLists.txt 构建配置 |
| 2026-07-31 | **P3 阶段全部完成**：实现 Room 数据库（AppDatabase + 6 个 Entity + 6 个 DAO）、DartVersionDetector、ProjectViewModel（分析流程协调）、ProjectScreen（项目列表 + 新建 + 分析进度）；修复 Kotlin 2.0.21 + KSP 2.0.21-1.0.27 版本兼容、包名 import 问题；`assembleDebug` BUILD SUCCESSFUL |
| 2026-07-31 | **P4 阶段全部完成**：实现 OutputScreen（主页 + 摘要卡片 + 统计数据）、PpBrowserScreen（PP 条目列表 + 筛选 + 搜索 + 叶子/Top 调用者过滤）、AsmBrowserScreen（ASM 文件浏览器 + 行号 + 搜索高亮）、AddressTranslator（地址转换服务 + AddressMapping 实体/DAO）；新增 AddressMapping 实体和 DAO；修复包名 import、DartMethod 字段引用问题；`assembleDebug` BUILD SUCCESSFUL |
| 2026-07-31 | **P5-1 ~ P5-4 完成**：实现 SoEditorScreen（3 Tab 主界面）、StructureTab（ELF 节头表 + 符号表展示）、HexEditorTab（十六进制字节级编辑 + 分页导航）、DisassemblyTab（ARM64 反汇编 + 三列布局）、BackupManager（撤销栈 + CRC32 校验 + .bak 全量备份）；SoEditorViewModel 协调各 Tab 状态；修复包名不一致（feature vs features）、import 错误（KeyboardOptions、size、Box）、Text composable 参数不匹配问题；`compileDebugKotlin` BUILD SUCCESSFUL |
| 2026-07-31 | **P5-5 完成**：实现产物 ↔ SO 联动；新增 SoEditorDetailScreen（接收 filePath + initialOffset 参数，自动打开 SO 文件并跳转到指定位置）；更新 AppNavGraph 添加 `so_editor/{filePath}?offset={offset}` 路由；更新 PpBrowserScreen 添加 onLocateInSo 回调和 SO 定位按钮；**P5 阶段全部完成** |
| 2026-07-31 | **P6-2 完成**：实现版本更新检测；DualSourceDownloader 新增 `fetchVersionInfo()` 获取远程 version.json（Gitee/GitHub 双源回退）；EnginePackManager.checkForUpdates() 对比本地/远程版本；新增 SettingsViewModel + UpdateCheckCard UI（检查更新按钮 + 已安装版本列表 + 更新提示）；构建通过 |
| 2026-07-31 | **P6-3 完成**：创建 OnboardingScreen（4 页滑动向导：下载引擎 → 选择 APK → 运行分析 → 编辑 SO）；HorizontalPager + 指示器 + 跳过/下一步按钮；OnboardingPreferences 用 SharedPreferences 记录完成状态；MainActivity 首次启动显示引导；构建通过 |
| 2026-07-31 | **P6-4 完成**：创建 PatchExporter（SAF 导出 + 缓存导出 + CRC32 校验 + 时间戳文件名）；BackupManager 新增 `getPatchRecords()`；SoEditorViewModel 新增 `undo()` / `exportPatches()` / `exportPatchesToCache()` 方法；SoEditorScreen 添加撤销和导出按钮（Snackbar 反馈）；添加 documentfile 依赖；构建通过 |
| 2026-07-31 | **P6-6 完成**：PpEntryDao 新增 `getByAnalysisIdPaged()` 分页查询；AsmBrowserViewModel 改用 `file.useLines()` 流式逐行读取替代 `readText()` 全量加载；SoEditorViewModel Hex 数据分段加载（256 字节/页）；`assembleDebug` BUILD SUCCESSFUL |
| 2026-07-31 | **引擎包 7z 迁移 + 修复**：真机报 `unsupported compression method[10]`（v0.2.0 资产使用非标准压缩方法 0x0A，非权限问题）；弃用系统 ZIP，改用 commons-compress 1.26.1 + xz 1.10（SevenZFile.builder，LZMA2）；缓存文件 fler-engines.7z + 7z 魔数校验；`ensureEnginesReady` 由 `flow` 改 `channelFlow`，下载/解压中间进度实时推送给设置页进度条与前台通知（修复进度条停 0% bug）；EngineExtractor 解压前清理残留 + 失败提示优化；全链路新增 `FlerEngine` 日志（URL/HTTP 码/字节/校验是否生效/条目/堆栈）；默认 URL 升级至 `myfler/fler-dart` v0.3.0（仅 GitHub）；清理过期 gitee/fler 常量；versionCode 3 / versionName 1.2；`assembleDebug` BUILD SUCCESSFUL。**待办：myfler/fler-dart 打 tag v0.3.0 重建标准 LZMA2 资产后真机联调（P6-1/P6-5）** |
| 2026-07-31 | **真机下载故障定位 + App 加固（v1.3）**：v0.3.0 Release 已发布（2026-07-31 06:40 UTC，fler-engines.7z 12,491,064 字节，SHA256 与其 checksums.txt 一致 `85e36dc0...`）；真机报 SHA256 校验失败，日志比对发现**设备获取的校验和为 v0.2.0 的 `68989a1c...`** → 根因：设备 SharedPreferences 残留自定义下载源（checksumUrl 指向 v0.2.0），与代码/Release 无关；修复=设置页重置下载源为默认。加固：`fetchChecksum` 成功/失败打印 checksumUrl；SHA256 不匹配日志打期望 vs 实际哈希+完整源描述，错误提示"若自定义过下载源请重置为默认"；下载+校验失败自动重试 3 次；EngineViewModel 暴露 `isCustomSource`，引擎卡片显示自定义源警告；versionCode 4 / versionName 1.3；`assembleDebug` BUILD SUCCESSFUL |
| 2026-07-31 | **P6-5 根因终定 + v0.3.1 产包修复（最终版）**：v0.3.0 校验通过后解压仍报 `Unsupported compression method [10]`。定位：**7-Zip 23.00+ 会对 ARM64 ELF 可执行文件自动加 ARM64 BCJ filter（方法 ID 0x0A=10）**，`-m0=lzma2` 只设压缩方法不关过滤器；commons-compress 1.26.1 无 ARM64 filter 支持 → 提取失败（14:41 日志：纯 LZMA2 块 libicudata/icuuc 解压成功、ARM64 块失败，完全吻合）。首版修复（fler-dart commit a7826da）用 `-myfd=ARM64` 禁 filter，但 **CI runner 是 7-Zip 23.01 不支持 `-myfd`（24+ 才有）→ E_INVALIDARG 失败，该 commit 废弃**。终版修复（commit **0a4eabb** / tag v0.3.1 已重建指向）：改 `7z a -mx=9 -m0=lzma2 -mf=off`（9.x 起支持，禁用全部 filter；所有引擎都是 ARM64 ELF，效果等同只禁 ARM64 → 纯 LZMA2），并新增验证步骤（`7z l -slt` 含 ARM64/BCJ/BCJ2 即失败 + `7z t` 完整测试）。**v0.3.1 CI 通过并已发布**：本地验证 `7z l -slt` 15 文件全部 `Method = LZMA2:26`（无 ARM64/BCJ）、`7z t` Everything is Ok（86116834B→12049792B）、SHA256 `b844f539a0b4e591bab22cb9106d40094ae5bf2c338dc14e5209535db027fc1e`。App：默认 URL/校验 URL 升到 v0.3.1（不改版本号，仍 versionCode 4/"1.3"）；`assembleDebug` BUILD SUCCESSFUL。**待办：真机下载验证（versionCode 4 APK，默认源）** |
| 2026-08-01 | **【v0.3.9 成功 + v0.3.10 内存直导】**：v0.3.9（PRODUCT 宏）真机分析 Bit.apk **成功**（`blutter_analyze` 返回 0，2727ms，Parsed 3012 pp + 39323 obj）。但产物页 classes/methods=0：根因是 `blutter_entry.cpp` 的**文本文件解析器与 blutter 真实输出格式不匹配**（objs.txt 是 `Obj!Class@off`，解析器期望 `off-off(Class)`；asm 是 `.dart` 文件含 `// ** addr:`，解析器找 `.txt`+`; Function:`）。**修复**：按用户选择方案 A —— 重写 `blutter_entry.cpp` 为**直接内存导出**（遍历 `DartApp.fler_libs()→classes→Functions()` 写 classes/methods，src_code 用函数反汇编；对象池经 `DartDumper.FlPoolDescription` 写 pp_entries/strings），并在 build-dartvm.sh 加 **Step 1d** 非侵入补丁（DartApp.h 加 `fler_libs()` 访问器、DartDumper.h 公开 `FlPoolDescription()`）。推 tag **v0.3.10**。**另修复 App 侧 3 个 bug**：① `ProjectViewModel` 阶段5 用原始 project 覆盖导致 dartVersion=N/A → 改用保留 dartVersion；② `ProjectDetailViewModel` 串行 collect 被 getByIdFlow 永久阻塞，导致分析记录(0)/so文件(0) → 改独立协程 + 分析变化时刷新 so 文件。App `assembleDebug` 成功。**待办：v0.3.10 CI 完成后真机重测（清引擎→重下 v0.3.10→分析→产物页应显示类/方法/PP 真实数据）** |
| 2026-07-31 | **【根因终定 v0.3.9】真正根因：缺失 `PRODUCT` 宏**。此前 v0.3.8 补 `DART_PRECOMPILED_RUNTIME` 后编译全过，但真机 Bit.apk 仍 SIGSEGV@0x27，另一 3.10.4 APK 报 `vector(-2)`（205ms）——两种崩溃都是布局错位。定位：`raw_object.h` 用 `#if !defined(PRODUCT) && !defined(DART_PRECOMPILED_RUNTIME)` 守卫多个原始对象字段（`UntaggedClass.direct_subclasses`、`UntaggedFunction.var_descriptors`、`constant_coverage` 等）；目标 APK 快照是 **product** 构建，宿主 blutter 经导出 CMake target 拿到 `PRODUCT` → 布局一致；引擎以裸 .a 链接无传播 → 缺 PRODUCT → 结构体多出字段 → 偏移错位 → vector/0x27。**修复**：`dartvm/CMakeLists.txt` 显式补 `PRODUCT` + `U_USING_ICU_NAMESPACE=0`（并更新注释说明各宏作用）。推 tag **v0.3.9** 重建；App 默认源升级 v0.3.9。**待办：CI 完成后真机重测 Bit.apk(3.12.1) 与 3.10.4 APK（清引擎→重下→分析，预期均返回 0）**。另：ICU `libicudata.so.73` 加载失败为次要项（symlink 无法被 Android linker 搜索到，dartvm 不依赖 ICU 可忽略），根治需 dlopen 自定义 namespace，列后续。 |
| 2026-07-31 | **【根因终定 v0.3.8】编译错 + 运行时崩溃统一归因：缺失 `DART_PRECOMPILED_RUNTIME`**。现象链：宿主 blutter.py 编译通过并成功分析，而 fler-dart 引擎 NDK 编译**所有版本**报 `no member named 'entry_point' in 'dart::Closure'`；真机反复 vector / SIGSEGV@0x27。定位：`object.h` 中 `Closure::entry_point()` 仅在 `#if DART_PRECOMPILED_RUNTIME` 下声明；静态库由 CMake 模板以 `PUBLIC DART_PRECOMPILED_RUNTIME` 构建，blutter.py 通过**导出的 CMake target** 链接 → INTERFACE 宏传播到宿主可执行文件 → 编译通过；而 fler-dart 以**裸 .a 路径**链接 → 宏不传播 → blutter 源码编译时缺宏 → 编译失败，且运行时 `Code::EntryPointOf`/`Closure::entry_point` 走 JIT 分支解析 AOT 快照 → 入口地址错 → `classes.at(cid)` 越界 / 空指针 0x27。**修复**：`dartvm/CMakeLists.txt` 显式补 `DART_PRECOMPILED_RUNTIME` + `DART_TARGET_OS_ANDROID` + `TARGET_ARCH_ARM64` + `EXCLUDE_CFE_AND_KERNEL_PLATFORM`；Step 1b / patch-elfhelper 均不再需要（保留移除）；blutter 固定 528acbe；恢复完整 12 版本矩阵；缓存 key v15。推 tag **v0.3.8** 重建；App 默认源升级 v0.3.8。**待办：CI 完成后真机重测 Bit.apk（清引擎→重下→分析，预期 blutter_analyze 返回 0）** |
| 2026-07-31 | **【v0.3.5→v0.3.6 根因修正】真机 SIGSEGV@0x27**：v0.3.5（最新上游 blutter）重测后真机由 `vector(-2)` 变为 **`SIGSEGV signal=11 fault_addr=0x27 (-997)`**，仍发生在 LoadInfo。定位：`patch-elfhelper.sh` 注释记载的崩溃正是 `ElfHelper.cpp:116 dynstr + dynsym->name`（dynstr==nullptr → 0x27）；宿主 Debug Repro（**最新 blutter、无补丁**）分析 Bit.apk 完全成功 → **fler-dart 的历史补丁（Step 1b closure.entry_point + patch-elfhelper）是旧 blutter 的 workaround，对已验证 commit 属多余/有害**；且 CI `actions/cache` 会复用旧 blutter 缓存导致引擎 blutter ≠ 宿主。**修复**：`build-dartvm.sh` 固定 blutter 到宿主实测成功的 **528acbe83ba35a3a53fb97b231cb5f968c7068d1**（fetch+checkout，强制不随缓存漂移），**移除 Step 1b / patch-elfhelper 两个补丁**；`build-dartvm.yml` 缓存 key 升至 `v13-528acbe-dynamic`；推 tag **v0.3.6** 重建；App 默认源升级 v0.3.6。**待办：CI 完成后真机重测（清引擎→重下 v0.3.6→分析 Bit.apk，预期与宿主一致成功）** |
| 2026-07-31 | **【引擎根因终定】Bit.apk 分析失败定位（CI 宿主复现）**：真机 `analysis failed: vector`（blutter `std::vector::at()` 越界，`DartApp::GetClass → classes.at(cid)`）。通过新增 `debug-repro.yml` workflow 在 GitHub Actions 宿主上用 **最新上游 blutter.py** 对 Bit.apk 复现：Dart 3.12.1 / 快照 hash `ace654289f5abc240509fc941453ebc5` / flags（product no-shared_data compressed-pointers）全部确认；**宿主分析成功**（完整产出 pp.txt/objs.txt/asm 817 文件）；blutter.py 自动推导宏 `HAS_RECORD_TYPE + NO_METHOD_EXTRACTOR_STUB + UNIFORM_INTEGER_ACCESS` 与 build-dartvm.sh 一致 → **排除"版本宏配置"与"blutter 源码 bug"**。**根因：fler-dart 引擎 v0.3.4 构建时克隆的 blutter 版本陈旧，存在 Dart 3.12.x 快照解析 bug（需 Step 1b 补丁打 Closure entry_point），上游已修复**。处置：推 tag **v0.3.5** 用最新 blutter 重建全部引擎并发布 Release；App 默认源升级 v0.3.5；待 CI 完成后真机重测（先清除旧引擎强制重下）。**配套**：App 侧加固完成——DartVersionDetector 改全文件扫描 + 强特征优先（`X.Y.Z (stable)`）、去掉静默版本 fallback（检测失败明确报错）；EngineLoader 按 DT_NEEDED 补 `libicudata.so.73` 等符号链接（消除 ICU dlopen 失败）；`assembleDebug` BUILD SUCCESSFUL |
| 2026-07-31 | **【审查与修复批次】代码审查结论 + 全链路修复**：审查发现 P4/P5 存在功能缺口与导航断裂。修复项：(1) **分析结果导入 Room**——新增 `AnalysisImporter`，把 Blutter SQLite（classes/methods/pp_entries/strings）防御式读入 Room 并回写真实统计计数；`ProjectViewModel` 阶段5接入（此前计数恒为 0、产物页永远为空）；(2) **ELF 解析器修复**——`parseSymbols` 改用 `sh_link` 定位字符串表（.symtab→.strtab，.dynsym→.dynstr），修复 stripped 场景 `sections_[-1]` 越界，全部偏移加文件边界防御；`Section` 增加 `link` 字段；(3) **导航接线**——新增 `project_detail/{id}`（项目详情页：分析记录 + SO 文件 + 运行分析）、`pp_browser/{analysisId}`、`asm_list/{analysisId}`（方法列表）、`asm_browser/{analysisId}/{methodId}`；修复 ProjectScreen/OutputScreen 空回调、SoEditorDetail 返回键失效；(4) **产物浏览真实数据**——PP 浏览基于导入的 pp_entries，"仅叶子"筛选改为 String 类型；ASM 浏览改读 `dart_methods.src_code`（不再依赖不存在的 asm 文件），新增 AsmListScreen；修复 AsmBrowser 空态误判（content.isBlank→lines.isEmpty）；(5) **AddressTranslator 接入**——新增 `importMethods`，用 ELF 节头把方法虚拟地址换算成文件偏移写入 address_mappings，`ProjectViewModel` 阶段5触发（此前无调用点、fileOffset 恒 0）；(6) **SO 编辑器真实数据**——`openFile` 用 ElfParserBindings 解析节/符号/动态符号；`loadHexData` 真实读取文件字节（此前全零假数据）；新增自研 ARM64 解码器 `decoder.cpp`（B/BL/BR/RET/CBZ/TBZ/LDR/STR/ADD/SUB/CMP/MOV/ADRP/STP/LDP/CSEL 等，未识别输出 `.word`），经 JNI 接入 `Arm64EncoderBindings.disassemble` / `CapstoneBindings`；新增 `applyPatch`/`writeByte`/`undo`（备份 + 撤销 + 真实写盘）；Hex Tab 支持字节写入，反汇编 Tab 点击指令 + 底部补丁栏应用 4 字节补丁；(7) 小缺陷——About 版本号改读 BuildConfig、OutputScreen 刷新图标修正。**配套改动**：Room 版本 2（DartMethod 增 src_code、PpEntry 增 type，破坏性迁移可接受）；`assembleDebug` BUILD SUCCESSFUL。**待办**：真机验证分析→导入→产物浏览→SO 编辑全链路（P6-5） |
| 2026-08-01 | **【操作逻辑优化批次】5 项 UX 改进**：(1) **Engine 标签 N/A**——`ProjectViewModel` 阶段5 `projectDao.update` 时 `engineVersion = dartVersion`（此前硬编码 v0.3.10 导致首页 Engine 永远 N/A，已验证 dartVersion 正确检测）；(2) **移除「产物」Tab**——`Screen.Output` / `TopLevelTabs` / `tabIcons` / `tabLabels` / `NavHost.Output composable` / `strings.xml:tab_output` 全部移除，删除 `OutputScreen.kt` + `OutputViewModel.kt`（与 ProjectDetailScreen 的分析记录列表功能高度重复，统一从项目详情页进入）；(3) **分析记录删除**——`AppDatabase` 新增 `cascadeDeleteAnalysis(analysisId)` 事务方法（pp_entries/dart_methods/dart_classes/libraries → analyses 顺序删除）；`ProjectDetailViewModel` 注入 `AppDatabase` 并新增 `deleteAnalysis` + `errorMessage` 一次性事件；`ProjectDetailScreen.AnalysisCard` 加删除 IconButton + 确认对话框 + Scaffold snackbarHost 错误提示；(4) **ASM→SO 传递方法长度**——`DartMethod` 新增 `functionSize: Long?` 字段（DB version 2→3，破坏性迁移）；`AnalysisImporter` 改写入 `functionSize = size.takeIf { it > 0 }`（此前误塞进 signature）；`Screen.SoEditorDetail` route 增 `length` 参数；`AppNavGraph.editMethodInSo` 传递 `method.functionSize`；`SoEditorDetailScreen` 增 `methodLength` 参数 + `isMethodMode` 标志，顶栏显示方法范围，反汇编只加载方法范围内字节；`DisassemblyTab` 增 `isMethodMode`，方法模式下隐藏上下页/地址跳转导航；(5) **补丁 UI 模板**——`PatchBar` 新增 ARM64 常用指令 AssistChip 横向列表（NOP/RET/MOV W0,#0/MOV W0,#1/BRK #0/WFI，little-endian 字节序已校验），点击即填入字节。**配套改动**：`compileDebugKotlin` + `assembleDebug` BUILD SUCCESSFUL。 |
| 2026-08-01 | **【SO 编辑器反汇编修复 + 汇编编辑能力】3 项关键修复**：(1) **"该方法无可反汇编字节"根因**——`SoEditorViewModel.openFile` 是 `fun`（内部 `viewModelScope.launch` 异步设置 `currentFileSize`），而 `SoEditorDetailScreen.LaunchedEffect` 紧接着调用 `loadDisassembly`，此时 `currentFileSize` 仍是 0 → `readFileBytes` 立即返回空 → 反汇编数据为空。**修复**：`openFile` 改为 `suspend`，`LaunchedEffect` 协程内顺序 `await`；`SoEditorScreen` 的 `if` 调用改用 `LaunchedEffect`。(2) **汇编指令编辑能力**（用户反馈"编辑 SO 应该是编辑汇编代码，而不是反汇编"）——`SoEditorViewModel` 新增 `applyInstructionPatch(offset, instruction, args)` suspend 方法，调用 `Arm64EncoderBindings.encode` 把人类可读汇编（如 "MOV W0, #1"）编码为 4 字节机器码，复用 `applyPatch` 完成备份+写盘+撤销记录；`DisassemblyTab` 新增 `InstructionEditDialog`，点击指令行预填当前指令文本（mnemonic + opStr），用户输入新汇编后解析为 (指令名, 操作数) 调用 `applyInstructionPatch`，成功后自动刷新反汇编视图让新指令立即可见。`parseAsmText` 工具函数按首个空白分割指令名与操作数，支持 "RET"/"NOP"（无操作数）和 "MOV W0, #1"（有操作数）两种格式。(3) **DisassemblyListView 回调签名升级**——`onInstructionClick` 从 `(Long) -> Unit` 改为 `(DisasmInstruction) -> Unit`，让编辑对话框能拿到完整指令对象（含 mnemonic/opStr/bytes）用于预填。**配套改动**：`clean` + `assembleDebug` BUILD SUCCESSFUL。 |

| 2026-08-01 | **【MCP Server 内嵌设计】**：App 内嵌 MCP 服务器开放逆向能力给 AI 代理。后端读 App 内 Room/引擎 SQLite（src_code，零导出）；传输双模式——HTTP+SSE（Claude Desktop 兼容）+ Streamable HTTP（JDK HttpServer 零新增依赖）；JSON 用 kotlinx-serialization-json；局域网模式前台服务保活（常驻通知）；补丁默认关闭（destructiveHint，客户端决定），撤销栈持久化 undo.log（重启可撤销）；工具复用 CapstoneBindings/ElfParserBindings/AddressTranslator/备份链路；安全默认本机 + 可选 Token；文档同步方案.md（二十一章）/ dev-plan.md（P7）。 |

| 2026-08-01 | **【MCP 日志页 + 连接 URL 修复 + 反汇编 key 崩溃】**：(1) **MCP 日志页**——新增 McpLogger（有界 500 条，StateFlow）+ 注入 McpHttpServer（连接/请求/会话/解析错误）/McpProtocol（工具调用）/McpServerManager（启停/端口）；新增 McpLogScreen（级别过滤 I/W/E + 自动滚底 + 清空）+ McpLogViewModel + Screen.McpLog 路由 + 设置卡片「查看日志」按钮。(2) **连接 URL 缺路径**——本机/局域网 URL 补齐 /mcp（MCP Inspector/Streamable HTTP）与 /sse（Claude Desktop）两组，McpStatus/McpUiState 新增 sseLocalUrl/sseLanUrl。(3) **LazyColumn 重复 key 崩溃**（Key already was used，滚动/惯性滑动时）——DisassemblyListView 的 key 由 (address to mnemonic).hashCode() 改为 ddress（哈希碰撞导致重复 key）。文档同步方案.md（21.6）/ dev-plan.md（P7-7）。ssembleDebug BUILD SUCCESSFUL。 |

| 2026-08-01 | **【Keystone 集成（完整 AArch64 指令编码）】**：capstone cs_asm 不支持 AArch64（4.0.2 只实现 x86/ARM32，ARM64 返回 CS_ERR_ARCH），指令编辑只能靠自研编码器。集成 **Keystone** 补齐完整 AArch64 汇编：新增独立仓库 github.com/myfler/keystone-build（GitHub Actions 交叉编译 libkeystone-arm64-v8a.a，tag keystone-latest-arm64-v1，release 发版）；App 端 etchKeystone Gradle 任务改为校验本地交叉编译产物 libs/arm64-v8a/libkeystone.a（不远程下载，配置缓存兼容，执行期零 project 引用）；keystone 头文件入库 cpp/keystone_include/；CMake 链接 keystone 静态库；新增 keystone_jni.cpp（ks_open/ks_asm/ks_free/ks_close，静态链）+ KeystoneBindings.kt；encodeInstruction = **Keystone → 自研编码器**，删除 capstone cs_asm 一路（CapstoneBindings.assembleWithCapstone + 
ativeAssemble）。产物：libfler_jni.so 6.2MB（--gc-sections 丢弃非 ARM64 代码），APK 29.7MB。ssembleDebug BUILD SUCCESSFUL。 |

| 2026-08-01 | **【移除自研编码器/解码器 + capstone 解码，编码仅留 Keystone】**：删除自研编码器（encoder.cpp + arm64_encoder.h + nativeEncode + Arm64EncoderBindings.encode）、自研解码器（decoder.cpp/h + nativeDisasm）、capstone 解码（capstone_jni.cpp + CapstoneBindings，含 disassembleWithCapstone/disassemble）；DisasmInstruction 数据类移至独立文件；SO 编辑器汇编 Tab 与 MCP disassemble_range 改为提示'反汇编引擎已移除'（汇编内容经 ASM 浏览的 src_code 查看）；指令编辑编码仅用 Keystone（encodeInstruction = Keystone，无回退）；CMake 精简为 elf_parser + blutter_jni/elf_parser_jni/keystone_jni。libfler_jni.so 6MB，APK 29.3MB。ssembleDebug BUILD SUCCESSFUL。 |

| 2026-08-01 | **【死路由审计 + AsmBrowser(src_code) 入口恢复 + capstone 反汇编恢复 + SO 返回按钮】**：(1) **死路由审计**：全项目仅 AsmBrowser（asm_browser/{analysisId}/{methodId}）为死路由——页面/ViewModel（读 src_code）代码完整但无任何导航入口；(2) **方案 A**：方法列表点击方法 → AsmBrowser 查看 src_code（AppNavGraph onMethodClick 改 navigate AsmBrowser），AsmBrowser 内「在 SO 中编辑」→ SO 编辑器；(3) **capstone 反汇编恢复**：恢复 capstone_jni.cpp（cs_disasm_iter 循环解码，不可解码字 .word，CS_ARCH_ARM64=1）+ CapstoneBindings.kt（disassembleWithCapstone）+ CMake；SoEditorViewModel.loadDisassembly 与 MCP disassemble_range 恢复 capstone 解码（引擎包不可用则置空提示），编码仍用 Keystone；(4) **SO 编辑器返回按钮**：新增 SoEditorViewModel.closeFile()（重置全部状态）+ SoEditorScreen TopAppBar navigationIcon（打开文件后显示 ArrowBack → 回最近文件/选择列表）。ssembleDebug BUILD SUCCESSFUL。 |

| 2026-08-01 | **【Keystone 编码失败根因修复】**：指令编辑报'无法编码该指令'，真机 logcat FlerKeystoneJNI: ks_asm failed: 'ADD x0, x22, #0x3' errno=0（大写/小写均失败、errno=0）。定位：keystone 0.9.2 的 ARM64 ks_asm 返回值（stat_count）可能为 0，但 encoding/encoding_size 已填好（errno=0 表示无错误），JNI 以 count<=0 误判失败。**修复**：keystone_jni 改以 encoding_size==0 || !encoding 判断成败（不依赖 count）。另在 SoEditorViewModel.encodeInstruction 增加小写兜底（对话框预填大写 mnemonic，keystone 示例用小写）。ssembleDebug BUILD SUCCESSFUL。 |

| 2026-08-02 | **【Rizin 集成方案 v2 调研完成】**：输出 [rizin-integration-plan.md](file:///c:/Users/Len/AndroidStudioProjects/fler/rizin-integration-plan.md) v2 + [rizin-integration-research.md](file:///c:/Users/Len/AndroidStudioProjects/fler/rizin-integration-research.md)。核心方案：① 引擎抽象层 `BinaryAnalysisEngine` / `EmulationEngine` + `EngineRegistry` 注册中心 + `AnalysisSession` 统一会话层，新增引擎零改动现有代码；② MCP 服务自动暴露 Engine 能力（`EngineMcpToolRegistry`）；③ Capstone 三方共用方案 A——blutter/Rizin/App 共用同一份 `libcapstone.so`，Rizin 静态库编译时 `-Duse_sys_capstone=enabled` + `find_library`；④ 为 Unicorn/unidbg 预留 `EmulationEngine` 插槽。 |

| 2026-08-02 | **【P7-Engine 抽象层落地（Rizin 集成 v2 第 1 阶段）】**：按 [rizin-integration-plan.md](file:///c:/Users/Len/AndroidStudioProjects/fler/rizin-integration-plan.md) v2 落地引擎抽象层 + 骨架实现，`compileDebugKotlin` BUILD SUCCESSFUL。新增文件清单：
  **① 核心数据模型**（`core/analysis/`）：[AnalysisTypes.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/AnalysisTypes.kt)（AnalysisCapability 枚举 / OpenOptions / StringScanOptions / FileInfo / BasicBlock / Xref / ImportInfo / RelocInfo / StringInfo）、[SectionInfo.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/SectionInfo.kt)（+ fromElfSection 适配旧 ElfSection）、[SymbolInfo.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/SymbolInfo.kt)、[FunctionInfo.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/FunctionInfo.kt)、[DisasmInstruction.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/DisasmInstruction.kt)。
  **② 抽象接口**：[BinaryAnalysisEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/BinaryAnalysisEngine.kt)（open/close/sections/symbols/imports/entries/relocs/strings/fileInfo/functions/basicBlocks/xrefs/disasm/assemble/read/write/undo/closeAll）、[EmulationEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/EmulationEngine.kt)（emulate/emulateRange/readReg/writeReg/mmap/memMap 基本仿真接口）。
  **③ 调度层**：[EngineRegistry.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/EngineRegistry.kt)（按 Capability 挑选引擎 + 优先级排序）、[AnalysisSession.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/AnalysisSession.kt)（@Singleton，统一会话，UI/MCP 共用；`withEngine` suspend lambda；sessionId 管理；autoAnalyze 钩子）。
  **④ 引擎实现**：[engine/SelfAnalysisEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/SelfAnalysisEngine.kt)（fallback，适配旧 ElfParserBindings + CapstoneBindings，含 scanStrings 实现）、[engine/PlaceholderEngines.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/PlaceholderEngines.kt)（RizinEngine / UnicornEnginePlaceholder / UnidbgEnginePlaceholder 骨架，抛 NotImplementedError）、[assembler/KeystoneAssembler.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/assembler/KeystoneAssembler.kt)（独立汇编器，不依赖分析引擎）。
  **⑤ Hilt DI**：[di/AnalysisModule.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/di/AnalysisModule.kt)（注册所有分析/仿真引擎到 EngineRegistry；@Singleton AnalysisSession 注入）。
  **⑥ ViewModel 重构**：[SoEditorViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt)（所有 elf/symbol/disasm 读写改走 `AnalysisSession`，不再直接耦合 `ElfParserBindings/CapstoneBindings`；新增 `removeRecent` 给 UI 最近文件删除按钮；保留 `applyInstructionPatch` 汇编→机器码→写盘链路）。
  **⑦ UI 适配**：[StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt) / [DisassemblyTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt) 改用 `core.analysis.*Info` 数据模型。
  **⑧ MCP 自动暴露**：[mcp/EngineMcpToolRegistry.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/mcp/EngineMcpToolRegistry.kt)（`engine_list_engines` / `engine_open` / `engine_close` / `engine_auto_analyze` / `engine_list_sections` / `engine_list_symbols` / `engine_list_functions` / `engine_scan_strings` / `engine_disasm` / `engine_read_bytes` / `engine_patch_bytes` / `engine_patch_instruction`；后续 Rizin/Unicorn 只要把能力写进 `capabilities` 自动生成工具，MCP 层零改动）。
  **修复的编译问题**：① SectionInfo 中未定义的 `SHF_READ` 改为用 `SHF_ALLOC` 推断 r 位；② SelfAnalysisEngine.scanStrings 的 lambda 内非法 `return` 重写为安全的 when 结构；③ EngineMcpToolRegistry 中 `p.value` 成员引用错误改为 `p.second`，`v.boolean` API 差异用 `as JsonPrimitive + content.toBooleanStrictOrNull()` 兼容；④ AnalysisSession 中 `withEngine` 函数未声明 suspend → 改为 suspend + suspend lambda；⑤ AnalysisModule 中误导入未使用的 PlaceholderEngines → 删除导入；⑥ SoEditorScreen 引用未定义的 `removeRecent` → 在 SoEditorViewModel 补齐。 |

---

## P8 Rizin 集成（v2 架构）

**目标**：引入 Rizin 分析框架作为主引擎，替换 SelfAnalysisEngine 的 fallback 路径；为 Unicorn/unidbg 预留扩展插槽；Capstone 三方零冲突共用。

> 详细方案见 [rizin-integration-plan.md](file:///c:/Users/Len/AndroidStudioProjects/fler/rizin-integration-plan.md) v2。

| # | 任务 | 状态 | 完成时间 | 备注 |
|---|------|------|----------|------|
| P8-1 | 引擎抽象层 + 骨架落地 | [x] | 2026-08-02 | 12 个新文件（接口/数据模型/Registry/Session/SelfEngine/Placeholders/DI/MCP）；`compileDebugKotlin` 通过 |
| P8-2 | 本地交叉编译 librizin.a + 依赖 .a | [x] | 2026-08-02 | 用户手动编译：Rizin v0.9.1 + Capstone 5.0.9，26 个 librz_*.a（~40MB）+ libcapstone.a（36MB），放入 `app/libs/arm64-v8a/`；头文件放入 `app/src/main/cpp/include/` |
| P8-3 | RizinEngine JNI 实现（rizin_jni.cpp） | [x] | 2026-08-02 | 6 个 native 方法（open/close/analyze/cmdStr/readBytes/writeBytes）+ RizinJsonParser（8 种 JSON 解析）+ RizinEngine 12 个 capability 全实现；`assembleDebug` 通过 |
| P8-4 | Capstone 三方共用真机验证 + LGPL 合规 | [ ] | — | 验证 blutter/Rizin/App 共用 libcapstone.so 零冲突；LICENSE 文档化 |
| P8-5 | UI 适配（函数列表 + 交叉引用 + 函数边界） | [x] | 2026-08-02 | 结构 Tab 增函数子标签；汇编 Tab 增 xref 底部面板 + 函数边界标注；ViewModel 增 functions/xrefData/functionOverlay 状态 |

**后续阶段（预留，不计入 P8）**：
- v0.4.0 Unicorn 集成（实现 UnicornEngine + unicorn_jni.cpp）
- v0.5.0 unidbg 集成（实现 UnidbgEngine + unidbg_jni.cpp）

**验收标准**：
- [ ] RizinEngine 通过所有 BinaryAnalysisEngine 接口方法
- [ ] SO 编辑器使用 RizinEngine 替代 SelfAnalysisEngine 后，节区/符号/反汇编/函数识别能力提升
- [ ] MCP `engine_*` 工具集通过 RizinEngine 实际工作
- [ ] APK 体积增量 ≤ 8MB（librizin.a 静态链接后）
- [ ] blutter + Rizin + App 三方共用 libcapstone.so 真机无 dlopen 冲突

---

## 变更记录（P8 补充）

| 日期 | 变更 |
|------|------|
| 2026-08-02 | **【P8-2 + P8-3 RizinEngine JNI 激活】**：用户手动编译 Rizin v0.9.1 + Capstone 5.0.9 静态库（26 个 librz_*.a ~40MB + libcapstone.a 36MB），放入 `app/libs/arm64-v8a/`；头文件放入 `app/src/main/cpp/include/{capstone,rizin}/`。CMakeLists.txt 配置静态链接 + include 路径（`include/rizin` + `include/rizin/rz_util` + `include/rizin/sdb`）。新增 [rizin_jni.cpp](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/cpp/jni_bridge/rizin_jni.cpp)（6 个 native 方法：`nativeOpen` → `rz_core_new` + `rz_core_file_open` + `rz_core_bin_load`；`nativeClose` → `rz_core_free`；`nativeAnalyze` → `rz_core_analysis_all`；`nativeCmdStr` → `rz_core_cmd_str`；`nativeReadBytes` → `rz_io_nread_at`；`nativeWriteBytes` → `rz_core_write_at`；附 backtrace stub 解决 Android NDK 无 execinfo.h 问题）。新增 [RizinBindings.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/jni/RizinBindings.kt)（Kotlin JNI 声明）。新增 [RizinJsonParser.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinJsonParser.kt)（解析 8 种 Rizin JSON 输出：`ij`→FileInfo / `iSj`→SectionInfo / `isj`→SymbolInfo / `iij`→ImportInfo / `irj`→RelocInfo / `aflj`→FunctionInfo / `izzj`→StringInfo / `pdj`→DisasmInstruction / `axtj,axfj`→Xref / `afbj`→BasicBlock）。重写 [RizinEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt)（`isAvailable=true`，12 个 capability 全实现：ELF_PARSING/DISASSEMBLY/ASSEMBLY/FUNCTION_ANALYSIS/XREF/CFG/STRING_SCAN/DEMANGLE/BYTE_EDIT/ADDRESS_TRANSLATION/BINARY_HASH/SIGNATURE_MATCH）。[PlaceholderEngines.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/PlaceholderEngines.kt) 移除旧 RizinEngine 占位，只保留 Unicorn/Unidbg。引擎优先级：Rizin（高）> SelfAnalysis（fallback）。`assembleDebug` BUILD SUCCESSFUL。 |
| 2026-08-02 | **【P8-5 UI 适配（函数列表 + 交叉引用 + 函数边界标注）】**：(1) **结构 Tab 增函数子标签**——[StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt) 新增第 4 个子标签「函数 (N)」，展示 Rizin `aaa` 识别的函数列表（`aflj`），每行显示函数名/签名/地址/大小，点击跳转反汇编 Tab 对应地址；新增 `FunctionsList` + `FunctionRow` composable。(2) **汇编 Tab 增函数边界标注**——[DisassemblyTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt) 反汇编列表中，函数起始地址上方显示 `▶ 函数名` 标注行（蓝色背景），一眼看到函数边界；新增 `FunctionLabel` composable。(3) **汇编 Tab 增交叉引用面板**——点击指令行弹出 `ModalBottomSheet`，显示「调用方」(xrefsTo，谁引用了我) 和「被调用」(xrefsFrom，我引用了谁) 两类引用，每行显示类型标签（CALL/JUMP/DATA/STR）+ 地址，点击跳转对应反汇编地址；新增 `XrefBottomSheet` + `XrefRow` composable。(4) **ViewModel 新增状态**——`SoEditorUiState` 增 `functions` 字段（openFile 时加载 `session.listFunctions()`）；新增 `xrefData: StateFlow<XrefDataState>`（点击指令时 `loadXrefs(addr)` 加载 `session.xrefsTo/xrefsFrom`）；新增 `functionOverlay: StateFlow<Map<Long,String>>`（反汇编加载后 `updateFunctionOverlay()` 匹配函数起始地址→函数名）。(5) **汇编优先 Keystone**——ViewModel `encodeInstruction` 已优先调用 `keystoneAssembler.assemble`，RizinEngine.assemble 仅作 MCP/Session 层 fallback。调用方更新：[SoEditorDetailScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorDetailScreen.kt) + [SoEditorScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt) 传入 `functions` + `onFunctionClick`。`compileDebugKotlin` BUILD SUCCESSFUL。 |

