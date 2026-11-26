package com.mojtaba.simcallrecorder.di

import android.content.Context
import androidx.room.Room
import com.mojtaba.simcallrecorder.data.local.AppDatabase
import com.mojtaba.simcallrecorder.data.local.MIGRATION_1_2
import com.mojtaba.simcallrecorder.data.local.MIGRATION_2_3
import com.mojtaba.simcallrecorder.data.local.MIGRATION_3_4
import com.mojtaba.simcallrecorder.data.local.StaleRecordingRecoveryCallback
import com.mojtaba.simcallrecorder.data.local.dao.CallRecordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object DatabaseModule {
    @Provides
    @Singleton
    fun provideAppDatabase(
        @ApplicationContext context: Context,
    ): AppDatabase = Room.databaseBuilder(
        context,
        AppDatabase::class.java,
        "recordings.db",
    )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
        // Runs once when this process first opens the database, before any new session can
        // insert a RECORDING row. Unlike Activity.onCreate, it cannot interrupt a live
        // recording merely because the user returns to the UI.
        .addCallback(StaleRecordingRecoveryCallback(File(context.filesDir, "recordings")))
        .setJournalMode(AppDatabase.JOURNAL_MODE)
        .build()

    @Provides
    fun provideCallRecordDao(database: AppDatabase): CallRecordDao =
        database.callRecordDao()
}
