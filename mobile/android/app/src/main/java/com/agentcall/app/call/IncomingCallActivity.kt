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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentcall.app.R
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
        val isDebugForce = intent.getBooleanExtra("debug_force", false)
        // Backlog item 14 — stale-FSI guard (skipped for debug_force visual QA)
        if (!isDebugForce) {
            val state = CallStateHolder.state.value
            if (state.status != CallStatus.RINGING || state.callId != currentCallId) {
                Log.i(TAG, "[LAUNCH] stale FSI for $currentCallId (${state.status}) — finishing")
                isProcessing.set(false)
                finish()
                return
            }
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
        if (!showCall && !intent.getBooleanExtra("debug_force", false)) {
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

    val pulseDot by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "pulseDot")
    // Gentle breathing on the brand mark while the phone rings.
    val logoBreath by infiniteTransition.animateFloat(0.97f, 1.03f,
        infiniteRepeatable(tween(1600, easing = EaseInOutSine), RepeatMode.Reverse), label = "logoBreath")

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Premium atmospheric: subtle brand-purple vertical wash + radial halo behind avatar
        // Keeps dark identity, avoids flat black; very low alpha so it never reads as neon.
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    colors = listOf(BrandPurple.copy(alpha = 0.09f), Color.Transparent),
                    startY = 0f,
                    endY = 820f
                )
            )
        )
        AmbientBackground(
            accentColor = BrandPurple,
            secondaryColor = BrandPurple.copy(alpha = 0.55f),
            speedMultiplier = 0.7f,
            density = 0.9f,
        )
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(BrandPurple.copy(alpha = 0.13f), Color.Transparent),
                    center = Offset(size.width * 0.5f, size.height * 0.36f),
                    radius = size.width * 0.62f
                ),
                radius = size.width * 0.62f,
                center = Offset(size.width * 0.5f, size.height * 0.36f)
            )
        }

        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.L), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(modifier = Modifier.weight(0.12f))

            Surface(shape = RoundedCornerShape(100.dp), color = BrandPurple.copy(alpha = 0.13f)) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                        .background(BrandPurple.copy(alpha = 0.55f + pulseDot * 0.45f)))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Incoming AI Call",
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.sp),
                        color = BrandPurple, fontWeight = FontWeight.SemiBold)
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
                Spacer(modifier = Modifier.height(12.dp))
            }            // AgentCall brand mark — the same asset Home uses, presented as a
            // brand logo (bare, over the dark ambient halo) and NOT inside a
            // purple avatar disc: the brand logo is not an AI avatar. No
            // enclosing rings; the countdown lives in "Auto-decline in Ns".
            Image(
                painter = painterResource(R.drawable.agentcall_logo_transparent),
                contentDescription = "AgentCall",
                modifier = Modifier
                    .size(120.dp)
                    .scale(logoBreath),
            )

            Spacer(modifier = Modifier.height(12.dp))
            Text(callerName, style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.SemiBold)

            Spacer(modifier = Modifier.height(6.dp))
            Text(if (clientInfoName != null) "AI Agent \u00B7 via $clientInfoName" else "AI Agent",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(8.dp))
            ClientBadge(clientInfoName = clientInfoName)

            Spacer(modifier = Modifier.height(12.dp))

            if (contextSummary.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(Radii.Panel),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, "Call context", modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(12.dp))
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
                            modifier = Modifier.padding(12.dp),
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
                        Spacer(modifier = Modifier.height(12.dp))
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
                                color = if (isSelected) BrandPurple.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .border(
                                                2.dp,
                                                if (isSelected) BrandPurple else MaterialTheme.colorScheme.onSurfaceVariant,
                                                CircleShape,
                                            )
                                            .background(
                                                if (isSelected) BrandPurple else Color.Transparent,
                                                CircleShape,
                                            ),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        if (isSelected) {
                                            Box(Modifier.size(8.dp).background(Color.White, CircleShape))
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(label, style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(onClick = { onLater(selectedMinutes) },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandPurple)) {
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
                        shadowElevation = 0.dp,
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

            Spacer(modifier = Modifier.height(12.dp))
            if (!showLaterPicker) {
                Text("Answer, decline, or schedule for later",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
                Spacer(modifier = Modifier.height(12.dp))
                Text("Auto-decline in ${secondsLeft}s",
                    style = MonoLabel,
                    color = Slate400,
                    textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.weight(0.05f))
        }
    }
}