package com.agentcall.app.di

import android.content.Context
import com.agentcall.app.call.SignalingClient
import com.agentcall.app.call.WebRTCClient
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.TokenManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideTokenManager(@ApplicationContext context: Context): TokenManager {
        return TokenManager(context)
    }

    @Provides
    @Singleton
    fun provideWebRTCClient(@ApplicationContext context: Context): WebRTCClient {
        val client = WebRTCClient(context)
        client.initialize()
        return client
    }

    @Provides
    @Singleton
    fun provideSignalingClient(tokenManager: TokenManager): SignalingClient {
        return SignalingClient(tokenManager)
    }
}
