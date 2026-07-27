# AgentCall v2 — Android Application Specification

> Complete redesign of the Android app around the Communication OS philosophy.
> Not a voice app. Not a bridge. A communication inbox for AI→human messages.

---

## 0. Current State Audit

### What Exists (22 files)

```
AgentCallApp.kt           — Application class, notification channels
MainActivity.kt           — Bottom nav (Home, Settings)
call/
  ├── CallActivity.kt            — Active call UI (389 lines)
  ├── CallEventBus.kt            — Service↔VM event bus
  ├── CallService.kt             — Foreground Service with TTS/STT (370 lines)
  ├── CallViewModel.kt           — Call state management (161 lines)
  ├── IncomingCallActivity.kt    — Incoming call UI (414 lines)
  └── SignalingClient.kt         — WebSocket client (154 lines)
home/
  ├── HomeScreen.kt              — Status + recent calls (614 lines)
  └── HomeViewModel.kt           — Home state management (236 lines)
settings/
  └── SettingsScreen.kt          — Server config + connection test (502 lines)
data/
  ├── api/ApiClient.kt           — Retrofit client (87 lines)
  ├── api/ApiService.kt          — Retrofit API interface (43 lines)
  ├── api/TokenManager.kt       — Encrypted prefs (60 lines)
  └── model/Models.kt            — Data classes (85 lines)
di/AppModule.kt                  — Hilt module (19 lines)
ui/
  ├── theme/Color.kt             — Colors (78 lines)
  ├── theme/Theme.kt             — Theme (158 lines)
  ├── theme/Type.kt              — Typography (115 lines)
  └── composables/AmbientBackground.kt — Animated bg (82 lines)
auth/                             — EMPTY
push/                             — EMPTY
ui/components/                    — EMPTY
```

### What Must Be Deleted

| File | Lines | Reason |
|---|---|---|
| `call/CallActivity.kt` | 389 | Voice-call specific. Replaced by SessionActivity. |
| `call/CallEventBus.kt` | 25 | Voice-specific event model. Replaced by direct daemon API. |
| `call/CallService.kt` | 370 | Voice call foreground service. Replaced by PushService. |
| `call/CallViewModel.kt` | 161 | Voice call state. Replaced by SessionViewModel. |
| `call/IncomingCallActivity.kt` | 414 | Voice incoming call. Replaced by session notification flow. |
| `call/SignalingClient.kt` | 154 | WebSocket client for voice signaling. Replaced by DaemonClient. |
| `home/HomeViewModel.kt` | 236 | Voice-centric home state. Rewritten for session list. |
| `home/HomeScreen.kt` | 614 | Voice-centric UI. Rewritten for session inbox. |
| `data/api/ApiService.kt` | 43 | VoiceBridge REST endpoints. Replaced by DaemonClient. |
| `data/api/ApiClient.kt` | 87 | Retrofit config. Replaced by HTTP+WS client to daemon. |
| `data/api/TokenManager.kt` | 60 | Auth tokens. Not needed (daemon is local). |
| `data/model/Models.kt` | 85 | VoiceBridge data models. Replaced by v2 models. |
| `auth/` | 0 | Empty. No auth screen needed (daemon is local). |
| `push/` | 0 | Empty. Will be implemented in v2. |
| `ui/components/` | 0 | Empty. |

### What Must Be Kept (as-is or minor refactor)

| File | Lines | Reason |
|---|---|---|
| `AgentCallApp.kt` | 45 | Application class; refactor notification channels |
| `MainActivity.kt` | 137 | Navigation shell; refactor destinations |
| `di/AppModule.kt` | 19 | DI module; minimal changes |
| `ui/theme/Color.kt` | 78 | Color system; keep |
| `ui/theme/Theme.kt` | 158 | Theme system; keep |
| `ui/theme/Type.kt` | 115 | Typography; keep |
| `ui/composables/AmbientBackground.kt` | 82 | Animated bg; keep as shared component |

### Net Change
```
Deleted:    16 files, ~3,200 lines
Rewritten:  2 files (MainActivity, AgentCallApp)
New:        12 files, ~2,000 lines
Total:      ~2,100 lines (from ~5,500 today)
            62% reduction in line count
            But: optimized for UX, not line count
```

---

## 2. Application Architecture

### 2.1 Screen Map

