package com.ai.fler.core.analysis

import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.DisasmInstruction
import java.io.RandomAccessFile

/**
 * Dart AOT 函数入口探测器（不依赖固定偏移）。
 *
 * 背景：Blutter 对匿名闭包恢复的 `method.address` 往往是对象池槽 vaddr（指向
 * Dart stub / trampoline 代码区），并非真实函数入口——真实 body 与该槽地址的
 * 距离因 app / Dart 版本 / 编译选项而异（不可硬编码，如 libapp 有 +0x40 偏差，
 * 其它 app 可能不同）。本工具用「验证 + 序言扫描」通用地定位真实入口：
 *
 *  - [isPrologue]：标准函数序言识别（Dart AOT 常见形态）：
 *      stp x29, x30, [x15, #-0x10]!  /  sub x15, x15, #N  /  mov x29, x15
 *      stp fp,  lr,  [SP, #-0x10]!   （其它引擎/库形态）
 *  - [isStubHead]：stub/trampoline 开头识别（blr x30 / br xN / 短跳板）。
 *  - [resolve]：从给定 vaddr 出发：
 *      1) 直接验证当前地址；2) 否则向高地址扫描 scanBytes 内找第一个序言；
 *      3) 找不到序言则找第一个 `ret` 结束的小 leaf（无 prologue）。
 *
 * 供 get_method / analyze_method / getter_return_shape 等工具在 functionSize=0
 * 或 src_code 为空时调用，把「槽地址」解析为「真实代码入口」。
 */
object DartEntryResolver {

    /** 探测结果。 */
    data class Entry(
        val entryVaddr: Long,     // 判定为真实函数入口的 vaddr
        val sourceVaddr: Long,    // 输入（Blutter 槽 vaddr）
        val offset: Long,         // entryVaddr - sourceVaddr（诊断用，勿作硬编码）
        val confidence: Int,      // 0=原地址直接可用; 1=扫描到序言; 2=leaf 兜底; -1=失败
        val reason: String,       // 判据描述（首指令原文 / 扫描到的序言原文）
        val firstInsns: List<DisasmInstruction>, // 入口处的头几条指令
    )

    /** 标准 Dart AOT 序言指令 opStr 前缀（ARM64）。 */
    private val PROLOGUE_PATTERNS = listOf(
        "stp x29, x30, [x15, #-0x10]!",
        "stp x29, x30, [x15, #-0x18]!",
        "stp x29, x30, [x15, #-0x20]!",
        "stp fp, lr, [sp, #-0x10]!",
        "stp fp, lr, [sp, #-0x18]!",
        "stp fp, lr, [sp, #-0x20]!",
    )

    /** stub / trampoline 开头的指令形态。 */
    private val STUB_MNEMONICS = setOf("blr", "br", "b")

    /**
     * 判断 [ins] 是否构成标准函数序言。
     */
    fun isPrologue(ins: DisasmInstruction): Boolean {
        val text = "${ins.mnemonic} ${ins.opStr}".trim().lowercase()
        for (p in PROLOGUE_PATTERNS) {
            if (text.startsWith(p)) return true
        }
        // 变体：sub x15, x15, #N（无 stp 的压栈序言）
        if (ins.mnemonic == "sub" && ins.opStr.startsWith("x15, x15, #")) return true
        if (ins.mnemonic == "sub" && ins.opStr.startsWith("sp, sp, #")) return true
        return false
    }

    /** 判断指令是否 stub 开头（间接跳转 / 短无条件跳）。 */
    fun isStubHead(ins: DisasmInstruction): Boolean =
        ins.mnemonic in STUB_MNEMONICS

