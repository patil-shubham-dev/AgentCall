package com.agentcall.app.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.agentcall.app.call.SignalingClient
import com.agentcall.app.call.VoiceBridgeEvent
import com.agentcall.app.data.api.AgentStatusResponse
import com.agentcall.app.data.api.ApiClient
import com.agentcall.app.data.api.ApiService
import com.agentcall.app.data.api.AiKeyItem
import com.agentcall.app.data.database.entity.AiProfileEntity
import com.agentcall.app.data.repository.CallRepository
import com.agentcall.app.settings.BatteryOptimizationManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isConnected: Boolean = false,
    val isReconnecting: Boolean = false,
    val statusText: String = "Connecting...",
    val isLoading: Boolean = true,
)

enum class AiPresence { ONLINE, BUSY, OFFLINE }

fun AiKeyItem.toPresence(): AiPresence = when {
    busy -> AiPresence.BUSY
    online -> AiPresence.ONLINE
    else -> AiPresence.OFFLINE
}

object ServerConfigEvent {
    val reconnectRequests = MutableStateFlow(0)
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val signalingClient: SignalingClient,
    private val repository: CallRepository,
    private val batteryOptimizationManager: BatteryOptimizationManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val profiles: StateFlow<List<AiProfileEntity>> = repository
        .getAllProfiles()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** AI availability, keyed by AI key name (matches profile.name). */
    private val _aiStatus = MutableStateFlow<Map<String, AiKeyItem>>(emptyMap())
    val aiStatus: StateFlow<Map<String, AiKeyItem>> = _aiStatus.asStateFlow()

    /**
     * Per-agent online status + last-seen (backlog item 2), keyed by agent
     * name. Fetched on connect and profile changes through a short-TTL cache
     * (battery audit M5) so the Connected event and a profiles emission that
     * land together cost one GET per agent, not two rounds of N.
     * Errors degrade to an empty map (chips simply don't show).
     */
    private val _agentStatus = MutableStateFlow<Map<String, AgentStatusResponse>>(emptyMap())
    val agentStatus: StateFlow<Map<String, AgentStatusResponse>> = _agentStatus.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    /**
     * One-shot battery-optimization onboarding (ColorOS Doze finding): shown
     * once, after the app is actually set up (connected + at least one agent)
     * and the user hasn't already dismissed it. Non-blocking — a banner, not
     * a gate. Reachable any time from Settings > Call Reliability.
     */
    private val _showBatteryBanner = MutableStateFlow(false)
    val showBatteryBanner: StateFlow<Boolean> = _showBatteryBanner.asStateFlow()

    private var eventsJob: kotlinx.coroutines.Job? = null

    // Battery audit M5: TTL gate for the N-per-agent status GETs. Both the
    // Connected handler and the profiles collector fire on the same connect;
    // within AGENT_STATUS_TTL_MS only the first round trips.
    @Volatile
    private var lastAgentStatusFetchMs = 0L

    init {
        viewModelScope.launch {
            // Battery audit H1: isConnected is now derived from connection
            // state itself, not from an event that was never emitted.
            // CONNECTING leaves the flag alone (transient flap must not blink
            // the UI false); DISCONNECTED and RECONNECTING are truthfully
            // "not connected" — the socket cannot deliver anything right now.
            signalingClient.connectionState.collect { state ->
                val connected = when (state) {
                    SignalingClient.ConnectionState.CONNECTED -> true
                    SignalingClient.ConnectionState.DISCONNECTED -> false
                    SignalingClient.ConnectionState.RECONNECTING -> false
                    SignalingClient.ConnectionState.CONNECTING -> _uiState.value.isConnected
                }
                _uiState.value = _uiState.value.copy(
                    isReconnecting = state == SignalingClient.ConnectionState.RECONNECTING,
                    isConnected = connected,
                    statusText = when (state) {
                        SignalingClient.ConnectionState.CONNECTING -> "Connecting..."
                        SignalingClient.ConnectionState.RECONNECTING -> "Reconnecting..."
                        SignalingClient.ConnectionState.DISCONNECTED -> "Disconnected"
                        SignalingClient.ConnectionState.CONNECTED -> _uiState.value.statusText
                    },
                )
            }
        }

        viewModelScope.launch {
            combine(
                signalingClient.connectionState,
                profiles,
            ) { connection, agents ->
                connection == SignalingClient.ConnectionState.CONNECTED &&
                    agents.isNotEmpty() &&
                    batteryOptimizationManager.shouldShowBanner()
            }.collect { visible ->
                _showBatteryBanner.value = visible
            }
        }

        connect()

        viewModelScope.launch {
            ServerConfigEvent.reconnectRequests.collect { count ->
                if (count > 0) {
                    delay(200)
                    reconnect()
                }
            }
        }

        // A new agent profile (or a first sync) refreshes its status chip.
        // Profiles come from Room, not the socket — the grid must render even
        // while the WS is CONNECTING/RECONNECTING (prod proxy drops sockets),
        // so the loading skeleton clears as soon as local data is available.
        viewModelScope.launch {
            profiles.collect { list ->
                refreshAgentStatuses()
                if (_uiState.value.isLoading) {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            }
        }

        viewModelScope.launch {
            // Battery audit H1: the availability refresh exists to keep the
            // Home chips honest while a user can see them. Two lifecycle-aware
            // gates replace the old always-running loop:
            //  1. ProcessLifecycleOwner.repeatOnLifecycle(STARTED) — going to
            //     the background CANCELS this coroutine tree, so nothing fires
            //     for however long the process survives (critical for users
            //     who granted battery-optimization exemption, where Doze will
            //     not mask background work).
            //  2. collectLatest on connectionState — each cycle is keyed to an
            //     actual CONNECTED emission; disconnected/parked costs zero
            //     requests because rings arrive via FCM/WS push regardless.
            // Cadence itself lives in the pure AvailabilityPollCadence object.
            ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var failureStreak = 0
                signalingClient.connectionState.collectLatest { state ->
                    if (state != SignalingClient.ConnectionState.CONNECTED) return@collectLatest
                    val ok = runCatching {
                        ApiClient.ensurePhoneToken()
                        ApiClient.create<ApiService>().listAiKeys()
                    }.onSuccess { response ->
                        _aiStatus.value = response.keys.associateBy { it.name }
                        repository.reconcileProfileKeyIds()
                    }.isSuccess
                    failureStreak = if (ok) 0 else failureStreak + 1
                    delay(AvailabilityPollCadence.computeDelayMs(ok, failureStreak))
                }
            }
        }
    }

