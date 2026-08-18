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
 * 间接调用点（blr/br）扫描与形态标注。
 *
 * Dart AOT 中闭包/虚函数调用通过 `blr lr`/`br xN` 间接跳转，目标通常无法静态解析
 * （函数指针来自对象字段或 isolate 线程槽）。本工具不猜测目标，而是识别 site 的
 * 来源形态并标注，帮助分析者快速区分：
 * - `ldr {r}, [poolReg, #imm]` + `blr/br {r}`：池槽加载调用 → 给出 pp 槽偏移与描述
 *   （若槽指向函数/stub，后续可查 pool 引用确认目标）
 * - `ldr {r}, [x26, #imm]` + `blr/br {r}`：isolate 线程槽调用（闭包调度主形态）→ 标注 isolate
 * - `ldr {r}, [xN, #imm]`（对象字段）+ `blr/br {r}`：虚调用 → 标注 dynamic（字段）
 * - 其它：动态寄存器来源 → 标注 dynamic
 *
 * 用途：方法里有多少间接调用、长什么样，避免对调用图「零边」的误判。
 */
@Singleton
class IndirectCallScanner @Inject constructor(
    private val ppEntryDao: PpEntryDao,
) {

    /** 一个间接调用 site。 */
    data class Site(
        val vaddr: Long,        // blr/br 指令地址
        val kind: String,       // isolate / pool / field / dynamic
        val reg: String,        // 目标寄存器（lr/x9/...）
        val poolImm: Long = -1L, // kind=pool 时的池槽立即数（相对 poolReg）
        val poolReg: String = "", // kind=pool 时的池基址寄存器
        val source: String = "", // 来源指令摘要（如 "ldr x30, [x26, 0x630]"）
        val poolDescription: String = "", // 槽对应的 pp 条目描述（可读）
    )

    /**
     * 扫描 [startVaddr, endVaddr) 区间内所有 blr/br 间接调用。
     *
     * @param soPath libapp.so 绝对路径
     * @param textSection .text 节区间
     * @param analysisId 用于查池槽描述
     * @param poolRegs 池基址寄存器（默认 x27,x26）
     */
    suspend fun scanSites(
        analysisId: Long,
        soPath: String,
        textSection: StringXrefScanner.TextRange,
        startVaddr: Long,
        endVaddr: Long,
        poolRegs: List<String> = StringXrefScanner.DEFAULT_POOL_REGS,
    ): List<Site> {
        val startPaddr = textSection.fileOffset + (startVaddr - textSection.vaddrBase)
        val end = if (endVaddr > startVaddr) endVaddr else startVaddr + 512
        val size = (end - startVaddr).coerceAtMost(64 * 1024)
        val insns = withContext(Dispatchers.IO) {
            if (size <= 0) emptyList()
            else try {
                RandomAccessFile(soPath, "r").use { raf ->
                    raf.seek(startPaddr)
                    val buf = ByteArray(size.toInt())
                    val read = raf.read(buf, 0, size.toInt())
                    if (read <= 0) emptyList()
                    else CapstoneBindings.disassembleWithCapstone(buf.copyOf(read), startVaddr) ?: emptyList()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
        if (insns.isEmpty()) return emptyList()

        // 每条指令的「前置近处指令」窗口（回看最多 8 条找来源 ldr）
        val sites = ArrayList<Site>()
        for (i in insns.indices) {
            val ins = insns[i]
            val isIndirect = ins.mnemonic == "blr" || ins.mnemonic == "br"
            if (!isIndirect) continue
            val reg = ins.opStr.trim()
            val lookback = if (i >= 8) insns.subList(i - 8, i) else insns.subList(0, i)
            val src = findSourceLdr(lookback, reg, poolRegs)
            sites.add(
                Site(
                    vaddr = ins.address,
                    kind = src?.kind ?: "dynamic",
                    reg = reg,
                    poolImm = src?.poolImm ?: -1L,
                    poolReg = src?.poolReg ?: "",
                    source = src?.source ?: "",
                    poolDescription = if (src?.kind == "pool") ppDescription(analysisId, src.poolReg, src.poolImm) else "",
                )
            )
        }
        return sites
    }

    /** 回看窗口找最近一条加载 [reg] 的 ldr，判定来源形态。 */
    private fun findSourceLdr(
        lookback: List<DisasmInstruction>,
        reg: String,
        poolRegs: List<String>,
    ): SourceLdr? {
        for (ins in lookback.asReversed()) {
            val mn = ins.mnemonic
            if (mn != "ldr" && mn != "ldur") continue
            val op = ins.opStr.trim()
            val dst = op.substringBefore(',').trim()
            if (dst != reg) continue
            val mem = bracketMem(op) ?: continue
            val comma = mem.indexOf(',')
            if (comma <= 0) return SourceLdr("dynamic", source = "${mn} ${op}", poolImm = -1, poolReg = "")
            val base = mem.substring(0, comma).trim()
            val imm = parseImmediate(mem.substring(comma + 1).trim()) ?: return null
            val kind = when {
                base in poolRegs -> "pool"
                base == "x26" -> "isolate"
                else -> "field"
            }
            return SourceLdr(
                kind = kind,
                source = "${mn} ${op}",
                poolImm = imm,
                poolReg = base,
            )
        }
        return null
    }

    private data class SourceLdr(
        val kind: String,
        val source: String,
        val poolImm: Long,
        val poolReg: String,
    )

    private suspend fun ppDescription(analysisId: Long, poolReg: String, imm: Long): String {
        // poolReg=x27 时 imm 即 pp 槽偏移；x26 为线程槽不查
        if (poolReg != "x27") return ""
        return try {
            ppEntryDao.getPpByVmOffset(analysisId, imm).firstOrNull()?.description?.take(60).orEmpty()
        } catch (e: Exception) {
            ""
        }
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