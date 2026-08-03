package com.ai.fler.core.mcp

import com.ai.fler.core.analysis.EmulationSession
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 仿真（Unicorn）能力暴露给 MCP 服务（M5）。
 *
 * 所有工具带 `emu_` 前缀，通过 [EmulationSession] 门面操作；
 * soPath 是会话键（同路径复用会话，与 UI 的 EmulationTab 共享同一门面）。
 *
 * 典型流程：
 * 1. `emu_open(soPath)` 打开仿真会话（PT_LOAD 装载 + 栈/heap/哨兵映射）
 * 2. `emu_call_function(soPath, function, args)` 高层调用，返回 x0
 *    —— 或用 `emu_run` / `emu_step` / `emu_read_registers` 手动控制
 * 3. `emu_close(soPath)` 释放
 */
@Singleton
class EmulationMcpToolRegistry @Inject constructor(
    private val session: EmulationSession
) {

    companion object {
        private const val TOOL_PREFIX = "emu_"
    }

    fun buildTools(): Map<String, McpToolHandlers.McpTool> {
        val list = mutableListOf<McpToolHandlers.McpTool>()

        // ---------- 会话生命周期 ----------

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "open",
            description = "打开 so 的仿真会话（Unicorn：PT_LOAD 装载 + 栈/heap/哨兵内存）；同路径复用",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            if (!session.isAvailable) {
                return@McpTool buildJsonObject {
                    put("ok", false)
                    put("reason", "仿真引擎不可用（Unicorn 未编译进当前构建）")
                }
            }
            val handle = session.open(path)
            buildJsonObject {
                put("ok", handle != null)
                if (handle == null) put("reason", "会话打开失败（文件不存在或非 arm64 ELF）")
                else put("soPath", path)
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "close",
            description = "关闭指定 so 的仿真会话；soPath 空则关闭全部",
            inputSchema = objProps(
                "soPath" to strType(false, "so 文件绝对路径；空=关闭全部会话")
            )
        ) { p ->
            val path = p.str("soPath")
            if (path.isNullOrBlank()) {
                session.openPaths().forEach { session.close(it) }
            } else {
                session.close(path)
            }
            buildJsonObject { put("ok", true) }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "list_sessions",
            description = "列出当前打开的仿真会话与引擎可用性",
            inputSchema = objProps()
        ) { _ ->
            val paths = session.openPaths()
            buildJsonObject {
                put("engineAvailable", session.isAvailable)
                putJsonArray("sessions") { paths.forEach { add(it) } }
            }
        }

        // ---------- 高层函数调用 ----------

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "call_function",
            description = "调用 so 内函数：参数写 x0-x7（最多 8 个），LR 设哨兵，运行至返回/断点/超时，返回 x0。" +
                "function 支持函数名或 hex 地址；会话未打开时自动 open",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径"),
                "function" to strType(true, "函数名或 hex 地址（如 0x1234）"),
                "args" to strType(false, "参数 JSON 数组，hex 字符串或十进制，如 [\"0x1\", \"31\"]；最多 8 个"),
                "timeoutMs" to intType(false, def = 30000, "超时毫秒"),
                "maxInstrs" to intType(false, def = 20000000, "防失控指令上限")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val fn = p.str("function") ?: throw McpToolException("function 缺失")
            val args = p.parseArgList("args")
            val timeoutMs = (p.int("timeoutMs") ?: 30_000).toLong().coerceAtLeast(1_000L)
            val maxInstrs = (p.int("maxInstrs") ?: 20_000_000).toLong().coerceAtLeast(1L)
            val result = session.callFunction(path, fn, args, timeoutMs, maxInstrs)
                ?: return@McpTool buildJsonObject {
                    put("ok", false)
                    put("reason", "调用失败：函数未找到、引擎不可用或会话无效")
                }
            buildJsonObject {
                put("ok", true)
                put("function", result.functionName)
                put("functionAddress", "0x${result.functionAddress.toString(16)}")
                put("returnValue", result.returnValue)
                put("returnValueHex", result.returnValueUnsigned)
                put("stopReason", result.stopReason.name)
                put("instructionCount", result.instructionCount)
                put("pc", "0x${result.pc.toString(16)}")
            }
        }

        // ---------- 执行控制 ----------

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "run",
            description = "从当前 PC 继续执行，直到断点/超时/指令上限/手动 stop",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径"),
                "instrCount" to intType(false, def = 100000, "最大指令数；0=不限"),
                "timeoutMs" to intType(false, def = 30000, "超时毫秒；0=不限")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val instrCount = (p.int("instrCount") ?: 100_000).toLong().coerceAtLeast(0L)
            val timeoutMs = (p.int("timeoutMs") ?: 30_000).toLong().coerceAtLeast(0L)
            val result = session.run(path, instrCount, timeoutMs)
                ?: return@McpTool sessionNotOpen(path)
            buildJsonObject {
                put("ok", true)
                put("stoppedBy", result.stoppedBy.name)
                put("pc", "0x${result.pc.toString(16)}")
                put("instructionCount", result.instructionCount)
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "step",
            description = "单步执行一条指令",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val result = session.step(path) ?: return@McpTool sessionNotOpen(path)
            buildJsonObject {
                put("ok", true)
                put("stoppedBy", result.stoppedBy.name)
                put("pc", "0x${result.pc.toString(16)}")
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "stop",
            description = "请求中断正在运行的仿真（异步生效，下一次 hook 检查点停止）",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            session.requestStop(path)
            buildJsonObject { put("ok", true) }
        }

        // ---------- 寄存器 ----------

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "read_registers",
            description = "读取全部寄存器快照（x0-x30/sp/lr/pc/标志）",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            if (!session.isOpen(path)) return@McpTool sessionNotOpen(path)
            val snapshot = session.readRegisters(path)
            buildJsonObject {
                put("ok", true)
                snapshot.values.forEach { (name, value) ->
                    put(name, "0x${java.lang.Long.toUnsignedString(value, 16)}")
                }
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "write_register",
            description = "写单个寄存器（如 x0/sp/lr/pc）",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径"),
                "name" to strType(true, "寄存器名，如 x0 / sp / lr / pc"),
                "value" to strOrLongType(true, "hex 或十进制值")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val name = p.str("name") ?: throw McpToolException("name 缺失")
            val value = p.parseHexOrDec("value") ?: throw McpToolException("value 非法")
            val ok = session.writeRegister(path, name, value)
            buildJsonObject { put("ok", ok) }
        }

        // ---------- 内存 ----------

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "read_memory",
            description = "读取仿真内存（hex 输出）",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径"),
                "address" to strOrLongType(true, "虚拟地址"),
                "size" to intType(false, def = 256, "字节数，上限 64KB")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val addr = p.parseHexOrDec("address") ?: throw McpToolException("address 非法")
            val size = (p.int("size") ?: 256).coerceIn(1, 64 * 1024)
            val bytes = session.readMemory(path, addr, size.toLong())
            buildJsonObject {
                put("ok", bytes.isNotEmpty())
                put("address", "0x${addr.toString(16)}")
                put("size", bytes.size)
                put("hex", bytes.joinToString(" ") { b ->
                    b.toUByte().toString(16).padStart(2, '0')
                })
            }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "write_memory",
            description = "写仿真内存。hex 字符串以空格分隔",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径"),
                "address" to strOrLongType(true, "虚拟地址"),
                "hex" to strType(true, "以空格分隔的十六进制，如 C0 03 5F D6")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val addr = p.parseHexOrDec("address") ?: throw McpToolException("address 非法")
            val bytes = parseHexBytes(p.str("hex")) ?: throw McpToolException("hex 缺失或非法")
            val ok = session.writeMemory(path, addr, bytes)
            buildJsonObject { put("ok", ok); put("wrote", bytes.size) }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "map_memory",
            description = "手动映射一段仿真内存（perms 位掩码：1=X 2=W 4=R，默认 7=RWX）",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径"),
                "address" to strOrLongType(true, "起始虚拟地址（页对齐）"),
                "size" to strOrLongType(true, "字节数（页对齐向上取整）"),
                "perms" to intType(false, def = 7, "权限位掩码 1=X 2=W 4=R")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val addr = p.parseHexOrDec("address") ?: throw McpToolException("address 非法")
            val size = p.parseHexOrDec("size") ?: throw McpToolException("size 非法")
            val perms = (p.int("perms") ?: 0b111) and 0b111
            val ok = session.mapMemory(path, addr, size, perms)
            buildJsonObject { put("ok", ok) }
        }

        // ---------- 断点 ----------

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "breakpoint_add",
            description = "添加断点（执行到该地址时停止，stopReason=BREAKPOINT）",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径"),
                "address" to strOrLongType(true, "断点虚拟地址")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val addr = p.parseHexOrDec("address") ?: throw McpToolException("address 非法")
            val ok = session.addBreakpoint(path, addr)
            buildJsonObject { put("ok", ok); put("address", "0x${addr.toString(16)}") }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "breakpoint_remove",
            description = "移除断点",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径"),
                "address" to strOrLongType(true, "断点虚拟地址")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val addr = p.parseHexOrDec("address") ?: throw McpToolException("address 非法")
            val ok = session.removeBreakpoint(path, addr)
            buildJsonObject { put("ok", ok) }
        }

        list += McpToolHandlers.McpTool(
            name = TOOL_PREFIX + "breakpoint_list",
            description = "列出当前断点",
            inputSchema = objProps(
                "soPath" to strType(true, "so 文件绝对路径")
            )
        ) { p ->
            val path = p.str("soPath") ?: throw McpToolException("soPath 缺失")
            val bps = session.listBreakpoints(path)
            buildJsonObject {
                put("count", bps.size)
                putJsonArray("breakpoints") {
                    bps.forEach { add("0x${it.toString(16)}") }
                }
            }
        }

        return list.associateBy { it.name }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun sessionNotOpen(path: String): JsonObject = buildJsonObject {
        put("ok", false)
        put("reason", "会话未打开：先调用 emu_open（soPath=$path）")
    }

    /** 空格分隔 hex 字符串 → 字节数组；非法返回 null。 */
    private fun parseHexBytes(hex: String?): ByteArray? {
        if (hex.isNullOrBlank()) return null
        return try {
            val bytes = hex.trim().split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .map { it.toUByte(16).toByte() }
                .toByteArray()
            bytes.takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    /** args 参数：JSON 数组（hex 字符串或十进制），如 ["0x1","31"]。 */
    private fun JsonObject.parseArgList(key: String): List<Long> {
        val elem: JsonElement = this[key] ?: return emptyList()
        val items = when (elem) {
            is kotlinx.serialization.json.JsonArray -> elem
            is JsonPrimitive -> {
                // 兼容客户端把数组序列化成字符串传入
                runCatching {
                    kotlinx.serialization.json.Json.parseToJsonElement(elem.content)
                        .jsonArray
                }.getOrNull() ?: return emptyList()
            }
            else -> return emptyList()
        }
        return items.mapNotNull { item ->
            val s = (item as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val t = s.trim()
            if (t.startsWith("0x", ignoreCase = true) || t.startsWith("-0x", ignoreCase = true)) {
                val neg = t.startsWith('-')
                val body = t.removePrefix("-").substring(2)
                body.toLongOrNull(16)?.let { if (neg) -it else it }
            } else {
                t.toLongOrNull(10) ?: t.toLongOrNull(16)
            }
        }.take(8)
    }

    // Schema helpers（与 EngineMcpToolRegistry 同风格）

    private fun objProps(vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            props.forEach { (k, v) -> put(k, v) }
        }
        val required = props.mapNotNull { p ->
            val o = p.second
            val isRequired = (o["_required"] as? JsonPrimitive)?.contentOrNull == "true"
            if (isRequired) p.first else null
        }
        if (required.isNotEmpty()) {
            putJsonArray("required") { required.forEach { add(it) } }
        }
    }

    private fun strType(required: Boolean, description: String = ""): JsonObject = buildJsonObject {
        put("type", "string")
        if (description.isNotBlank()) put("description", description)
        if (required) put("_required", true)
    }
    private fun intType(schemaRequired: Boolean, def: Int? = null, description: String = ""): JsonObject =
        buildJsonObject {
            put("type", "integer")
            if (description.isNotBlank()) put("description", description)
            if (def != null) put("default", def)
            if (schemaRequired) put("_required", true)
        }
    private fun strOrLongType(required: Boolean, description: String = ""): JsonObject =
        buildJsonObject {
            // 允许 hex 字符串或十进制整数；MCP 实际统一用 string 更稳
            put("type", "string")
            if (description.isNotBlank()) put("description", description)
            if (required) put("_required", true)
        }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    private fun JsonObject.parseHexOrDec(key: String): Long? {
        val s = str(key) ?: return null
        return s.trim().let {
            if (it.startsWith("0x", ignoreCase = true)) it.drop(2).toLongOrNull(16)
            else it.toLongOrNull(16) ?: it.toLongOrNull()
        }
    }
}