```
┌─────────────────────────────────────────────────────┐
│                    AgentCall                          │
├─────────────────────────────────────────────────────┤
│                                                       │
│  ┌─────────────────────────────────────────────────┐ │
│  │  SESSION LIST (default)                          │ │
│  │  ┌───────────────────────────────────────────┐  │ │
│  │  │ 🔵 Claude needs a decision                 │  │ │
│  │  │    "Which deployment strategy?" • 2m ago   │  │ │
│  │  ├───────────────────────────────────────────┤  │ │
│  │  │ 🟢 ChatGPT sent a message                  │  │ │
│  │  │    "The report is ready" • 15m ago         │  │ │
│  │  ├───────────────────────────────────────────┤  │ │
│  │  │ ⚪ Gemini notification                      │  │ │
│  │  │    "New code review request" • 1h ago      │  │ │
│  │  └───────────────────────────────────────────┘  │ │
│  └─────────────────────────────────────────────────┘ │
│                                                       │
│  ┌─────────────────────────────────────────────────┐ │
│  │  TAB BAR                                         │ │
│  │  [Sessions] [Agents] [Settings] [Profile]        │ │
│  └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

### 2.2 Screen Inventory

| Screen | Purpose | Priority |
|---|---|---|
| **SessionList** | Inbox: all pending/active/recent sessions grouped by status | P0 |
| **SessionDetail** | Full session view: messages, response input, capability-specific UI | P0 |
| **AgentList** | Connected AI agents + their permission status | P1 |
| **AgentDetail** | Per-agent settings (allow/block, trust level, quiet hours) | P1 |
| **Settings** | Daemon connection, push config, appearance | P1 |
| **Profile** | User name, devices, presence override | P2 |
| **DeviceList** | Registered devices | P2 |
| **History** | Past sessions (searchable, filterable) | P2 |

### 2.3 Navigation

```
Bottom Nav:
  [Sessions] [Agents] [Settings]

Sessions Tab:
  └── SessionList
       └── SessionDetail (push navigation)

Agents Tab:
  └── AgentList
       └── AgentDetail (push navigation)

Settings Tab:
  └── Settings
       ├── Profile (push)
       ├── DeviceList (push)
       └── History (push)

Deep Link (notification tap):
  Notification → SessionDetail(session_id)
```

### 2.4 State Management

```kotlin
// App-wide state, provided by DaemonClient
data class AppState(
  val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
  val presence: Presence = Presence(),
  val recentSessions: List<SessionSummary> = emptyList(),
  val registeredDevices: List<Device> = emptyList(),
  val agents: List<Agent> = emptyList(),
)

sealed class ConnectionStatus {
  object Disconnected : ConnectionStatus()
  object Connecting : ConnectionStatus()
  data class Connected(val daemonVersion: String) : ConnectionStatus()
  data class Error(val message: String) : ConnectionStatus()
}

// Per-screen state
data class SessionDetailState(
  val session: Session? = null,
  val messages: List<Message> = emptyList(),
  val isLoading: Boolean = true,
  val replyText: String = "",
  val isSending: Boolean = false,
)
```

### 2.5 Background Behavior

```
Push received → system notification → user taps → SessionDetail opens

If app is foregrounded:
  WebSocket connection to daemon → real-time session updates
  No push needed (but push may arrive anyway)

If app is backgrounded:
  Push notification wakes app
  DaemonClient connects to daemon via WebSocket
  Session state syncs

If app is killed:
  Next push → app cold starts → SessionDetail opens
```

No foreground service needed. No wake lock needed. No TTS/STT needed.

### 2.6 Notification Flow

```kotlin
// PushService.kt — new file
class PushService : FirebaseMessagingService() {
  override fun onNewToken(token: String) {
    DaemonClient.registerDevice(token, "android")
  }

  override fun onMessageReceived(message: RemoteMessage) {
    val sessionId = message.data["session_id"]
    val capability = message.data["capability"]
    val context = message.data["context"]
    val agentName = message.data["agent_name"]

    showNotification(sessionId, capability, context, agentName)
  }

  private fun showNotification(sessionId, capability, context, agentName) {
    // Capability-specific notification styling:
    //   decision → "Claude needs a decision: Which deployment strategy?"
    //   message → "ChatGPT: The report is ready"
    //   notify → "Gemini: New code review request"
    //   approval → "Cursor needs approval: Deploy to production?"
    //   confirmation → "Codex: Please confirm you received the file"
    //   callback → "OpenCode: Please call me back when you're available"

    // High-priority notification with fullScreenIntent for urgent
    // Group notifications by agent
    // Action buttons on notification when applicable:
    //   decision: [Option A] [Option B] [Open]
    //   approval: [Approve] [Reject] [Open]
    //   confirmation: [Confirm] [Open]
  }
}
```

### 2.7 Session Flow

```
Push notification → user taps → SessionDetailActivity

