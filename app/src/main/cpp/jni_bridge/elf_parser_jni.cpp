#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>

#include "../elf_parser/elf_parser.h"

using namespace fler::elf;

static const char* TAG = "FlerElfParserJNI";

// === 全局 jclass / jmethodID 缓存 ===
// FindClass + GetMethodID 每次都查 JVM 内部哈希表，符号解析相当昂贵：
// SelfAnalysisEngine scanStrings 会反复触发 nativeGetSections/nativeReadBytes，
// 大型 SO（数万符号）一次性 getSymbols 也会反复进入 helper。缓存后只查一次，
// 在 JNI_OnLoad 中初始化为 global ref 长期持有。
static jclass g_sectionCls = nullptr;
static jmethodID g_sectionCtor = nullptr;
static jclass g_symbolCls = nullptr;
static jmethodID g_symbolCtor = nullptr;
static jclass g_loadSegmentCls = nullptr;
static jmethodID g_loadSegmentCtor = nullptr;

// 将 C++ Section 转为 Java 对象
// ElfSection 字段顺序：name(String) type(Int) offset(Long) size(Long) address(Long) flags(Long)
// 对应 JVM 签名：(Ljava/lang/String;IJJJJ)V
static jobject sectionToJava(JNIEnv* env, const Section& sec) {
    jstring jname = env->NewStringUTF(sec.name.c_str());
    jobject obj = env->NewObject(g_sectionCls, g_sectionCtor,
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
    jstring jname = env->NewStringUTF(sym.name.c_str());
    jobject obj = env->NewObject(g_symbolCls, g_symbolCtor,
        jname,
        static_cast<jlong>(sym.address),
        static_cast<jlong>(sym.size),
        static_cast<jbyte>(sym.type),
        static_cast<jbyte>(sym.binding),
        static_cast<jshort>(sym.shndx));
    env->DeleteLocalRef(jname);
    return obj;
}

// 将 C++ ProgramHeader 转为 Java 对象
// ElfLoadSegment 字段顺序：type(Int) flags(Int) offset(Long) vaddr(Long) paddr(Long) filesz(Long) memsz(Long) align(Long)
// 对应 JVM 签名：(IIJJJJJJ)V
static jobject loadSegmentToJava(JNIEnv* env, const ProgramHeader& ph) {
    return env->NewObject(g_loadSegmentCls, g_loadSegmentCtor,
        static_cast<jint>(ph.type),
        static_cast<jint>(ph.flags),
        static_cast<jlong>(ph.offset),
        static_cast<jlong>(ph.vaddr),
        static_cast<jlong>(ph.paddr),
        static_cast<jlong>(ph.filesz),
        static_cast<jlong>(ph.memsz),
        static_cast<jlong>(ph.align));
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
    jobjectArray arr = env->NewObjectArray(sections.size(), g_sectionCls, nullptr);
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
    jobjectArray arr = env->NewObjectArray(symbols.size(), g_symbolCls, nullptr);
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
    jobjectArray arr = env->NewObjectArray(symbols.size(), g_symbolCls, nullptr);
    for (size_t i = 0; i < symbols.size(); ++i) {
        auto* sym = symbolToJava(env, symbols[i]);
        env->SetObjectArrayElement(arr, i, sym);
        env->DeleteLocalRef(sym);
    }
    return arr;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeGetEntry(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return 0;
    return static_cast<jlong>(parser->getEntry());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_ai_fler_core_jni_ElfParserBindings_nativeGetLoadSegments(
    JNIEnv* env, jobject /*thiz*/, jlong handle) {
    auto* parser = reinterpret_cast<ElfParser*>(handle);
    if (!parser || !parser->isValid()) return nullptr;

    auto segments = parser->getLoadSegments();
    jobjectArray arr = env->NewObjectArray(segments.size(), g_loadSegmentCls, nullptr);
    for (size_t i = 0; i < segments.size(); ++i) {
        auto* seg = loadSegmentToJava(env, segments[i]);
        env->SetObjectArrayElement(arr, i, seg);
        env->DeleteLocalRef(seg);
    }
    return arr;
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

// ========== JNI_OnLoad：一次性缓存 jclass / jmethodID ==========
//
// FindClass 在 JNI_OnLoad 中可用：此时线程上下文 classloader 是 application
// classloader，能正常解析 com/ai/fler/core/jni/* 类。返回的 local ref 在
// 函数返回后失效，必须 NewGlobalRef 转为 global ref 长期持有。
//
// Android 上 .so 卸载时 JNI_OnUnload 不保证被调用（classloader 时机不确定），
// 因此不依赖卸载钩子释放：全局 ref 随进程退出自动回收，App 生命周期内
// .so 常驻不卸载，泄漏可控。
extern "C" void fridaCacheJavaVm(JavaVM* vm);

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    fridaCacheJavaVm(vm);
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    // 把 FindClass 的 local ref 升级为 global ref。失败时清异常并返回 false。
    auto cacheClass = [&](const char* name, jclass& outCls) -> bool {
        jclass local = env->FindClass(name);
        if (!local) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "JNI_OnLoad FindClass failed: %s", name);
            env->ExceptionClear();
            return false;
        }
        outCls = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        return outCls != nullptr;
    };

    // ElfSection(Ljava/lang/String;IJJJJ)V
    if (!cacheClass("com/ai/fler/core/jni/ElfSection", g_sectionCls)) return JNI_ERR;
    g_sectionCtor = env->GetMethodID(g_sectionCls, "<init>", "(Ljava/lang/String;IJJJJ)V");
    if (!g_sectionCtor) { env->ExceptionClear(); return JNI_ERR; }

    // ElfSymbol(Ljava/lang/String;JJBBS)V
    if (!cacheClass("com/ai/fler/core/jni/ElfSymbol", g_symbolCls)) return JNI_ERR;
    g_symbolCtor = env->GetMethodID(g_symbolCls, "<init>", "(Ljava/lang/String;JJBBS)V");
    if (!g_symbolCtor) { env->ExceptionClear(); return JNI_ERR; }

    // ElfLoadSegment(IIJJJJJJ)V
    if (!cacheClass("com/ai/fler/core/jni/ElfLoadSegment", g_loadSegmentCls)) return JNI_ERR;
    g_loadSegmentCtor = env->GetMethodID(g_loadSegmentCls, "<init>", "(IIJJJJJJ)V");
    if (!g_loadSegmentCtor) { env->ExceptionClear(); return JNI_ERR; }

    __android_log_print(ANDROID_LOG_INFO, TAG, "JNI_OnLoad: jclass/jmethodID 缓存建立完成");
    return JNI_VERSION_1_6;
}
