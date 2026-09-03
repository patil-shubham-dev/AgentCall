package com.agentcall.app.call

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.model.FcmTokenRequest
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import retrofit2.HttpException
import kotlin.coroutines.resume

/**
 * Canonical FCM registration / reconciliation.
 *
 * Replaces the previous fragmented one-shot paths (App startup coroutine,
 * onNewToken direct call, ws-connected re-register) with a single
 * WorkManager pipeline: NetworkType.CONNECTED + exponential backoff + unique
 * work. The worker always fetches the CURRENT Firebase token at execution
 * time and requires backend confirmation before marking success.
 *
 * Transient Render cold-start timeouts -> Result.retry() so WorkManager
 * retries with exponential backoff. The success timestamp is only written
 * after POST /phone/fcm-token returns 200.
 */
class FcmRegistrationWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        FcmRegistrationStore.init(applicationContext)
        ApiClient.init(applicationContext)
        Log.i(TAG, "[FCM] worker attempt #${runAttemptCount + 1}")

        val token = try {
            awaitFcmToken()
        } catch (e: Exception) {
            Log.w(TAG, "[FCM] worker token fetch failed", e)
            FcmRegistrationStore.recordFailure(e.message ?: "token-fetch-failed")
            return if (isTransient(e)) Result.retry() else Result.failure()
        }

        if (token.isNullOrBlank()) {
            Log.w(TAG, "[FCM] worker token null/blank — FCM unavailable")
            FcmRegistrationStore.recordFailure("no-token")
            // No token is transient (play services may be initializing)
            return Result.retry()
        }

        // Never log full token
        Log.i(TAG, "[FCM] worker fetched token ${token.take(12)}... len=${token.length}")

        return try {
            ApiClient.ensurePhoneToken()
            val api = ApiClient.create<ApiService>()
            api.registerFcmToken(FcmTokenRequest(token))
            FcmRegistrationStore.recordSuccess(token)
            Log.i(TAG, "[FCM] worker registration succeeded")
            Result.success()
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            Log.w(TAG, "[FCM] worker registration failed: $msg", e)
            FcmRegistrationStore.recordFailure(msg.take(200))
            if (isTransient(e)) {
                Log.i(TAG, "[FCM] transient failure — retrying")
                Result.retry()
            } else {
                // Permanent validation error (400) — do not retry endlessly
                // but surface failure; next enqueue (token refresh, startup, WS)
                // will try again with potentially new state.
                Result.failure()
            }
        }
    }

    private suspend fun awaitFcmToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) cont.resume(task.result) else cont.resume(null)
        }
    }

    private fun isTransient(e: Throwable): Boolean {
        if (e is IOException || e is SocketTimeoutException) return true
        if (e is HttpException) {
            val code = e.code()
            // 5xx, 429, 408 are transient; 400/401/403 are permanent (validation/auth)
            return code == 408 || code == 429 || code in 500..599
        }
        // Unknown networking/socket wrappers
        val msg = e.message ?: ""
        if (msg.contains("timeout", ignoreCase = true)) return true
        if (msg.contains("Unable to resolve host", ignoreCase = true)) return true
        if (msg.contains("Failed to connect", ignoreCase = true)) return true
        return true // default to retry for cold-start resilience; permanent errors are explicit 4xx above
    }

    companion object {
        private const val TAG = "AgentCall"
    }
}

object FcmRegistrationScheduler {
    private const val UNIQUE_WORK_NAME = "fcm_registration"

    fun enqueue(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<FcmRegistrationWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag(UNIQUE_WORK_NAME)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)

        Log.i("AgentCall", "[FCM] enqueue $UNIQUE_WORK_NAME (KEEP)")
    }
}
