package com.agentcall.app.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.agentcall.app.R
import com.agentcall.app.data.api.AiKeyItem
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.ui.theme.*

@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    onCallClicked: (String) -> Unit = {},
    onProfileClicked: (String) -> Unit = {},
    onOpenBatteryHelp: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(
                top = (innerPadding.calculateTopPadding() - 10.dp).coerceAtLeast(0.dp),
                bottom = innerPadding.calculateBottomPadding(),
                start = innerPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = innerPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenPadding).padding(top = 0.dp, bottom = Spacing.M),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Brand lockup: larger transparent logo + reduced wordmark, composed as one system.
                // Logo is centered to the two-line text block (wordmark + status) for optical balance.
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(R.drawable.agentcall_adaptive_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "AgentCall",
                            style = MaterialTheme.typography.displayLarge.copy(
                                fontSize = 19.sp,
                                lineHeight = 23.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.12.sp,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier.size(7.dp).clip(CircleShape)
                                    .background(
                                        when { state.isReconnecting -> DotReconnecting; state.isConnected -> DotOnline; else -> DotOffline }
                                    )
                            )
                            Spacer(modifier = Modifier.width(7.dp))
                            Text(
                                text = when { state.isReconnecting -> "Reconnecting"; state.isConnected -> "Ready"; else -> "Offline" },
                                style = MaterialTheme.typography.labelSmall,
                                color = when { state.isReconnecting -> Warning; state.isConnected -> Success; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                            )
                            if (state.isConnected) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "\u00B7 v1.0",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)) {
                    Icon(Icons.Default.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f))
            if (showBatteryBanner) {
                MinimalBatteryBanner(onOpen = { viewModel.dismissBatteryBanner(); onOpenBatteryHelp() }, onDismiss = { viewModel.dismissBatteryBanner() })
            }
            when {
                state.isLoading -> LoadingSkeletonMinimal()
                profiles.isEmpty() && !state.isConnected -> DisconnectedContentMinimal(onRetry = { viewModel.reconnect() }, onOpenSettings = onOpenSettings)
                profiles.isEmpty() && state.isConnected -> EmptyProfilesContentMinimal(onAdd = { onOpenSettings() })
                else -> AgentList(profiles = profiles, aiStatus = aiStatus, agentStatus = agentStatus, onProfileClicked = onProfileClicked, onProfileLongPressed = { pendingDelete = it })
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${target.name} from this device?") },
            text = { Text("Deletes profile, call history and transcripts from this device. Server key is kept.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            confirmButton = { TextButton(onClick = { viewModel.deleteAgent(target); pendingDelete = null }) { Text("Delete", color = Error, fontWeight = FontWeight.SemiBold) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun AgentList(
    profiles: List<AiProfileEntity>,
    aiStatus: Map<String, AiKeyItem>,
    agentStatus: Map<String, com.agentcall.app.data.api.AgentStatusResponse>,
    onProfileClicked: (String) -> Unit,
    onProfileLongPressed: (AiProfileEntity) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text("Your agents", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.S))
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.ScreenPadding), verticalArrangement = Arrangement.spacedBy(Spacing.ListGap), contentPadding = PaddingValues(bottom = Spacing.XXL)) {
            items(profiles, key = { it.id }) { profile ->
                AgentRow(profile = profile, aiKey = aiStatus[profile.name], lastSeenText = formatLastSeen(agentStatus[profile.name]?.lastSeenAt), onClick = { onProfileClicked(profile.id) }, onLongPress = { onProfileLongPressed(profile) })
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AgentRow(profile: AiProfileEntity, aiKey: AiKeyItem?, onClick: () -> Unit, onLongPress: () -> Unit, lastSeenText: String? = null) {
    val presence = aiKey?.toPresence()
    val dotColor = when (presence) { AiPresence.BUSY -> DotBusy; AiPresence.ONLINE -> DotOnline; else -> DotOffline }
    val statusText = when (presence) { AiPresence.BUSY -> "Busy"; AiPresence.ONLINE -> "Online"; else -> "Offline" }
    val recencyLine = when { presence == AiPresence.OFFLINE && lastSeenText != null -> lastSeenText; else -> profile.lastCalledAt?.let { "Last call ${formatRelativeTime(it)}" } ?: "No calls yet" }
    // Card lift: surfaceVariant (+1 elevation) with outlineVariant hairline keeps pure-black bg untouched but makes card read clearly; avatar uses theme primary/onPrimary so it adapts (dark = light circle) for contrast.
    Surface(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress), shape = RoundedCornerShape(Radii.Card), color = MaterialTheme.colorScheme.surfaceVariant, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), tonalElevation = 1.dp) {
        Row(modifier = Modifier.padding(horizontal = Spacing.L, vertical = Spacing.L), verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Text(profile.name.take(1).uppercase(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
            }
            Spacer(modifier = Modifier.width(Spacing.M))
            Column(modifier = Modifier.weight(1f)) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(dotColor))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("$statusText \u00B7 $recencyLine", style = MaterialTheme.typography.labelSmall, color = if (presence == AiPresence.ONLINE || presence == AiPresence.BUSY) dotColor else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(20.dp))
        }
    }
}

