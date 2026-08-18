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
    private const val PREFS_HOST = "agentcall_host"
    private const val KEY_SERVER_HOST = "server_host"

    // The backend host must survive process death (kill -9, system kill after
    // a reboot, OOM). Without persistence, a START_STICKY-restarted foreground
    // service reverts to DEFAULT_HOST and points at the wrong backend — e.g.
    // FCM wakes the process for a ring, ringFromEvent validates against the
    // production host, finds no such call, and silently drops the ring.
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_HOST, android.content.Context.MODE_PRIVATE)
        serverHost = prefs?.getString(KEY_SERVER_HOST, null)
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.DEFAULT_HOST
    }

    private fun persistHost() {
        prefs?.edit()?.putString(KEY_SERVER_HOST, serverHost)?.apply()
    }

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
        persistHost()
        phoneToken = null
        _retrofit = null
    }

    fun resetToDefault() {
        serverHost = BuildConfig.DEFAULT_HOST
        persistHost()
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
            // Retrofit proxies built before a host switch keep the old base
            // URL, so rewrite the scheme/host/port from the current config on
            // every request. Keeps Hilt-injected singletons (CallRepository,
            // IncomingCallActivity, ...) pointing at the active server.
            val newUrl = original.url.newBuilder()
                .scheme(if (isDomainHost()) "https" else "http")
                .host(serverHost)
                .port(if (isDomainHost()) 443 else API_PORT)
                .build()
            val token = phoneToken
            val request = if (token != null) {
                original.newBuilder()
                    .url(newUrl)
                    .header("Authorization", "Bearer $token")
                    .build()
            } else {
                original.newBuilder().url(newUrl).build()
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