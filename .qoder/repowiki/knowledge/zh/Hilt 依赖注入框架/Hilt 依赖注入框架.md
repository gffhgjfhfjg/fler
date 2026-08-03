---
kind: external_dependency
name: Hilt 依赖注入框架
slug: hilt
category: external_dependency
category_hints:
    - vendor_identity
    - framework_behavior
scope:
    - '**'
source_files:
    - app/src/main/java/com/ai/fler/FlerApplication.kt
    - app/src/main/java/com/ai/fler/core/di/CoreModule.kt
---

### Hilt 依赖注入框架
- 核心作用：Android 依赖注入框架，基于 Dagger 简化 DI 配置
- 集成方式：@HiltAndroidApp 标记 Application，@AndroidEntryPoint 标记 Activity/Fragment
- 模块组织：CoreModule、AnalysisModule、DatabaseModule 等按功能划分
- 生命周期：Singleton 级别组件包括 EngineRegistry、AnalysisSession、SoEditorCache、BackupManager 等
- 特性：KSP 注解处理器，支持 Compose Navigation 集成