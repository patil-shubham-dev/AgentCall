package com.agentcall.app.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PhoneForwarded
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.settings.CallerTuneManager
import com.agentcall.app.settings.MessageTemplates
import com.agentcall.app.settings.QuietHoursManager
import com.agentcall.app.ui.composables.ActionCircle
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.ClientBadge
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

@AndroidEntryPoint
class IncomingCallActivity : ComponentActivity() {

    @Inject lateinit var callerTuneManager: CallerTuneManager
    @Inject lateinit var quietHoursManager: QuietHoursManager
    private var mediaPlayer: MediaPlayer? = null
    private var ringtoneFallback: Ringtone? = null
    @Volatile private var isPlayerValid = false

    private var showCall by mutableStateOf(false)
    private var quietRing by mutableStateOf(false)
    private var showMicPermissionDenied by mutableStateOf(false)
    private var pendingCallIntent: Intent? = null
    private var currentCallId by mutableStateOf("")
private var currentCallerName by mutableStateOf("AI Agent")
private var currentContextSummary by mutableStateOf("")
private var currentClientInfoName by mutableStateOf<String?>(null)
    private var currentTimeoutSeconds by mutableStateOf(60)
    private var ownsRing = false
    private var ringResolved = false
    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCallIntent?.let {
                pendingCallIntent = null
                startService(it)
                ringResolved = true
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

    private fun startRinger() {
        // Backlog item 6: during quiet hours the ring is fully silent (the
        // notification used the quiet channel too). The UI still shows so an
        // important call can be answered.
        if (quietHoursManager.isQuietNow(currentCallerName)) {
            Log.i(TAG, "[RING] quiet hours active — silent ring for $currentCallerName")
            isPlayerValid = false
            return
        }
        try {
            // One global tune for every incoming call (Settings > Caller Tune),
            // falling back to the system default.
            val uri: Uri = callerTuneManager.uri
            val mp = MediaPlayer().apply {
                setDataSource(this@IncomingCallActivity, uri)
                isLooping = true
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error: what=$what extra=$extra")
                    isPlayerValid = false
                    true
                }
            }
            kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                try {
                    mp.prepare()
                    mp.start()
                    isPlayerValid = true
                } catch (_: Exception) {
                    mp.release()
                    ringtoneFallback = RingtoneManager.getRingtone(this@IncomingCallActivity, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                    ringtoneFallback?.play()
                }
            }
            mediaPlayer = mp
        } catch (_: Exception) {
            ringtoneFallback = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
            ringtoneFallback?.play()
        }
    }

    private fun stopRinger() {
        try {
            mediaPlayer?.apply {
                if (isPlayerValid && isPlaying) stop()
                release()
            }
        } catch (_: Exception) { }
        try {
            ringtoneFallback?.stop()
        } catch (_: Exception) { }
        mediaPlayer = null
        ringtoneFallback = null
        isPlayerValid = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)

        if (!isProcessing.compareAndSet(false, true)) {
            finish()
            return
        }
        ownsRing = true

        currentCallId = intent.getStringExtra("call_id") ?: run { isProcessing.set(false); finish(); return }
        currentCallerName = intent.getStringExtra("caller_name") ?: "AI Agent"
        currentContextSummary = intent.getStringExtra("context_summary") ?: ""
        currentClientInfoName = intent.getStringExtra("client_info_name")
        // Backlog item 14 — stale-FSI guard: a full-screen intent for an
        // already-answered/ended call (relaunch from recents, racing
        // duplicate notification, stale push) must never re-ring. Validate
        // against the shared ring truth BEFORE telling the FGS the ring UI is
        // open, so the service's 60s timeout stays armed when the guard
        // finishes — a rejected launch must not leave the ring ungoverned.
        val state = CallStateHolder.state.value
        if (state.status != CallStatus.RINGING || state.callId != currentCallId) {
            Log.i(TAG, "[LAUNCH] stale FSI for $currentCallId (${state.status}) — finishing")
            isProcessing.set(false)
            finish()
            return
        }
        // The ring UI now owns the timeout: the service's 60s fallback (meant
        // for a notification that was never opened) would otherwise fire under
        // the open Later picker and send the AI a decline note racing the
        // user's chosen callback. The in-UI countdown below is picker-aware.
        SignalingForegroundService.notifyRingOpened(this@IncomingCallActivity)

        quietRing = quietHoursManager.isQuietNow(currentCallerName)
        Log.i(TAG, "[LAUNCH] IncomingCallActivity created callId=$currentCallId via=$launchSource quiet=$quietRing")

        startRinger()

