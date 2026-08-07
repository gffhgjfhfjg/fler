# Code Wiki 文档生成方案

## 概要

为 fler 项目生成一份结构化的完整 Code Wiki 文档，涵盖项目整体架构、主要模块职责、关键类与函数说明、依赖关系以及项目运行方式。

## 当前状态分析

已通过 4 个并行搜索 Agent 完成对以下层面的完整探索：
- 构建配置（build.gradle.kts / settings.gradle.kts / CMakeLists.txt / libs.versions.toml）
- 核心分析引擎层（BinaryAnalysisEngine / EmulationEngine / EngineRegistry / AnalysisSession / 6 个引擎实现）
- DI / JNI / MCP / Service 层（3 个 Hilt Module / 8 个 JNI Binding / 11 个 MCP 类 / 12 个 Service）
- 数据层 + 特性层（AppDatabase + 8 Entity + 8 DAO / 4 个功能模块 / 应用层）

## 生成内容

**输出文件**：`c:\Users\Len\AndroidStudioProjects\fler\fler8.7.md`

**文档结构**：

1. **项目概览** — 项目定位、技术栈、版本信息
2. **工程结构** — 包结构目录树、模块组织、构建系统
3. **架构总览** — 分层架构图（ASCII）、依赖关系图、单例依赖图
4. **核心引擎层** — BinaryAnalysisEngine 接口、EngineRegistry、AnalysisSession、6 个引擎实现、数据类型
5. **JNI 桥接层** — 8 个 Binding 类、C++ 实现层（6 个 JNI cpp + elf_parser）
6. **DI 层** — 3 个 Hilt Module
7. **MCP 层** — HTTP Server、协议、工具注册、地址轴解析
8. **Service 层** — 12 个服务类
9. **数据层** — AppDatabase、8 Entity、8 DAO、数据库迁移
10. **特性层** — 项目管理、SO 编辑器（4 Tab）、引擎下载、设置/MCP
11. **应用层** — Application、MainActivity、主题、导航
12. **构建与运行** — 环境要求、构建步骤、原生库准备
13. **关键设计决策** — 静态链接策略、缓存机制、坐标系统、撤销系统

## 验证

- 检查文档中所有文件路径均为实际存在的路径
- 检查类名、方法签名与代码一致
- 检查版本号、依赖名与 build.gradle.kts 一致
