package com.agentcall.app.data.api

import com.agentcall.app.BuildConfig
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

@Serializable
data class PhoneTokenResponse(
    @SerialName("status") val status: String,
    @SerialName("token") val token: String,
    @SerialName("user_id") val userId: String,
)

@Serializable
data class PhoneTokenRequest(@SerialName("user_id") val userId: String = "solo-user")

interface PhoneApi {
    @POST("phone/token")
    suspend fun requestToken(@Body body: PhoneTokenRequest): PhoneTokenResponse
}

object ApiClient {
    var serverHost: String = BuildConfig.DEFAULT_HOST
        private set

    @Volatile
    var phoneToken: String? = null

    private const val API_PORT = 4000

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

    fun setServerHost(host: String) {
        serverHost = host.trim().ifBlank { BuildConfig.DEFAULT_HOST }
        phoneToken = null
        _retrofit = null
    }

    fun resetToDefault() {
        serverHost = BuildConfig.DEFAULT_HOST
        phoneToken = null
        _retrofit = null
    }

    suspend fun ensurePhoneToken(userId: String = "solo-user") {
        if (phoneToken != null) return
        val api = create<PhoneApi>()
        val response = api.requestToken(PhoneTokenRequest(userId))
        phoneToken = response.token
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .addInterceptor(loggingInterceptor)
        .addInterceptor { chain ->
            val original = chain.request()
            val token = phoneToken
            val request = if (token != null) {
                original.newBuilder()
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                original
            }
            chain.proceed(request)
        }
        .build()

    @Volatile
    private var _retrofit: Retrofit? = null

    @PublishedApi
    internal val retrofit: Retrofit
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