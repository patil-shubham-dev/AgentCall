package com.agentcall.app.call

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.agentcall.app.settings.MessageTemplates
import com.agentcall.app.settings.QuietHoursManager
import com.agentcall.app.ui.composables.AgentCallAvatar
import com.agentcall.app.ui.composables.FlowFieldBackground
import com.agentcall.app.ui.theme.*
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

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
        // The ring UI owns the timeout now: the service's 60s fallback (meant
        // for a notification that was never opened) would otherwise fire under
        // the open Later picker and send the AI a decline note racing the
        // user's chosen callback. The in-UI countdown is picker-aware.
        SignalingForegroundService.notifyRingOpened(this@IncomingCallActivity)

        quietRing = quietHoursManager.isQuietNow(currentCallerName)
        Log.i(TAG, "[LAUNCH] IncomingCallActivity created callId=$currentCallId via=$launchSource quiet=$quietRing")

        // The notification already started the ring. This is a no-op for the
        // same call and covers direct/debug/deep-link launches that never
        // posted one. Never a second player.
        IncomingCallRinger.start(this, currentCallId, quietRing)

        // One ring truth for both surfaces: when the notification's Answer (or
        // Decline) resolves this call while the ring UI is visible, follow it
        // instead of leaving a second call screen behind.
        lifecycleScope.launch {
            CallStateHolder.state.collect { state ->
                if (state.callId != currentCallId) return@collect
                when (state.status) {
                    CallStatus.ANSWERED -> if (!answeredInUi && !showCall) {
                        Log.i(TAG, "[STATE] answered elsewhere for $currentCallId — closing ring UI")
                        finish()
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
                    animationSpec = tween(durationMillis = 340),
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
                            quiet = quietRing,
                            onAnswer = {
                                ringResolved = true
                                answeredInUi = true
                                // Ring resolved by answer: disarm the exact-alarm
                                // timeout (no-op if the FGS job path owns it).
                                RingTimeoutScheduler.cancel(this@IncomingCallActivity, currentCallId)
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
                                if (ContextCompat.checkSelfPermission(
                                        this@IncomingCallActivity,
                                        Manifest.permission.RECORD_AUDIO,
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    startService(svcIntent)
                                    showCall = true
                                } else {
                                    pendingCallIntent = svcIntent
                                    requestRecordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            },
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
        answeredInUi = false
        IncomingCallRinger.start(this, currentCallId, quietRing)
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
                isProcessing.set(false)
                finish()
            }
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

/**
 * Incoming call surface:
 * status → avatar → caller → context → one swipe control → Later.
 *
 * The hierarchy above the control stays fixed while the countdown runs, so the
 * swipe target never moves under the user's finger.
 */
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
    var resolved by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableIntStateOf(TIMEOUT_SECONDS) }

    // Auto-decline countdown, paused while the Later picker is open so the
    // user can take their time choosing; resumes with the remaining seconds
    // when the picker closes. Cancelled as soon as the call is resolved.
    LaunchedEffect(showLaterPicker, resolved) {
        if (showLaterPicker || resolved) return@LaunchedEffect
        while (secondsLeft > 0 && !resolved) {
            delay(1_000)
            secondsLeft--
        }
        if (!resolved) {
            resolved = true
            onDecline()
        }
    }

    val laterOptions = listOf(
        5 to "5 minutes",
        10 to "10 minutes",
        15 to "15 minutes",
        30 to "30 minutes",
        60 to "1 hour",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Short screens (split screen, landscape) switch to a smaller, scrollable
        // composition so the swipe target is never pushed off-screen.
        val compact = maxHeight < 560.dp
        val veryCompact = maxHeight < 460.dp

        FlowFieldBackground(modifier = Modifier.fillMaxSize())

        // Keep the ambient field visible while anchoring the text and controls
        // in a calmer, darker band.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to MaterialTheme.colorScheme.background.copy(alpha = 0.18f),
                        0.26f to Color.Transparent,
                        0.70f to Color.Transparent,
                        1f to MaterialTheme.colorScheme.background.copy(alpha = 0.72f),
                    ),
                ),
        )

        Column(
            modifier = if (compact) {
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
            } else {
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp)
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = if (compact) Modifier.height(12.dp) else Modifier.weight(0.10f))

            IncomingStatusPill(quiet = quiet)

            Spacer(modifier = if (compact) Modifier.height(14.dp) else Modifier.height(24.dp))

            RingingAvatar(size = if (compact) 76.dp else 108.dp)

            Spacer(modifier = if (compact) Modifier.height(12.dp) else Modifier.height(22.dp))

            Text(
                callerName,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 24.sp,
                    letterSpacing = (-0.02).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (clientInfoName != null) "AgentCall AI via $clientInfoName" else "AgentCall AI",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (contextSummary.isNotBlank() && !veryCompact) {
                Spacer(modifier = Modifier.height(if (compact) 12.dp else 18.dp))
                Text(
                    contextSummary,
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 21.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = if (compact) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }

            Spacer(modifier = if (compact) Modifier.height(22.dp) else Modifier.weight(1f))

            SwipeCallControl(
                onAnswer = {
                    if (!resolved) {
                        resolved = true
                        onAnswer()
                    }
                },
                onDecline = {
                    if (!resolved) {
                        resolved = true
                        onDecline()
                    }
                },
                enabled = !resolved,
            )

            TextButton(
                onClick = { if (!resolved) showLaterPicker = true },
                enabled = !resolved,
                modifier = Modifier.heightIn(min = 48.dp).padding(top = 2.dp),
            ) {
                Text(
                    "Later",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    modifier = Modifier.size(13.dp),
                )
                Spacer(modifier = Modifier.width(5.dp))
                Text(
                    "Auto-decline in ${secondsLeft}s",
                    style = MonoLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                )
            }

            Spacer(modifier = if (compact) Modifier.height(16.dp) else Modifier.weight(0.05f))
        }

        if (showLaterPicker) {
            ModalBottomSheet(
                onDismissRequest = { showLaterPicker = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = Radii.Sheet, topEnd = Radii.Sheet),
                tonalElevation = 0.dp,
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom),
                        )
                        .padding(horizontal = 24.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = BrandPurpleLight,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                "Call back later",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "The AI will call you again at this time.",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                    laterOptions.forEach { (minutes, label) ->
                        val selected = selectedMinutes == minutes
                        Surface(
                            onClick = { selectedMinutes = minutes },
                            shape = RoundedCornerShape(Radii.Card),
                            color = if (selected) AccentTint else Color.Transparent,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .padding(horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = selected,
                                    onClick = { selectedMinutes = minutes },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = BrandPurpleLight,
                                        unselectedColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = {
                            if (!resolved) {
                                resolved = true
                                showLaterPicker = false
                                onLater(selectedMinutes)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(Radii.Field),
                        colors = ButtonDefaults.buttonColors(containerColor = BrandPurple),
                    ) {
                        Text("Call me back in $selectedMinutes min", color = OnAccent)
                    }
                    TextButton(
                        onClick = { showLaterPicker = false },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            "Back to call",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun IncomingStatusPill(quiet: Boolean) {
    Surface(
        shape = RoundedCornerShape(Radii.Pill),
        color = if (quiet) WarningBg else BrandPurple.copy(alpha = 0.13f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (quiet) {
                Icon(
                    Icons.Default.DoNotDisturbOn,
                    contentDescription = null,
                    tint = AlertAmber,
                    modifier = Modifier.size(13.dp),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(BrandPurpleLight),
                )
            }
            Spacer(modifier = Modifier.width(7.dp))
            Text(
                if (quiet) "AI voice call · silent" else "AI voice call",
                style = MaterialTheme.typography.labelMedium,
                color = if (quiet) AlertAmber else BrandPurpleLight,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/**
 * Agent avatar with a slow expanding ring. Motion communicates "it is ringing"
 * and stops entirely when system animations are disabled.
 */
@Composable
private fun RingingAvatar(size: Dp) {
    val context = LocalContext.current
    val reducedMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            ) == 0f
        }.getOrDefault(false)
    }
    val transition = rememberInfiniteTransition(label = "ringingAvatar")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2_400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ringPulse",
    )

    // The rings expand to 78dp on the 108dp avatar; the box is sized so the
    // largest ring is never clipped by the Canvas bounds.
    Box(
        modifier = Modifier.size(size * 1.45f),
        contentAlignment = Alignment.Center,
    ) {
        if (!reducedMotion) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val core = size.toPx()
                repeat(2) { index ->
                    val progress = (pulse + index * 0.5f) % 1f
                    val radius = core / 2f + progress * core * 0.20f
                    drawCircle(
                        color = BrandPurple.copy(alpha = (1f - progress) * 0.16f),
                        radius = radius,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
        }
        AgentCallAvatar(size = size, contentDescription = "AgentCall AI")
    }
}

private const val TIMEOUT_SECONDS = 60

/** Amber with enough contrast on the dark ground (the alias is historical). */
private val AlertAmber = Color(0xFFE7C169)
