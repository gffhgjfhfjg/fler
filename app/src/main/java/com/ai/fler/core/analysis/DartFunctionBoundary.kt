package com.ai.fler.core.analysis

import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.DisasmInstruction
import com.ai.fler.data.dao.MethodLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile

/**
 * Dart AOT 真实函数边界估计。
 *
 * 混淆包中 Blutter 恢复的方法大量 `function_size=0`（匿名闭包尤甚），导致
 * [FunctionIndex.findContaining]/[DartCallGraphBuilder.findContaining] 只能退化为
 * 「下一方法 start」半开区间，边界粗糙、会把后续方法吞并。本工具用 Capstone 分块
 * 反汇编 .text，按函数结尾特征（ret / brk / 尾调用 b）为每个方法估计真实 end vaddr：
 * - 方法结束标志：`ret`（标准）、`brk #0`（Dart 错误路径）、`b #target` 且 target 落在
 *   当前方法起点之后（非本地跳转 = 尾调用）。
 * - 声明了 function_size>0 的方法以声明值为准（机器码结构未破坏）。
 * - 多个结束标志并存时取最早者（保守：方法体最短可信边界）。
 *
 * 供调用图 findContaining、getter_return_shape、方法级 CFG 复用。
 */
object DartFunctionBoundary {

    /** 方法 offset(vaddr) → 真实 end vaddr（开区间上界）。 */
    suspend fun estimateEnds(
        soPath: String,
        text: StringXrefScanner.TextRange,
        funcs: List<MethodLight>,
    ): Map<Long, Long> = withContext(Dispatchers.IO) {
        val starts = funcs.mapNotNull { it.functionOffset }
            .filter { it > 0 }
            .sorted()
        val startSet = starts.toHashSet()
        // size>0 方法直接用声明 end；size=0 收集待估计
        val declared = HashMap<Long, Long>()
        for (f in funcs) {
            val off = f.functionOffset ?: continue
            val size = f.functionSize ?: 0
            if (size > 0 && off > 0) declared[off] = off + size
        }
        if (starts.isEmpty() || text.fileSize <= 0) return@withContext declared

        // 方法起点 → 其在 starts 中的后继起点（估算用）
        val nextStartOf = HashMap<Long, Long>()
        for (i in starts.indices) {
            val cur = starts[i]
            val next = if (i + 1 < starts.size) starts[i + 1] else Long.MAX_VALUE
            nextStartOf[cur] = next
        }

        val ends = HashMap<Long, Long>()
        val endFile = text.fileOffset + text.fileSize
        RandomAccessFile(soPath, "r").use { raf ->
            val buf = ByteArray(256 * 1024)
            var filePos = text.fileOffset
            while (filePos < endFile) {
                val want = minOf(256L * 1024L, endFile - filePos).toInt()
                raf.seek(filePos)
                val read = raf.read(buf, 0, want)
                if (read <= 0) break
                val block = buf.copyOf(read)
                val baseVaddr = text.vaddrBase + (filePos - text.fileOffset)
                val insns = CapstoneBindings.disassembleWithCapstone(block, baseVaddr) ?: run { filePos += read; continue }
                for (ins in insns) {
                    val vaddr = ins.address
                    // 当前 vaddr 归属的方法（最后一个 start <= vaddr）
                    val owner = lastStartLe(starts, vaddr) ?: continue
                    if (owner in declared) continue // 声明 size 的方法跳过
                    // 已找到 end 的方法跳过（后续 vaddr 属于别的方法或间隙）
                    if (owner in ends) continue
                    val nextStart = nextStartOf[owner] ?: Long.MAX_VALUE
                    // 越过下一个方法起点：本方法结束标志未命中，交由 nextStart 兜底
                    if (vaddr >= nextStart) continue
                    val mnemonic = ins.mnemonic
                    val isEnd = isMethodEnd(mnemonic, ins.opStr, vaddr, nextStart)
                    if (isEnd) {
                        ends[owner] = vaddr + 4
                    }
                }
                filePos += read
            }
        }
        // 未命中结束标志的 size=0 方法：用下一方法起点兜底（与 nextStart 语义一致）
        val result = HashMap<Long, Long>(declared)
        for (off in starts) {
            if (off in result) continue
            result[off] = nextStartOf[off] ?: Long.MAX_VALUE
        }
        result
    }

    /** 最后一个 start <= vaddr 的起点；无则 null。 */
    private fun lastStartLe(starts: List<Long>, vaddr: Long): Long? {
        var lo = 0
        var hi = starts.size - 1
        var ans = -1L
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= vaddr) { ans = starts[mid]; lo = mid + 1 } else hi = mid - 1
        }
        return if (ans >= 0) ans else null
    }

    /**
     * 单条指令是否构成方法结束。
     * - `ret`：标准返回
     * - `brk`：Dart 错误路径
     * - `b #target`：若目标 > 指令地址（跳到后续方法/外部 = 尾调用）则结束；
     *   目标 <= 指令地址（循环回跳）不算结束。
     */
    fun isMethodEnd(mnemonic: String?, opStr: String?, vaddr: Long, nextStart: Long): Boolean {
        if (vaddr >= nextStart) return false // 已越过下一方法起点，交由 nextStart 兜底
        return when (mnemonic) {
            "ret", "brk" -> true
            "b" -> {
                val target = parseImm(opStr)
                target != null && target > vaddr
            }
            else -> false
        }
    }

    /** 解析 `b` 的立即目标（`0x..`/`#0x..` 均可）。 */
    fun parseImm(op: String?): Long? {
        val s = op?.trim() ?: return null
        if (s.isEmpty()) return null
        val hex = if (s.startsWith("#")) s.substring(1) else s
        return strip0x(hex).toLongOrNull(16)
    }

    private fun strip0x(s: String): String =
        if (s.length > 2 && s[0] == '0' && (s[1] == 'x' || s[1] == 'X')) s.substring(2) else s
}