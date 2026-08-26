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
    // A push (ACTION_RING_FROM_PUSH) is being validated against the backend;
    // while true the service must not park/stop or the ring is lost.
    @Volatile private var pendingRingValidation = false

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
        // WS-down fallback (battery audit M4): the poll runs only through the
        // FallbackPollCadence.shouldRunFallbackPoll gate — genuinely
        // DISCONNECTED, not parked, and only while a foreground app or an
        // in-flight ring can act on what it finds. CONNECTING is "wait": the
        // socket that is coming up will deliver the same events. Re-evaluated
        // on every connection-state emission AND whenever ring state changes
        // (ring()/clearRing()), since those flip the hasActiveRing input.
        fallbackJob = scope.launch {
            signalingClient.connectionState.collect { updateFallbackPoll() }
        }
        // If the service starts with the socket down (process restart after a
        // system kill, sticky-service restart), establish the WebSocket from
        // here — there is no boot receiver by design: rings wake us via FCM.
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
            // Battery audit L1: seed optimistically BEFORE the fetch so a
            // ws-CONNECTED callback racing this coroutine cannot double-POST
            // (observed on cold start: both registrations fired in the same
            // millisecond when seeding waited for POST completion). On
            // failure the seed is cleared so ws-CONNECTED doubles as retry.
            lastFcmRegisterMs = System.currentTimeMillis()
            val token = runCatching { awaitFcmToken() }.getOrNull()
            if (token.isNullOrBlank()) {
                Log.w(TAG, "[FCM] startup token fetch returned nothing — FCM unavailable?")
            } else {
                Log.i(TAG, "[FCM] startup token registration")
                runCatching { callRepository.registerFcmToken(token) }
                    .onFailure {
                        Log.w(TAG, "[FCM] startup token registration failed", it)
                        lastFcmRegisterMs = 0L
                    }
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
        CallService.showIncomingCallNotification(this, callId, callerName, summary, quiet = quiet, clientInfoName = clientInfoName)
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
     * Backlog item 14 — idle self-stop: with FCM as the primary ring-wake
     * path the always-on socket is no longer needed. When there is no ring
     * in flight, no active call and no visible app, the service parks the
     * websocket and stops itself. Any of the three conditions keeps it alive
     * (a ring owns the WS/poll path, an active call may still need the
     * socket, and a foreground app must keep its status truthful).
     *
     * [pendingRingValidation] also blocks parking: a push that is still
     * being validated has not yet decided whether it rings.
     */
    private fun maybeParkAndStop() {
        if (ringingCallId != null) return
        if (pendingRingValidation) return
        if (CallService.hasActiveCall) return
        if (ForegroundTracker.isForeground) return
        Log.i(TAG, "[FGS] no ring, no call, app backgrounded — parking WS and stopping")
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
                    // Guards the idle-park path: a park request that lands
                    // while the push is still validating must not kill the
                    // service before the ring (or its rejection) resolves.
                    pendingRingValidation = true
                    scope.launch {
                        ringFromEvent(event)
                        pendingRingValidation = false
                        // A push that failed validation (stale/expired/unknown)
                        // must not leave the FGS alive forever — nothing will
                        // ever park it otherwise.
                        if (ringingCallId == null) {
                            maybeParkAndStop()
                        }
                    }
                }
                // Idle-park backstop: if a push produced no ring within a
                // generous window (validation is a single network call), the
                // service is stale and should park itself.
                ringTimeoutJob?.cancel()
                ringTimeoutJob = scope.launch {
                    delay(PUSH_IDLE_STOP_MS)
                    if (ringingCallId == null) {
                        Log.i(TAG, "[FGS] push did not produce a ring — parking and stopping")
                        maybeParkAndStop()
                    }
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
        // Sent by CallService.endCall() after the active-call flag drops:
        // the FGS may have missed the terminal event while hasActiveCall was
        // still true, so it never got a chance to park/stop itself.
        const val ACTION_IDLE_PARK = "com.agentcall.app.action.IDLE_PARK"
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
        // How long a push that never produces a ring is allowed to keep the
        // service alive before it parks itself (validation is one network call).
        private const val PUSH_IDLE_STOP_MS = 20_000L
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
