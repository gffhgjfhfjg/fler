---
kind: frontend_style
name: Fler Android 前端样式系统（Material 3 + Compose 主题）
category: frontend_style
scope:
    - '**'
source_files:
    - app/src/main/java/com/ai/fler/app/theme/Theme.kt
    - app/src/main/java/com/ai/fler/app/theme/Color.kt
    - app/src/main/java/com/ai/fler/app/theme/Type.kt
    - app/src/main/java/com/ai/fler/ui/animation/AnimationConstants.kt
    - app/src/main/java/com/ai/fler/ui/components/ShimmerBox.kt
    - app/src/main/res/values/themes.xml
    - app/src/main/res/values-night/themes.xml
    - app/src/main/res/values/colors.xml
---

Fler 的 UI 样式完全基于 Jetpack Compose 与 Material Design 3，通过统一的 Theme 入口集中管理颜色、排版、形状与动画规范，确保全应用视觉一致性。

**系统与工具栈**
- UI 框架：Jetpack Compose（声明式 UI），无传统 XML 布局样式。
- 设计系统：Material 3（`androidx.compose.material3`），启用动态取色（Android 12+ `dynamicColorScheme`），老设备回退到自定义品牌蓝调色板。
- 主题入口：`FlerTheme` Composable 作为根主题，包裹整个应用树，统一注入 `colorScheme`、`typography`、`shapes`。
- 原生兼容：`res/values/themes.xml` 定义 `Theme.Fler` 基主题（NoActionBar），仅用于设置 windowBackground，实际样式由 Compose 接管。

**核心文件与职责**
- `app/src/main/java/com/ai/fler/app/theme/Color.kt`：浅/深两套调色板，以 `FlerPrimary = #1E6FBA` 品牌蓝为核心推导完整语义色集合（primary/secondary/tertiary/background/surface/error 及其 on/container 变体）。
- `app/src/main/java/com/ai/fler/app/theme/Type.kt`：Material 3 Typography 定制，沿用默认 Roboto 字族，统一字号/行高/字重映射到 display/headline/title/body/label 层级。
- `app/src/main/java/com/ai/fler/app/theme/Theme.kt`：主题编排中心，定义 `LightColors` / `DarkColors`、`FlerShapes`（8/12/16dp 三档圆角）、`FlerTheme` 入口（处理暗色切换、系统栏图标深浅、动态取色降级）。
- `app/src/main/res/values/colors.xml` & `themes.xml`：仅保留黑/白基础色与 `fler_primary` 锚点，供非 Compose 场景使用。
- `app/src/main/res/values-night/themes.xml`：夜间模式下的 NoActionBar 主题。

**架构与约定**
- 所有屏幕在 `MainActivity` 中通过 `FlerTheme { ... }` 包裹，禁止直接硬编码颜色或字体。
- 组件样式一律通过 `MaterialTheme.colorScheme.*`、`MaterialTheme.typography.*`、`MaterialTheme.shapes.*` 访问，保证随主题自动适配。
- 统一动画规范集中在 `ui/animation/AnimationConstants.kt`：`AnimDuration` 提供 micro/fast/normal/slow/xslow/shimmer 五档时长，`AnimEasing` 规定进入（FastOutSlowIn）、退出（FastOutLinearIn）、线性循环（Linear）、强调展开（LinearOutSlowIn）四类缓动曲线，并提供 `tweenSpec()` 工厂函数。
- 骨架屏等通用交互通过 `ui/components/ShimmerBox.kt` 等可复用组件实现，内部引用 `MaterialTheme.colorScheme.onSurface` 并应用统一圆角。

**约束与规则**
- 颜色必须来自 `MaterialTheme.colorScheme`，禁止在业务代码中直接使用 `Color(...)` 字面量（除 theme 定义外）。
- 圆角统一使用 `FlerShapes` 的 small/medium/large 三档，避免各处硬编码不同 dp 值。
- 动画时长与缓动曲线优先使用 `AnimDuration` 与 `AnimEasing` 常量，保持节奏一致。
- 深色/浅色主题通过 `isSystemInTheme()` 自动切换，Android 12+ 默认启用动态取色，不可禁用。