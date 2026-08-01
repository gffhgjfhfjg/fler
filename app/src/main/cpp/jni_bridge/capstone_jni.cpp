#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <vector>

static const char* TAG = "FlerCapstoneJNI";

// Capstone 常量（不引头文件，与引擎包内 capstone 4.x ABI 一致）
#define CS_ARCH_ARM64 3
#define CS_MODE_ARM 0

// Capstone 函数指针
typedef size_t (*cs_open_t)(uint32_t arch, uint32_t mode, void** handle);
typedef size_t (*cs_disasm_t)(void* handle, const uint8_t* code, size_t code_size,
                              uint64_t address, size_t count, void** insn);
typedef void (*cs_free_t)(void* insn, size_t count);
typedef void (*cs_close_t)(void* handle);

// cs_insn 结构（capstone 4.x 布局，仅 public 字段）
struct CsInsn {
    uint32_t id;
    uint64_t address;
    uint16_t size;
    uint8_t bytes[16];
    char mnemonic[32];
    char op_str[160];
    void* detail;
};

/**
 * 复用引擎包中已加载的 libcapstone.so 做反汇编。
 *
 * 与 blutter_jni.cpp 同款 dlopen 策略：
 *   - dlopen(capstonePath, RTLD_NOLOAD) 命中 EngineLoader 已 System.load 的库
 *   - 未命中则 RTLD_NOW | RTLD_GLOBAL 重新打开（独立的 handle，capstone 无状态）
 *
 * @return Array<DisasmInstruction>，符号缺失/加载失败返回 null（调用方回退自研解码器）
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_CapstoneBindings_nativeDisasm(
    JNIEnv* env, jobject /*thiz*/,
    jstring jCapstonePath, jbyteArray jCode, jlong jBase) {

    const char* capstone_path = env->GetStringUTFChars(jCapstonePath, nullptr);
    jsize codeLen = env->GetArrayLength(jCode);
    std::vector<uint8_t> code(static_cast<size_t>(codeLen));
    env->GetByteArrayRegion(jCode, 0, codeLen, reinterpret_cast<jbyte*>(code.data()));

    void* handle = dlopen(capstone_path, RTLD_NOLOAD);
    if (!handle) {
        dlerror();
        handle = dlopen(capstone_path, RTLD_NOW | RTLD_GLOBAL);
    }
    env->ReleaseStringUTFChars(jCapstonePath, capstone_path);
    if (!handle) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlopen capstone failed: %s", dlerror());
        return nullptr;
    }

    auto cs_open = reinterpret_cast<cs_open_t>(dlsym(handle, "cs_open"));
    auto cs_disasm = reinterpret_cast<cs_disasm_t>(dlsym(handle, "cs_disasm"));
    auto cs_free = reinterpret_cast<cs_free_t>(dlsym(handle, "cs_free"));
    auto cs_close = reinterpret_cast<cs_close_t>(dlsym(handle, "cs_close"));
    if (!cs_open || !cs_disasm || !cs_free || !cs_close) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "dlsym capstone symbols failed");
        dlclose(handle);
        return nullptr;
    }

    void* csh = nullptr;
    size_t err = cs_open(CS_ARCH_ARM64, CS_MODE_ARM, &csh);
    if (err != 0 || !csh) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "cs_open failed err=%zu", err);
        dlclose(handle);
        return nullptr;
    }

    void* insn = nullptr;
    size_t count = 0;
    if (!code.empty()) {
        count = cs_disasm(csh, code.data(), code.size(),
                          static_cast<uint64_t>(jBase), 0, &insn);
    }

    jclass cls = env->FindClass("com/ai/fler/core/jni/DisasmInstruction");
    jmethodID ctor = env->GetMethodID(
        cls, "<init>", "(JILjava/lang/String;Ljava/lang/String;[B)V");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(count), cls, nullptr);

    auto* insns = static_cast<CsInsn*>(insn);
    for (size_t i = 0; i < count; i++) {
        const CsInsn& it = insns[i];
        jstring jmn = env->NewStringUTF(it.mnemonic);
        jstring jops = env->NewStringUTF(it.op_str);
        jbyteArray jbytes = env->NewByteArray(it.size);
        env->SetByteArrayRegion(jbytes, 0, it.size,
                                reinterpret_cast<const jbyte*>(it.bytes));
        jobject obj = env->NewObject(
            cls, ctor,
            static_cast<jlong>(it.address),
            static_cast<jint>(it.size),
            jmn, jops, jbytes);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), obj);
        env->DeleteLocalRef(jbytes);
        env->DeleteLocalRef(jmn);
        env->DeleteLocalRef(jops);
        env->DeleteLocalRef(obj);
    }

    if (insn) cs_free(insn, count);
    cs_close(&csh);
    dlclose(handle);
    return arr;
}
