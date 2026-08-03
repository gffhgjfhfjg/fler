---
kind: external_dependency
name: Rizin 二进制分析引擎
slug: rizin
category: external_dependency
category_hints:
    - vendor_identity
    - sdk_real_api
scope:
    - '**'
source_files:
    - app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt
    - app/src/main/cpp/jni_bridge/rizin_jni.cpp
---

### Rizin 二进制分析引擎
- 核心作用：主要分析引擎，提供 ELF 解析、反汇编、函数分析、交叉引用、CFG 等高级功能
- 集成方式：通过 JNI 静态链接 26 个 librz_*.a 库到 libfler_jni.so
- 关键命令：ij（文件信息）、iSj/iisj（节区/符号）、aflj（函数列表）、pdj（反汇编）、axtj/axfj（交叉引用）
- 权限处理：先尝试 RW 模式打开文件，失败自动降级到只读以适配 SELinux
- 验证：具体命令格式和参数需对照官方文档确认