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

    val DECLINE_DEFAULT = "The user is currently busy and can't answer right now. " +
        "If this is important, try calling again shortly. If not, continue working on any " +
        "other task that doesn't need the user's input, and call again once that's done. " +
        "If there's nothing else to do, stop here."

    val LATER_DEFAULT = "The user wants you to call back in {X} minutes. Until then, " +
        "continue working on any subtask that doesn't need input - don't try to finish the " +
        "entire task, just make progress on what you can - and call back when the time is up."

    fun declineMessage(context: Context): String {
        return prefs(context).getString(KEY_DECLINE, DECLINE_DEFAULT) ?: DECLINE_DEFAULT
    }

    fun laterMessage(context: Context, minutes: Int): String {
        val template = prefs(context).getString(KEY_LATER, LATER_DEFAULT) ?: LATER_DEFAULT
        return template.replace("{X}", minutes.toString())
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
