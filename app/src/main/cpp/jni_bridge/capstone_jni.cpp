#include <jni.h>
#include <android/log.h>
#include <capstone/capstone.h>
#include <cstdint>
#include <cstring>
#include <cstdio>
#include <vector>

static const char* TAG = "FlerCapstoneJNI";

// 自定义结果结构：仅保留需要的 public 字段，detail 置空避免悬垂指针
struct CsInsn {
    uint32_t id;
    uint64_t address;
    uint16_t size;
    uint8_t bytes[24];
    char mnemonic[32];
    char op_str[160];
    void* detail;
};

/**
 * 用静态链接的 Capstone 做反汇编。
 *
 * capstone.a 直接编进 fler_jni.so（CMakeLists 中 capstone STATIC IMPORTED），
 * 不再依赖引擎包的 libcapstone.so，SO 编辑器反汇编零引擎依赖。
 *
 * @return Array<DisasmInstruction>，加载/解码失败返回 null
 */
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_CapstoneBindings_nativeDisasm(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray jCode, jlong jBase) {

    jsize codeLen = env->GetArrayLength(jCode);
    std::vector<uint8_t> code(static_cast<size_t>(codeLen));
    env->GetByteArrayRegion(jCode, 0, codeLen, reinterpret_cast<jbyte*>(code.data()));

    csh csh = 0;
    cs_err err = cs_open(CS_ARCH_ARM64, CS_MODE_ARM, &csh);
    if (err != CS_ERR_OK || !csh) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "cs_open failed err=%d", err);
        return nullptr;
    }

    // 逐条解码：用 cs_disasm_iter 循环遍历整个缓冲区；
    // 不可解码的字输出为 4 字节 `.word` 伪指令并继续，保证声明的函数范围完整展示。
    std::vector<CsInsn> results;
    cs_insn* insnBuf = cs_malloc(csh);
    const uint8_t* cursor = code.data();
    size_t remaining = code.size();
    uint64_t addr = static_cast<uint64_t>(jBase);
    if (insnBuf) {
        while (remaining >= 4) {
            CsInsn item;
            memset(&item, 0, sizeof(item));
            if (cs_disasm_iter(csh, &cursor, &remaining, &addr, insnBuf)) {
                item.id = insnBuf->id;
                item.address = insnBuf->address;
                item.size = insnBuf->size;
                memcpy(item.bytes, insnBuf->bytes, sizeof(item.bytes));
                snprintf(item.mnemonic, sizeof(item.mnemonic), "%s", insnBuf->mnemonic);
                snprintf(item.op_str, sizeof(item.op_str), "%s", insnBuf->op_str);
                item.detail = nullptr;
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
        return nullptr;
    }
    jmethodID ctor = env->GetMethodID(
        cls, "<init>", "(JILjava/lang/String;Ljava/lang/String;[B)V");
    if (!ctor) {
        env->DeleteLocalRef(cls);
        cs_close(&csh);
        return nullptr;
    }

    const size_t count = results.size();
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(count), cls, nullptr);

    for (size_t i = 0; i < count; i++) {
        const CsInsn& it = results[i];
        // 字节拷贝固定上限 24，防止越界读（防御）
        size_t byteCount = it.size > 24 ? 24 : it.size;
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
    return arr;
}
