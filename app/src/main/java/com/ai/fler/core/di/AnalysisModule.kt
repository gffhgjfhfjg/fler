package com.ai.fler.core.di

import com.ai.fler.core.analysis.AnalysisSession
import com.ai.fler.core.analysis.EngineRegistry
import com.ai.fler.core.analysis.AnalysisEnginePriority
import com.ai.fler.core.analysis.EmulationEnginePriority
import com.ai.fler.core.analysis.SoEditorCache
import com.ai.fler.core.analysis.assembler.KeystoneAssembler
import com.ai.fler.core.analysis.engine.RizinEngine
import com.ai.fler.core.analysis.engine.SelfAnalysisEngine
import com.ai.fler.core.analysis.engine.UnicornEngine
import com.ai.fler.core.analysis.engine.UnidbgEnginePlaceholder
import com.ai.fler.core.log.AppLogger
import com.ai.fler.core.service.BackupManager
import com.ai.fler.core.service.EngineLoader
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import android.content.Context

/**
 * 分析 / 仿真引擎的 DI 注册模块。
 *
 * - EngineRegistry: 注册中心，所有引擎在此按优先级登记。
 * - AnalysisSession: 统一会话门面（UI / MCP 入口）。
 * - RizinEngine: isAvailable=false，占位；静态库就位后自动升为优先。
 * - SelfAnalysisEngine: fallback，低优先级但默认可用。
 * - Unicorn/Unidbg 引擎：Unicorn 已集成（静态链接，isAvailable 运行时探测）；Unidbg 占位。
 */
@Module
@InstallIn(SingletonComponent::class)
object AnalysisModule {

    @Provides
    @Singleton
    fun provideEngineRegistry(
        @ApplicationContext appContext: Context,
        keystoneAssembler: KeystoneAssembler
    ): EngineRegistry {
        val reg = EngineRegistry()

        // 分析引擎：按优先级
        reg.registerAnalysis(RizinEngine(appContext.cacheDir.absolutePath), AnalysisEnginePriority.RIZIN)
        reg.registerAnalysis(
            SelfAnalysisEngine(keystoneAssembler),
            AnalysisEnginePriority.SELF_ANALYSIS
        )

        // 仿真引擎：Unicorn 已集成（编译期禁用/库缺失时 isAvailable=false 自动降级）
        reg.registerEmulation(
            UnicornEngine(),
            EmulationEnginePriority.UNICORN
        )
        reg.registerEmulation(
            UnidbgEnginePlaceholder(),
            EmulationEnginePriority.UNIDBG
        )
        return reg
    }

    @Provides
    @Singleton
    fun provideKeystoneAssembler(): KeystoneAssembler = KeystoneAssembler()

    @Provides
    @Singleton
    fun provideAnalysisSession(
        registry: EngineRegistry,
        backupManager: BackupManager,
        soEditorCache: SoEditorCache,
        appLogger: AppLogger,
    ): AnalysisSession = AnalysisSession(registry, backupManager, soEditorCache, appLogger)
}
