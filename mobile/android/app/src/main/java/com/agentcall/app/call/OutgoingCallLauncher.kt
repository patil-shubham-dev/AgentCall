package com.agentcall.app.call

import android.content.Context
import android.content.Intent
import java.util.UUID

/**
 * Single code path for starting an outgoing call (backlog items 1, 4, 8):
 * a fresh call id, the agent's display name, `outgoing=true` (ringback until
 * the agent picks up), and an optional context summary. Used by the profile
 * \"Call\" button, the missed-call \"Call back\" action, and the missed-call
 * notification.
 */
object OutgoingCallLauncher {

    fun launch(context: Context, agentName: String, contextSummary: String? = null) {
        context.startActivity(
            Intent(context, CallActivity::class.java).apply {
                putExtra("call_id", UUID.randomUUID().toString())
                putExtra("caller_name", agentName)
                putExtra("outgoing", true)
                putExtra(CallService.EXTRA_CONTEXT_SUMMARY, contextSummary ?: "")
            }
        )
    }
}
