// =============================================================================
// Frida 动态桥接 JNI（root 方案 · App 内完整闭环）
//
// 静态链接：libfrida-core.a（frida-core-devkit 17.17.0-android-arm64 产物，
// 与设备端 frida-server 同版本）链入 fler_jni.so，作为 frida-server 的客户端：
//   - 设备端：frida-server 由 RootAccess 以 root 常驻（/data/local/tmp/frida-server）
//   - 本库：frida-core 只跑协议客户端（local device ⇄ 127.0.0.1:27042），
//     attach/spawn/resume/script/事件回传
// 目标进程里的 agent 由 frida-server 侧提供，嵌入客户端无需自带 agent 资产。
//
// 信号分派：frida 的消息信号（script "message"）需要运行中的 GMainContext 才会
// 派发。因此本库启动专用 worker 线程（持有 GMainLoop + GMainContext），所有
// *_sync 调用经 g_main_context_invoke 投递到该线程执行，结果用 future 回传；
// 脚本消息则由 frida 内部线程发射，统一进入事件队列，由 marshal 线程
// AttachCurrentThread 后回调 Kotlin FridaBindings.nativeOnMessage(handle, json)。
//
// 编译开关 FLER_ENABLE_FRIDA（CMake ENABLE_FRIDA）关闭时本文件全部 JNI 退化为
// 安全 stub（isAvailable=false），保证 FridaEngine 不触发 UnsatisfiedLinkError。
// =============================================================================

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <future>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>

static const char* TAG = "FlerFridaJNI";

#ifdef FLER_ENABLE_FRIDA

#include <frida/frida-core.h>

// ─────────────────────────────────────────────────────────────
// 全局状态
// ─────────────────────────────────────────────────────────────
static JavaVM* g_vm = nullptr;

// frida-core devkit 必须最先调用 frida_init()（初始化内嵌 glib 的线程 TLS/系统，
// 否则任何 g_main_context_new/g_thread 都会在 g_private_get 空指针崩溃）。
static std::atomic<bool> g_fridaInited{false};

static void ensureFridaInit() {
    if (g_fridaInited.exchange(true)) return;
    frida_init();
    __android_log_print(ANDROID_LOG_INFO, TAG, "frida_init() done");
}

// ---- worker 线程：GMainLoop 驱动 frida-core 同步调用 ----
struct FridaWorker {
    GMainContext* ctx = nullptr;
    GMainLoop* loop = nullptr;
    GThread* thread = nullptr;
    FridaDeviceManager* manager = nullptr;
    std::atomic<bool> running{false};
    // 看门狗：某次 syncOnWorker/asyncOnWorker 超时后置 true，后续调用快速失败，
    // 避免整个 worker 队列被卡死导致 MCP server 失去响应。
    std::atomic<bool> blocked{false};
};

static FridaWorker g_worker;

struct InvokeJob {
    std::function<void()> fn;
};

// g_main_context_invoke 调度回调：执行后释放 job
static gboolean invokeDispatch(gpointer data) {
    auto* job = static_cast<InvokeJob*>(data);
    job->fn();
    delete job;
    return G_SOURCE_REMOVE;
}

static void postToWorker(std::function<void()> fn) {
    if (!g_worker.running.load() || g_worker.ctx == nullptr) return;
    auto* job = new InvokeJob{ std::move(fn) };
    g_main_context_invoke(g_worker.ctx, invokeDispatch, job);
}

// 在 worker 线程同步执行并回传结果（阻塞调用线程直到 worker 完成）。std::promise
// 不可拷贝，包一层 shared_ptr 使其作为 lambda 捕获后可被 std::function 持有。
// 看门狗：future 超时（默认 10s）标记 worker blocked 并抛异常，调用方快速失败，
// 不再无限挂起（避免单次卡死拖垮整个 MCP server）。
template <typename R>
static R syncOnWorker(std::function<R()> fn,
                      std::chrono::milliseconds timeout = std::chrono::seconds(10)) {
    if (!g_worker.running.load() || g_worker.ctx == nullptr) {
        throw std::runtime_error("frida worker not running");
    }
    if (g_worker.blocked.load()) {
        throw std::runtime_error("frida worker blocked (previous op timed out)");
    }
    auto p = std::make_shared<std::promise<R>>();
    auto future = p->get_future();
    auto* job = new InvokeJob{ [fn = std::move(fn), p]() mutable {
        try {
            p->set_value(fn());
        } catch (...) {
            p->set_exception(std::current_exception());
        }
    }};
    g_main_context_invoke(g_worker.ctx, invokeDispatch, job);
    if (future.wait_for(timeout) != std::future_status::ready) {
        g_worker.blocked.store(true);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "syncOnWorker timed out after %lldms; "
                            "worker marked blocked", (long long)timeout.count());
        throw std::runtime_error("frida worker blocked (sync op timed out)");
    }
    return future.get();
}

