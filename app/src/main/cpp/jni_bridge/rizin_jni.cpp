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
 *   6. nativeWriteBytes→ 文件偏移→vaddr 翻译后 rz_core_write_at 写字节
 *
 * 所有复杂的数据解析（JSON → Kotlin 数据模型）在 RizinEngine.kt 中用
 * kotlinx.serialization 完成，JNI 层保持极简。
 */
#include <jni.h>
#include <android/log.h>
#include <rizin/rz_core.h>
#include <rizin/rz_io.h>
#include <rizin/rz_project.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <algorithm>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
#include <map>
#include <unordered_map>
#include <mutex>

static const char* TAG = "FlerRizinJNI";

// RzCore* 指针存为 jlong（64 位），在 64 位平台上安全
#define CORE(handle) reinterpret_cast<RzCore*>(handle)

// core 指针 -> 打开的文件绝对路径（nativeReadBytes 裸 pread 兜底用，
// 不依赖 Rizin 内部 io->desc->name，那个字段在部分版本为空）。
std::map<RzCore*, std::string> g_corePaths;

// core 指针 -> (mmap 起址, 文件大小) 缓存，避免每次 nativeReadBytes 兜底都
// 重新 open + mmap + munmap + close 全文件。scanStrings 对 40MB libapp.so
// 会触发 ~160 次 readBytes，无缓存时每次完整 mmap/munmap 一遍 40MB。
// mmap 生命周期与 RzCore 绑定，在 nativeClose 中释放。
static std::unordered_map<RzCore*, std::pair<void*, size_t>> g_coreMmaps;
static std::mutex g_mmapMutex;

/**
 * 裸文件直读（绕过 Rizin io）。
 *
 * Rizin io 层对这类 Dart AOT so 只建立了第一个 PT_LOAD 段的有效 map，
 * 首段之后的内容 rz_io_pread_at 一律返回 0 字节（症状：.text 反汇编/xref
 * 全部落空）。兜底用 mmap 直读（elf_parser 同款，已验证全文件可读）：
 * 实测本设备上 pread 超过 ~2MB 就返回 EOF，而 mmap 全文件正常。
 *
 * mmap 按 RzCore* 句柄缓存（[g_coreMmaps]）：scanStrings 对 40MB libapp.so
 * 会触发 ~160 次循环 readBytes，无缓存时每次都完整 open+mmap+munmap+close
 * 一遍 40MB，累计 160 次全文件 mmap；复用后只 mmap 一次，结束时随
 * nativeClose 一起释放。Android 上 MAP_SHARED 的 page cache 由内核管理，
 * 不会因长时间持有 mmap 导致物理内存常驻。
 */
static ssize_t raw_pread_all(RzCore* core, const char* path, ut64 off, uint8_t* buf, size_t size) {
    if (!path || size == 0) return -1;
    std::lock_guard<std::mutex> lock(g_mmapMutex);

    void* map = nullptr;
    size_t fileSize = 0;
    auto it = g_coreMmaps.find(core);
    if (it != g_coreMmaps.end()) {
        map = it->second.first;
        fileSize = it->second.second;
    } else {
        int fd = ::open(path, O_RDONLY);
        if (fd < 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "raw mmap open 失败 path=%s errno=%d", path, errno);
            return -1;
        }
        struct stat st;
        if (::fstat(fd, &st) < 0) { ::close(fd); return -1; }
        fileSize = static_cast<size_t>(st.st_size);
        map = ::mmap(nullptr, fileSize, PROT_READ, MAP_SHARED, fd, 0);
        ::close(fd);
        if (map == MAP_FAILED) {
            __android_log_print(ANDROID_LOG_ERROR, TAG, "raw mmap 失败 path=%s errno=%d size=%zu", path, errno, fileSize);
            return -1;
        }
        g_coreMmaps[core] = {map, fileSize};
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "raw mmap 缓存建立 core=%p path=%s size=%zu", core, path, fileSize);
    }

    if (off >= fileSize) return 0;
    size_t n = std::min(size, fileSize - static_cast<size_t>(off));
    std::memcpy(buf, static_cast<const uint8_t*>(map) + off, n);
    return static_cast<ssize_t>(n);
}

