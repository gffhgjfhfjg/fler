package com.ai.fler.core.jni

/**
 * ELF 节区信息。
 * 对应 C++ 层的 Section 结构体。
 */
data class ElfSection(
    val name: String,
    val type: Int,
    val offset: Long,
    val size: Long,
    val address: Long,
    val flags: Long
) {
    companion object {
        const val SHT_NULL = 0
        const val SHT_PROGBITS = 1
        const val SHT_SYMTAB = 2
        const val SHT_STRTAB = 3
        const val SHT_RELA = 4
        const val SHT_HASH = 5
        const val SHT_DYNAMIC = 6
        const val SHT_NOTE = 7
        const val SHT_NOBITS = 8
        const val SHT_REL = 9
        const val SHT_DYNSYM = 11

        const val SHF_WRITE = 0x1L
        const val SHF_ALLOC = 0x2L
        const val SHF_EXECINSTR = 0x4L
    }
}

/**
 * ELF 符号信息。
 * 对应 C++ 层的 Symbol 结构体。
 */
data class ElfSymbol(
    val name: String,
    val address: Long,
    val size: Long,
    val type: Byte,
    val binding: Byte,
    val shndx: Short
) {
    companion object {
        const val STB_LOCAL: Byte = 0
        const val STB_GLOBAL: Byte = 1
        const val STB_WEAK: Byte = 2

        const val STT_NOTYPE: Byte = 0
        const val STT_OBJECT: Byte = 1
        const val STT_FUNC: Byte = 2
        const val STT_SECTION: Byte = 3
        const val STT_FILE: Byte = 4
        const val STT_COMMON: Byte = 5
        const val STT_TLS: Byte = 6
    }
}

/**
 * ELF 解析器绑定。
 *
 * 通过 JNI 桥接调用 C++ 层的 ElfParser，
 * 提供节区查询、符号查找、数据读取/写入等功能。
 *
 * 实现 [AutoCloseable] 以确保 native 资源正确释放。
 * 使用 use {} 块自动关闭，或在 finally 中调用 close()。
 *
 * 线程安全: 每个实例独占自己的 native handle。
 */
class ElfParserBindings : AutoCloseable {

    private var nativeHandle: Long = 0
    private var closed = false

    /**
     * 打开 ELF 文件。
     * @param path 文件绝对路径
     * @return 是否打开成功
     */
    fun open(path: String): Boolean {
        check(!closed) { "ElfParserBindings already closed" }
        nativeHandle = nativeOpen(path)
        return nativeHandle != 0L
    }

    /**
     * 关闭并释放 native 资源。
     * 幂等操作，可安全多次调用。
     */
    override fun close() {
        if (!closed && nativeHandle != 0L) {
            nativeClose(nativeHandle)
            nativeHandle = 0L
            closed = true
        }
    }

    /**
     * 防止 GC 时泄漏 native 资源。
     */
    @Suppress("unused")
    protected fun finalize() {
        close()
    }

    /**
     * 获取所有节区。
     */
    fun getSections(): List<ElfSection> {
        val array = nativeGetSections(nativeHandle) ?: return emptyList()
        val result = mutableListOf<ElfSection>()
        for (i in 0 until array.size) {
            val sec = array[i]
            result.add(sec)
        }
        return result
    }

    /**
     * 获取所有符号（.symtab）。
     */
    fun getSymbols(): List<ElfSymbol> {
        val array = nativeGetSymbols(nativeHandle) ?: return emptyList()
        val result = mutableListOf<ElfSymbol>()
        for (i in 0 until array.size) {
            result.add(array[i])
        }
        return result
    }

    /**
     * 获取所有动态符号（.dynsym）。
     */
    fun getDynamicSymbols(): List<ElfSymbol> {
        val array = nativeGetDynamicSymbols(nativeHandle) ?: return emptyList()
        val result = mutableListOf<ElfSymbol>()
        for (i in 0 until array.size) {
            result.add(array[i])
        }
        return result
    }

    /**
     * 按名称查找符号地址。
     * @return 符号虚拟地址，0 表示未找到
     */
    fun findSymbolAddress(name: String): Long {
        return nativeFindSymbolAddress(nativeHandle, name)
    }

    /**
     * 读取指定节区的原始数据。
     */
    fun getSectionData(sectionName: String): ByteArray {
        return nativeGetSectionData(nativeHandle, sectionName) ?: ByteArray(0)
    }

    /**
     * 从指定偏移读取指定长度的字节。
     */
    fun readBytes(offset: Long, size: Long): ByteArray {
        return nativeReadBytes(nativeHandle, offset, size) ?: ByteArray(0)
    }

    /**
     * 从指定偏移写入字节到文件。
     * @return 是否写入成功
     */
    fun writeBytes(offset: Long, data: ByteArray): Boolean {
        return nativeWriteBytes(nativeHandle, offset, data)
    }

    /**
     * 计算指定范围的 CRC32。
     */
    fun computeCRC32(offset: Long, size: Long): Long {
        return nativeComputeCRC32(nativeHandle, offset, size)
    }

    // ========== JNI 方法声明 ==========

    private external fun nativeOpen(path: String): Long
    private external fun nativeClose(handle: Long)
    private external fun nativeGetSections(handle: Long): Array<ElfSection>?
    private external fun nativeGetSymbols(handle: Long): Array<ElfSymbol>?
    private external fun nativeGetDynamicSymbols(handle: Long): Array<ElfSymbol>?
    private external fun nativeFindSymbolAddress(handle: Long, name: String): Long
    private external fun nativeGetSectionData(handle: Long, name: String): ByteArray?
    private external fun nativeReadBytes(handle: Long, offset: Long, size: Long): ByteArray?
    private external fun nativeWriteBytes(handle: Long, offset: Long, data: ByteArray): Boolean
    private external fun nativeComputeCRC32(handle: Long, offset: Long, size: Long): Long
}