// 在 worker 线程发起 frida *_async 调用并回传结果（阻塞调用线程直到回调完成）。
// 与 syncOnWorker 的关键区别：job 内只【发起】异步调用（frida 的 async 函数不会
// 嵌套 pump GMainContext，立即返回），真正的 *_finish 由 frida 在 worker context 上
// 派发的回调执行，结果经 promise 回传。这样 worker 主循环永不因嵌套 pump 而阻塞，
// 根治「目标进程有活跃 hook 时 *_sync 重入死锁」问题。
template <typename R>
static R asyncOnWorker(std::function<void(const std::shared_ptr<std::promise<R>>&)> fireAsync,
                       std::chrono::milliseconds timeout = std::chrono::seconds(10)) {
    if (!g_worker.running.load() || g_worker.ctx == nullptr) {
        throw std::runtime_error("frida worker not running");
    }
    if (g_worker.blocked.load()) {
        throw std::runtime_error("frida worker blocked (previous op timed out)");
    }
    auto p = std::make_shared<std::promise<R>>();
    auto future = p->get_future();
    auto* job = new InvokeJob{ [fireAsync = std::move(fireAsync), p]() mutable {
        fireAsync(p);
    }};
    g_main_context_invoke(g_worker.ctx, invokeDispatch, job);
    if (future.wait_for(timeout) != std::future_status::ready) {
        g_worker.blocked.store(true);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "asyncOnWorker timed out after %lldms; "
                            "worker marked blocked", (long long)timeout.count());
        throw std::runtime_error("frida worker blocked (async op timed out)");
    }
    return future.get();
}

static void workerMain() {
    g_worker.ctx = g_main_context_new();
    g_worker.loop = g_main_loop_new(g_worker.ctx, FALSE);
    g_worker.manager = frida_device_manager_new();
    g_worker.running.store(true);
    g_worker.blocked.store(false);
    __android_log_print(ANDROID_LOG_INFO, TAG, "frida worker thread started");
    g_main_loop_run(g_worker.loop);
    g_worker.running.store(false);
    if (g_worker.manager != nullptr) {
        frida_unref(g_worker.manager);
        g_worker.manager = nullptr;
    }
    g_main_loop_unref(g_worker.loop);
    g_worker.loop = nullptr;
    g_main_context_unref(g_worker.ctx);
    g_worker.ctx = nullptr;
    __android_log_print(ANDROID_LOG_INFO, TAG, "frida worker thread exited");
}

// 用 GThread 起 worker，确保 GLib thread-private 初始化好，避免 slice allocator
// 在裸 std::thread 上 g_private_get 空指针崩溃。
static gpointer workerThreadEntry(gpointer /*data*/) {
    workerMain();
    return nullptr;
}

// ---- 脚本消息 → JNI 回传队列 --------------------------------------------------
static std::mutex g_qMutex;
static std::condition_variable g_qCv;
static std::deque<std::pair<long, std::string>> g_eventQueue;
static std::atomic<bool> g_quitMarshal{false};

// marshal 线程用的 FridaBindings 全局引用与方法 ID：必须在 Java 线程（nativeInitialize）
// 里解析。AttachCurrentThread 出的线程用 bootstrap classloader，FindClass 找不到 app 类。
static jclass g_fridaCls = nullptr;
static jmethodID g_onMessageMid = nullptr;
static std::atomic<bool> g_marshalStarted{false};

static void marshalThread();

static bool resolveFridaRefs(JNIEnv* env) {
    if (g_fridaCls != nullptr) return true;
    jclass local = env->FindClass("com/ai/fler/core/jni/FridaBindings");
    if (local == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, TAG, "resolveFridaRefs: FindClass failed");
        return false;
    }
    g_fridaCls = (jclass)env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    g_onMessageMid = env->GetStaticMethodID(g_fridaCls, "nativeOnMessage", "(JLjava/lang/String;)V");
    if (g_onMessageMid == nullptr) {
        env->ExceptionClear();
        __android_log_print(ANDROID_LOG_ERROR, TAG, "resolveFridaRefs: GetStaticMethodID failed");
        env->DeleteGlobalRef(g_fridaCls);
        g_fridaCls = nullptr;
        return false;
    }
    return true;
}