    /**
     * 从 [sourceVaddr] 解析真实函数入口。
     *
     * @param soPath libapp.so 绝对路径
     * @param sourceVaddr Blutter 槽 vaddr（或任意疑似函数地址）
     * @param textSection .text 节区间（vaddrBase→文件偏移换算；null 时假定 vaddr==文件偏移）
     * @param scanBytes 向高地址扫描上限（默认 4KB）
     */
    fun resolve(
        soPath: String,
        sourceVaddr: Long,
        textSection: StringXrefScanner.TextRange? = null,
        scanBytes: Int = 4096,
    ): Entry? {
        val entry = tryResolveEntry(soPath, sourceVaddr, textSection, scanBytes) ?: return null
        return entry
    }

    private fun tryResolveEntry(
        soPath: String,
        sourceVaddr: Long,
        textSection: StringXrefScanner.TextRange?,
        scanBytes: Int,
    ): Entry? {
        val head = readAndDisasm(soPath, sourceVaddr, textSection, 64) ?: return null
        if (head.isEmpty()) return null

        // 1) 直接验证当前地址
        val first = head.first()
        if (isPrologue(first)) {
            return Entry(
                entryVaddr = sourceVaddr,
                sourceVaddr = sourceVaddr,
                offset = 0,
                confidence = 0,
                reason = "direct-prologue: ${insText(first)}",
                firstInsns = head,
            )
        }

        // 2) 扫描序言（向高地址，最多 scanBytes）
        val scanBlock = readAndDisasm(soPath, sourceVaddr, textSection, scanBytes) ?: return null
        for (ins in scanBlock) {
            if (isPrologue(ins) && ins.address >= sourceVaddr) {
                val fromHere = readAndDisasm(soPath, ins.address, textSection, 64) ?: continue
                return Entry(
                    entryVaddr = ins.address,
                    sourceVaddr = sourceVaddr,
                    offset = ins.address - sourceVaddr,
                    confidence = 1,
                    reason = "scanned-prologue@0x${ins.address.toString(16)}: ${insText(ins)}",
                    firstInsns = fromHere,
                )
            }
        }

        // 3) leaf 兜底：当前地址即一段短函数（首个 ret 前无 prologue）
        val leafLen = countUntilRet(scanBlock)
        if (leafLen > 0) {
            val fromHere = readAndDisasm(soPath, sourceVaddr, textSection, minOf(leafLen, 64)) ?: return null
            return Entry(
                entryVaddr = sourceVaddr,
                sourceVaddr = sourceVaddr,
                offset = 0,
                confidence = 2,
                reason = "leaf@0x${sourceVaddr.toString(16)}: ${leafLen} insns to ret",
                firstInsns = fromHere,
            )
        }

        return null
    }

    /** 从头反汇编到第一个 ret 的指令数（0=未发现）。 */
    private fun countUntilRet(insns: List<DisasmInstruction>): Int {
        for (i in insns.indices) {
            val ins = insns[i]
            if (ins.mnemonic == "ret" || ins.mnemonic == "brk") return i + 1
            if (i > 64) break
        }
        return 0
    }

    private fun insText(ins: DisasmInstruction): String = "${ins.mnemonic} ${ins.opStr}".trim()

    private fun readAndDisasm(
        soPath: String,
        startVaddr: Long,
        textSection: StringXrefScanner.TextRange?,
        size: Int,
    ): List<DisasmInstruction>? {
        if (size <= 0) return null
        return try {
            RandomAccessFile(soPath, "r").use { raf ->
                // 换算 vaddr → 文件偏移；无 .text 信息时假定 vaddr==文件偏移（bias=0）
                val paddr = if (textSection != null && startVaddr >= textSection.vaddrBase) {
                    textSection.fileOffset + (startVaddr - textSection.vaddrBase)
                } else {
                    startVaddr
                }
                raf.seek(paddr)
                val buf = ByteArray(size)
                val read = raf.read(buf, 0, size)
                if (read <= 0) return@use null
                val block = buf.copyOf(read)
                CapstoneBindings.disassembleWithCapstone(block, startVaddr) ?: emptyList()
            }
        } catch (e: Exception) {
            null
        }
    }
}
