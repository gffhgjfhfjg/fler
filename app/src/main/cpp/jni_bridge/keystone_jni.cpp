#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>

#include <keystone/keystone.h>

static const char* TAG = "FlerKeystoneJNI";

/**
 * 用 Keystone 汇编 ARM64 指令（ks_asm），返回编码后的机器码。
 *
 * Keystone 静态链接进 libfler.so（CMake 链接 libkeystone.a），直接调用。
 * 分支指令的偏移量由传入的 address（指令所在文件偏移）正确计算。
 *
 * @return 机器码字节数组；Keystone 打开失败或汇编失败返回 null
 */
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ai_fler_core_jni_KeystoneBindings_nativeAsm(
    JNIEnv* env, jobject /*thiz*/,
    jstring jAssembly, jlong jAddress) {

    const char* assembly = env->GetStringUTFChars(jAssembly, nullptr);
    if (!assembly) return nullptr;

    ks_engine* ks = nullptr;
    ks_err err = ks_open(KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN, &ks);
    if (err != KS_ERR_OK || !ks) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "ks_open failed err=%d", (int)err);
        env->ReleaseStringUTFChars(jAssembly, assembly);
        return nullptr;
    }

    unsigned char* encoding = nullptr;
    size_t encoding_size = 0;
    size_t stat_count = 0;
    int count = ks_asm(ks, assembly, (uint64_t)jAddress, &encoding, &encoding_size, &stat_count);
    env->ReleaseStringUTFChars(jAssembly, assembly);

    if (count <= 0 || encoding_size == 0 || !encoding) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "ks_asm failed: %s", assembly);
        if (encoding) ks_free(encoding);
        ks_close(ks);
        return nullptr;
    }

    jbyteArray result = env->NewByteArray((jsize)encoding_size);
    env->SetByteArrayRegion(result, 0, (jsize)encoding_size,
                            reinterpret_cast<const jbyte*>(encoding));

    ks_free(encoding);
    ks_close(ks);
    return result;
}
