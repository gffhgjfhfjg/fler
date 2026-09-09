package com.ai.fler.core.gadget

import java.util.zip.ZipFile

/**
 * gadget 注入编排（纯逻辑，无 Android 依赖，JVM 可测）：
 *
 * 输入 APK + 各 ABI 的 gadget .so → 产出对 APK 的修改计划
 * （条目替换表 + 条目新增表），由 [com.ai.fler.core.service.ApkRepacker]
 * 执行 ZIP 重建与重签名。
 *
 * 步骤：
 * 1. 解析二进制 manifest（包名/入口类/extractNativeLibs）
 * 2. 确定注入 ABI（APK 现有 lib/<abi> ∩ 提供的 gadget；无 lib 目录时全部注入）
 * 3. 检查 gadget 库名冲突，必要时顺延（libgadget → libgadget2 …）
 * 4. 对 classes.dex / classes2.dex … 逐个尝试把
 *    System.loadLibrary("<lib>") 注入入口类 <clinit>（多 dex 定位后只改命中者）
 * 5. AXML 改写 android:extractNativeLibs=true（保证安装器解压三件套）
 * 6. 生成 gadget config / script 条目与原条目 CRC 指纹（供对抗脚本伪装）
 */
object GadgetInjector {

    /** gadget 支持的 ABI → frida release 资产 ABI。 */
    val SUPPORTED_ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")

    data class Options(
        /** gadget 库基名（最终条目 lib/<abi>/<libBase>.so）。 */
        val libBase: String = "libgadget",
        val interaction: GadgetConfig.Interaction = GadgetConfig.Interaction.Listen(),
        /** 对抗参数（script 模式嵌入 config；listen 模式用于持久化供 attach 后使用）。 */
        val antiTamper: GadgetConfig.AntiTamperParams? = null,
        /** 对抗脚本字节（assets/gadget/anti_tamper.js）。null = 不加 script 条目。 */
        val scriptBytes: ByteArray? = null,
    )

    /**
     * 注入计划。overrides/adds 均为「APK 条目名 → 新内容」。
     */
    internal data class InjectionPlan(
        val overrides: LinkedHashMap<String, ByteArray>,
        val adds: LinkedHashMap<String, ByteArray>,
        val manifestInfo: Axml.ManifestInfo,
        val entryClass: String,
        val entryClassType: String,
        val injectedDexEntry: String,
        val createdClinit: Boolean,
        val alreadyInjected: Boolean,
        val libBase: String,
        val abis: List<String>,
        /** 原条目（被修改的）ZIP 指纹，供对抗脚本与元数据持久化。 */
        val originalEntryCrcs: Map<String, List<Long>>,
        val configJson: String,
    )

    /** dex 条目名排序列表（classes.dex, classes2.dex … classes10.dex）。 */
    fun dexEntries(names: Set<String>): List<String> =
        names.filter { it == "classes.dex" || Regex("^classes\\d+\\.dex$").matches(it) }
            .sortedWith(compareBy { dexIndex(it) })

    private fun dexIndex(name: String): Int =
        if (name == "classes.dex") 1
        else name.removePrefix("classes").removeSuffix(".dex").toIntOrNull() ?: Int.MAX_VALUE

