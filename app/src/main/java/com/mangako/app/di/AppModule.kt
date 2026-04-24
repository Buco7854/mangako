package com.mangako.app.di

import android.content.Context
import androidx.room.Room
import com.mangako.app.data.db.HistoryDao
import com.mangako.app.data.db.HistoryDatabase
import com.mangako.app.data.db.PendingDao
import com.mangako.app.domain.cbz.CbzProcessor
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
}
