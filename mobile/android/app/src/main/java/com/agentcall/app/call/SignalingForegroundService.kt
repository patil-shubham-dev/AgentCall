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
    private val ringCallers = mutableMapOf<String, Triple<String, String, String?>>()
    @Volatile private var foregroundStarted = false
    @Volatile private var lastFcmRegisterMs = 0L

    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            signalingClient.disconnect()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // Battery audit L4: the two SDK branches were identical — collapsed.
        // RECEIVER_NOT_EXPORTED is a compile-time constant whose unknown bits
        // are ignored pre-13, so one call covers all supported levels.
        registerReceiver(disconnectReceiver, IntentFilter(ACTION_DISCONNECT), RECEIVER_NOT_EXPORTED)
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
        // WS-down fallback — safety net for dropped FCM, foreground-only,
        // heavily rate-limited (5 min when idle). With FCM-only idle, this
        // is backup, not primary. Still gated by shouldRunFallbackPoll.
        fallbackJob = scope.launch {
            signalingClient.connectionState.collect { updateFallbackPoll() }
        }
        // FCM-only idle: no auto WebSocket. Rings wake via FCM
        // (AgentCallMessagingService posts directly, no FGS). FCM token
        // registration moved to AgentCallApp so it runs even when this
        // service is not alive (idle).
    }

    /** Debounced FCM token reconciliation via canonical WorkManager path. */
    private fun maybeRegisterFcmToken() {
        val now = System.currentTimeMillis()
        if (now - lastFcmRegisterMs < FCM_REGISTER_DEBOUNCE_MS) return
        lastFcmRegisterMs = now
        Log.i(TAG, "[FCM] ws-connected � enqueuing reconciliation")
        FcmRegistrationScheduler.enqueue(this)
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
                CallStateHolder.answered(event.callId)
                // Deliberately no maybeParkAndStop(): CallService owns the
                // active session and parks/stops us via ACTION_IDLE_PARK when
                // the call ends.
            }
            is VoiceBridgeEvent.CallEnded -> {
                clearRing()
                CallStateHolder.ended(event.callId)
                // Finalize the ring-time record (the answering path upserts its
                // own "started" row; this covers rings that ended without an
                // answer). Idempotent — no-op when no row exists.
                callRepository.saveCallEnded(event.callId, "ended")
                maybeParkAndStop()
            }
            is VoiceBridgeEvent.CallCancelled -> {
                clearRing()
                CallStateHolder.ended(event.callId)
                callRepository.saveCallEnded(event.callId, "cancelled")
                maybeParkAndStop()
            }
            is VoiceBridgeEvent.CallExpired -> {
                // Backlog item 1: the ring window closed unanswered. Record the
                // miss and — when the app is backgrounded — post the silent
                // missed-call notification that deep-links to the profile.
                clearRing()
                CallStateHolder.ended(event.callId)
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
                maybeParkAndStop()
            }
            is VoiceBridgeEvent.CallAborted -> {
                // The agent's process died mid-call (ring or active): record the
                // distinct "aborted" outcome — no missed-call notification, the
                // caller hung up rather than the phone missing the call.
                clearRing()
                CallStateHolder.ended(event.callId)
                callRepository.saveCallEnded(event.callId, "aborted")
                maybeParkAndStop()
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
        Log.i(TAG, "[DIAG] ring_validation_start callId=${event.callId} expiresAtMs=${event.expiresAtMs} nowMs=${System.currentTimeMillis()}")
        if (event.expiresAtMs != null && event.expiresAtMs <= System.currentTimeMillis()) {
            Log.w(TAG, "[RING] skipping expired call_incoming callId=${event.callId}")
            Log.i(TAG, "[DIAG] ring_validation_expired callId=${event.callId}")
            return
        }
        val diagGetStartMs = System.currentTimeMillis()
        Log.i(TAG, "[DIAG] get_calls_start callId=${event.callId} atMs=$diagGetStartMs")
        val session = try {
            callRepository.getCallStatus(event.callId)
        } catch (_: Exception) {
            null
        }
        Log.i(TAG, "[DIAG] get_calls_response callId=${event.callId} status=$session elapsedMs=${System.currentTimeMillis() - diagGetStartMs}")
        // Pending-only ring policy (RingTimeoutPolicy): a replayed call_incoming
        // for an already-answered (active) call must never re-ring — it would
        // clobber CallStateHolder mid-session and arm a 60s timeout against a
        // live call. The answer path confirms ACTIVE locally, so nothing legit
        // depends on 'active' passing a ring validator.
        if (RingTimeoutPolicy.shouldRingForServerStatus(session)) {
            val agentId = event.callerName.lowercase().replace("\\s+".toRegex(), "-")
            // Canonical profile id: survives server-side renames (the slug may
            // point at a renamed agent's pre-rename profile, which ensure
            // resolves by keyId). History must attach to the canonical id or a
            // renamed agent's calls split across two profiles.
            val profileId = callRepository.ensureProfileExists(agentId, event.callerName)
            // A ring is a call: create the history row now so decline notes,
            // expiry and answer all have a record to update (backlog item 1).
            callRepository.markCallRinging(event.callId, profileId, event.callerName, event.createdAtMs ?: System.currentTimeMillis())
            ring(event.callId, event.callerName, event.summary, event.clientInfoName)
        }
    }

    private fun ring(callId: String, callerName: String, summary: String, clientInfoName: String? = null) {
        if (callId == ringingCallId) return
        if (wasRecentlyRung(callId)) {
            Log.i(TAG, "[RING] skipping repeat ring callId=$callId (recently rung)")
            return
        }
        rememberRung(callId)
        noteActivity()
        Log.i(TAG, "[RING] ringing callId=$callId caller=$callerName")
        Log.i(TAG, "[DIAG] ring_start callId=$callId atMs=${System.currentTimeMillis()}")
        logRingDiagnostics()
        ringCallers[callId] = Triple(callerName, summary, clientInfoName)
        ringingCallId = callId
        // Battery audit M4: a ring starting while the socket is down must
        // start (or keep) the fallback poll even if no connection-state
        // emission follows — hasActiveRing is an input to the gate.
        updateFallbackPoll()
        // Ring truth for the Answer/Decline paths: the full-screen activity
        // and the notification's direct actions validate against this before
        // acting, so a stale ring UI can never answer/cancel a live call.
        // Set BEFORE the notification posts — the Answer PendingIntent is
        // fireable the moment the notification is visible.
        CallStateHolder.ringing(callId)
        // Backlog item 6: during quiet hours the ring is silent (dedicated
        // channel) but the full-screen UI still shows, so the user can answer
        // an important call. The AI learns about the window through the
        // auto-decline note below — never by assuming, never without a call.
        val quiet = quietHoursManager.isQuietNow(callerName)
        val diagNotifStartMs = System.currentTimeMillis()
        CallService.showIncomingCallNotification(this, callId, callerName, summary, quiet = quiet, clientInfoName = clientInfoName)
        Log.i(TAG, "[DIAG] notification_posted callId=$callId quiet=$quiet elapsedMs=${System.currentTimeMillis() - diagNotifStartMs}")
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
                CallStateHolder.ended(callId)
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
                    putExtra(CallService.EXTRA_FROM_TIMEOUT, true)
                })
                // Deliberately no maybeParkAndStop(): the server's
                // call_expired must still reach this service so the missed-call
                // notification can be posted; the terminal handler parks/stops.
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
        // Battery audit M4: the ring resolving flips hasActiveRing — without
        // re-evaluating, the poll would keep running at foreground cadence
        // with nobody left to act on it.
        updateFallbackPoll()
    }

    /**
     * FCM-only idle: no persistent FGS/WS. The service is transient — it lives
     * only while ringing or in an active call. When neither is true, park the
     * socket and stop, regardless of whether the app is foreground or background.
     * The previous check `if (isForeground) return` kept a permanent "Connected"
     * notification; that is now removed. Idle = no FGS, no notification, no WS.
     * Rings wake via FCM (AgentCallMessagingService posts directly, no FGS),
     * and the call itself opens WS via CallService.
     *
     * Fallback poller decision: the safety-net poll (pollActiveCall) is retained
     * but heavily rate-limited (5 min when idle) and gated to foreground only
     * via shouldRunFallbackPoll. Since this service no longer runs when idle,
     * the foreground safety net is now also covered by HomeViewModel's
     * foreground health poll — FGS fallback is backup for when FGS *is* alive
     * (ringing). Pure FCM-only with zero fallback was considered but we kept
     * the 5-min foreground poll as defense against OEM-dropped FCM.
     */
    private fun maybeParkAndStop() {
        if (ringingCallId != null) return
        if (CallService.hasActiveCall) return
        Log.i(TAG, "[FGS] no ring, no call — parking WS and stopping (FCM-only idle)")
        signalingClient.park()
        stopSelf()
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

    /**
     * Single decision point for the fallback poll loop (battery audit M4):
     * evaluates the pure gate against live state. Called from the connection
     * collector and from ring()/clearRing() so a ring starting or resolving
     * while the socket is down flips the poll without waiting for the next
     * connection-state emission.
     */
    private fun updateFallbackPoll() {
        val shouldPoll = FallbackPollCadence.shouldRunFallbackPoll(
            isDisconnected = signalingClient.connectionState.value ==
                SignalingClient.ConnectionState.DISCONNECTED,
            isParked = signalingClient.isParked,
            isForeground = ForegroundTracker.isForeground,
            hasActiveRing = ringingCallId != null,
        )
        if (shouldPoll) startFallbackPoll() else stopFallbackPoll()
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
        // FCM-only idle: foreground only while ringing or in an active call.
        // Previously this was always-on, causing permanent "AgentCall" notification.
        // Rings now arrive via AgentCallMessagingService, which validates and
        // posts directly WITHOUT starting this service (background FGS starts
        // crash: dataSync quota, then phoneCall Telecom requirements —
        // 2026-09-12 P0). This service goes foreground only for a live ring
        // it already owns (WS/poll path) or an active call.
        val shouldBeForeground = ringingCallId != null || CallService.hasActiveCall
        if (shouldBeForeground) {
            startForeground(NOTIFICATION_ID, createNotification(notificationTextFor(signalingClient.connectionState.value)).build())
            foregroundStarted = true
        }

        when (intent?.action) {
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
                // so it can never cancel a call that is now being handled, then
                // park/stop when nothing else needs the service.
                clearRing()
                maybeParkAndStop()
            }
            ACTION_IDLE_PARK -> {
                // Sent by CallService.endCall() (and MainActivity.onStop via
                // SignalingForegroundService.stop): the call is over and
                // nothing needs the socket — park and stop. A ring that
                // started concurrently with the previous call's teardown must
                // not be dismissed by this stale park request.
                if (ringingCallId == null) {
                    clearRing()
                }
                maybeParkAndStop()
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
            .setSmallIcon(R.drawable.agentcall_notification_icon)
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
        // Sent by CallService.endCall() after the active-call flag drops:
        // the FGS may have missed the terminal event while hasActiveCall was
        // still true, so it never got a chance to park/stop itself.
        const val ACTION_IDLE_PARK = "com.agentcall.app.action.IDLE_PARK"
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

        fun notifyIdlePark(context: Context) {
            context.startService(
                Intent(context, SignalingForegroundService::class.java).apply {
                    action = ACTION_IDLE_PARK
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
