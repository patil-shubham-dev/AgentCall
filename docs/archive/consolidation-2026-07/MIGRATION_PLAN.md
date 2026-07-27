# AgentCall — Migration Plan

> From VoiceBridge v1.0.0 to AgentCall v2.0.0.
> Categorized: KEEP | REFACTOR | REWRITE | DELETE

---

## 0. Current State Summary

```
Total files:   79 (backend 30, infra 7, mcp 7, android 22, ios 14)
Total lines:   ~9,500 (est.)
Architecture:  Express REST + VoiceBridge service + Android voice app
```

---

## 1. KEEP (as-is, minor or no changes)

### 1.1 MCP Server Infrastructure

| File | Lines | Reason |
|---|---|---|
| `mcp-server/src/index.ts` | ~100 | stdio + SSE transport works; swap tools |

### 1.2 Backend Utilities

| File | Lines | Reason |
|---|---|---|
| `backend/src/services/EventBus.ts` | ~80 | Internal pub/sub, generic, keep |
| `backend/src/config.ts` | ~60 | Env-based config pattern |
| `backend/src/health.ts` | ~40 | Health check endpoint |
| `backend/src/middleware/errorHandler.ts` | ~50 | JSON error handler |

### 1.3 Android Theme

| File | Lines | Reason |
|---|---|---|
| `mobile/android/.../ui/theme/Color.kt` | 78 | Color system, keep |
| `mobile/android/.../ui/theme/Theme.kt` | 158 | Theme system, keep |
| `mobile/android/.../ui/theme/Type.kt` | 115 | Typography, keep |
| `mobile/android/.../ui/composables/AmbientBackground.kt` | 82 | Shared component, keep |

**Total KEEP:** ~760 lines (8% of codebase)

---

## 2. REFACTOR (keep file, change content)

### 2.1 Android Shell

| File | Lines | Change |
|---|---|---|
| `mobile/android/.../AgentCallApp.kt` | 45 | Add FCM init, refactor notification channels |
| `mobile/android/.../MainActivity.kt` | 137 | Replace voice navigation with v2 navigation |
| `mobile/android/.../di/AppModule.kt` | 19 | Update DI for new services |

### 2.2 Settings Screen (heavy refactor)

| File | Lines | Change |
|---|---|---|
| `mobile/android/.../settings/SettingsScreen.kt` | 502 | Remove server config, add presence/permission UI |

**Total REFACTOR:** ~700 lines (7% of codebase)

---

## 3. REWRITE (new file, same purpose)

### 3.1 Daemon Core

| New File | Replaces | Effort |
|---|---|---|
| `daemon/src/index.ts` | `backend/src/index.ts` | 2 days |
| `daemon/src/mcp-server.ts` | MCP tools (new set) | 2 days |
| `daemon/src/session-engine.ts` | VoiceBridge service model | 1 day |
| `daemon/src/policy-engine.ts` | (new — no equivalent) | 2 days |
| `daemon/src/presence-resolver.ts` | (new — no equivalent) | 1 day |
| `daemon/src/device-registry.ts` | (new — no equivalent) | 1 day |
| `daemon/src/delivery-bus.ts` | (new — no equivalent) | 2 days |
| `daemon/src/push/fcm.ts` | (new — no equivalent) | 1 day |
| `daemon/src/push/apns.ts` | (new — no equivalent) | 1 day |
| `daemon/src/relay/ws.ts` | SignalingClient (Android) | 1 day |
| `daemon/src/storage/sqlite.ts` | PostgreSQL repos | 1 day |
| `daemon/src/config.ts` | `backend/src/config.ts` | 0.5 day |
| `daemon/src/health.ts` | `backend/src/health.ts` | 0.5 day |

**Total REWRITE (daemon):** ~16 days

### 3.2 Android App

