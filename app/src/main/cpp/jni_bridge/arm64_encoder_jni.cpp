#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "../arm64_encoder/arm64_encoder.h"
#include "../arm64_encoder/decoder.h"

using namespace fler::arm64;

static const char* TAG = "FlerArm64EncoderJNI";

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_Arm64EncoderBindings_nativeEncode(
    JNIEnv* env, jobject /*thiz*/,
    jstring jname, jstring jargs) {
    const char* name = env->GetStringUTFChars(jname, nullptr);
    const char* args = env->GetStringUTFChars(jargs, nullptr);

    uint32_t encoding = 0;
    bool ok = Arm64Encoder::instance().encode(name, args, encoding);

    env->ReleaseStringUTFChars(jname, name);
    env->ReleaseStringUTFChars(jargs, args);

    if (!ok) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "Failed to encode: %s %s", name, args);
        return 0;
    }
    return static_cast<jlong>(encoding);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_Arm64EncoderBindings_nativeListInstructions(
    JNIEnv* env, jobject /*thiz*/) {
    auto instructions = Arm64Encoder::instance().listInstructions();
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(instructions.size(), stringClass, nullptr);
    for (size_t i = 0; i < instructions.size(); ++i) {
        jstring js = env->NewStringUTF(instructions[i].c_str());
        env->SetObjectArrayElement(arr, i, js);
        env->DeleteLocalRef(js);
    }
    return arr;
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_Arm64EncoderBindings_nativeRegisterInstruction(
    JNIEnv* env, jobject /*thiz*/,
    jstring jname, jlong fnPtr) {
    const char* name = env->GetStringUTFChars(jname, nullptr);
    auto* fn = reinterpret_cast<Arm64Encoder::EncoderFn*>(fnPtr);
    if (fn) {
        Arm64Encoder::instance().registerInstruction(name, *fn);
    }
    env->ReleaseStringUTFChars(jname, name);
}

// ========== 反汇编 ==========
// 返回 Array<DisasmInstruction>
// DisasmInstruction 字段顺序：address(Long) size(Int) mnemonic(String) opStr(String) bytes(ByteArray)
// 对应 JVM 签名：(JILjava/lang/String;Ljava/lang/String;[B)V
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_Arm64EncoderBindings_nativeDisasm(
    JNIEnv* env, jobject /*thiz*/,
    jbyteArray jcode, jlong jbase) {

    jsize len = env->GetArrayLength(jcode);
    std::vector<uint8_t> code(len);
    env->GetByteArrayRegion(jcode, 0, len, reinterpret_cast<jbyte*>(code.data()));

    auto decoded = disassemble(code.data(), static_cast<size_t>(len), static_cast<uint64_t>(jbase));

    jclass cls = env->FindClass("com/ai/fler/core/jni/DisasmInstruction");
    jmethodID ctor = env->GetMethodID(cls, "<init>", "(JILjava/lang/String;Ljava/lang/String;[B)V");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(decoded.size()), cls, nullptr);

    for (size_t i = 0; i < decoded.size(); ++i) {
        const auto& d = decoded[i];
        jstring jmn = env->NewStringUTF(d.mnemonic.c_str());
        jstring jops = env->NewStringUTF(d.operands.c_str());
        jbyteArray jbytes = env->NewByteArray(4);
        jbyte bytes[4];
        bytes[0] = static_cast<jbyte>(d.raw & 0xFF);
        bytes[1] = static_cast<jbyte>((d.raw >> 8) & 0xFF);
        bytes[2] = static_cast<jbyte>((d.raw >> 16) & 0xFF);
        bytes[3] = static_cast<jbyte>((d.raw >> 24) & 0xFF);
        env->SetByteArrayRegion(jbytes, 0, 4, bytes);

        jobject obj = env->NewObject(
            cls, ctor,
            static_cast<jlong>(d.address),
            static_cast<jint>(d.size),
            jmn, jops, jbytes);
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), obj);

        env->DeleteLocalRef(jbytes);
        env->DeleteLocalRef(jmn);
        env->DeleteLocalRef(jops);
        env->DeleteLocalRef(obj);
    }
    return arr;
}