        setContent {
            AgentCallTheme(darkTheme = true) {
                if (showMicPermissionDenied) {
                    LaunchedEffect(Unit) {
                        Toast.makeText(this@IncomingCallActivity,
                            "Microphone permission required for calls. Grant it in Settings.",
                            Toast.LENGTH_LONG).show()
                        showMicPermissionDenied = false
                    }
                }

                if (showCall) {
                    ActiveCallScreen(callId = currentCallId, context = this@IncomingCallActivity,
                        contextSummary = currentContextSummary.takeIf { it.isNotBlank() },
                        onEndCall = { cid ->
                            startService(Intent(this@IncomingCallActivity, CallService::class.java).apply {
                                action = CallService.ACTION_END_CALL
                                putExtra(CallService.EXTRA_CALL_ID, cid)
                            })
                            finish()
                        })
                } else {
                    IncomingCallScreen(
                        callerName = currentCallerName,
                        contextSummary = currentContextSummary,
                        clientInfoName = currentClientInfoName,
                        quiet = quietRing,
                        onAnswer = {
                            stopRinger()
                            // Backlog item 14 — the FGS ring is cleared by
                            // CallService's ACTION_START_CALL (notifyRingResolved)
                            // after the shared state flips to ANSWERED, so a
                            // stale decline can never cancel an answered call.
                            CallService.cancelIncomingNotification(this@IncomingCallActivity)
                            val svcIntent = Intent(this@IncomingCallActivity, CallService::class.java).apply {
                                action = CallService.ACTION_START_CALL
                                putExtra(CallService.EXTRA_CALL_ID, currentCallId)
                                putExtra(CallService.EXTRA_CALLER_NAME, currentCallerName)
                                putExtra(CallService.EXTRA_CONTEXT_SUMMARY, currentContextSummary)
                                putExtra(CallService.EXTRA_LAUNCH_UI, false)
                                // Debug-only: forward the watchdog-cap override so
                                // adb-driven H2 verification can pass it through the
                                // ring UI (this ROM blocks shell starts of the
                                // non-exported service directly).
                                if (com.agentcall.app.BuildConfig.DEBUG) {
                                    val dbg = intent.getLongExtra(CallService.EXTRA_DEBUG_MAX_CALL_MS, 0L)
                                    if (dbg > 0L) putExtra(CallService.EXTRA_DEBUG_MAX_CALL_MS, dbg)
                                }
                            }
                            if (ContextCompat.checkSelfPermission(this@IncomingCallActivity,
                                    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startService(svcIntent)
                                ringResolved = true
                                showCall = true
                            } else {
                                pendingCallIntent = svcIntent
                                requestRecordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onDecline = {
                            stopRinger()
                            ringResolved = true
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
                            stopRinger()
                            ringResolved = true
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

        lifecycleScope.launch {
            CallEventBus.events.collect { event ->
                if (event is CallEvent.CallEnded) {
                    delay(500)
                    finish()
                }
            }
        }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob())

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val callId = intent.getStringExtra("call_id") ?: return
        Log.d(TAG, "onNewIntent callId=$callId")
        currentCallId = callId
        currentCallerName = intent.getStringExtra("caller_name") ?: "AI Agent"
        currentContextSummary = intent.getStringExtra("context_summary") ?: ""
        currentClientInfoName = intent.getStringExtra("client_info_name")
        // Re-evaluate DND state for the new call (a second call can arrive
        // while the activity is alive).
        quietRing = quietHoursManager.isQuietNow(currentCallerName)
        showCall = false
        showMicPermissionDenied = false
        pendingCallIntent = null
        ringResolved = false
        stopRinger()
        startRinger()
    }

    override fun onPause() {
        super.onPause()
        try {
            mediaPlayer?.apply {
                if (isPlayerValid && isPlaying) pause()
            }
        } catch (_: Exception) { }
        try {
            ringtoneFallback?.stop()
        } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        // Backlog item 14 — a stale FSI can surface after the call already
        // resolved (relaunch from recents, racing duplicate intent): leave
        // the ring UI immediately instead of ringing a dead call.
        if (!showCall) {
            val state = CallStateHolder.state.value
            if (state.status != CallStatus.RINGING || state.callId != currentCallId) {
                Log.i(TAG, "[RESUME] stale FSI for $currentCallId (${state.status}) — finishing")
                stopRinger()
                isProcessing.set(false)
                finish()
                return
            }
        }
        try {
            mediaPlayer?.apply {
                if (isPlayerValid && !isPlaying) start()
            }
        } catch (_: Exception) { }
        ringtoneFallback?.play()
    }

    override fun onDestroy() {
        stopRinger()
        if (ownsRing && !ringResolved) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncomingCallScreen(
    callerName: String,
    contextSummary: String,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onLater: (Int) -> Unit,
    quiet: Boolean = false,
    clientInfoName: String? = null,
) {
    var showLaterPicker by remember { mutableStateOf(false) }
    var selectedMinutes by remember { mutableIntStateOf(10) }

    val timeoutSeconds = 60
    var secondsLeft by remember { mutableIntStateOf(timeoutSeconds) }

    // Auto-decline countdown, paused while the Later picker is open so the
    // user can take their time choosing; resumes with the remaining seconds
    // when the picker closes (LaunchedEffect keyed on showLaterPicker).
    LaunchedEffect(showLaterPicker) {
        if (showLaterPicker) return@LaunchedEffect
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        onDecline()
    }

    val laterOptions = listOf(5 to "5 min", 10 to "10 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour")

    val infiniteTransition = rememberInfiniteTransition(label = "incoming")

    val ring1 by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "ring1")
    val ring2 by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing, delayMillis = 600), RepeatMode.Restart), label = "ring2")
    val ring3 by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing, delayMillis = 1200), RepeatMode.Restart), label = "ring3")

    val pulseDot by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "pulseDot")
    val glowSweep by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(4000, easing = EaseInOutSine), RepeatMode.Reverse), label = "glowSweep")

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        AmbientBackground(
            accentColor = Indigo400,
            secondaryColor = GradientBrandEnd,
            speedMultiplier = 1f,
            density = 1.5f,
        )

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.weight(0.12f))

            Surface(shape = RoundedCornerShape(100.dp), color = Indigo400.copy(alpha = 0.12f)) {
                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(Indigo400.copy(alpha = 0.5f + pulseDot * 0.5f)))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Incoming AI Call",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                        color = Indigo400, fontWeight = FontWeight.SemiBold)
                }
            }

            Spacer(modifier = Modifier.weight(0.08f))

            if (quiet) {
                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = Amber400.copy(alpha = 0.12f),
                    modifier = Modifier.padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.DoNotDisturbOn, "Quiet hours", tint = Amber400, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Quiet hours — ringing silently",
                            style = MaterialTheme.typography.labelSmall,
                            color = Amber300,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Box(
                modifier = Modifier.size(170.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    val ringRadius = r * (0.5f + ring1 * 0.5f)
                    drawCircle(color = Indigo400.copy(alpha = 0.12f * (1f - ring1)), radius = ringRadius)
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    val ringRadius = r * (0.5f + ring2 * 0.5f)
                    drawCircle(color = Indigo400.copy(alpha = 0.08f * (1f - ring2)), radius = ringRadius)
                }
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val r = size.minDimension / 2
                    val ringRadius = r * (0.5f + ring3 * 0.5f)
                    drawCircle(color = Indigo400.copy(alpha = 0.05f * (1f - ring3)), radius = ringRadius)
                }

                // Avatar 120dp with a thin countdown progress arc around it;
            // the rotating sweep arc gives way to the meaningful readout.
                Box(modifier = Modifier.size(120.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Indigo500, GradientBrandEnd))),
                    contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(120.dp)) {
                        drawCircle(color = Color.White.copy(alpha = 0.08f + glowSweep * 0.08f),
                            radius = size.minDimension / 2 * 0.85f)
                    }
                    Icon(Icons.Default.Call, "Incoming call", modifier = Modifier.size(60.dp), tint = Slate50)
                }
                Canvas(modifier = Modifier.size(128.dp)) {
                    val progress = secondsLeft / timeoutSeconds.toFloat()
                    drawArc(color = Slate400.copy(alpha = 0.55f),
                        startAngle = -90f, sweepAngle = 360f * progress, useCenter = false,
                        style = Stroke(width = 2.dp.toPx()),
                        topLeft = Offset(4f, 4f),
                        size = androidx.compose.ui.geometry.Size(size.width - 8f, size.height - 8f))
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(callerName, style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground)

            Spacer(modifier = Modifier.height(8.dp))
            ClientBadge(clientInfoName = clientInfoName)

            Spacer(modifier = Modifier.height(16.dp))

            if (contextSummary.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, "Call context", modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(contextSummary, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Start)
                    }
                }
            }

            Spacer(modifier = Modifier.weight(0.1f))

            if (showLaterPicker) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 16.dp)) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Call back in...",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { showLaterPicker = false }) {
                                Icon(Icons.Default.Close, "Back to call", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState()),
                        ) {
                        laterOptions.forEach { (mins, label) ->
                            val isSelected = selectedMinutes == mins
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable { selectedMinutes = mins },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Indigo800 else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .border(
                                                2.dp,
                                                if (isSelected) Indigo400 else MaterialTheme.colorScheme.onSurfaceVariant,
                                                CircleShape,
                                            )
                                            .background(
                                                if (isSelected) Indigo400 else Color.Transparent,
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            Box(Modifier.size(8.dp).background(Color.White, CircleShape))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(label, style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = { onLater(selectedMinutes) },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo600)) {
                            Text("Call me back in $selectedMinutes min")
                        }
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionCircle(
                        icon = Icons.AutoMirrored.Filled.PhoneForwarded,
                        label = "Decline", iconTint = Red400, labelColor = Red400,
                        bgColor = GlassRed, size = 64.dp, onClick = onDecline,
                    )

                    ActionCircle(
                        icon = Icons.Default.Call, label = "Answer",
                        iconTint = Slate50, labelColor = Green400,
                        bgColor = Color.Transparent, size = 76.dp, onClick = onAnswer,
                        isSolid = true, solidColor = Green500,
                    )

                    ActionCircle(
                        icon = Icons.Default.Schedule, label = "Later",
                        iconTint = Indigo300, labelColor = Indigo300,
                        bgColor = GlassWhite, size = 64.dp,
                        onClick = { showLaterPicker = true },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            if (!showLaterPicker) {
                Text("Answer, decline, or schedule for later",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Auto-decline in ${secondsLeft}s",
                    style = MonoLabel,
                    color = Slate400,
                    textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.weight(0.05f))
        }
    }
}