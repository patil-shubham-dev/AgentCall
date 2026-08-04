# AgentCall Architecture Review

> Analysis date: 2026-07-26 | Codebase: VoiceBridge/AgentCall v1.0.0

---

## 1. Executive Summary

**Overall: 4/10** — backend core engine is solid and self-hostable; mobile apps have real UI and service code but critical gaps in auth, push, and cross-platform parity. No cloud dependency is required for the backend to function, but both mobile apps assume a production cloud endpoint by default.

---

## 2. Backend Analysis

### What's Real
- Full Express/Fastify server with 12 connected API routes (`backend/src/routes.ts`)
- `VoiceBridgeService` — createCall, addMessage, processTextMessage, pause, resume, complete, cancel (`backend/src/voicebridge/service.ts:77-232`)
- `EventBus` with typed event emission (`backend/src/voicebridge/events.ts`)
- `SessionRepository` + `CallbackRepository` + `LifecycleCoordinator` wiring
- `MetricsCollector` — prometheus-compatible
- `validateConfig()` — env var validation at startup
- `RecoveryManager` — restart recovery
- `PersistenceVerifier` — dual-write checks
- Dockerfile (root + backend) — production-ready with healthcheck + non-root user
- MCP server — stdio + SSE transports, 5 tools (create_call, send_message, get_call, complete_call, schedule_callback)

### What's Missing
| Component | Status |
|-----------|--------|
| Migration tooling | ❌ schema.sql exists, no Knex migration |
| PostgreSQL service in docker-compose | ❌ missing |
| coturn STUN/TURN in docker-compose | ❌ missing |
| Explicit network in docker-compose | ❌ missing |
| AI/LLM integration | ❌ no provider abstraction |
| Telephony (PSTN) | ❌ stub references |
| Auth middleware on routes | ❌ no JWT/session check |

### Local-First Verdict
Backend requires **zero** cloud services. All deps are self-contained (node_modules, optional PostgreSQL). Ready for local-first pivot.

---

## 3. Android App (Compose/Kotlin) — 22 source files

### Hierarchy

```
AgentCallApp.kt              — Application + notification channels
MainActivity.kt              — Bottom nav (Home, Settings)
├── home/
│   ├── HomeScreen.kt        — Status, active call banner, recent calls, incoming handler
│   └── HomeViewModel.kt     — Signaling events, connection quality, reconnect
├── call/
│   ├── CallActivity.kt      — Active call UI (waveform, messages, Record/Speaker/Repeat/End)
│   ├── CallViewModel.kt     — Call state, waveform generator, API fetch
│   ├── CallService.kt       — Foreground Service, TTS, STT (SpeechRecognizer)
│   ├── IncomingCallActivity.kt — Answer/Decline/Later with priority & scheduler picker
│   ├── SignalingClient.kt   — OkHttp WebSocket, reconnect, typed events
│   └── CallEventBus.kt      — Service ↔ ViewModel shared flow
├── settings/
│   └── SettingsScreen.kt    — Server host config + connection test + network info
├── data/
│   ├── api/
│   │   ├── ApiClient.kt     — Retrofit, configurable host, port 4000
│   │   ├── ApiService.kt    — 9 Retrofit endpoints
│   │   └── TokenManager.kt  — EncryptedSharedPreferences
│   └── model/
│       └── Models.kt        — 10 serializable data classes
├── ui/
│   ├── theme/
│   │   ├── Color.kt         — Brand, gradients, glass, waveform colors
│   │   ├── Theme.kt         — Dark + Light with ExtendedColors composition
│   │   └── Type.kt          — Material3 Typography
│   └── composables/
│       └── AmbientBackground.kt — Animated gradient orbs
├── di/
│   └── AppModule.kt         — Hilt singleton: SignalingClient
├── auth/                    — Empty directory ❌
└── push/                    — Empty directory ❌
```

### Screen Inventory

