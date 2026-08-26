package com.agentcall.app.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.agentcall.app.call.CallService

/**
 * DEBUG BUILDS ONLY (src/debug source set — not compiled into release).
 *
 * Lets adb drive a real ACTION_START_CALL session for battery-fix
 * verification (H2 wake lock + watchdog) on OEM ROMs that block shell
 * starts of non-exported services and force-finish adb-launched ring
 * activities. Mirrors the notification Answer action's exact intent shape,
 * plus the optional watchdog-cap override.
 *
 * Declared exported in src/debug/AndroidManifest.xml.
 */
class DebugCallTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val svc = Intent(context, CallService::class.java).apply {
            action = CallService.ACTION_START_CALL
            putExtra(CallService.EXTRA_CALL_ID, intent.getStringExtra(CallService.EXTRA_CALL_ID))
            putExtra(CallService.EXTRA_CALLER_NAME, intent.getStringExtra(CallService.EXTRA_CALLER_NAME) ?: "DebugAgent")
            putExtra(CallService.EXTRA_CONTEXT_SUMMARY, intent.getStringExtra(CallService.EXTRA_CONTEXT_SUMMARY))
            val cap = intent.getLongExtra(CallService.EXTRA_DEBUG_MAX_CALL_MS, 0L)
            if (cap > 0L) putExtra(CallService.EXTRA_DEBUG_MAX_CALL_MS, cap)
        }
        ContextCompat.startForegroundService(context, svc)
    }
}
