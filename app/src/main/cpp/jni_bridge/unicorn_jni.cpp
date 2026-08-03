// =============================================================================
// Unicorn 仿真引擎 JNI 桥（M2 完整实现）
//
// 静态链接：libunicorn.a（build-unicorn workflow 产物，arm64 单架构）直接链入
// fler_jni.so，不依赖任何外部 .so。
//
// 编译开关 FLER_ENABLE_UNICORN 由 CMake 的 ENABLE_UNICORN 选项注入：
// 关闭时本文件仍编译，但全部 JNI 方法退化为安全 stub（isAvailable=false），
// 保证 Kotlin 层 UnicornBindings 调用不会 UnsatisfiedLinkError——
// 这是三级回滚的第一级（编译期）。
//
// 停止原因码（与 Kotlin StopReason 枚举 ordinal 对齐，多出的 6=FUNCTION_RETURN
// 在 Kotlin 侧扩展该枚举值）：
//   0=NONE 1=BREAKPOINT 2=SINGLE_STEP 3=TIMEOUT 4=ERROR 5=INTERRUPTED 6=FUNCTION_RETURN
// =============================================================================

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <set>
#include <string>
#include <unordered_map>
#include <vector>

static const char* TAG = "FlerUnicornJNI";

#ifdef FLER_ENABLE_UNICORN

#include <unicorn/unicorn.h>

// ─────────────────────────────────────────────────────────────
// 地址空间布局（open 时固定）
// ─────────────────────────────────────────────────────────────
static constexpr uint64_t STACK_BASE = 0x40000000;   // 栈底
static constexpr uint64_t STACK_SIZE = 1 * 1024 * 1024;
static constexpr uint64_t HEAP_BASE  = 0x50000000;   // 简易 heap
static constexpr uint64_t HEAP_SIZE  = 8 * 1024 * 1024;
static constexpr uint64_t SENTINEL_ADDR = 0xDEADBEEF0000; // 哨兵返回地址

// 停止原因码
enum StopCode : int {
    STOP_NONE = 0,
    STOP_BREAKPOINT = 1,
    STOP_SINGLE_STEP = 2,
    STOP_TIMEOUT = 3,
    STOP_ERROR = 4,
    STOP_INTERRUPTED = 5,
    STOP_FUNCTION_RETURN = 6,
};

struct EmuContext {
    uc_engine* uc = nullptr;
    std::set<uint64_t> breakpoints;
    std::mutex bpMutex;      // 保护 breakpoints（code hook 与 JNI 线程共享）
    std::mutex opMutex;      // 串行化 uc_* 操作（除 uc_emu_stop 外均非线程安全）
    std::atomic<bool> stopRequested{false};
    std::atomic<int> stopReason{STOP_NONE};
    uint64_t instrCount = 0;   // code hook 内累计（仅 run 期间有效）
    std::chrono::steady_clock::time_point deadline{};
    bool hasDeadline = false;
};

// 指令级钩子：取消 > 哨兵 > 断点 > 计数 > 超时（方案约定的优先级顺序）
static void codeHook(uc_engine* uc, uint64_t address, uint32_t /*size*/, void* user) {
    auto* ctx = static_cast<EmuContext*>(user);

    if (ctx->stopRequested.exchange(false)) {
        ctx->stopReason.store(STOP_INTERRUPTED);
        uc_emu_stop(uc);
        return;
    }
    if (address == SENTINEL_ADDR) {
        ctx->stopReason.store(STOP_FUNCTION_RETURN);
        uc_emu_stop(uc);
        return;
    }
    {
        std::lock_guard<std::mutex> lock(ctx->bpMutex);
        if (ctx->breakpoints.count(address) > 0) {
            ctx->stopReason.store(STOP_BREAKPOINT);
            uc_emu_stop(uc);
            return;
        }
    }
    ++ctx->instrCount;
    // 指令数上限由 uc_emu_start 的 count 参数执行（执行后停止）；
    // hook 在指令执行前触发，若在此停机则第 N 条永远不会执行（单步不前进）
    if (ctx->hasDeadline && std::chrono::steady_clock::now() >= ctx->deadline) {
        ctx->stopReason.store(STOP_TIMEOUT);
        uc_emu_stop(uc);
        return;
    }
}

