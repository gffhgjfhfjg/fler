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
 * Dart AOT 的 pool 常量加载形态（已用libapp 校准）：
 * `ldr {reg}, [x27, #imm]` —— x27 是 PP 池基址寄存器，imm 即 pp 的 vm_offset
 * （与 `pp_entries.vm_offset` 一致）。混淆只剥离符号名，不改变该机器码结构。
 *
 * 与堆字段访问的区分：
 * - pool 常量：`ldr {reg}, [x27, #imm]`（base 恒为池基址寄存器）
 * - 堆字段：`add {reg}, {reg}, x28, lsl #32`（HEAP 合成地址）+ `ldur {reg}, [{reg}, #off]`
 * → 只匹配 `[poolReg, #imm]` 形态，base 非池基址（x27 等）即跳过，天然排除字段访问。
 *
 * ## 坐标
 * 指令以文件偏移（paddr）解码；site 统一换算为 vaddr 返回：
 * `vaddr = text.vaddrBase + (paddr - text.fileOffset)`，
 * 与 FunctionIndex / MethodLight.functionOffset 同坐标系。
 *
 * ## 执行
 * 分块 RandomAccessFile 流式读取（参照 RizinEngine.scanStrings 的 256KB 模式），
 * 每块用 static 版 Capstone 解码——单次 JNI 解整块，避免 Rizin pdj 逐条 JNI 往返。
 * 块间重叠 [OVERLAP] 字节覆盖边界被截断的半条指令，site 按 vaddr 去重。
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
    )

    /** 进度回调。 */
    fun interface Sink {
        fun report(fraction: Float, message: String)
    }

    /**
     * 扫描 so 的 .text，返回引用了目标 pool 槽的命中。
     *
     * @param soPath so 文件绝对路径
     * @param text .text 区间（文件偏移 + vaddr 基线）
     * @param analysisId 分析 id（构建函数索引做方法归属；不传则只返回 site/pp）
     * @param targetPpOffsets 目标 pp 槽偏移集合（pp_entries.vm_offset）
     * @param poolRegs 池基址寄存器候选（默认 x27，calibrate_pool_sig 可校准）
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
                while (blockStart < endFile) {
                    val want = minOf(CHUNK, endFile - blockStart).toInt()
                    val read = raf.read(buf, 0, want)
                    if (read <= 0) break
                    val block = buf.copyOf(read)
                    // baseAddress 传块起点 paddr，Capstone 生成的 address 即 paddr
                    val insns = CapstoneBindings.disassembleWithCapstone(block, blockStart)
                        ?: return@use
                    processBlock(
                        insns = insns,
                        text = text,
                        index = index,
                        targetPpOffsets = targetPpOffsets,
                        poolRegs = poolRegs,
                        out = hitSet,
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

    private fun processBlock(
        insns: List<DisasmInstruction>,
        text: TextRange,
        index: FunctionIndex.Snapshot?,
        targetPpOffsets: Set<Long>,
        poolRegs: List<String>,
        out: LinkedHashMap<Long, Hit>,
    ) {
        for (ins in insns) {
            if (ins.mnemonic != "ldr") continue
            val op = ins.opStr
            val lb = op.indexOf('[')
            if (lb < 0) continue
            val rb = op.indexOf(']', lb + 1)
            if (rb <= lb + 1) continue
            val mem = op.substring(lb + 1, rb)              // "x27, #0x57b0"
            val comma = mem.indexOf(',')
            if (comma <= 0) continue
            val base = mem.substring(0, comma).trim()
            if (base !in poolRegs) continue
            val immPart = mem.substring(comma + 1).trim()   // "#0x57b0"
            if (!immPart.startsWith('#')) continue
            val immStr = immPart.substring(1).trim().removePrefix("0x")
            val imm = immStr.toLongOrNull(16) ?: continue
            if (imm !in targetPpOffsets) continue

            val siteVaddr = text.vaddrBase + (ins.address - text.fileOffset)
            if (out.containsKey(siteVaddr)) continue

            var methodId = 0L
            var className = ""
            var methodName = ""
            val m: MethodLight? = index?.findContaining(siteVaddr)
            if (m != null) {
                methodId = m.id
                className = m._className
                methodName = m.methodName
            }
            out[siteVaddr] = Hit(
                siteVaddr = siteVaddr,
                ppOffset = imm,
                methodId = methodId,
                className = className,
                methodName = methodName,
            )
        }
    }

    companion object {
        /** 默认池基址寄存器（校准实证：x27=PP）。 */
        val DEFAULT_POOL_REGS: List<String> = listOf("x27")

        /** .text 分块大小（同 RizinEngine.scanStrings 的 256KB 流式）。 */
        private const val CHUNK = 256 * 1024L
    }
}