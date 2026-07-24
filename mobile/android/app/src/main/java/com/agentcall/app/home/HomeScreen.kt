package com.agentcall.app.home

import android.content.Intent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.call.IncomingCallActivity
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.theme.*
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onCallClicked: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.incomingCallId) {
        state.incomingCallId?.let { callId ->
            val intent = Intent(context, IncomingCallActivity::class.java).apply {
                putExtra("call_id", callId)
                putExtra("caller_name", "AI Agent")
                putExtra("context_summary", state.incomingSummary ?: "")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            viewModel.clearIncoming()
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "homePulse")
    val waitingPulse by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "waitingPulse")
    val waitingScale by infiniteTransition.animateFloat(1f, 1.06f,
        infiniteRepeatable(tween(2500, easing = EaseInOutSine), RepeatMode.Reverse), label = "waitingScale")

    // Particle data
    val particles = remember {
        List(12) { i ->
            Particle(
                x = 0.1f + 0.8f * (i % 4) / 3f,
                y = 0.1f + 0.8f * (i / 4) / 2f,
                size = 2f + (i % 3) * 1.5f,
                speed = 0.3f + (i % 5) * 0.15f,
                delay = (i * 700) % 3000,
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900),
    ) {
        // Animated ambient background
        AmbientBackground(
            accentColor = Indigo500,
            secondaryColor = GradientBrandEnd,
            speedMultiplier = 0.8f,
        )

        // Floating particles
        particles.forEach { particle ->
            val particleAnim by infiniteTransition.animateFloat(
                0f, 1f,
                infiniteRepeatable(
                    animation = tween(3000 + particle.delay, delayMillis = particle.delay, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "particle_${particle.hashCode()}"
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val px = particle.x * size.width + sin(particleAnim * 2f * kotlin.math.PI.toFloat() * particle.speed) * 30f
                val py = particle.y * size.height - particleAnim * size.height * 0.08f
                drawCircle(
                    color = Indigo400.copy(alpha = 0.08f * (1f - particleAnim)),
                    radius = particle.size,
                    center = Offset(px, py),
                )
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(16.dp))

            // ── Header ────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "VoiceBridge",
                            style = MaterialTheme.typography.headlineMedium,
                            color = Slate50,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "v1.0",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500,
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = state.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400,
                    )
                }

                // Connection quality badge
                val (qualityColor, qualityIcon, qualityLabel) = when (state.connectionQuality) {
                    ConnectionQuality.EXCELLENT -> Triple(Green400, Icons.Default.Wifi, "Excellent")
                    ConnectionQuality.GOOD -> Triple(Indigo400, Icons.Default.Wifi, "Good")
                    ConnectionQuality.FAIR -> Triple(Amber400, Icons.Default.Wifi, "Fair")
                    ConnectionQuality.POOR -> Triple(Red400, Icons.Default.WifiOff, "Poor")
                    ConnectionQuality.UNKNOWN -> Triple(Slate500, Icons.Default.WifiOff, "---")
                }

                Surface(
                    shape = RoundedCornerShape(100.dp),
                    color = qualityColor.copy(alpha = 0.12f),
                    tonalElevation = 0.dp,
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val dotColor = if (state.isConnected) qualityColor else Amber400
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor.copy(alpha = 0.5f + waitingPulse * 0.5f)),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = qualityLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = dotColor,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── Active Call Banner ─────────────────────
            AnimatedVisibility(
                visible = state.activeCallId != null,
                enter = slideInVertically(animationSpec = spring(dampingRatio = 0.7f)) + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
            ) {
                state.activeCallId?.let { callId ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { onCallClicked(callId) },
                        shape = RoundedCornerShape(20.dp),
                        color = Slate800,
                        tonalElevation = 4.dp,
                        shadowElevation = 12.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val activeDotAnim by infiniteTransition.animateFloat(0f, 1f,
                                infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse), label = "activeDot")
                            Box(modifier = Modifier.size(44.dp)) {
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    drawCircle(
                                        GradientBrandStart.copy(alpha = 0.12f + activeDotAnim * 0.1f),
                                        size.minDimension / 2
                                    )
                                    drawCircle(
                                        GradientCallStart.copy(alpha = 0.8f + activeDotAnim * 0.2f),
                                        size.minDimension * 0.3f
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Active Call — AI Agent",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Slate50,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Tap to open",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Green400,
                                )
                            }
                            Icon(Icons.Default.ChevronRight, null, tint = Slate400)
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // ── Main Content ───────────────────────────
            if (!state.isConnected) {
                // Disconnected state
                Spacer(modifier = Modifier.weight(1f))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.size(80.dp).scale(waitingScale).clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Red500.copy(alpha = 0.15f),
                                        Slate800,
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.WifiOff, null,
                            modifier = Modifier.size(36.dp),
                            tint = Red400.copy(alpha = 0.7f + waitingPulse * 0.3f),
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "Disconnected",
                        style = MaterialTheme.typography.titleLarge,
                        color = Slate50,
                    )
                    Text(
                        "Open Settings to configure server address",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate400,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp, horizontal = 24.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
            } else if (state.activeCallId == null) {
                // Ready & waiting state
                Spacer(modifier = Modifier.weight(0.5f))
                Column(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.size(80.dp).scale(waitingScale).clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(
                                        Indigo500.copy(alpha = 0.1f + waitingPulse * 0.1f),
                                        Indigo700.copy(alpha = 0.05f),
                                    )
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Animated ring around icon
                        Canvas(modifier = Modifier.size(80.dp)) {
                            val sweep = waitingPulse * 120f
                            drawArc(
                                color = Indigo400.copy(alpha = 0.3f),
                                startAngle = -60f + waitingPulse * 360f,
                                sweepAngle = sweep,
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f),
                            )
                        }
                        Icon(
                            Icons.Default.PhoneCallback, null,
                            modifier = Modifier.size(36.dp),
                            tint = Indigo400.copy(alpha = 0.7f + waitingPulse * 0.3f),
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        "Connected",
                        style = MaterialTheme.typography.titleLarge,
                        color = Slate50,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Waiting for AI to call...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate400,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = GlassGreen,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier.size(6.dp).clip(CircleShape)
                                    .background(Green500.copy(alpha = 0.6f + waitingPulse * 0.4f))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Your phone will ring when an agent needs you",
                                style = MaterialTheme.typography.labelSmall,
                                color = Green400,
                            )
                        }
                    }
                }

                // ── Recent Calls ──────────────────────────
                if (state.recentCalls.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp), tint = Slate500)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Recent Calls",
                            style = MaterialTheme.typography.titleSmall,
                            color = Slate400,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.5.sp,
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        items(state.recentCalls.take(5), key = { it.callId }) { call ->
                            RecentCallCard(call, onCallClicked)
                        }
                    }
                    Spacer(modifier = Modifier.weight(0.5f))
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RecentCallCard(call: RecentCallEntry, onCallClicked: (String) -> Unit) {
    val timeStr = remember(call.timestamp) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        sdf.format(Date(call.timestamp))
    }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(80)
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(
            initialOffsetY = { it / 2 },
            animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f)
        ) + fadeIn(animationSpec = tween(200)),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onCallClicked(call.callId) },
            shape = RoundedCornerShape(14.dp),
            color = Slate800.copy(alpha = 0.6f),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val statusColor = when (call.status) {
                    "ended" -> Green500
                    "cancelled" -> Red400
                    else -> Slate500
                }
                val statusIcon = when (call.status) {
                    "ended" -> Icons.Default.CheckCircle
                    "cancelled" -> Icons.Default.Cancel
                    else -> Icons.Default.Call
                }
                Box(
                    modifier = Modifier.size(36.dp).clip(CircleShape)
                        .background(statusColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(statusIcon, null, modifier = Modifier.size(18.dp), tint = statusColor)
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = call.summary.take(60),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Slate100,
                        maxLines = 1,
                    )
                    Text(
                        text = "${call.reason} · $timeStr",
                        style = MaterialTheme.typography.labelSmall,
                        color = Slate500,
                    )
                }
                Icon(Icons.Default.ChevronRight, null, modifier = Modifier.size(18.dp), tint = Slate600)
            }
        }
    }
}

private data class Particle(
    val x: Float,
    val y: Float,
    val size: Float,
    val speed: Float,
    val delay: Int,
)

