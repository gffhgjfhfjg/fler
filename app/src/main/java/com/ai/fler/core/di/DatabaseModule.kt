package com.ai.fler.core.di

import android.content.Context
import androidx.room.Room
import com.ai.fler.data.AppDatabase
import com.ai.fler.data.dao.AddressMappingDao
import com.ai.fler.data.dao.AnalysisDao
import com.ai.fler.data.dao.DartClassDao
import com.ai.fler.data.dao.DartMethodDao
import com.ai.fler.data.dao.LibraryDao
import com.ai.fler.data.dao.PpEntryDao
import com.ai.fler.data.dao.ProjectDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库依赖注入模块。
 *
 * 提供 AppDatabase 和各 DAO 的单例。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideProjectDao(db: AppDatabase): ProjectDao = db.projectDao()

    @Provides
    fun provideAnalysisDao(db: AppDatabase): AnalysisDao = db.analysisDao()

    @Provides
    fun provideDartClassDao(db: AppDatabase): DartClassDao = db.dartClassDao()

    @Provides
    fun provideDartMethodDao(db: AppDatabase): DartMethodDao = db.dartMethodDao()

    @Provides
    fun providePpEntryDao(db: AppDatabase): PpEntryDao = db.ppEntryDao()

    @Provides
    fun provideLibraryDao(db: AppDatabase): LibraryDao = db.libraryDao()

    @Provides
    fun provideAddressMappingDao(db: AppDatabase): AddressMappingDao = db.addressMappingDao()
}
