---
kind: build_system
name: Gradle 多模块构建与 CMake/Native 集成体系
category: build_system
scope:
    - '**'
source_files:
    - build.gradle.kts
    - settings.gradle.kts
    - gradle/libs.versions.toml
    - app/build.gradle.kts
    - app/src/main/cpp/CMakeLists.txt
    - gradle.properties
---

## 构建系统与工具链

Fler 项目采用 **Gradle Kotlin DSL + Android Gradle Plugin (AGP) 9.3.1** 作为核心构建系统，配合 **CMake 3.22.1** 管理 JNI 原生代码编译。整体构建流程由 Gradle 统一编排，Native 层通过 CMake 静态链接 Capstone、Keystone、Rizin 等第三方库。

### 版本与依赖管理
- 使用 **Version Catalog (`gradle/libs.versions.toml`)** 集中管理所有依赖版本，包括 AGP、Kotlin 2.0.21、Compose BOM、Hilt 2.60.1、Room 2.7.1、OkHttp 4.12.0 等
- 插件通过 `alias(libs.plugins.*)` 引用，避免硬编码版本号
- 仓库源优先使用阿里云镜像（`maven.aliyun.com`），同时保留 Google 和 Maven Central
- 启用 **Gradle Configuration Cache** 提升构建性能

### 模块结构
- 单模块工程：仅包含 `:app` 一个子模块，根 `settings.gradle.kts` 中显式 include
- 当前未拆分为多模块，但已预留多模块扩展能力

## Native 构建架构

### CMake 配置 (`app/src/main/cpp/CMakeLists.txt`)
- 定义两个目标：
  - `fler_native`（STATIC）：自研 ELF 解析器
  - `fler_jni`（SHARED）：JNI 桥接库，导出给 Kotlin 调用
- 静态链接策略：
  - Keystone：通过 `IMPORTED` 目标链接 `libs/arm64-v8a/libkeystone.a`
  - Capstone：静态链接 `libcapstone.a`，消除运行时 SO 依赖
  - Rizin：动态发现 `librz_*.a` 并链接
- ABI 限制：仅支持 `arm64-v8a`（`ndk.abiFilters`）
- 编译器选项：C++20、`-fvisibility=hidden`、`-fPIC`、Debug/Release 优化级别分离

### 预构建产物管理
- 第三方静态库以 `.a` 文件形式直接提交到 `app/libs/arm64-v8a/` 目录
- 通过 Gradle 任务 `fetchKeystone` 在 `preBuild` 阶段校验本地产物存在性
- 构建失败时给出明确提示：需先执行 `scripts/build-keystone.sh` 交叉编译

## 构建脚本与工具

### Gradle 配置
- 根 `build.gradle.kts` 仅声明插件引用（`apply false`），实际配置集中在 `app/build.gradle.kts`
- `gradle.properties` 配置 JVM 参数（`-Xmx2048m`）、并行构建开关（注释掉）、Configuration Cache
- 使用 `org.gradle.toolchains.foojay-resolver-convention` 自动管理 JDK 工具链

### NDK 与 CMake 集成
- `externalNativeBuild.cmake.path` 指向 `src/main/cpp/CMakeLists.txt`
- `cppFlags` 设置全局 C++ 标志：`-std=c++20 -fvisibility=hidden`
- `android_stl` 使用 `c++_shared`（NDK 共享 STL）

### 构建命令
- 开发调试：`./gradlew assembleDebug`
- 发布构建：`./gradlew assembleRelease`（release 优化默认关闭）
- 测试：`./gradlew test` / `./gradlew connectedAndroidTest`

## 约定与约束

1. **依赖版本统一管理**：所有第三方库版本必须在 `libs.versions.toml` 中声明，禁止在模块 build 文件中硬编码
2. **ABI 限制**：仅支持 arm64-v8a，其他架构需额外配置 ndk.abiFilters
3. **Native 库本地化**：第三方静态库必须预先编译并放置在 `app/libs/arm64-v8a/`，构建前需验证存在性
4. **可见性控制**：所有 C++ 符号默认隐藏（`-fvisibility=hidden`），JNI 导出符号需显式设置 `default` 可见性
5. **C++ 标准**：强制使用 C++20，不支持旧版本编译器
6. **构建缓存**：启用 Configuration Cache，避免修改构建脚本导致全量重建
7. **仓库源优先级**：阿里云镜像优先，Google/Maven Central 作为后备

## 关键文件
- `build.gradle.kts` — 顶层插件声明
- `settings.gradle.kts` — 模块注册与仓库源配置
- `gradle/libs.versions.toml` — 依赖版本中心化管理
- `app/build.gradle.kts` — 应用模块构建配置、依赖声明、CMake 集成
- `app/src/main/cpp/CMakeLists.txt` — Native 代码编译规则与链接配置
- `gradle.properties` — Gradle 全局构建参数
- `scripts/build-keystone.sh` — Keystone 静态库交叉编译脚本（外部依赖）