// 非法内存访问钩子：记录原因并停止（默认行为是继续执行，会产生误导）
static bool memInvalidHook(uc_engine* uc, uc_mem_type type, uint64_t address,
                           int size, int64_t /*value*/, void* user) {
    auto* ctx = static_cast<EmuContext*>(user);
    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "invalid mem access type=%d addr=0x%llx size=%d",
                        type, (unsigned long long)address, size);
    ctx->stopReason.store(STOP_ERROR);
    uc_emu_stop(uc);
    return false;
}

// ARM64 寄存器名 → UC_ARM64_REG_* 映射
static int arm64RegId(const std::string& name) {
    static const std::unordered_map<std::string, int> kRegs = {
        {"x0", UC_ARM64_REG_X0}, {"x1", UC_ARM64_REG_X1}, {"x2", UC_ARM64_REG_X2},
        {"x3", UC_ARM64_REG_X3}, {"x4", UC_ARM64_REG_X4}, {"x5", UC_ARM64_REG_X5},
        {"x6", UC_ARM64_REG_X6}, {"x7", UC_ARM64_REG_X7}, {"x8", UC_ARM64_REG_X8},
        {"x9", UC_ARM64_REG_X9}, {"x10", UC_ARM64_REG_X10}, {"x11", UC_ARM64_REG_X11},
        {"x12", UC_ARM64_REG_X12}, {"x13", UC_ARM64_REG_X13}, {"x14", UC_ARM64_REG_X14},
        {"x15", UC_ARM64_REG_X15}, {"x16", UC_ARM64_REG_X16}, {"x17", UC_ARM64_REG_X17},
        {"x18", UC_ARM64_REG_X18}, {"x19", UC_ARM64_REG_X19}, {"x20", UC_ARM64_REG_X20},
        {"x21", UC_ARM64_REG_X21}, {"x22", UC_ARM64_REG_X22}, {"x23", UC_ARM64_REG_X23},
        {"x24", UC_ARM64_REG_X24}, {"x25", UC_ARM64_REG_X25}, {"x26", UC_ARM64_REG_X26},
        {"x27", UC_ARM64_REG_X27}, {"x28", UC_ARM64_REG_X28},
        {"x29", UC_ARM64_REG_X29}, {"fp", UC_ARM64_REG_FP},
        {"x30", UC_ARM64_REG_X30}, {"lr", UC_ARM64_REG_LR},
        {"sp", UC_ARM64_REG_SP}, {"pc", UC_ARM64_REG_PC},
        {"pstate", UC_ARM64_REG_PSTATE}, {"nzcv", UC_ARM64_REG_NZCV},
    };
    auto it = kRegs.find(name);
    return it == kRegs.end() ? -1 : it->second;
}

// ─────────────────────────────────────────────────────────────
// 生命周期
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeIsAvailable(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    return uc_arch_supported(UC_ARCH_ARM64) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeGetVersion(
    JNIEnv* env, jclass /*clazz*/) {
    unsigned int major = 0;
    unsigned int minor = 0;
    uc_version(&major, &minor);
    std::string ver = std::to_string(major) + "." + std::to_string(minor);
    return env->NewStringUTF(ver.c_str());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeOpen(
    JNIEnv* /*env*/, jclass /*clazz*/) {
    auto* ctx = new EmuContext();
    uc_err err = uc_open(UC_ARCH_ARM64, UC_MODE_ARM, &ctx->uc);
    if (err != UC_ERR_OK) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "uc_open failed: %s", uc_strerror(err));
        delete ctx;
        return 0;
    }

    unsigned int major = 0;
    unsigned int minor = 0;
    uc_version(&major, &minor);

    // 栈：SP 指向栈顶（16 字节对齐）
    err = uc_mem_map(ctx->uc, STACK_BASE, STACK_SIZE, UC_PROT_READ | UC_PROT_WRITE);
    if (err == UC_ERR_OK) {
        uint64_t sp = STACK_BASE + STACK_SIZE;
        uc_reg_write(ctx->uc, UC_ARM64_REG_SP, &sp);
    }
    // 简易 heap（malloc 替身，供用户代码读写）
    uc_mem_map(ctx->uc, HEAP_BASE, HEAP_SIZE, UC_PROT_READ | UC_PROT_WRITE);
    // 哨兵页：LR 指向这里，执行到此即视为函数返回
    uc_mem_map(ctx->uc, SENTINEL_ADDR, 0x1000, UC_PROT_READ | UC_PROT_EXEC);

    uc_hook hh = 0;
    uc_hook_add(ctx->uc, &hh, UC_HOOK_CODE, (void*)codeHook, ctx, 1, 0);
    uc_hook_add(ctx->uc, &hh,
                UC_HOOK_MEM_READ_UNMAPPED | UC_HOOK_MEM_WRITE_UNMAPPED |
                UC_HOOK_MEM_FETCH_UNMAPPED,
                (void*)memInvalidHook, ctx, 1, 0);

    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "emulation session opened (unicorn %u.%u)", major, minor);
    return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeClose(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx) return;
    if (ctx->uc) uc_close(ctx->uc);
    delete ctx;
}