static void startMarshalThread() {
    if (g_marshalStarted.exchange(true)) return;
    std::thread(marshalThread).detach();
}

static void marshalThread() {
    JNIEnv* env = nullptr;
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "marshalThread attach failed");
        return;
    }
    if (g_fridaCls == nullptr || g_onMessageMid == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "marshalThread: refs not resolved");
        g_vm->DetachCurrentThread();
        return;
    }

    for (;;) {
        std::pair<long, std::string> item;
        {
            std::unique_lock<std::mutex> lock(g_qMutex);
            g_qCv.wait(lock, [] { return !g_eventQueue.empty() || g_quitMarshal.load(); });
            if (g_quitMarshal.load() && g_eventQueue.empty()) break;
            item = std::move(g_eventQueue.front());
            g_eventQueue.pop_front();
        }
        jstring js = env->NewStringUTF(item.second.c_str());
        env->CallStaticVoidMethod(g_fridaCls, g_onMessageMid, static_cast<jlong>(item.first), js);
        env->DeleteLocalRef(js);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    g_vm->DetachCurrentThread();
    __android_log_print(ANDROID_LOG_INFO, TAG, "marshal thread exited");
}

static void pushEvent(long scriptHandle, const std::string& json) {
    {
        std::lock_guard<std::mutex> lock(g_qMutex);
        g_eventQueue.emplace_back(scriptHandle, json);
    }
    g_qCv.notify_one();
}

// ---- session/script handle 管理 -------------------------------------------------
static std::mutex g_handleMutex;
static long g_nextHandle = 1;
static std::unordered_map<long, FridaSession*> g_sessionMap;
static std::unordered_map<FridaSession*, long> g_sessionRev;
static std::unordered_map<long, FridaScript*> g_scriptMap;
static std::unordered_map<FridaScript*, long> g_scriptRev;

static long storeSession(FridaSession* session) {
    std::lock_guard<std::mutex> lock(g_handleMutex);
    long h = g_nextHandle++;
    g_object_ref(session);
    g_sessionMap[h] = session;
    g_sessionRev[session] = h;
    return h;
}

static long storeScript(FridaScript* script) {
    std::lock_guard<std::mutex> lock(g_handleMutex);
    long h = g_nextHandle++;
    g_object_ref(script);
    g_scriptMap[h] = script;
    g_scriptRev[script] = h;
    return h;
}

static FridaSession* sessionOf(long handle) {
    std::lock_guard<std::mutex> lock(g_handleMutex);
    auto it = g_sessionMap.find(handle);
    return it == g_sessionMap.end() ? nullptr : it->second;
}

static FridaScript* scriptOf(long handle) {
    std::lock_guard<std::mutex> lock(g_handleMutex);
    auto it = g_scriptMap.find(handle);
    return it == g_scriptMap.end() ? nullptr : it->second;
}

static long scriptHandleOf(FridaScript* script) {
    std::lock_guard<std::mutex> lock(g_handleMutex);
    auto it = g_scriptRev.find(script);
    return it == g_scriptRev.end() ? -1L : it->second;
}

static void removeScript(FridaScript* script) {
    std::lock_guard<std::mutex> lock(g_handleMutex);
    long h = -1;
    auto rev = g_scriptRev.find(script);
    if (rev != g_scriptRev.end()) {
        h = rev->second;
        g_scriptRev.erase(rev);
    }
    if (h >= 0) {
        auto fwd = g_scriptMap.find(h);
        if (fwd != g_scriptMap.end()) g_scriptMap.erase(fwd);
    }
}

static void removeSession(FridaSession* session) {
    std::lock_guard<std::mutex> lock(g_handleMutex);
    long h = -1;
    auto rev = g_sessionRev.find(session);
    if (rev != g_sessionRev.end()) {
        h = rev->second;
        g_sessionRev.erase(rev);
    }
    if (h >= 0) {
        auto fwd = g_sessionMap.find(h);
        if (fwd != g_sessionMap.end()) g_sessionMap.erase(fwd);
    }
}

// ---- 最近脚本错误记录（供 MCP 取回诊断信息） ------------------------------------
static std::mutex g_errMutex;
static std::string g_lastScriptError;

static void setLastScriptError(const std::string& msg) {
    std::lock_guard<std::mutex> lock(g_errMutex);
    g_lastScriptError = msg;
}

static std::string takeLastScriptError() {
    std::lock_guard<std::mutex> lock(g_errMutex);
    std::string r = g_lastScriptError;
    g_lastScriptError.clear();
    return r;
}

