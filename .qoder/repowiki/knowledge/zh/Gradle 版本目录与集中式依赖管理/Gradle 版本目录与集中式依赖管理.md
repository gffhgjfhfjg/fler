---
kind: dependency_management
name: Gradle 版本目录与集中式依赖管理
category: dependency_management
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - build.gradle.kts
    - app/build.gradle.kts
    - gradle.properties
---

本项目采用 Gradle Kotlin DSL + Version Catalog（libs.versions.toml）进行统一的第三方库与插件版本管理，结合阿里云镜像加速仓库拉取，并通过 CMake/JNI 本地静态库方式集成 Capstone、Keystone、Rizin 等二进制分析引擎。

**1. 使用的系统与工具**
- 构建系统：Gradle 9.x（通过 `gradle-wrapper` 管理），Kotlin DSL (`build.gradle.kts`)。
- 版本目录：`gradle/libs.versions.toml` 集中声明所有 library 与 plugin 的版本号，模块内通过 `alias(libs.xxx)` 引用，避免硬编码版本号。
- 仓库源：`settings.gradle.kts` 中配置阿里云 Maven 镜像（google/public/gradle-plugin）+ Google + MavenCentral，并启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止子模块自行添加仓库。
- JVM 工具链：通过 `org.gradle.toolchains.foojay-resolver-convention` 插件自动解析 JDK 版本。
- NDK/CMake：Android 模块使用 `externalNativeBuild` 调用 CMake，仅编译 arm64-v8a ABI，STL 指定为 `c++_shared`。

**2. 核心文件与位置**
- `gradle/libs.versions.toml`：统一版本定义（AGP 9.3.1、Kotlin 2.0.21、Compose BOM 2024.09.00、Hilt 2.60.1、Room 2.7.1、OkHttp 4.12.0 等）。
- `settings.gradle.kts`：仓库源、插件管理与依赖解析策略。
- `build.gradle.kts`（根）：声明全局插件并 apply false，由子模块按需引入。
- `app/build.gradle.kts`：应用模块依赖声明，全部通过 `libs.*` 引用，无直接版本号。
- `gradle.properties`：JVM 参数、配置缓存开启等全局 Gradle 行为。

**3. 架构与约定**
- **单一版本源**：所有依赖版本集中在 `libs.versions.toml` 的 `[versions]` 段，`[libraries]` 段以 `{ group, name, version.ref }` 形式引用，`[plugins]` 段同理。升级只需改一处。
- **BOM 管理 Compose**：通过 `platform(libs.androidx.compose.bom)` 统一管理 Compose 组件版本，避免冲突。
- **KSP 替代注解处理器**：Hilt 与 Room 编译器均通过 KSP（`ksp(libs.hilt.compiler)` / `ksp(libs.room.compiler)`）注册，与 Kotlin 2.0 兼容。
- **JNI 静态库本地化**：Capstone、Keystone、Rizin 等 C/C++ 库以预编译 `.a` 静态库形式放入 `app/libs/arm64-v8a/`，CMakeLists.txt 直接链接，不通过远程包管理器获取。
- **动态引擎加载**：`fler-engines/` 目录存放多个版本的 `dartvm_xxx.so` 及共享库（libc++_shared.so、libcapstone.so、libicu*.so），运行时由 `EngineLoader` 动态选择加载。

**4. 约束与规范**
- 子模块禁止自定义仓库：`dependencyResolutionManagement.repositoriesMode.set(FAIL_ON_PROJECT_REPOS)` 强制所有仓库来源集中在根级 `settings.gradle.kts`。
- 插件版本统一：根 `build.gradle.kts` 中所有插件均 `apply false`，子模块通过 `alias(libs.plugins.*)` 引入，确保版本一致。
- 构建缓存启用：`gradle.properties` 中 `org.gradle.configuration-cache=true` 开启配置缓存以提升增量构建性能。
- 本地静态库校验：Keystone 在 `preBuild` 阶段检查 `libs/arm64-v8a/libkeystone.a` 是否存在且非空，缺失则抛出明确错误提示。
- ABI 限制：NDK 仅允许 `arm64-v8a`，减少产物体积与交叉编译复杂度。