package com.agentcall.app.call

import android.content.Context
import android.content.SharedPreferences

object FcmRegistrationStore {
    private const val PREFS_NAME = "fcm_registration_store"
    private const val KEY_LAST_SUCCESS_MS = "last_success_ms"
    private const val KEY_LAST_ATTEMPT_MS = "last_attempt_ms"
    private const val KEY_LAST_TOKEN_HASH = "last_token_hash"
    private const val KEY_LAST_ERROR = "last_error"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun recordSuccess(token: String) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        val hash = token.take(12)
        p.edit().putLong(KEY_LAST_SUCCESS_MS, now).putLong(KEY_LAST_ATTEMPT_MS, now).putString(KEY_LAST_TOKEN_HASH, hash).remove(KEY_LAST_ERROR).apply()
    }

    fun recordFailure(error: String) {
        val p = prefs ?: return
        val now = System.currentTimeMillis()
        p.edit().putLong(KEY_LAST_ATTEMPT_MS, now).putString(KEY_LAST_ERROR, error.take(200)).apply()
    }

    fun isRegistered(): Boolean {
        val p = prefs ?: return false
        val lastSuccess = p.getLong(KEY_LAST_SUCCESS_MS, 0L)
        if (lastSuccess == 0L) return false
        val ageMs = System.currentTimeMillis() - lastSuccess
        return ageMs < 30L * 24 * 60 * 60 * 1000
    }

    fun getStatusForSettings(): String {
        val p = prefs ?: return "Unknown — never checked"
        val lastSuccess = p.getLong(KEY_LAST_SUCCESS_MS, 0L)
        val lastAttempt = p.getLong(KEY_LAST_ATTEMPT_MS, 0L)
        val lastError = p.getString(KEY_LAST_ERROR, null)
        return when {
            lastSuccess == 0L && lastAttempt == 0L -> "Never attempted — app never registered for push"
            lastSuccess > 0 && isRegistered() -> "Registered"
            lastSuccess > 0 && !isRegistered() -> "Stale — last success long ago"
            lastError != null -> "Failed: $lastError"
            else -> "Not registered"
        }
    }
}
