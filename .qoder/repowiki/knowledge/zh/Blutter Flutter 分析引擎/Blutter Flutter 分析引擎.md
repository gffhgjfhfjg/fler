---
kind: external_dependency
name: Blutter Flutter 分析引擎
slug: blutter
category: external_dependency
category_hints:
    - vendor_identity
    - client_constraint
scope:
    - '**'
source_files:
    - app/src/main/cpp/jni_bridge/blutter_jni.cpp
    - app/src/main/java/com/ai/fler/core/jni/BlutterEngine.kt
    - fler-engines/
---

### Blutter Flutter 分析引擎
- 核心作用：专门分析 Flutter 应用生成的 libapp.so，提取 Dart 类、方法、PP 条目等信息
- 集成方式：动态加载对应版本的 dartvm_x.y.z.so 引擎包（运行时选择）
- 加载机制：通过 dlopen 动态加载，包含 stderr 重定向和信号捕捉
- 依赖链：需要先加载 libc++_shared.so → libicudata.so → libicuuc.so → dartvm_x.y.z.so
- 引擎包：预置在 fler-engines/ 目录，支持从远程源下载更新