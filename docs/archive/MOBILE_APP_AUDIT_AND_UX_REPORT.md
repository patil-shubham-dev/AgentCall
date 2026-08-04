# Mobile App Audit and UX Report

## Part A — App Discovery

### 1. Screens and Composables

| Screen | File | What It Does | Status |
|--------|------|-------------|--------|
| **MainActivity** | `MainActivity.kt` | Entry point, bottom nav (Home/Settings), NavHost routing | Navigation works |
| **HomeScreen** | `home/HomeScreen.kt` | Shows connection badge + status text header, active call banner, and body (Loading/Disconnected/Reconnecting/Connected-waiting). Incoming calls launch `IncomingCallActivity`. Recent calls list at bottom. | Working |
| **SettingsScreen** | `settings/SettingsScreen.kt` | Server host text input, Connect/Reset buttons, Test Connection ping, Network Info (HTTP URL, WS URL, Connection Type), About section. | Working |
| **IncomingCallScreen** | `call/IncomingCallActivity.kt` | Full-screen incoming call UI: animated expanding rings, priority-colored glow, answer/decline/later buttons. Later shows radio-button picker (5/10/15/30/60 min). | Working |
| **ActiveCallScreen** | `call/CallActivity.kt` | Active call with chat messages (LazyColumn), context banner, waveform visualization, Record/Stop/Speaker/Repeat buttons, End Call. | Working |
| **CallService** | `call/CallService.kt` | Foreground service for WebRTC/audio, notification channel, incoming call notifications. | Working |

No Transcript screen exists. No separate Call History list view (compact list embedded in HomeScreen).

### 2. State Management — Three Sources of Truth

The connection status inconsistency has a concrete root cause: **three independent state sources** that can and do drift apart.

#### Source A — HomeScreen Header Badge
- **Owner:** `HomeViewModel` → `_uiState.connectionQuality` + `_uiState.isConnected`
- **Backend:** `SignalingClient.connectionState` (WebSocket lifecycle)
- **Health poll:** `api.getActiveCall("solo-user")` every 5s → quality rating (EXCELLENT/GOOD/FAIR/POOR)
- **Display:** Colored badge + label ("Excellent"/"Good"/"Fair"/"Poor" or "---" when unknown)
- **Status text:** set from `state.statusText` which tracks WebSocket state transitions

#### Source B — Settings "Test Connection" Result
- **Owner:** `SettingsViewModel` → `_testStatus` + `_testLatency`
- **Backend:** Independent `HTTP GET /api/v1/health` with 3s connect/read timeout
- **Display:** "✓ 612ms" or "Failed"
- **Completely independent** of WebSocket state or any other connection monitor

#### Source C — Settings "Connection Type" + "Backend Server" Status
- **Owner:** `SettingsViewModel._connectionStatus` (was hardcoded default `"Connected"` before fix)
- **Display:** Green/amber dot + "Connected" / "Reconnecting…" label
- **Connection Type:** Hardcoded string `"Local WiFi"` — does not reflect actual host

#### How Drift Manifests

```
Scenario: WebSocket drops but HTTP still works
  → Header badge: Red "Poor" or "Disconnected" (from Source A)
  → Header text: "Connection lost" (from Source A)
  → Settings badge: "Connected" (from Source C — was always "Connected")
  → Settings "Test Connection": "✓ 612ms" (from Source B — HTTP works)
  → Connection Type: "Local WiFi" (from Source C — hardcoded)
```

#### Fix Applied (see Part B)

### 3. "Connection Type" Field — Root Cause

`SettingsScreen.kt` line 381 (pre-fix):
```kotlin
InfoRow(
    icon = Icons.Default.Cloud,
    title = "Connection Type",
    subtitle = "Local WiFi",  // <-- HARDCODED
)
```

This was hardcoded regardless of what `ApiClient.serverHost` was set to. The strings.xml also had `"local_wifi"` → `"Local WiFi"` that was never referenced dynamically.

