package com.ai.fler.core.gadget

import android.content.Context
import android.os.Build
import android.util.Base64
import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.service.ApkRepacker
import com.android.apksig.ApkVerifier
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 非 root Frida gadget 重打包服务（编排 [GadgetInjector] + [ApkRepacker]）：
 *
 * 1. 解析源 APK（ABI / 原签名证书 / manifest 信息）
 * 2. 下载各 ABI frida-gadget（[FridaGadgetDownloader]，缓存复用）
 * 3. [GadgetInjector]：dex clinit 注入 loadLibrary + AXML extractNativeLibs=true
 *    + gadget/config/script 三件套 + config JSON（listen 或 script 模式）
 * 4. [ApkRepacker]：ZIP 重建（替换+新增条目）+ v1/v2/v3 重签名
 * 5. 持久化注入元数据（对抗参数、原签名指纹）到 filesDir/gadget_injections/
 */
@Singleton
class GadgetRepacker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLogger: AppLogger,
    private val apkRepacker: ApkRepacker,
    private val gadgetDownloader: FridaGadgetDownloader,
) {

    /** 注入选项（[repack] 入参）。 */
    data class Options(
        val interaction: GadgetConfig.Interaction = GadgetConfig.Interaction.Listen(),
        val gadgetLibBase: String = "libgadget",
        /** APK 无 lib/<abi> 时注入的设备 ABI 顺序（默认本机 SUPPORTED_ABIS）。 */
        val deviceAbis: List<String> = Build.SUPPORTED_ABIS?.toList()
            ?: listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86"),
        /** 无原生库 APK 的 ABI 取 deviceAbis 首个受支持者。 */
        val preferSingleAbiForPureJava: Boolean = true,
    )

    /** 重打包结果。 */
    data class GadgetRepackResult(
        val ok: Boolean,
        val output: ApkRepacker.RepackResult = ApkRepacker.RepackResult(ok = false),
        val packageName: String = "",
        val entryClass: String = "",
        val injectedDex: String = "",
        val abis: List<String> = emptyList(),
        val libBase: String = "",
        val alreadyInjected: Boolean = false,
        val certSpoofed: Boolean = false,
        val error: String? = null,
    )

    data class GadgetRepackOutput(
        val file: File,
        val result: GadgetRepackResult,
        val metadata: GadgetInjectionRecord? = null,
    )

    /**
     * 执行注入重打包。产物在 cacheDir 下（调用方负责删除/导出）。
     *
     * @param signOptions 签名选项（默认 v1+v2+v3）
     * @param keySource 签名密钥（默认内置 debug）
     */
    suspend fun repack(
        apkFile: File,
        options: Options,
        signOptions: ApkRepacker.SignOptions,
        keySource: ApkRepacker.KeySource,
        onProgress: suspend (Float, String) -> Unit = { _, _ -> },
    ): GadgetRepackOutput = withContext(Dispatchers.IO) {
        try {
            onProgress(0.02f, "解析源 APK")
            val apkAbis = ZipFile(apkFile).use { apk ->
                apk.entries().asSequence()
                    .filter { it.name.startsWith("lib/") && !it.isDirectory }
                    .mapNotNull { n -> n.name.removePrefix("lib/").substringBefore('/') }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }
            val gadgetAbis: List<String> = if (apkAbis.isEmpty()) {
                val dev = options.deviceAbis.firstOrNull { it in GadgetInjector.SUPPORTED_ABIS }
                    ?: "arm64-v8a"
                listOf(dev)
            } else {
                apkAbis.filter { it in GadgetInjector.SUPPORTED_ABIS }
            }
            if (gadgetAbis.isEmpty()) {
                throw IllegalStateException("APK 无 frida 支持的 ABI（$apkAbis）")
            }

            // --------------------------------------------------------------
            // 原签名证书（对抗参数；失败不阻断——仅失去签名伪装）
            // --------------------------------------------------------------
            onProgress(0.06f, "读取原 APK 签名证书")
            val certB64 = readSignerCert(apkFile)
            val pkg = ZipFile(apkFile).use { apk ->
                val bytes = apk.getInputStream(apk.getEntry("AndroidManifest.xml"))
                    .use { it.readBytes() }
                Axml.parse(bytes).manifestInfo().packageName
            } ?: throw IllegalStateException("manifest 缺少 package 名")

            // --------------------------------------------------------------
            // gadget 下载（各 ABI）
            // --------------------------------------------------------------
            val gadgetBytes = LinkedHashMap<String, ByteArray>()
            val total = gadgetAbis.size
            gadgetAbis.forEachIndexed { i, abi ->
                val so = gadgetDownloader.ensureGadget(abi) { p, stage ->
                    onProgress(0.08f + 0.32f * ((i + p) / total), stage)
                }
                gadgetBytes[abi] = so.readBytes()
            }

            // --------------------------------------------------------------
            // 注入计划
            // --------------------------------------------------------------
            onProgress(0.42f, "注入 dex / manifest")
            val antiTamper = GadgetConfig.AntiTamperParams(
                pkg = pkg,
                certB64 = certB64,
                entryCrcs = emptyMap(), // 由 plan() 填充真实指纹
            )
            val plan = ZipFile(apkFile).use { apk ->
                GadgetInjector.plan(
                    apk = apk,
                    gadgetByAbi = gadgetBytes,
                    options = GadgetInjector.Options(
                        libBase = options.gadgetLibBase,
                        interaction = options.interaction,
                        antiTamper = antiTamper,
                        scriptBytes = loadAntiTamperScript(),
                    ),
                )
            }
            val antiTamperFinal = antiTamper.copy(entryCrcs = plan.originalEntryCrcs)

            // script 模式的 config 需要带上真实 entryCrcs → 重写 config 条目
            val finalPlan = if (options.interaction is GadgetConfig.Interaction.Script && certB64 != null) {
                rebuildConfig(plan, antiTamperFinal)
            } else plan

            // --------------------------------------------------------------
            // ZIP 重建 + 签名
            // --------------------------------------------------------------
            onProgress(0.5f, "重建 ZIP 与签名")
            val repack = apkRepacker.repackWithEntriesToTempFile(
                apkFile = apkFile,
                entryOverrides = finalPlan.overrides,
                entryAdds = finalPlan.adds,
                entryName = "${plan.entryClass} <clinit>",
                signOptions = signOptions,
                keySource = keySource,
            ) { frac, stage ->
                onProgress(0.5f + 0.48f * frac, stage)
            }
            if (!repack.result.ok) {
                repack.file.delete()
                throw IllegalStateException(repack.result.error ?: "回打失败")
            }

            // --------------------------------------------------------------
            // 元数据持久化
            // --------------------------------------------------------------
            val record = GadgetInjectionRecord(
                packageName = pkg,
                apkPath = apkFile.absolutePath,
                entryClass = plan.entryClass,
                injectedDexEntry = plan.injectedDexEntry,
                abis = plan.abis,
                libBase = plan.libBase,
                mode = modeName(options.interaction),
                port = (options.interaction as? GadgetConfig.Interaction.Listen)?.port,
                onLoad = (options.interaction as? GadgetConfig.Interaction.Listen)?.onLoad,
                certB64 = certB64,
                entryCrcs = plan.originalEntryCrcs,
                configJson = plan.configJson,
                gadgetVersion = FridaGadgetDownloader.GADGET_VERSION,
                createdAt = System.currentTimeMillis(),
            )
            runCatching { persistRecord(record) }
                .onFailure { appLogger.warn(TAG, "注入元数据持久化失败: ${it.message}") }

            onProgress(1f, "完成")
            appLogger.info(
                TAG,
                "gadget 注入完成: pkg=$pkg 入口=${plan.entryClass} ABI=${plan.abis} " +
                    "库=${plan.libBase} 模式=${modeName(options.interaction)} " +
                    "签名=${repack.result.schemes.ifEmpty { listOf("无") }} " +
                    "输出=${repack.result.outputSize / 1048576}MB"
            )
            GadgetRepackOutput(
                file = repack.file,
                result = GadgetRepackResult(
                    ok = true,
                    output = repack.result,
                    packageName = pkg,
                    entryClass = plan.entryClass,
                    injectedDex = plan.injectedDexEntry,
                    abis = plan.abis,
                    libBase = plan.libBase,
                    alreadyInjected = plan.alreadyInjected,
                    certSpoofed = certB64 != null,
                ),
                metadata = record,
            )
        } catch (e: Exception) {
            appLogger.error(TAG, "gadget 注入失败: ${e.message} (${e.javaClass.name})")
            GadgetRepackOutput(
                file = File(context.cacheDir, "gadget_failed.apk"),
                result = GadgetRepackResult(ok = false, error = e.message ?: e.javaClass.name),
            )
        }
    }

    // ==================================================================
    // 内部
    // ==================================================================

    private fun modeName(interaction: GadgetConfig.Interaction): String = when (interaction) {
        is GadgetConfig.Interaction.Listen -> "listen"
        is GadgetConfig.Interaction.Script -> "script"
    }

    /** script 模式下把真实 entryCrcs 写进 config（重生成 config 条目）。 */
    private fun rebuildConfig(
        plan: GadgetInjector.InjectionPlan,
        antiTamper: GadgetConfig.AntiTamperParams,
    ): GadgetInjector.InjectionPlan {
        val configJson = GadgetConfig.buildJson(
            plan.libBase,
            GadgetConfig.Interaction.Script(),
            antiTamper,
        )
        val adds = LinkedHashMap(plan.adds)
        for (abi in plan.abis) {
            adds[GadgetConfig.configEntry(abi, plan.libBase)] = configJson.toByteArray(Charsets.UTF_8)
        }
        return plan.copy(adds = adds, configJson = configJson)
    }

    /** 读取原 APK 签名证书（DER → base64）；无签名/校验失败返回 null。 */
    private fun readSignerCert(apkFile: File): String? = try {
        val verifier = ApkVerifier.Builder(apkFile).build()
        val result = verifier.verify()
        val certs = result.signerCertificates
        val cert = certs?.firstOrNull() ?: return null
        Base64.encodeToString(cert.encoded, Base64.NO_WRAP)
    } catch (e: Exception) {
        appLogger.info(TAG, "原 APK 签名证书读取失败（跳过签名伪装）: ${e.message}")
        null
    }

    /** assets/gadget/anti_tamper.js（frida-compile 产物，含 Java bridge）。 */
    private fun loadAntiTamperScript(): ByteArray? = try {
        context.assets.open("gadget/anti_tamper.js").use { it.readBytes() }
    } catch (e: Exception) {
        appLogger.warn(TAG, "对抗脚本资产缺失: ${e.message}")
        null
    }

    private fun persistRecord(record: GadgetInjectionRecord) {
        val dir = File(context.filesDir, "gadget_injections").apply { mkdirs() }
        val safe = record.packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        File(dir, "$safe.json").writeText(json.encodeToString(record))
    }

    /** 查询指定包名的注入记录（attach 后加载对抗脚本用）。 */
    fun injectionRecord(packageName: String): GadgetInjectionRecord? {
        val dir = File(context.filesDir, "gadget_injections")
        val safe = packageName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val f = File(dir, "$safe.json")
        if (!f.isFile) return null
        return runCatching { json.decodeFromString<GadgetInjectionRecord>(f.readText()) }
            .getOrNull()
    }

    companion object {
        private const val TAG = "GadgetRepacker"
        private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    }
}

/** gadget 注入元数据（持久化，供 attach 后取对抗参数）。 */
@Serializable
data class GadgetInjectionRecord(
    val packageName: String,
    val apkPath: String,
    val entryClass: String,
    val injectedDexEntry: String,
    val abis: List<String>,
    val libBase: String,
    val mode: String,
    val port: Int? = null,
    val onLoad: String? = null,
    val certB64: String? = null,
    val entryCrcs: Map<String, List<Long>> = emptyMap(),
    val configJson: String,
    val gadgetVersion: String,
    val createdAt: Long,
) {
    val listenAddress: String get() = "127.0.0.1:${port ?: 27042}"
}