| New File | Replaces | Effort |
|---|---|---|
| `session/SessionListScreen.kt` | `home/HomeScreen.kt` (rewrite) | 2 days |
| `session/SessionListViewModel.kt` | `home/HomeViewModel.kt` (rewrite) | 1 day |
| `session/SessionDetailScreen.kt` | `call/CallActivity.kt` (rewrite) | 2 days |
| `session/SessionDetailViewModel.kt` | `call/CallViewModel.kt` (rewrite) | 1 day |
| `session/DecisionCard.kt` | (new — no equivalent) | 1 day |
| `session/ApprovalCard.kt` | (new — no equivalent) | 1 day |
| `agents/AgentListScreen.kt` | (new — no equivalent) | 2 days |
| `agents/AgentListViewModel.kt` | (new — no equivalent) | 1 day |
| `agents/AgentDetailScreen.kt` | (new — no equivalent) | 2 days |
| `agents/AgentDetailViewModel.kt` | (new — no equivalent) | 1 day |
| `push/PushService.kt` | (new — `push/` was empty) | 1 day |
| `daemon/DaemonClient.kt` | `data/api/ApiClient.kt` (rewrite) | 1 day |
| `daemon/DaemonConnection.kt` | `call/SignalingClient.kt` (rewrite) | 1 day |
| `daemon/models/Models.kt` | `data/model/Models.kt` (rewrite) | 0.5 day |
| `settings/SettingsViewModel.kt` | (new) | 1 day |
| `settings/DeviceListScreen.kt` | (new) | 1 day |
| `settings/HistoryScreen.kt` | (new) | 1 day |
| `settings/ProfileScreen.kt` | (new) | 1 day |
| `settings/SettingsScreen.kt` | `settings/SettingsScreen.kt` (rewrite) | 2 days |
| `presence/PresenceIndicator.kt` | (new) | 0.5 day |
| `ui/composables/CapabilityIcon.kt` | (new) | 0.5 day |
| `ui/composables/UrgencyBadge.kt` | (new) | 0.5 day |

**Total REWRITE (Android):** ~24 days

---

## 4. DELETE (with explanation)

### 4.1 Backend

| File | Lines | Why Delete |
|---|---|---|
| `backend/src/voicebridge/` | ~1,800 | VoiceBridge is a specific AI→human workflow. AgentCall is the generic OS. Every line of VoiceBridge assumes a single AI backend, call-oriented lifecycle, and REST API. None of these assumptions apply in v2. |
| `backend/src/routes.ts` | ~200 | REST API replaced by MCP protocol. Routes were designed for a specific AI backend (VoiceBridge). The daemon exposes MCP, not REST. |
| `backend/src/middleware/validateApiKey.ts` | ~50 | Auth is now per-MCP-request via API key in transport headers. No Express middleware needed. |
| `backend/src/services/StunTurnService.ts` | ~100 | No WebRTC in v1 daemon. If WebRTC is added later (v2+), it goes in as a channel, not a service. |
| `backend/src/services/PhoneService.ts` | ~100 | No phone integration. AgentCall is not telephony. |
| `backend/package.json` | — | New daemon has its own package.json with minimal deps. |

### 4.2 Infrastructure

| File | Why Delete |
|---|---|
| `infra/docker-compose.yml` | Daemon is a single process. No Docker needed for deployment. User can containerize if desired, but no compose file provided. |
| `infra/Caddyfile` | No reverse proxy needed. Daemon binds to localhost. |
| `infra/coturn.conf` | No TURN server needed. No WebRTC. |
| `infra/k8s/` | No Kubernetes. Single user, single process. |

### 4.3 Android App (Entire Modules)

| Module | Lines | Why Delete |
|---|---|---|
| `call/` entire directory | ~1,500 | Voice call architecture: CallActivity, CallService, CallEventBus, CallViewModel, IncomingCallActivity, SignalingClient. All assume audio call lifecycle. v2 replaces with session-based communication. |
| `home/HomeViewModel.kt` | 236 | Voice-centric state management (connection quality, signaling status). v2 uses session list model. |
| `data/api/ApiClient.kt` | 87 | Retrofit client for VoiceBridge REST API. v2 uses daemon HTTP/WS client. |
| `data/api/ApiService.kt` | 43 | VoiceBridge endpoints. v2 has new API. |
| `data/api/TokenManager.kt` | 60 | Auth tokens for cloud backend. v2 daemon is local, no token auth needed. |
| `data/model/Models.kt` | 85 | VoiceBridge data models. v2 has new models. |
| `auth/` | 0 | Empty directory. No auth screen needed (daemon is local). |
| `push/` | 0 | Empty directory. Will be recreated with PushService. |
| `ui/components/` | 0 | Empty directory. |

### 4.4 iOS App