    /**
     * 生成注入计划。
     *
     * @param gadgetByAbi ABI → gadget .so 字节（需与 APK 目标 ABI 匹配）
     * @throws Axml.AxmlException / DexGadgetInjector.DexInjectException / IllegalStateException
     */
    internal fun plan(apk: ZipFile, gadgetByAbi: Map<String, ByteArray>, options: Options): InjectionPlan {
        // --------------------------------------------------------------
        // 1. manifest 解析
        // --------------------------------------------------------------
        val manifestEntry = apk.getEntry("AndroidManifest.xml")
            ?: throw IllegalStateException("APK 缺少 AndroidManifest.xml")
        val manifestBytes = apk.getInputStream(manifestEntry).use { it.readBytes() }
        val doc = Axml.parse(manifestBytes)
        val info = doc.manifestInfo()
        val entryClass = info.entryClassName
            ?: throw IllegalStateException(
                "manifest 无 Application 类与 launcher Activity，无法确定注入入口")

        // --------------------------------------------------------------
        // 2. ABI 选择
        // --------------------------------------------------------------
        val apkAbis = apk.entries().asSequence()
            .filter { it.name.startsWith("lib/") && !it.isDirectory }
            .mapNotNull { entryAbiOf(it.name) }
            .toSet()
        val targetAbis = when {
            apkAbis.isEmpty() -> gadgetByAbi.keys.toList() // 无原生库：注入调用方提供的 ABI
            else -> gadgetByAbi.keys.filter { it in apkAbis }
        }.ifEmpty { throw IllegalStateException("APK 的 ABI（$apkAbis）与提供的 gadget 不匹配") }

        // --------------------------------------------------------------
        // 3. 库名冲突检查
        // --------------------------------------------------------------
        val existingNames = apk.entries().asSequence().map { it.name }.toSet()
        var libBase = options.libBase
        var attempt = 2
        while (targetAbis.any { abi ->
                existingNames.contains(GadgetConfig.gadgetEntry(abi, libBase))
            }) {
            if (attempt > 9) throw IllegalStateException("gadget 库名冲突过多，无法分配")
            libBase = options.libBase + attempt
            attempt++
        }

        // --------------------------------------------------------------
        // 4. dex 注入
        // --------------------------------------------------------------
        val classType = DexGadgetInjector.toClassType(entryClass)
        val allEntries = apk.entries().asSequence().map { it.name }.toSet()
        var injected: DexGadgetInjector.Result? = null
        var injectedEntry: String? = null
        val overrides = LinkedHashMap<String, ByteArray>()

        for (dexName in dexEntries(allEntries)) {
            val bytes = apk.getInputStream(apk.getEntry(dexName)).use { it.readBytes() }
            val r = DexGadgetInjector.inject(bytes, classType, GadgetConfig.libName(libBase))
            if (r != null) {
                injected = r
                injectedEntry = dexName
                if (!r.alreadyInjected) overrides[dexName] = r.dexBytes
                break
            }
        }
        val inj = injected
            ?: throw IllegalStateException(
                "所有 dex 中均未找到入口类 $entryClass（是否加固/动态加载？）")

        // --------------------------------------------------------------
        // 5. AXML：extractNativeLibs=true
        // --------------------------------------------------------------
        if (info.extractNativeLibs != true) {
            doc.setExtractNativeLibs(true)
        }
        overrides["AndroidManifest.xml"] = doc.toBytes()

        // --------------------------------------------------------------
        // 6. gadget 三件套条目
        // --------------------------------------------------------------
        val scriptParams = options.antiTamper?.takeIf { options.interaction is GadgetConfig.Interaction.Script }
        val configJson = GadgetConfig.buildJson(libBase, options.interaction, scriptParams)
        val adds = LinkedHashMap<String, ByteArray>()
        for (abi in targetAbis) {
            val so = gadgetByAbi[abi]
                ?: throw IllegalStateException("缺少 $abi 的 gadget .so")
            adds[GadgetConfig.gadgetEntry(abi, libBase)] = so
            adds[GadgetConfig.configEntry(abi, libBase)] = configJson.toByteArray(Charsets.UTF_8)
            options.scriptBytes?.let { adds[GadgetConfig.scriptEntry(abi, libBase)] = it }
        }

        // --------------------------------------------------------------
        // 7. 被修改条目的原始 ZIP 指纹（对抗脚本伪装用）
        // --------------------------------------------------------------
        val crcs = LinkedHashMap<String, List<Long>>()
        for (name in overrides.keys) {
            val e = apk.getEntry(name) ?: continue
            crcs[name] = listOf(e.crc, e.size, e.compressedSize)
        }

        return InjectionPlan(
            overrides = overrides,
            adds = adds,
            manifestInfo = info,
            entryClass = entryClass,
            entryClassType = classType,
            injectedDexEntry = injectedEntry ?: "classes.dex",
            createdClinit = inj.createdClinit,
            alreadyInjected = inj.alreadyInjected,
            libBase = libBase,
            abis = targetAbis,
            originalEntryCrcs = crcs,
            configJson = configJson,
        )
    }

    private fun entryAbiOf(name: String): String? {
        if (!name.startsWith("lib/")) return null
        val rest = name.removePrefix("lib/")
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        return rest.substring(0, slash)
    }
}