// ---- 工具：GError → 字符串（自动释放） ----------------------------------------
static std::string errToString(GError** error) {
    if (error != nullptr && *error != nullptr) {
        std::string s = ((*error)->message != nullptr) ? (*error)->message : "unknown";
        g_error_free(*error);
        *error = nullptr;
        return s;
    }
    return "";
}

// 本地设备（attach 到 frida-server 回环端口）。改为异步获取：add_remote_device 的
// *_sync 同样会在 worker 上嵌套 pump，必须走 *_async + *_finish。每次现查（新设备
// 引用），调用方在操作回调里 g_object_unref 释放。
static const char* kFridaServerAddr = "127.0.0.1:27042";

struct DeviceAcquireCtx {
    std::shared_ptr<std::promise<FridaDevice*>> promise;
};

static void onLocalDeviceReady(GObject* obj, GAsyncResult* result, gpointer userData) {
    auto* ctx = static_cast<DeviceAcquireCtx*>(userData);
    FridaDeviceManager* manager = reinterpret_cast<FridaDeviceManager*>(obj);
    GError* error = nullptr;
    FridaDevice* device = frida_device_manager_add_remote_device_finish(manager, result, &error);
    if (device == nullptr) {
        ctx->promise->set_exception(std::make_exception_ptr(std::runtime_error(
            "add remote device (127.0.0.1:27042) failed: " + errToString(&error))));
    } else {
        ctx->promise->set_value(device);
    }
    delete ctx;
}

// worker 上异步获取 local device 并阻塞等待结果（返回的 device 持引用，调用方负责释放）。
static FridaDevice* acquireLocalDeviceAsync() {
    return asyncOnWorker<FridaDevice*>([](const auto& p) {
        auto* ctx = new DeviceAcquireCtx{ p };
        frida_device_manager_add_remote_device(g_worker.manager, kFridaServerAddr, nullptr,
                                               nullptr, onLocalDeviceReady, ctx);
    });
}

// ---- script "message" 回调 ------------------------------------------------------
static void onScriptMessage(FridaScript* script, const gchar* message, GBytes* data, gpointer) {
    long h = scriptHandleOf(script);
    std::string msg = (message != nullptr) ? message : "";
    if (data != nullptr) {
        gsize size = 0;
        gconstpointer bytes = g_bytes_get_data(data, &size);
        if (size > 0 && bytes != nullptr) {
            char hexbuf[3];
            for (gsize i = 0; i < size; i++) {
                snprintf(hexbuf, sizeof(hexbuf), "%02x",
                         static_cast<const unsigned char*>(bytes)[i]);
                msg.append(" ");
                msg.append(hexbuf);
            }
        }
    }
    pushEvent(h, msg);
}

static void onSessionDetached(FridaSession* session, FridaSessionDetachReason reason, FridaCrash* crash, gpointer) {
    // 事件推给 Kotlin；reason 0=application-requested 1=replace 2=process-replaced 3=process-terminated
    char buf[160];
    snprintf(buf, sizeof(buf),
             "{\"type\":\"detached\",\"reason\":%d,\"crash\":%s}",
             static_cast<int>(reason),
             (crash != nullptr) ? "true" : "false");
    pushEvent(-1L, buf);
    (void)session;
}

// ---- createScript/loadScript 异步回调（asyncOnWorker 用，worker context 上派发） ----
// 结构体打包回调上下文：promise（回传结果）+ 回调期仍需要的资源（options 生命周期
// 延长到 create 完成后释放）。
struct ScriptCreateCtx {
    std::shared_ptr<std::promise<long>> promise;
    FridaScriptOptions* options;
};

static void onScriptCreated(GObject* obj, GAsyncResult* result, gpointer userData) {
    auto* ctx = static_cast<ScriptCreateCtx*>(userData);
    FridaSession* session = reinterpret_cast<FridaSession*>(obj);
    GError* error = nullptr;
    FridaScript* script = frida_session_create_script_finish(session, result, &error);
    long h = 0L;
    if (script == nullptr) {
        std::string e = errToString(&error);
        setLastScriptError("createScript failed: " + e);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "createScript failed: %s", e.c_str());
    } else {
        g_signal_connect(script, "message", G_CALLBACK(onScriptMessage), nullptr);
        h = storeScript(script);
    }
    if (ctx->options != nullptr) {
        g_object_unref(ctx->options);
        ctx->options = nullptr;
    }
    ctx->promise->set_value(h);
    delete ctx;
}

struct ScriptLoadCtx {
    std::shared_ptr<std::promise<bool>> promise;
};

