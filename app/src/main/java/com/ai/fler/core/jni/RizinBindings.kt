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
 * Rizin 打包在 libfler_rizin.so（26 个 librz_*.a 静态链接，capstone 依赖
 * 共享的 libfler_capstone.so），首次使用时经 [NativeLoader] 懒加载，
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

    /** so 未加载（懒加载失败）时各方法返回的降级默认值语义与 native 失败一致。 */
    private fun ready(): Boolean = NativeLoader.tryLoadComponent(NativeLoader.Component.RIZIN)

    /**
     * 创建 RzCore 实例，打开文件并加载二进制信息。
     *
     * @param path 文件绝对路径
     * @return RzCore* 指针（>0），失败返回 0
     */
    fun open(path: String): Long =
        if (ready()) nativeOpen(path) else 0L

    /**
     * 释放 RzCore 实例。
     */
    fun close(handle: Long) {
        if (ready()) nativeClose(handle)
    }

    /**
     * 执行 aaa 自动分析（函数识别、交叉引用等）。
     *
     * 首次打开文件后调用一次，后续查询不需要重复分析。
     *
     * @return true 成功
     */
    fun analyze(handle: Long): Boolean =
        if (ready()) nativeAnalyze(handle) else false

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
        if (ready()) nativeCmdStr(handle, cmd) else null

    /**
     * 直接读取字节（比 pxj 更高效，不做 hex 编码）。
     *
     * @param offset 文件偏移
     * @param size  读取长度
     * @return 字节数组，失败返回 null
     */
    fun readBytes(handle: Long, offset: Long, size: Int): ByteArray? =
        if (ready()) nativeReadBytes(handle, offset, size) else null

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
        if (ready()) nativeWriteBytes(handle, offset, data) else false

    /**
     * 文件偏移（物理地址）→ 虚拟地址（按段 map 换算，与 vp 对称）。
     * 映射外回退原值。
     */
    fun paddrToVaddr(handle: Long, paddr: Long): Long =
        if (ready()) nativePaddrToVaddr(handle, paddr) else paddr

    /**
     * 保存 Rizin Project 到文件。
     *
     * 将当前分析状态（函数、符号、xref、flag 等）持久化到 .rzdb 文件，
     * 下次打开同一 SO 文件时可直接加载，跳过 aaa 全量分析。
     *
     * @param handle RzCore* 指针
     * @param path 项目文件绝对路径
     * @return true 成功
     */
    fun projectSave(handle: Long, path: String): Boolean =
        if (ready()) nativeProjectSave(handle, path) else false

    /**
     * 加载 Rizin Project 文件。
     *
     * 从 .rzdb 文件恢复分析状态，跳过 aaa 全量分析。
     *
     * @param handle RzCore* 指针
     * @param path 项目文件绝对路径
     * @return true 成功
     */
    fun projectLoad(handle: Long, path: String): Boolean =
        if (ready()) nativeProjectLoad(handle, path) else false

    // ===== JNI native 方法 =====

    @JvmStatic private external fun nativeOpen(path: String): Long
    @JvmStatic private external fun nativeClose(handle: Long)
    @JvmStatic private external fun nativeAnalyze(handle: Long): Boolean
    @JvmStatic private external fun nativeCmdStr(handle: Long, cmd: String): String?
    @JvmStatic private external fun nativeReadBytes(handle: Long, offset: Long, size: Int): ByteArray?
    @JvmStatic private external fun nativeWriteBytes(handle: Long, offset: Long, data: ByteArray): Boolean
    @JvmStatic private external fun nativePaddrToVaddr(handle: Long, paddr: Long): Long
    @JvmStatic private external fun nativeProjectSave(handle: Long, path: String): Boolean
    @JvmStatic private external fun nativeProjectLoad(handle: Long, path: String): Boolean
}
