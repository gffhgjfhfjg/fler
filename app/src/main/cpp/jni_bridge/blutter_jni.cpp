#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <unistd.h>
#include <cstdlib>
#include <cstring>
#include <cerrno>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <cstdio>
#include <string>
#include <csignal>
#include <csetjmp>

// blutter_analyze 由 dartvm_*.so 导出（visibility("default")）
typedef int (*blutter_analyze_fn)(const char* so_path, const char* db_path);

static const char* TAG = "FlerBlutterJNI";

/**
 * 确保 app cacheDir 下存在可写的 blutter_tmp 子目录，并把工作目录切到那里。
 *
 * 背景：blutter_analyze 内部会创建临时目录（默认用 /tmp 或 $TMPDIR 或 cwd）。
 * Android 应用对 /tmp 和 /data/local/tmp 都没有写权限，会立即返回 TempDirError(-1)。
 *
 * 解决：在 app cacheDir 下建 blutter_tmp/ 子目录（app 总有写权限），
 * 1) chdir 到该目录 —— blutter 在 cwd 下创建临时文件时能成功
 * 2) setenv("TMPDIR", ...) —— 让使用 mkstemp/tmpfile 的库也走这里
 *
 * @param cache_dir app cacheDir 的绝对路径（JNI 从 Kotlin 传入）
 * @return 0 成功，-1 失败
 */
