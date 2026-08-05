# APK 瘦身方案（A 档 · release 为主）

> 目标：不改变软件功能，仅通过构建配置给 APK 瘦身。
> 状态：方案已确认，待实施。

## 一、现状数据（2026-08-03 实测，debug APK 57.5MB）

| 项 | 打包体积 | 说明 |
|---|---|---|
| `lib/arm64-v8a/libfler_jni.so` | 33.9MB（未压缩） | Rizin + Capstone + Keystone 静态链接；debug 用 `-O0`，剥离前 64.2MB |
| `lib/arm64-v8a/libc++_shared.so` | 1.2MB | c++_shared STL |
| dex ×17 | ~20.5MB（压缩后，raw 68.9MB） | 未开 R8；含 material-icons-extended 大量未用图标 |
| `resources.arsc` / res | ~2MB | 仅 `values-night`，无多语言 |
| 其他（META-INF、kotlin、org 等） | <0.5MB | |

- `.so` 均未压缩（`useLegacyPackaging=false` 默认，按页对齐供 mmap）。
- 原生已 `-O2` + strip（release 默认 strip）；debug 为 `-O0`。
- release 当前 `optimization { enable = false }`（未开 R8）。

## 二、选定方案：A 档（纯构建配置，零代码/零功能改动）

### 1. `app/build.gradle.kts` —— buildTypes.release 开启 R8

```kotlin
buildTypes {
    release {
        optimization { enable = true }          // false→true：R8 压缩 + 资源收缩
        proguardFiles(
            getDefaultProguardFile("proguard-android-optimize.txt"),
            "proguard-rules.pro"
        )
    }
}
```

### 2. 新建 `app/proguard-rules.pro`

```proguard
# JNI 桥接类：native 按全限定类名+方法名调用，必须保留（否则混淆即崩）
-keep class com.ai.fler.core.jni.** { *; }
# kotlinx-serialization（MCP JSON-RPC 载荷），保守保留可序列化模型
-keep @kotlinx.serialization.Serializable class * { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod
```

> Hilt / Room / kotlinx-serialization 均自带混淆规则，无需额外配置。
> 已确认：代码中无 `Class.forName` / reflect / `kotlin.reflect`，R8 风险面收敛在 JNI keep 规则。

### 3. `app/src/main/cpp/CMakeLists.txt` —— 链接期去死代码

- `add_compile_options(...)` 追加 `-ffunction-sections -fdata-sections`
- `fler_jni` 追加链接选项：

```cmake
target_link_options(fler_jni PRIVATE -Wl,--gc-sections)
```

> 作用：丢弃 Rizin/Capstone/Keystone 中被链接对象里未被引用的函数（仅作用于已链接部分，不影响功能）。

## 三、预估收益

- 全部 A 档：release **~57MB → 约 25–30MB**
  - dex ~20.5MB → 约 6–10MB（R8）
  - `libfler_jni.so` 释放 `-O2` + strip + gc-sections → 33.9MB → 约 13–18MB

## 四、验证清单（实施后）

1. `gradlew :app:assembleRelease` → 记录 release APK 体积。
2. 真机安装 release（未签名仅可本机 adb 安装，或临时用 debug 签名）回归：
   - Rizin 打开 SO：符号 / 动态符号 / 字符串 / 反汇编 / xref 正常
   - Hex 写字节成功（`rz_io_pwrite_at` 路径）
   - Hilt 注入正常（应用能启动、SO 编辑器能打开）
   - Room 数据读写、MCP 本地 HTTP 正常
   - 无 `ClassNotFoundException` / 静默返回空
3. 用 `app/build/outputs/mapping/release/mapping.txt` 抽查关键类未被误删。

## 五、风险与回滚

- 唯一风险点：R8 配合 JNI / serialization 的 keep 规则。
- 若某功能异常：先补 `-keep` 规则；极端情况回滚 `optimization { enable = false }` 即恢复原状。
- 注意：release 当前未配签名（`signingConfigs` 为空），会产出**未签名** APK。建议顺带 `signingConfigs.release = signingConfigs.debug` 以便直接 adb 安装回归（不影响瘦身）。

## 六、后续可选档位（本次不做）

| 档 | 内容 | 收益 | 风险 |
|---|---|---|---|
| B | Capstone/Keystone 只编 AArch64+ARM；Rizin 按需裁剪插件（改 CI workflow 重建静态库） | 再省数 MB | 中，需真机回归 izzj/axtj 等 |
| C | `.so` 压缩打包（`useLegacyPackaging=true`）→ 33.9→约 12MB；改 AAB 分发 | 下载体积再降 ~20MB | 安装/加载稍慢 |
| D | 移除 material-icons-extended 换 core 图标 | 已被 R8 覆盖 | 需换 7 个图标，视觉有变化 |
