package com.agentcall.app.call

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.ui.theme.*
import kotlinx.coroutines.delay

class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val callId = intent.getStringExtra("call_id") ?: run { finish(); return }
        setContent {
            AgentCallTheme {
                val context = this@CallActivity
                ActiveCallScreen(
                    callId = callId,
                    context = context,
                    onEndCall = {
                        val intent = Intent(this@CallActivity, CallService::class.java).apply {
                            action = CallService.ACTION_END_CALL
                        }
                        this@CallActivity.startService(intent)
                        finish()
                    },
                )
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

    LaunchedEffect(Unit) {
        while (true) {
            delay(250)
            viewModel.tick()
        }
    }

    val minutes = state.elapsedSeconds / 60
    val seconds = state.elapsedSeconds % 60
    val timerText = "%02d:%02d".format(minutes, seconds)

    val infiniteTransition = rememberInfiniteTransition(label = "callPulse")
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(3000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breathing",
    )

    val connectionLabel = when (state.status) {
        CallConnectionStatus.CONNECTING -> "Connecting..."
        CallConnectionStatus.CONNECTED -> "Connected"
        CallConnectionStatus.RECONNECTING -> "Reconnecting..."
        CallConnectionStatus.DISCONNECTED -> "Disconnected"
        CallConnectionStatus.FAILED -> "Failed"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900),
    ) {
        // Background gradient orbs
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Indigo500.copy(alpha = 0.08f),
                radius = size.width * 0.5f,
                center = Offset(size.width * 0.5f, size.height * 0.2f),
            )
            drawCircle(
                color = GradientBrandEnd.copy(alpha = 0.05f),
                radius = size.width * 0.7f,
                center = Offset(size.width * 0.3f, size.height * 0.6f),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.weight(0.08f))

            // ── Connection Status ──────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val dotColor = when (state.status) {
                    CallConnectionStatus.CONNECTED -> Green500
                    CallConnectionStatus.RECONNECTING -> Amber400
                    CallConnectionStatus.CONNECTING -> Indigo400
                    else -> Red400
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = connectionLabel,
                    style = MaterialTheme.typography.labelMedium,
                    color = dotColor,
                )
            }

            Spacer(modifier = Modifier.weight(0.06f))

            // ── Timer ──────────────────────────────
            Text(
                text = timerText,
                fontSize = 64.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Light,
                color = Slate50,
                letterSpacing = 2.sp,
            )

            Spacer(modifier = Modifier.weight(0.04f))

            // ── AI Avatar ──────────────────────────
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .graphicsLayer(scaleX = breathingScale, scaleY = breathingScale)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(GradientBrandStart, GradientBrandEnd),
                        ),
                    ),
                contentAlignment = Alignment.Center,
            ) {Icon(
                        imageVector = Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Slate50,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "AI Agent",
                style = MaterialTheme.typography.headlineSmall,
                color = Slate50,
                fontWeight = FontWeight.SemiBold,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Waiting for your response",
                style = MaterialTheme.typography.bodyMedium,
                color = Slate400,
            )

            Spacer(modifier = Modifier.weight(0.06f))

            // ── Waveform Visualization ─────────────
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
            ) {
                val barWidth = size.width / (state.waveformLevels.size * 2 - 1)
                val centerY = size.height / 2
                state.waveformLevels.forEachIndexed { i, level ->
                    val barHeight = level * size.height * 0.8f
                    val x = i * barWidth * 2
                    val color = if (state.isMuted) WaveformMuted
                    else WaveformActive
                    drawRoundRect(
                        color = color.copy(alpha = 0.4f + level * 0.6f),
                        topLeft = Offset(x, centerY - barHeight / 2),
                        size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2),
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── Connection Quality ─────────────────
            Row(
                modifier = Modifier.fillMaxWidth(0.6f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val qualityLabel = when {
                    state.connectionQuality > 0.7f -> "Excellent"
                    state.connectionQuality > 0.4f -> "Good"
                    else -> "Poor"
                }
                val qualityColor = when {
                    state.connectionQuality > 0.7f -> Green400
                    state.connectionQuality > 0.4f -> Amber400
                    else -> Red400
                }
                repeat(5) { i ->
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(if (i < (state.connectionQuality * 5).toInt()) 16.dp else 8.dp)
                            .padding(horizontal = 2.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                if (i < (state.connectionQuality * 5).toInt()) qualityColor
                                else Slate700
                            ),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = qualityLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = qualityColor,
                )
            }

            Spacer(modifier = Modifier.weight(0.08f))

            // ── Call Controls ──────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(32.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CallControl(
                    icon = if (state.isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                    label = if (state.isMuted) "Unmute" else "Mute",
                    tint = if (state.isMuted) Amber400 else Slate50,
                    bgColor = if (state.isMuted) GlassAmber else GlassWhite,
                    onClick = { viewModel.toggleMute() },
                )
                CallControl(
                    icon = Icons.Default.VolumeUp,
                    label = "Speaker",
                    tint = if (state.isSpeakerOn) Indigo400 else Slate50,
                    bgColor = if (state.isSpeakerOn) GlassIndigo else GlassWhite,
                    onClick = { viewModel.toggleSpeaker() },
                )
                CallControl(
                    icon = Icons.Default.Keyboard,
                    label = "Keypad",
                    tint = Slate50,
                    bgColor = GlassWhite,
                    onClick = { },
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // ── End Call Button ────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onEndCall,
                    modifier = Modifier.size(72.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Red500,
                    ),
                    contentPadding = PaddingValues(0.dp),
                    elevation = ButtonDefaults.buttonElevation(
                        defaultElevation = 8.dp,
                        pressedElevation = 4.dp,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.PhoneForwarded,
                        contentDescription = "End Call",
                        modifier = Modifier.size(28.dp),
                        tint = Slate50,
                    )
                }
                Text(
                    text = "End",
                    style = MaterialTheme.typography.labelMedium,
                    color = Red400,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun CallControl(
    icon: ImageVector,
    label: String,
    tint: Color,
    bgColor: Color,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        var pressed by remember { mutableStateOf(false) }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = if (pressed) bgColor.copy(alpha = 0.3f) else bgColor,
            tonalElevation = 0.dp,
        ) {
            Box(
                modifier = Modifier.size(60.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(26.dp),
                    tint = tint,
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Slate400,
        )
    }
}