// ─────────────────────────────────────────────────────────────
// 内存
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeMapMemory(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong address, jlong size, jint perms) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx || !ctx->uc) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->opMutex);
    uc_err err = uc_mem_map(ctx->uc, static_cast<uint64_t>(address),
                            static_cast<size_t>(size), static_cast<uint32_t>(perms));
    if (err != UC_ERR_OK) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "uc_mem_map(0x%llx, %lld) failed: %s",
                            (unsigned long long)address, (long long)size, uc_strerror(err));
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeReadMemory(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jlong address, jlong size) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx || !ctx->uc || size <= 0) return nullptr;
    std::vector<uint8_t> buf(static_cast<size_t>(size));
    {
        std::lock_guard<std::mutex> lock(ctx->opMutex);
        uc_err err = uc_mem_read(ctx->uc, static_cast<uint64_t>(address),
                                 buf.data(), buf.size());
        if (err != UC_ERR_OK) return nullptr;
    }
    jbyteArray result = env->NewByteArray(buf.size());
    env->SetByteArrayRegion(result, 0, buf.size(), reinterpret_cast<const jbyte*>(buf.data()));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeWriteMemory(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jlong address, jbyteArray jdata) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx || !ctx->uc || !jdata) return JNI_FALSE;
    jsize len = env->GetArrayLength(jdata);
    if (len <= 0) return JNI_FALSE;
    std::vector<uint8_t> buf(len);
    env->GetByteArrayRegion(jdata, 0, len, reinterpret_cast<jbyte*>(buf.data()));
    std::lock_guard<std::mutex> lock(ctx->opMutex);
    uc_err err = uc_mem_write(ctx->uc, static_cast<uint64_t>(address), buf.data(), buf.size());
    return err == UC_ERR_OK ? JNI_TRUE : JNI_FALSE;
}

// ─────────────────────────────────────────────────────────────
// 寄存器
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeReadRegister(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring jname) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx || !ctx->uc) return 0;
    const char* cname = env->GetStringUTFChars(jname, nullptr);
    int regId = arm64RegId(cname);
    env->ReleaseStringUTFChars(jname, cname);
    if (regId < 0) return 0;
    uint64_t value = 0;
    std::lock_guard<std::mutex> lock(ctx->opMutex);
    if (uc_reg_read(ctx->uc, regId, &value) != UC_ERR_OK) return 0;
    return static_cast<jlong>(value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeWriteRegister(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jstring jname, jlong value) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx || !ctx->uc) return JNI_FALSE;
    const char* cname = env->GetStringUTFChars(jname, nullptr);
    int regId = arm64RegId(cname);
    env->ReleaseStringUTFChars(jname, cname);
    if (regId < 0) return JNI_FALSE;
    uint64_t v = static_cast<uint64_t>(value);
    std::lock_guard<std::mutex> lock(ctx->opMutex);
    return uc_reg_write(ctx->uc, regId, &v) == UC_ERR_OK ? JNI_TRUE : JNI_FALSE;
}

