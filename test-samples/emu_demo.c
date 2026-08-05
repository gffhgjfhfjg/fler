/*
 * emu_demo.c —— fler 仿真引擎演示测试库
 *
 * 编译（Windows PowerShell，NDK r28）：
 *   & "$env:LOCALAPPDATA\Android\Sdk\ndk\28.2.13676358\toolchains\llvm\prebuilt\windows-x86_64\bin\clang.exe" `
 *     --target=aarch64-linux-android24 -O2 -fPIC -shared -o libemu_demo.so emu_demo.c
 *
 * 设计约束（配合 fler Unicorn 仿真环境）：
 * - 不依赖 libc / 其他 so（仿真环境不解析 DT_NEEDED、不做重定位修复）
 * - 导出函数之间不互相调用（避免 PLT 跳转），递归用 static 内部实现
 * - 指针参数请使用仿真会话内的可写内存：
 *     简易 heap: 0x50000000 ~ 0x50800000（8MB，RW）
 *     栈:       0x40000000 ~ 0x40100000（1MB，RW）
 */

typedef unsigned long long u64;

/* ── 纯寄存器函数（UI 直接可玩）────────────────────────── */

/* x0 + x1。示例：add(0x1234, 0x5678) → 0x68AC */
long long add(long long a, long long b) {
    return a + b;
}

/* a ^ b（位异或）。常用于验证参数透传 */
long long xor2(long long a, long long b) {
    return a ^ b;
}

/* 绝对值 */
long long abs64(long long x) {
    return x < 0 ? -x : x;
}

/* 0 + 1 + ... + (n-1)，循环演示。sum_to(100) → 4950 */
long long sum_to(long long n) {
    long long s = 0;
    for (long long i = 0; i < n; i++) s += i;
    return s;
}

/* 字节序翻转 */
u64 reverse_bytes(u64 x) {
    u64 r = 0;
    for (int i = 0; i < 8; i++) {
        r = (r << 8) | (x & 0xFF);
        x >>= 8;
    }
    return r;
}

/* 递归斐波那契（static 实现避免 PLT；fib(25) → 75025，约 250 万条指令） */
static long long fib_impl(long long n) {
    if (n < 2) return n;
    return fib_impl(n - 1) + fib_impl(n - 2);
}
long long fib(long long n) {
    return fib_impl(n);
}

/* 忙等 n 次循环，用于性能观察。spin(1000000) ≈ 200 万条指令 */
long long spin(long long n) {
    volatile long long x = 0;
    for (long long i = 0; i < n; i++) x += i;
    return x;
}

/* ── 内存操作函数（需先向 heap 写数据，MCP 最佳）──────── */

/* 对 [buf, buf+len) 逐字节异或 key。先用 emu_write_memory 往
 * 0x50000000 写字节，再 xor_buf(0x50000000, len, key)，
 * 之后 emu_read_memory 读回结果 */
void xor_buf(unsigned char* buf, long long len, unsigned char key) {
    for (long long i = 0; i < len; i++) buf[i] ^= key;
}

/* djb2 哈希。hash_buf(0x50000000, 5)，buf="fler!" → 0x310F71B8EF */
u64 hash_buf(const unsigned char* buf, long long len) {
    u64 h = 5381;
    for (long long i = 0; i < len; i++) {
        h = ((h << 5) + h) + buf[i];
    }
    return h;
}

/* C 风格字符串长度。str_len(0x50000000)，buf="fler!" → 5 */
long long str_len(const char* s) {
    long long n = 0;
    while (s[n]) n++;
    return n;
}
