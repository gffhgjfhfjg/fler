package com.ai.fler.core.analysis

import com.ai.fler.core.jni.CapstoneBindings
import com.ai.fler.core.jni.DisasmInstruction
import com.ai.fler.data.dao.MethodLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 反混淆核心扫描器：在 so 的 .text 机器码中定位「引用了指定 Dart 对象池（PP）
 * 槽」的指令，并把命中地址归属到具体 Dart 方法。
 *
 * ## 原理
 * Dart AOT 的 pool 常量加载形态（已用 libapp 校准）：
 * 1. 小槽（imm ≤ 0xFFF）：`ldr {reg}, [x27, #imm]` —— x27 是 PP 池基址寄存器，
 *    imm 即 pp 的 vm_offset（与 `pp_entries.vm_offset` 一致）。
 * 2. 大槽（imm > 0xFFF，混淆包高频出现）：两指令序列
 *    `add {t}, {poolReg}, #hi, lsl #12` + `ldr {t}, [{t}, #lo]`，
 *    其中 pp = (hi << 12) + lo，{t} 为任意临时寄存器（可能复用 poolReg 或非池基址寄存器）。
 *    单条 `ldr [{poolReg}, #imm]` 的立即数只有 12 位（≤4095），故大槽必须走双指令。
 * 混淆只剥离符号名，不改变该机器码结构。
 *
 * 与堆字段访问的区分：
 * - pool 常量：`ldr {reg}, [x27, #imm]`（base 恒为池基址寄存器）
 * - 堆字段：`add {reg}, {reg}, x28, lsl #32`（HEAP 合成地址）+ `ldur {reg}, [{reg}, #off]`
 * → 只匹配 `[poolReg, #imm]` 与「add(池基址,lsl12)+ldr」两种形态，base 非池基址即跳过。
 *
 * ## 坐标
 * 指令以文件偏移（paddr）解码；site 统一换算为 vaddr 返回：
 * `vaddr = text.vaddrBase + (paddr - text.fileOffset)`，
 * 与 FunctionIndex / MethodLight.functionOffset 同坐标系。
 *
 * ## 执行
 * 分块 RandomAccessFile 流式读取（参照 RizinEngine.scanStrings 的 256KB 模式），
 * 每块用 static 版 Capstone 解码——单次 JNI 解整块，避免 Rizin pdj 逐条 JNI 往返。
 * 双指令序列可能跨块切断：块间保留上一条「add 池基址 lsl12」的解析状态（pendingAdd），
 * 下一块首条 ldr 若能与之配对则视为同序列。site 按 vaddr 去重。
 */
