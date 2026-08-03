---
kind: external_dependency
name: Capstone 反汇编框架
slug: capstone
category: external_dependency
category_hints:
    - vendor_identity
    - framework_behavior
scope:
    - '**'
source_files:
    - app/src/main/cpp/jni_bridge/capstone_jni.cpp
    - app/src/main/java/com/ai/fler/core/jni/CapstoneBindings.kt
---

### Capstone 反汇编框架
- 核心作用：轻量级反汇编框架，用于 SelfAnalysisEngine 的 fallback 功能和独立反汇编
- 集成方式：静态链接 libcapstone.a 到 libfler_jni.so，与 Rizin 共享同一份静态库
- 特点：零冲突设计，Blutter、Rizin、App 三方共用同一份 Capstone 静态库
- 依赖关系：Rizin 的 cs_open 等符号在 .a 中为 undefined，由本库提供实现