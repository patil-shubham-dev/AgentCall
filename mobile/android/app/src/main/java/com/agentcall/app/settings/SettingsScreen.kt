package com.agentcall.app.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.AltRoute
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import android.app.TimePickerDialog
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.agentcall.app.BuildConfig
import com.agentcall.app.R
import com.agentcall.app.settings.CallerTuneManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.AiKeyCreateRequest
import com.agentcall.app.data.api.AiKeyCreateResponse
import com.agentcall.app.data.api.AiKeyItem
import com.agentcall.app.call.FcmRegistrationStore
import com.agentcall.app.data.repository.CallRepository
import com.agentcall.app.ui.composables.AmbientBackground
import com.agentcall.app.ui.composables.Plate
import com.agentcall.app.ui.composables.SectionLabel
import com.agentcall.app.ui.theme.*
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.coroutines.resume

enum class ConnectionTestStatus {
    IDLE, TESTING, SUCCESS, FAILED
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val callRepository: CallRepository,
    val callerTuneManager: CallerTuneManager,
    val quietHoursManager: QuietHoursManager,
) : ViewModel() {
    private val _serverHost = MutableStateFlow(ApiClient.serverHost)
    val serverHost: StateFlow<String> = _serverHost.asStateFlow()

    // One-shot health + FCM status (FCM-only idle model — no persistent WS).
    // "Ready" means backend reachable AND last POST /phone/fcm-token succeeded.
    private val _connectionStatus = MutableStateFlow("Checking...")
    val connectionStatus: StateFlow<String> = _connectionStatus.asStateFlow()

    private val _fcmDetail = MutableStateFlow(FcmRegistrationStore.getStatusForSettings())
    val fcmDetail: StateFlow<String> = _fcmDetail.asStateFlow()

    private val _testStatus = MutableStateFlow(ConnectionTestStatus.IDLE)
    val testStatus: StateFlow<ConnectionTestStatus> = _testStatus.asStateFlow()

    private val _testLatency = MutableStateFlow(0L)
    val testLatency: StateFlow<Long> = _testLatency.asStateFlow()

    private val _aiKeys = MutableStateFlow<List<AiKeyItem>>(emptyList())
    val aiKeys: StateFlow<List<AiKeyItem>> = _aiKeys.asStateFlow()

    private val _aiKeysLoading = MutableStateFlow(false)
    val aiKeysLoading: StateFlow<Boolean> = _aiKeysLoading.asStateFlow()

    private val _aiKeysError = MutableStateFlow<String?>(null)
    val aiKeysError: StateFlow<String?> = _aiKeysError.asStateFlow()

    private val _createdKey = MutableStateFlow<AiKeyCreateResponse?>(null)
    val createdKey: StateFlow<AiKeyCreateResponse?> = _createdKey.asStateFlow()

    private val _creatingKey = MutableStateFlow(false)
    val creatingKey: StateFlow<Boolean> = _creatingKey.asStateFlow()

    init {
        refreshConnectionStatus()
    }

    fun refreshConnectionStatus() {
        viewModelScope.launch {
            _connectionStatus.value = "Checking..."
            _fcmDetail.value = FcmRegistrationStore.getStatusForSettings()
            val backendOk = checkBackendHealth()
            val fcmOk = checkFcmRegistered()
            _fcmDetail.value = FcmRegistrationStore.getStatusForSettings()
            _connectionStatus.value = when {
                backendOk && fcmOk -> "Ready — Backend reachable, notifications active"
                backendOk && !fcmOk -> "Backend reachable, but push notifications not registered — calls may not ring"
                !backendOk && fcmOk -> "Backend unreachable — push may not work"
                else -> "Offline — Backend unreachable"
            }
        }
    }

    private suspend fun checkBackendHealth(): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = java.net.URL(ApiClient.getHealthUrl())
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (_: Exception) {
            false
        }
    }

    // Now checks actual backend POST success, not just local token existence.
    // A phone that has a token locally but never successfully POSTed it will
    // never ring — this was previously invisible.
    private suspend fun checkFcmRegistered(): Boolean {
        // First, ensure we have a local token at all.
        val hasLocalToken = suspendCancellableCoroutine<Boolean> { cont ->
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                cont.resume(task.isSuccessful && !task.result.isNullOrBlank())
            }
        }
        if (!hasLocalToken) return false
        // Then check that the last POST to /phone/fcm-token actually succeeded.
        // FcmRegistrationStore is updated on every successful register (App,
        // onNewToken, ws-connected). If never succeeded, show warning.
        return FcmRegistrationStore.isRegistered()
    }

    fun updateServerHost(host: String) {
        _serverHost.value = host
    }

    fun connect() {
        val host = _serverHost.value.trim().ifBlank { ApiClient.serverHost }
        ApiClient.setServerHost(host)
        // Re-check health immediately after host change; no WS reconnect needed in FCM-only idle.
        refreshConnectionStatus()
        com.agentcall.app.home.ServerConfigEvent.reconnectRequests.value++
    }

    fun testConnection() {
        _testStatus.value = ConnectionTestStatus.TESTING
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val start = System.currentTimeMillis()
                try {
                    val url = java.net.URL(ApiClient.getHealthUrl())
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
            // Also refresh the main status label so health + FCM are re-checked.
            refreshConnectionStatus()
        }
    }

    fun resetTest() {
        _testStatus.value = ConnectionTestStatus.IDLE
        _testLatency.value = 0L
    }

    fun loadAiKeys() {
        viewModelScope.launch {
            _aiKeysLoading.value = true
            _aiKeysError.value = null
            try {
                ApiClient.ensurePhoneToken()
                _aiKeys.value = withContext(Dispatchers.IO) {
                    ApiClient.create<ApiService>().listAiKeys().keys
                }
            } catch (e: Exception) {
                _aiKeysError.value = e.message ?: "Failed to load AI keys"
            } finally {
                _aiKeysLoading.value = false
            }
        }
    }

    fun createAiKey(name: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            _creatingKey.value = true
            _aiKeysError.value = null
            try {
                ApiClient.ensurePhoneToken()
                val created = withContext(Dispatchers.IO) {
                    ApiClient.create<ApiService>().createAiKey(AiKeyCreateRequest(name))
                }
                _createdKey.value = created
                onDone()
            } catch (e: retrofit2.HttpException) {
                // 409 = unique-name rule (POST /api/v1/ai/keys rejects a name
                // another key already has) — surface it as a real instruction,
                // not an opaque "HTTP 409 Conflict".
                _aiKeysError.value = if (e.code() == 409) {
                    "An agent with this name already exists — pick a different name."
                } else {
                    e.message ?: "Failed to create AI key"
                }
            } catch (e: Exception) {
                _aiKeysError.value = e.message ?: "Failed to create AI key"
            } finally {
                _creatingKey.value = false
            }
        }
    }

    fun clearCreatedKey() {
        _createdKey.value = null
    }

    fun deleteAiKey(keyId: String) {
        viewModelScope.launch {
            _aiKeysError.value = null
            try {
                ApiClient.ensurePhoneToken()
                withContext(Dispatchers.IO) {
                    ApiClient.create<ApiService>().deleteAiKey(keyId)
                }
                _aiKeys.value = _aiKeys.value.filter { it.keyId != keyId }
            } catch (e: Exception) {
                _aiKeysError.value = e.message ?: "Failed to delete AI key"
            }
        }
    }
}

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onReconnect: () -> Unit = {},
    onOpenBatteryHelp: () -> Unit = {},
    onBack: () -> Unit = {},
) {
    val serverHost by viewModel.serverHost.collectAsStateWithLifecycle()
    val connectionStatus by viewModel.connectionStatus.collectAsStateWithLifecycle()
    val fcmDetail by viewModel.fcmDetail.collectAsStateWithLifecycle()
    val testStatus by viewModel.testStatus.collectAsStateWithLifecycle()
    val testLatency by viewModel.testLatency.collectAsStateWithLifecycle()
    val aiKeys by viewModel.aiKeys.collectAsStateWithLifecycle()
    val aiKeysLoading by viewModel.aiKeysLoading.collectAsStateWithLifecycle()
    val aiKeysError by viewModel.aiKeysError.collectAsStateWithLifecycle()
    val createdKey by viewModel.createdKey.collectAsStateWithLifecycle()
    val creatingKey by viewModel.creatingKey.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    var declineTemplate by remember { mutableStateOf(MessageTemplates.declineMessage(context)) }
    var laterTemplate by remember { mutableStateOf(MessageTemplates.laterTemplateRaw(context)) }

    var showAddAiDialog by remember { mutableStateOf(false) }
    var keyToDelete by remember { mutableStateOf<AiKeyItem?>(null) }

    LaunchedEffect(Unit) { viewModel.loadAiKeys() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Header with ambient background behind — reduced top inset so header sits closer to status bar
        Box(modifier = Modifier.height(68.dp)) {
            AmbientBackground(
                accentColor = Indigo500,
                density = 0.6f,
                speedMultiplier = 0.5f,
                modifier = Modifier.fillMaxSize(),
            )
            Column(modifier = Modifier.fillMaxSize()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
                Text(
                    text = "Configure your connection",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Server Connection ────────────────────────
            SettingsSection(title = "SERVER CONNECTION") {
                GlassCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Status row — FCM-only idle: no persistent WS. Green = backend + FCM,
                        // Amber = backend ok but FCM missing, Red = backend unreachable.
                        val lampColor = when {
                            connectionStatus.startsWith("Ready") -> Green400
                            connectionStatus.startsWith("Backend reachable, but") -> Amber400
                            connectionStatus == "Checking..." -> Amber400
                            else -> Red400
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val dotAnim by rememberInfiniteTransition(label = "connDot").animateFloat(
                                0f, 1f,
                                infiniteRepeatable(tween(1500, easing = EaseInOutSine), RepeatMode.Reverse),
                                label = "connDot"
                            )
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(lampColor.copy(alpha = 0.5f + dotAnim * 0.5f)),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Backend Server",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { viewModel.refreshConnectionStatus() },
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(Icons.Default.Refresh, "Refresh status", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = connectionStatus,
                            style = MaterialTheme.typography.labelSmall,
                            color = lampColor,
                            maxLines = 3,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Push: $fcmDetail",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 2,
                        )

                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(color = Slate700.copy(alpha = 0.6f))
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
                            placeholder = { Text(BuildConfig.DEFAULT_HOST, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                            leadingIcon = {
                                Icon(Icons.Default.Computer, "Server address", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
                                cursorColor = MaterialTheme.colorScheme.primary,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
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
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ),
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
                                        Icon(Icons.Default.Sync, "Testing", tint = MaterialTheme.colorScheme.primary,
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

            // ── Call Reliability ────────────────────────
            SettingsSection(title = "CALL RELIABILITY") {
                GlassCard {
                    Column(modifier = Modifier.padding(4.dp)) {
                        InfoRow(
                            icon = Icons.Default.BatteryAlert,
                            title = "Battery optimization & autostart",
                            subtitle = "Ensure AgentCall can wake for calls",
                            onClick = onOpenBatteryHelp,
                        )
                    }
                }
            }

            // ── Caller Tune ───────────────────────────
            val context = LocalContext.current
            val callerTuneManager = viewModel.callerTuneManager
            val callerTunePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri: Uri? ->
                if (uri != null) {
                    val cursor = context.contentResolver.query(uri, null, null, null, null)
                    val name = cursor?.use { c ->
                        val nameIndex = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && c.moveToFirst()) c.getString(nameIndex) else "Custom tune"
                    } ?: "Custom tune"
                    callerTuneManager.setUri(uri, name)
                }
            }

            SettingsSection(title = "CALLER TUNE") {
                GlassCard {
                    Column(modifier = Modifier.padding(4.dp)) {
                        InfoRow(
                            icon = Icons.Default.MusicNote,
                            title = "Ringtone",
                            subtitle = callerTuneManager.label,
                            onClick = {
                                callerTunePicker.launch(arrayOf("audio/*"))
                            },
                        )
                        SettingsDivider()
                        InfoRow(
                            icon = Icons.Default.RestartAlt,
                            title = "Reset to default",
                            subtitle = null,
                            onClick = { callerTuneManager.resetToDefault() },
                        )
                    }
                }
            }

            // ── Call Messages ────────────────────────────
            CollapsibleSettingsSection(title = "CALL MESSAGES") {
                GlassCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        var declineSaved by remember { mutableStateOf(false) }
                        var laterSaved by remember { mutableStateOf(false) }
                        var laterMissingPlaceholder by remember { mutableStateOf(false) }
                        TemplateEditor(
                            title = "Decline message",
                            subtitle = "Sent to the AI when you decline a call",
                            value = declineTemplate,
                            onValueChange = { declineTemplate = it },
                            onSave = {
                                MessageTemplates.setDeclineMessage(context, declineTemplate)
                                declineSaved = true
                            },
                            onReset = {
                                declineTemplate = MessageTemplates.DECLINE_DEFAULT
                                MessageTemplates.resetDecline(context)
                            },
                            saved = declineSaved,
                        )
                        LaunchedEffect(declineSaved) {
                            if (declineSaved) {
                                delay(2000)
                                declineSaved = false
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        TemplateEditor(
                            title = "Call-back-later message",
                            subtitle = "Sent when you pick a time; {X} is replaced with the minutes you choose",
                            value = laterTemplate,
                            onValueChange = {
                                laterTemplate = it
                                laterMissingPlaceholder = false
                            },
                            onSave = {
                                if ("{X}" !in laterTemplate) {
                                    laterMissingPlaceholder = true
                                } else {
                                    MessageTemplates.setLaterTemplate(context, laterTemplate)
                                    laterSaved = true
                                }
                            },
                            onReset = {
                                laterTemplate = MessageTemplates.LATER_DEFAULT
                                MessageTemplates.resetLater(context)
                                laterMissingPlaceholder = false
                            },
                            saved = laterSaved,
                            warning = if (laterMissingPlaceholder)
                                "Keep the {X} placeholder so the chosen minutes get substituted." else null,
                        )
                        LaunchedEffect(laterSaved) {
                            if (laterSaved) {
                                delay(2000)
                                laterSaved = false
                            }
                        }
                    }
                }
            }

            // ── Quiet Hours (backlog item 6) ────────────────────
            SettingsSection(title = "QUIET HOURS") {
                QuietHoursCard(manager = viewModel.quietHoursManager)
            }

            // ── AI Connections ────────────────────────────
            SettingsSection(title = "AI CONNECTIONS") {
                GlassCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Connect AI assistants (ChatGPT, Claude, Opencode, ...) to your phone so they can call you.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        if (aiKeysError != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Error, "Error", tint = Red400, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = aiKeysError.orEmpty(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Red400,
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (aiKeysLoading && aiKeys.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Loading keys...", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        aiKeys.forEach { key ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Default.SmartToy,
                                    "AI",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = key.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                )
                                IconButton(onClick = { keyToDelete = key }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        "Delete ${key.name}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }

                        if (createdKey != null) {
                            Spacer(modifier = Modifier.height(12.dp))
                            CreatedKeyCard(
                                created = createdKey!!,
                                onDismiss = { viewModel.clearCreatedKey() },
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = { showAddAiDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                            ),
                        ) {
                            Icon(Icons.Default.SmartToy, "Add AI", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Add AI", style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }

            if (showAddAiDialog) {
                AddAiDialog(
                    creating = creatingKey,
                    onDismiss = { if (!creatingKey) showAddAiDialog = false },
                    onCreate = { name ->
                        viewModel.createAiKey(name) { showAddAiDialog = false }
                    },
                )
            }

            keyToDelete?.let { key ->
                AlertDialog(
                    onDismissRequest = { keyToDelete = null },
                    title = { Text("Delete ${key.name}?") },
                    text = { Text("The AI will no longer be able to call your phone with this key.") },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.deleteAiKey(key.keyId)
                            keyToDelete = null
                        }) {
                            Text("Delete", color = Red400)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { keyToDelete = null }) { Text("Cancel") }
                    },
                )
            }

            // ── Network Info ──────────────────────────────
            CollapsibleSettingsSection(title = "NETWORK INFO") {
                GlassCard {
                    Column(modifier = Modifier.padding(4.dp)) {
                        InfoRow(
                            icon = Icons.Default.Link,
                            title = "HTTP API",
                            subtitle = com.agentcall.app.data.api.ApiClient.getHttpBaseUrl(),
                        )
                        SettingsDivider()
                        InfoRow(
                            icon = Icons.AutoMirrored.Filled.AltRoute,
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
            CollapsibleSettingsSection(title = "ABOUT") {
                GlassCard {
                    Column(modifier = Modifier.padding(4.dp)) {
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
            }
            // ── Privacy & Data (backlog item 17) ───────────────
            SettingsSection(title = "PRIVACY & DATA") {
                GlassCard {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Your voice stays on your device.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Speech recognition and spoken replies use your phone's built-in " +
                                "speech services. The AgentCall backend never receives audio — only " +
                                "the text transcript. Transcripts are kept in memory on the server " +
                                "for a short window (about an hour after a call) and are never sold " +
                                "or shared.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 18.sp,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Note: some device speech engines may process audio through their own " +
                                "cloud services (e.g. Google's on-device/online recognizer). To keep " +
                                "voice fully on-device, set your device's speech service to offline mode.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            lineHeight = 16.sp,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

@Composable
private fun AddAiDialog(
    creating: Boolean,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add AI") },
        text = {
            Column {
                Text(
                    text = "Give this AI a name. It will appear on your phone when it calls.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("e.g. ChatGPT, Claude, Opencode", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    enabled = !creating,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        cursorColor = Indigo400,
                        focusedBorderColor = Indigo600,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                    shape = RoundedCornerShape(12.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name.trim()) },
                enabled = name.isNotBlank() && !creating,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                if (creating) {
                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text("Cancel") }
        },
    )
}

@Composable
private fun CreatedKeyCard(
    created: AiKeyCreateResponse,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val mcpUrl = com.agentcall.app.data.api.ApiClient.getHttpBaseUrl()
        .replace(Regex("/api/v1/?$"), "") + "/mcp"
    val headerSnippet = "claude mcp add --transport http $mcpUrl " +
        "--header \"Authorization: Bearer ${created.key}\""
    val urlSnippet = "$mcpUrl?key=${created.key}"
    // Ready-to-paste MCP config blocks. "$schema" needs ${'$'} escaping inside
    // the Kotlin template; the URL + key interpolate directly.
    val desktopJson = """{
  "mcpServers": {
    "agentcall": {
      "url": "$mcpUrl",
      "headers": { "Authorization": "Bearer ${created.key}" }
    }
  }
}"""
    val opencodeJson = """{
  "${'$'}schema": "https://opencode.ai/config.json",
  "mcp": {
    "agentcall": {
      "type": "remote",
      "url": "$mcpUrl",
      "enabled": true,
      "headers": { "Authorization": "Bearer ${created.key}" }
    }
  }
}"""

    fun copy(text: String) {
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("AI key", text))
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Indigo600.copy(alpha = 0.12f),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircle, "Key created", tint = Green400, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${created.name} created",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "This key is shown only once. Copy it now — it is not stored on the server.",
                style = MaterialTheme.typography.labelSmall,
                color = Red400,
            )

            Spacer(modifier = Modifier.height(10.dp))
            KeySnippet(text = created.key, copyLabel = "Copy key", onCopy = { copy(created.key) })

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Header-based clients (Claude, Opencode, Cursor)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            KeySnippet(text = headerSnippet, copyLabel = "Copy", onCopy = { copy(headerSnippet) })

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "ChatGPT (set Authentication to \u201CNone\u201D and paste this URL)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            KeySnippet(text = urlSnippet, copyLabel = "Copy", onCopy = { copy(urlSnippet) })

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "claude_desktop_config.json (Claude Desktop)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            KeySnippet(text = desktopJson, copyLabel = "Copy JSON", onCopy = { copy(desktopJson) }, maxLines = 10)

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "opencode.json (OpenCode)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            KeySnippet(text = opencodeJson, copyLabel = "Copy JSON", onCopy = { copy(opencodeJson) }, maxLines = 10)

            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text("Done", color = Indigo400)
                }
            }
        }
    }
}

@Composable
private fun KeySnippet(
    text: String,
    copyLabel: String,
    onCopy: () -> Unit,
    maxLines: Int = 4,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = maxLines,
            )
            Spacer(modifier = Modifier.height(6.dp))
            TextButton(onClick = onCopy, modifier = Modifier.padding(0.dp)) {
                Icon(Icons.Default.ContentCopy, "Copy", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(copyLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(modifier = Modifier.height(24.dp))
        SectionLabel(title = title)
        Spacer(modifier = Modifier.height(10.dp))
        content()
    }
}

/**
 * Section label row with a chevron; the plate below expands/collapses
 * smoothly. Collapsed by default (Call Messages, Network Info, About).
 */
@Composable
private fun CollapsibleSettingsSection(title: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "chevron",
    )
    Column(modifier = Modifier.padding(horizontal = 20.dp)) {
        Spacer(modifier = Modifier.height(24.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radii.Pill))
                .clickable { expanded = !expanded }
                .padding(vertical = 14.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(title = title, modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp).rotate(chevronRotation),
            )
        }
        Spacer(modifier = Modifier.height(10.dp))
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            content()
        }
    }
}

@Composable
internal fun GlassCard(content: @Composable () -> Unit) {
    Plate(containerShape = RoundedCornerShape(Radii.Panel)) {
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
                Spacer(modifier = Modifier.width(8.dp))
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

/**
 * Global quiet hours editor (backlog item 6). Stored in SharedPreferences via
 * QuietHoursManager — survives app restarts; the backend is never touched.
 */
@Composable
private fun QuietHoursCard(manager: QuietHoursManager) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(manager.globalEnabled) }
    var startMin by remember { mutableIntStateOf(manager.globalStartMinutes) }
    var endMin by remember { mutableIntStateOf(manager.globalEndMinutes) }

    fun openPicker(initial: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            context,
            { _, hour, minute -> onPicked(hour * 60 + minute) },
            initial / 60,
            initial % 60,
            true,
        ).show()
    }

    GlassCard {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Nightlight, "Quiet hours", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Quiet hours",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = "Calls ring silently in this window, and the calling AI is told the window.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { on -> enabled = on; manager.globalEnabled = on },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                        uncheckedThumbColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                        uncheckedBorderColor = MaterialTheme.colorScheme.outline,
                    ),
                )
            }

            if (enabled) {
                Spacer(modifier = Modifier.height(12.dp))
                InfoRow(
                    icon = Icons.Default.Schedule,
                    title = "Start",
                    subtitle = QuietHoursManager.minutesToLabel(startMin),
                    onClick = { openPicker(startMin) { startMin = it; manager.setGlobalRange(it, endMin) } },
                )
                InfoRow(
                    icon = Icons.Default.Schedule,
                    title = "End",
                    subtitle = QuietHoursManager.minutesToLabel(endMin),
                    onClick = { openPicker(endMin) { endMin = it; manager.setGlobalRange(startMin, it) } },
                )
                Spacer(modifier = Modifier.height(4.dp))

                val nowMinutes = java.time.LocalTime.now().toSecondOfDay() / 60
                val isActiveNow = QuietHoursManager.isMinutesInRange(nowMinutes, startMin, endMin)
                val wrap = if (startMin > endMin) " (overnight)" else ""
                Text(
                    text = if (isActiveNow) {
                        "Quiet hours are ACTIVE now — incoming rings are silent."
                    } else {
                        "Quiet hours apply ${
                            QuietHoursManager.minutesToLabel(startMin)
                        } → ${
                            QuietHoursManager.minutesToLabel(endMin)
                        }$wrap. You can still answer an important call."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isActiveNow) Amber400 else MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp,
                )
            }
        }
    }
}

@Composable
private fun TemplateEditor(
    title: String,
    subtitle: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    onReset: () -> Unit,
    saved: Boolean = false,
    warning: String? = null,
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            isError = warning != null,
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
        if (warning != null) {
            Text(
                text = warning,
                style = MaterialTheme.typography.labelSmall,
                color = Red400,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onReset) {
                Icon(Icons.Default.RestartAlt, "Reset to default", modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Reset to default", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.weight(1f))
            AnimatedContent(
                targetState = saved,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "savedState",
            ) { isSaved ->
                if (isSaved) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, "Saved", tint = Green400, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Saved", style = MaterialTheme.typography.labelMedium, color = Green400)
                    }
                } else {
                    Button(
                        onClick = onSave,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ),
                    ) {
                        Icon(Icons.Default.Save, "Save message", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}
