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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.settings.CallerTuneManager
import com.agentcall.app.settings.MessageTemplates
import com.agentcall.app.ui.composables.AmbientBackground
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
    private var mediaPlayer: MediaPlayer? = null
    private var ringtoneFallback: Ringtone? = null
    @Volatile private var isPlayerValid = false

    private var showCall by mutableStateOf(false)
    private var showMicPermissionDenied by mutableStateOf(false)
    private var pendingCallIntent: Intent? = null
    private var currentCallId by mutableStateOf("")
    private var currentCallerName by mutableStateOf("AI Agent")
    private var currentContextSummary by mutableStateOf("")
    private var currentTimeoutSeconds by mutableStateOf(60)
    private val requestRecordAudioLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            pendingCallIntent?.let {
                pendingCallIntent = null
                startService(it)
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

    private fun startRinger() {
        try {
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

        if (!isProcessing.compareAndSet(false, true)) {
            finish()
            return
        }

        currentCallId = intent.getStringExtra("call_id") ?: run { isProcessing.set(false); finish(); return }
        currentCallerName = intent.getStringExtra("caller_name") ?: "AI Agent"
        currentContextSummary = intent.getStringExtra("context_summary") ?: ""

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
                        onEndCall = {
                            startService(Intent(this@IncomingCallActivity, CallService::class.java).apply {
                                action = CallService.ACTION_END_CALL
                                putExtra(CallService.EXTRA_CALL_ID, currentCallId)
                            })
                            finish()
                        })
                } else {
                    IncomingCallScreen(
                        callerName = currentCallerName,
                        contextSummary = currentContextSummary,
                        onAnswer = {
                            stopRinger()
                            SignalingForegroundService.notifyRingResolved(this@IncomingCallActivity)
                            CallService.cancelIncomingNotification(this@IncomingCallActivity)
                            val svcIntent = Intent(this@IncomingCallActivity, CallService::class.java).apply {
                                action = CallService.ACTION_START_CALL
                                putExtra(CallService.EXTRA_CALL_ID, currentCallId)
                                putExtra(CallService.EXTRA_CALLER_NAME, currentCallerName)
                                putExtra(CallService.EXTRA_CONTEXT_SUMMARY, currentContextSummary)
                            }
                            if (ContextCompat.checkSelfPermission(this@IncomingCallActivity,
                                    Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startService(svcIntent)
                                showCall = true
                            } else {
                                pendingCallIntent = svcIntent
                                requestRecordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        onDecline = {
                            stopRinger()
                            SignalingForegroundService.notifyRingResolved(this@IncomingCallActivity)
                            CallService.cancelIncomingNotification(this@IncomingCallActivity)
                            startService(Intent(this@IncomingCallActivity, CallService::class.java).apply {
                                action = CallService.ACTION_CANCEL_CALL
                                putExtra(CallService.EXTRA_CALL_ID, currentCallId)
                                putExtra(CallService.EXTRA_TEXT, MessageTemplates.declineMessage(this@IncomingCallActivity))
                            })
                            isProcessing.set(false)
                            finish()
                        },
                        onLater = { minutes ->
                            stopRinger()
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
        showCall = false
        showMicPermissionDenied = false
        pendingCallIntent = null
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
        try {
            mediaPlayer?.apply {
                if (isPlayerValid && !isPlaying) start()
            }
        } catch (_: Exception) { }
        ringtoneFallback?.play()
    }

    override fun onDestroy() {
        stopRinger()
        super.onDestroy()
        isProcessing.set(false)
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
) {
    var showLaterPicker by remember { mutableStateOf(false) }
    var selectedMinutes by remember { mutableIntStateOf(10) }

    val timeoutSeconds = 60
    var secondsLeft by remember { mutableIntStateOf(timeoutSeconds) }

    LaunchedEffect(Unit) {
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft--
        }
        if (!showLaterPicker) onDecline()
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

        Column(modifier = Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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

                Canvas(modifier = Modifier.size(140.dp)) {
                    val sweep = 30f + glowSweep * 20f
                    val rotation = glowSweep * 360f
                    drawArc(color = Indigo400.copy(alpha = 0.2f),
                        startAngle = rotation, sweepAngle = sweep, useCenter = false,
                        style = Stroke(width = 2.5f),
                        topLeft = Offset(4f, 4f),
                        size = androidx.compose.ui.geometry.Size(size.width - 8f, size.height - 8f))
                }

                Box(modifier = Modifier.size(110.dp).clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Indigo500, GradientBrandEnd))),
                    contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.size(110.dp)) {
                        drawCircle(color = Color.White.copy(alpha = 0.08f + glowSweep * 0.08f),
                            radius = size.minDimension / 2 * 0.85f)
                    }
                    Icon(Icons.Default.Call, "Incoming call", modifier = Modifier.size(54.dp), tint = Slate50)
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Text(callerName, style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)

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
                Surface(
                    modifier = Modifier.fillMaxWidth(0.85f),
                    shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                ) {
                    Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Call back in...",
                            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(16.dp))
                        laterOptions.forEach { (mins, label) ->
                            val isSelected = selectedMinutes == mins
                            Surface(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                onClick = { selectedMinutes = mins },
                                shape = RoundedCornerShape(12.dp),
                                color = if (isSelected) Indigo800 else MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(selected = isSelected, onClick = { selectedMinutes = mins },
                                        colors = RadioButtonDefaults.colors(selectedColor = Indigo400))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(label, style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurface)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { onLater(selectedMinutes) },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo600)) {
                            Text("Call me back in $selectedMinutes min")
                        }
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    ActionButton(
                        icon = Icons.AutoMirrored.Filled.PhoneForwarded,
                        label = "Decline", iconTint = Red400, labelColor = Red400,
                        bgColor = GlassRed, size = 64.dp, onClick = onDecline,
                    )

                    ActionButton(
                        icon = Icons.Default.Call, label = "Answer",
                        iconTint = Slate50, labelColor = Green400,
                        bgColor = Color.Transparent, size = 72.dp, onClick = onAnswer,
                        isSolid = true, solidColor = Green500,
                    )

                    ActionButton(
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center)
            }
            Spacer(modifier = Modifier.weight(0.05f))
        }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector, label: String, iconTint: Color, labelColor: Color,
    bgColor: Color, size: Dp, onClick: () -> Unit,
    isSolid: Boolean = false, solidColor: Color = Green500,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f), label = "pressScale"
    )
    val pressElevation by animateDpAsState(
        targetValue = if (isPressed) 4.dp else 8.dp,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = 600f), label = "pressElevation"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isSolid) {
            Button(
                onClick = onClick, modifier = Modifier.size(size).scale(pressScale),
                shape = CircleShape, colors = ButtonDefaults.buttonColors(containerColor = solidColor),
                contentPadding = PaddingValues(0.dp), interactionSource = interactionSource,
                elevation = ButtonDefaults.buttonElevation(defaultElevation = pressElevation),
            ) {
                Icon(icon, label, modifier = Modifier.size(size * 0.44f), tint = iconTint)
            }
        } else {
            Surface(
                onClick = onClick, shape = CircleShape, color = bgColor,
                tonalElevation = 0.dp, interactionSource = interactionSource,
                shadowElevation = pressElevation,
            ) {
                Box(modifier = Modifier.size(size).scale(pressScale), contentAlignment = Alignment.Center) {
                    Icon(icon, label, modifier = Modifier.size(size * 0.44f), tint = iconTint)
                }
            }
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = labelColor, fontWeight = FontWeight.Medium)
    }
}