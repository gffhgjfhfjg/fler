package com.ai.fler.core.analysis

import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.MethodLight
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 反混淆定位用的函数地址索引。
 *
 * 汇总 Blutter 方法表（保留 **methodId/classId**，用于把扫描命中的 site 归属到
 * 具体方法），按 vaddr 排序，支持：
 * - [findContaining]：给定 vaddr → 归属方法（兼容混淆方法 `function_size=0`）
 * - [methodsOf]：classId → 该类全部方法
 *
 * 与 [DartCallGraphBuilder] 内部二分的关键差异：混淆方法 `function_size=0`，
 * 若按 `offset + size` 校验上界会全部失配——因此以「下一方法的 start」为界，
 * 区间 [start, nextStart) 归当前方法。函数表按 offset 有序即可。
 *
 * 线程安全：构建后不可变，可多协程并发只读。
 */
@Singleton
class FunctionIndex @Inject constructor(
    private val dartMethodDao: DartMethodDao,
) {

    /** 为某次分析构建不可变快照（方法索引）。 */
    suspend fun build(analysisId: Long): Snapshot {
        val rows = dartMethodDao.getByAnalysisIdLight(analysisId)
            .filter { it.functionOffset != null && it.functionOffset!! > 0 }
            .sortedBy { it.functionOffset }
        return Snapshot(rows)
    }

    /** 函数索引快照（构建后不可变）。 */
    class Snapshot(private val rows: List<MethodLight>) {

        val size: Int get() = rows.size

        val all: List<MethodLight> get() = rows

        /**
         * 二分返回包含 vaddr 的方法。规则：
         * - 找最后一个 `start <= vaddr` 的方法 idx；
         * - 若 size>0 且 vaddr 超出其 [start, start+size) → 不属于任何方法，返回 null；
         * - 若 size=0/负 → 视为「占位」，归入以 [start, nextStart) 为区间，
         *   只要 vaddr < nextStart 即归属（nextStart 为下一方法 offset 或 +∞）。
         * 找不到返回 null。
         */
        fun findContaining(vaddr: Long): MethodLight? {
            if (rows.isEmpty() || vaddr < (rows.first().functionOffset ?: 0)) return null
            val idx = lastStartLe(vaddr)
            if (idx < 0) return null
            val f = rows[idx]
            val start = f.functionOffset ?: return null
            val size = f.functionSize ?: 0
            if (size > 0 && vaddr >= start + size) return null
            val nextStart = if (idx + 1 < rows.size) (rows[idx + 1].functionOffset ?: start) else Long.MAX_VALUE
            if (vaddr >= nextStart) return null
            return f
        }

        /** 某类的全部方法（按地址升序）。 */
        fun methodsOf(classId: Long): List<MethodLight> {
            val out = ArrayList<MethodLight>(16)
            for (f in rows) if (f.classId == classId) out.add(f)
            return out
        }

        /** 最后一个 start <= vaddr 的索引；无则 -1。 */
        private fun lastStartLe(vaddr: Long): Int {
            var lo = 0
            var hi = rows.size - 1
            var ans = -1
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if ((rows[mid].functionOffset ?: 0L) <= vaddr) { ans = mid; lo = mid + 1 } else hi = mid - 1
            }
            return ans
        }
    }
}