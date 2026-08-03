# UI / 设计 / 动画 审计与优化计划

> **说明**：shadcn 是 React Web 组件库，不适用于 Android Jetpack Compose 项目。本计划直接针对项目的 Compose UI 代码进行审计，覆盖的设计、动画、组件等方面通过 Compose 原生 API 实现，无需 shadcn。

---

## 一、审计范围

| 领域 | 涉及文件 | 审计重点 |
|------|---------|---------|
| 设计系统/主题 | `Theme.kt`, `Color.kt`, `Type.kt` | 色彩体系、排版层级、形状系统、动态取色 |
| 动画 | 全局搜索 `animate*`, `Animatable`, `transition`, `spring`, `tween` | 动画类型、性能、一致性、过度动画 |
| UI 组件 | `ShimmerBox.kt`, `CardListTile.kt`, `XrefBottomSheet.kt` 等 | 组件复用、状态管理、可访问性 |
| 屏幕页面 | 全部 `*Screen.kt` | 布局性能、空状态/错误状态、加载态 |
| 导航 | `AppNavGraph.kt`, `Screen.kt` | 转场动画、深链接、返回栈管理 |
| 性能 | `LazyColumn` 使用、重组范围、`remember`/`derivedStateOf` | 不必要的重组、列表键值、状态提升 |

---

## 二、现有关键发现

### 2.1 好的实践
- 完整实现了 Material Design 3 主题系统（浅色/深色模式、动态取色、自定义调色板）
- 使用 `Roboto` 字体族 + 自定义 `Typography`，排版层级清晰
- 使用 `AnimatedContent` + `fadeIn/fadeOut` 实现 Tab 切换动画
- `ShimmerBox` 骨架屏组件通过 `rememberInfiniteTransition` 实现流畅脉冲效果
- 多数页面使用 `collectAsStateWithLifecycle` 生命周期感知
- Hilt 依赖注入，组件结构清晰

### 2.2 待优化项

#### A. 设计系统
1. **深色模式颜色对比度** — 部分自定义颜色在深色模式下缺乏对比度验证
2. **形状系统** — 未在 `Theme.kt` 中定义统一的 `Shapes`，各组件使用硬编码圆角
3. **动态取色兜底** — `dynamicColor` 不可用时（Android 12 以下）的降级方案不够优雅

#### B. 动画
1. **硬编码动画参数** — 多处动画使用硬编码时长/缓动曲线，未提取为共享常量
2. **列表项动画缺失** — `LazyColumn` 列表项新增/删除时无 `animateItemPlacement`/`AnimatedVisibility`
3. **页面转场单一** — 部分页面切换仅使用 `fadeIn/fadeOut`，缺少 `slideInHorizontally` 等方向性转场
4. **呼吸脉冲动画** — `SoEditorViewModel` 中使用 `delay()` 控制 `_flashAlpha` toggle，应改为 Compose 的 `Animatable` 或 `animateFloatAsState`

#### C. UI 组件
1. **空状态/错误状态覆盖不全** — 部分页面（如 `AsmBrowserScreen`）缺少空状态和错误状态展示
2. **加载状态视觉反馈** — 部分长时间操作缺少 `LinearProgressIndicator` 或 `CircularProgressIndicator`
3. **列表项 key 优化** — 部分 `LazyColumn` 未使用稳定的 `key`，可能导致不必要的重组

#### D. 性能
1. **重组范围过大** — 部分 Composable 函数未合理拆分，单次状态变化触发大范围重组
2. **`derivedStateOf` 使用不足** — 部分列表过滤/计算逻辑未使用 `derivedStateOf` 缓存
3. **图片/图标加载** — 未使用 `Coil` 或 `Glide` 进行图片加载优化

---

## 三、实施步骤

### Step 1: 设计系统增强

**目标**：统一形状系统、优化深色模式对比度、完善动态取色兜底

**改动文件**：
- [Theme.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/app/theme/Theme.kt) — 添加 `Shapes` 定义、优化 `dynamicColor` 降级逻辑
- [Color.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/app/theme/Color.kt) — 调整深色模式下颜色对比度、添加 `onSurface` 变体色

**具体方案**：
1. 在 `Theme.kt` 中定义 `FlerShapes`：
   ```kotlin
   val FlerShapes = Shapes(
       small = RoundedCornerShape(8.dp),
       medium = RoundedCornerShape(12.dp),
       large = RoundedCornerShape(16.dp),
   )
   ```
