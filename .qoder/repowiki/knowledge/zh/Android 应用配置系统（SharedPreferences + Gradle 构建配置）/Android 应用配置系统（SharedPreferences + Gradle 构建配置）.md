---
kind: configuration_system
name: Android 应用配置系统（SharedPreferences + Gradle 构建配置）
category: configuration_system
scope:
    - '**'
source_files:
    - gradle/libs.versions.toml
    - settings.gradle.kts
    - gradle.properties
    - app/build.gradle.kts
    - app/src/main/java/com/ai/fler/core/mcp/McpConfig.kt
    - app/src/main/java/com/ai/fler/core/service/EngineSourceConfig.kt
    - app/src/main/java/com/ai/fler/core/service/EnginePackManager.kt
    - app/src/main/java/com/ai/fler/features/onboarding/OnboardingScreen.kt
---

Fler Android 项目的配置系统由两层构成：构建期配置与运行期用户配置，二者职责清晰、互不干扰。

## 一、构建期配置（Gradle + TOML）
- **版本集中管理**：`gradle/libs.versions.toml` 统一声明 AGP、Kotlin、Compose、Hilt、Room、OkHttp 等所有依赖的版本号，子模块通过 `alias(libs.plugins.*)` 引用，避免硬编码。
- **仓库源镜像**：`settings.gradle.kts` 中通过 `pluginManagement.repositories` 和 `dependencyResolutionManagement.repositories` 配置阿里云镜像优先，并启用 `RepositoriesMode.FAIL_ON_PROJECT_REPOS` 禁止子模块自行添加仓库，保证依赖来源可控。
- **JVM/Gradle 参数**：`gradle.properties` 设置 `org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8` 与 `org.gradle.configuration-cache=true`，开启配置缓存提升构建性能。
- **应用构建参数**：`app/build.gradle.kts` 中定义 `applicationId`、`minSdk=26`、`targetSdk=36`、`versionCode=4`、`versionName="1.3"`，并通过 `buildFeatures.buildConfig = true` 生成 `BuildConfig`，供 AboutScreen 等界面读取版本号。
- **NDK/CMake 配置**：`ndk.abiFilters += ["arm64-v8a"]`，CMake 使用 `-std=c++20 -fvisibility=hidden`，STL 为 `c++_shared`。
- **Keystone 静态库校验**：`preBuild` 任务依赖自定义 `fetchKeystone` 任务，强制要求本地存在 `libs/arm64-v8a/libkeystone.a`，否则抛出 `GradleException`。

## 二、运行期用户配置（SharedPreferences + StateFlow）
项目采用 **SharedPreferences + Hilt 单例 + StateFlow** 的模式管理用户可配置的运行时选项，每个配置域独立一个类，命名规范统一：

| 配置类 | SharedPreferences 名称 | 用途 |
|---|---|---|
| `McpConfig` | `mcp_server` | MCP Server 开关、绑定模式（LOCAL/LAN）、端口（默认 8765）、Bearer Token、补丁工具开关 |
| `EngineSourceConfig` | `engine_source` | 引擎包主/备下载地址、SHA256 校验地址、版本信息 JSON 地址、GitHub 加速前缀（默认 `https://gh-proxy.com`） |
| `OnboardingPreferences` | `onboarding` | 引导流程完成标记 |
| `EnginePackManager` | `engine_pack` | 已安装引擎包版本记录 |

每个配置类的共同特征：
- 使用 `@Singleton` + Hilt `@Inject` 注入，构造时传入 `@ApplicationContext Context`。
- 字段以 `MutableStateFlow` 暴露，读写时同步持久化到 SharedPreferences，UI 侧可直接订阅 `StateFlow` 实现响应式更新。
- 提供 `isCustom()` / `resetToDefault()` 等方法区分默认值与用户自定义值。
- 键名集中在 companion object 中定义，避免魔法字符串散落。

## 三、配置分层与约定
- **编译期常量**：通过 `BuildConfig.VERSION_NAME` / `VERSION_CODE` 暴露给 UI，不可在运行时修改。
- **用户可配置项**：全部走 SharedPreferences，支持热更新（StateFlow），无重启生效需求。
- **下载源回退策略**：`DualSourceDownloader` 先尝试主源，失败自动回退备用源；校验失败最多重试指定次数。
- **引擎包就绪检查**：`EnginePackManager.isEnginePackReady()` 严格校验 `dartvm_*.so` 与 `libc++_shared.so` 是否存在，解压后立即二次验证目录结构。

## 四、约束与规则
- 子模块不得自行添加 Maven 仓库（由 `FAIL_ON_PROJECT_REPOS` 强制执行）。
- Keystone 静态库必须本地预编译放置，构建时缺失会直接失败。
- 引擎包下载后必须进行 SHA256 校验，校验失败抛出异常并提示重置下载源。
- 所有 SharedPreferences 使用 `MODE_PRIVATE`，键名集中管理，避免冲突。
- 配置类必须暴露 `StateFlow` 而非原始 `SharedPreferences`，确保 UI 层响应式消费。