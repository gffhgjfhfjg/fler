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

- **root / Magisk 授权**：Magisk 按 uid 授权，重装 App 会导致 uid 漂移使旧授权失效；同时部分 ROM（如 MIUI）有**后台启动限制**会压掉 Magisk 授权弹窗（`SuRequestActivity` 一拉起即转 BACKGROUND）。遇到"探测中一直转/超时"时：在 Magisk 给当前 uid 授权，或给 Magisk 关掉后台弹出界面限制。App 侧已做 `runSu` 5s 超时 + 授权重触发 + 设置页 withTimeout 兜底，避免永久卡死。
- **Frida 客户端/服务端须同版本**（当前 `17.17.0`），不一致时会自动停旧重装。
- 模拟执行（Unicorn）默认关闭，需在设置中显式开启。

---

## License

见 [LICENSE](LICENSE)。

## 文档

- `docs/rikkahub/` — Fler 系统提示词与 `fler-analyze-method` 技能（供工具调用方注入上下文）。