**Fix applied:** Now computes host type dynamically:
```kotlin
subtitle = if (Regex("^[\\d.]+$").matches(serverHost)) "Local Network ($serverHost)" else "Production ($serverHost)"
```

### 4. Accessibility Audit

| Finding | File | Line(s) | Severity |
|---------|------|---------|----------|
| `contentDescription = null` on navigation/search icons | `IncomingCallScreen.kt` | 234 (Call icon in avatar) | High |
| `contentDescription = null` on chevron/end-call icons | `HomeScreen.kt` | 254, 336, 440, 517, 602 | High |
| `contentDescription = null` on status icons | `HomeScreen.kt` | 337, 380, 440, 490 | Medium |
| `contentDescription = null` on waveform/action icons | `CallActivity.kt` | 142, 174, 287, 319 | Medium |
| No `semantics { }` blocks anywhere | All | — | Low (contentDescription is the primary mechanism) |
| 48dp minimum touch targets: **not met** for chevron icons (18dp), inline info rows without padding | `HomeScreen.kt` | 254 (18dp chevron), `RecentCallCard` chevron 18dp | High |
| Font scaling: `sp` units used throughout — text WILL reflow with system font size. No `maxLines` truncation testing with large fonts. | All | Various | Medium |
| Color-only status signals: Quality badge relies on color (Green=Excellent, Amber=Fair, Red=Poor). Text label alongside mitigates this partially. | `HomeScreen.kt` | 163-169 | Low |

### 5. Backend Features Missing UI Controls

Backend route | Implemented in UI? | Notes
---|---|---
`GET /api/v1/health` | Yes | Test Connection button
`GET /api/v1/ready` | No | Readiness probe, no reason to expose
`GET /api/v1/metrics` | No | Debug data, no UI
`POST /api/v1/calls` | No (by design) | Calls initiated by MCP server, not phone
`POST /api/v1/phone/register` | No | Auto-called by signaling, no status shown in UI
`POST /api/v1/calls/:callId/callback` | Yes | "Later" button in IncomingCallScreen
Notification preferences | No | No setting for ringer/priority/quiet hours
Retry/callback default timing | Partial | 5/10/15/30/60 min in IncomingCallScreen but no global default setting
Audio input source selection | No | Always uses default mic
Connection health reporting | No | No way to see latency/history from Settings

### 6. Multi-Profile / Account Model

**PROFILE_MODEL_DESIGN_PROPOSAL.md does not exist.** There is no multi-profile concept anywhere in the codebase:

- `HomeViewModel` hardcodes `"solo-user"` as user ID
- `SignalingClient` defaults to `"solo-user"`
- Single `ApiClient.serverHost` for backend URL
- Single `SERVICE_TOKEN` (not even used by the Android app — no auth header sent)
- Single `TokenManager` for access/refresh tokens (phone registration)
- No per-AI-provider profiles
- No per-profile transcript history
- No provider switching UI

---

## Part B — Bug Fix: Inconsistent Connection Status

### Changes Made

**File: `SettingsScreen.kt` (`SettingsViewModel`)**

1. **Injected `SignalingClient`** into `SettingsViewModel` so status reads from the real WebSocket state:
   ```kotlin
   class SettingsViewModel @Inject constructor(
       private val signalingClient: SignalingClient,
   ) : ViewModel()
   ```

2. **Added `init` block** to collect `signalingClient.connectionState` and drive `_connectionStatus` from it:
   ```kotlin
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
   ```

3. **Default changed** from `"Connected"` (always false) to `"Checking..."` (neutral until first state emission).

4. **Removed redundant `_connectionStatus` assignment** in `connect()` — the `init` block handles all updates.

**File: `SettingsScreen.kt` (Composable)**

