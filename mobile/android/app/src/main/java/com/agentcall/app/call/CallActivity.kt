package com.agentcall.app.call

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.theme.*
import kotlinx.coroutines.delay

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callId = intent.getStringExtra("call_id") ?: run { finish(); return }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
        }

        setContent {
            AgentCallTheme(darkTheme = true) {
                ActiveCallScreen(callId = callId, context = this@CallActivity,
                    onEndCall = {
                        startService(Intent(this@CallActivity, CallService::class.java).apply {
                            action = CallService.ACTION_END_CALL
                        })
                        finish()
                    })
            }
        }
    }
}

@Composable
fun ActiveCallScreen(
    callId: String,
    context: Context,
    onEndCall: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var showContext by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        viewModel.connect(callId)
        while (true) { delay(250); viewModel.tick() }
    }

    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    LaunchedEffect(state.isConnected) {
        if (state.isConnected) {
            delay(4000)
            showContext = false
        }
    }

    val minutes = state.elapsedSeconds / 60
    val seconds = state.elapsedSeconds % 60
    val timerText = "%02d:%02d".format(minutes, seconds)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    Box(modifier = Modifier.fillMaxSize().background(Slate900)) {
        // Ambient background
        AmbientBackground(
            accentColor = Indigo400,
            secondaryColor = Indigo600,
            speedMultiplier = 1f,
            density = if (state.isAiSpeaking || state.isRecording) 1.2f else 0.6f,
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // ── Status Bar ─────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val dotAnim by infiniteTransition.animateFloat(0f, 1f,
                    infiniteRepeatable(tween(1200, easing = EaseInOutSine), RepeatMode.Reverse), label = "statusDot")
                Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                    .background(if (state.isConnected) Green500.copy(alpha = 0.5f + dotAnim * 0.5f) else Amber400))
                Spacer(modifier = Modifier.width(8.dp))
                Text(state.statusText, style = MaterialTheme.typography.labelSmall,
                    color = if (state.isConnected) Green500 else Amber400,
                    modifier = Modifier.weight(1f))

                Text(timerText, fontSize = 18.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Light, color = Slate50, letterSpacing = 1.sp)
            }

            // ── Context Banner ─────────────────────────
            AnimatedVisibility(visible = showContext && state.callContext.summary.isNotBlank(),
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.8f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut()) {
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(16.dp), color = Indigo900.copy(alpha = 0.6f),
                    tonalElevation = 4.dp
                ) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Default.Info, "AI calling about", modifier = Modifier.size(18.dp), tint = Indigo300)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text("AI is calling about:",
                                style = MaterialTheme.typography.labelSmall, color = Indigo300,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(state.callContext.summary,
                                style = MaterialTheme.typography.bodyMedium, color = Slate200)
                        }
                    }
                }
            }

            // ── Chat Messages ──────────────────────────
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.messages, key = { it.id }) { msg ->
                    MessageBubble(msg = msg, isAi = msg.role == "ai")
                }

                if (state.isPaused) {
                    item(key = "paused") {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp), color = Amber400.copy(alpha = 0.12f),
                        ) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.PauseCircle, "Call paused", tint = Amber400,
                                    modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Call paused — press Record when you're ready",
                                    style = MaterialTheme.typography.labelMedium, color = Amber300)
                            }
                        }
                    }
                }
            }

            // ── Waveform Visualization ─────────────────
            WaveformBar(
                levels = state.waveformLevels,
                isActive = state.isRecording || state.isAiSpeaking,
                isRecording = state.isRecording,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ── Control Buttons ────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallControl(
                    icon = if (state.isRecording) Icons.Default.StopCircle else Icons.Default.Mic,
                    label = if (state.isRecording) "Stop" else "Record",
                    tint = if (state.isRecording) Red400 else Slate50,
                    bgColor = if (state.isRecording) GlassRed else GlassWhite,
                    bgGradient = if (state.isRecording) listOf(Red400.copy(alpha = 0.2f), Red500.copy(alpha = 0.1f)) else null,
                    onClick = {
                        context.startService(Intent(context, CallService::class.java).apply {
                            action = if (state.isRecording) CallService.ACTION_STOP_RECORDING
                            else CallService.ACTION_START_RECORDING
                        })
                        viewModel.setRecording(!state.isRecording)
                    })

                CallControl(
                    icon = Icons.Default.VolumeUp, label = "Speaker",
                    tint = Slate50, bgColor = GlassWhite, onClick = {
                        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        audioManager.isSpeakerphoneOn = !audioManager.isSpeakerphoneOn
                    })

                CallControl(
                    icon = Icons.Default.Replay, label = "Repeat",
                    tint = Slate50, bgColor = GlassWhite, onClick = {
                        context.startService(Intent(context, CallService::class.java).apply {
                            action = CallService.ACTION_REPEAT_LAST
                        })
                    })
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── End Call Button ────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                var isPressed by remember { mutableStateOf(false) }
                val pressScale by animateFloatAsState(
                    targetValue = if (isPressed) 0.92f else 1f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f), label = "endCallPress"
                )
                Button(
                    onClick = onEndCall,
                    modifier = Modifier.size(64.dp).scale(pressScale), shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Red500),
                    contentPadding = PaddingValues(0.dp),
                    interactionSource = remember { MutableInteractionSource() },
                ) {
                    Icon(Icons.Default.PhoneForwarded, "End", modifier = Modifier.size(26.dp), tint = Slate50)
                }
                Text("End", style = MaterialTheme.typography.labelSmall, color = Red400, fontWeight = FontWeight.Medium)
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun MessageBubble(msg: ChatBubble, isAi: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(if (isAi) 50 else 100)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { if (isAi) -it / 3 else it / 2 },
            animationSpec = spring(dampingRatio = 0.65f, stiffness = 250f)
        ) + fadeIn(animationSpec = tween(250)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isAi) Arrangement.Start else Arrangement.End,
        ) {
            if (isAi) {
                // AI avatar
                val avatarGradient = Brush.linearGradient(listOf(GradientBrandStart, GradientBrandEnd))
                Surface(
                    modifier = Modifier.size(32.dp),
                    shape = CircleShape,
                    color = Color.Transparent,
                ) {
                    Box(
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(avatarGradient),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.SmartToy, "AI avatar", modifier = Modifier.size(16.dp), tint = Slate50)
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
            }

            Surface(
                shape = RoundedCornerShape(
                    topStart = if (isAi) 4.dp else 16.dp,
                    topEnd = if (isAi) 16.dp else 4.dp,
                    bottomStart = 16.dp, bottomEnd = 16.dp,
                ),
                color = if (isAi) Slate800 else Indigo800,
                tonalElevation = if (isAi) 0.dp else 2.dp,
            ) {
                Column(modifier = Modifier.padding(12.dp).widthIn(max = 280.dp)) {
                    if (isAi) {
                        Text("AI", style = MaterialTheme.typography.labelSmall,
                            color = Indigo400, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(msg.text, style = MaterialTheme.typography.bodyMedium, color = if (isAi) Slate100 else Slate50)
                }
            }

            if (!isAi) {
                Spacer(modifier = Modifier.width(8.dp))
                // User avatar
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(Slate700),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Person, "You", modifier = Modifier.size(18.dp), tint = Slate400)
                }
            }
        }
    }
}