2. 在深色模式中，确保 `onSurface` 与背景的对比度 ≥ 7:1（WCAG AAA 标准）
3. `dynamicColor` 降级时，使用 `lightColorScheme`/`darkColorScheme` 提供的完整兜底，而非手动拼接

### Step 2: 动画系统优化

**目标**：提取共享动画常量、补充列表项动画、优化脉冲动画实现

**改动文件**：
- 新建 `app/src/main/java/com/ai/fler/ui/animation/AnimationConstants.kt` — 共享动画时长/缓动常量
- 新建 `app/src/main/java/com/ai/fler/ui/animation/Animations.kt` — 共享转场动画定义
- [SoEditorViewModel.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorViewModel.kt) — 优化 `_flashAlpha` 脉冲动画
- [SoEditorScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/SoEditorScreen.kt) — 补充 Tab 切换方向性动画
- [ProjectScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/project/ProjectScreen.kt) — 添加列表项新增/删除动画
- [ProjectDetailScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/project/ProjectDetailScreen.kt) — 添加列表项新增/删除动画

**具体方案**：
1. `AnimationConstants.kt`：
   ```kotlin
   object AnimDuration {
       val fast = 200.ms
       val normal = 300.ms
       val slow = 500.ms
   }
   object AnimEasing {
       val entry = FastOutSlowInEasing
       val exit = FastOutLinearInEasing
   }
   ```
2. `Animations.kt`：
   ```kotlin
   fun Modifier.fadeInSlideRight(): Modifier = /* ... */
   fun Modifier.fadeInSlideLeft(): Modifier = /* ... */
   ```
3. 脉冲动画：将 ViewModel 中的 `delay()` + `_flashAlpha.toggle` 改为 UI 层 `animateFloatAsState` + `tween(500, FastOutSlowInEasing)`
4. `LazyColumn` 列表项新增/删除添加 `animateItemPlacement()` 修饰符

### Step 3: UI 组件增强

**目标**：补全空状态/错误状态、统一加载反馈、优化列表性能

**改动文件**：
- 新建 `app/src/main/java/com/ai/fler/ui/components/EmptyState.kt` — 空状态组件
- 新建 `app/src/main/java/com/ai/fler/ui/components/ErrorState.kt` — 错误状态组件
- 新建 `app/src/main/java/com/ai/fler/ui/components/LoadingOverlay.kt` — 加载覆盖层组件
- [AsmBrowserScreen.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/output/AsmBrowserScreen.kt) — 添加空状态/错误状态
- [StructureTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/StructureTab.kt) — 添加 `key` 优化、`animateItemPlacement`
- [DisassemblyTab.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/DisassemblyTab.kt) — 添加 `key` 优化、`animateItemPlacement`
- [XrefBottomSheet.kt](file:///c:/Users/Len/AndroidStudioProjects/fler/app/src/main/java/com/ai/fler/features/so_editor/XrefBottomSheet.kt) — 优化状态管理

**具体方案**：
1. `EmptyState` 组件：
   ```kotlin
   @Composable
   fun EmptyState(icon: ImageVector, title: String, message: String, action: (() -> Unit)? = null)
   ```
2. `ErrorState` 组件：支持重试按钮、错误消息展示
3. `LoadingOverlay` 组件：基于 `CircularProgressIndicator` 的半透明覆盖层
4. 为所有 `LazyColumn` 列表项添加稳定的 `key`（使用 ID 或地址，而非索引或 hashCode）

### Step 4: 性能优化

**目标**：减少不必要的重组、优化列表渲染

**改动文件**：
- 所有 `*Screen.kt` 和 `*Tab.kt` 文件

**具体方案**：
1. 检查并拆分过大的 Composable 函数，确保每个函数职责单一
2. 对列表过滤/排序/搜索逻辑使用 `derivedStateOf` 缓存计算结果
3. 确保所有 `LazyColumn` 使用稳定的 `key` 参数
4. 检查 `remember` 使用，确保昂贵的计算被缓存

---

## 四、验收标准

- 所有页面在不同屏幕尺寸下布局正确
- 深色/浅色模式切换流畅，颜色对比度符合 WCAG AA 标准
- 页面切换动画平滑，无闪烁/跳帧
- 列表项新增/删除有平滑动画
- 空状态、错误状态、加载状态在所有页面统一且完整
- 编译通过，无新增警告
- APK 大小无显著增加（< 100KB）

---

## 五、不涉及的范围

- 不修改业务逻辑（分析引擎、数据库、网络请求等）
- 不添加新功能或新页面
- 不修改第三方库依赖
- 不修改 Gradle 构建配置