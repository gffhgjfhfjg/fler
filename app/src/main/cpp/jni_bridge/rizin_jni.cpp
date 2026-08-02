/**
 * Rizin JNI 桥接层。
 *
 * 设计思路：Rizin 的核心 API 就是一个命令解释器，通过 rz_core_cmd_str(core, cmd)
 * 执行命令并获取输出。大多数分析命令支持 j 后缀返回 JSON（如 iSj / isj / aflj / pdj）。
 *
 * 本 JNI 层只暴露最小接口：
 *   1. nativeOpen     → 创建 RzCore + 打开文件 + 加载二进制
 *   2. nativeClose    → 释放 RzCore
 *   3. nativeAnalyze  → 执行 aaa 自动分析
 *   4. nativeCmdStr   → 执行任意命令，返回字符串输出
 *   5. nativeReadBytes→ 直接 rz_io_nread_at 读字节（比 pxj 更快，不做 hex 编码）
 *   6. nativeWriteBytes→ 直接 rz_core_write_at 写字节
 *
 * 所有复杂的数据解析（JSON → Kotlin 数据模型）在 RizinEngine.kt 中用
 * kotlinx.serialization 完成，JNI 层保持极简。
 */
#include <jni.h>
#include <android/log.h>
#include <rizin/rz_core.h>
#include <rizin/rz_io.h>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

static const char* TAG = "FlerRizinJNI";

// RzCore* 指针存为 jlong（64 位），在 64 位平台上安全
#define CORE(handle) reinterpret_cast<RzCore*>(handle)

// Android NDK 不提供 execinfo.h 的 backtrace 系列函数。
// Rizin librz_util 引用了它们用于崩溃日志，Android 上用空实现即可。
extern "C" {
    int backtrace(void** buffer, int size) { (void)buffer; (void)size; return 0; }
    void backtrace_symbols_fd(void* const* buffer, int size, int fd) {
        (void)buffer; (void)size; (void)fd;
    }
}

extern "C" {

/**
 * 创建 RzCore 实例，打开文件并加载二进制信息。
 *
 * @param path 文件绝对路径
 * @return RzCore* 指针（>0），失败返回 0
 */
JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativeOpen(
    JNIEnv* env, jobject /*thiz*/, jstring jPath) {

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "GetStringUTFChars failed");
        return 0;
    }

    RzCore* core = rz_core_new();
    if (!core) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "rz_core_new() failed");
        env->ReleaseStringUTFChars(jPath, path);
        return 0;
    }

    // 打开文件（RZ_PERM_R = 读，RZ_PERM_W = 写）
    // RZ_PERM_RWX = 7（读+写+执行映射），保证 read/write 都可用
    RzCoreFile* cf = rz_core_file_open(core, path, 7, 0LL);
    if (!cf) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "rz_core_file_open failed: %s", path);
        rz_core_free(core);
        env->ReleaseStringUTFChars(jPath, path);
        return 0;
    }

    // 加载二进制信息（节区、符号、入口等）
    if (!rz_core_bin_load(core, path, UT64_MAX)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "rz_core_bin_load failed (non-fatal): %s", path);
        // 非致命：仍可用 rizin 做字节级读写和反汇编，只是没有符号信息
    }

    // 设置默认架构为 ARM64（ELF 头可能已设，这里确保）
    rz_core_cmd_str(core, "e asm.arch=arm");
    rz_core_cmd_str(core, "e asm.bits=64");

    __android_log_print(ANDROID_LOG_INFO, TAG, "Rizin 打开成功: %s", path);
    env->ReleaseStringUTFChars(jPath, path);
    return reinterpret_cast<jlong>(core);
}

/**
 * 释放 RzCore 实例。
 */
JNIEXPORT void JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativeClose(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {

    RzCore* core = CORE(handle);
    if (core) {
        // rz_core_file_close_all 会关闭所有打开的文件
        rz_core_free(core);
        __android_log_print(ANDROID_LOG_INFO, TAG, "Rizin 已释放");
    }
}

/**
 * 执行 aaa 自动分析（函数识别、交叉引用等）。
 *
 * @return true 成功
 */
JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativeAnalyze(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle) {

    RzCore* core = CORE(handle);
    if (!core) return JNI_FALSE;

    // aaa = 分析所有（函数、交叉引用、字符串等）
    int result = rz_core_analysis_all(core);
    __android_log_print(ANDROID_LOG_INFO, TAG, "aaa 分析完成, result=%d", result);
    return JNI_TRUE;
}

/**
 * 执行 Rizin 命令并返回字符串输出。
 *
 * 核心方法：所有数据查询（节区、符号、函数、反汇编等）都通过这里完成。
 * Kotlin 层用命令后缀 j 获取 JSON，然后用 kotlinx.serialization 解析。
 *
 * @param cmd Rizin 命令（如 "iSj" "isj" "aflj" "pd 10 @ 0x1234"）
 * @return 命令输出字符串，失败返回 null
 */
JNIEXPORT jstring JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativeCmdStr(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jCmd) {

    RzCore* core = CORE(handle);
    if (!core) return nullptr;

    const char* cmd = env->GetStringUTFChars(jCmd, nullptr);
    if (!cmd) return nullptr;

    char* output = rz_core_cmd_str(core, cmd);
    env->ReleaseStringUTFChars(jCmd, cmd);

    if (!output) return nullptr;

    jstring result = env->NewStringUTF(output);
    free(output);  // rz_core_cmd_str 返回的字符串需要 free
    return result;
}

/**
 * 直接读取字节（比 pxj 更高效，不做 hex 编码）。
 *
 * @param offset 文件偏移
 * @param size  读取长度
 * @return 字节数组，失败返回 null
 */
JNIEXPORT jbyteArray JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativeReadBytes(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong jOffset, jint jSize) {

    RzCore* core = CORE(handle);
    if (!core || !core->io || jSize <= 0) return nullptr;

    std::vector<uint8_t> buf(static_cast<size_t>(jSize));
    int n = rz_io_nread_at(core->io, static_cast<ut64>(jOffset), buf.data(), static_cast<size_t>(jSize));
    if (n <= 0) return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(n));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(n),
                             reinterpret_cast<const jbyte*>(buf.data()));
    return result;
}

/**
 * 直接写入字节。
 *
 * @param offset 文件偏移
 * @param data   字节数组
 * @return true 成功
 */
JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativeWriteBytes(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong jOffset, jbyteArray jData) {

    RzCore* core = CORE(handle);
    if (!core) return JNI_FALSE;

    jsize size = env->GetArrayLength(jData);
    if (size <= 0) return JNI_FALSE;

    std::vector<uint8_t> buf(static_cast<size_t>(size));
    env->GetByteArrayRegion(jData, 0, size, reinterpret_cast<jbyte*>(buf.data()));

    bool ok = rz_core_write_at(core, static_cast<ut64>(jOffset), buf.data(), static_cast<int>(size));
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
