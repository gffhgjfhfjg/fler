package com.ai.fler.core.analysis

import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.DisasmInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

/**
 * 方法级控制流图（CFG）：对单个 Dart 方法 body 反汇编并划分基本块。
 *
 * Dart AOT 混淆包方法无 Rizin CFG（大库 Rizin 识别不出函数），本工具用 Capstone
 * 反汇编方法区间，按控制流指令（b / b.* / cbz / cbnz / tbz / tbnz / ret）切分基本块，
 * 输出块表与返回路径（return 指令所在块/地址、返回值来源寄存器）。
 *
 * 用途：getter_return_shape 类工具的方法体边界、函数内跳转识别、CFG 浏览。
 */
object MethodCfg {

    /** 一个基本块。 */
    data class Block(
        val startVaddr: Long,
        val endVaddr: Long,     // 块末尾指令地址（含）
        val size: Int,
        val succs: List<Long>,  // 后继块起始地址（含 fallthrough 与分支目标）
        val isReturn: Boolean,  // 以 ret 结尾
        val isTailCall: Boolean, // 以 b 结尾且目标在方法体外（尾调用返回）
        val terminator: String, // 块结尾指令摘要（"ret" / "b 0x.." / "bl 0x.." 等）
    )

    /** 一条返回路径。 */
    data class ReturnPath(
        val vaddr: Long,      // ret / 尾调用 b 指令地址
        val lastWriteW0: String?, // 该路径最后一次写入 w0/x0 的指令摘要（如 "mov w0, #1"）
        val isTailCall: Boolean, // true=经 b 尾调用返回，false=ret 返回
        val writerVaddr: Long = 0, // 最后一次写 w0/x0 的指令地址（补丁坐标）
        val writerHex: String = "", // 该指令机器码 hex（可直接替换补丁）
    )

    /** 方法 CFG 结果。 */
    data class Result(
        val startVaddr: Long,
        val endVaddr: Long,
        val blocks: List<Block>,
        val returns: List<ReturnPath>,
        val truncated: Boolean,
    )

    /**
     * 反汇编 [startVaddr, endVaddr) 并构建基本块。
     *
     * @param soPath libapp.so 绝对路径
     * @param textSection .text 节区间（vaddr→文件偏移换算）
     * @param startVaddr 方法起始 vaddr
     * @param endVaddr 方法结束 vaddr（开区间上界；0=未知，最多扫 maxBytes）
     */
    fun build(
        soPath: String,
        textSection: StringXrefScanner.TextRange,
        startVaddr: Long,
        endVaddr: Long,
        maxBytes: Long = 64 * 1024,
    ): Result? {
        val end = if (endVaddr > startVaddr) endVaddr else startVaddr + 512
        val size = (end - startVaddr).coerceAtMost(maxBytes)
        val bodyHi = startVaddr + size // 方法体开区间上界（尾调用目标 > bodyHi 判定为体外）
        val startPaddr = textSection.fileOffset + (startVaddr - textSection.vaddrBase)
        val insns = readAndDisasm(soPath, startPaddr, startVaddr, size.toInt()) ?: return null
        val truncated = (end - startVaddr) > maxBytes

        // 收集基本块切分点：块起点 = 方法起点 ∪ 分支目标 ∪ 分支指令的下一条
        val starts = sortedSetOf<Long>()
        starts.add(startVaddr)
        var prevBranchTarget = -1L
        val addrToIns = HashMap<Long, DisasmInstruction>(insns.size)
        val insnList = ArrayList<DisasmInstruction>(insns.size)
        for (ins in insns) {
            addrToIns[ins.address] = ins
            insnList.add(ins)
            val mn = ins.mnemonic
            val isBranch = mn == "b" || mn.startsWith("b.") || mn == "cbz" || mn == "cbnz" ||
                mn == "tbz" || mn == "tbnz"
            if (isBranch) {
                val target = parseImm(ins.opStr)
                if (target != null) {
                    starts.add(target)
                    prevBranchTarget = target
                }
                // 分支指令的下一条（fallthrough）
                val next = ins.address + ins.size
                if (next < startVaddr + size) starts.add(next)
            }
            if (mn == "ret" || mn == "brk") {
                val next = ins.address + ins.size
                if (next < startVaddr + size) starts.add(next)
            }
        }

        // 切分基本块
        val blocks = ArrayList<Block>()
        val returns = ArrayList<ReturnPath>()
        val startList = starts.toList()
        for (i in startList.indices) {
            val bStart = startList[i]
            val bEnd = if (i + 1 < startList.size) startList[i + 1] - 1 else startVaddr + size - 1
            val blockInsns = ArrayList<DisasmInstruction>(16)
            for (ins in insnList) {
                if (ins.address >= bStart && ins.address <= bEnd) blockInsns.add(ins)
            }
            if (blockInsns.isEmpty()) continue
            val last = blockInsns.last()
            val mn = last.mnemonic
            val isReturn = mn == "ret" || (mn == "br" && last.opStr.trim() == "lr")
            val tailTarget = if (mn == "b") parseImm(last.opStr) else null
            // 尾调用：b 目标落在方法体区间 [startVaddr, bodyHi) 之外（公共返回 stub 等）
            val isTailCall = isTailCallTarget(tailTarget, startVaddr, bodyHi)
            val succs = ArrayList<Long>(2)
            if (mn == "b" || mn.startsWith("b.") || mn == "cbz" || mn == "cbnz" || mn == "tbz" || mn == "tbnz") {
                parseImm(last.opStr)?.let { succs.add(it) }
            }
            val nextBlockStart = if (i + 1 < startList.size) startList[i + 1] else -1L
            if (!isReturn && !isTailCall && nextBlockStart > 0) {
                succs.add(nextBlockStart)
            }
            val writer = findLastWriteW0(blockInsns)
            blocks.add(
                Block(
                    startVaddr = bStart,
                    endVaddr = bEnd,
                    size = blockInsns.size,
                    succs = succs,
                    isReturn = isReturn,
                    isTailCall = isTailCall,
                    terminator = mnemonicText(last),
                )
            )
            if (isReturn || isTailCall) {
                returns.add(
                    ReturnPath(
                        vaddr = last.address,
                        lastWriteW0 = writer?.text,
                        isTailCall = isTailCall,
                        writerVaddr = writer?.vaddr ?: 0,
                        writerHex = writer?.hex ?: "",
                    )
                )
            }
        }

        return Result(
            startVaddr = startVaddr,
            endVaddr = end,
            blocks = blocks,
            returns = returns,
            truncated = truncated,
        )
    }