/**
 * 裸文件直写（绕过 Rizin io），mmap + memcpy + msync，返回成功写入字节数。
 */
static ssize_t raw_pwrite_all(const char* path, ut64 off, const uint8_t* buf, size_t size) {
    if (!path || size == 0) return -1;
    int fd = ::open(path, O_RDWR);
    if (fd < 0) return -1;
    struct stat st;
    if (::fstat(fd, &st) < 0) { ::close(fd); return -1; }
    if (off + size > static_cast<ut64>(st.st_size)) { ::close(fd); return -1; }
    void* map = ::mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) { ::close(fd); return -1; }
    std::memcpy(static_cast<uint8_t*>(map) + off, buf, size);
    int ms = ::msync(map, static_cast<size_t>(st.st_size), MS_SYNC);
    ::munmap(map, static_cast<size_t>(st.st_size));
    ::close(fd);
    return ms == 0 ? static_cast<ssize_t>(size) : -1;
}

/** 从 RzCore 取当前文件路径（裸 I/O 兜底用）。优先用 g_corePaths 旁路表，null 时回退 io->desc->name。 */
static const char* core_file_path(RzCore* core) {
    if (!core) return nullptr;
    auto it = g_corePaths.find(core);
    if (it != g_corePaths.end() && !it->second.empty()) return it->second.c_str();
    if (core->io && core->io->desc) return core->io->desc->name;
    return nullptr;
}

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
    g_corePaths[core] = path;

    // 打开文件：先尝试 RW（mode 6），失败则降级到只读（mode 4）
    // 某些解压后的 SO 文件可能没有写权限，RW 打开会失败
    RzCoreFile* cf = rz_core_file_open(core, path, 6, 0LL);
    if (!cf) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "rz_core_file_open RW failed, trying RO: %s", path);
        cf = rz_core_file_open(core, path, 4, 0LL);
    }
    if (!cf) {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "rz_core_file_open failed: %s", path);
        rz_core_free(core);
        env->ReleaseStringUTFChars(jPath, path);
        return 0;
    }

    // 加载二进制信息（节区、符号、入口等）
    // 基址必须用 0LL：传 UT64_MAX 会让 Rizin 以「无基址」方式装载，
    // 导致 io 只为第一个 PT_LOAD 段建立 map，首段之后的 .text/.rodata
    // 全部不可读（症状：反汇编/xref/函数分析全空）。基址 0 时各段按
    // ELF 自身 vaddr 建 map（libflutter 第二段 vaddr=offset+0x10000 也正确）。
    if (!rz_core_bin_load(core, path, 0LL)) {
        __android_log_print(ANDROID_LOG_WARN, TAG, "rz_core_bin_load failed (non-fatal): %s", path);
        // 非致命：仍可用 rizin 做字节级读写和反汇编，只是没有符号信息
    }

    // 探针：列出装载后的 io map，确认所有 PT_LOAD 段都已映射（om 输出每行一个 map）。
    char* maps = rz_core_cmd_str(core, "om");
    if (maps) {
        __android_log_print(ANDROID_LOG_INFO, TAG, "io maps after load:\n%s", maps);
        free(maps);
    } else {
        char* mapsj = rz_core_cmd_str(core, "omj");
        __android_log_print(ANDROID_LOG_INFO, TAG, "om 命令无输出（null），omj=%s",
            mapsj ? mapsj : "(null)");
        if (mapsj) free(mapsj);
    }

    // 设置默认架构为 ARM64（ELF 头可能已设，这里确保）
    rz_core_cmd_str(core, "e asm.arch=arm");
    rz_core_cmd_str(core, "e asm.bits=64");

    // 关闭 io.cache：写入必须直接落盘而非只写内存缓存。
    // 一次性设置：每次 nativeWriteBytes 都发命令会引入额外 rz_core_cmd_str
    // 开销（命令解析 + 字符串分配），open 时设一次后续写操作均生效。
    rz_core_cmd_str(core, "e io.cache=false");

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
        // 释放 mmap 缓存（必须在 rz_core_free 之前，用 core 作 key 查找）
        {
            std::lock_guard<std::mutex> lock(g_mmapMutex);
            auto it = g_coreMmaps.find(core);
            if (it != g_coreMmaps.end()) {
                ::munmap(it->second.first, it->second.second);
                g_coreMmaps.erase(it);
            }
        }
        g_corePaths.erase(core);
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
 *
 * 必须用 rz_io_pread_at 物理直读，不能 rz_io_nread_at：
 * io.va=true 时 nread_at 按段 map 裁剪，只覆盖第一段（ELF 头区），
 * 第二段起的 .text 等段无 map 会直接返回 0 字节——PIE 库反汇编
 * 跳转全部落空（症状：搜索/跳转目标地址永远“无数据”）。
 */
