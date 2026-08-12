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
    private const val KEY_VOICEMAIL = "voicemail_message"

    val DECLINE_DEFAULT = "The user declined this call and is unavailable right now. " +
        "Please call me back when you're available: finish any task that doesn't need my " +
        "input first, then call back. If this call was a clarification or was urgent, " +
        "call back soon."

    val LATER_DEFAULT = "The user wants you to call back in {X} minutes. Please call me " +
        "back when the time is up: keep making progress on subtasks that don't need my " +
        "input, then call back and continue."

    // Backlog item 5: "leave a voicemail" decline variant. Delivered through
    // the same cancel path (ACTION_CANCEL_CALL + EXTRA_TEXT) so the persisted
    // retry machinery carries it — no new transport.
    val VOICEMAIL_DEFAULT = "The user missed your call and left this voicemail: " +
        "\u201CI'm not free right now — please call me back when you're available and " +
        "continue what you were working on.\u201D"

    // Backlog item 1: the "Call back" action on a missed-call row reuses the
    // outgoing-call flow; this prompt is carried as the outgoing call's
    // context summary so the agent knows why it's being called back.
    val CALLBACK_PROMPT = "This is a return call for the missed call earlier. " +
        "Please pick up and continue with the task you were working on, or let the " +
        "user know what you need from them."

    fun declineMessage(context: Context): String {
        return prefs(context).getString(KEY_DECLINE, DECLINE_DEFAULT) ?: DECLINE_DEFAULT
    }

    /** The prompt carried by a missed-call "Call back" outgoing call. */
    fun callbackPrompt(context: Context): String = CALLBACK_PROMPT

    fun voicemailMessage(context: Context): String {
        return prefs(context).getString(KEY_VOICEMAIL, VOICEMAIL_DEFAULT) ?: VOICEMAIL_DEFAULT
    }

    /**
     * Quiet-hours decline note (backlog item 6). The user's phone is silent
     * right now; the AI learns the window through this note — never by
     * assuming, and never outside an actual call.
     */
    fun quietHoursMessage(context: Context, startLabel: String, endLabel: String): String {
        val custom = prefs(context).getString(KEY_QUIET_HOURS_TEMPLATE, null)
        val base = custom ?: "The user's phone is in quiet hours today (from {START} to {END}). " +
            "Please do not call back during this window — continue on subtasks that don't " +
            "need my input and call again after {END}."
        return base.replace("{START}", startLabel).replace("{END}", endLabel)
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

    fun setVoicemailMessage(context: Context, text: String) {
        prefs(context).edit().putString(KEY_VOICEMAIL, text).commit()
    }

    fun resetDecline(context: Context) {
        prefs(context).edit().remove(KEY_DECLINE).commit()
    }

    fun resetLater(context: Context) {
        prefs(context).edit().remove(KEY_LATER).commit()
    }

    fun resetVoicemail(context: Context) {
        prefs(context).edit().remove(KEY_VOICEMAIL).commit()
    }

    private const val KEY_QUIET_HOURS_TEMPLATE = "quiet_hours_template"

    /**
     * Quiet-hours note text is fixed for now (not exposed as a Settings
     * editor); the keys exist so a future editor can reuse the same plumbing.
     */
    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
