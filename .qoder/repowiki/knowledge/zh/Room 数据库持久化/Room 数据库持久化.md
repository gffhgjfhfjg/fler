---
kind: external_dependency
name: Room 数据库持久化
slug: room
category: external_dependency
category_hints:
    - vendor_identity
    - migration_status
scope:
    - '**'
source_files:
    - app/src/main/java/com/ai/fler/data/AppDatabase.kt
    - app/src/main/java/com/ai/fler/data/dao/
---

### Room 数据库持久化
- 核心作用：SQLite ORM 框架，用于项目和分析数据的持久化存储
- 实体模型：Project、Analysis、DartClass、DartMethod、PpEntry、Library、AddressMapping 共 7 个实体
- 数据访问：每个实体对应一个 DAO，提供 CRUD 操作和复杂查询
- 版本管理：当前版本 3，支持 schema 导出和迁移
- 事务支持：cascadeDeleteProject 和 cascadeDeleteAnalysis 提供级联删除事务