@Composable
private fun WaveformBar(levels: List<Float>, isActive: Boolean, isRecording: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "waveformGlow")
    val glowAlpha by infiniteTransition.animateFloat(0.4f, 0.8f,
        infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse), label = "glow")

    val waveformColor = when {
        isRecording -> WaveformActive
        else -> WaveformActive
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 20.dp)) {
        val barWidth = size.width / (levels.size * 2 - 1)
        val centerY = size.height / 2
        levels.forEachIndexed { i, level ->
            val barHeight = level * size.height * 0.8f
            val x = i * barWidth * 2
            val baseAlpha = 0.4f + level * 0.6f
            val alpha = if (isActive) (baseAlpha * (0.8f + glowAlpha * 0.2f)).coerceIn(0f, 1f) else baseAlpha * 0.5f
            drawRoundRect(
                color = waveformColor.copy(alpha = alpha),
                topLeft = Offset(x, centerY - barHeight / 2),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
            )
        }
    }
}

@Composable
private fun CallControl(
    icon: ImageVector, label: String, tint: Color, bgColor: Color,
    bgGradient: List<Color>? = null, onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f), label = "callControlPress"
    )

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = bgColor,
            tonalElevation = 0.dp,
            interactionSource = interactionSource,
        ) {
            Box(
                modifier = Modifier.size(52.dp).scale(pressScale),
                contentAlignment = Alignment.Center,
            ) {
                if (bgGradient != null) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(Brush.linearGradient(bgGradient))
                    }
                }
                Icon(icon, label, modifier = Modifier.size(24.dp), tint = tint)
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = Slate400)
    }
}