| Directory | Lines | Why Delete |
|---|---|---|
| `mobile/ios-archived/` | ~1,200 | Archived and unmaintained. Keep in git history (don't delete the directory), just mark as permanently archived and exclude from migration. If iOS is needed, rebuild from Android patterns. |

### 4.5 Docs

| File | Why Delete |
|---|---|
| `REAL_DEPLOYABILITY_AUDIT.md` | VoiceBridge audit. Replace with v2 architecture docs. |
| `VISION.md` | Superseded by these documents. |

**Total DELETE:** ~4,500 lines (47% of codebase)

---

## 5. Phase Plan

### Phase 0: Preparation (1 week)

```
Actions:
  - Create /daemon/ directory at repo root
  - Set up package.json, tsconfig, vitest
  - Copy MCP server infra from mcp-server/src/index.ts
  - Install deps: better-sqlite3, firebase-admin, ws
  - Create AGENTS.md with new architecture notes

Risk: Low — just scaffolding
Dependency: None
```

### Phase 1: Daemon Core (2 weeks)

```
Actions:
  - Implement SQLite storage (schema, CRUD)
  - Implement Session Engine (create, read, update, list)
  - Implement MCP server with 4 tools: request_communication, send_message,
    get_session, cancel_session
  - Implement API key auth (register_agent tool)
  - Wire up: MCP → Session Engine → SQLite

Deliverable: `echo '{"jsonrpc":"2.0","method":"tools/call"...}' | node daemon/src/index.js`
             returns session_id. Sessions persisted in SQLite.

Risk: Low-Medium — MCP infra exists, just new tools
Dependency: Phase 0
```

### Phase 2: Delivery & Policy (2 weeks)

```
Actions:
  - Implement Agent Registry + Policy Engine
  - Implement Presence Resolver
  - Implement Device Registry
  - Implement Push Gateway (FCM)
  - Implement WebSocket Relay
  - Implement Delivery Router
  - Wire up: Session Engine → Policy Engine → Presence → Devices → Deliver

Deliverable: request_communication → push notification reaches phone

Risk: Medium — FCM setup requires Firebase project, APNs requires Apple dev account
Dependency: Phase 1
```

### Phase 3: Android App (3 weeks)

```
Actions:
  - Delete: call/ directory, home/HomeViewModel, data/api/*, data/model/Models.kt
  - Implement: DaemonClient, PushService
  - Implement: SessionListScreen + SessionDetailScreen
  - Implement: AgentListScreen + AgentDetailScreen
  - Implement: SettingsScreen (rewrite)
  - Implement: DeviceListScreen, HistoryScreen, ProfileScreen
  - Wire up: Push → notification tap → SessionDetail → respond → daemon
  - End-to-end test: Claude requests communication → phone receives it → user responds

Deliverable: Full communication flow end-to-end with real push notifications

Risk: Medium-High — Android refactor is the bulk of the work
Dependency: Phase 2 (daemon must be ready to receive device registrations)
```

### Phase 4: Hardening (1 week)

```
Actions:
  - Add rate limiting
  - Add quiet hours with calendar integration
  - Add crash recovery test
  - Add health endpoint + Prometheus metrics
  - Add systemd user service file
  - Add Dockerfile (optional)
  - Write integration tests

Deliverable: Production-ready daemon

Risk: Low
Dependency: Phase 3
```

### Phase 5: Future (post-v2)

```
Actions:
  - iOS app (from Android patterns)
  - Desktop app (Electron/Tauri shell around web UI)
  - Browser extension (lightweight notification client)
  - Voice channel (WebRTC integration)
  - Video channel
  - File sharing
  - Group communication (multiple humans)
  - End-to-end encryption
  - Multi-user support
```

---

## 6. Effort Summary

| Phase | Duration | Risk | Parallelizable |
|---|---|---|---|
| 0 — Prep | 1 week | Low | Yes |
| 1 — Daemon Core | 2 weeks | Low-Med | No |
| 2 — Delivery & Policy | 2 weeks | Medium | Partially (push+WS in parallel) |
| 3 — Android App | 3 weeks | Med-High | No (needs daemon API stable) |
| 4 — Hardening | 1 week | Low | Yes |
| **Total** | **~9 weeks** | | |

### Risk Mitigation

| Risk | Mitigation |
|---|---|
| FCM setup delays | Start Firebase project in Phase 0. Use WebSocket relay as fallback if FCM is not ready by Phase 2. |
| Android rewrite scope creep | The spec (`ANDROID_V2_SPEC.md`) is fixed. No scope changes without re-plan. |
| MCP tool changes | MCP 1.0 is stable. If protocol changes, daemon transport layer isolates from tool logic. |
| SQLite performance at scale | v1 is single-user. SQLite handles this trivially. If multi-user is needed, schema is simple enough to migrate to Postgres. |
| Developer unavailability for 9 weeks | Phases 0–2 (daemon) can be done by one person. Phase 3 (Android) needs mobile expertise. They can run in parallel if the API contract is agreed in Phase 1. |

---

## 7. What We Stop Doing

| Currently | We Stop Because |
|---|---|
| Managing AI provider integrations | AgentCall is AI-agnostic; AI providers manage themselves |
| Operating Postgres | SQLite replaces for single-user |
| Running Docker Compose | Single daemon process |
| Maintaining coturn/STUN/TURN | No WebRTC in v1 |
| Writing Express routes | MCP replaces REST |
| Supporting PSTN/telephony | Out of scope |
| Maintaining iOS (archived) | Permanently archived |
| Building audio pipelines | TTS/STT removed from Android |
| Managing cloud deployments | Local-first; user deploys on own machine |
| VoiceBridge domain model | Entirely replaced by Communication OS model |
