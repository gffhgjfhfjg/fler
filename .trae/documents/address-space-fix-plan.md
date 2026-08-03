# 地址空间修复计划 V3：最终修复

## 诊断结论

### 快速诊断结果

| 指标 | 注入前 | 注入后（1个函数 + aar） |
|------|--------|------------------------|
| `io.va` | true | - |
| `afo` | null | - |
| `vaddrToPaddr` | 返回原值 | - |
| `axtj` 长度 | **3** | **46** ✅ |
| `afij` | null | `DateTime.compareTo` 已注册 |

### 根因定位

**`af` + `aar` 工作正常，不是 xref 重建失败的问题。**

真正的根因是**时序问题**：
1. `defineFunction` 调用 `af @ $addr` 清除该地址范围内的 xref 条目
2. `defineFunctions(pairs)` 对 19803 个函数逐一调用 `af`（39606 次 JNI 调用，耗时极长）
3. 等全部 19803 个函数 `af` 完后，`reanalyzeXrefs` 才调用 `aar` 重建 xref
4. 用户在这中间点击函数 → 看到 xref = 0（已被 `af` 清除，`aar` 还没跑完）

### 修复方案

**核心思路：既然 `aar` 不依赖函数边界就能找到 xref，`defineFunction` 就不需要调 `af`。**

移除 `defineFunction` 中的 `af @ $hexAddr` 命令，只保留 `f $name @ $hexAddr` 设 flag 名。这样：
- `defineFunctions` 不会清除任何 xref 条目
- `aaa` 阶段建立的 xref 始终保留
- `aar` 仍可独立运行，补充扫描更多 xref
- 不再依赖 `aar` 的时序（xref 始终可用）

### 修改文件

1. **[RizinEngine.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/core/analysis/engine/RizinEngine.kt)**
   - `defineFunction` 中移除 `cmd(handle, "af @ $hexAddr")`，只保留 `f $name @ $hexAddr`
   - 更新注释说明：不再需要 `af`，因为 `aar` 不依赖函数边界

2. **[SoEditorViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt)**
   - 恢复为正常流程：移除快速诊断代码，恢复 `checkAddressSpace` 注入前诊断即可
   - 保留 `reanalyzeXrefs` 调用作为补充扫描

### 实施步骤

| 步骤 | 文件 | 改动 |
|------|------|------|
| 1 | RizinEngine.kt | 移除 `af` 命令，更新注释 |
| 2 | SoEditorViewModel.kt | 恢复为简化流程 |
| 3 | 构建 APK | `gradlew assembleDebug` |
| 4 | 真机验证 | 点击 Dart 函数查看 xref 面板 |

### 验证标准

1. 打开有 Blutter 分析的 SO 文件
2. 点击任意 Dart 函数，xref 面板稳定显示调用方
3. 多次切换函数，xref 不出现"无交叉引用"
4. 结构 Tab 的函数、符号、节区数据不受影响