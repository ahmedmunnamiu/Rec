package com.ahmedmunna.callrecorder.di

import android.content.Context
import androidx.room.Room
import com.ahmedmunna.callrecorder.data.db.AppDatabase
import com.ahmedmunna.callrecorder.data.db.EngineTestDao
import com.ahmedmunna.callrecorder.data.db.RecordingDao
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
    fun database(@ApplicationContext ctx: Context): AppDatabase =
        Room.databaseBuilder(ctx, AppDatabase::class.java, "recorder.db").build()

    @Provides fun recordingDao(db: AppDatabase): RecordingDao = db.recordings()
    @Provides fun engineTestDao(db: AppDatabase): EngineTestDao = db.engineTests()
}
