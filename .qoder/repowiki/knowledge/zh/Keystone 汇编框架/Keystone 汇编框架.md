---
kind: external_dependency
name: Keystone 汇编框架
slug: keystone
category: external_dependency
category_hints:
    - vendor_identity
    - sdk_real_api
scope:
    - '**'
source_files:
    - app/src/main/cpp/jni_bridge/keystone_jni.cpp
    - app/src/main/java/com/ai/fler/core/analysis/assembler/KeystoneAssembler.kt
---

### Keystone 汇编框架
- 核心作用：指令汇编器，将汇编文本转换为机器码字节数组
- 集成方式：静态链接 libkeystone.a，需要本地交叉编译生成 arm64-v8a 版本
- 构建要求：需要通过 scripts/build-keystone.sh 脚本交叉编译，产物放置到 app/libs/arm64-v8a/libkeystone.a