@Singleton
class StringXrefScanner @Inject constructor(
    private val functionIndex: FunctionIndex,
) {

    /** .text 节区间（文件坐标）与 vaddr 基线。 */
    data class TextRange(
        val fileOffset: Long,   // .text 节在文件中的偏移（paddr 起点）
        val fileSize: Long,     // .text 节大小
        val vaddrBase: Long,    // .text 节 vaddr 起点（= ELF section address）
    )

    /** 一次扫描的命中记录。 */
    data class Hit(
        val siteVaddr: Long,    // 引用指令的 vaddr（补丁坐标，可转文件偏移）
        val ppOffset: Long,     // 命中的 pool 槽偏移（pp_entries.vm_offset）
        val methodId: Long,     // 归属方法 id（FunctionIndex 解析；未命中 0）
        val className: String,  // 归属类名（Blutter 恢复，混淆时可能为 <unknown>）
        val methodName: String, // 归属方法名（混淆时常为空/<anonymous closure>）
        val methodVaddr: Long = 0, // 归属方法 functionOffset（供 sub_<vaddr> 显示名）
    )

    /** 进度回调。 */
    fun interface Sink {
        fun report(fraction: Float, message: String)
    }

    /** 已解析的「add 池基址 + lsl12」前置指令，等待后续 ldr 配对。 */
    internal data class PendingAdd(
        val targetReg: String,  // add 的目标寄存器 xN（ldr 的基址应与之相同）
        val hiImm: Long,        // add 立即数 hi（lsl 12 后为 pp 高 12 位）
        val paddr: Long,        // add 指令的文件偏移（作 site 坐标）
    )

    /**
     * 扫描 so 的 .text，返回引用了目标 pool 槽的命中。
     *
     * @param soPath so 文件绝对路径
     * @param text .text 区间（文件偏移 + vaddr 基线）
     * @param analysisId 分析 id（构建函数索引做方法归属；不传则只返回 site/pp）
     * @param targetPpOffsets 目标 pp 槽偏移集合（pp_entries.vm_offset）
     * @param poolRegs 池基址寄存器候选（默认 x27；也支持校准出的 x26；双指令的 hi 基址寄存器也按此集匹配）
     * @param sink 进度回调（可空）
     * @return 命中列表（按 site vaddr 升序，去重）
     */
    suspend fun scan(
        soPath: String,
        text: TextRange,
        analysisId: Long,
        targetPpOffsets: Set<Long>,
        poolRegs: List<String> = DEFAULT_POOL_REGS,
        sink: Sink? = null,
    ): List<Hit> {
        if (targetPpOffsets.isEmpty() || text.fileSize <= 0) return emptyList()
        val index = functionIndex.build(analysisId)
        val hitSet = LinkedHashMap<Long, Hit>()
        val endFile = text.fileOffset + text.fileSize

        withContext(Dispatchers.IO) {
            RandomAccessFile(soPath, "r").use { raf ->
                raf.seek(text.fileOffset)
                var blockStart = text.fileOffset
                val buf = ByteArray(CHUNK.toInt())
                var blockIdx = 0
                // 跨块保留的 add 前置（覆盖块间被切断的双指令序列）
                var pendingAdd: PendingAdd? = null
                while (blockStart < endFile) {
                    val want = minOf(CHUNK, endFile - blockStart).toInt()
                    val read = raf.read(buf, 0, want)
                    if (read <= 0) break
                    val block = buf.copyOf(read)
                    // baseAddress 传块起点 paddr，Capstone 生成的 address 即 paddr
                    val insns = CapstoneBindings.disassembleWithCapstone(block, blockStart)
                        ?: return@use
                    pendingAdd = processBlock(
                        insns = insns,
                        text = text,
                        index = index,
                        targetPpOffsets = targetPpOffsets,
                        poolRegs = poolRegs,
                        out = hitSet,
                        pendingAdd = pendingAdd,
                    )
                    blockStart += read
                    blockIdx++
                    sink?.report(
                        ((blockStart - text.fileOffset).toFloat() / text.fileSize).coerceIn(0f, 1f),
                        "扫描第 $blockIdx 块 @文件偏移 0x${blockStart.toString(16)}"
                    )
                }
            }
        }
        return hitSet.values.toList()
    }

    /**
     * 处理一块反汇编指令。返回块末尾仍待配对的前置 add（供下一块 ldr 配对），
     * 无则 null。
     */
    private fun processBlock(
        insns: List<DisasmInstruction>,
        text: TextRange,
        index: FunctionIndex.Snapshot?,
        targetPpOffsets: Set<Long>,
        poolRegs: List<String>,
        out: LinkedHashMap<Long, Hit>,
        pendingAdd: PendingAdd?,
    ): PendingAdd? {
        var pending = pendingAdd
        for (ins in insns) {
            // 先消费上一条 add（若有）：当前 ldr 若与之配对则命中，配对后清空
            val pairHit = pending?.let { parsePairLdr(ins, it) }
            if (pairHit != null) {
                val pp = (pending!!.hiImm shl 12) + pairHit.lo
                if (pp in targetPpOffsets) {
                    addHit(
                        out = out,
                        sitePaddr = pending!!.paddr,
                        ppOffset = pp,
                        text = text,
                        index = index,
                    )
                }
                pending = null
            }

            when (ins.mnemonic) {
                "add" -> {
                    // 记录「add {dst}, {poolReg}, #imm, lsl #12」：可能的双指令大槽前置
                    val a = parsePoolAdd(ins, poolRegs) ?: continue
                    pending = a
                }
                "ldr" -> {
                    // 单指令形态：ldr {reg}, [poolReg, #imm]，imm 即 pp 槽（≤4095）
                    val s = parseDirectLdr(ins, poolRegs) ?: run { pending = null; continue }
                    // 走到这里说明当前 ldr 与 pending 未配对（pairHit 已在上方尝试过），
                    // 编译器对 pool 大槽的 add+ldr 必严格相邻，故作废残留 pending，只按单指令处理。
                    pending = null
                    if (s.imm in targetPpOffsets) {
                        addHit(
                            out = out,
                            sitePaddr = ins.address,
                            ppOffset = s.imm,
                            text = text,
                            index = index,
                        )
                    }
                }
                else -> {
                    // 其它指令打断 add→ldr 的相邻性，作废 pending
                    pending = null
                }
            }
        }
        return pending
    }

    /** 解析单指令 `ldr {reg}, [poolReg, #imm]`，返回 poolReg ∈ poolRegs 的立即数。 */
    private fun parseDirectLdr(ins: DisasmInstruction, poolRegs: List<String>): PoolLdr? {
        val mem = bracketMem(ins.opStr) ?: return null
        val comma = mem.indexOf(',')
        if (comma <= 0) return null
        val base = mem.substring(0, comma).trim()
        if (base !in poolRegs) return null
        val imm = parseImmediate(mem.substring(comma + 1).trim()) ?: return null
        return PoolLdr(imm)
    }

    /**
     * 解析 `add {dst}, {poolReg}, #imm, lsl #12` 双指令前置。
     * Capstone 输出形如 `add x5, x27, #0x76, lsl #12`。
     * 仅当源操作数 ∈ poolRegs 且 shift 为 lsl #12 时记录。
     */
    private fun parsePoolAdd(ins: DisasmInstruction, poolRegs: List<String>): PendingAdd? {
        val op = ins.opStr
        val dst = op.substringBefore(',').trim()
        if (!dst.startsWith('x')) return null
        val rest = op.substringAfter(',').trim()
        val parts = rest.split(',')
        if (parts.size < 2) return null
        val src = parts[0].trim()
        if (src !in poolRegs) return null
        val imm = parseImmediate(parts[1].trim()) ?: return null
        // shift 后缀（"lsl #12" / "lsl 12"），必须为 12
        val shiftPart = parts.getOrNull(2)?.trim() ?: return null
        if (!shiftPart.contains("lsl")) return null
        val shiftVal = parseImmediate(shiftPart.substringAfter("lsl").trim())
        if (shiftVal != 12L) return null
        return PendingAdd(targetReg = dst, hiImm = imm, paddr = ins.address)
    }

    /**
     * 尝试把 [ins] 作为双指令的 ldr 与 [pending] 配对：
     * `ldr {t}, [{t}, #lo]`，其中基址寄存器 = pending.targetReg。
     * 返回 lo（字节偏移）；不匹配返回 null。
     */
    private fun parsePairLdr(ins: DisasmInstruction, pending: PendingAdd): PairLdr? {
        if (ins.mnemonic != "ldr") return null
        val mem = bracketMem(ins.opStr) ?: return null
        val comma = mem.indexOf(',')
        if (comma <= 0) return null
        val base = mem.substring(0, comma).trim()
        if (base != pending.targetReg) return null
        val lo = parseImmediate(mem.substring(comma + 1).trim()) ?: return null
        return PairLdr(lo)
    }

    /** 提取 `[....]` 内的内容。 */
    private fun bracketMem(opStr: String): String? {
        val lb = opStr.indexOf('[')
        if (lb < 0) return null
        val rb = opStr.indexOf(']', lb + 1)
        if (rb <= lb + 1) return null
        return opStr.substring(lb + 1, rb)
    }

    /** 解析立即数字面量（支持 `#0x..`、`#123`、`0x..`、`123`、`#..lsl` 前的数值）。 */
    private fun parseImmediate(s: String): Long? {
        var v = s.trim()
        if (v.startsWith('#')) v = v.substring(1).trim()
        v = v.trim()
        if (v.isEmpty()) return null
        return if (v.startsWith("0x")) v.removePrefix("0x").toLongOrNull(16)
        else v.toLongOrNull()
    }

    private fun addHit(
        out: LinkedHashMap<Long, Hit>,
        sitePaddr: Long,
        ppOffset: Long,
        text: TextRange,
        index: FunctionIndex.Snapshot?,
    ) {
        val siteVaddr = text.vaddrBase + (sitePaddr - text.fileOffset)
        if (out.containsKey(siteVaddr)) return
        var methodId = 0L
        var className = ""
        var methodName = ""
        var methodVaddr = 0L
        val m: MethodLight? = index?.findContaining(siteVaddr)
        if (m != null) {
            methodId = m.id
            className = m._className
            methodName = m.methodName
            methodVaddr = m.functionOffset ?: 0L
        }
        out[siteVaddr] = Hit(
            siteVaddr = siteVaddr,
            ppOffset = ppOffset,
            methodId = methodId,
            className = className,
            methodName = methodName,
            methodVaddr = methodVaddr,
        )
    }

    /** 单指令 ldr 的立即数。 */
    internal data class PoolLdr(val imm: Long)

    /** 双指令 ldr 的 lo 立即数。 */
    internal data class PairLdr(val lo: Long)

    companion object {
        /** 默认池基址寄存器（校准实证：x27=PP；混淆包常同时出现 x26）。 */
        val DEFAULT_POOL_REGS: List<String> = listOf("x27", "x26")

        /** .text 分块大小（同 RizinEngine.scanStrings 的 256KB 流式）。 */
        private const val CHUNK = 256 * 1024L
    }
}