5. **"Connection Type" label** changed from hardcoded `"Local WiFi"` to dynamic:
   ```kotlin
   subtitle = if (Regex("^[\\d.]+$").matches(serverHost))
       "Local Network ($serverHost)"
   else
       "Production ($serverHost)"
   ```

### Verification

- Before fix: Settings always showed "Connected" even when WebSocket was disconnected
- After fix: Settings reads from `SignalingClient.connectionState`, same source as HomeScreen
- "Connection Type" now shows "Production (agentcall-66ke.onrender.com)" for Render URL or "Local Network (192.168.1.x)" for LAN IPs

---

## Part C — UI/UX, Motion, and Accessibility

### Motion

Already present:
- HomeScreen: pulsing rings on waiting/connected state, particle animation, active call dot animation, shimmer loading skeleton, slide-in/fade for recent call cards
- CallActivity: message slide-in animations, press-scale on buttons, waveform glow
- IncomingCallScreen: 3-layer expanding ring animation, glow sweep, press-scale on action buttons

No changes needed for motion — transitions are already smooth and purposeful.

### Accessibility Deficiencies (unfixed in this pass — see findings in Part A.4)

Priority items for a dedicated accessibility pass:
1. Add `contentDescription` to all `null` icons (10+ locations)
2. Ensure 48dp minimum touch targets on clickable icons (chevrons at 18dp, inline info rows)
3. Test with system font size at maximum and fix text truncation/overflow
4. Verify color+text pairing on every status indicator (Quality badge already pairs color with text label — good)

---

## Part D — Multi-AI Profile Groundwork

### Current Status

`PROFILE_MODEL_DESIGN_PROPOSAL.md` was never produced. The app has a single flat user model with no profile concept.

### What Would Be Required

This is a genuinely substantial change touching every layer:

**Data Model:**
- Each AI provider profile needs: `profile_id`, `provider_type` (openai/anthropic/google/etc), `display_name`, `api_key` (encrypted), `base_url`, `model` (gpt-4/claude/etc), `avatar_color`, and its own `transcript_history`
- Backend: new `profiles` table, `profile_id` column on `calls` table, per-profile transcript storage
- Android: `Profile` data class, `ProfileDao`/`ProfileRepository`, per-profile WebSocket connections

**Auth:**
- Each profile needs its own API key (not the shared SERVICE_TOKEN)
- Keys stored via `EncryptedSharedPreferences` or Android Keystore per profile
- No OAuth/user accounts exist — would need to be built

**Home Screen:**
- Profile selector (tabs/dropdown/carousel at top)
- Per-profile recent calls list filtered by `profile_id`
- "Add Profile" flow with provider selection + API key entry

**Settings:**
- Profile management (add/edit/delete profiles)
- Per-profile configuration (model, voice, timeout, etc.)
- Test connection per profile

**Signaling:**
- `profile_id` must be sent with WebSocket registration
- Backend routes calls to correct profile (currently all calls are "solo-user")

### Recommendation

This should be a separate deliberate phase. Estimated scope: 3-5 files on backend (migration, routes, models), 8-12 files on Android (screens, viewmodels, data layer, DI), 1-2 on MCP server. The API key storage security review alone needs a dedicated pass.

---

## Summary of Delivered Changes

| File | Change | Status |
|------|--------|--------|
| `mobile/android/app/.../settings/SettingsScreen.kt` | Injected SignalingClient into SettingsViewModel for real connection state | ✅ Built & installed |
| `mobile/android/app/.../settings/SettingsScreen.kt` | Default `"Connected"` → `"Checking..."` + init block subscribing to WebSocket state | ✅ Built & installed |
| `mobile/android/app/.../settings/SettingsScreen.kt` | "Connection Type" `"Local WiFi"` → dynamic Production/Local Network | ✅ Built & installed |
| `mobile/android/app/build.gradle.kts` | DEFAULT_HOST `10.0.2.2` → `agentcall-66ke.onrender.com` | ✅ Built & installed |
