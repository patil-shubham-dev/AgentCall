package com.agentcall.app.call

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.agentcall.app.ForegroundTracker
import com.agentcall.app.MainActivity
import com.agentcall.app.R
import com.agentcall.app.data.repository.CallRepository
import com.agentcall.app.settings.MessageTemplates
import com.agentcall.app.settings.QuietHoursManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import javax.inject.Inject

/**
 * Always-alive foreground service owning the incoming-call ring.
 *
 * The ring cannot depend on an Activity being alive or in the foreground:
 * Android 14 background-activity-launch restrictions and OEM full-screen-intent
 * suppression mean the ring UI may never launch while the app is backgrounded.
 * This service therefore posts the audible notification itself, keeps a 60s
 * ring timeout, and auto-declines via the resilient cancel chain if the ring
 * UI is never shown or is destroyed without an answer.
 */
@AndroidEntryPoint
class SignalingForegroundService : Service() {

    @Inject lateinit var signalingClient: SignalingClient
    @Inject lateinit var callRepository: CallRepository
    @Inject lateinit var quietHoursManager: QuietHoursManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var eventsJob: Job? = null
    private var connectionStateJob: Job? = null
    private var ringTimeoutJob: Job? = null
    private var ringingCallId: String? = null
    private val recentlyRung = ArrayDeque<Pair<String, Long>>()
    // Ring metadata by callId (callerName to summary) — needed when the ring
    // later expires/cancels and the phone must record the outcome.
    private val ringCallers = mutableMapOf<String, Pair<String, String>>()
    @Volatile private var foregroundStarted = false
    @Volatile private var lastFcmRegisterMs = 0L

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            signalingClient.disconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_DISCONNECT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(disconnectReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(disconnectReceiver, filter)
        }
        eventsJob = scope.launch {
            signalingClient.events.collect { event ->
                handleEvent(event)
            }
        }
        // Keep the notification text truthful as the connection state changes.
        connectionStateJob = scope.launch {
            signalingClient.connectionState.collect { state ->
                updateNotificationForState(state)
                // Phase A (FCM push-to-wake): re-register the Firebase token
                // whenever the WS comes up. The phone token (WS auth) is fresh
                // at that moment, whereas the startup registration can race a
                // stale cached phone token after a backend restart and 401.
                // Debounced — WS flaps must not hammer the endpoint.
                if (state == SignalingClient.ConnectionState.CONNECTED) {
                    maybeRegisterFcmToken()
                }
            }
        }
        // WS-down fallback: on deployments whose proxy cannot hold a websocket
        // the ring push never arrives. While the socket is NOT connected, poll
        // the active-call REST endpoint so incoming calls still ring. When the
        // WS is up this loop is stopped — the push path stays the single ring
        // source; the shared recentlyRung/ringingCallId guards dedupe overlap
        // while the connection flaps.
        fallbackJob = scope.launch {
            signalingClient.connectionState.collect { state ->
                if (state == SignalingClient.ConnectionState.CONNECTED) {
                    stopFallbackPoll()
                } else {
                    startFallbackPoll()
                }
            }
        }
        // After a device reboot the app process is gone; the boot receiver only
        // starts this service, so establish the WebSocket from here.
        if (signalingClient.connectionState.value == SignalingClient.ConnectionState.DISCONNECTED) {
            signalingClient.connect()
        }
        // Phase A (FCM push-to-wake): register the Firebase token at startup,
        // not just on rotation. onNewToken only fires when the token *changes*,
        // so an install that minted its token while the backend host was wrong
        // (or before the endpoint existed) would never re-register — a silent
        // gap where FCM rings can never be delivered. Fetching the current
        // token here is idempotent and self-heals that case.
        scope.launch {
            val token = runCatching { awaitFcmToken() }.getOrNull()
            if (token.isNullOrBlank()) {
                Log.w(TAG, "[FCM] startup token fetch returned nothing — FCM unavailable?")
            } else {
                Log.i(TAG, "[FCM] startup token registration")
                runCatching { callRepository.registerFcmToken(token) }
                    .onFailure { Log.w(TAG, "[FCM] startup token registration failed", it) }
            }
        }
    }

    /** Debounced FCM token registration; called on WS CONNECTED. */
    private fun maybeRegisterFcmToken() {
        val now = System.currentTimeMillis()
        if (now - lastFcmRegisterMs < FCM_REGISTER_DEBOUNCE_MS) return
        lastFcmRegisterMs = now
        scope.launch {
            val token = runCatching { awaitFcmToken() }.getOrNull()
            if (token.isNullOrBlank()) return@launch
            Log.i(TAG, "[FCM] token registration (ws connected)")
            runCatching { callRepository.registerFcmToken(token) }
                .onFailure { Log.w(TAG, "[FCM] ws-connected registration failed", it) }
        }
    }

    /** Wraps FirebaseMessaging.getInstance().token (a Task) in a coroutine. */
    private suspend fun awaitFcmToken(): String? = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        com.google.firebase.messaging.FirebaseMessaging.getInstance().token
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    cont.resume(task.result)
                } else {
                    cont.resume(null)
                }
            }
    }

    private suspend fun handleEvent(event: VoiceBridgeEvent) {
        when (event) {
            is VoiceBridgeEvent.Connected -> {
                // Single delivery path: calls created while the phone was
                // offline arrive as queued call_incoming pushes (bounded by the
                // server queue TTL). No active-call poll here — it could ring a
                // call whose push was already dropped as stale.
            }
            is VoiceBridgeEvent.CallIncoming -> {
                noteActivity()
                ringFromEvent(event)
            }
            is VoiceBridgeEvent.CallAnswered -> {
                noteActivity()
                // The backend confirmed the answer — the ring is resolved even
                // if the answering surface never reported back locally, so the
                // 60s auto-decline can never fire on a live call.
                clearRing()
            }
            is VoiceBridgeEvent.CallEnded -> {
                clearRing()
                // Finalize the ring-time record (the answering path upserts its
                // own "started" row; this covers rings that ended without an
                // answer). Idempotent — no-op when no row exists.
                callRepository.saveCallEnded(event.callId, "ended")
            }
            is VoiceBridgeEvent.CallCancelled -> {
                clearRing()
                callRepository.saveCallEnded(event.callId, "cancelled")
            }
            is VoiceBridgeEvent.CallExpired -> {
                // Backlog item 1: the ring window closed unanswered. Record the
                // miss and — when the app is backgrounded — post the silent
                // missed-call notification that deep-links to the profile.
                clearRing()
                val meta = ringCallers.remove(event.callId)
                callRepository.saveCallEnded(event.callId, "expired")
                if (!ForegroundTracker.isForeground && meta != null) {
                    CallService.showMissedCallNotification(
                        this@SignalingForegroundService,
                        event.callId,
                        meta.first,
                        meta.first.agentSlug(),
                    )
                }
            }
            is VoiceBridgeEvent.CallAborted -> {
                // The agent's process died mid-call (ring or active): record the
                // distinct "aborted" outcome — no missed-call notification, the
                // caller hung up rather than the phone missing the call.
                clearRing()
                callRepository.saveCallEnded(event.callId, "aborted")
            }
            else -> {}
        }
    }

    private fun wasRecentlyRung(callId: String): Boolean {
        pruneRecentlyRung()
        return recentlyRung.any { it.first == callId }
    }

    private fun rememberRung(callId: String) {
        pruneRecentlyRung()
        recentlyRung.addLast(callId to System.currentTimeMillis())
        while (recentlyRung.size > MAX_RECENT_RINGS) recentlyRung.removeFirst()
    }

    private fun pruneRecentlyRung() {
        val cutoff = System.currentTimeMillis() - RECENT_RING_TTL_MS
        while (recentlyRung.isNotEmpty() && recentlyRung.first().second < cutoff) {
            recentlyRung.removeFirst()
        }
    }

    private suspend fun ringFromEvent(event: VoiceBridgeEvent.CallIncoming) {
        // The shared flow replays its last event on collector start, and queued
        // pushes can arrive for calls that were already resolved while we were
        // offline. Verify the call is still live before ringing.
        if (event.expiresAtMs != null && event.expiresAtMs <= System.currentTimeMillis()) {
            Log.w(TAG, "[RING] skipping expired call_incoming callId=${event.callId}")
            return
        }
        val session = try {
            callRepository.getCallStatus(event.callId)
        } catch (_: Exception) {
            null
        }
        if (session != null && (session == "pending" || session == "active")) {
            val agentId = event.callerName.lowercase().replace("\\s+".toRegex(), "-")
            callRepository.ensureProfileExists(agentId, event.callerName)
            // A ring is a call: create the history row now so decline notes,
            // expiry and answer all have a record to update (backlog item 1).
            callRepository.markCallRinging(event.callId, agentId, event.callerName, event.createdAtMs ?: System.currentTimeMillis())
            ring(event.callId, event.callerName, event.summary)
        }
    }

    private fun ring(callId: String, callerName: String, summary: String) {
        if (callId == ringingCallId) return
        if (wasRecentlyRung(callId)) {
            Log.i(TAG, "[RING] skipping repeat ring callId=$callId (recently rung)")
            return
        }
        rememberRung(callId)
        noteActivity()
        Log.i(TAG, "[RING] ringing callId=$callId caller=$callerName")
        logRingDiagnostics()
        ringCallers[callId] = callerName to summary
        ringingCallId = callId
        // Backlog item 6: during quiet hours the ring is silent (dedicated
        // channel) but the full-screen UI still shows, so the user can answer
        // an important call. The AI learns about the window through the
        // auto-decline note below — never by assuming, never without a call.
        val quiet = quietHoursManager.isQuietNow(callerName)
        CallService.showIncomingCallNotification(this, callId, callerName, summary, quiet = quiet)
        // Pre-bind and warm the TTS engine now so the first spoken word after
        // the user answers never pays the engine bind/voice-load cost.
        try {
            startService(Intent(this, CallService::class.java).apply {
                action = CallService.ACTION_PREWARM_TTS
            })
        } catch (e: Exception) {
            Log.w(TAG, "[RING] TTS prewarm start failed", e)
        }
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(RING_TIMEOUT_MS)
            if (ringingCallId == callId) {
                Log.i(TAG, "[RING] timeout for $callId — auto-declining")
                clearRing()
                val note = if (quiet) {
                    val (start, end) = quietHoursManager.activeRange(callerName) ?: (0 to 0)
                    MessageTemplates.quietHoursMessage(
                        this@SignalingForegroundService,
                        QuietHoursManager.minutesToLabel(start),
                        QuietHoursManager.minutesToLabel(end),
                    )
                } else {
                    MessageTemplates.declineMessage(this@SignalingForegroundService)
                }
                startService(Intent(this@SignalingForegroundService, CallService::class.java).apply {
                    action = CallService.ACTION_CANCEL_CALL
                    putExtra(CallService.EXTRA_CALL_ID, callId)
                    putExtra(CallService.EXTRA_TEXT, note)
                })
            }
        }
    }

    private fun logRingDiagnostics() {
        try {
            val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val fsi = Build.VERSION.SDK_INT >= 34 && mgr.canUseFullScreenIntent()
            val channel = mgr.getNotificationChannel(CallService.CHANNEL_INCOMING_CALL)
            val postGranted = ContextCompat.checkSelfPermission(
                this, android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memState = ActivityManager.RunningAppProcessInfo()
            ActivityManager.getMyMemoryState(memState)
            Log.i(
                TAG,
                "[RING-DIAG] fsi=$fsi channelImp=${channel?.importance} postNotifs=$postGranted " +
                    "interactive=${power.isInteractive} procImp=${memState.importance}",
            )
        } catch (e: Exception) {
            Log.w(TAG, "[RING-DIAG] failed", e)
        }
    }

    private fun clearRing() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
        ringingCallId = null
        CallService.cancelIncomingNotification(this)
    }

    private var fallbackJob: Job? = null
    private var fallbackPollLoop: Job? = null

    // Battery-friendly adaptive cadence: poll fast only while the app is in
    // the foreground or a ring is in flight (the user is looking at the phone);
    // back off once the phone has been idle/backgrounded for a while. In Doze
    // the OS throttles network anyway, so the slowest cadence costs nothing.
    @Volatile private var lastActivityMs = 0L
    @Volatile private var idlePollStreak = 0

    private fun noteActivity() {
        lastActivityMs = System.currentTimeMillis()
        idlePollStreak = 0
    }

    private fun nextPollDelayMs(): Long {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIdle = pm.isDeviceIdleMode
        val isFg = ForegroundTracker.isForeground || ringingCallId != null
        val now = System.currentTimeMillis()
        val delay = FallbackPollCadence.computeDelayMs(
            isDeviceIdle = isIdle,
            isForeground = ForegroundTracker.isForeground,
            hasActiveRing = ringingCallId != null,
            lastActivityMs = lastActivityMs,
            idlePollStreak = idlePollStreak,
            nowMs = now,
        )
        // Advance streak / reset based on the same logic the pure function used.
        if (isIdle) {
            idlePollStreak++
        } else if (isFg) {
            idlePollStreak = 0
        } else {
            idlePollStreak++
        }
        return delay
    }

    private fun startFallbackPoll() {
        if (fallbackPollLoop != null) return
        fallbackPollLoop = scope.launch {
            while (isActive) {
                pollActiveCall()
                delay(nextPollDelayMs())
            }
        }
    }

    private fun stopFallbackPoll() {
        fallbackPollLoop?.cancel()
        fallbackPollLoop = null
    }

    private suspend fun pollActiveCall() {
        val active = try {
            callRepository.checkActiveCall(signalingClient.currentUserId)
        } catch (_: Exception) {
            null // transient network — retry on the next tick
        } ?: return
        // Only unanswered rings; an answered (active) call must never ring again.
        if (active.status != "pending") return
        if (active.callId == ringingCallId) return
        if (wasRecentlyRung(active.callId)) return
        val agentId = try {
            callRepository.getCallDetails(active.callId)?.agentId
        } catch (_: Exception) {
            null
        }
        val callerName = agentId?.takeIf { it.isNotBlank() } ?: "AgentCall"
        Log.i(TAG, "[RING] fallback poll found pending call callId=${active.callId} caller=$callerName")
        ringFromEvent(
            VoiceBridgeEvent.CallIncoming(
                callId = active.callId,
                reason = active.reason,
                summary = active.summary,
                callerName = callerName,
                createdAtMs = System.currentTimeMillis(),
                expiresAtMs = null,
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification(notificationTextFor(signalingClient.connectionState.value)).build())
        foregroundStarted = true

        when (intent?.action) {
            ACTION_RING_FROM_PUSH -> {
                // Phase A (FCM push-to-wake): a ring delivered via FCM instead
                // of the WS/poll path. Routes through the SAME ringFromEvent
                // machinery — status re-validation, profile record, recentlyRung
                // dedupe, full-screen ring + 60s timeout — so a push that races
                // a WS/poll ring is a silent no-op and an expired push never
                // rings. Start the fallback poll too: if the socket is down the
                // push path just became the live ring source.
                val callId = intent.getStringExtra(EXTRA_RING_CALL_ID)
                if (callId.isNullOrBlank()) {
                    Log.w(TAG, "[RING] ACTION_RING_FROM_PUSH without callId — ignoring")
                } else {
                    val event = VoiceBridgeEvent.CallIncoming(
                        callId = callId,
                        reason = "input_required",
                        summary = intent.getStringExtra(EXTRA_RING_SUMMARY) ?: "",
                        callerName = intent.getStringExtra(EXTRA_RING_CALLER) ?: "AI Agent",
                        createdAtMs = intent.getStringExtra(EXTRA_RING_CREATED_AT)?.toEpochMsOrNull(),
                        expiresAtMs = intent.getStringExtra(EXTRA_RING_EXPIRES_AT)?.toEpochMsOrNull(),
                    )
                    scope.launch { ringFromEvent(event) }
                }
            }
            ACTION_RING_OPENED -> {
                // The ring UI is open and owns the timeout (its in-UI countdown
                // is picker-aware and auto-declines on unresolved destroy). The
                // service timer must not fire under an open picker: a decline
                // note racing a user's "call back later" would send the AI two
                // contradictory instructions. Notification stays — the open UI
                // is the ring now; only the 60s fallback for a notification
                // that was never opened is retired.
                ringTimeoutJob?.cancel()
                ringTimeoutJob = null
            }
            ACTION_RING_RESOLVED -> {
                // The ring UI was answered/declined/postponed — stop the timeout
                // so it can never cancel a call that is now being handled.
                clearRing()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notificationTextFor(state: SignalingClient.ConnectionState): String = when (state) {
        SignalingClient.ConnectionState.CONNECTED -> "Connected — ready for calls"
        SignalingClient.ConnectionState.CONNECTING -> "Connecting..."
        SignalingClient.ConnectionState.RECONNECTING -> "Reconnecting..."
        SignalingClient.ConnectionState.DISCONNECTED -> "Disconnected"
    }

    private fun createNotification(text: String): NotificationCompat.Builder {
        val disconnectIntent = PendingIntent.getBroadcast(
            this, REQUEST_DISCONNECT,
            Intent(ACTION_DISCONNECT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_SIGNALING)
            .setContentTitle("AgentCall")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_agent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0,
                    Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .addAction(
                NotificationCompat.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    disconnectIntent,
                ).build()
            )
    }

    private fun updateNotificationForState(state: SignalingClient.ConnectionState) {
        if (!foregroundStarted) return
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIFICATION_ID, createNotification(notificationTextFor(state)).build())
    }

    override fun onDestroy() {
        super.onDestroy()
        eventsJob?.cancel()
        connectionStateJob?.cancel()
        fallbackJob?.cancel()
        stopFallbackPoll()
        ringTimeoutJob?.cancel()
        try { unregisterReceiver(disconnectReceiver) } catch (_: IllegalArgumentException) {}
    }

    companion object {
        private const val TAG = "AgentCall"
        const val CHANNEL_SIGNALING = "signaling_service"
        const val ACTION_DISCONNECT = "com.agentcall.app.action.DISCONNECT_SIGNALING"
        const val ACTION_RING_RESOLVED = "com.agentcall.app.action.RING_RESOLVED"
        const val ACTION_RING_OPENED = "com.agentcall.app.action.RING_OPENED"
        // Phase A (FCM push-to-wake): the FGS is started by the Firebase
        // MessagingService with this action carrying the ring payload.
        const val ACTION_RING_FROM_PUSH = "com.agentcall.app.action.RING_FROM_PUSH"
        const val EXTRA_RING_CALL_ID = "extra_ring_call_id"
        const val EXTRA_RING_CALLER = "extra_ring_caller"
        const val EXTRA_RING_SUMMARY = "extra_ring_summary"
        const val EXTRA_RING_CREATED_AT = "extra_ring_created_at"
        const val EXTRA_RING_EXPIRES_AT = "extra_ring_expires_at"
        private const val NOTIFICATION_ID = 1003
        private const val REQUEST_DISCONNECT = 1001
        private const val RING_TIMEOUT_MS = 60_000L
        private const val RECENT_RING_TTL_MS = 5 * 60_000L
        private const val MAX_RECENT_RINGS = 16
        // Adaptive fallback-poll cadence (battery): fast while active/foreground,
        // backing off to one request per POLL_IDLE_MS once idle for a while.
        private const val POLL_ACTIVE_MS = 10_000L
        private const val POLL_BACKGROUND_MS = 60_000L
        private const val POLL_IDLE_MS = 300_000L
        private const val ACTIVE_WINDOW_MS = 5 * 60_000L
        private const val POLL_DOZE_SKIPS_MS = 300_000L
        private const val POLL_DOZE_EVERY = 3
        // Debounce FCM re-registration on WS flaps (idempotent server-side).
        private const val FCM_REGISTER_DEBOUNCE_MS = 60_000L

        fun start(context: Context) {
            context.startForegroundService(Intent(context, SignalingForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SignalingForegroundService::class.java))
        }

        fun notifyRingResolved(context: Context) {
            context.startService(
                Intent(context, SignalingForegroundService::class.java).apply {
                    action = ACTION_RING_RESOLVED
                }
            )
        }

        fun notifyRingOpened(context: Context) {
            context.startService(
                Intent(context, SignalingForegroundService::class.java).apply {
                    action = ACTION_RING_OPENED
                }
            )
        }
    }
}

// ISO-8601 → epoch ms; blank or unparseable yields null (mirrors the parser
// in SignalingClient/CallViewModel so all ring sources agree on staleness).
private fun String.toEpochMsOrNull(): Long? =
    try {
        if (isBlank()) null else java.time.Instant.parse(this).toEpochMilli()
    } catch (_: Exception) {
        null
    }