JNIEXPORT jbyteArray JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativeReadBytes(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jlong jOffset, jint jSize) {

    RzCore* core = CORE(handle);
    if (!core || !core->io || jSize <= 0) return nullptr;

    std::vector<uint8_t> buf(static_cast<size_t>(jSize));
    int n = rz_io_pread_at(core->io, static_cast<ut64>(jOffset), buf.data(), static_cast<size_t>(jSize));
    if (n <= 0) {
        // Rizin io 只映射首段时，首段之后的物理读返回 0。兜底：裸 pread 直读文件。
        const char* path = core_file_path(core);
        ssize_t rn = raw_pread_all(core, path, static_cast<ut64>(jOffset), buf.data(), static_cast<size_t>(jSize));
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "readBytes 兜底尝试 offset=0x%llx size=%lld rz=%d raw=%lld path=%s",
            static_cast<unsigned long long>(jOffset),
            static_cast<long long>(jSize), n, static_cast<long long>(rn),
            path ? path : "(null)");
        if (rn > 0) n = static_cast<int>(rn);
    }
    if (n <= 0) return nullptr;

    jbyteArray result = env->NewByteArray(static_cast<jsize>(n));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(n),
                             reinterpret_cast<const jbyte*>(buf.data()));
    return result;
}

/**
 * 文件偏移（物理地址）→ 虚拟地址。
 *
 * 不能用「s <paddr>; v.」命令实现：io.va=true 时 s 命令把入参当虚拟
 * 地址解释，v. 原样返回 seek，换算恒等失效（PIE 库差 0x4000 时永远
 * 失败，症状：长按汇编行「断点调试/函数调用」跳转仿真后地址仍是 paddr）。
 * rz_io_p2v 按段 map 做物理→虚拟换算，与 vp（vaddr→paddr）对称。
 * 映射外返回 UT64_MAX，调用方回退原值。
 */
JNIEXPORT jlong JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativePaddrToVaddr(
    JNIEnv* /*env*/, jobject /*thiz*/, jlong handle, jlong paddr) {

    RzCore* core = CORE(handle);
    if (!core || !core->io) return paddr;
    ut64 v = rz_io_p2v(core->io, static_cast<ut64>(paddr));
    if (v == UT64_MAX) return paddr;
    return static_cast<jlong>(v);
}