static void onScriptLoaded(GObject* obj, GAsyncResult* result, gpointer userData) {
    auto* ctx = static_cast<ScriptLoadCtx*>(userData);
    FridaScript* script = reinterpret_cast<FridaScript*>(obj);
    GError* error = nullptr;
    frida_script_load_finish(script, result, &error);
    bool ok = (error == nullptr);
    if (!ok) {
        std::string e = errToString(&error);
        setLastScriptError("loadScript failed: " + e);
        __android_log_print(ANDROID_LOG_ERROR, TAG, "loadScript failed: %s", e.c_str());
    }
    ctx->promise->set_value(ok);
    delete ctx;
}

// ---- 设备级操作异步回调（acquireLocalDeviceAsync 后接续使用；device 由回调释放） ----
struct EnumerateProcsCtx {
    std::shared_ptr<std::promise<std::string>> promise;
    FridaDevice* device;
};

static void onProcessesEnumerated(GObject* obj, GAsyncResult* result, gpointer userData) {
    auto* ctx = static_cast<EnumerateProcsCtx*>(userData);
    FridaDevice* dev = reinterpret_cast<FridaDevice*>(obj);
    GError* error = nullptr;
    FridaProcessList* list = frida_device_enumerate_processes_finish(dev, result, &error);
    std::string body;
    if (list == nullptr) {
        body = "{\"error\":\"" + errToString(&error) + "\"}";
    } else {
        body = "[";
        gint n = frida_process_list_size(list);
        const gchar* sep = "";
        for (gint i = 0; i != n; i++) {
            FridaProcess* p = frida_process_list_get(list, i);
            guint pid = frida_process_get_pid(p);
            const gchar* name = frida_process_get_name(p);
            body += sep;
            body += "{\"pid\":" + std::to_string(pid) +
                    ",\"name\":\"" + std::string(name) + "\"}";
            sep = ",";
        }
        g_object_unref(list);
        body += "]";
    }
    g_object_unref(ctx->device);
    ctx->promise->set_value(std::move(body));
    delete ctx;
}

struct EnumerateAppsCtx {
    std::shared_ptr<std::promise<std::string>> promise;
    FridaDevice* device;
};

static void onApplicationsEnumerated(GObject* obj, GAsyncResult* result, gpointer userData) {
    auto* ctx = static_cast<EnumerateAppsCtx*>(userData);
    FridaDevice* dev = reinterpret_cast<FridaDevice*>(obj);
    GError* error = nullptr;
    FridaApplicationList* list = frida_device_enumerate_applications_finish(dev, result, &error);
    std::string body;
    if (list == nullptr) {
        body = "{\"error\":\"" + errToString(&error) + "\"}";
    } else {
        body = "[";
        gint n = frida_application_list_size(list);
        const gchar* sep = "";
        for (gint i = 0; i != n; i++) {
            FridaApplication* app = frida_application_list_get(list, i);
            const gchar* id = frida_application_get_identifier(app);
            const gchar* name = frida_application_get_name(app);
            body += sep;
            body += "{\"identifier\":\"" + std::string(id) +
                    "\",\"name\":\"" + std::string(name) + "\"}";
            sep = ",";
        }
        g_object_unref(list);
        body += "]";
    }
    g_object_unref(ctx->device);
    ctx->promise->set_value(std::move(body));
    delete ctx;
}

struct AttachCtx {
    std::shared_ptr<std::promise<long>> promise;
    FridaDevice* device;
    guint pid;
};

static void onAttached(GObject* obj, GAsyncResult* result, gpointer userData) {
    auto* ctx = static_cast<AttachCtx*>(userData);
    FridaDevice* dev = reinterpret_cast<FridaDevice*>(obj);
    GError* error = nullptr;
    FridaSession* session = frida_device_attach_finish(dev, result, &error);
    long h = 0L;
    if (session == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "attach %u failed: %s",
                            ctx->pid, errToString(&error).c_str());
    } else {
        g_signal_connect(session, "detached", G_CALLBACK(onSessionDetached), nullptr);
        __android_log_print(ANDROID_LOG_INFO, TAG, "attached to pid %u", ctx->pid);
        h = storeSession(session);
    }
    g_object_unref(ctx->device);
    ctx->promise->set_value(h);
    delete ctx;
}

struct SpawnCtx {
    std::shared_ptr<std::promise<long>> promise;
    FridaDevice* device;
    std::string identifier;
};

