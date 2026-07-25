package com.agentcall.app.data.api

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * API client for the VoiceBridge backend.
 * Server host is configurable — defaults to 10.0.2.2 (Android emulator localhost).
 * Change it to your laptop's local IP when running on a real device.
 */
object ApiClient {
    /** Configurable server host (IP or hostname, no port). Default: 10.0.2.2 */
    var serverHost: String = DEFAULT_HOST
        private set

    private const val DEFAULT_HOST = "10.0.2.2"
    private const val API_PORT = 4000

    fun getHttpBaseUrl(): String = "http://$serverHost:$API_PORT/api/v1/"
    fun getWsUrl(userId: String): String = "ws://$serverHost:$API_PORT/phone?user_id=$userId"

    /** Update the server host and rebuild the Retrofit instance. */
    fun setServerHost(host: String) {
        serverHost = host.trim().ifBlank { DEFAULT_HOST }
        _retrofit = null
    }

    fun resetToDefault() {
        serverHost = DEFAULT_HOST
        _retrofit = null
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .build()

    @Volatile
    private var _retrofit: Retrofit? = null

    private val retrofit: Retrofit
        get() {
            val existing = _retrofit
            if (existing != null) return existing
            return synchronized(this) {
                _retrofit ?: buildRetrofit().also { _retrofit = it }
            }
        }

    private fun buildRetrofit(): Retrofit = Retrofit.Builder()
        .baseUrl(getHttpBaseUrl())
        .client(httpClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()

    inline fun <reified T> create(): T = retrofit.create(T::class.java)
}