private fun formatLastSeen(iso: String?): String? {
    if (iso.isNullOrBlank()) return null
    val ts = try { java.time.Instant.parse(iso).toEpochMilli() } catch (_: Exception) { return null }
    val diff = System.currentTimeMillis() - ts
    val minutes = diff / 60000
    val hours = minutes / 60
    return when { minutes < 1 -> "Last seen just now"; minutes < 60 -> "Last seen ${minutes}m ago"; hours < 24 -> "Last seen ${hours}h ago"; else -> "Last seen ${hours / 24}d ago" }
}
private fun formatRelativeTime(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    val minutes = diff / 60000
    val hours = minutes / 60
    return when { minutes < 1 -> "just now"; minutes < 60 -> "${minutes}m ago"; hours < 24 -> "${hours}h ago"; else -> "${hours / 24}d ago" }
}

@Composable
private fun EmptyProfilesContentMinimal(onAdd: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.XL, vertical = Spacing.XXXL), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Default.SmartToy, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(modifier = Modifier.height(Spacing.L))
        Text("No agents yet", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(Spacing.S))
        Text("Agents you add will appear here. When an AI calls, it will ring like a phone call.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(Spacing.L))
        Button(onClick = onAdd, shape = RoundedCornerShape(Radii.Field), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(6.dp)); Text("Go to Settings -> Add AI") }
    }
}

@Composable
private fun LoadingSkeletonMinimal() {
    Column(modifier = Modifier.fillMaxWidth().padding(Spacing.XL), verticalArrangement = Arrangement.spacedBy(Spacing.S)) {
        repeat(3) {
            Surface(shape = RoundedCornerShape(Radii.Card), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth()) {
                Row(modifier = Modifier.padding(Spacing.L), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                    Spacer(modifier = Modifier.width(Spacing.M))
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.width(120.dp).height(14.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                        Box(modifier = Modifier.width(160.dp).height(10.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)))
                    }
                }
            }
        }
    }
}

@Composable
private fun DisconnectedContentMinimal(onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val host = ApiClient.serverHost
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = Spacing.XL, vertical = Spacing.XXXL), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) { Icon(Icons.Default.WifiOff, contentDescription = null, modifier = Modifier.size(28.dp), tint = Error) }
        Spacer(modifier = Modifier.height(Spacing.L))
        Text("Disconnected", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(modifier = Modifier.height(Spacing.S))
        Text("Could not reach $host", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(Spacing.L))
        Row(horizontalArrangement = Arrangement.Center) {
            Button(onClick = onRetry, shape = RoundedCornerShape(Radii.Field), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(6.dp)); Text("Retry connection") }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onOpenSettings, shape = RoundedCornerShape(Radii.Field)) { Text("Open Settings") }
        }
    }
}

@Composable
private fun MinimalBatteryBanner(onOpen: () -> Unit, onDismiss: () -> Unit) {
    Surface(shape = RoundedCornerShape(Radii.Card), color = MaterialTheme.colorScheme.surface, border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.ScreenPadding, vertical = Spacing.S)) {
        Row(modifier = Modifier.padding(Spacing.L), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = Warning, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(Spacing.M))
            Column(modifier = Modifier.weight(1f)) {
                Text("Make sure calls can wake the app", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("Battery & autostart permissions", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onOpen) { Text("Set up", color = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) { Icon(Icons.Default.Close, contentDescription = "Dismiss", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        }
    }
}






