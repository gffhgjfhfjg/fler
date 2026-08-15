# fler

**设备端二进制逆向 / Flutter App 静态分析工作台** — 一款 Android Native 逆向工具，把 Blutter（Dart AOT 恢复）、Rizin、Capstone、Keystone、Unicorn、Frida 动态插桩等引擎整合进一个 App，并通过本地 **MCP（Model Context Protocol）服务器**暴露给桌面端 LLM 客户端使用。

> 应用包名 `com.ai.fler` · 支持 Android 8.0 (API 26)+ · arm64-v8a

---

## 功能特性

- **Blutter Dart AOT 恢复**：解析 Flutter `libapp.so` 的对象池（PP）、类与方法，恢复 Dart 语义结构（类层级、方法、字符串常量、布尔 getter 定位），支持混淆包的 `.text` 结构扫描反混淆。
- **Rizin 分析引擎**：内置 rizin（由 26 个静态库 `librz_*.a` + capstone 链接），提供函数 / CFG / 交叉引用 / 反汇编等完整二进制分析能力。
- **Capstone 反汇编 + Keystone 汇编**：ARM64 指令级反汇编与汇编（含 pool 常量引用扫描、布尔 getter 返回形态分析等反混淆辅助）。
- **Unicorn 模拟执行**：可选开启的指令模拟（默认关闭）。
- **ELF 解析器**：章节 / 符号 / 动态符号表浏览。
- **Frida 动态插桩**：root 设备方案。内置 frida-server 部署/保活，支持 attach / spawn / hook / 事件流，及**运行时字节级热补丁**（`patch_code` / `read_code`）。
- **原生补丁**：对 `.so` 写原始字节 / 汇编指令，自动备份 + CRC 校验 + 可撤销。
- **MCP 服务器**（本机 HTTP，默认端口 `8765`）：
  - Streamable HTTP 端点 `http://127.0.0.1:8765/mcp`（LLM 客户端用）
  - SSE 端点 `http://127.0.0.1:8765/sse`（Claude Desktop 用）
  - 可选 LAN 绑定 + Bearer Token 鉴权
  - `/export/<file>` 导出下载，SAF 工作目录 / App 缓存落地
- **Hook 脚本管理**：内置预设 + 自定义 Frida JS 脚本增删改查。

---

## 架构

```
fler
├── app/src/main/
│   ├── java/com/ai/fler/
│   │   ├── core/
│   │   │   ├── analysis/          # Rizin 引擎
│   │   │   ├── frida/             # Frida 会话编排 / root 部署 / hook 脚本
│   │   │   ├── jni/               # JNI 绑定 facade（Blutter/Capstone/ELF/Frida/Keystone/Rizin/Unicorn）
│   │   │   ├── mcp/               # MCP 协议 / 工具注册 / 服务器
│   │   │   ├── service/           # 引擎包管理 / 工作目录 / 下载器 / 提取器
│   │   │   ├── editor/  log/  di/
│   │   ├── features/              # 设置 / 引擎 / MCP 管理 UI（Jetpack Compose + Hilt）
│   │   ├── data/  ui/  app/
│   ├── cpp/
│   │   ├── jni_bridge/            # blutter/ capstone/ elf_parser/ frida/ keystone/ rizin/ unicorn JNI 桥
│   │   ├── elf_parser/  include/  keystone_include/
│   └── res/  keepRules/
├── scripts/                       # 交叉编译脚本（unicorn / keystone 等）
├── vendor/                        # unicorn 源码（不入库）
├── docs/                          # rikkahub 技能文档
```

Native 代码静态链接进 `libfler_jni.so`（frida-core、rizin、capstone、keystone、unicorn、elf-parser 全部打包进单一 `.so`）。

---

## 构建

环境：JDK 17、Android SDK、NDK（含 clang）、CMake。本地仓库走国内镜像（阿里云）加速。

```bash
# 默认（ENABLE_FRIDA / 各引擎 on）
./gradlew :app:assembleDebug        # debug
./gradlew :app:assembleRelease      # release（so 以 deflate 压缩打包，体积小）

# 产物
app/build/outputs/apk/.../*.apk
```

> **体积说明**：release 对 `lib/arm64-v8a/*.so` 使用 `useLegacyPackaging=true`（deflate 压缩进 APK），`libfler_jni.so` 从 85MB 压到约 31MB，安装时解压到数据分区。分发重体积、可接受安装稍慢时用此配置。

### 交叉编译的第三方库
- **keystone**：`app/src/main/cpp/keystone_include/` + `scripts/`（GitHub Actions 交叉编译产物，构建期自动拉取，`.a` 不入库）
- **unicorn**：`scripts/build-unicorn.ps1`（源码在 `vendor/`，不入库）
- **frida**：`app/src/main/cpp/include/frida/frida-core.h` + frida-core 静态库；编译选项 `ENABLE_FRIDA`（关掉时 JNI 退化为安全 stub，避免 `UnsatisfiedLinkError`）

---

## Blutter 分析引擎（fler-dart）

