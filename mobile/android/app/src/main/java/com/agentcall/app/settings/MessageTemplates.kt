package com.agentcall.app.settings

import android.content.Context
import android.content.SharedPreferences

/**
 * Decline / call-back-later message templates sent to the AI when the user
 * taps Decline or Later on an incoming call.
 *
 * Stored in SharedPreferences so Priority 3 can expose a Settings editor over
 * the same keys; defaults are the documented messages. The Later template
 * carries a {X} placeholder that is substituted with the chosen delay.
 */
object MessageTemplates {

    private const val PREFS_NAME = "call_message_templates"
    private const val KEY_DECLINE = "decline_message"
    private const val KEY_LATER = "later_template"

    val DECLINE_DEFAULT = "The user declined this call and is unavailable right now. " +
        "Please call me back when you're available: finish any task that doesn't need my " +
        "input first, then call back. If this call was a clarification or was urgent, " +
        "call back soon."

    val LATER_DEFAULT = "The user wants you to call back in {X} minutes. Please call me " +
        "back when the time is up: keep making progress on subtasks that don't need my " +
        "input, then call back and continue."

    fun declineMessage(context: Context): String {
        return prefs(context).getString(KEY_DECLINE, DECLINE_DEFAULT) ?: DECLINE_DEFAULT
    }

    fun laterMessage(context: Context, minutes: Int): String {
        return laterTemplateRaw(context).replace("{X}", minutes.toString())
    }

    fun laterTemplateRaw(context: Context): String {
        return prefs(context).getString(KEY_LATER, LATER_DEFAULT) ?: LATER_DEFAULT
    }

    fun setDeclineMessage(context: Context, text: String) {
        // commit() (not apply()) so the disk write is synchronous: the
        // Settings "Saved" indicator must not outlive a lost async flush.
        prefs(context).edit().putString(KEY_DECLINE, text).commit()
    }

    fun setLaterTemplate(context: Context, text: String) {
        prefs(context).edit().putString(KEY_LATER, text).commit()
    }

    fun resetDecline(context: Context) {
        prefs(context).edit().remove(KEY_DECLINE).commit()
    }

    fun resetLater(context: Context) {
        prefs(context).edit().remove(KEY_LATER).commit()
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
