package com.agentcall.app.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.agentcall.app.settings.MessageTemplates
import com.agentcall.app.settings.QuietHoursManager
import com.agentcall.app.ui.theme.AgentCallTheme
import com.agentcall.app.ui.theme.IncomingCallTokens
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {

    @Inject lateinit var quietHoursManager: QuietHoursManager

    private var showCall by mutableStateOf(false)
    private var quietRing by mutableStateOf(false)
    private var showMicPermissionDenied by mutableStateOf(false)
    private var pendingCallIntent: Intent? = null
    private var currentCallId by mutableStateOf("")
    private var currentCallerName by mutableStateOf("AI Agent")
    private var currentContextSummary by mutableStateOf("")
    private var currentClientInfoName by mutableStateOf<String?>(null)
    private var ownsRing = false
    private var ringResolved = false
    private var answeredInUi = false
    // Notification Answer: defer the session start to onResume so the
    // microphone foreground service starts while this activity is visible.
    private var autoAnswerPending = false

    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            pendingCallIntent?.let {
                pendingCallIntent = null
                startService(it)
                ringResolved = true
                answeredInUi = true
                showCall = true
            }
        } else {
            showMicPermissionDenied = true
        }
    }

    companion object {
        private const val TAG = "IncomingCallActivity"
        private val isProcessing = AtomicBoolean(false)
    }

    private val launchSource: String
        get() = if (intent?.flags?.and(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) {
            "history"
        } else if (intent?.action == Intent.ACTION_MAIN) {
            "main"
        } else {
            "notification/fsi"
        }

    private fun adoptCall(intent: Intent, callId: String) {
        currentCallId = callId
        currentCallerName = intent.getStringExtra("caller_name") ?: "AI Agent"
        currentContextSummary = intent.getStringExtra("context_summary") ?: ""
        currentClientInfoName = intent.getStringExtra("client_info_name")
        quietRing = quietHoursManager.isQuietNow(currentCallerName)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }

        if (!isProcessing.compareAndSet(false, true)) {
            finish()
            return
        }
        ownsRing = true

        currentCallId = intent.getStringExtra("call_id") ?: run {
            isProcessing.set(false)
            finish()
            return
        }
        adoptCall(intent, currentCallId)
        val isDebugForce = intent.getBooleanExtra("debug_force", false)
        // Backlog item 14 — stale-launch guard. IDLE is not stale: after the
        // FCM process was reclaimed the notification is the only record of a
        // live ring, so finishing here made the ring page close itself.
        val launchAction = if (isDebugForce) {
            IncomingLaunchPolicy.Action.SHOW_RING
        } else {
            IncomingLaunchPolicy.actionFor(
                callId = currentCallId,
                launchedFromHistory = launchSource == "history",
                state = CallStateHolder.state.value,
            )
        }
        when (launchAction) {
            IncomingLaunchPolicy.Action.FINISH_STALE -> {
                Log.i(TAG, "[LAUNCH] stale launch for $currentCallId (${CallStateHolder.state.value.status}) — finishing")
                isProcessing.set(false)
                finish()
                return
            }
            IncomingLaunchPolicy.Action.SHOW_ANSWERED -> {
                // Answered elsewhere or the activity was recreated after an
                // in-place answer: keep the live call on screen.
                ringResolved = true
                answeredInUi = true
                RingTimeoutScheduler.cancel(this, currentCallId)
                CallService.cancelIncomingNotification(this)
                showCall = true
            }
            IncomingLaunchPolicy.Action.SHOW_RING -> {
                // A fresh process has no in-memory ring state, and a previous
                // finished call may still own the holder. Adopt the intent's
                // call so decline/answer and CallService's duplicate-answer
                // guard stay coherent.
                if (CallStateHolder.state.value.callId != currentCallId) {
                    CallStateHolder.ringing(currentCallId)
                }
                // The ring UI owns the timeout now: the service's 60s fallback
                // (meant for a notification that was never opened) would
                // otherwise fire under the open Later picker and send the AI a
                // decline note racing the user's chosen callback. The in-UI
                // countdown is picker-aware.
                SignalingForegroundService.notifyRingOpened(this@IncomingCallActivity)
                autoAnswerPending = !isDebugForce &&
                    intent.getBooleanExtra(CallService.EXTRA_AUTO_ANSWER, false)
                // The notification already started the ring. Skip it when the
                // user just tapped Answer; this is a no-op for the same call
                // otherwise and covers direct/debug/deep-link launches.
                if (!autoAnswerPending) {
                    IncomingCallRinger.start(this, currentCallId, quietRing)
                }
            }
        }
        Log.i(TAG, "[LAUNCH] IncomingCallActivity created callId=$currentCallId via=$launchSource quiet=$quietRing autoAnswer=$autoAnswerPending")

        // One ring truth for both surfaces: when the notification's Answer (or
        // Decline) resolves this call while the ring UI is visible, follow it
        // instead of leaving a second call screen behind.
        lifecycleScope.launch {
            CallStateHolder.state.collect { state ->
                if (state.callId != currentCallId) return@collect
                when (state.status) {
                    CallStatus.ANSWERED -> if (!answeredInUi && !showCall) {
                        Log.i(TAG, "[STATE] answered elsewhere for $currentCallId — showing live call")
                        ringResolved = true
                        answeredInUi = true
                        showCall = true
                    }
                    CallStatus.ENDED -> if (!showCall) finish()
                    else -> Unit
                }
            }
        }

        setContent {
            AgentCallTheme(darkTheme = true) {
                if (showMicPermissionDenied) {
                    LaunchedEffect(Unit) {
                        Toast.makeText(
                            this@IncomingCallActivity,
                            "Microphone permission is required for calls. Grant it in Settings.",
                            Toast.LENGTH_LONG,
                        ).show()
                        showMicPermissionDenied = false
                    }
                }

                Crossfade(
                    targetState = showCall,
                    animationSpec = tween(durationMillis = IncomingCallTokens.Motion.ScreenTransitionMs),
                    label = "callTransition",
                ) { inCall ->
                    if (inCall) {
                        ActiveCallScreen(
                            callId = currentCallId,
                            context = this@IncomingCallActivity,
                            contextSummary = currentContextSummary.takeIf { it.isNotBlank() },
                            agentName = currentCallerName,
                            onEndCall = { cid ->
                                startService(Intent(this@IncomingCallActivity, CallService::class.java).apply {
                                    action = CallService.ACTION_END_CALL
                                    putExtra(CallService.EXTRA_CALL_ID, cid)
                                })
                            },
                            onDismiss = { finish() },
                        )
                    } else {
                        key(currentCallId) {
                        IncomingCallScreen(
                            callerName = currentCallerName,
                            contextSummary = currentContextSummary,
                            clientInfoName = currentClientInfoName,
                            onAnswer = { answerCall() },
                            onDecline = {
                                ringResolved = true
                                RingTimeoutScheduler.cancel(this@IncomingCallActivity, currentCallId)
                                // Backlog item 14 — only cancel while this call is
                                // still ringing: the in-UI countdown auto-decline
                                // can race the answer tap, and a stale FSI decline
                                // must never cancel a live or answered call.
                                val state = CallStateHolder.state.value
                                if (state.status == CallStatus.RINGING && state.callId == currentCallId) {
                                    SignalingForegroundService.notifyRingResolved(this@IncomingCallActivity)
                                    CallService.cancelIncomingNotification(this@IncomingCallActivity)
                                    startService(Intent(this@IncomingCallActivity, CallService::class.java).apply {
                                        action = CallService.ACTION_CANCEL_CALL
                                        putExtra(CallService.EXTRA_CALL_ID, currentCallId)
                                        putExtra(CallService.EXTRA_TEXT, quietDeclineNote())
                                    })
                                } else {
                                    Log.i(TAG, "[DECLINE] skipped cancel for $currentCallId (${state.status}) — no longer ringing")
                                }
                                isProcessing.set(false)
                                finish()
                            },
                            onLater = { minutes ->
                                ringResolved = true
                                RingTimeoutScheduler.cancel(this@IncomingCallActivity, currentCallId)
                                SignalingForegroundService.notifyRingResolved(this@IncomingCallActivity)
                                CallService.cancelIncomingNotification(this@IncomingCallActivity)
                                startService(Intent(this@IncomingCallActivity, CallService::class.java).apply {
                                    action = CallService.ACTION_SCHEDULE_CALLBACK
                                    putExtra(CallService.EXTRA_CALL_ID, currentCallId)
                                    putExtra(CallService.EXTRA_TEXT, minutes.toString())
                                    putExtra(CallService.EXTRA_NOTE, MessageTemplates.laterMessage(this@IncomingCallActivity, minutes))
                                })
                                isProcessing.set(false)
                                finish()
                            },
                        )
                        }
                    }
                }
            }
        }

        lifecycleScope.launch {
            CallEventBus.events.collect { event ->
                if (event is CallEvent.CallEnded && !showCall) {
                    delay(500)
                    finish()
                }
            }
        }
    }

    /** In-app swipe and notification Answer share this one start path. */
    private fun answerCall() {
        ringResolved = true
        answeredInUi = true
        // Ring resolved by answer: disarm the exact-alarm timeout (no-op if the
        // FGS job path owns it).
        RingTimeoutScheduler.cancel(this, currentCallId)
        // Backlog item 14 — the FGS ring is cleared by CallService's
        // ACTION_START_CALL (notifyRingResolved) after the shared state flips
        // to ANSWERED, so a stale decline can never cancel an answered call.
        CallService.cancelIncomingNotification(this)
        val svcIntent = Intent(this, CallService::class.java).apply {
            action = CallService.ACTION_START_CALL
            putExtra(CallService.EXTRA_CALL_ID, currentCallId)
            putExtra(CallService.EXTRA_CALLER_NAME, currentCallerName)
            putExtra(CallService.EXTRA_CONTEXT_SUMMARY, currentContextSummary)
            // This activity transitions itself to ActiveCallScreen.
            putExtra(CallService.EXTRA_LAUNCH_UI, false)
            // Debug-only: forward the watchdog-cap override so adb-driven H2
            // verification can pass it through the ring UI (this ROM blocks
            // shell starts of the non-exported service directly).
            if (com.agentcall.app.BuildConfig.DEBUG) {
                val dbg = intent.getLongExtra(CallService.EXTRA_DEBUG_MAX_CALL_MS, 0L)
                if (dbg > 0L) putExtra(CallService.EXTRA_DEBUG_MAX_CALL_MS, dbg)
            }
        }
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startService(svcIntent)
            showCall = true
        } else {
            pendingCallIntent = svcIntent
            requestRecordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val callId = intent.getStringExtra("call_id") ?: return
        Log.d(TAG, "onNewIntent callId=$callId")
        if (intent.getBooleanExtra(CallService.EXTRA_AUTO_ANSWER, false)) {
            // Notification Answer while this activity already exists.
            val state = CallStateHolder.state.value
            when (IncomingLaunchPolicy.actionFor(callId, launchedFromHistory = false, state)) {
                IncomingLaunchPolicy.Action.SHOW_ANSWERED -> {
                    adoptCall(intent, callId)
                    ringResolved = true
                    answeredInUi = true
                    CallService.cancelIncomingNotification(this)
                    showCall = true
                }
                IncomingLaunchPolicy.Action.SHOW_RING -> {
                    adoptCall(intent, callId)
                    if (state.callId != callId) CallStateHolder.ringing(callId)
                    answerCall()
                }
                IncomingLaunchPolicy.Action.FINISH_STALE ->
                    Log.i(TAG, "[ANSWER] ignored for $callId (${state.status} for ${state.callId})")
            }
            return
        }
        adoptCall(intent, callId)
        showCall = false
        showMicPermissionDenied = false
        pendingCallIntent = null
        ringResolved = false
        answeredInUi = false
        IncomingCallRinger.start(this, currentCallId, quietRing)
    }

    override fun onResume() {
        super.onResume()
        if (!showCall && !intent.getBooleanExtra("debug_force", false)) {
            // A stale launch is one with positive evidence it is resolved or
            // superseded; IDLE only means the process was rebuilt and the
            // notification is the surviving record (see IncomingLaunchPolicy).
            when (IncomingLaunchPolicy.actionFor(
                callId = currentCallId,
                launchedFromHistory = launchSource == "history",
                state = CallStateHolder.state.value,
            )) {
                IncomingLaunchPolicy.Action.FINISH_STALE -> {
                    Log.i(TAG, "[RESUME] stale launch for $currentCallId — finishing")
                    isProcessing.set(false)
                    finish()
                    return
                }
                IncomingLaunchPolicy.Action.SHOW_ANSWERED -> {
                    ringResolved = true
                    answeredInUi = true
                    RingTimeoutScheduler.cancel(this, currentCallId)
                    CallService.cancelIncomingNotification(this)
                    showCall = true
                }
                IncomingLaunchPolicy.Action.SHOW_RING -> Unit
            }
        }
        if (autoAnswerPending && !showCall) {
            autoAnswerPending = false
            answerCall()
        }
    }

    override fun onDestroy() {
        if (ownsRing && !ringResolved && !isChangingConfigurations) {
            // Backlog item 14 — same guard as decline: only auto-decline
            // while this call is still ringing.
            val callIdToCancel = currentCallId
            val state = CallStateHolder.state.value
            if (state.status == CallStatus.RINGING && state.callId == callIdToCancel) {
                // Ring UI destroyed without answer/decline/later (e.g. back-press).
                // Resolve immediately instead of leaving the call pending and the
                // notification orphaned until the 60s service-side timeout.
                Log.i(TAG, "[DISMISS] ring destroyed unresolved — auto-declining $callIdToCancel")
                CallService.cancelIncomingNotification(this)
                if (callIdToCancel.isNotBlank()) {
                    startService(Intent(this@IncomingCallActivity, CallService::class.java).apply {
                        action = CallService.ACTION_CANCEL_CALL
                        putExtra(CallService.EXTRA_CALL_ID, callIdToCancel)
                        putExtra(CallService.EXTRA_TEXT, quietDeclineNote())
                    })
                }
            }
        }
        super.onDestroy()
        isProcessing.set(false)
    }

    /** Decline note: quiet-hours window info when DND is active, else the default. */
    private fun quietDeclineNote(): String {
        if (!quietRing) return MessageTemplates.declineMessage(this)
        val (start, end) = quietHoursManager.activeRange(currentCallerName) ?: (0 to 0)
        return MessageTemplates.quietHoursMessage(
            this,
            QuietHoursManager.minutesToLabel(start),
            QuietHoursManager.minutesToLabel(end),
        )
    }
}