| Screen | File | Lines | Status |
|--------|------|-------|--------|
| Home | `home/HomeScreen.kt` | 614 | ✅ Full status display, connection quality, recent calls, incoming call handler |
| Active Call | `call/CallActivity.kt` | 389 | ✅ Waveform, chat bubbles, Record/Speaker/Repeat/End controls |
| Incoming Call | `call/IncomingCallActivity.kt` | 414 | ✅ Animated rings, priority colors, Answer/Decline/Later with scheduler |
| Settings | `settings/SettingsScreen.kt` | 502 | ✅ Server host config, connection test (HTTP ping), network info |
| Onboarding | — | 0 | ❌ Not implemented |
| Auth/Login | `auth/` empty | 0 | ❌ Not implemented |
| Notifications | — | 0 | ❌ Not implemented (channels exist in AgentCallApp.kt) |
| Transcript History | — | 0 | ❌ Not implemented (messages inline in CallActivity only) |

### Feature Audit (30 questions)

| # | Question | Answer | Evidence |
|---|----------|--------|----------|
| 1 | Main interface renders | ✅ | `MainActivity.kt:60-137` — `MainApp` composable with Scaffold + NavHost |
| 2 | Home screen | ✅ | `HomeScreen.kt:41-283` — status, recent calls, incoming handler |
| 3 | Settings screen | ✅ | `SettingsScreen.kt:106-411` — server config, ping test, network info |
| 4 | Active call screen | ✅ | `CallActivity.kt:69-253` — waveform, messages, controls |
| 5 | Incoming call screen | ✅ | `IncomingCallActivity.kt:115-360` — answer/decline/later |
| 6 | Auth/login screen | ❌ | `auth/` directory empty |
| 7 | Onboarding flow | ❌ | Not present |
| 8 | Navigation mechanism | ✅ | Bottom nav + explicit intents (`MainActivity.kt:55-58, 84-92`) |
| 9 | Auth provider | ❌ | TokenManager defined but no login UI or OAuth flow |
| 10 | Encrypted token storage | ✅ | `TokenManager.kt:12-59` — EncryptedSharedPreferences |
| 11 | Token refresh | ❌ | No refresh logic |
| 12 | API service layer | ✅ | `ApiService.kt:6-43` — 9 Retrofit endpoints |
| 13 | Configurable API base URL | ✅ | `ApiClient.kt:17-87` — serverHost with setter, resetToDefault |
| 14 | WebSocket client | ✅ | `SignalingClient.kt:28-154` — OkHttp WS, reconnect, connection state |
| 15 | WebRTC | ❌ | Not used (platform STT/TTS instead) |
| 16 | Audio capture (STT) | ✅ | `CallService.kt:187-241` — SpeechRecognizer |
| 17 | Audio playback (TTS) | ✅ | `CallService.kt:51-79` — TextToSpeech |
| 18 | Microphone permission | ✅ | `CallActivity.kt:50-53` — RECORD_AUDIO check |
| 19 | Notification permission | ✅ | `AgentCallApp.kt:18-44` — 2 channels (ongoing + incoming) |
| 20 | Background service | ✅ | `CallService.kt:25-369` — Foreground Service |
| 21 | Wake lock during calls | ✅ | `CallService.kt:281-289` — PARTIAL_WAKE_LOCK |
| 22 | Auto-reconnect | ✅ | `SignalingClient.kt:72-78` — 3s delay reconnect |
| 23 | Process death survival | ❌ | In-memory state loss, no persistence |
| 24 | Background push (FCM) | ❌ | `push/` empty; no FCM dependency in build.gradle |
| 25 | VoIP-style incoming calls | ⚠️ | Uses `fullScreenIntent` notification, no FCM |
| 26 | Boot receiver | ❌ | No `BroadcastReceiver` for `BOOT_COMPLETED` |
| 27 | CallKit/VoIP equivalent | ⚠️ | Notification-based incoming, no OS-level call integration |
| 28 | Android-only features | ✅ | Foreground service, notification channels, wake lock |
| 29 | iOS parity — auth | ⚠️ | iOS has AuthView + AuthViewModel; Android auth/ is empty |
| 30 | iOS parity — push | ⚠️ | iOS has PushHandler + CallKit; Android push/ is empty |