/**
 * 直接写入字节（文件偏移寻址）。
 *
 * @param offset 文件偏移
 * @param data   字节数组
 * @return true 成功
 *
 * 关键点：Hex 编辑器传入的是「文件偏移」。写入必须用**物理地址直写**
 * rz_io_pwrite_at —— 它按 paddr（= 文件偏移）直接写 desc，绕过 io.va 的
 * map 翻译，也绕过 ELF 段写权限检查（io.va=true 时代码段的 map 是 R-X，
 * rz_core_write_at → rz_io_write 会因无写权限而失败，这是此前写入失败的根因）。
 * 读取侧 rz_io_pread_at 同样是物理直读（nread_at 会受段 map 裁剪），二者地址空间一致。
 * io.cache 已在 nativeOpen 中一次性关闭，保证写入直接落到文件而非内存缓存，
 * 此处不再每次发命令以减少 rz_core_cmd_str 解析开销。
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

    // io.cache 已在 nativeOpen 中一次性关闭，这里直接物理直写。
    int n = rz_io_pwrite_at(core->io, static_cast<ut64>(jOffset), buf.data(), static_cast<size_t>(size));
    bool ok = (n == static_cast<int>(size));
    if (!ok) {
        // Rizin io 超出首段时无法物理写。兜底：裸 pwrite 直写文件。
        const char* path = core_file_path(core);
        ssize_t wn = raw_pwrite_all(path, static_cast<ut64>(jOffset), buf.data(), static_cast<size_t>(size));
        if (wn == static_cast<ssize_t>(size)) {
            ok = true;
            n = static_cast<int>(wn);
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "writeBytes: rz_io_pwrite_at 失败，已用裸 pwrite 兜底 offset=0x%llx size=%d",
                static_cast<unsigned long long>(jOffset), static_cast<int>(size));
        }
    }

    // 写后读回校验（物理直读），确认字节已落盘。
    // 读侧同样需要兜底：写入首段之外的区域时 rz_io_pread_at 返回 0，
    // 用 raw_pread_all（复用 mmap 缓存）读回才能正确比对。
    bool readbackMatched = false;
    if (ok && size > 0) {
        std::vector<uint8_t> check(static_cast<size_t>(size));
        int r = rz_io_pread_at(core->io, static_cast<ut64>(jOffset), check.data(), static_cast<size_t>(size));
        if (r != size) {
            const char* path = core_file_path(core);
            ssize_t rr = raw_pread_all(core, path, static_cast<ut64>(jOffset), check.data(), static_cast<size_t>(size));
            if (rr > 0) r = static_cast<int>(rr);
        }
        readbackMatched = (r == size) && (std::memcmp(check.data(), buf.data(), size) == 0);
    }

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "writeBytes: fileOffset=0x%llx size=%d written=%d readbackMatched=%d",
        static_cast<unsigned long long>(jOffset),
        static_cast<int>(size), n, readbackMatched ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * 保存 Rizin Project 到文件。
 *
 * 将当前分析状态（函数、符号、xref、flag 等）持久化到 .rzdb 文件，
 * 下次打开同一 SO 文件时可直接加载，跳过 aaa 全量分析。
 *
 * @param path 项目文件绝对路径
 * @return true 成功
 */
JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativeProjectSave(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jPath) {

    RzCore* core = CORE(handle);
    if (!core) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return JNI_FALSE;

    // rz_project_save_file 返回 RzProjectErr 枚举：RZ_PROJECT_ERR_SUCCESS=0，
    // 其余均为错误码（≠0）。不能把原始返回值当 bool 用——否则「成功=0」会被
    // 判为 false、「失败=非0」会被判为 true，导致项目永远存不上/永远误判已加载。
    bool ok = (rz_project_save_file(core, path, true) == RZ_PROJECT_ERR_SUCCESS);
    env->ReleaseStringUTFChars(jPath, path);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "projectSave: path=%s ok=%d", path, ok ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

/**
 * 加载 Rizin Project 文件。
 *
 * 从 .rzdb 文件恢复分析状态，跳过 aaa 全量分析。
 *
 * @param path 项目文件绝对路径
 * @return true 成功
 */
JNIEXPORT jboolean JNICALL
Java_com_ai_fler_core_jni_RizinBindings_nativeProjectLoad(
    JNIEnv* env, jobject /*thiz*/, jlong handle, jstring jPath) {

    RzCore* core = CORE(handle);
    if (!core) return JNI_FALSE;

    const char* path = env->GetStringUTFChars(jPath, nullptr);
    if (!path) return JNI_FALSE;

    // 同 projectSave：返回 RzProjectErr（0=成功，非0=错误），不能当 bool 用。
    bool ok = (rz_project_load_file(core, path, false, nullptr) == RZ_PROJECT_ERR_SUCCESS);
    env->ReleaseStringUTFChars(jPath, path);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "projectLoad: path=%s ok=%d", path, ok ? 1 : 0);
    return ok ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
