#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "../elf_parser/elf_parser.h"
#include "../elf_parser/elf_writer.h"

using namespace fler::elf;

static const char* TAG = "FlerElfParserJNI";

// 将 C++ Section 转为 Java 对象
// ElfSection 字段顺序：name(String) type(Int) offset(Long) size(Long) address(Long) flags(Long)
// 对应 JVM 签名：(Ljava/lang/String;IJJJJ)V
static jobject sectionToJava(JNIEnv* env, const Section& sec) {
    jclass cls = env->FindClass("com/ai/fler/core/jni/ElfSection");
    jmethodID ctor = env->GetMethodID(cls, "<init>",
        "(Ljava/lang/String;IJJJJ)V");
    jstring jname = env->NewStringUTF(sec.name.c_str());
    jobject obj = env->NewObject(cls, ctor,
        jname,
        static_cast<jint>(sec.type),
        static_cast<jlong>(sec.offset),
        static_cast<jlong>(sec.size),
        static_cast<jlong>(sec.address),
        static_cast<jlong>(sec.flags));
    env->DeleteLocalRef(jname);
    return obj;
}

// 将 C++ Symbol 转为 Java 对象
// ElfSymbol 字段顺序：name(String) address(Long) size(Long) type(Byte) binding(Byte) shndx(Short)
// 对应 JVM 签名：(Ljava/lang/String;JJBBS)V
// 注意：Kotlin Short 在 JVM 中是 short，签名是 S（不是 H）
static jobject symbolToJava(JNIEnv* env, const Symbol& sym) {
    jclass cls = env->FindClass("com/ai/fler/core/jni/ElfSymbol");
    jmethodID ctor = env->GetMethodID(cls, "<init>",
        "(Ljava/lang/String;JJBBS)V");
    jstring jname = env->NewStringUTF(sym.name.c_str());
    jobject obj = env->NewObject(cls, ctor,
        jname,
        static_cast<jlong>(sym.address),
        static_cast<jlong>(sym.size),
        static_cast<jbyte>(sym.type),
        static_cast<jbyte>(sym.binding),
        static_cast<jshort>(sym.shndx));
    env->DeleteLocalRef(jname);
    return obj;
}

// ========== ElfParser JNI ==========

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeOpen(
    JNIEnv* env, jobject /*thiz*/, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    ElfParser* parser = ElfParser::open(path);
    env->ReleaseStringUTFChars(jpath, path);
    if (!parser) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "Failed to open ELF: %s", path);
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, TAG, "Opened ELF: %s, size=%zu", path, parser->getFileSize());
    return reinterpret_cast<jlong>(parser);
}

extern "C" JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeClose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    delete parser;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeGetSections(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return nullptr;

    auto sections = parser->getSections();
    jclass cls = env->FindClass("com/ai/fler/core/jni/ElfSection");
    jobjectArray arr = env->NewObjectArray(sections.size(), cls, nullptr);
    for (size_t i = 0; i < sections.size(); ++i) {
        auto* sec = sectionToJava(env, sections[i]);
        env->SetObjectArrayElement(arr, i, sec);
        env->DeleteLocalRef(sec);
    }
    return arr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeGetSymbols(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return nullptr;

    auto symbols = parser->getSymbols();
    jclass cls = env->FindClass("com/ai/fler/core/jni/ElfSymbol");
    jobjectArray arr = env->NewObjectArray(symbols.size(), cls, nullptr);
    for (size_t i = 0; i < symbols.size(); ++i) {
        auto* sym = symbolToJava(env, symbols[i]);
        env->SetObjectArrayElement(arr, i, sym);
        env->DeleteLocalRef(sym);
    }
    return arr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeGetDynamicSymbols(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return nullptr;

    auto symbols = parser->getDynamicSymbols();
    jclass cls = env->FindClass("com/ai/fler/core/jni/ElfSymbol");
    jobjectArray arr = env->NewObjectArray(symbols.size(), cls, nullptr);
    for (size_t i = 0; i < symbols.size(); ++i) {
        auto* sym = symbolToJava(env, symbols[i]);
        env->SetObjectArrayElement(arr, i, sym);
        env->DeleteLocalRef(sym);
    }
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeFindSymbolAddress(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jname) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return 0;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    jlong addr = static_cast<jlong>(parser->findSymbolAddress(name));
    env->ReleaseStringUTFChars(jname, name);
    return addr;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeGetSectionData(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jname) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return nullptr;
    const char* name = env->GetStringUTFChars(jname, nullptr);
    auto data = parser->getSectionData(name);
    env->ReleaseStringUTFChars(jname, name);
    if (data.empty()) return nullptr;
    jbyteArray result = env->NewByteArray(data.size());
    env->SetByteArrayRegion(result, 0, data.size(),
        reinterpret_cast<const jbyte*>(data.data()));
    return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeReadBytes(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong offset, jlong size) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return nullptr;
    auto data = parser->readBytes(static_cast<uint64_t>(offset), static_cast<size_t>(size));
    if (data.empty()) return nullptr;
    jbyteArray result = env->NewByteArray(data.size());
    env->SetByteArrayRegion(result, 0, data.size(),
        reinterpret_cast<const jbyte*>(data.data()));
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeWriteBytes(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong offset, jbyteArray jdata) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return JNI_FALSE;
    jsize len = env->GetArrayLength(jdata);
    std::vector<uint8_t> data(len);
    env->GetByteArrayRegion(jdata, 0, len, reinterpret_cast<jbyte*>(data.data()));

    bool ok = parser->writeBytes(static_cast<uint64_t>(offset), data.data(), len);
    if (ok) parser->flush();
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeComputeCRC32(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong offset, jlong size) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return 0;
    return static_cast<jlong>(parser->computeCRC32(
        static_cast<uint64_t>(offset),
        static_cast<size_t>(size)));
}
