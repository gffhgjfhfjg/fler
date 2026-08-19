package com.ai.fler.core.frida

import android.util.Log
import com.ai.fler.core.jni.FridaBindings
import com.ai.fler.core.log.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Frida 会话编排引擎（root 方案核心）。
 *
 * 生命周期：
 * - [ensureReady]：校验 frida-core 客户端可用 → root 设备上把 frida-server
 *   部署/拉起（[RootAccess]）→ [FridaBindings.initialize] 启动本地客户端 worker
 * - [attach] / [spawnAndAttach]：得到 sessionHandle；成功后自动开始采集目标进程
 *   logcat（[TargetLogCollector]，detach/kill 时停止）
 * - [runHook]：在 session 上创建 + 加载 Interceptor 脚本（[FridaScriptBuilder]）
 * - 脚本 send() 事件经 [FridaBindings.messageListener] 进入本引擎事件环形缓冲，
 *   MCP 侧 [events] 拉取，UI 侧观察 [eventsVersion] 后拉取
 *
 * 全部 JNI/部署调用在 Dispatchers.IO；事件回调由 native marshal 线程触发，
 * 环形缓冲线程安全，UI/MCP 可任意线程消费。
 */
@Singleton
class FridaEngine @Inject constructor(
    private val rootAccess: RootAccess,
    private val targetLogCollector: TargetLogCollector,
    private val appLogger: AppLogger,
) {
    companion object {
        private const val TAG = "FlerFridaEngine"
        private const val MAX_EVENTS = 5000
    }

    // ---------- 会话/事件模型 ----------

    data class SessionInfo(
        val sessionHandle: Long,
        val pid: Long,
        val pkg: String?,
        val createdAt: Long,
    )

    data class HookScript(
        val scriptHandle: Long,
        val label: String,
        val source: String,
    )

    data class FridaEvent(
        val ts: Long,
        val sessionHandle: Long,
        val scriptHandle: Long,
        val json: String,
    )

    data class RuntimeStatus(
        val available: Boolean,
        val version: String,
        val root: Boolean,
        val serverRunning: Boolean,
        val initialized: Boolean,
        val workerAlive: Boolean,
    )

    // ---------- 状态 ----------
    private val sessions = ConcurrentHashMap<Long, SessionInfo>()
    private val scriptToSession = ConcurrentHashMap<Long, Long>()
    private val scriptOfSession = ConcurrentHashMap<Long, MutableList<HookScript>>()
    private val events = ArrayDeque<FridaEvent>()
    private val lock = Any()
    private var initialized = false
    private var totalEvents = 0L

    /** 事件缓冲版本号：新增/清空事件时 +1，UI 观察后调 [events] 拉取。 */
    private val _eventsVersion = MutableStateFlow(0L)
    val eventsVersion: StateFlow<Long> = _eventsVersion.asStateFlow()

    init {
        // native marshal 线程 → 事件缓冲（只读队列，无同步负担）
        FridaBindings.messageListener = { scriptHandle, json ->
            pushEvent(scriptHandle, json)
        }
    }

    // ---------- 初始化 ----------

    /** frida 客户端（libfrida-core）是否存在。 */
    val isAvailable: Boolean get() = FridaBindings.isAvailable

    /** 运行态探测（不部署）。 */
    suspend fun status(): RuntimeStatus = withContext(Dispatchers.IO) {
        RuntimeStatus(
            available = FridaBindings.isAvailable,
            version = FridaBindings.version,
            root = rootAccess.isRoot(),
            serverRunning = if (!FridaBindings.isAvailable) false else rootAccess.isServerRunning(),
            initialized = initialized,
            workerAlive = if (!FridaBindings.isAvailable) false else FridaBindings.workerAlive,
        )
    }

    /** 完整就绪：root + 部署 server + 启动客户端 worker。 */
    suspend fun ensureReady(): RuntimeStatus = withContext(Dispatchers.IO) {
        if (!FridaBindings.isAvailable) {
            appLogger.error(TAG, "frida-core 不可用（编译未启用或库缺失）")
            return@withContext status()
        }
        if (!rootAccess.isRoot()) {
            appLogger.error(TAG, "设备未 root，无法部署 frida-server")
            return@withContext status()
        }
        val deployed = rootAccess.ensureAndStart()
        if (!deployed) {
            appLogger.error(TAG, "frida-server 部署/启动失败")
            return@withContext status()
        }
        if (!initialized) {
            initialized = FridaBindings.initialize()
        }
        if (initialized) {
            // 探活：枚举一次进程，确认 server 链路通。首次枚举若失败（例如 server
            // 刚拉起或 root 授权时序导致 worker 首调竞态）重试一次再判失败。
            var probe = FridaBindings.enumerateProcesses()
            if (probe.contains("error")) {
                probe = FridaBindings.enumerateProcesses()
            }
            if (probe.contains("error")) {
                appLogger.error(TAG, "frida-server 链路探测失败: $probe")
            } else {
                appLogger.info(TAG, "frida-server 已就绪，枚举进程数 > 0")
            }
        }
        status()
    }

    // ---------- 枚举 ----------

    /** 设备进程列表（经 frida-server）。解析失败返回 null。 */
    suspend fun listProcesses(): String = withContext(Dispatchers.IO) {
        ensureReady()
        FridaBindings.enumerateProcesses()
    }

    /** 设备应用列表（identifier + name）。 */
    suspend fun listApplications(): String = withContext(Dispatchers.IO) {
        ensureReady()
        FridaBindings.enumerateApplications()
    }

    // ---------- 会话 ----------

    /** attach 到已运行进程，返回 sessionHandle（0=失败）。成功后自动开始采集目标 logcat。 */
    suspend fun attach(pid: Long): Long = withContext(Dispatchers.IO) {
        ensureReady()
        val h = FridaBindings.attach(pid)
        if (h != 0L) {
            sessions[h] = SessionInfo(h, pid, null, System.currentTimeMillis())
            appLogger.info(TAG, "attach pid=$pid -> session=$h")
            // 自动采集目标应用 logcat（失败不影响会话本身）
            runCatching { targetLogCollector.start(pid) }
                .onFailure { appLogger.warn(TAG, "目标日志采集启动失败: ${it.message}") }
        }
        h
    }

    /** spawn 目标应用（gating：入口处暂停，等 attach+resume）。返回 pid（0=失败）。 */
    suspend fun spawn(pkg: String): Long = withContext(Dispatchers.IO) {
        ensureReady()
        val pid = FridaBindings.spawn(pkg)
        if (pid != 0L) appLogger.info(TAG, "spawn $pkg -> pid=$pid (gated)")
        pid
    }

    /** 让 spawn 的进程继续执行。 */
    suspend fun resume(pid: Long): Boolean = withContext(Dispatchers.IO) {
        ensureReady()
        FridaBindings.resume(pid)
    }

    // ---------- 脚本 ----------

    /** 在 session 上部署 hook 脚本（Interceptor）。 */
    suspend fun runHook(
        sessionHandle: Long,
        label: String,
        source: String,
    ): Long = withContext(Dispatchers.IO) {
        ensureReady()
        val session = sessions[sessionHandle] ?: return@withContext 0L
        val scriptHandle = FridaBindings.createScript(sessionHandle, source)
        Log.d(TAG, "createScript($label) -> $scriptHandle len=${source.length}")
        if (scriptHandle == 0L) {
            appLogger.error(TAG, "createScript($label) 失败")
            return@withContext 0L
        }
        // 先登记映射再 load：load 会同步触发脚本内立即执行的 send()，消息到达时
        // 就能用 scriptToSession 解析出 sessionHandle。
        scriptToSession[scriptHandle] = sessionHandle
        if (!FridaBindings.loadScript(scriptHandle)) {
            scriptToSession.remove(scriptHandle)
            FridaBindings.unloadScript(scriptHandle)
            appLogger.error(TAG, "loadScript($label) 失败")
            return@withContext 0L
        }
        Log.d(TAG, "loadScript($label) ok")
        scriptOfSession[sessionHandle]?.add(HookScript(scriptHandle, label, source))
        appLogger.info(TAG, "hook[$label] 已加载, session=$sessionHandle script=$scriptHandle")
        scriptHandle
    }

    /** 向脚本发送 rpc 消息。 */
    suspend fun post(scriptHandle: Long, json: String): Boolean = withContext(Dispatchers.IO) {
        if (scriptHandle <= 0L) return@withContext false
        FridaBindings.post(scriptHandle, json)
        true
    }

    /** 卸载已加载的脚本（异步，清理句柄映射）。 */
    suspend fun unloadScript(scriptHandle: Long): Boolean = withContext(Dispatchers.IO) {
        if (scriptHandle <= 0L) return@withContext false
        val sessionHandle = scriptToSession.remove(scriptHandle)
        if (sessionHandle != null) {
            scriptOfSession[sessionHandle]?.removeAll { it.scriptHandle == scriptHandle }
        }
        FridaBindings.unloadScript(scriptHandle)
    }

    /** 清空事件环形缓冲（便于聚焦新一轮命中）。 */
    fun clearEvents() {
        synchronized(lock) { events.clear() }
        _eventsVersion.value = _eventsVersion.value + 1
    }

    // ---------- 收尾 ----------

    suspend fun detach(sessionHandle: Long): Boolean = withContext(Dispatchers.IO) {
        ensureReady()
        val pid = sessions[sessionHandle]?.pid
        val ok = FridaBindings.detach(sessionHandle)
        if (ok) {
            sessions.remove(sessionHandle)
            pid?.let { targetLogCollector.stopFor(it) }
        }
        ok
    }

    suspend fun kill(pid: Long): Boolean = withContext(Dispatchers.IO) {
        ensureReady()
        val ok = FridaBindings.kill(pid)
        if (ok) targetLogCollector.stopFor(pid)
        ok
    }

    /** 当前记录的会话列表。 */
    fun sessionsSnapshot(): List<SessionInfo> = sessions.values.toList().sortedBy { it.sessionHandle }

    // ---------- 事件 ----------

    /** 拉取事件（指定会话/脚本过滤，最近 [limit] 条）。 */
    fun events(sessionHandle: Long?, scriptHandle: Long?, limit: Int): List<FridaEvent> {
        val n = if (limit in 1..1000) limit else 100
        synchronized(lock) {
            return events.filter { e ->
                (sessionHandle == null || e.sessionHandle == sessionHandle) &&
                    (scriptHandle == null || e.scriptHandle == scriptHandle)
            }
                .toList()
                .takeLast(n)
        }
    }

    /** 事件总量（单调递增；用于增量轮询断点）。 */
    fun eventCount(): Long = synchronized(lock) { totalEvents }

    private fun pushEvent(scriptHandle: Long, json: String) {
        val sessionHandle = scriptToSession[scriptHandle] ?: 0L
        synchronized(lock) {
            totalEvents++
            events.addLast(
                FridaEvent(
                    ts = System.currentTimeMillis(),
                    sessionHandle = sessionHandle,
                    scriptHandle = scriptHandle,
                    json = json,
                )
            )
            while (events.size > MAX_EVENTS) events.removeFirst()
        }
        _eventsVersion.value = _eventsVersion.value + 1
    }
}