package com.ai.fler.core.analysis

import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.DisasmInstruction
import com.ai.fler.data.dao.PpEntryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 单方法字符串标签提取（反混淆可读性增强）。
 *
 * 对指定 Dart 方法 body 区间 [start, endVaddr) 做真实 Capstone 机器码扫描，
 * 收集该方法引用的所有 pool 字符串槽（`ldr [poolReg,#imm]` 单指令 / `add+ldr`
 * 双指令），再映射到字符串内容作为业务标签。
 *
 * 用途：匿名方法（sub_<vaddr>）名称无语义时，靠其引用的字符串快速判断业务归属，
 * 如 `sub_12d89f8 [字符串标签: "积分和VIP天数抽奖", "VIP"]`。
 */
@Singleton
class MethodStringLabels @Inject constructor(
    private val ppEntryDao: PpEntryDao,
) {

    /** 一次方法扫描的标签记录。 */
    data class Label(
        val ppOffset: Long,
        val text: String,
    )

    /**
     * 扫描 [startVaddr, endVaddr) 区间内的 pool 字符串槽。
     *
     * @param soPath libapp.so 绝对路径
     * @param textSection .text 节区间（用于 vaddr→文件偏移换算）
     * @param startVaddr 方法起始 vaddr
     * @param endVaddr 方法结束 vaddr（开区间上界；0=未知，仅扫一页）
     * @param maxLabels 返回标签数上限
     */
    suspend fun scanLabels(
        analysisId: Long,
        soPath: String,
        textSection: StringXrefScanner.TextRange,
        startVaddr: Long,
        endVaddr: Long,
        maxLabels: Int = 8,
    ): List<Label> {
        val startPaddr = textSection.fileOffset + (startVaddr - textSection.vaddrBase)
        val end = if (endVaddr > startVaddr) endVaddr else startVaddr + 512
        val endPaddr = textSection.fileOffset + (end - textSection.vaddrBase)
        if (endPaddr <= startPaddr || endPaddr - startPaddr > 64 * 1024) {
            // 超长区间（方法体异常大）截断防失控
        }
        val size = (endPaddr - startPaddr).coerceAtMost(32 * 1024)
        val ppOffsets = withContext(Dispatchers.IO) {
            val slots = HashSet<Long>()
            RandomAccessFile(soPath, "r").use { raf ->
                raf.seek(startPaddr)
                val buf = ByteArray(size.toInt())
                val read = raf.read(buf, 0, size.toInt())
                if (read <= 0) return@use emptyList<Label>()
                val block = buf.copyOf(read)
                val baseVaddr = textSection.vaddrBase + (startPaddr - textSection.fileOffset)
                val insns = CapstoneBindings.disassembleWithCapstone(block, baseVaddr) ?: return@use emptyList<Label>()
                var pending: Pair<String, Long>? = null
                for (ins in insns) {
                    when (ins.mnemonic) {
                        "add" -> {
                            parsePoolAdd(ins, poolRegs)?.let { pending = it }
                        }
                        "ldr" -> {
                            val pairLo = pending?.let { parsePairLdr(ins, it) }
                            if (pairLo != null) {
                                val pp = (pending!!.second shl 12) + pairLo
                                slots.add(pp)
                                pending = null
                            } else {
                                val imm = parseDirectLdr(ins) ?: run { pending = null; continue }
                                pending = null
                                slots.add(imm)
                            }
                        }
                        else -> pending = null
                    }
                }
            }
            slots
        }
        if (ppOffsets.isEmpty()) return emptyList()
        return lookupLabels(analysisId, ppOffsets, maxLabels)
    }

    /** 槽偏移 → 字符串内容标签（查 pp_entries 的 description，非 String 槽跳过）。 */
    private suspend fun lookupLabels(analysisId: Long, ppOffsets: Set<Long>, maxLabels: Int): List<Label> {
        if (ppOffsets.isEmpty()) return emptyList()
        val entries = ppEntryDao.getPpByVmOffsets(analysisId, ppOffsets.toList())
        val labels = ArrayList<Label>(entries.size)
        for (e in entries) {
            if (labels.size >= maxLabels) break
            val desc = e.description?.trim().orEmpty()
            if (desc.isEmpty() || !looksLikeString(desc)) continue
            labels.add(Label(e.vmOffset, desc.take(80)))
        }
        return labels
    }

    /** 粗判 description 是否像字符串内容（排除数字/空/单字符噪声）。 */
    private fun looksLikeString(s: String): Boolean {
        if (s.length < 2) return false
        val hasAlpha = s.any { it.isLetter() }
        val hasCjk = s.any { it.code in 0x4E00..0x9FFF }
        return hasAlpha || hasCjk
    }

    companion object {
        private val poolRegs = StringXrefScanner.DEFAULT_POOL_REGS

        /** 解析 `add {dst}, {poolReg}, #imm, lsl #12` 双指令前置。 */
        private fun parsePoolAdd(ins: DisasmInstruction, poolRegs: List<String>): Pair<String, Long>? {
            val op = ins.opStr
            val dst = op.substringBefore(',').trim()
            if (!dst.startsWith('x')) return null
            val parts = op.substringAfter(',').split(',')
            if (parts.size < 2) return null
            val src = parts[0].trim()
            if (src !in poolRegs) return null
            val imm = parseImmediate(parts[1].trim()) ?: return null
            val shiftPart = parts.getOrNull(2)?.trim() ?: return null
            if (!shiftPart.contains("lsl")) return null
            val shiftVal = parseImmediate(shiftPart.substringAfter("lsl").trim())
            if (shiftVal != 12L) return null
            return dst to imm
        }

        /** 双指令 ldr：`ldr {t}, [{t}, #lo]`，基址=pending 目标寄存器。 */
        private fun parsePairLdr(ins: DisasmInstruction, pending: Pair<String, Long>): Long? {
            if (ins.mnemonic != "ldr") return null
            val mem = bracketMem(ins.opStr) ?: return null
            val comma = mem.indexOf(',')
            if (comma <= 0) return null
            val base = mem.substring(0, comma).trim()
            if (base != pending.first) return null
            return parseImmediate(mem.substring(comma + 1).trim())
        }

        /** 单指令 ldr：`ldr {reg}, [poolReg, #imm]`，imm 即 pp 槽（≤4095）。 */
        private fun parseDirectLdr(ins: DisasmInstruction): Long? {
            val mem = bracketMem(ins.opStr) ?: return null
            val comma = mem.indexOf(',')
            if (comma <= 0) return null
            val base = mem.substring(0, comma).trim()
            if (base !in poolRegs) return null
            return parseImmediate(mem.substring(comma + 1).trim())
        }

        private fun bracketMem(opStr: String): String? {
            val lb = opStr.indexOf('[')
            if (lb < 0) return null
            val rb = opStr.indexOf(']', lb + 1)
            if (rb <= lb + 1) return null
            return opStr.substring(lb + 1, rb)
        }

        private fun parseImmediate(s: String): Long? {
            var v = s.trim()
            if (v.startsWith('#')) v = v.substring(1).trim()
            if (v.isEmpty()) return null
            return if (v.startsWith("0x")) v.removePrefix("0x").toLongOrNull(16)
            else v.toLongOrNull()
        }
    }
}