    /** 块内最后一次写入 w0/x0 的指令详情。 */
    private data class W0Writer(val vaddr: Long, val text: String, val hex: String)

    /** 该块内最后一次写入 w0/x0 的指令。 */
    private fun findLastWriteW0(insns: List<DisasmInstruction>): W0Writer? {
        for (i in insns.indices.reversed()) {
            val ins = insns[i]
            if (ins.mnemonic.startsWith("mov") || ins.mnemonic.startsWith("cset") ||
                ins.mnemonic.startsWith("csinc") || ins.mnemonic.startsWith("ldrb") ||
                ins.mnemonic.startsWith("ldur") || ins.mnemonic.startsWith("ldr")
            ) {
                val dst = ins.opStr.substringBefore(',').trim()
                if (dst == "w0" || dst == "x0") {
                    return W0Writer(
                        vaddr = ins.address,
                        text = mnemonicText(ins),
                        hex = ins.bytes.joinToString(" ") { b -> b.toUByte().toString(16).uppercase().padStart(2, '0') },
                    )
                }
            }
        }
        return null
    }

    private fun mnemonicText(ins: DisasmInstruction): String = "${ins.mnemonic} ${ins.opStr}".trim()

    private fun readAndDisasm(soPath: String, startPaddr: Long, baseVaddr: Long, size: Int): List<DisasmInstruction>? {
        if (size <= 0) return null
        return try {
            RandomAccessFile(soPath, "r").use { raf ->
                raf.seek(startPaddr)
                val buf = ByteArray(size)
                val read = raf.read(buf, 0, size)
                if (read <= 0) return@use null
                val block = buf.copyOf(read)
                CapstoneBindings.disassembleWithCapstone(block, baseVaddr) ?: emptyList()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 解析立即目标（0x.. / #0x..）。 */
    fun parseImm(op: String?): Long? {
        val s = op?.trim() ?: return null
        if (s.isEmpty()) return null
        val hex = if (s.startsWith("#")) s.substring(1) else s
        val v = if (hex.length > 2 && hex[0] == '0' && (hex[1] == 'x' || hex[1] == 'X')) hex.substring(2) else hex
        return v.toLongOrNull(16)
    }

    /** 尾调用判定：b 目标落在方法体区间 [start, bodyHi) 之外（公共返回 stub / 其它方法）。 */
    fun isTailCallTarget(target: Long?, start: Long, bodyHi: Long): Boolean =
        target != null && (target < start || target >= bodyHi)
}