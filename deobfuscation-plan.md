# fler 反混淆增强 —— 落地文档与进度跟踪

> 目标：把「混淆 = 符号名不可读」降级为「结构恒可读」。不依赖被剥离的
> Dart 方法名，从 `.text` 机器码 + Dart 对象池（PP）常量引用反推方法/字段，
> 产出可直接打补丁的地址表。本文件为唯一落地文档，随实现实时更新。

---

## 一、问题与支点

| 混淆包特征 | 现状影响 | 反混淆支点 |
|---|---|---|
| `method_name` = 空 / `<anonymous closure>` | 按名搜索失效 | 结构定位不受影响 |
| `function_size` = 0 | 函数边界二分失效 | 用「下一方法 offset」作边界 |
| `function_offset`(vaddr)、`class_id`、`pp_entries` **完好** | — | **核心支点** |
| `pp_entries` 的 `vm_offset` = `[pp+0x..]` 引用偏移 | — | 字符串/Stub 搜索锚点 |

### 校准事实（真实数据验证，2026-08-08）

- 分析 **8=guwenda**（30083 方法，可读基准）、**10=Image**（仅 6513 方法 /
  74062 pp，方法名几乎全为 `<anonymous closure>`）、6=第三个包。
- 锚点：`User.isVIP`@0xA5F0B0、`LoginManager.isLogin`@0xA88930（guwenda 非混淆）。
- **x27 = PP 池基址寄存器**（isLogin 真实反汇编 `ldr x2, [x27, #0x57b0]`
  ↔ src_code `[PP, #0x57b0]`）。扫描签名：`ldr {reg}, [x27, #imm]`。
- **Blutter src_code 不可逐字信任**（isLogin 中 `[PP, #0x40]` 无对应真实指令，
  为单路径简化）→ 必须用真实 Capstone 反汇编扫描。
- 字段访问与 pool 常量加载形态不同：字段走 `add {reg}, {reg}, x28, lsl #32`
  （x28=HEAP 合成地址）+ `ldur`；pool 常量走 `ldr {reg}, [x27, #imm]` → 可区分防误报。

---

## 二、架构

```
Blutter 分析(dart_methods/dart_classes/pp_entries)
  ├─ FunctionIndex: 方法表按 vaddr 排序，保留 methodId/classId
  │    findContaining(vaddr)（用下一方法 offset 作界，兼容 size=0）
  │    methodsOf(classId)
  ├─ StringXrefScanner: .text 分块流式(256KB) → Capstone 解码
  │    → 匹配 pool 常量加载签名 ldr {reg},[x27,#imm]
  │    → site_vaddr → FunctionIndex 二分归属 methodId/classId
  │    → Map<methodId, List<siteVaddr>>
  ├─ 类级聚合: 同类方法 → 引用 String pp 槽 → 恢复字段集
  └─ 布尔 getter 识别: 形状(短体+返回 bool 槽) → 候选补丁表
       ↓
  MCP 工具(buildDeobfTools) → 地址表 → patch/export 打补丁
```

---

## 三、组件与工具

### P0（核心）
- [x] 落地文档
- [ ] `FunctionIndex`（core/analysis/FunctionIndex.kt）
- [ ] `StringXrefScanner`（core/analysis/StringXrefScanner.kt）
- [ ] DAO 小改：`PpEntryDao.getPpByVmOffsets` 批量 IN 查询
- [ ] MCP 工具接入 `buildDeobfTools()`：
  - `calibrate_pool_sig`(analysisId, vaddr) —— 反汇编已知方法，返回命中签名
  - `scan_pool_refs`(analysisId, soPath?, minRefCount?, maxResults?)
  - `string_xrefs`(analysisId, query)
- [ ] guwenda(8) 校准 + Image(10) 验证

### P1（类级）
- [ ] `infer_class_fields`(analysisId, classId/className)
- [ ] `find_bool_getters`(analysisId, classId/vaddrRange)
- [ ] Image 全量跑候选补丁表

### P2（可选体验/性能）
- [ ] Capstone JNI 精简变体（只回命中指令）
- [ ] 扫描结果落库（MIGRATION_8_9）
- [ ] SO 编辑器「字符串 xref」入口

---

## 四、关键设计决策

1. **签名**：`ldr {reg}, [x27, #imm]` 且 `imm == 目标 pp vm_offset` 即命中；
   排除 `add ..., x28, lsl #32` 形态（字段访问）。
2. **函数归属**：`findContaining` 用「下一方法 offset」作上界，兼容 `function_size=0`。
3. **坐标**：vaddr = sec.address + (paddr - sec.offset)；`fileOffsetOf` 复用
   McpToolHandlers 现成换算。
4. **性能**：256KB 分块 + 后台协程 + `currentProgress()` 进度上报。
5. **范围**：P0 仅 libapp.so。

---

## 五、验证路径

1. guwenda：`calibrate_pool_sig` 确认 x27 → `scan_pool_refs` 命中 `User.isVIP`
   等已知方法；`string_xrefs("VIP")` 与 src_code 一致。
2. Image：`infer_class_fields` 恢复 is_premium/premiumUser/premiumExpiresIn/
   premiumPackages 字段集；`find_bool_getters` 定位候选。
3. 端到端：Image 检测 getter 上打 TRUE/FALSE 补丁，打包验证生效。

---

## 六、进度日志

- **2026-08-08**：方案定稿落地。确认三份分析 id（8/10/6），校准锚点命中，
  x27=PP 基址实证，Blutter src_code 不可逐字信任。