    fun connect() {
        signalingClient.connect()
        eventsJob?.cancel()
        eventsJob = viewModelScope.launch {
            signalingClient.events.collect { event ->
                when (event) {
                    is VoiceBridgeEvent.Connected -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = true,
                            isReconnecting = false,
                            statusText = "Ready",
                            isLoading = false,
                        )
                        refreshAgentStatuses()
                    }
                    is VoiceBridgeEvent.CallEnded -> {
                        repository.saveCallEnded(event.callId, "ended")
                    }
                    is VoiceBridgeEvent.Disconnected -> {
                        _uiState.value = _uiState.value.copy(
                            isConnected = false,
                            isReconnecting = false,
                            statusText = "Disconnected",
                        )
                    }
                    is VoiceBridgeEvent.Error -> {
                        _snackbarEvents.tryEmit("Error: ${event.message}")
                    }
                    else -> {}
                }
            }
        }
    }

    fun reconnect() {
        signalingClient.disconnect()
        _uiState.value = _uiState.value.copy(
            isConnected = false,
            isReconnecting = true,
            statusText = "Reconnecting...",
        )
        viewModelScope.launch {
            delay(300)
            connect()
        }
    }

    /**
     * Permanently dismiss the one-time battery-optimization banner for this
     * install. The onboarding stays reachable from Settings > Call Reliability.
     */
    fun dismissBatteryBanner() {
        batteryOptimizationManager.onboardingShown = true
        _showBatteryBanner.value = false
    }

    /**
     * Permanently delete an agent from this device: profile + call history +
     * transcripts, nothing else. No server call — the MCP key on the
     * AI/harness side keeps working, so the agent can still call again (a
     * fresh profile is recreated on the next ring). Delete the key in
     * Settings to remove the agent from the system entirely. Allowed while a
     * call is live: the call itself is unaffected, only its history row is
     * gone.
     */
    fun deleteAgent(profile: AiProfileEntity) {
        viewModelScope.launch {
            try {
                repository.deleteAgent(profile)
                _aiStatus.value = _aiStatus.value - profile.name
                _agentStatus.value = _agentStatus.value - profile.name
                _snackbarEvents.tryEmit("Deleted ${profile.name} from this device")
            } catch (e: Exception) {
                _snackbarEvents.tryEmit(e.message ?: "Couldn't delete ${profile.name} — try again")
            }
        }
    }

    /**
     * One status GET per agent, error-tolerant: any failure leaves the
     * existing map (or an empty map) — never a crash, never a spinner.
     * TTL-gated (battery audit M5): redundant callers inside the window
     * return without touching the network. A batch /agents/statuses endpoint
     * is the proper long-term fix — tracked as follow-up work, not built here.
     */
    fun refreshAgentStatuses() {
        viewModelScope.launch {
            val agents = profiles.value
            if (agents.isEmpty()) return@launch
            val now = System.currentTimeMillis()
            if (now - lastAgentStatusFetchMs < AGENT_STATUS_TTL_MS) return@launch
            lastAgentStatusFetchMs = now
            // The status route requires a phone token; mint it first (the poll
            // loop does the same) or every GET would 401 and the chip would
            // never populate.
            runCatching { ApiClient.ensurePhoneToken() }
            val results = mutableMapOf<String, AgentStatusResponse>()
            for (profile in agents) {
                val status = runCatching {
                    ApiClient.create<ApiService>().getAgentStatus(profile.name)
                }.getOrNull()
                if (status != null) results[profile.name] = status
            }
            if (results.isNotEmpty()) {
                _agentStatus.value = _agentStatus.value + results
            }
        }
    }

    private companion object {
        const val AGENT_STATUS_TTL_MS = 30_000L
    }
}