package com.ai.fler.core.jni

/**
 * Frida 动态调试 JNI 绑定（root 方案客户端）。
 *
 * frida-core 已静态链接进 fler_jni.so（frida_jni.cpp 直接调用 frida-* C API），
 * 对设备端 root 常驻的 frida-server 做协议客户端。仅 arm64。
 *
 * 模型：
 * - [initialize] 启动 frida worker 线程（GMainLoop 驱动同步调用），幂等。
 * - [attach] 返回 sessionHandle（long）；[createScript] 在同一 session 上返回
 *   scriptHandle；[loadScript/unloadScript/post] 操作脚本；[detach] 收尾 session。
 * - 脚本内 send()/console.log 产生的消息经 [nativeOnMessage] 静态回调，转发给
 *   [messageListener]（注意总是在内部 marshal 线程触发，Listener 需自行切线程）。
 *
 * 编译期 ENABLE_FRIDA=OFF 或 libfrida-core.a 缺失时，全部 native 退化为
 * 安全 stub（[isAvailable]=false），不应有任何 UnsatisfiedLinkError。
 */
object FridaBindings {

    /** 引擎是否可用（frida-core 编译链接成功）。 */
    val isAvailable: Boolean by lazy { nativeIsAvailable() }

    /** frida 版本字符串（如 "17.17.0"）；编译禁用时为 "disabled"。 */
    val version: String by lazy { nativeVersion() }

    /**
     * 脚本消息回调（native marshal 线程上触发）。
     * @param scriptHandle 消息归属的 scriptHandle（-1 = detach/system 事件）
     * @param json 消息 JSON（type=send/log/error），send 的数据部分附 hex
     */
    @Volatile
    var messageListener: ((scriptHandle: Long, json: String) -> Unit)? = null

    /** JNI 回调入口（frida 内部线程 → native marshal 线程 attach 后调用）。 */
    @Suppress("unused")
    @JvmStatic
    fun nativeOnMessage(scriptHandle: Long, json: String) {
        messageListener?.invoke(scriptHandle, json)
    }

    /** 初始化（启动 worker 线程 + 设备管理器），返回是否成功。幂等。 */
    fun initialize(): Boolean = nativeInitialize()

    /** 枚举设备进程（经 frida-server）→ JSON 数组。 */
    fun enumerateProcesses(): String = nativeEnumerateProcesses()

    /** 枚举已安装应用（identifier+name）→ JSON 数组。 */
    fun enumerateApplications(): String = nativeEnumerateApplications()

    /** attach 到 pid，返回 sessionHandle；0=失败。 */
    fun attach(pid: Long): Long = nativeAttach(pid)

    /** spawn 应用 identifier，返回 pid（0=失败）。 */
    fun spawn(identifier: String): Long = nativeSpawn(identifier)

    fun resume(pid: Long): Boolean = nativeResume(pid)

    fun kill(pid: Long): Boolean = nativeKill(pid)

    /** 在 session 上创建脚本，返回 scriptHandle（0=失败）。 */
    fun createScript(sessionHandle: Long, source: String): Long =
        nativeCreateScript(sessionHandle, source)

    fun loadScript(scriptHandle: Long): Boolean = nativeLoadScript(scriptHandle)

    fun unloadScript(scriptHandle: Long): Boolean = nativeUnloadScript(scriptHandle)

    /** 向脚本发送消息（rpc 入口）。 */
    fun post(scriptHandle: Long, json: String) = nativePost(scriptHandle, json)

    fun detach(sessionHandle: Long): Boolean = nativeDetach(sessionHandle)

    /** 关闭全部 session/script 并 close 设备管理器（会停止后续使用，谨慎）。 */
    fun close() = nativeClose()

    /** 取回并清空最近一次脚本 create/load 的原生错误文本（无错误时为空串）。 */
    fun takeLastScriptError(): String = nativeLastScriptError()

    /**
     * frida worker 线程是否存活且未被看门狗标记阻塞。不经过 worker（纯原生原子量
     * 读取），worker 卡死时也能返回 false，供 frida_ready/frida_status 快速失败判定。
     */
    val workerAlive: Boolean get() = nativeWorkerAlive()

    private external fun nativeIsAvailable(): Boolean
    private external fun nativeVersion(): String
    private external fun nativeInitialize(): Boolean
    private external fun nativeEnumerateProcesses(): String
    private external fun nativeEnumerateApplications(): String
    private external fun nativeAttach(pid: Long): Long
    private external fun nativeSpawn(identifier: String): Long
    private external fun nativeResume(pid: Long): Boolean
    private external fun nativeKill(pid: Long): Boolean
    private external fun nativeCreateScript(sessionHandle: Long, source: String): Long
    private external fun nativeLoadScript(scriptHandle: Long): Boolean
    private external fun nativeUnloadScript(scriptHandle: Long): Boolean
    private external fun nativePost(scriptHandle: Long, json: String)
    private external fun nativeDetach(sessionHandle: Long): Boolean
    private external fun nativeClose()
    private external fun nativeLastScriptError(): String
    private external fun nativeWorkerAlive(): Boolean
}