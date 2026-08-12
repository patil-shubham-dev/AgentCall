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

    // ── Per-agent tunes (backlog item 7) ─────────────────────────
    // Precedence: agent tune -> global tune -> system default. Agent tunes
    // are keyed by the agent's id (the slugified name) and stored in the same
    // prefs file, so a reset-to-default for the global tune never touches them.

    fun hasAgentTune(agentId: String): Boolean =
        prefs.contains(agentKey(agentId, KEY_AGENT_URI)) ||
            prefs.contains(agentKey(agentId, KEY_AGENT_LABEL))

    fun getUriForAgent(agentId: String): Uri {
        val stored = prefs.getString(agentKey(agentId, KEY_AGENT_URI), null)
        if (stored != null) return Uri.parse(stored)
        return uri
    }

    fun getLabelForAgent(agentId: String): String {
        return prefs.getString(agentKey(agentId, KEY_AGENT_LABEL), null) ?: label
    }

    fun setAgentUri(agentId: String, uri: Uri, label: String) {
        prefs.edit {
            putString(agentKey(agentId, KEY_AGENT_URI), uri.toString())
            putString(agentKey(agentId, KEY_AGENT_LABEL), label)
        }
    }

    fun resetAgentUri(agentId: String) {
        prefs.edit {
            remove(agentKey(agentId, KEY_AGENT_URI))
            remove(agentKey(agentId, KEY_AGENT_LABEL))
        }
    }

    private fun agentKey(agentId: String, suffix: String) = "agent:$agentId:$suffix"

    companion object {
        private const val KEY_TUNE_URI = "caller_tune_uri"
        private const val KEY_TUNE_LABEL = "caller_tune_label"
        private const val KEY_AGENT_URI = "uri"
        private const val KEY_AGENT_LABEL = "label"
    }
}