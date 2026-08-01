#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <vector>

static const char* TAG = "FlerCapstoneJNI";

// Capstone 常量（不引头文件，与引擎包内 capstone 4.x ABI 一致）
#define CS_ARCH_ARM64 3
#define CS_MODE_ARM 0

// Capstone 函数指针
typedef size_t (*cs_open_t)(uint32_t arch, uint32_t mode, void** handle);
typedef int (*cs_asm_t)(void* handle, const char* assembly, uint64_t address,
                        void** insn, size_t* count);
typedef void* (*cs_malloc_t)(void* handle);
typedef bool (*cs_disasm_iter_t)(void* handle, const uint8_t** code, size_t* size,
                                 uint64_t* address, void* insn);
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
    auto cs_malloc = reinterpret_cast<cs_malloc_t>(dlsym(handle, "cs_malloc"));
    auto cs_disasm_iter = reinterpret_cast<cs_disasm_iter_t>(dlsym(handle, "cs_disasm_iter"));
    auto cs_free = reinterpret_cast<cs_free_t>(dlsym(handle, "cs_free"));
    auto cs_close = reinterpret_cast<cs_close_t>(dlsym(handle, "cs_close"));
    if (!cs_open || !cs_malloc || !cs_disasm_iter || !cs_free || !cs_close) {
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

    // 逐条解码：用 cs_disasm_iter 循环遍历整个缓冲区；
    // 不可解码的字输出为 4 字节 `.word` 伪指令并继续，保证声明的函数范围完整展示。
    // （不用 cs_disasm+SKIPDATA：后者会把大段数据合并成 size>16 的伪指令，
    //   而我们的字节拷贝固定 16 字节上限，越界读会 SIGSEGV。）
    std::vector<CsInsn> results;
    void* insnBuf = cs_malloc(csh);
    const uint8_t* cursor = code.data();
    size_t remaining = code.size();
    uint64_t addr = static_cast<uint64_t>(jBase);
    if (insnBuf) {
        while (remaining >= 4) {
            CsInsn item;
            memset(&item, 0, sizeof(item));
            if (cs_disasm_iter(csh, &cursor, &remaining, &addr, insnBuf)) {
                memcpy(&item, insnBuf, sizeof(CsInsn));
                item.detail = nullptr; // insnBuf 在循环后释放，detail 不再使用
            } else {
                // 不可解码：输出 .word 0x........ 并前进 4 字节
                item.address = addr;
                item.size = 4;
                memcpy(item.bytes, cursor, 4);
                snprintf(item.mnemonic, sizeof(item.mnemonic), ".word");
                snprintf(item.op_str, sizeof(item.op_str), "0x%08x",
                         *(const uint32_t*)cursor);
                cursor += 4;
                remaining -= 4;
                addr += 4;
            }
            results.push_back(item);
        }
        // 尾部不足 4 字节（罕见）：输出 .byte
        if (remaining > 0) {
            CsInsn item;
            memset(&item, 0, sizeof(item));
            item.address = addr;
            item.size = static_cast<uint16_t>(remaining);
            memcpy(item.bytes, cursor, remaining);
            snprintf(item.mnemonic, sizeof(item.mnemonic), ".byte");
            char ops[96];
            int o = 0;
            for (size_t k = 0; k < remaining; k++) {
                o += snprintf(ops + o, sizeof(ops) - (size_t)o, "%s0x%02x",
                              k > 0 ? "," : "", cursor[k]);
            }
            snprintf(item.op_str, sizeof(item.op_str), "%s", ops);
            results.push_back(item);
        }
        cs_free(insnBuf, 0);
    }

    jclass cls = env->FindClass("com/ai/fler/core/jni/DisasmInstruction");
    if (!cls) {
        cs_close(&csh);
        dlclose(handle);
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(
        cls, "<init>", "(JILjava/lang/String;Ljava/lang/String;[B)V");
    if (!ctor) {
        env->DeleteLocalRef(cls);
        cs_close(&csh);
        dlclose(handle);
        return nullptr;
    }

    const size_t count = results.size();
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(count), cls, nullptr);

    for (size_t i = 0; i < count; i++) {
        const CsInsn& it = results[i];
        // 字节拷贝固定上限 16，防止越界读（防御）
        size_t byteCount = it.size > 16 ? 16 : it.size;
        jstring jmn = env->NewStringUTF(it.mnemonic);
        jstring jops = env->NewStringUTF(it.op_str);
        jbyteArray jbytes = env->NewByteArray(static_cast<jsize>(byteCount));
        env->SetByteArrayRegion(jbytes, 0, static_cast<jsize>(byteCount),
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

    cs_close(&csh);
    dlclose(handle);
    return arr;
}

/**
 * 用 Capstone 汇编 ARM64 指令（cs_asm），返回编码后的机器码。
 *
 * 支持多条指令（文本以 ';' 或换行分隔），返回全部编码字节。
 * 分支指令的偏移量由传入的 address（指令所在文件偏移）正确计算。
 *
 * @return 机器码字节数组；Capstone 不可用或汇编失败返回 null
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ai_fler_core_jni_CapstoneBindings_nativeAssemble(
    JNIEnv* env, jobject /*thiz*/,
    jstring jCapstonePath, jstring jAssembly, jlong jAddress) {

    const char* capstone_path = env->GetStringUTFChars(jCapstonePath, nullptr);
    const char* assembly = env->GetStringUTFChars(jAssembly, nullptr);

    void* handle = dlopen(capstone_path, RTLD_NOLOAD);
    if (!handle) {
        dlerror();
        handle = dlopen(capstone_path, RTLD_NOW | RTLD_GLOBAL);
    }
    env->ReleaseStringUTFChars(jCapstonePath, capstone_path);
    env->ReleaseStringUTFChars(jAssembly, assembly);
    if (!handle) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "assemble: dlopen capstone failed: %s", dlerror());
        return nullptr;
    }

    auto cs_open = reinterpret_cast<cs_open_t>(dlsym(handle, "cs_open"));
    auto cs_asm = reinterpret_cast<cs_asm_t>(dlsym(handle, "cs_asm"));
    auto cs_free = reinterpret_cast<cs_free_t>(dlsym(handle, "cs_free"));
    auto cs_close = reinterpret_cast<cs_close_t>(dlsym(handle, "cs_close"));
    if (!cs_open || !cs_asm || !cs_free || !cs_close) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "assemble: dlsym symbols failed");
        dlclose(handle);
        return nullptr;
    }

    void* csh = nullptr;
    size_t err = cs_open(CS_ARCH_ARM64, CS_MODE_ARM, &csh);
    if (err != 0 || !csh) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "assemble: cs_open failed err=%zu", err);
        dlclose(handle);
        return nullptr;
    }

    void* insn = nullptr;
    size_t count = 0;
    int aerr = cs_asm(csh, assembly, static_cast<uint64_t>(jAddress), &insn, &count);
    if (aerr != 0 || count == 0 || !insn) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "assemble: cs_asm failed err=%d count=%zu",
                            aerr, count);
        cs_close(&csh);
        dlclose(handle);
        return nullptr;
    }

    auto* insns = static_cast<CsInsn*>(insn);
    size_t total = 0;
    for (size_t i = 0; i < count; i++) total += insns[i].size;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(total));
    jbyte* out = env->GetByteArrayElements(result, nullptr);
    size_t off = 0;
    for (size_t i = 0; i < count; i++) {
        memcpy(out + off, insns[i].bytes, insns[i].size);
        off += insns[i].size;
    }
    env->ReleaseByteArrayElements(result, out, 0);

    cs_free(insn, count);
    cs_close(&csh);
    dlclose(handle);
    return result;
}
