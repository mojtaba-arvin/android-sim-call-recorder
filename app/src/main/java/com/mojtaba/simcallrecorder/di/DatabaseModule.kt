package com.mojtaba.simcallrecorder.di

import android.content.Context
import androidx.room.Room
import com.mojtaba.simcallrecorder.data.local.AppDatabase
import com.mojtaba.simcallrecorder.data.local.dao.RecordingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "recordings.db",
    ).build()

    @Provides
    fun provideRecordingDao(database: AppDatabase): RecordingDao =
        database.recordingDao()
}
