package com.ai.fler.core.frida

import android.content.Context
import android.util.Log
import com.ai.fler.core.service.DualSourceDownloader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * root 设备上部署 frida-server 的「加固 + 保活」层。
 *
 * 职责（全部经 su）：
 * 1. [isRooted]：探测 Magisk/标准 su（`su -c id`，uid=0）
 * 2. [ensureServer]：缺 `/data/local/tmp/frida-server` 时从 GitHub 下载
 *    （xz 压缩包，App 内解压），以 root 写入 + chmod 755
 * 3. [startServer]：常驻拉起（-D daemonize），本地回环监听 127.0.0.1:27042
 * 4. [isServerRunning]：ps 检测；掉线后 [ensureAndStart] 自动重启
 *
 * 说明：frida-server 仅需 root 一次，本机 fler 客户端（FridaBindings）通过
 * libfrida-core 连 local device（回环 27042）做 hook——不需要 adb。
 */
@Singleton
class RootAccess @Inject constructor(
    @ApplicationContext private val context: Context,
    private val downloader: DualSourceDownloader,
) {
    companion object {
        private const val TAG = "FlerFridaRoot"

        /** frida 版本（须与 FridaBindings 的 libfrida-core 一致，同 devkit 依赖）。 */
        const val FRIDA_VERSION = "17.17.0"

        /** 设备端部署路径（root 可写、可执行）。 */
        const val SERVER_REMOTE_PATH = "/data/local/tmp/frida-server"

        /** 本地缓存目录/文件（App 私有）。 */
        private const val SERVER_LOCAL_NAME = "frida-server-$FRIDA_VERSION"

        private const val RELEASE_URL =
            "https://github.com/frida/frida/releases/download/%s/frida-server-%s-android-arm64.xz"

        /** su 命令超时阈值（毫秒）。防止 Magisk 授权挂起导致永久阻塞。 */
        private const val SU_TIMEOUT_MS = 5_000L

        /** 授权重触发的探活命令（触发 Magisk 弹窗重新浮起）。 */
        private const val AUTH_RETRIGGER_CMD = "id"

        /** 授权重触发前的延迟（毫秒），给上一个 su 的退出留出时间。 */
        private const val AUTH_RETRIGGER_DELAY_MS = 1_000L
    }

    // ========== root 检测 ==========

    /** 当前进程是否有 root 能力（su -c id 返回 uid=0）。 */
    suspend fun isRoot(): Boolean = withContext(Dispatchers.IO) {
        runSu("id", retriggerAuth = true)?.contains("uid=0") == true
    }

    // ========== frida-server 部署 ==========

    /** 设备上是否已存在 frida-server。 */
    suspend fun isServerInstalled(): Boolean = withContext(Dispatchers.IO) {
        val out = runSu("test -x $SERVER_REMOTE_PATH && echo OK")
        out?.trim() == "OK"
    }

    /** 设备上 frida-server 版本与本地 client 一致。 */
    suspend fun isServerVersionCurrent(): Boolean = withContext(Dispatchers.IO) {
        val out = runSu("$SERVER_REMOTE_PATH --version 2>&1")
        out?.trim() == FRIDA_VERSION
    }

    /** 把本地缓存的 frida-server 以 root 部署到设备并 chmod。 */
    private suspend fun installToDevice(local: File): Boolean = withContext(Dispatchers.IO) {
        try {
            // 进程写 stdin 方式避免 base64 命令长度限制：直接 cat > 目标文件
            val process = ProcessBuilder("su", "-c", "cat > $SERVER_REMOTE_PATH").start()
            local.inputStream().use { input ->
                process.outputStream.use { it.write(input.readBytes()) }
            }
            val rc = process.waitFor()
            if (rc != 0) {
                Log.e(TAG, "write frida-server: rc=$rc")
                return@withContext false
            }
            runSu("chmod 755 $SERVER_REMOTE_PATH && echo OK") == "OK"
        } catch (e: Exception) {
            Log.e(TAG, "installToDevice failed: ${e.message}", e)
            false
        }
    }

    /**
     * 确保本地缓存 frida-server 二进制存在（必要时下载并解压 xz）。
     * 返回本地文件；失败抛异常。
     */
    private suspend fun ensureLocalBinary(): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "frida")
        val xzFile = File(dir, "$SERVER_LOCAL_NAME.xz")
        val binFile = File(dir, SERVER_LOCAL_NAME)
        if (binFile.exists() && binFile.length() > 1_000_000) {
            return@withContext binFile
        }
        dir.mkdirs()

        // 下载 xz 压缩包
        val url = RELEASE_URL.format(FRIDA_VERSION, FRIDA_VERSION)
        downloader.downloadAsset(url, xzFile, onProgress = { _, _, _ -> })

        // 解压 xz → 可执行文件
        try {
            xzFile.inputStream().use { raw ->
                val xzIn = XZInputStream(BufferedInputStream(raw))
                FileOutputStream(binFile).use { out ->
                    xzIn.copyTo(out)
                }
            }
            binFile.setExecutable(true)
            binFile
        } catch (e: Exception) {
            Log.e(TAG, "xz 解压失败: ${e.message}", e)
            xzFile.delete()
            throw IllegalStateException("frida-server 解压失败", e)
        }
    }

    /**
     * 部署并启动 frida-server。缺装则先安装；常驻守护（-D）。
     * @return true 表示这次调用确保 server 处于运行中
     */
    suspend fun ensureAndStart(): Boolean = withContext(Dispatchers.IO) {
        if (!isRoot()) {
            Log.w(TAG, "ensureAndStart: 无 root")
            return@withContext false
        }
        // 版本不匹配时停旧 server 并强制重装（客户端须与 server 严格同版本）
        var forceReinstall = false
        if (isServerInstalled()) {
            if (!isServerVersionCurrent()) {
                Log.w(TAG, "frida-server 版本不符，停旧重装")
                stopServer()
                forceReinstall = true
            }
        }
        var installed = if (forceReinstall) false else isServerInstalled()
        if (!installed) {
            val bin = ensureLocalBinary()
            installed = installToDevice(bin)
        }
        if (!installed) return@withContext false
        if (!isServerRunning()) {
            startServer()
        }
        isServerRunning()
    }

    /** 后台拉起 frida-server（-D 自动后台化，-l 回环：仅本机 fler 客户端连）。 */
    suspend fun startServer(): Boolean = withContext(Dispatchers.IO) {
        val out = runSu("nohup $SERVER_REMOTE_PATH -D -l 127.0.0.1:27042 >/dev/null 2>&1 &")
        // -D 后进程已脱离；dohup & 立即返回
        isServerRunning()
    }

    /** frida-server 是否在跑。 */
    suspend fun isServerRunning(): Boolean = withContext(Dispatchers.IO) {
        val out = runSu("ps -A | grep frida-server | grep -v grep", retriggerAuth = true)
        !out.isNullOrBlank()
    }

    /** 停掉当前 frida-server（root）。 */
    suspend fun stopServer(): Boolean = withContext(Dispatchers.IO) {
        runSu("pkill -f frida-server") != null
    }

    // ========== su 工具 ==========

    /**
     * 单条 su 命令执行，返回 stdout；失败/不可用返回 null。
     * 避免在响应体里混入错误输出。
     *
     * 带超时保护：Magisk 按 uid 授权，重装后 uid 漂移会导致 su 挂起等待授权弹窗，
     * 若无超时这里会永久阻塞（UI「探测中」卡死）。[retriggerAuth] 为 true 时，
     * 超时会再触发一次 su（重新拉起 Magisk 授权弹窗，供用户在弹窗里点「允许」）。
     */
    private fun runSu(cmd: String, retriggerAuth: Boolean = false): String? {
        return try {
            val process = ProcessBuilder("su", "-c", cmd)
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(SU_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                Log.w(TAG, "su '$cmd' 超时(>${SU_TIMEOUT_MS}ms)，疑似等待 Magisk 授权，已强杀")
                if (retriggerAuth) retriggerRootAuth()
                return null
            }
            val out = process.inputStream.readBytes().toString(Charsets.UTF_8)
            val rc = process.exitValue()
            Log.d(TAG, "su '$cmd' -> rc=$rc out=${out.trim()}")
            out.trim().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "runSu failed: ${e.message}", e)
            null
        }
    }

    /**
     * 重新触发一次 root 授权：短暂延迟后再跑一次 su，让 Magisk 的授权弹窗
     * 重新浮起。仅当上一条 su 因授权挂起时调用（由 [retriggerAuth] 控制），
     * 用于用户点「允许」后完成 uid 授权。
     */
    private fun retriggerRootAuth() {
        try {
            Thread.sleep(AUTH_RETRIGGER_DELAY_MS)
            val p = ProcessBuilder("su", "-c", AUTH_RETRIGGER_CMD)
                .redirectErrorStream(true)
                .start()
            if (p.waitFor(3, TimeUnit.SECONDS)) {
                val out = p.inputStream.readBytes().toString(Charsets.UTF_8).trim()
                Log.d(TAG, "授权重触发 su 'id' -> rc=${p.exitValue()} out=$out")
            } else {
                p.destroyForcibly()
                Log.w(TAG, "授权重触发 su 再次超时，请检查 Magisk 授权弹窗")
            }
        } catch (e: Exception) {
            Log.e(TAG, "retriggerRootAuth failed: ${e.message}", e)
        }
    }
}