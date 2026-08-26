package com.agentcall.app.home

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.data.api.AiKeyItem
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.composables.GradientAvatar
import com.agentcall.app.ui.composables.Plate
import com.agentcall.app.ui.composables.SectionLabel
import com.agentcall.app.ui.composables.StatusPill
import com.agentcall.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.sin

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onCallClicked: (String) -> Unit = {},
    onProfileClicked: (String) -> Unit = {},
    onOpenBatteryHelp: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val aiStatus by viewModel.aiStatus.collectAsStateWithLifecycle()
    val agentStatus by viewModel.agentStatus.collectAsStateWithLifecycle()
    val showBatteryBanner by viewModel.showBatteryBanner.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<AiProfileEntity?>(null) }

    LaunchedEffect(Unit) {
        viewModel.snackbarEvents.collect { message ->
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "homePulse")
    val waitingPulse by infiniteTransition.animateFloat(0f, 1f,
        infiniteRepeatable(tween(2000, easing = EaseInOutSine), RepeatMode.Reverse), label = "waitingPulse")

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
                        color = Phosphor.copy(alpha = 0.08f * (1f - particleAnim)),
                        radius = particle.size,
                        center = Offset(px, py),
                    )
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("AGENTCALL",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.onBackground)
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(state.statusText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("v1.0",
                                style = MonoLabel,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    StatusPill(
                        label = when {
                            state.isReconnecting -> "RECONNECTING"
                            state.isConnected -> "CONNECTED"
                            else -> "OFFLINE"
                        },
                        lampColor = when {
                            state.isReconnecting -> Amber400
                            state.isConnected -> Green400
                            else -> LampOff
                        },
                        pulse = state.isReconnecting || state.isConnected,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                if (showBatteryBanner) {
                    BatteryOptimizationBanner(
                        onOpen = {
                            viewModel.dismissBatteryBanner()
                            onOpenBatteryHelp()
                        },
                        onDismiss = { viewModel.dismissBatteryBanner() },
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                when {
                    state.isLoading -> LoadingSkeleton()
                    profiles.isEmpty() && state.isConnected -> EmptyProfilesContent(waitingPulse)
                    profiles.isEmpty() && !state.isConnected -> DisconnectedContent()
                    else -> AiProfileGrid(
                        profiles = profiles,
                        aiStatus = aiStatus,
                        agentStatus = agentStatus,
                        onProfileClicked = onProfileClicked,
                        onProfileLongPressed = { pendingDelete = it },
                    )
                }
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${target.name} from this device?", color = Slate50) },
            text = {
                Text(
                    "This deletes ${target.name}'s profile, call history and transcripts " +
                        "from this device and cannot be undone. The agent's key on the " +
                        "server is kept, so it can still call you again — delete the key " +
                        "in Settings to remove it entirely.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Slate400,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteAgent(target)
                        pendingDelete = null
                    },
                ) {
                    Text("Delete from this device", color = Red400, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = Slate400)
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiProfileGrid(
    profiles: List<AiProfileEntity>,
    aiStatus: Map<String, AiKeyItem>,
    agentStatus: Map<String, com.agentcall.app.data.api.AgentStatusResponse>,
    onProfileClicked: (String) -> Unit,
    onProfileLongPressed: (AiProfileEntity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            SectionLabel(title = "YOUR AI AGENTS")
        }
        Spacer(modifier = Modifier.height(10.dp))

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
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
                        lastSeenText = formatLastSeen(agentStatus[profile.name]?.lastSeenAt),
                        onClick = { onProfileClicked(profile.id) },
                        onLongPress = { onProfileLongPressed(profile) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AiProfileCard(
    profile: AiProfileEntity,
    aiKey: AiKeyItem?,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    lastSeenText: String? = null,
) {
    val presence = aiKey?.toPresence()
    val statusColor = when (presence) {
        AiPresence.BUSY -> Amber400
        AiPresence.ONLINE -> Green400
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusText = when (presence) {
        AiPresence.BUSY -> "Busy"
        AiPresence.ONLINE -> "Online"
        else -> "Offline"
    }
    // Line 3 is always present: the recency story of this agent. Offline shows
    // last-seen; otherwise the last call — or "No calls yet" when it never called.
    val recencyLine = when {
        presence == AiPresence.OFFLINE && lastSeenText != null -> lastSeenText
        else -> profile.lastCalledAt?.let { "Last call ${formatRelativeTime(it)}" } ?: "No calls yet"
    }

    Plate(
        onClick = null,
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongPress),
        containerShape = RoundedCornerShape(Radii.Plate),
        containerColor = Slate800.copy(alpha = 0.9f),
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            GradientAvatar(size = 64.dp)
            Spacer(modifier = Modifier.height(10.dp))
            Text(profile.name.uppercase(), style = MaterialTheme.typography.titleMedium,
                color = Slate50, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(contentAlignment = Alignment.Center) {
                    Box(modifier = Modifier.size(11.dp).clip(CircleShape)
                        .background(statusColor.copy(alpha = if (presence == AiPresence.ONLINE) 0.15f else 0f)))
                    Box(modifier = Modifier.size(7.dp).clip(CircleShape)
                        .background(if (presence == AiPresence.ONLINE || presence == AiPresence.BUSY) statusColor else LampOff))
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(statusText, style = MaterialTheme.typography.labelSmall,
                    color = if (presence == AiPresence.ONLINE || presence == AiPresence.BUSY) statusColor
                            else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(recencyLine, style = MonoBody,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
    }
}

private fun formatLastSeen(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val ts = try { java.time.Instant.parse(iso).toEpochMilli() } catch (_: Exception) { return null }
    val diff = System.currentTimeMillis() - ts
    val minutes = diff / 60000
    val hours = minutes / 60
    return when {
        minutes < 1 -> "Last seen just now"
        minutes < 60 -> "Last seen ${minutes}m ago"
        hours < 24 -> "Last seen ${hours}h ago"
        else -> "Last seen ${hours / 24}d ago"
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
        StatusPill(
            label = "Connected and waiting for incoming calls",
            lampColor = Green400,
            pulse = true,
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BatteryOptimizationBanner(
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
) {
    Plate(
        onClick = null,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .combinedClickable(onClick = onOpen),
        containerShape = RoundedCornerShape(12.dp),
        containerColor = Slate800.copy(alpha = 0.9f),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.BatteryAlert, "Battery optimization", tint = Amber400, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Make sure calls can wake the app",
                    style = MaterialTheme.typography.titleSmall,
                    color = Slate50,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Set up battery & autostart permissions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(onClick = onOpen) {
                Text("Set up", color = Indigo400)
            }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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