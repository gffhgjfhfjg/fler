package com.ai.fler.core.frida

import android.util.Log
import com.ai.fler.core.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 目标应用（被 hook 进程）logcat 采集器。
 *
 * [FridaEngine] attach / spawn 成功后自动 [start]（`logcat --pid=<pid> -v time`
 * 常驻子进程，root 设备经 su），detach / kill 后 [stopFor]。采集到的日志进入
 * 环形缓冲（[MAX_ENTRIES] 条），UI / MCP 经 [version]（变化通知）+ [snapshot]
 * （拉取）消费——洪泛安全，不逐行复制列表。
 *
 * 未 root 时降级直接执行 `logcat`（多数 ROM 无 READ_LOGS 权限会立即退出，
 * 采集自然结束，UI 显示无日志）。
 */
@Singleton
class TargetLogCollector @Inject constructor(
    private val rootAccess: RootAccess,
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "FlerTargetLog"
        private const val MAX_ENTRIES = 3000

        /** logcat -v time 行格式：`08-19 02:36:15.123 W/Tag( 1234): message` */
        private val LINE_REGEX = Regex(
            """^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) ([VDIWEF])/(.*?)\(\s*\d+\): (.*)$"""
        )
    }

    /** 一条目标应用日志。 */
    data class TargetLogEntry(
        val seq: Long,
        val timestamp: Long,
        val level: String,
        val tag: String,
        val message: String,
    )

    private val buffer = ArrayDeque<TargetLogEntry>()
    private val lock = Any()
    private var seq = 0L
    private var process: Process? = null
    private var currentPid = 0L

    /** 缓冲版本号：每有新日志/状态变化 +1，UI 据此重新 [snapshot]。 */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()

    /** 当前采集的 pid（0 = 未采集）。 */
    private val _activePid = MutableStateFlow(0L)
    val activePid: StateFlow<Long> = _activePid.asStateFlow()

    // ========== 生命周期 ==========

    /**
     * 开始采集指定 pid 的 logcat（幂等：同 pid 已在采集中直接返回 true）。
     * 切换目标时自动停掉旧采集。
     */
    suspend fun start(pid: Long): Boolean = withContext(Dispatchers.IO) {
        if (pid <= 0) return@withContext false
        synchronized(lock) {
            if (currentPid == pid && process != null) return@withContext true
            stopLocked()
        }

        val rooted = rootAccess.isRoot()
        val builder = if (rooted) {
            ProcessBuilder("su", "-c", "logcat --pid=$pid -v time")
        } else {
            ProcessBuilder("logcat", "--pid=$pid", "-v", "time")
        }
        try {
            val proc = builder.start()
            synchronized(lock) {
                process = proc
                currentPid = pid
                _activePid.value = pid
            }
            Thread({
                readLoop(proc, pid)
            }, "fler-target-logcat").apply { isDaemon = true }.start()
            appLogger.info(TAG, "目标应用日志采集开始 pid=$pid root=$rooted")
            true
        } catch (e: Exception) {
            Log.e(TAG, "logcat 启动失败 pid=$pid: ${e.message}", e)
            appLogger.error(TAG, "目标日志采集启动失败 pid=$pid: ${e.message}")
            false
        }
    }

    /** 停止采集（无条件）。 */
    fun stop() {
        synchronized(lock) { stopLocked() }
        bump()
    }

    /** 仅当采集的 pid 与 [pid] 一致时停止（detach/kill 收尾用）。 */
    fun stopFor(pid: Long) {
        val hit = synchronized(lock) { currentPid == pid }
        if (hit) stop()
    }

    /** 是否正在采集。 */
    fun isCollecting(): Boolean = synchronized(lock) { process != null }

    /** 当前采集的 pid（0 = 未采集）。 */
    fun activePid(): Long = synchronized(lock) { currentPid }

    /** 清空缓冲（不动采集进程）。 */
    fun clear() {
        synchronized(lock) { buffer.clear() }
        bump()
    }

    // ========== 拉取 ==========

    /**
     * 拉取最近 [limit] 条日志。
     * @param level 可选级别过滤（V/D/I/W/E）；null = 全部
     */
    fun snapshot(limit: Int, level: String? = null): List<TargetLogEntry> {
        val n = limit.coerceIn(1, MAX_ENTRIES)
        synchronized(lock) {
            val src = if (level.isNullOrBlank()) buffer else buffer.filter { it.level == level }
            return src.toList().takeLast(n)
        }
    }

    // ========== 内部 ==========

    /** 读子进程 stdout 直到 EOF（目标退出 / stop 销毁）。须在专用线程调用。 */
    private fun readLoop(proc: Process, pid: Long) {
        try {
            BufferedReader(InputStreamReader(proc.inputStream, Charsets.UTF_8)).use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    parseLine(line)?.let { push(it) }
                }
            }
        } catch (_: Exception) {
            // stop() destroy 触发的 IO 异常：正常收尾
        }
        val ended = synchronized(lock) {
            if (process === proc) {
                process = null
                currentPid = 0
                _activePid.value = 0
                true
            } else false
        }
        if (ended) {
            appLogger.info(TAG, "目标应用日志采集结束 pid=$pid")
        }
        bump()
    }

    private fun push(entry: TargetLogEntry) {
        synchronized(lock) {
            buffer.addLast(entry)
            while (buffer.size > MAX_ENTRIES) buffer.removeFirst()
        }
        bump()
    }

    private fun stopLocked() {
        try {
            process?.destroy()
        } catch (_: Exception) {
        }
        process = null
        currentPid = 0
        _activePid.value = 0
    }

    private fun bump() {
        _version.value = _version.value + 1
    }

    /** 解析一行 logcat（-v time）；不匹配（如 logcat 错误输出）返回 null。 */
    private fun parseLine(line: String): TargetLogEntry? {
        val m = LINE_REGEX.find(line) ?: return null
        val (time, level, tag, message) = m.destructured
        val ts = parseTime(time)
        val s = synchronized(lock) { seq++ }
        return TargetLogEntry(
            seq = s,
            timestamp = ts,
            level = level,
            tag = tag,
            message = message,
        )
    }

    /** `MM-dd HH:mm:ss.SSS` → epoch 毫秒（年份取当前年；解析失败用当前时间）。 */
    private fun parseTime(time: String): Long = try {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        fmt.isLenient = true
        val year = SimpleDateFormat("yyyy", Locale.US).format(Date())
        fmt.parse("$year-$time")?.time ?: System.currentTimeMillis()
    } catch (_: Exception) {
        System.currentTimeMillis()
    }
}
