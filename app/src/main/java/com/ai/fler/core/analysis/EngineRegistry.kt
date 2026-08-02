package com.ai.fler.core.analysis

import javax.inject.Inject
import javax.inject.Singleton

/**
 * 引擎注册中心。
 *
 * 职责：
 * 1. 维护所有已注册的 [BinaryAnalysisEngine] 与 [EmulationEngine]。
 * 2. 提供「按能力挑引擎」「按 engineId 取引擎」的查询接口。
 * 3. 默认 fallback 策略：能力同时被多个引擎支持时，按 [AnalysisEnginePriority] 排序
 *    取优先级最高的一个，保证 UI / MCP 层永远拿到最合适的实现。
 *
 * 本类由 Hilt 在 [AnalysisModule] 提供。
 */
@Singleton
class EngineRegistry @Inject constructor() {

    private val analysisEngines = mutableMapOf<String, BinaryAnalysisEngine>()
    private val emulationEngines = mutableMapOf<String, EmulationEngine>()
    private val analysisPriority = mutableMapOf<String, Int>()   // engineId -> 优先级值，大的更优先
    private val emulationPriority = mutableMapOf<String, Int>()

    // ------------------------------------------------------------------
    // 注册
    // ------------------------------------------------------------------

    /**
     * 注册分析引擎。
     *
     * @param priority 数值越大优先级越高。推荐：Rizin=100, SelfAnalysis=10, Capstone=20
     */
    fun registerAnalysis(engine: BinaryAnalysisEngine, priority: Int = 0) {
        analysisEngines[engine.engineId] = engine
        analysisPriority[engine.engineId] = priority
    }

    fun registerEmulation(engine: EmulationEngine, priority: Int = 0) {
        emulationEngines[engine.engineId] = engine
        emulationPriority[engine.engineId] = priority
    }

    fun unregisterAnalysis(engineId: String) {
        analysisEngines.remove(engineId)
        analysisPriority.remove(engineId)
    }

    fun unregisterEmulation(engineId: String) {
        emulationEngines.remove(engineId)
        emulationPriority.remove(engineId)
    }

    // ------------------------------------------------------------------
    // 枚举 / 查询
    // ------------------------------------------------------------------

    fun listAnalysis(): List<BinaryAnalysisEngine> =
        analysisEngines.values
            .sortedByDescending { analysisPriority[it.engineId] ?: 0 }
            .toList()

    fun listEmulation(): List<EmulationEngine> =
        emulationEngines.values
            .sortedByDescending { emulationPriority[it.engineId] ?: 0 }
            .toList()

    fun getAnalysis(engineId: String): BinaryAnalysisEngine? = analysisEngines[engineId]

    fun getEmulation(engineId: String): EmulationEngine? = emulationEngines[engineId]

    /**
     * 挑选支持指定**全部**能力且 isAvailable=true 的分析引擎，按优先级返回最高的一个。
     */
    fun pickAnalysisFor(vararg caps: AnalysisCapability): BinaryAnalysisEngine? {
        if (caps.isEmpty()) return listAnalysis().firstOrNull { it.isAvailable }
        return listAnalysis().firstOrNull { engine ->
            engine.isAvailable && caps.all { engine.supports(it) }
        }
    }

    /**
     * 挑选支持指定能力且 isAvailable=true 的仿真引擎。
     */
    fun pickEmulationFor(vararg caps: EmulationCapability): EmulationEngine? {
        if (caps.isEmpty()) return listEmulation().firstOrNull { it.isAvailable }
        return listEmulation().firstOrNull { engine ->
            engine.isAvailable && caps.all { engine.supports(it) }
        }
    }

    /**
     * 返回支持指定能力的所有分析引擎（按优先级从高到低）。
     */
    fun listAnalysisSupporting(vararg caps: AnalysisCapability): List<BinaryAnalysisEngine> =
        listAnalysis().filter { engine ->
            engine.isAvailable && caps.all { engine.supports(it) }
        }
}

/** 约定的优先级常量，保证注册顺序无关。 */
object AnalysisEnginePriority {
    const val RIZIN = 100
    const val CAPSTONE = 20
    const val SELF_ANALYSIS = 10
}

object EmulationEnginePriority {
    const val UNIDBG = 100
    const val UNICORN = 50
}
