package com.agentcall.app.di

import com.agentcall.app.call.SignalingClient
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
    fun provideSignalingClient(): SignalingClient {
        return SignalingClient()
    }
}
