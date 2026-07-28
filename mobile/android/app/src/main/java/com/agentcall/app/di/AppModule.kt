package com.agentcall.app.di

import android.app.Application
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
    fun provideSignalingClient(app: Application): SignalingClient {
        return SignalingClient(app)
    }
}