static void onSpawned(GObject* obj, GAsyncResult* result, gpointer userData) {
    auto* ctx = static_cast<SpawnCtx*>(userData);
    FridaDevice* dev = reinterpret_cast<FridaDevice*>(obj);
    GError* error = nullptr;
    guint pid = frida_device_spawn_finish(dev, result, &error);
    if (error != nullptr || pid == 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "spawn %s failed: %s",
                            ctx->identifier.c_str(), errToString(&error).c_str());
    } else {
        __android_log_print(ANDROID_LOG_INFO, TAG, "spawned %s pid=%u",
                            ctx->identifier.c_str(), pid);
    }
    g_object_unref(ctx->device);
    ctx->promise->set_value(static_cast<long>(pid));
    delete ctx;
}

struct DeviceBoolCtx {
    std::shared_ptr<std::promise<bool>> promise;
    FridaDevice* device;
};

static void onResumed(GObject* obj, GAsyncResult* result, gpointer userData) {
    auto* ctx = static_cast<DeviceBoolCtx*>(userData);
    FridaDevice* dev = reinterpret_cast<FridaDevice*>(obj);
    GError* error = nullptr;
    frida_device_resume_finish(dev, result, &error);
    bool ok = (error == nullptr);
    if (!ok) errToString(&error);
    g_object_unref(ctx->device);
    ctx->promise->set_value(ok);
    delete ctx;
}

static void onKilled(GObject* obj, GAsyncResult* result, gpointer userData) {
    auto* ctx = static_cast<DeviceBoolCtx*>(userData);
    FridaDevice* dev = reinterpret_cast<FridaDevice*>(obj);
    GError* error = nullptr;
    frida_device_kill_finish(dev, result, &error);
    bool ok = (error == nullptr);
    if (!ok) errToString(&error);
    g_object_unref(ctx->device);
    ctx->promise->set_value(ok);
    delete ctx;
}

// ─────────────────────────────────────────────────────────────
// JNI 入口（static Java 方法：com/ai/fler/core/jni/FridaBindings）
// ─────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeIsAvailable(JNIEnv*, jobject) {
    return JNI_TRUE;
}