Flutter App 的 Dart AOT 恢复依赖 Blutter 引擎，但该引擎**不在本仓库**，而是由独立仓库 **[myfler/fler-dart](https://github.com/myfler/fler-dart)** 负责编译与发布，App 运行时按需下载加载：

- **职责分离**：`fler-dart` 通过 CI 按 Dart 版本交叉编译 Blutter 的 `dartvm.so`，并把 `blutter_entry.cpp`（把 Blutter 分析结果写入 SQLite 的入口）打进引擎，产出发行包（如 `fler-engines.7z` / `dartvm-<dartVersion>.7z`）上传到 GitHub Releases。
- **运行期分发**：App 从 `fler-dart` main 分支的 `manifest.json`（`https://raw.githubusercontent.com/myfler/fler-dart/main/manifest.json`）读取可用 Dart 版本清单，经 `EngineLoader` 按需下载对应 `dartvm_<dartVersion>.so`，由 JNI `blutter_analyze()` 执行分析，结果写入 SQLite（表结构由引擎决定）。
- **默认源**：`EngineSourceConfig` 内置 manifest 地址 + GitHub 加速前缀（`https://gh-proxy.com`，可在设置页修改/清空），缺失或不可用时可在设置页改自定义源。

> 引擎包为独立版本（当前 `v0.4.0`），与 App 版本解耦；升级引擎只要重下对应 Dart 版本的 `dartvm_*.so`，无需重装 App。

---

## 使用

1. **本机（Android）**：安装 App → 设置页启动 MCP 服务器，得到 `http://127.0.0.1:8765/mcp`。
2. **LLM 客户端**（Claude Desktop / MCP 客户端）：
   ```
   http://127.0.0.1:8765/mcp   (Streamable HTTP)
   http://127.0.0.1:8765/sse    (SSE / 推送)
   ```
3. **Frida 动态插桩**：需 root 设备。App 会部署常驻 frida-server 到 `/data/local/tmp`，经 `frida_ready` → `frida_list_processes` / `frida_list_apps` → `frida_hook` / `frida_spawn` 完成闭环。
4. **静态分析**：导入目标 `libapp.so`（APK/SO），用 `engine_open` / `engine_analyze` / Blutter 相关工具恢复 Dart 方法、字符串 xref、pool 槽引用；`scan_pool_refs` / `find_bool_getters` 用于混淆包反混淆。

> 功能均以 MCP 工具暴露，工具清单与参数可在客户端 `tools/list` 查看，或在 App 的 MCP 统计/日志页核对。

---

## 已知注意项

- **Frida 客户端/服务端须同版本**（当前 `17.17.0`），不一致时会自动停旧重装。
- 模拟执行（Unicorn）默认关闭，需在设置中显式开启。

---

## 缺点与不足

以下为本项目当前已知的短板 / 待改进项，供使用者与贡献者透明参考：

- **仅 arm64-v8a**：Native 层（capstone/keystone/rizin/frida/unicorn 静态链接进单 `.so`）只构建了 arm64。armv7 / x86_64 设备无法使用，低端 32 位机型不支持。
- **依赖 root 才能用足 Frida**：Frida 动态插桩（attach/spawn/hook/热补丁）走 Magisk root + `frida-server` 部署，非 root 设备上仅能用静态分析面。
- **镜像与第三方库导致构建较重**：unicorn、keystone 需交叉编译且 `.a`/`vendor` 不入库（构建期拉取/本地脚本），首次构建依赖 Google/阿里云镜像网络，断网或代理环境下易失败。
- **`.so` 较大、安装增耗**：`libfler_jni.so` 打包 frida-core + rizin(+26 个 `.a`) + capstone + keystone + unicorn ≈ 85MB；虽用 deflate 压缩进 APK（→31MB），但安装时解压到数据分区，安装略慢、磁盘占用较高，非体积敏感分发场景不划算。
- **MCP 仅本机/LAN**：Streamable HTTP 服务器绑定 `127.0.0.1`（或可选 LAN `0.0.0.0`），不支持跨网远程连接（除非另配代理/隧道）。
- **分析结果是离线快照**：`analysisId` 是一次 Blutter 分析的结果快照；App 更新后需重新导入 `libapp.so` 并重跑分析，不自动跟踪版本差异。
- **依赖 Rizin 的全量 `aaa` 分析对大库开销高**：对超大 `libapp.so` 做函数/CFG/xref 分析内存与耗时都大（有 OOM 风险），分析代码时建议用 Blutter 合并结果而非整库 `engine_analyze`。
- **UI 面较薄**：当前核心能力通过 MCP 暴露给桌面端 LLM 客户端，App 自身倾向于「工具/服务器」定位，独立交互 UI（除设置/统计/日志页外）较简，不适合纯手机端手工逆向。

---

## 致谢

本项目构建在众多优秀的开源项目之上，特此感谢：

- **[Blutter](https://github.com/worawit/Blutter)** — Dart AOT 对象池/类/方法恢复引擎，本项目 Blutter 分析的基础。
- **[Rizin](https://github.com/rizinorg/rizin)** — 二进制分析框架，提供函数 / CFG / 交叉引用 / 反汇编能力。
- **[Capstone](https://github.com/capstone-engine/capstone)** — 反汇编框架，ARM64 指令级反汇编。
- **[Keystone](https://github.com/keystone-engine/keystone)** — 汇编框架，指令级汇编与补丁。
- **[Unicorn](https://github.com/unicorn-engine/unicorn)** — CPU 模拟引擎，指令模拟执行。
- **[Frida](https://github.com/frida/frida)** — 动态插桩框架，attach / spawn / hook / 运行时热补丁。
- **[Flutter](https://flutter.dev)** — 本项目 UI 框架。
- **[Android](https://developer.android.com)** — 平台与工具链。
- **[rikkahub](https://github.com/rikka-apps/)** 及其工具链 — 提供系统提示词与 `fler-analyze-method` 技能注入。

## License

见 [LICENSE](LICENSE)。

## 文档

- `docs/rikkahub/` — Fler 系统提示词与 `fler-analyze-method` 技能（供工具调用方注入上下文）。