// 批量读寄存器：names/values 两个并行数组，避免逐个 JNI 往返
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeReadAllRegisters(
    JNIEnv* env, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx || !ctx->uc) return nullptr;

    static const char* kNames[] = {
        "x0", "x1", "x2", "x3", "x4", "x5", "x6", "x7",
        "x8", "x9", "x10", "x11", "x12", "x13", "x14", "x15",
        "x16", "x17", "x18", "x19", "x20", "x21", "x22", "x23",
        "x24", "x25", "x26", "x27", "x28", "fp", "lr", "sp", "pc", "nzcv",
    };
    constexpr int kCount = sizeof(kNames) / sizeof(kNames[0]);

    jclass pairCls = env->FindClass("com/ai/fler/core/jni/UnicornBindings$RegEntry");
    jmethodID ctor = env->GetMethodID(pairCls, "<init>", "(Ljava/lang/String;J)V");
    jobjectArray arr = env->NewObjectArray(kCount, pairCls, nullptr);

    std::lock_guard<std::mutex> lock(ctx->opMutex);
    for (int i = 0; i < kCount; ++i) {
        int regId = arm64RegId(kNames[i]);
        uint64_t value = 0;
        if (regId >= 0) uc_reg_read(ctx->uc, regId, &value);
        jstring jname = env->NewStringUTF(kNames[i]);
        jobject entry = env->NewObject(pairCls, ctor, jname, static_cast<jlong>(value));
        env->SetObjectArrayElement(arr, i, entry);
        env->DeleteLocalRef(jname);
        env->DeleteLocalRef(entry);
    }
    return arr;
}

// ─────────────────────────────────────────────────────────────
// 执行控制
// ─────────────────────────────────────────────────────────────

// 运行并返回 [stopReason, pc, instrCount]
static jlongArray runCore(JNIEnv* env, EmuContext* ctx, uint64_t maxInstrs, jlong timeoutMs) {
    ctx->stopReason.store(STOP_NONE);
    ctx->instrCount = 0;
    ctx->hasDeadline = timeoutMs > 0;
    if (ctx->hasDeadline) {
        ctx->deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    }

    uint64_t pc = 0;
    uc_reg_read(ctx->uc, UC_ARM64_REG_PC, &pc);

    // uc_emu_start 自带 timeout（微秒）作为兜底；指令数上限用第 4 参 count
    // （执行完 count 条后停在下一条指令边界，PC 已前进）；断点/哨兵由 code hook 处理。
    // until 传 0 表示不因地址停止（停止逻辑全部集中在 hook，避免与断点语义冲突）。
    // 注意：不能用 codeHook 的 maxInstrs 检查做指令数限制——hook 在指令执行前
    // 触发，会停在尚未执行的第 N 条上（单步将永远不前进）。
    uint64_t timeoutUs = timeoutMs > 0 ? static_cast<uint64_t>(timeoutMs) * 1000 : 0;
    uc_err err = uc_emu_start(ctx->uc, pc, 0, timeoutUs,
                              static_cast<size_t>(maxInstrs));

    int reason = ctx->stopReason.load();
    if (err != UC_ERR_OK && reason != STOP_TIMEOUT) {
        // UC_ERR_TIMEOUT 已被 hook 标为 STOP_TIMEOUT；其余错误统一归 ERROR。
        // 注意：uc_emu_stop 触发的停止返回值是 UC_ERR_OK。
        __android_log_print(ANDROID_LOG_WARN, TAG, "uc_emu_start error: %s", uc_strerror(err));
        reason = STOP_ERROR;
    }

    uint64_t pcAfter = 0;
    uc_reg_read(ctx->uc, UC_ARM64_REG_PC, &pcAfter);

    jlongArray result = env->NewLongArray(3);
    jlong vals[3] = {reason, static_cast<jlong>(pcAfter), static_cast<jlong>(ctx->instrCount)};
    env->SetLongArrayRegion(result, 0, 3, vals);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeRun(
    JNIEnv* env, jclass /*clazz*/, jlong handle, jlong instrCount, jlong timeoutMs) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx || !ctx->uc) return nullptr;
    std::lock_guard<std::mutex> lock(ctx->opMutex);
    return runCore(env, ctx, static_cast<uint64_t>(instrCount < 0 ? 0 : instrCount), timeoutMs);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeStep(
    JNIEnv* env, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx || !ctx->uc) return nullptr;
    std::lock_guard<std::mutex> lock(ctx->opMutex);
    jlongArray r = runCore(env, ctx, 1, 0);
    // 单步语义：指令数限制停止时原因码修正为 SINGLE_STEP
    if (r) {
        jlong vals[3];
        env->GetLongArrayRegion(r, 0, 3, vals);
        if (vals[0] == STOP_NONE) {
            vals[0] = STOP_SINGLE_STEP;
            env->SetLongArrayRegion(r, 0, 3, vals);
        }
    }
    return r;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeRequestStop(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx) return;
    ctx->stopRequested.store(true);
    // uc_emu_stop 线程安全：让正在执行的 uc_emu_start 尽快返回，
    // hook 会在下个指令边界读取 stopRequested 并标记 INTERRUPTED
    if (ctx->uc) uc_emu_stop(ctx->uc);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeSetPc(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong pc) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx || !ctx->uc) return JNI_FALSE;
    uint64_t v = static_cast<uint64_t>(pc);
    std::lock_guard<std::mutex> lock(ctx->opMutex);
    return uc_reg_write(ctx->uc, UC_ARM64_REG_PC, &v) == UC_ERR_OK ? JNI_TRUE : JNI_FALSE;
}

