package com.ai.fler.core.gadget

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * frida-gadget 配置（lib<base>.config.so）与注入产物命名约定。
 *
 * 命名规则（见 https://frida.re/docs/gadget/）：
 * - 安装器只解压 lib/<abi>/ 下 `lib` 前缀 + `.so` 后缀的文件，
 *   因此 gadget / config / script 三个条目都必须满足该模式：
 *   libgadget.so / libgadget.config.so / libgadget.script.so
 * - gadget 按自身路径查找 `<自身名>.config` / `<自身名>.config.so`
 * - script 交互的 path 支持相对 gadget 所在目录的路径
 */
object GadgetConfig {

    /** System.loadLibrary(libName) 的库名（libgadget.so → "gadget"）。 */
    fun libName(libBase: String): String = libBase.removePrefix("lib")

    /** APK 内 gadget 条目名。 */
    fun gadgetEntry(abi: String, libBase: String) = "lib/$abi/$libBase.so"

    /** APK 内 gadget 配置条目名。 */
    fun configEntry(abi: String, libBase: String) = "lib/$abi/$libBase.config.so"

    /** APK 内对抗脚本条目名（script 模式加载 / listen 模式备用）。 */
    fun scriptEntry(abi: String, libBase: String) = "lib/$abi/$libBase.script.so"

    // ==================================================================
    // 交互模式
    // ==================================================================

    /** gadget 交互模式。 */
    sealed interface Interaction {
        /**
         * listen：在设备本机暴露 frida-server 兼容接口（默认 127.0.0.1:27042）。
         *
         * @param onLoad wait=启动冻结至 attach（早期插桩，FLER/frida CLI 直连）
         *               resume=立即放行（后续 attach）
         * @param onPortConflict pick-next=端口冲突顺延
         */
        data class Listen(
            val address: String = "127.0.0.1",
            val port: Int = 27042,
            val onLoad: String = "wait",
            val onPortConflict: String = "pick-next",
        ) : Interaction

        /**
         * script：加载 lib 目录内的对抗脚本（签名伪装/完整性指纹）后自动放行，
         * 无需 attach 即可绕过启动期自校验。
         */
        data class Script(val onChange: String = "ignore") : Interaction
    }

    /** 对抗脚本参数（嵌入 config interaction.parameters）。 */
    data class AntiTamperParams(
        val pkg: String,
        val certB64: String?,
        /** 被修改条目的原始 ZIP 指纹：name → [crc, size, csize]。 */
        val entryCrcs: Map<String, List<Long>>,
    )

    /**
     * 生成 gadget 配置 JSON。
     *
     * @param scriptParams script 模式时非 null（嵌入对抗参数）
     */
    fun buildJson(
        libBase: String,
        interaction: Interaction,
        scriptParams: AntiTamperParams?,
    ): String {
        val interactionObj: JsonObject = when (interaction) {
            is Interaction.Listen -> buildJsonObject {
                put("type", "listen")
                put("address", interaction.address)
                put("port", interaction.port)
                put("on_port_conflict", interaction.onPortConflict)
                put("on_load", interaction.onLoad)
            }
            is Interaction.Script -> buildJsonObject {
                put("type", "script")
                put("path", "$libBase.script.so")
                put("on_change", interaction.onChange)
                put("parameters", paramsJson(scriptParams))
            }
        }
        val root = buildJsonObject { put("interaction", interactionObj) }
        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    private fun paramsJson(params: AntiTamperParams?): JsonElement = if (params == null) {
        buildJsonObject { }
    } else {
        buildJsonObject {
            put("pkg", params.pkg)
            params.certB64?.let { put("certB64", it) }
            put("entryCrcs", buildJsonObject {
                params.entryCrcs.forEach { (name, v) ->
                    put(name, JsonArray(v.map { JsonPrimitive(it) }))
                }
            })
        }
    }

    private val prettyJson = Json { prettyPrint = true }
}
