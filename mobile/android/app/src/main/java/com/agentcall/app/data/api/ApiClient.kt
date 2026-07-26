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
 * Server host is configurable — defaults to production URL.
 * Use a LAN IP (e.g. 192.168.1.100) for local development.
 */
object ApiClient {
    /** Configurable server host (IP or hostname, no port). Default: production URL */
    var serverHost: String = DEFAULT_HOST
        private set

    private const val DEFAULT_HOST = "dydcghsn0my6-production-qgbb8wql.australia-southeast1.suga.run"
    private const val API_PORT = 4000

    /** True when host is a domain name (not an IP address) → use HTTPS/WSS */
    private fun isDomainHost(): Boolean = !serverHost.matches(Regex("^[\\d.]+$"))

    fun getHttpBaseUrl(): String {
        val scheme = if (isDomainHost()) "https" else "http"
        val port = if (isDomainHost()) "" else ":$API_PORT"
        return "$scheme://$serverHost$port/api/v1/"
    }

    fun getWsUrl(userId: String): String {
        val scheme = if (isDomainHost()) "wss" else "ws"
        val port = if (isDomainHost()) "" else ":$API_PORT"
        return "$scheme://$serverHost$port/phone?user_id=$userId"
    }

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
        level = HttpLoggingInterceptor.Level.HEADERS
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