// ─────────────────────────────────────────────────────────────
// 断点
// ─────────────────────────────────────────────────────────────

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeAddBreakpoint(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong address) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->bpMutex);
    ctx->breakpoints.insert(static_cast<uint64_t>(address));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeRemoveBreakpoint(
    JNIEnv* /*env*/, jclass /*clazz*/, jlong handle, jlong address) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(ctx->bpMutex);
    return ctx->breakpoints.erase(static_cast<uint64_t>(address)) > 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeListBreakpoints(
    JNIEnv* env, jclass /*clazz*/, jlong handle) {
    auto* ctx = reinterpret_cast<EmuContext*>(handle);
    if (!ctx) return nullptr;
    std::vector<jlong> addrs;
    {
        std::lock_guard<std::mutex> lock(ctx->bpMutex);
        addrs.reserve(ctx->breakpoints.size());
        for (uint64_t a : ctx->breakpoints) addrs.push_back(static_cast<jlong>(a));
    }
    jlongArray result = env->NewLongArray(addrs.size());
    if (!addrs.empty()) env->SetLongArrayRegion(result, 0, addrs.size(), addrs.data());
    return result;
}

#else // !FLER_ENABLE_UNICORN —— 编译期禁用，全部退化为安全 stub

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeIsAvailable(JNIEnv*, jclass) { return JNI_FALSE; }

extern "C" JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeGetVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("disabled");
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeOpen(JNIEnv*, jclass) { return 0; }

extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeClose(JNIEnv*, jclass, jlong) {}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeMapMemory(JNIEnv*, jclass, jlong, jlong, jlong, jint) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeReadMemory(JNIEnv*, jclass, jlong, jlong, jlong) {
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeWriteMemory(JNIEnv*, jclass, jlong, jlong, jbyteArray) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeReadRegister(JNIEnv*, jclass, jlong, jstring) {
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeWriteRegister(JNIEnv*, jclass, jlong, jstring, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeReadAllRegisters(JNIEnv*, jclass, jlong) {
    return nullptr;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeRun(JNIEnv*, jclass, jlong, jlong, jlong) {
    return nullptr;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeStep(JNIEnv*, jclass, jlong) { return nullptr; }

extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeRequestStop(JNIEnv*, jclass, jlong) {}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeSetPc(JNIEnv*, jclass, jlong, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeAddBreakpoint(JNIEnv*, jclass, jlong, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeRemoveBreakpoint(JNIEnv*, jclass, jlong, jlong) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_ai_fler_core_jni_UnicornBindings_nativeListBreakpoints(JNIEnv*, jclass, jlong) {
    return nullptr;
}

#endif // FLER_ENABLE_UNICORN
