package com.agentcall.app.di

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.agentcall.app.BuildConfig
import com.agentcall.app.call.SignalingClient
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.database.AgentCallDatabase
import com.agentcall.app.data.database.dao.AiProfileDao
import com.agentcall.app.data.database.dao.CallRecordDao
import com.agentcall.app.data.database.dao.TranscriptMessageDao
import com.agentcall.app.data.repository.CallRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSignalingClient(app: Application): SignalingClient {
        return SignalingClient(app)
    }

    @Provides
    @Singleton
    fun provideApplicationContext(app: Application): Context = app

    @Provides
    @Singleton
    fun provideDatabase(app: Application): AgentCallDatabase {
        val builder = Room.databaseBuilder(
            app,
            AgentCallDatabase::class.java,
            "agentcall.db"
        )
        // Real migration for the schema bump to v2 (per-agent ringtone +
        // quick replies). The destructive fallback stays DEBUG-only as a dev
        // convenience for mid-development schema changes — never a release
        // strategy.
        builder.addMigrations(AgentCallDatabase.MIGRATION_1_2)
        if (BuildConfig.DEBUG) {
            builder.fallbackToDestructiveMigration()
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideAiProfileDao(db: AgentCallDatabase): AiProfileDao = db.aiProfileDao()

    @Provides
    @Singleton
    fun provideCallRecordDao(db: AgentCallDatabase): CallRecordDao = db.callRecordDao()

    @Provides
    @Singleton
    fun provideTranscriptMessageDao(db: AgentCallDatabase): TranscriptMessageDao = db.transcriptMessageDao()

    @Provides
    @Singleton
    fun provideApiService(): ApiService = ApiClient.create()

    @Provides
    @Singleton
    fun provideCallRepository(
        profileDao: AiProfileDao,
        callDao: CallRecordDao,
        transcriptDao: TranscriptMessageDao,
        api: ApiService,
    ): CallRepository = CallRepository(profileDao, callDao, transcriptDao, api)
}
