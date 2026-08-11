package com.ai.fler.core.mcp

import com.ai.fler.core.frida.FridaEngine
import com.ai.fler.core.frida.FridaScriptBuilder
import com.ai.fler.core.frida.HookScriptRepository
import com.ai.fler.core.frida.RootAccess
import com.ai.fler.core.jni.FridaBindings
import com.ai.fler.data.dao.DartMethodDao
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frida 动态调试 MCP 工具面（root 方案 · App 内闭环）。
 *
 * 工作流：
 * 1. frida_ready：确保 root + frida-server + 本地客户端就绪
 * 2. frida_list_processes / frida_list_apps：选目标
 * 3. frida_attach(pid) 或 frida_spawn(pkg) → sessionHandle
 * 4. frida_hook(session, analysisId, methodName) → 自动从静态分析库取 vaddr 插桩；
 *    或 frida_use_script(session, scriptId) 加载「Hook 脚本」页里落地的自定义 JS
 * 5. frida_events：拉取 hook 命中事件
 * 6. frida_resume/kill/detach：生命周期收尾
 */
@Singleton
class FridaMcpToolRegistry @Inject constructor(
    private val engine: FridaEngine,
    private val rootAccess: RootAccess,
    private val dartMethodDao: DartMethodDao,
    private val hookScriptRepository: HookScriptRepository,
) {

    fun buildTools(): List<McpToolHandlers.McpTool> = listOf(
        tool(
            name = "frida_ready",
            description = "Frida 环境就绪检查+启动：探测 root，必要时把 frida-server 部署到 /data/local/tmp 并拉起（常驻），初始化本地 libfrida-core 客户端。返回 available/root/serverRunning/initialized/version",
        ) { _ ->
            val status = engine.ensureReady()
            buildJsonObject {
                put("available", status.available)
                put("root", status.root)
                put("serverRunning", status.serverRunning)
                put("initialized", status.initialized)
                put("version", status.version)
            }
        },
        tool(
            name = "frida_status",
            description = "Frida 环境状态查询（不触发部署）：available（libfrida-core 客户端）/root/serverRunning/initialized/version",
        ) { _ ->
            val status = engine.status()
            buildJsonObject {
                put("available", status.available)
                put("root", status.root)
                put("serverRunning", status.serverRunning)
                put("initialized", status.initialized)
                put("version", status.version)
            }
        },
        tool(
            name = "frida_list_processes",
            description = "列出设备上所有进程（经 frida-server，root 可见全部）：[{pid,name}]",
        ) { _ ->
            JsonPrimitive(engine.listProcesses())
        },
        tool(
            name = "frida_list_apps",
            description = "列出设备已安装应用（frida-server 枚举）：[{identifier,name}]",
        ) { _ ->
            JsonPrimitive(engine.listApplications())
        },
        tool(
            name = "frida_attach",
            description = "attach 到已运行的 pid，返回 sessionHandle（后续 frida_hook/frida_eval 用）",
            schema = mapOf(
                "pid" to "目标进程 pid（来自 frida_list_processes）"
            )
        ) { p ->
            val pid = p.longOrThrow("pid")
            val session = engine.attach(pid)
            if (session == 0L) throw McpToolException("attach pid=$pid 失败：进程不存在或 frida-server 未就绪（先 frida_ready）")
            buildJsonObject {
                put("sessionHandle", session)
                put("pid", pid)
            }
        },
        tool(
            name = "frida_spawn",
            description = "spawn 目标应用并在入口 gating 暂停，返回 pid + sessionHandle。之后 frida_hook 布好脚本再 frida_resume 放行",
            schema = mapOf(
                "identifier" to "应用包名/identifier（来自 frida_list_apps）"
            )
        ) { p ->
            val identifier = p.strOrThrow("identifier")
            val pid = engine.spawn(identifier)
            if (pid == 0L) throw McpToolException("spawn $identifier 失败（包名不存在或应用不可调试）")
            val session = engine.attach(pid)
            buildJsonObject {
                put("pid", pid)
                put("sessionHandle", session)
                put("gated", true)
            }
        },
        tool(
            name = "frida_hook",
            description = "把静态分析库中的 Dart/so 方法 vaddr 自动翻译成 Interceptor 脚本并加载到 session。返回 scriptHandle + 事件用 frida_events 拉取。两种定位方式：1) methodName+analysisId 从静态库查名（非混淆包）；2) 直接给 vaddr（+module），用于混淆包——先用 string_xrefs/scan_pool_refs 等反混淆工具拿到目标 vaddr 再直挂",
            schema = mapOf(
                "sessionHandle" to "frida_attach/frida_spawn 返回的会话句柄",
                "analysisId" to "（按名时必填）该方法的 Blutter 分析记录 id",
                "methodName" to "（按名时必填）Dart 方法名（method_name 精确匹配，如 _fetchList）",
                "vaddr" to "（直挂时必填）函数 vaddr，十进制或 0x 十六进制；来自 get_method/list_methods 的 functionOffset 或反混淆工具产物",
                "module" to "目标 so 名（默认 libapp.so；直挂时也可指定如 libapp.so）",
                "decodeDart" to "是否尽力解码 Dart String 参数（默认 true，失败回退原始指针/hex）"
            )
        ) { p ->
            val session = p.longOrThrow("sessionHandle")
            val module = p["module"]?.jsonPrimitive?.contentOrNull ?: "libapp.so"
            val decodeDart = p["decodeDart"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true

            val directVaddr = p["vaddr"]?.jsonPrimitive?.contentOrNull?.trim()?.let { raw ->
                if (raw.startsWith("0x") || raw.startsWith("0X")) raw.substring(2).toLongOrNull(16)
                else if (raw.endsWith("h")) raw.dropLast(1).toLongOrNull(16)
                else raw.toLongOrNull()
            }

            val vaddr: Long
            val label: String
            if (directVaddr != null) {
                vaddr = directVaddr
                label = "vaddr:0x${vaddr.toString(16)}"
            } else {
                val analysisId = p.longOrThrow("analysisId")
                val methodName = p.strOrThrow("methodName")
                val method = dartMethodDao.getMethodWithClassByName(analysisId, methodName)
                    ?: throw McpToolException("analysisId=$analysisId 下未找到方法 $methodName")
                vaddr = method.method.functionOffset
                    ?: throw McpToolException("方法 $methodName 无 functionOffset（可能是内联/未定位）")
                label = "${method._className}.$methodName"
            }

            val js = FridaScriptBuilder.hookNative(module, vaddr, label, decodeDart)
            val scriptHandle = engine.runHook(session, label, js)
            if (scriptHandle == 0L) {
                throw McpToolException("hook $label 加载失败: ${FridaBindings.takeLastScriptError()}")
            }

            buildJsonObject {
                put("scriptHandle", scriptHandle)
                put("sessionHandle", session)
                put("label", label)
                put("module", module)
                put("vaddr", "0x${vaddr.toString(16)}")
            }
        },
        tool(
            name = "frida_eval",
            description = "在 session 上加载任意 Frida JS 脚本（如内存扫描、dump、SSL unpin 模板），send() 输出走 frida_events",
            schema = mapOf(
                "sessionHandle" to "会话句柄",
                "source" to "Frida JavaScript 源码"
            )
        ) { p ->
            val session = p.longOrThrow("sessionHandle")
            val source = p.strOrThrow("source")
            val scriptHandle = engine.runHook(session, "eval", source)
            if (scriptHandle == 0L) {
                throw McpToolException("eval 脚本加载失败: ${FridaBindings.takeLastScriptError()}")
            }
            buildJsonObject { put("scriptHandle", scriptHandle) }
        },
        tool(
            name = "frida_patch_code",
            description = "运行时字节级热补丁（Memory.patchCode），与静态 patch 等价但进程内生效、可逆、无需重启目标。写前自动读原字节回传 frida_events（type=patch_read），写后回读校验（type=patch_done ok=true/false）。用途：解锁门控/改分支跳转等已验证静态补丁可行的场景。三种定位 target 方式：1) 只用 vaddr（默认 libapp.so）；2) module+vaddr；3) methodName+analysisId 查名。返回 scriptHandle。注意：目标地址若已被 Interceptor hook 过会把它改写的 trampoline 当原指令，patch 后可能 SIGSEGV——请先 detach 或避免对同地址混用；如需观察请挂旁路地址",
            schema = mapOf(
                "sessionHandle" to "会话句柄（frida_attach/frida_spawn）",
                "vaddr" to "目标指令 vaddr（十进制或 0x 十六进制；与 methodName 二选一）",
                "module" to "目标 so 名（默认 libapp.so，子串匹配）",
                "methodName" to "（与 vaddr 二选一）从静态库按名解析 vaddr，需 analysisId",
                "analysisId" to "（按名时必填）Blutter 分析记录 id",
                "bytes" to "要写入的字节 hex，如 15 00 00 14 或 15000014",
                "label" to "事件标签（默认 patch）"
            )
        ) { p ->
            val session = p.longOrThrow("sessionHandle")
            val module = p["module"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "libapp.so"
            val label = p["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "patch"
            val bytes = p.strOrThrow("bytes")
            val clean = bytes.replace(" ", "").replace("\t", "").replace("\n", "").replace("0x", "")
            if (clean.isEmpty() || clean.length % 2 != 0 || !clean.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) {
                throw McpToolException("bytes 非法：需为偶数长度 hex，如 '15 00 00 14'")
            }

            val vaddr = p["vaddr"]?.jsonPrimitive?.contentOrNull?.trim()?.let { raw ->
                if (raw.startsWith("0x") || raw.startsWith("0X")) raw.substring(2).toLongOrNull(16)
                else if (raw.endsWith("h")) raw.dropLast(1).toLongOrNull(16)
                else raw.toLongOrNull()
            } ?: run {
                val methodName = p.strOrThrow("methodName")
                val analysisId = p.longOrThrow("analysisId")
                dartMethodDao.getMethodWithClassByName(analysisId, methodName)
                    ?.method?.functionOffset
                    ?: throw McpToolException("analysisId=$analysisId 下未找到方法 $methodName")
            }

            val js = FridaScriptBuilder.patchBytes(module, vaddr, clean, label)
            val scriptHandle = engine.runHook(session, "patch:$label", js)
            if (scriptHandle == 0L) {
                throw McpToolException("patch 脚本加载失败: ${FridaBindings.takeLastScriptError()}")
            }
            buildJsonObject {
                put("scriptHandle", scriptHandle)
                put("sessionHandle", session)
                put("label", label)
                put("module", module)
                put("vaddr", "0x${vaddr.toString(16)}")
                put("bytes", clean)
                put("note", "patch 结果（原字节/写后校验）在 frida_events 的 patch_read / patch_done 事件里")
            }
        },
        tool(
            name = "frida_read_code",
            description = "读取目标进程内存字节（读回校验 / 快照确认静态 patch 是否已生效）。返回 scriptHandle，结果在 frida_events 的 read_done（type=read_done bytes）事件里",
            schema = mapOf(
                "sessionHandle" to "会话句柄",
                "vaddr" to "目标 vaddr（十进制或 0x 十六进制）",
                "module" to "目标 so 名（默认 libapp.so，子串匹配）",
                "size" to "字节数 1..64（默认 8）",
                "label" to "事件标签（默认 read）"
            )
        ) { p ->
            val session = p.longOrThrow("sessionHandle")
            val module = p["module"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "libapp.so"
            val label = p["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "read"
            val size = p["size"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()?.coerceIn(1, 64) ?: 8
            val vaddr = p["vaddr"]?.jsonPrimitive?.contentOrNull?.trim()?.let { raw ->
                if (raw.startsWith("0x") || raw.startsWith("0X")) raw.substring(2).toLongOrNull(16)
                else if (raw.endsWith("h")) raw.dropLast(1).toLongOrNull(16)
                else raw.toLongOrNull()
            } ?: throw McpToolException("缺少 vaddr")
            val js = FridaScriptBuilder.readBytes(module, vaddr, size, label)
            val scriptHandle = engine.runHook(session, "read:$label", js)
            if (scriptHandle == 0L) {
                throw McpToolException("read 脚本加载失败: ${FridaBindings.takeLastScriptError()}")
            }
            buildJsonObject {
                put("scriptHandle", scriptHandle)
                put("sessionHandle", session)
                put("label", label)
                put("module", module)
                put("vaddr", "0x${vaddr.toString(16)}")
                put("size", size)
                put("note", "读取结果在 frida_events 的 read_done 事件里")
            }
        },
        tool(
            name = "frida_scripts",
            description = "列出「Hook 脚本」页已落地的全部脚本（内置预设 + 自定义）：id/name/description/isPreset/source 行数/updatedAt。配合 frida_use_script 按 id 加载",
        ) { _ ->
            buildJsonArray {
                hookScriptRepository.observeAll().first().forEach { s ->
                    addJsonObject {
                        put("id", s.id)
                        put("name", s.name)
                        put("description", s.description)
                        put("isPreset", s.isPreset)
                        put("lines", s.source.lineSequence().count())
                        put("updatedAt", s.updatedAt)
                    }
                }
            }
        },
        tool(
            name = "frida_use_script",
            description = "加载「Hook 脚本」页里按 id 保存的脚本到 session。可选覆盖模板参数：传 module/vaddr/label 时会替换脚本里 MODULE_TPL/VADDR_TPL/LABEL_TPL 占位符（预设模板即插即用，无需先改管理页）；对不含占位符的自定义脚本调用不受影响。send() 输出走 frida_events",
            schema = mapOf(
                "sessionHandle" to "会话句柄",
                "scriptId" to "脚本 id（来自 frida_scripts）",
                "module" to "可选：覆盖目标 so 名（如 libapp.so）",
                "vaddr" to "可选：覆盖函数 vaddr（十进制或 0x 十六进制，来自 get_method/反混淆工具）",
                "label" to "可选：覆盖事件标签"
            )
        ) { p ->
            val session = p.longOrThrow("sessionHandle")
            val scriptId = p.longOrThrow("scriptId")
            val script = hookScriptRepository.getById(scriptId)
                ?: throw McpToolException("scriptId=$scriptId 不存在（先 frida_scripts 查看）")

            val module = p["module"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val label = p["label"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            val vaddr = p["vaddr"]?.jsonPrimitive?.contentOrNull?.trim()?.let { raw ->
                if (raw.startsWith("0x") || raw.startsWith("0X")) raw.substring(2).toLongOrNull(16)
                else if (raw.endsWith("h")) raw.dropLast(1).toLongOrNull(16)
                else raw.toLongOrNull()
            }

            val source = FridaScriptBuilder.fillTemplate(
                source = script.source,
                module = module,
                vaddr = vaddr,
                label = label,
            )

            val scriptHandle = engine.runHook(session, script.name, source)
            if (scriptHandle == 0L) {
                throw McpToolException("脚本「${script.name}」加载失败: ${FridaBindings.takeLastScriptError()}")
            }
            buildJsonObject {
                put("scriptHandle", scriptHandle)
                put("sessionHandle", session)
                put("scriptId", script.id)
                put("name", script.name)
                put("label", script.name)
                put("module", module ?: "")
                put("vaddr", vaddr?.let { "0x${it.toString(16)}" } ?: "")
                put("templated", module != null || vaddr != null || label != null)
            }
        },
        tool(
            name = "frida_resume",
            description = "让 spawn 后 gating 暂停的进程继续执行（布好 hook 后调用）",
            schema = mapOf("pid" to "进程 pid")
        ) { p ->
            val pid = p.longOrThrow("pid")
            buildJsonObject { put("resumed", engine.resume(pid)) }
        },
        tool(
            name = "frida_events",
            description = "拉取 hook 命中/脚本日志事件（最近 limit 条，可过滤 session/script）。事件 json 含 type=enter/leave/hook/system，args/retval 等",
            schema = mapOf(
                "sessionHandle" to "可选过滤：会话句柄",
                "scriptHandle" to "可选过滤：脚本句柄",
                "limit" to "返回条数上限（默认 100）"
            )
        ) { p ->
            val session = p["sessionHandle"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val script = p["scriptHandle"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            val limit = p["limit"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 100
            buildJsonArray {
                engine.events(session, script, limit).forEach { e ->
                    addJsonObject {
                        put("ts", e.ts)
                        put("sessionHandle", e.sessionHandle)
                        put("scriptHandle", e.scriptHandle)
                        put("message", e.json)
                    }
                }
            }
        },
        tool(
            name = "frida_clear_events",
            description = "清空事件环形缓冲，便于聚焦新一轮命中（之后 frida_events 只返回新事件）",
        ) { _ ->
            engine.clearEvents()
            buildJsonObject { put("cleared", true) }
        },
        tool(
            name = "frida_post",
            description = "向已加载脚本发送 rpc 消息（脚本 rpc.exports 入口 / 脚本内 recv 通信）。json 原样传给脚本",
            schema = mapOf(
                "scriptHandle" to "脚本句柄（来自 frida_hook/frida_eval/frida_use_script）",
                "json" to "要发送的 JSON 字符串"
            )
        ) { p ->
            val scriptHandle = p.longOrThrow("scriptHandle")
            val json = p.strOrThrow("json")
            buildJsonObject { put("posted", engine.post(scriptHandle, json)) }
        },
        tool(
            name = "frida_unload",
            description = "卸载已加载的脚本（回收 Interceptor，释放句柄映射）。脚本卸载后其事件不再产生",
            schema = mapOf("scriptHandle" to "脚本句柄")
        ) { p ->
            val scriptHandle = p.longOrThrow("scriptHandle")
            buildJsonObject { put("unloaded", engine.unloadScript(scriptHandle)) }
        },
        tool(
            name = "frida_sessions",
            description = "当前活动的 Frida 会话列表：[{sessionHandle,pid,createdAt}]",
        ) { _ ->
            buildJsonArray {
                engine.sessionsSnapshot().forEach { s ->
                    addJsonObject {
                        put("sessionHandle", s.sessionHandle)
                        put("pid", s.pid)
                        put("pkg", s.pkg ?: "")
                        put("createdAt", s.createdAt)
                    }
                }
            }
        },
        tool(
            name = "frida_detach",
            description = "detach 会话（卸载其脚本并释放 session）",
            schema = mapOf("sessionHandle" to "会话句柄")
        ) { p ->
            val session = p.longOrThrow("sessionHandle")
            buildJsonObject { put("detached", engine.detach(session)) }
        },
        tool(
            name = "frida_kill",
            description = "强杀目标进程（root，经 frida-server）",
            schema = mapOf("pid" to "进程 pid")
        ) { p ->
            val pid = p.longOrThrow("pid")
            buildJsonObject { put("killed", engine.kill(pid)) }
        },
    )

    // ---------- 工具构造辅助 ----------

    private fun tool(
        name: String,
        description: String,
        schema: Map<String, String> = emptyMap(),
        handler: suspend (JsonObject) -> JsonElement,
    ): McpToolHandlers.McpTool {
        val props = buildJsonObject {
            schema.forEach { (k, doc) ->
                putJsonObject(k) { put("type", "string"); put("description", doc) }
            }
        }
        val inputSchema = buildJsonObject {
            put("type", "object")
            put("properties", props)
        }
        return McpToolHandlers.McpTool(name, description, inputSchema, handler)
    }

    private fun JsonObject.longOrThrow(key: String): Long =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: throw McpToolException("缺少数字参数: $key")

    private fun JsonObject.strOrThrow(key: String): String =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw McpToolException("缺少字符串参数: $key")
}