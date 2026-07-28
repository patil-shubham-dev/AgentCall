package com.agentcall.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.agentcall.app.call.SignalingForegroundService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SignalingForegroundService.start(context)
        }
    }
}