SessionDetailActivity layout:
  ┌─────────────────────────────┐
  │ ← Back    Claude            │
  │           decision · urgent │
  ├─────────────────────────────┤
  │                             │
  │ ┌──── AI ─────────────────┐ │
  │ │ Which deployment         │ │
  │ │ strategy?                │ │
  │ └──────────────────────────┘ │
  │                             │
  │ ┌──── You ────────────────┐ │
  │ │ Use rolling update.      │ │
  │ │ Canary first.            │ │
  │ └──────────────────────────┘ │
  │                             │
  ├─────────────────────────────┤
  │ [  Type a message...  ] [→] │
  └─────────────────────────────┘

For 'decision' capability:
  ┌─────────────────────────────┐
  │ Claude needs your decision  │
  ├─────────────────────────────┤
  │ Which deployment strategy?  │
  ├─────────────────────────────┤
  │ ○ AWS                       │
  │ ● GCP   ← selected          │
  │ ○ Azure                     │
  ├─────────────────────────────┤
  │ [  Add note...  ]  [Send]   │
  └─────────────────────────────┘

For 'approval' capability:
  ┌─────────────────────────────┐
  │ Cursor requests approval    │
  ├─────────────────────────────┤
  │ Deploy v2.3.1 to production?│
  ├─────────────────────────────┤
  │ [✓ Approve]  [✗ Reject]    │
  │ [View details...]           │
  └─────────────────────────────┘
```

### 2.8 Device Management Screen

```
DEVICES
┌─────────────────────────────────────┐
│ 📱 Pixel 8                          │
│ Last seen: 2 min ago                │
│ Active · Priority 1                  │
│ Channels: text, decision, approval   │
├─────────────────────────────────────┤
│ 💻 MacBook Pro                       │
│ Last seen: 1 hour ago                │
│ Channels: text, decision, approval   │
├─────────────────────────────────────┤
│ ⌚ Galaxy Watch                      │
│ Last seen: 5 min ago                │
│ Channels: notify, confirmation       │
└─────────────────────────────────────┘
[Register New Device]
```

### 2.9 Connected AI Management Screen

```
CONNECTED AGENTS
┌─────────────────────────────────────┐
│ 🤖 Claude                           │
│ Trusted · Can interrupt · Active    │
│ 12 sessions today                   │
├─────────────────────────────────────┤
│ 💬 ChatGPT                          │
│ Notifications only · 5 sessions     │
├─────────────────────────────────────┤
│ 🚫 Gemini                           │
│ Blocked                             │
├─────────────────────────────────────┤
│ ➕ Connect New Agent                 │
└─────────────────────────────────────┘

