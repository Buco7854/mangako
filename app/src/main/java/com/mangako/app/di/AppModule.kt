package com.mangako.app.di

import android.content.Context
import androidx.room.Room
import com.mangako.app.data.db.HistoryDao
import com.mangako.app.data.db.HistoryDatabase
import com.mangako.app.data.db.PendingDao
import com.mangako.app.domain.cbz.CbzProcessor
import com.mangako.app.domain.pipeline.PipelineEvaluator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides @Singleton
    fun provideHistoryDatabase(@ApplicationContext ctx: Context): HistoryDatabase =
        Room.databaseBuilder(ctx, HistoryDatabase::class.java, "mangako.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideHistoryDao(db: HistoryDatabase): HistoryDao = db.historyDao()

    @Provides
    fun providePendingDao(db: HistoryDatabase): PendingDao = db.pendingDao()

    @Provides @Singleton
    fun provideCbzProcessor(): CbzProcessor = CbzProcessor()

    /** A single shared evaluator — stateless so it's safe to reuse across
     *  workers, the Inbox simulator, and the pipeline preview. */
    @Provides @Singleton
    fun providePipelineEvaluator(): PipelineEvaluator = PipelineEvaluator()
}
