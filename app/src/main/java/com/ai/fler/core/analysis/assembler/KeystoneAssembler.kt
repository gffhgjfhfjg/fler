package com.ai.fler.core.analysis.assembler

import com.ai.fler.core.jni.KeystoneBindings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keystone 汇编器（Engine 抽象层独立组件）。
 *
 * 保留了 Keystone 汇编器方向，所以即使没有 AnalysisEngine 时也能独立使用它；
 * 同时 [SelfAnalysisEngine.assemble] 最终也调用到本类。
 */
@Singleton
class KeystoneAssembler @Inject constructor() {

    /**
     * 编码指令 → 机器码。
     *
     * @param assembly 完整指令（如 "MOV W0, #1"）。
     * @param address 目标地址（分支指令 PC-rel 计算需要）。
     * @return 编码成功返回机器码字节；失败返回 null。
     */
    fun assemble(assembly: String, address: Long = 0L): ByteArray? {
        if (assembly.isBlank()) return null
        // Keystone 0.9.2 解析器对大小写敏感；先试原文再试小写
        val attempts = if (assembly == assembly.lowercase()) listOf(assembly)
        else listOf(assembly, assembly.lowercase())
        for (a in attempts) {
            val bytes = try { KeystoneBindings.asm(a, address)?.takeIf { it.isNotEmpty() } }
                catch (_: Throwable) { null }
            if (bytes != null) return bytes
        }
        return null
    }
}
