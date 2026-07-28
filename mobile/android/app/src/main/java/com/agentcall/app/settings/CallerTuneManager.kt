package com.agentcall.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.media.RingtoneManager
import android.net.Uri
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CallerTuneManager @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("caller_tune", Context.MODE_PRIVATE)

    val uri: Uri
        get() {
            val stored = prefs.getString(KEY_TUNE_URI, null)
            if (stored != null) return Uri.parse(stored)
            return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        }

    val label: String
        get() = prefs.getString(KEY_TUNE_LABEL, null) ?: "Default ringtone"

    fun setUri(uri: Uri, label: String) {
        prefs.edit {
            putString(KEY_TUNE_URI, uri.toString())
            putString(KEY_TUNE_LABEL, label)
        }
    }

    fun resetToDefault() {
        prefs.edit {
            remove(KEY_TUNE_URI)
            remove(KEY_TUNE_LABEL)
        }
    }

    companion object {
        private const val KEY_TUNE_URI = "caller_tune_uri"
        private const val KEY_TUNE_LABEL = "caller_tune_label"
    }
}