static int prepare_workdir(const char* cache_dir) {
    if (!cache_dir || cache_dir[0] == '\0') {
        __android_log_print(ANDROID_LOG_ERROR, TAG, "prepare_workdir: cache_dir is null or empty");
        return -1;
    }

    // 拼接 cache_dir + "/blutter_tmp"
    size_t len = strlen(cache_dir) + 16;
    char* workdir = static_cast<char*>(malloc(len));
    if (!workdir) return -1;
    snprintf(workdir, len, "%s/blutter_tmp", cache_dir);

    // 创建（已存在不报错）
    if (mkdir(workdir, 0770) != 0 && errno != EEXIST) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "mkdir(%s) failed: %s", workdir, strerror(errno));
        free(workdir);
        return -1;
    }
    chmod(workdir, 0770);

    // chdir 到工作目录
    if (chdir(workdir) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "chdir(%s) failed: %s", workdir, strerror(errno));
        free(workdir);
        return -1;
    }

    // 设置 TMPDIR 让 mkstemp/tmpfile 也走这里
    setenv("TMPDIR", workdir, 1);
    setenv("TMP", workdir, 1);
    setenv("TEMP", workdir, 1);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "工作目录已切换到: %s, TMPDIR=%s", workdir, workdir);
    free(workdir);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ai_fler_core_jni_BlutterEngine_nativeBlutterAnalyze(
    JNIEnv* env, [[maybe_unused]] jobject thiz,
    jstring engineSoPath, jstring soPath, jstring dbPath, jstring cacheDir) {

    const char* engine_path = env->GetStringUTFChars(engineSoPath, nullptr);
    const char* so = env->GetStringUTFChars(soPath, nullptr);
    const char* db = env->GetStringUTFChars(dbPath, nullptr);
    const char* cache_dir = env->GetStringUTFChars(cacheDir, nullptr);

    __android_log_print(ANDROID_LOG_INFO, TAG,
        "nativeBlutterAnalyze: engine=%s, so=%s, db=%s, cacheDir=%s",
        engine_path, so, db, cache_dir);

    // 先用 RTLD_NOLOAD 检查 dartvm_*.so 是否已被 System.load 加载（快速路径）。
    // 若未加载，fallback 用 RTLD_NOW | RTLD_GLOBAL 实际加载。
    //
    // 不依赖 System.load 状态：
    // - System.load 失败时 loadedLibs 不会 add，但 JNI 仍可独立 dlopen
    // - dlopen 会处理 NEEDED 依赖（libicuuc.so.73 等 symlink），失败时 dlerror() 返回详细原因
    // - RTLD_GLOBAL 让后续 dlsym 能找到符号（blutter 内部也可能 dlsym 其他库）
    void* handle = dlopen(engine_path, RTLD_NOLOAD | RTLD_LAZY);
    if (handle) {
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "dartvm 已加载（RTLD_NOLOAD 命中）: %s", engine_path);
    } else {
        // RTLD_NOLOAD 失败，说明 System.load 没成功加载或没调用。
        // 直接 dlopen 加载，能拿到详细 dlerror（缺哪个依赖等）。
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "RTLD_NOLOAD 未命中，尝试 dlopen(RTLD_NOW): %s", engine_path);
        dlerror(); // 清空错误
        handle = dlopen(engine_path, RTLD_NOW | RTLD_GLOBAL);
        if (!handle) {
            const char* err = dlerror();
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "dlopen failed for %s: %s\n"
                "（dartvm_*.so 加载失败。常见原因：\n"
                " 1. 缺少 NEEDED 依赖（如 libicuuc.so.73 / libicudata.so.73 symlink 未创建）；\n"
                " 2. so 文件损坏或架构不匹配；\n"
                " 3. 文件权限问题）",
                engine_path, err ? err : "(unknown)");
            env->ReleaseStringUTFChars(engineSoPath, engine_path);
            env->ReleaseStringUTFChars(soPath, so);
            env->ReleaseStringUTFChars(dbPath, db);
            env->ReleaseStringUTFChars(cacheDir, cache_dir);
            return -999;
        }
        __android_log_print(ANDROID_LOG_INFO, TAG,
            "dlopen 成功: %s", engine_path);
    }

    auto fn = reinterpret_cast<blutter_analyze_fn>(dlsym(handle, "blutter_analyze"));
    if (!fn) {
        const char* err = dlerror();
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "dlsym blutter_analyze failed in %s: %s",
            engine_path, err ? err : "(null)");
        dlclose(handle);
        env->ReleaseStringUTFChars(engineSoPath, engine_path);
        env->ReleaseStringUTFChars(soPath, so);
        env->ReleaseStringUTFChars(dbPath, db);
        env->ReleaseStringUTFChars(cacheDir, cache_dir);
        return -998;
    }

    // 把工作目录切到 app cacheDir/blutter_tmp，避免 blutter 内部创建临时文件失败
    // （Android app 对 /tmp 无写权限，会立即返回 TempDirError=-1）
    if (prepare_workdir(cache_dir) != 0) {
        __android_log_print(ANDROID_LOG_WARN, TAG,
            "工作目录准备失败，继续尝试 blutter_analyze（可能失败）");
    }

    // ========== 前置诊断：验证所有输入路径是否就绪 ==========
    // （因为 blutter_analyze 返回码语义不明，1ms 返回 -1 时很难判断是临时目录、SO、还是 DB 问题）
    {
        struct stat st;

        // 1. libapp.so 是否存在 + 可读
        if (stat(so, &st) != 0) {
            __android_log_print(ANDROID_LOG_ERROR, TAG,
                "输入诊断 FAIL: so_path 不存在 stat(%s) errno=%d(%s)",
                so, errno, strerror(errno));
        } else {
            if (!(st.st_mode & S_IRUSR)) {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "输入诊断 WARN: so_path 无读权限: %s (mode=%o)", so, st.st_mode);
            }
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "输入诊断 OK: so_path=%s size=%ld bytes mode=%o",
                so, (long)st.st_size, st.st_mode);

            // 校验前 4 字节是 ELF magic
            int fd = open(so, O_RDONLY);
            if (fd >= 0) {
                unsigned char magic[4];
                ssize_t r = read(fd, magic, 4);
                close(fd);
                if (r == 4 && magic[0] == 0x7f && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F') {
                    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "输入诊断 OK: so_path ELF magic 验证通过");
                } else {
                    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "输入诊断 WARN: so_path 不是有效 ELF (magic=%02x %02x %02x %02x)",
                        magic[0], magic[1], magic[2], magic[3]);
                }
            }
        }

        // 2. db 父目录是否存在 + 可写
        // db_path 形如 /data/.../cache/analysis_X.db，取父目录
        char* db_dir = strdup(db);
        if (db_dir) {
            char* last_slash = strrchr(db_dir, '/');
            if (last_slash) {
                *last_slash = '\0';
                if (stat(db_dir, &st) != 0) {
                    __android_log_print(ANDROID_LOG_ERROR, TAG,
                        "输入诊断 FAIL: db 父目录不存在 stat(%s) errno=%d(%s)",
                        db_dir, errno, strerror(errno));
                } else if (!(st.st_mode & S_IWUSR)) {
                    __android_log_print(ANDROID_LOG_WARN, TAG,
                        "输入诊断 WARN: db 父目录无写权限: %s (mode=%o)", db_dir, st.st_mode);
                } else {
                    __android_log_print(ANDROID_LOG_INFO, TAG,
                        "输入诊断 OK: db 父目录=%s 存在可写", db_dir);

                    // 在 db 父目录下试创建测试文件
                    char test_path[PATH_MAX];
                    snprintf(test_path, sizeof(test_path),
                             "%s/.blutter_test_%d.tmp", db_dir, (int)getpid());
                    FILE* f = fopen(test_path, "wb");
                    if (f) {
                        fwrite("ok", 1, 2, f);
                        fclose(f);
                        unlink(test_path);
                        __android_log_print(ANDROID_LOG_INFO, TAG,
                            "输入诊断 OK: db 父目录可写（测试文件创建/删除成功）");
                    } else {
                        __android_log_print(ANDROID_LOG_ERROR, TAG,
                            "输入诊断 FAIL: db 父目录无法写入 fopen(%s) errno=%d(%s)",
                            test_path, errno, strerror(errno));
                    }
                }
            }
            free(db_dir);
        }

        // 3. cwd 目录可写（blutter 在 cwd 下建临时目录/文件）
        char cwd_buf[PATH_MAX];
        if (getcwd(cwd_buf, sizeof(cwd_buf))) {
            char cwd_test[PATH_MAX];
            snprintf(cwd_test, sizeof(cwd_test),
                     "%s/.blutter_cwdtest_%d.tmp", cwd_buf, (int)getpid());
            FILE* f = fopen(cwd_test, "wb");
            if (f) {
                fwrite("ok", 1, 2, f);
                fclose(f);
                unlink(cwd_test);
                __android_log_print(ANDROID_LOG_INFO, TAG,
                    "输入诊断 OK: cwd=%s 可写", cwd_buf);
            } else {
                __android_log_print(ANDROID_LOG_ERROR, TAG,
                    "输入诊断 FAIL: cwd=%s 无法写入 fopen errno=%d(%s)",
                    cwd_buf, errno, strerror(errno));
            }
        }

        // 4. /tmp 是否存在可写（blutter 可能硬编码 /tmp）
        const char* hard_tmp = "/tmp";
        if (stat(hard_tmp, &st) != 0) {
            __android_log_print(ANDROID_LOG_WARN, TAG,
                "输入诊断 WARN: %s 不存在（blutter 若硬编码使用将失败）— errno=%d(%s)",
                hard_tmp, errno, strerror(errno));
        } else {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "输入诊断 INFO: %s 存在 (mode=%o)", hard_tmp, st.st_mode);
            char hard_tmp_test[PATH_MAX];
            snprintf(hard_tmp_test, sizeof(hard_tmp_test),
                     "%s/.blutter_hardtmp_%d.tmp", hard_tmp, (int)getpid());
            FILE* f = fopen(hard_tmp_test, "wb");
            if (f) {
                fwrite("ok", 1, 2, f);
                fclose(f);
                unlink(hard_tmp_test);
                __android_log_print(ANDROID_LOG_INFO, TAG,
                    "输入诊断 OK: %s 可写", hard_tmp);
            } else {
                __android_log_print(ANDROID_LOG_WARN, TAG,
                    "输入诊断 WARN: %s 不可写 errno=%d(%s) — "
                    "若 blutter 硬编码用 /tmp，需重新编译 dartvm 引擎",
                    hard_tmp, errno, strerror(errno));
            }
        }
    }

    // ========== 重定向 stderr，捕获 blutter 的 fprintf(stderr, ...) 输出 ==========
    // blutter_entry.cpp 用 fprintf(stderr, ...) 打印错误（如 "fler-dart: analysis failed: %s"），
    // 但 Android logcat 默认不捕获 stderr。我们重定向 stderr 到 pipe，调用后读出来打到 logcat。
    int stderr_pipe[2] = {-1, -1};
    int saved_stderr = -1;
    bool stderr_redirected = false;

    if (pipe(stderr_pipe) == 0) {
        saved_stderr = dup(STDERR_FILENO);
        if (saved_stderr >= 0) {
            dup2(stderr_pipe[1], STDERR_FILENO);
            close(stderr_pipe[1]);
            stderr_redirected = true;
        } else {
            close(stderr_pipe[0]);
            close(stderr_pipe[1]);
        }
    }

    // ========== 安装信号处理器，捕获 SIGSEGV/SIGBUS/SIGFPE/SIGABRT/SIGILL ==========
    // blutter_entry.cpp 的 try-catch 只能捕获 C++ 异常，不能捕获段错误。
    // 段错误会直接触发 SIGSEGV，默认行为是杀进程。我们用 sigsetjmp/siglongjmp
    // 在崩溃时跳回这里，返回错误码 -997，并把崩溃地址打到 logcat。
    //
    // SIGABRT：Dart VM 静态库以 -fno-exceptions 编译，当快照/堆数据异常导致
    // 超大分配（如 malloc 4.5PB 失败）时，std::bad_alloc 无法跨帧展开到
    // blutter_entry 的 catch，触发 std::terminate → abort() → SIGABRT。
    // 这里同样捕获，让 App 进程存活并返回错误码，而不是直接被杀。
    //
    // 注意：siglongjmp 跳过栈展开，blutter 内部的 C++ 对象不会析构，
    // 但进程还活着，能继续运行。内存泄漏可接受（分析失败本就是异常路径）。
    //
    // thread_local：多线程并发分析（如用户同时跑多个 Blutter 分析）时，
    // 静态 jmp_buf 会被后一个线程的 sigsetjmp 覆盖，前一个线程崩溃时
    // siglongjmp 会跳到错误的栈帧导致未定义行为。改为每线程一份，互不干扰。
    static thread_local sigjmp_buf jmp_buf;
    static thread_local volatile sig_atomic_t crash_addr = 0;
    static thread_local volatile sig_atomic_t crash_signo = 0;

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = [](int sig, siginfo_t* info, void*) {
        crash_signo = sig;
        crash_addr = reinterpret_cast<uintptr_t>(info ? info->si_addr : nullptr);
        siglongjmp(jmp_buf, 1);
    };
    sa.sa_flags = SA_SIGINFO;
    sigemptyset(&sa.sa_mask);

    struct sigaction old_segv, old_bus, old_fpe, old_abrt, old_ill;
    sigaction(SIGSEGV, &sa, &old_segv);
    sigaction(SIGBUS, &sa, &old_bus);
    sigaction(SIGFPE, &sa, &old_fpe);
    sigaction(SIGABRT, &sa, &old_abrt);
    sigaction(SIGILL, &sa, &old_ill);

    __android_log_print(ANDROID_LOG_INFO, TAG, "Calling blutter_analyze...");

    int ret;
    if (sigsetjmp(jmp_buf, 1) == 0) {
        // 正常路径：调用 blutter_analyze
        ret = fn(so, db);
        __android_log_print(ANDROID_LOG_INFO, TAG, "blutter_analyze returned: %d", ret);
    } else {
        // 崩溃路径：信号处理器 siglongjmp 跳回这里
        const char* crash_reason;
        switch (crash_signo) {
            case SIGABRT:
                crash_reason = "SIGABRT(abort)：Dart VM 内部异常终止，"
                    "常见于快照版本不匹配导致超大分配（malloc 失败 → std::bad_alloc → terminate）";
                break;
            case SIGILL:
                crash_reason = "SIGILL：执行非法指令，可能是引擎与 CPU 特性不匹配或代码损坏";
                break;
            default:
                crash_reason = "段错误/非法内存访问：blutter 访问了未映射内存。"
                    "常见原因：\n"
                    " 1. libapp.so 的 Dart 版本和 dartvm_*.so 不匹配；\n"
                    " 2. libapp.so 是 profile/debug 模式但 blutter 不支持；\n"
                    " 3. blutter 内部解析 ELF 时结构体偏移错位";
                break;
        }
        __android_log_print(ANDROID_LOG_ERROR, TAG,
            "blutter_analyze 崩溃! signal=%d fault_addr=0x%llx\n%s",
            (int)crash_signo, (unsigned long long)crash_addr, crash_reason);
        ret = -997;  // 特殊错误码：信号崩溃
    }

    // 恢复原信号处理器
    sigaction(SIGSEGV, &old_segv, nullptr);
    sigaction(SIGBUS, &old_bus, nullptr);
    sigaction(SIGFPE, &old_fpe, nullptr);
    sigaction(SIGABRT, &old_abrt, nullptr);
    sigaction(SIGILL, &old_ill, nullptr);

    // 恢复原 stderr，读取重定向期间写入的输出
    if (stderr_redirected) {
        fflush(stderr);
        dup2(saved_stderr, STDERR_FILENO);
        close(saved_stderr);

        // 设为非阻塞，避免读不完卡住
        int flags = fcntl(stderr_pipe[0], F_GETFL, 0);
        fcntl(stderr_pipe[0], F_SETFL, flags | O_NONBLOCK);

        // 读全部输出
        std::string captured;
        char buf[1024];
        ssize_t n;
        while ((n = read(stderr_pipe[0], buf, sizeof(buf) - 1)) > 0) {
            buf[n] = '\0';
            captured += buf;
        }
        close(stderr_pipe[0]);

        if (!captured.empty()) {
            __android_log_print(ANDROID_LOG_INFO, TAG,
                "=== blutter stderr 输出（%d 字节）===", (int)captured.size());
            __android_log_print(ANDROID_LOG_INFO, TAG, "%s", captured.c_str());
            __android_log_print(ANDROID_LOG_INFO, TAG, "=== blutter stderr 输出结束 ===");
        } else {
            __android_log_print(ANDROID_LOG_INFO, TAG, "blutter 无 stderr 输出");
        }
    }

    dlclose(handle);
    env->ReleaseStringUTFChars(engineSoPath, engine_path);
    env->ReleaseStringUTFChars(soPath, so);
    env->ReleaseStringUTFChars(dbPath, db);
    env->ReleaseStringUTFChars(cacheDir, cache_dir);
    return ret;
}
