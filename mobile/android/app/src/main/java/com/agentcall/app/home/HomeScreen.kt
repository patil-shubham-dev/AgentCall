package com.agentcall.app.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.data.api.AiKeyItem
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.composables.GradientAvatar
import com.agentcall.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.sin

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onCallClicked: (String) -> Unit = {},
    onProfileClicked: (String) -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val aiStatus by viewModel.aiStatus.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "homePulse")
    val waitingPulse by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "waitingPulse")
    val waitingScale by infiniteTransition.animateFloat(1f, 1.06f,
        infiniteRepeatable(tween(2500, easing = EaseInOutSine), RepeatMode.Reverse), label = "waitingScale")

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Box(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            AmbientBackground(
                accentColor = Indigo500,
                secondaryColor = GradientBrandEnd,
                speedMultiplier = 0.8f,
            )

            particles.forEach { particle ->
                val particleAnim by infiniteTransition.animateFloat(0f, 1f,
                    infiniteRepeatable(
                        tween(3000 + particle.delay, delayMillis = particle.delay, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ), label = "particle_${particle.hashCode()}")
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

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("AgentCall",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("v1.0",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(state.statusText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }

                    val badgeColor = when {
                        state.isReconnecting -> Amber400
                        state.isConnected -> Green400
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }

                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = badgeColor.copy(alpha = 0.12f),
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.size(8.dp).clip(CircleShape)
                                .background(badgeColor.copy(alpha = 0.5f + waitingPulse * 0.5f)))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                when {
                                    state.isReconnecting -> "Reconnecting"
                                    state.isConnected -> "Connected"
                                    else -> "Offline"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = badgeColor, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                when {
                    state.isLoading -> LoadingSkeleton()
                    profiles.isEmpty() && state.isConnected -> EmptyProfilesContent(waitingPulse)
                    profiles.isEmpty() && !state.isConnected -> DisconnectedContent()
                    else -> AiProfileGrid(
                        profiles = profiles,
                        aiStatus = aiStatus,
                        waitingScale = waitingScale,
                        waitingPulse = waitingPulse,
                        onProfileClicked = onProfileClicked,
                    )
                }
            }
        }
    }
}

@Composable
private fun AiProfileGrid(
    profiles: List<AiProfileEntity>,
    aiStatus: Map<String, AiKeyItem>,
    waitingScale: Float,
    waitingPulse: Float,
    onProfileClicked: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text("Your AI Agents",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 80.dp),
        ) {
            items(profiles, key = { it.id }) { profile ->
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
                    ) + fadeIn(),
                ) {
                    AiProfileCard(
                        profile = profile,
                        aiKey = aiStatus[profile.name],
                        onClick = { onProfileClicked(profile.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun AiProfileCard(profile: AiProfileEntity, aiKey: AiKeyItem?, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f), label = "cardPress"
    )

    val lastCallText = profile.lastCalledAt?.let { formatRelativeTime(it) } ?: "No calls yet"
    val presence = aiKey?.toPresence()
    val statusColor = when (presence) {
        AiPresence.BUSY -> Amber400
        AiPresence.ONLINE -> Green400
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (presence) {
        AiPresence.BUSY -> "Busy"
        AiPresence.ONLINE -> "Online"
        else -> lastCallText
    }

    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().scale(pressScale),
        shape = RoundedCornerShape(16.dp),
        color = Slate800.copy(alpha = 0.6f),
        tonalElevation = 0.dp,
        interactionSource = interactionSource,
    ) {
        Column(
            modifier = Modifier.padding(20.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GradientAvatar(size = 64.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(profile.name, style = MaterialTheme.typography.titleMedium,
                color = Slate50, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.8f)))
                Spacer(modifier = Modifier.width(6.dp))
                Text(statusText, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60000
    val hours = minutes / 60
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        else -> "${hours / 24}d ago"
    }
}

@Composable
private fun EmptyProfilesContent(waitingPulse: Float) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier.size(80.dp).clip(CircleShape)
                .background(Brush.linearGradient(
                    listOf(Indigo500.copy(alpha = 0.1f + waitingPulse * 0.1f), Indigo700.copy(alpha = 0.05f))
                )),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.size(80.dp)) {
                val sweep = waitingPulse * 120f
                drawArc(color = Indigo400.copy(alpha = 0.3f),
                    startAngle = -60f + waitingPulse * 360f,
                    sweepAngle = sweep, useCenter = false, style = Stroke(width = 2.5f))
            }
            Icon(Icons.Default.SmartToy, "Waiting",
                modifier = Modifier.size(36.dp),
                tint = Indigo400.copy(alpha = 0.7f + waitingPulse * 0.3f))
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text("No AI Agents Yet",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
        Text("Your AI agents will appear here when they call you.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 32.dp, top = 6.dp, end = 32.dp))
        Spacer(modifier = Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(100.dp), color = GlassIndigo) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape)
                    .background(Indigo400.copy(alpha = 0.6f + waitingPulse * 0.4f)))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Connected and waiting for incoming calls",
                    style = MaterialTheme.typography.labelSmall, color = Indigo300)
            }
        }
    }
}

@Composable
private fun LoadingSkeleton() {
    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerAlpha by shimmerTransition.animateFloat(0.3f, 0.7f,
        infiniteRepeatable(tween(1000, easing = EaseInOutSine), RepeatMode.Reverse), label = "shimmerAlpha")

    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.weight(1f))
        Box(modifier = Modifier.size(80.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = shimmerAlpha)))
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.width(180.dp).height(20.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = shimmerAlpha)))
        Spacer(modifier = Modifier.height(8.dp))
        Box(modifier = Modifier.width(240.dp).height(16.dp).clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = shimmerAlpha * 0.7f)))
        Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun DisconnectedContent() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(80.dp).clip(CircleShape)
            .background(Brush.linearGradient(listOf(Red500.copy(alpha = 0.15f), MaterialTheme.colorScheme.surface))),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.WifiOff, "Disconnected", modifier = Modifier.size(36.dp),
                tint = Red400)
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text("Disconnected", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground)
        Text("Open Settings to configure server address",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(start = 24.dp, top = 8.dp, end = 24.dp))
    }
}

private data class Particle(
    val x: Float,
    val y: Float,
    val size: Float,
    val speed: Float,
    val delay: Int,
)