Per-agent detail:
┌─────────────────────────────────────┐
│ Claude                    🔵 Active │
├─────────────────────────────────────┤
│ Allow communication      [✓]        │
│ Can interrupt DND        [✓]        │
│ Can request urgent       [✓]        │
│ Max sessions/hour        [ 10  ]    │
│ Quiet hours              [Customize]│
│ Notification sound       [Default]  │
├─────────────────────────────────────┤
│ Recent sessions                    │
│ ┌─ 2m ago · decision · Answered   │
│ └─ 1h ago · notify · Dismissed    │
├─────────────────────────────────────┤
│ [Block Agent]  [Remove Agent]       │
└─────────────────────────────────────┘
```

### 2.10 Settings Screen

```
SETTINGS
┌─────────────────────────────────────┐
│ DAEMON CONNECTION                   │
│ Connected to localhost:7377    🟢   │
│ [Disconnect]  [Change Server]       │
├─────────────────────────────────────┤
│ PRESENCE                            │
│ Current status: Available           │
│ [Set to Away]  [Set to DND]         │
│ [Schedule quiet hours...]           │
├─────────────────────────────────────┤
│ NOTIFICATIONS                       │
│ Show on lock screen    [✓]          │
│ Notification actions   [✓]          │
│ Urgent bypasses DND   [✓]          │
├─────────────────────────────────────┤
│ PRIVACY                             │
│ Clear all sessions                  │
│ Export data                         │
├─────────────────────────────────────┤
│ ABOUT                               │
│ AgentCall v2.0.0                    │
│ Daemon v2.0.0 · Connected          │
└─────────────────────────────────────┘
```

### 2.11 New File Structure

```
com.agentcall.app/
├── AgentCallApp.kt              ← refactored: FCM init, notification channels
├── MainActivity.kt              ← refactored: new navigation, no voice
│
├── session/
│   ├── SessionListScreen.kt     ← NEW: inbox view
│   ├── SessionListViewModel.kt  ← NEW: session list state
│   ├── SessionDetailScreen.kt   ← NEW: per-session view
│   ├── SessionDetailViewModel.kt← NEW: per-session state
│   └── DecisionCard.kt          ← NEW: decision capability UI
│   └── ApprovalCard.kt          ← NEW: approval capability UI
│
├── agents/
│   ├── AgentListScreen.kt       ← NEW: connected agents list
│   ├── AgentListViewModel.kt    ← NEW: agent list state
│   ├── AgentDetailScreen.kt     ← NEW: per-agent settings
│   └── AgentDetailViewModel.kt  ← NEW: per-agent state
│
├── push/
│   └── PushService.kt           ← NEW: FCM handler + notification builder
│
├── daemon/
│   ├── DaemonClient.kt          ← NEW: HTTP + WS client to local daemon
│   ├── DaemonConnection.kt      ← NEW: connection state machine
│   └── models/
│       └── Models.kt            ← NEW: v2 data models (Session, Message, etc.)
│
├── settings/
│   ├── SettingsScreen.kt        ← REWRITTEN: daemon config, presence, privacy
│   ├── SettingsViewModel.kt     ← REWRITTEN
│   ├── DeviceListScreen.kt      ← NEW: registered devices
│   ├── HistoryScreen.kt         ← NEW: session history
│   └── ProfileScreen.kt         ← NEW: user profile + presence override
│
├── presence/
│   └── PresenceIndicator.kt     ← NEW: reusable presence badge component
│
├── di/
│   └── AppModule.kt             ← refactored: new dependencies
│
└── ui/
    ├── theme/                   ← keep as-is
    │   ├── Color.kt
    │   ├── Theme.kt
    │   └── Type.kt
    └── composables/
        ├── AmbientBackground.kt ← keep as-is
        ├── CapabilityIcon.kt    ← NEW: icon per capability type
        └── UrgencyBadge.kt      ← NEW: urgency indicator
```

### 2.12 Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| No foreground service | Push-only | Push is sufficient for delivery; no audio needed |
| No TTS/STT | Removed | Voice is a future capability, not v1 |
| No WebRTC | Removed | Same reason |
| DaemonClient | HTTP + WebSocket | REST for query, WS for real-time updates |
| FCM dependency | Required | Only way to get push on Android |
| No wake lock | Removed | No audio, no need to keep CPU awake |
| Notification actions | Inline respond | Decision/approval from notification without opening app |
| Session List as default | Inbox metaphor | Familiar pattern, works for all capabilities |
| Capability-specific UI | Dynamic composables | Each capability renders differently |
| Single activity | Navigation Compose | Standard modern Android pattern |

---

## 3. DaemonClient (Network Layer)

```kotlin
// DaemonClient.kt — HTTP + WebSocket to local daemon

class DaemonClient(private val baseUrl: String) {

  // HTTP for queries
  suspend fun getSession(sessionId: String): Session
  suspend fun listSessions(limit: Int, status: String?): List<SessionSummary>
  suspend fun getAgents(): List<Agent>
  suspend fun getPresence(): Presence
  suspend fun respondToSession(sessionId: String, response: String)
  suspend fun registerDevice(pushToken: String, platform: String)

  // WebSocket for real-time updates
  fun connectToEventStream(): Flow<DaemonEvent>
  // DaemonEvent = SessionCreated | SessionUpdated | MessageReceived | PresenceChanged

  // Health
  suspend fun health(): DaemonHealth
}
```

**Protocol:** Daemon exposes a simple HTTP API at `localhost:7377`:
- `GET /api/sessions` — list sessions
- `GET /api/sessions/:id` — get session detail
- `POST /api/sessions/:id/respond` — send human response
- `GET /api/agents` — list agents
- `PUT /api/agents/:id/policy` — update agent policy
- `POST /api/devices` — register device
- `GET /api/presence` — get presence
- `PUT /api/presence` — set manual presence override
- `GET /health` — health check

**WebSocket:** `ws://localhost:7377/events` — real-time event stream