---

## 4. iOS App (archived) — 14 Swift files

| File | Lines | Purpose |
|------|-------|---------|
| `AgentCallApp.swift` | 13 | @main entry, AuthViewModel as environmentObject |
| `ContentView.swift` | 49 | Auth gate (isLoggedIn ? MainTabView : AuthView) |
| `AppDelegate.swift` | 20 | VoIP PushKit registration |
| `Auth/AuthView.swift` | — | OAuth with Google/GitHub/Apple |
| `ViewModels/AuthViewModel.swift` | — | OAuth flow, Keychain tokens |
| `Services/ApiService.swift` | — | Alamofire, points to `api.agentcall.example.com` |
| `Services/TokenManager.swift` | — | Keychain |
| `Services/PushHandler.swift` | — | PushKit + CallKit |
| `WebRTC/WebRTCClient.swift` | — | Full WebRTC with TURN credential fetch |
| `Home/HomeView.swift` | — | SocketSwift WebSocket |
| `Call/CallView.swift` | — | Active call |
| `Call/CallKitProvider.swift` | — | CallKit CXProvider |
| `Call/IncomingCallView.swift` | — | Incoming call |
| `Settings/SettingsView.swift` | 305 | Profile, notifications, DND, privacy, agents, about |

### Key iOS/Android Divergences

| Feature | iOS (archived) | Android |
|---------|---------------|---------|
| WebRTC | ✅ GoogleWebRTC | ❌ Platform STT/TTS |
| OAuth | ✅ Google/GitHub/Apple | ❌ Empty auth/ |
| Push | ✅ PushKit + CallKit | ❌ Empty push/ |
| Voice transport | WebRTC media channels | SpeechRecognizer + TextToSpeech |
| Auth model | Multi-user OAuth | Single-user SERVICE_TOKEN |
| Background calls | CallKit native UI | Full-screen notification |
| Token storage | Keychain | EncryptedSharedPreferences |

---

## 5. Gaps & Recommendations

### Critical (blocks deployment)
1. **No migration tooling** — schema.sql exists but no Knex migration runner
2. **No auth middleware** — API routes have no JWT/session validation
3. **Android auth/empty** — no login screen or OAuth
4. **Android push/empty** — no FCM integration for background notifications
5. **Missing infra services** — docker-compose lacks PostgreSQL and coturn

### High (quality-of-life)
6. **iOS is archived** — presumed unmaintained; Android is the active client
7. **Android: no process death survival** — in-memory state lost on kill
8. **Android: no boot receiver** — can't receive calls after reboot
9. **Android: no transcript history screen** — messages only in active call
10. **Settings: iOS has rich settings (profile, notifications, DND)** — Android settings focused on server config

### Medium
11. **Wake lock has hard 60s timeout** (`CallService.kt:284`) — may release during long calls
12. **No token refresh** — TokenManager stores tokens but no refresh flow
13. **No error boundary/retry in TTS** — `speakText` silently drops if TTS not initialized
14. **Hardcoded singleton user** — `SignalingClient.connect()` defaults to `"solo-user"`
15. **iOS API host is example.com** — production URL placeholder

### Recommended Action
1. Add Knex migration scripts (from `schema.sql`)
2. Implement JWT auth middleware in backend
3. Build Android auth screen (port iOS AuthView)
4. Integrate FCM in Android (currently empty `push/` directory)
5. Add PostgreSQL + coturn to Docker Compose
6. Fix wake lock timeout to use `acquire()` without timeout, release on call end
7. Decide iOS status — either archive officially or bring up to parity
