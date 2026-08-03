package com.ai.fler.core.jni

/**
 * Rizin JNI 绑定。
 *
 * 对应 rizin_jni.cpp 中的 native 方法。所有 Rizin 操作通过此对象调用：
 * - [open] → 创建 RzCore + 打开文件
 * - [analyze] → 执行 aaa 自动分析
 * - [cmdStr] → 执行任意 Rizin 命令，返回字符串输出
 * - [readBytes] / [writeBytes] → 直接字节 IO
 *
 * Rizin 静态链接进 libfler_jni.so（26 个 librz_*.a + libcapstone.a），
 * 不依赖引擎包，零引擎下载即可使用 SO 分析功能。
 *
 * 使用方式：
 * ```
 * val handle = RizinBindings.open("/path/to/lib.so")
 * RizinBindings.analyze(handle)           // aaa
 * val json = RizinBindings.cmdStr(handle, "iSj")  // 节区 JSON
 * RizinBindings.close(handle)
 * ```
 */
object RizinBindings {

    /**
     * 创建 RzCore 实例，打开文件并加载二进制信息。
     *
     * @param path 文件绝对路径
     * @return RzCore* 指针（>0），失败返回 0
     */
    fun open(path: String): Long =
        nativeOpen(path)

    /**
     * 释放 RzCore 实例。
     */
    fun close(handle: Long) {
        nativeClose(handle)
    }

    /**
     * 执行 aaa 自动分析（函数识别、交叉引用等）。
     *
     * 首次打开文件后调用一次，后续查询不需要重复分析。
     *
     * @return true 成功
     */
    fun analyze(handle: Long): Boolean =
        nativeAnalyze(handle)

    /**
     * 执行 Rizin 命令并返回字符串输出。
     *
     * 核心方法：所有数据查询都通过这里完成。
     * 命令后缀 j 获取 JSON（如 "iSj" "isj" "aflj" "pdj 10 @ 0x1234"）。
     *
     * @param cmd Rizin 命令
     * @return 命令输出字符串，失败返回 null
     */
    fun cmdStr(handle: Long, cmd: String): String? =
        nativeCmdStr(handle, cmd)

    /**
     * 直接读取字节（比 pxj 更高效，不做 hex 编码）。
     *
     * @param offset 文件偏移
     * @param size  读取长度
     * @return 字节数组，失败返回 null
     */
    fun readBytes(handle: Long, offset: Long, size: Int): ByteArray? =
        nativeReadBytes(handle, offset, size)

    /**
     * 直接写入字节（文件偏移寻址）。
     *
     * native 层会把文件偏移翻译成 Rizin 地址空间中的 vaddr 再写入，
     * 并关闭 io.cache 保证落盘。
     *
     * @param offset 文件偏移
     * @param data   字节数组
     * @return true 成功
     */
    fun writeBytes(handle: Long, offset: Long, data: ByteArray): Boolean =
        nativeWriteBytes(handle, offset, data)

    // ===== JNI native 方法 =====

    @JvmStatic private external fun nativeOpen(path: String): Long
    @JvmStatic private external fun nativeClose(handle: Long)
    @JvmStatic private external fun nativeAnalyze(handle: Long): Boolean
    @JvmStatic private external fun nativeCmdStr(handle: Long, cmd: String): String?
    @JvmStatic private external fun nativeReadBytes(handle: Long, offset: Long, size: Int): ByteArray?
    @JvmStatic private external fun nativeWriteBytes(handle: Long, offset: Long, data: ByteArray): Boolean
}
