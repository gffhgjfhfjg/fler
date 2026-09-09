package com.ai.fler.features.gadget

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ai.fler.core.gadget.GadgetConfig
import com.ai.fler.core.gadget.GadgetRepacker
import com.ai.fler.core.service.ApkRepacker
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * gadget 注入 ViewModel（设置页「非 root Frida」入口）。
 *
 * 流程：SAF 选 APK → 复制到 cacheDir/gadget_inject/ → 下载 gadget + 注入 + 重签名 →
 * SAF CreateDocument 写出。
 */
@HiltViewModel
class GadgetInjectViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val gadgetRepacker: GadgetRepacker,
    private val apkRepacker: ApkRepacker,
) : ViewModel() {

    data class UiState(
        val running: Boolean = false,
        val stage: String = "",
        val progress: Float = 0f,
        val error: String? = null,
        val success: String? = null,
        val pickedApkName: String? = null,
        val pickedApkPath: String? = null,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val workDir: File
        get() = File(appContext.cacheDir, "gadget_inject").apply { mkdirs() }

    /** 已导入的自定义密钥参数（预填）。 */
    fun savedKeyConfig(): ApkRepacker.SavedKeyConfig? = apkRepacker.savedKeyConfig()

    /** 导入自定义密钥库（SAF 选中后复制进 App 私有目录）。 */
    fun importCustomKey(uri: Uri, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    apkRepacker.importCustomKeystore(input)
                } ?: throw IllegalStateException("无法读取所选密钥库")
                onResult(true, null)
            } catch (e: Exception) {
                onResult(false, e.message ?: "密钥库导入失败")
            }
        }
    }

    /** SAF 选中 APK：复制到 cacheDir（保持本地绝对路径语义）。 */
    fun pickApk(uri: Uri) {
        viewModelScope.launch {
            _state.value = _state.value.copy(error = null, success = null)
            try {
                val name = queryDisplayName(uri) ?: "source.apk"
                val target = File(workDir, "src.apk")
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(target).use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("无法读取所选 APK")
                }
                _state.value = _state.value.copy(
                    pickedApkName = name,
                    pickedApkPath = target.absolutePath,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "APK 读取失败: ${e.message}")
            }
        }
    }

    /**
     * 注入选项（弹窗收集）。
     */
    data class InjectSelection(
        val mode: String,          // listen | script
        val port: Int,             // listen 模式端口
        val onLoad: String,        // listen 模式 wait | resume
        val sign: Boolean,
        val v1: Boolean,
        val v2: Boolean,
        val v3: Boolean,
        val useCustomKey: Boolean,
        val alias: String,
        val storePass: String,
        val keyPass: String,
    )

    /** 执行注入并写出到 [uri]。 */
    fun injectToUri(uri: Uri, selection: InjectSelection) {
        val apkPath = _state.value.pickedApkPath
        if (apkPath == null) {
            _state.value = _state.value.copy(error = "请先选择 APK")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(
                running = true, progress = 0f, stage = "准备", error = null, success = null
            )
            var output: GadgetRepacker.GadgetRepackOutput? = null
            try {
                val interaction: GadgetConfig.Interaction = when (selection.mode) {
                    "script" -> GadgetConfig.Interaction.Script()
                    else -> GadgetConfig.Interaction.Listen(
                        port = selection.port,
                        onLoad = selection.onLoad,
                    )
                }
                val keySource = if (selection.useCustomKey) {
                    ApkRepacker.KeySource.Custom(
                        storeFile = apkRepacker.customKeystoreFile,
                        storePassword = selection.storePass,
                        keyAlias = selection.alias,
                        keyPassword = selection.keyPass,
                    )
                } else {
                    ApkRepacker.KeySource.Debug
                }
                output = withContext(Dispatchers.IO) {
                    gadgetRepacker.repack(
                        apkFile = File(apkPath),
                        options = GadgetRepacker.Options(interaction = interaction),
                        signOptions = ApkRepacker.SignOptions(
                            enabled = selection.sign,
                            v1 = selection.v1,
                            v2 = selection.v2,
                            v3 = selection.v3,
                        ),
                        keySource = keySource,
                    ) { p, stage ->
                        _state.value = _state.value.copy(progress = p, stage = stage)
                    }
                }
                if (!output.result.ok) {
                    throw IllegalStateException(output.result.error ?: "注入失败")
                }

                _state.value = _state.value.copy(
                    progress = 0.98f, stage = "写出 APK"
                )
                withContext(Dispatchers.IO) {
                    appContext.contentResolver.openOutputStream(uri)?.use { os ->
                        apkRepacker.copyToStream(output.file, os)
                    } ?: throw IllegalStateException("无法写出所选位置")
                }
                val r = output.result
                val msg = buildString {
                    append("注入完成：${r.packageName}（入口 ${r.entryClass}）\n")
                    append("ABI ${r.abis.joinToString()} · ${r.libBase} · 模式 ${selection.mode}")
                    if (selection.mode == "listen") append(" :${selection.port}")
                    append("\n签名 ${r.output.schemes.joinToString().ifEmpty { "无" }} · ")
                    append("${r.output.outputSize / 1048576}MB · ${r.output.durationMs / 1000}s")
                    if (r.certSpoofed) append("\n已嵌入原签名指纹（自校验对抗）")
                }
                _state.value = _state.value.copy(running = false, success = msg, stage = "完成")
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    running = false,
                    error = "注入失败: ${e.message ?: e.javaClass.simpleName}",
                )
            } finally {
                output?.file?.delete()
            }
        }
    }

    fun dismissResult() {
        _state.value = _state.value.copy(error = null, success = null)
    }

    private fun queryDisplayName(uri: Uri): String? = try {
        appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) c.getString(idx) else null
            } else null
        }
    } catch (_: Exception) {
        null
    }
}
