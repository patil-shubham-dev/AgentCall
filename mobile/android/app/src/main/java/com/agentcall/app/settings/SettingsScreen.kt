package com.agentcall.app.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.agentcall.app.call.SignalingClient
import com.agentcall.app.call.SignalingClient.ConnectionState
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.theme.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class ConnectionTestStatus {
    IDLE, TESTING, SUCCESS, FAILED
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val signalingClient: SignalingClient,
) : ViewModel() {
    private val _serverHost = MutableStateFlow(ApiClient.serverHost)
    val serverHost: StateFlow<String> = _serverHost.asStateFlow()

    private val _connectionStatus = MutableStateFlow("Checking...")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _testStatus = MutableStateFlow(ConnectionTestStatus.IDLE)
    val testStatus: StateFlow<ConnectionTestStatus> = _testStatus.asStateFlow()

    private val _testLatency = MutableStateFlow(0L)
    val testLatency: StateFlow<Long> = _testLatency.asStateFlow()

    init {
        viewModelScope.launch {
            signalingClient.connectionState.collect { state ->
                _connectionStatus.value = when (state) {
                    ConnectionState.CONNECTED -> "Connected"
                    ConnectionState.CONNECTING -> "Connecting..."
                    ConnectionState.RECONNECTING -> "Reconnecting..."
                    ConnectionState.DISCONNECTED -> "Disconnected"
                }
            }
        }
    }

    fun updateServerHost(host: String) {
        _serverHost.value = host
    }

    fun connect() {
        val host = _serverHost.value.trim().ifBlank { ApiClient.serverHost }
        ApiClient.setServerHost(host)
        com.agentcall.app.home.ServerConfigEvent.reconnectRequests.value++
    }

    fun testConnection() {
        _testStatus.value = ConnectionTestStatus.TESTING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                try {
                    val host = _serverHost.value.trim()
                    val isDomain = !Regex("^[\\d.]+$").matches(host)
                    val scheme = if (isDomain) "https" else "http"
                    val port = if (isDomain) "" else ":4000"
                    val url = java.net.URL("$scheme://$host$port/api/v1/health")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "GET"
                    val responseCode = conn.responseCode
                    val elapsed = System.currentTimeMillis() - start
                    conn.disconnect()
                    Pair(elapsed, if (responseCode == 200) ConnectionTestStatus.SUCCESS else ConnectionTestStatus.FAILED)
                } catch (_: Exception) {
                    Pair(System.currentTimeMillis() - start, ConnectionTestStatus.FAILED)
                }
            }
            _testLatency.value = result.first
            _testStatus.value = result.second
        }
    }

    fun resetTest() {
        _testStatus.value = ConnectionTestStatus.IDLE
        _testLatency.value = 0L
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onReconnect: () -> Unit = {},
) {
    val serverHost by viewModel.serverHost.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val testStatus by viewModel.testStatus.collectAsStateWithLifecycle()
    val testLatency by viewModel.testLatency.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Header with ambient background behind
        Box(modifier = Modifier.height(80.dp)) {
            AmbientBackground(
                accentColor = Indigo500,
                density = 0.6f,
                speedMultiplier = 0.5f,
                modifier = Modifier.fillMaxSize(),
            )
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Text(
                    text = "Configure your connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Server Connection ────────────────────────
            SettingsSection(title = "SERVER CONNECTION") {
                GlassCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Status row
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotAnim by rememberInfiniteTransition(label = "connDot").animateFloat(
                                0f, 1f,
                                infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
                                label = "connDot"
                            )
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (connectionStatus == "Connected") Green500.copy(alpha = 0.5f + dotAnim * 0.5f)
                                        else Amber400.copy(alpha = 0.5f + dotAnim * 0.5f)
                                    ),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Backend Server",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = connectionStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (connectionStatus == "Connected") Green400 else Amber400,
                                fontWeight = FontWeight.Medium,
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            text = "Enter server hostname or IP address",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // IP input
                        OutlinedTextField(
                            value = serverHost,
                            onValueChange = { viewModel.updateServerHost(it) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("dydcghsn0my6-production-qgbb8wql.australia-southeast1.suga.run", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            leadingIcon = {
                                Icon(Icons.Default.Computer, "Server address", tint = Indigo400, modifier = Modifier.size(20.dp))
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(
                                onGo = { focusManager.clearFocus(); viewModel.connect(); onReconnect() }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                                cursorColor = Indigo400,
                                focusedBorderColor = Indigo600,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                            shape = RoundedCornerShape(12.dp),
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Buttons row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Button(
                                onClick = { focusManager.clearFocus(); viewModel.connect(); onReconnect() },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Indigo600),
                            ) {
                                Icon(Icons.Default.Wifi, "Connect to server", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Connect")
                            }

                            OutlinedButton(
                                onClick = {
                                    focusManager.clearFocus()
                                    com.agentcall.app.data.api.ApiClient.resetToDefault()
                                    viewModel.updateServerHost(com.agentcall.app.data.api.ApiClient.serverHost)
                                    viewModel.connect()
                                    onReconnect()
                                },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                                border = null,
                            ) {
                                Text("Reset")
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // ── Connection Test ────────────────
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f))
                        Spacer(modifier = Modifier.height(12.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Speed, "Test connection speed", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Connection",
                                style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium)
                            Spacer(modifier = Modifier.weight(1f))

                            // Test result indicator
                            AnimatedContent(
                                targetState = testStatus,
                                transitionSpec = {
                                    fadeIn() + slideInHorizontally { it / 4 } togetherWith
                                    fadeOut() + slideOutHorizontally { -it / 4 }
                                },
                                label = "testStatus",
                            ) { status ->
                                when (status) {
                                    ConnectionTestStatus.IDLE -> {}
                                    ConnectionTestStatus.TESTING -> {
                                        val spin by rememberInfiniteTransition(label = "spin").animateFloat(
                                            0f, 1f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Restart),
                                            label = "spin"
                                        )
                                        Icon(Icons.Default.Sync, "Testing", tint = Indigo400,
                                            modifier = Modifier.size(18.dp).clip(CircleShape).graphicsLayer {
                                                rotationZ = spin * 360f
                                            })
                                    }
                                    ConnectionTestStatus.SUCCESS -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.CheckCircle, "Connection test passed", tint = Green400, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("${testLatency}ms", style = MaterialTheme.typography.labelSmall, color = Green400)
                                        }
                                    }
                                    ConnectionTestStatus.FAILED -> {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Error, "Connection test failed", tint = Red400, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("Failed", style = MaterialTheme.typography.labelSmall, color = Red400)
                                        }
                                    }
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = testStatus != ConnectionTestStatus.TESTING,
                            enter = fadeIn() + expandVertically(),
                            exit = fadeOut() + shrinkVertically(),
                        ) {
                            Button(
                                onClick = { viewModel.testConnection() },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = when (testStatus) {
                                        ConnectionTestStatus.SUCCESS -> Green600.copy(alpha = 0.2f)
                                        ConnectionTestStatus.FAILED -> Red600.copy(alpha = 0.2f)
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    }
                                ),
                            ) {
                                Text(
                                    when (testStatus) {
                                        ConnectionTestStatus.SUCCESS -> "Test Again"
                                        ConnectionTestStatus.FAILED -> "Retry Test"
                                        else -> "Ping Server"
                                    },
                                    color = when (testStatus) {
                                        ConnectionTestStatus.SUCCESS -> Green400
                                        ConnectionTestStatus.FAILED -> Red400
                                        else -> MaterialTheme.colorScheme.onSurface
                                    },
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Default connects to production. Use a LAN IP (e.g. 192.168.1.100) for local dev.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            // ── Network Info ──────────────────────────────
            SettingsSection(title = "NETWORK INFO") {
                GlassCard {
                    Column(modifier = Modifier.padding(4.dp)) {
                        InfoRow(
                            icon = Icons.Default.Link,
                            title = "HTTP API",
                            subtitle = com.agentcall.app.data.api.ApiClient.getHttpBaseUrl(),
                        )
                        SettingsDivider()
                        InfoRow(
                            icon = Icons.Default.AltRoute,
                            title = "WebSocket",
                            subtitle = com.agentcall.app.data.api.ApiClient.getWsUrl("solo-user"),
                        )
                        SettingsDivider()
                        InfoRow(
                            icon = Icons.Default.Cloud,
                            title = "Connection Type",
                            subtitle = if (Regex("^[\\d.]+$").matches(serverHost)) "Local Network ($serverHost)" else "Production ($serverHost)",
                        )
                    }
                }
            }

            // ── About ─────────────────────────────────────
            SettingsSection(title = "ABOUT") {
                GlassCard {
                    InfoRow(
                        icon = Icons.Default.Info,
                        title = "Version",
                        subtitle = "1.0.0",
                        onClick = {},
                    )
                    SettingsDivider()
                    InfoRow(
                        icon = Icons.Default.Code,
                        title = "Stack",
                        subtitle = "Kotlin \u00B7 Jetpack Compose \u00B7 MCP",
                    )
                    SettingsDivider()
                        InfoRow(
                            icon = Icons.Default.Shield,
                            title = "Security",
                            subtitle = "HTTPS/WSS production · HTTP/WS local",
                        )
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 12.dp),
        )
        content()
    }
}

@Composable
private fun GlassCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
    ) {
        content()
    }
}

@Composable
private fun InfoRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
) {
    Surface(
        onClick = { onClick?.invoke() },
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            if (onClick != null) {
                Icon(Icons.Default.ChevronRight, "View $title", tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        modifier = Modifier.padding(start = 66.dp, end = 16.dp),
    )
}
