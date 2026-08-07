package com.ai.fler.core.mcp

import com.ai.fler.core.jni.ElfParserBindings
import com.ai.fler.core.jni.ElfSection
import com.ai.fler.core.log.AppLogger
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 地址坐标轴解析器。
 *
 * MCP 层地址语义长期混乱的根源：Blutter 分析给出的是 **vaddr**（如 get_method 的
 * functionOffset），而文件读写/补丁类工具按 **文件偏移** 寻址。对 libapp.so 这类
 * vaddr==文件偏移 的文件两者碰巧相等，掩盖问题；对 libflutter.so 这类 rw 段带
 * 0x10000 偏差的文件，混用坐标轴会读到/写到错误位置。
 *
 * 本类统一坐标换算：soPath + address → 双坐标 + 输入轴判定 + 偏差 + 歧义标记。
 * 节表按 soPath 缓存，多次调用零额外 ELF 解析开销。
 */
enum class AddressAxis { FILE_OFFSET, VADDR, AMBIGUOUS, NONE }

data class AxisResolution(
    val inputAxis: AddressAxis,
    val fileOffset: Long,
    val vaddr: Long,
    val section: String,
    val bias: Long,
    val ambiguous: Boolean,
    /** 歧义时：把输入按另一坐标轴解释得到的结果。 */
    val altFileOffset: Long? = null,
    val altVaddr: Long? = null,
)

@Singleton
class AddressAxisResolver @Inject constructor(
    private val appLogger: AppLogger
) {

    private val sectionsCache = ConcurrentHashMap<String, List<ElfSection>>()

    /**
     * 解析地址双坐标。
     *
     * - 输入只落在某节的文件偏移范围 → 视为文件偏移（FILE_OFFSET）
     * - 输入只落在某节的 vaddr 范围 → 视为 vaddr（VADDR）
     * - 同时命中两个不同节（如 libflutter：输入既是 A 段 vaddr 起点又是 B 段
     *   文件偏移起点）→ AMBIGUOUS，返回主解释 + [altFileOffset]/[altVaddr]
     * - 无节命中 → NONE，返回 null（此时两轴相等，无需换算）
     */
    fun resolve(soPath: String, address: Long): AxisResolution? {
        val sections = sectionsOf(soPath)
        if (sections.isEmpty()) {
            appLogger.warn(TAG, "节表为空，无法换算坐标 so=$soPath")
            return null
        }
        val inFile = sections.firstOrNull { address >= it.offset && address < it.offset + it.size }
        val inVaddr = sections.firstOrNull { address >= it.address && address < it.address + it.size }

        // 同节双命中：仅当该节偏差为 0（vaddr==文件偏移）时两种解读才等价，可放行；
        // 偏差非 0（如 libflutter rw 段 +0x10000）时同一数字同时是文件偏移又是 vaddr，
        // 两解读指向不同位置 → 必须标为歧义，交给调用方按上下文取舍。
        val sameZeroBias = inFile != null && inVaddr != null &&
            inFile.offset == inVaddr.offset && inFile.address == inVaddr.address &&
            (inFile.address - inFile.offset) == 0L
        val ambiguous = inFile != null && inVaddr != null && !sameZeroBias

        val fileAxisVaddr = inFile?.let { it.address + (address - it.offset) }
        val vaddrAxisFile = inVaddr?.let { it.offset + (address - it.address) }

        val axis = when {
            ambiguous -> AddressAxis.AMBIGUOUS
            inFile != null -> AddressAxis.FILE_OFFSET
            inVaddr != null -> AddressAxis.VADDR
            else -> AddressAxis.NONE
        }
        if (axis == AddressAxis.NONE) {
            appLogger.debug(TAG, "地址不落在任何节，按原值两轴相等处理 so=$soPath addr=0x${address.toString(16)}")
            return null
        }

        val section = inFile ?: inVaddr!!
        val bias = section.address - section.offset
        val res = AxisResolution(
            inputAxis = axis,
            fileOffset = if (inFile != null) address else vaddrAxisFile ?: address,
            vaddr = if (inVaddr != null) address else fileAxisVaddr ?: address,
            section = section.name,
            bias = bias,
            ambiguous = ambiguous,
            altFileOffset = if (ambiguous) vaddrAxisFile else null,
            altVaddr = if (ambiguous) fileAxisVaddr else null,
        )
        appLogger.info(TAG, "坐标解析 so=$soPath addr=0x${address.toString(16)} " +
            "-> axis=$axis fileOffset=0x${res.fileOffset.toString(16)} vaddr=0x${res.vaddr.toString(16)} " +
            "section=${res.section} bias=0x${bias.toString(16)} ambiguous=$ambiguous")
        return res
    }

    /** 各节 vaddr 相对文件偏移的偏差摘要（供 engine.open 日志）。 */
    fun biasSummary(soPath: String): String {
        val sections = sectionsOf(soPath)
        if (sections.isEmpty()) return "无节表"
        val byBias = sections.groupBy { it.address - it.offset }
            .mapValues { (_, list) -> list.size }
        return byBias.entries.joinToString(";") { (b, n) ->
            "0x${b.toString(16)}(${n}节)"
        }
    }

    private fun sectionsOf(soPath: String): List<ElfSection> =
        sectionsCache.getOrPut(soPath) {
            ElfParserBindings().use { parser ->
                if (!parser.open(soPath)) emptyList()
                else parser.getSections().filter { it.offset > 0 && it.size > 0 && it.address > 0 }
            }
        }

    companion object {
        private const val TAG = "AddressAxisResolver"
    }
}