// worker liveness：不经过 worker（纯原子量读取），worker 卡死时也能返回 false，
// 供 MCP 侧 frida_ready/frida_status 快速失败判定。
extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeWorkerAlive(JNIEnv*, jobject) {
    return static_cast<jboolean>(g_worker.running.load() && !g_worker.blocked.load());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeVersion(JNIEnv* env, jobject) {
    const gchar* v = frida_version_string();
    return env->NewStringUTF(v != nullptr ? v : "unknown");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeInitialize(JNIEnv* env, jobject) {
    if (g_worker.running.load()) return JNI_TRUE;
    ensureFridaInit();
    // Java 线程上解析 FridaBindings 类引用（AttachCurrentThread 的 marshal 线程
    // 用 bootstrap classloader 找不到 app 类），并在这里启动 marshal 线程。
    if (!resolveFridaRefs(env)) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeInitialize: resolveFridaRefs failed");
    }
    startMarshalThread();
    if (g_worker.thread == nullptr) {
        g_worker.thread = g_thread_new("frida-worker", workerThreadEntry, nullptr);
    }
    for (int i = 0; i < 300; i++) {
        if (g_worker.running.load() && g_worker.manager != nullptr) return JNI_TRUE;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    return JNI_FALSE;
}

// 枚举进程 → JSON 数组 [{pid,name},...]
extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeEnumerateProcesses(JNIEnv* env, jobject) {
    try {
        FridaDevice* local = acquireLocalDeviceAsync();
        std::string out = asyncOnWorker<std::string>([local](const auto& p) {
            auto* ctx = new EnumerateProcsCtx{ p, local };
            frida_device_enumerate_processes(local, nullptr, nullptr,
                                             onProcessesEnumerated, ctx);
        });
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        std::string s = std::string("{\"error\":\"") + e.what() + "\"}";
        return env->NewStringUTF(s.c_str());
    }
}

// 枚举已安装应用（identifier + name）→ JSON 数组
extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeEnumerateApplications(JNIEnv* env, jobject) {
    try {
        FridaDevice* local = acquireLocalDeviceAsync();
        std::string out = asyncOnWorker<std::string>([local](const auto& p) {
            auto* ctx = new EnumerateAppsCtx{ p, local };
            frida_device_enumerate_applications(local, nullptr, nullptr,
                                                onApplicationsEnumerated, ctx);
        });
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        std::string s = std::string("{\"error\":\"") + e.what() + "\"}";
        return env->NewStringUTF(s.c_str());
    }
}

// attach(pid) → sessionHandle；失败返回 0
extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeAttach(JNIEnv*, jobject, jlong pid) {
    try {
        FridaDevice* local = acquireLocalDeviceAsync();
        return static_cast<jlong>(asyncOnWorker<long>([local, pid](const auto& p) {
            auto* ctx = new AttachCtx{ p, local, static_cast<guint>(pid) };
            frida_device_attach(local, static_cast<guint>(pid), nullptr, nullptr, onAttached, ctx);
        }));
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeAttach exception: %s", e.what());
        return 0L;
    }
}

// spawn(packageIdentifier) → pid；失败返回 0
extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeSpawn(JNIEnv* env, jobject, jstring jIdentifier) {
    const char* idUtf = env->GetStringUTFChars(jIdentifier, nullptr);
    if (idUtf == nullptr) return 0L;
    std::string identifier(idUtf);
    env->ReleaseStringUTFChars(jIdentifier, idUtf);
    try {
        FridaDevice* local = acquireLocalDeviceAsync();
        return static_cast<jlong>(asyncOnWorker<long>([local, identifier](const auto& p) {
            auto* ctx = new SpawnCtx{ p, local, identifier };
            frida_device_spawn(local, identifier.c_str(), nullptr, nullptr, onSpawned, ctx);
        }));
    } catch (const std::exception& e) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeSpawn exception: %s", e.what());
        return 0L;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeResume(JNIEnv*, jobject, jlong pid) {
    try {
        FridaDevice* local = acquireLocalDeviceAsync();
        return static_cast<jboolean>(asyncOnWorker<bool>([local, pid](const auto& p) {
            auto* ctx = new DeviceBoolCtx{ p, local };
            frida_device_resume(local, static_cast<guint>(pid), nullptr, onResumed, ctx);
        }));
    } catch (...) {
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeKill(JNIEnv*, jobject, jlong pid) {
    try {
        FridaDevice* local = acquireLocalDeviceAsync();
        return static_cast<jboolean>(asyncOnWorker<bool>([local, pid](const auto& p) {
            auto* ctx = new DeviceBoolCtx{ p, local };
            frida_device_kill(local, static_cast<guint>(pid), nullptr, onKilled, ctx);
        }));
    } catch (...) {
        return JNI_FALSE;
    }
}

// createScript(sessionHandle, js) → scriptHandle；失败返回 0
extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeCreateScript(JNIEnv* env, jobject, jlong sessionHandle,
                                                           jstring jSource) {
    FridaSession* session = sessionOf(static_cast<long>(sessionHandle));
    if (session == nullptr) {
        setLastScriptError("session not found: handle=" + std::to_string(sessionHandle));
        return 0L;
    }
    const char* srcUtf = env->GetStringUTFChars(jSource, nullptr);
    if (srcUtf == nullptr) {
        setLastScriptError("GetStringUTFChars failed");
        return 0L;
    }
    std::string source(srcUtf);
    env->ReleaseStringUTFChars(jSource, srcUtf);
    try {
        return static_cast<jlong>(asyncOnWorker<long>([session, source](const auto& p) {
            FridaScriptOptions* options = frida_script_options_new();
            frida_script_options_set_name(options, "fler");
            frida_script_options_set_runtime(options, FRIDA_SCRIPT_RUNTIME_QJS);
            auto* ctx = new ScriptCreateCtx{ p, options };
            frida_session_create_script(session, source.c_str(), options, nullptr,
                                        onScriptCreated, ctx);
        }));
    } catch (const std::exception& e) {
        setLastScriptError(std::string("createScript exception: ") + e.what());
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeCreateScript exception: %s", e.what());
        return 0L;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeLoadScript(JNIEnv*, jobject, jlong scriptHandle) {
    FridaScript* script = scriptOf(static_cast<long>(scriptHandle));
    if (script == nullptr) {
        setLastScriptError("script not found: handle=" + std::to_string(scriptHandle));
        return JNI_FALSE;
    }
    try {
        return static_cast<jboolean>(asyncOnWorker<bool>([script](const auto& p) {
            auto* ctx = new ScriptLoadCtx{ p };
            frida_script_load(script, nullptr, onScriptLoaded, ctx);
        }));
    } catch (const std::exception& e) {
        setLastScriptError(std::string("loadScript exception: ") + e.what());
        __android_log_print(ANDROID_LOG_ERROR, TAG, "nativeLoadScript exception: %s", e.what());
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeLastScriptError(JNIEnv* env, jobject) {
    std::string e = takeLastScriptError();
    return env->NewStringUTF(e.c_str());
}

// unload/detach 一律异步：*_sync 在 worker 的 invoke job 里会嵌套泵同一个
// GMainContext（目标进程有活跃 hook 时尤其容易阻塞），改成 *_async + *_finish
// 回调式，worker 主循环不阻塞，回调在 worker context 上收尾清理。
static void onScriptUnloaded(GObject* obj, GAsyncResult* result, gpointer /*userData*/) {
    FridaScript* script = reinterpret_cast<FridaScript*>(obj);
    frida_script_unload_finish(script, result, nullptr);
    g_signal_handlers_disconnect_by_data(script, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_handleMutex);
        removeScript(script);
        g_object_unref(script);
    }
}

static void onDetachDone(GObject* obj, GAsyncResult* result, gpointer /*userData*/) {
    FridaSession* session = reinterpret_cast<FridaSession*>(obj);
    frida_session_detach_finish(session, result, nullptr);
    {
        std::lock_guard<std::mutex> lock(g_handleMutex);
        removeSession(session);
        g_object_unref(session);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeUnloadScript(JNIEnv*, jobject, jlong scriptHandle) {
    FridaScript* script = scriptOf(static_cast<long>(scriptHandle));
    if (script == nullptr) return JNI_FALSE;
    postToWorker([script]() { frida_script_unload(script, nullptr, onScriptUnloaded, nullptr); });
    return JNI_TRUE;
}

// post(scriptHandle, json)：向脚本发送消息（rpc 入口）
extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativePost(JNIEnv* env, jobject, jlong scriptHandle,
                                                   jstring jJson) {
    FridaScript* script = scriptOf(static_cast<long>(scriptHandle));
    if (script == nullptr) return;
    const char* jsonUtf = env->GetStringUTFChars(jJson, nullptr);
    if (jsonUtf == nullptr) return;
    std::string json(jsonUtf);
    env->ReleaseStringUTFChars(jJson, jsonUtf);
    postToWorker([script, json]() { frida_script_post(script, json.c_str(), nullptr); });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeDetach(JNIEnv*, jobject, jlong sessionHandle) {
    FridaSession* session = sessionOf(static_cast<long>(sessionHandle));
    if (session == nullptr) return JNI_FALSE;
    postToWorker([session]() { frida_session_detach(session, nullptr, onDetachDone, nullptr); });
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeClose(JNIEnv*, jobject) {
    // 清空全部 session/script
    {
        std::lock_guard<std::mutex> lock(g_handleMutex);
        for (auto& [h, session] : g_sessionMap) (void)h, g_object_unref(session);
        for (auto& [h, script] : g_scriptMap) (void)h, g_object_unref(script);
        g_sessionMap.clear();
        g_sessionRev.clear();
        g_scriptMap.clear();
        g_scriptRev.clear();
    }
    postToWorker([]() {
        if (g_worker.manager != nullptr) {
            frida_device_manager_close_sync(g_worker.manager, nullptr, nullptr);
        }
    });
}

#else // !FLER_ENABLE_FRIDA —— 安全 stub

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeIsAvailable(JNIEnv*, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeWorkerAlive(JNIEnv*, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("disabled");
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeInitialize(JNIEnv*, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeEnumerateProcesses(JNIEnv* env, jobject) {
    return env->NewStringUTF("{\"error\":\"frida disabled\"}");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeEnumerateApplications(JNIEnv* env, jobject) {
    return env->NewStringUTF("{\"error\":\"frida disabled\"}");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeAttach(JNIEnv*, jobject, jlong) {
    return 0L;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeSpawn(JNIEnv*, jobject, jstring) {
    return 0L;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeResume(JNIEnv*, jobject, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeKill(JNIEnv*, jobject, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeCreateScript(JNIEnv*, jobject, jlong, jstring) {
    return 0L;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeLoadScript(JNIEnv*, jobject, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeUnloadScript(JNIEnv*, jobject, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativePost(JNIEnv*, jobject, jlong, jstring) {}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeDetach(JNIEnv*, jobject, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeClose(JNIEnv*, jobject) {}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_FridaBindings_nativeLastScriptError(JNIEnv* env, jobject) {
    return env->NewStringUTF("");
}

#endif // FLER_ENABLE_FRIDA

// ─────────────────────────────────────────────────────────────
// JavaVM 缓存：由 elf_parser_jni.cpp 的 JNI_OnLoad 调用（避免重复 JNI_OnLoad 符号）
// ─────────────────────────────────────────────────────────────
extern "C" void fridaCacheJavaVm(JavaVM* vm) {
    g_vm = vm;
    if (vm != nullptr) {
        ensureFridaInit();
    } else {
        {
            std::lock_guard<std::mutex> lock(g_qMutex);
            g_quitMarshal.store(true);
        }
        g_qCv.notify_all();
    }
}
