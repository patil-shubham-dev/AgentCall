package com.agentcall.app.home

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.data.model.CallResponse
import com.agentcall.app.data.model.PresenceResponse
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

    LaunchedEffect(Unit) {
        viewModel.loadCallHistory()
        while (true) {
            viewModel.refreshPresence()
            delay(30_000)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Slate900),
    ) {
        // Background subtle gradient
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Indigo500.copy(alpha = 0.05f),
                radius = size.width * 0.6f,
                center = Offset(size.width * 0.8f, 0f),
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // ── Header ─────────────────────────────
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AgentCall",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Slate50,
                    )
                    Text(
                        text = "Connected & ready",
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400,
                    )
                }

                // ── Presence Indicator ──────────────
                if (state.presence != null) {
                    val isOnline = state.presence!!.status == "online"
                    val dotColor = if (isOnline) Green500 else Amber400
                    val dotGlow = if (isOnline) Green500.copy(alpha = 0.2f) else Amber400.copy(alpha = 0.2f)

                    Surface(
                        shape = RoundedCornerShape(100.dp),
                        color = if (isOnline) GlassGreen else GlassAmber,
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(dotColor),
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = state.presence!!.status.replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelSmall,
                                color = dotColor,
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // ── Active Call Banner ─────────────────
            AnimatedVisibility(
                visible = state.activeCall != null,
                enter = slideInVertically(animationSpec = spring()) + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
            ) {
                if (state.activeCall != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { state.activeCall?.let { onCallClicked(it) } },
                        shape = RoundedCornerShape(16.dp),
                        color = Slate800,
                        tonalElevation = 4.dp,
                        shadowElevation = 8.dp,
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Live indicator
                            Box(modifier = Modifier.size(40.dp)) {
                                Canvas(modifier = Modifier.size(40.dp)) {
                                    drawCircle(
                                        color = Green500.copy(alpha = 0.2f),
                                        radius = size.minDimension / 2,
                                    )
                                    drawCircle(
                                        color = Green500,
                                        radius = size.minDimension * 0.25f,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Active Call — AI Agent",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = Slate50,
                                )
                                Text(
                                    text = "Tap to open",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Green400,
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Slate400,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // ── Section Header ─────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Recent Calls",
                    style = MaterialTheme.typography.titleLarge,
                    color = Slate50,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "See all",
                    style = MaterialTheme.typography.labelMedium,
                    color = Indigo400,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ── Content ────────────────────────────
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = Indigo400,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }

                state.error != null -> {
                    ErrorState(
                        error = state.error!!,
                        onRetry = { viewModel.loadCallHistory() },
                    )
                }

                state.calls.isEmpty() -> {
                    EmptyState()
                }

                else -> {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.calls, key = { it.callId }) { call ->
                            CallHistoryCard(
                                call = call,
                                onClick = { onCallClicked(call.callId) },
                            )
                        }
                        item { Spacer(modifier = Modifier.height(16.dp)) }
                    }
                }
            }
        }
    }
}

// ── Call History Card ────────────────────────
@Composable
fun CallHistoryCard(
    call: CallResponse,
    onClick: () -> Unit,
) {
    val isSuccess = call.status == "ended"
    val isMissed = call.status == "timed_out" || call.status == "failed"
    val isCancelled = call.status == "cancelled"

    val statusColor = when {
        isSuccess -> Green500
        isMissed -> Red400
        isCancelled -> Amber400
        else -> Slate400
    }

    val statusIcon: ImageVector = when {
        isSuccess -> Icons.Default.CheckCircle
        isMissed -> Icons.Default.Cancel
        isCancelled -> Icons.Default.RemoveCircle
        else -> Icons.Default.Circle
    }

    val statusLabel = when {
        isSuccess -> "Completed"
        isMissed -> "Missed"
        isCancelled -> "Cancelled"
        else -> call.status.replaceFirstChar { it.uppercase() }
    }

    var pressed by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (pressed) Slate750 else Slate800,
        tonalElevation = 2.dp,
        shadowElevation = if (pressed) 2.dp else 4.dp,
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Status icon
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = statusIcon,
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                    tint = statusColor,
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Call info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "AI Agent",
                        style = MaterialTheme.typography.titleMedium,
                        color = Slate50,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    PriorityBadge(priority = call.priority ?: "normal")
                }

                Spacer(modifier = Modifier.height(4.dp))

                if (call.contextSummary != null) {
                    Text(
                        text = call.contextSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = Slate400,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                    )
                    if (call.durationSeconds != null && isSuccess) {
                        Text(
                            text = " · ${formatDuration(call.durationSeconds)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500,
                        )
                    }
                    if (call.createdAt != null) {
                        Text(
                            text = " · ${formatTimestamp(call.createdAt)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Slate500,
                        )
                    }
                }
            }

            // Chevron
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .offset(y = 2.dp),
                tint = Slate600,
            )
        }
    }
}

// ── Priority Badge ───────────────────────────
@Composable
fun PriorityBadge(priority: String) {
    val (label, color) = when (priority) {
        "urgent" -> "URGENT" to Red400
        "high" -> "HIGH" to Amber400
        "normal" -> "NORMAL" to Indigo400
        "low" -> "LOW" to Slate500
        else -> priority.uppercase() to Slate500
    }

    Surface(
        shape = RoundedCornerShape(6.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, letterSpacing = 1.sp),
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

// ── Empty State ──────────────────────────────
@Composable
fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(GlassIndigo),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.PhoneCallback,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = Indigo400,
            )
        }
        Spacer(modifier = Modifier.height(20.dp))
        Text(
            text = "No calls yet",
            style = MaterialTheme.typography.titleLarge,
            color = Slate50,
        )
        Text(
            text = "When an AI agent needs your input,\nyou'll receive a call here",
            style = MaterialTheme.typography.bodyMedium,
            color = Slate400,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

// ── Error State ──────────────────────────────
@Composable
fun ErrorState(error: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(GlassRed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = Red400,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = Slate400,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(onClick = onRetry) {
            Text("Retry")
        }
    }
}

// ── Helpers ──────────────────────────────────
private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

private fun formatTimestamp(iso: String): String {
    return try {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        val date = parser.parse(iso.take(19)) ?: return ""
        val now = Calendar.getInstance()
        val cal = Calendar.getInstance().apply { time = date }
        if (cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR) &&
            cal.get(Calendar.YEAR) == now.get(Calendar.YEAR)
        ) {
            SimpleDateFormat("h:mm a", Locale.US).format(date)
        } else {
            SimpleDateFormat("MMM d", Locale.US).format(date)
        }
    } catch (_: Exception) {
        ""
    }
}
