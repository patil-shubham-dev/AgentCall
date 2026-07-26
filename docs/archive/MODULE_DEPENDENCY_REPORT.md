# AgentCall — Module Dependency Report

> **Date:** 2026-07-26
> **Scope:** Backend (TypeScript), MCP Server (TypeScript), Android (Kotlin)

---

## 1. Backend Dependency Graph

### Current Graph

```
┌──────────────────────────────────────────────────────────────────────┐
│                         backend/src/index.ts                         │
│  Entry point: Fastify bootstrap, error handler, shutdown             │
└──────┬────────────────────┬───────────────────────┬──────────────────┘
       │                    │                       │
       ▼                    ▼                       ▼
┌──────────────┐   ┌──────────────┐   ┌──────────────────────┐
│ common/     │   │ routes.ts   │   │ signaling/server.ts  │
│ config.ts   │   │ 10 endpoints│   │ WebSocket /phone     │
│ logger.ts   │   │ NO Zod      │   │ Rate limiting        │
└──────────────┘   └──────┬───────┘   └──────────┬───────────┘
       │                   │                      │
       │                   ▼                      ▼
       │           ┌──────────────────────────────────────┐
       │           │       voicebridge/service.ts         │
       │           │  Call lifecycle + callback + etc.    │
       │           │  Module-level mutable state (Maps)   │
       │           └──────────────┬───────────────────────┘
       │                          │
       │                          ▼
       │           ┌──────────────────────────────────────┐
       │           │       voicebridge/types.ts           │
       │           │  Types + enrichment + emotion +...   │
       │           └──────────────┬───────────────────────┘
       │                          │
       │                          ▼
       │           ┌──────────────────────────────────────┐
       │           │         common/types.ts              │
       │           │  CallPriority, CallReason (types)    │
       └───────────┘                                      ┘
```

### Dependency List

| Module | Depends On | Dependent By | Direction |
|--------|-----------|--------------|-----------|
| `common/config.ts` | `dotenv/config` | `index.ts`, `routes.ts`, `signaling/server.ts`, `common/logger.ts` | Outbound only |
| `common/logger.ts` | `pino`, `pino-pretty`, `common/config.ts` | `index.ts`, `routes.ts`, `signaling/server.ts`, `voicebridge/service.ts` | Outbound only |
| `common/types.ts` | _(none)_ | `voicebridge/types.ts` | Outbound only |
| `voicebridge/types.ts` | `common/types.ts` | `voicebridge/service.ts` | Outbound only |
| `voicebridge/service.ts` | `node:crypto`, `ws`, `common/logger.ts`, `voicebridge/types.ts` | `routes.ts`, `signaling/server.ts` | **Cross-module** |
| `routes.ts` | `fastify`, `common/config.ts`, `common/logger.ts`, `voicebridge/service.ts`, `voicebridge/types.ts` | `index.ts` | Outbound only |
| `signaling/server.ts` | `ws`, `node:http`, `common/logger.ts`, `common/config.ts`, `voicebridge/service.ts` | `index.ts` | **Cross-module violation** |
| `index.ts` | All of the above | _(none — entry point)_ | Outbound only |

### Architecture Violations in Dependencies

| # | Violation | From | To | Impact |
|---|-----------|------|----|--------|
| **V1** | Layer breach | `signaling/server.ts` | `voicebridge/service.ts` | Signaling (transport layer) imports call business logic. Creates potential circular dependency path. Signaling should emit events, not call service functions. |
| **V2** | No service facade | `routes.ts` | `voicebridge/service.ts` (direct) | Routes call business logic functions directly with no intermediary, DTO mapping, or error translation. |
| **V3** | No Event Bus | All | All | Every service-to-service call is a direct function call. No events, no async handlers, no decoupling. |
| **V4** | No interface abstraction | `index.ts`, `routes.ts` | `voicebridge/service.ts`, `signaling/server.ts` | All imports are concrete -> concrete. No interfaces that could be swapped for testing or alternative implementations. |

### Improved Graph (Target)

```
                    ┌─────────────┐
                    │  Event Bus   │
                    │  (pub/sub)   │
                    └──────┬──────┘
          ┌────────────────┼────────────────┐
          │                │                │
          ▼                ▼                ▼
   ┌──────────┐    ┌──────────────┐   ┌─────────────┐
   │ Signaling│    │ Route Handler│   │  Services   │
   │ (Gateway)│    │ (thin)       │   │ - CallMgr   │
   │          │    │              │   │ - Presence  │
   │ Events→  │    │ Auth→Validation│ │ - Callback  │
   │ Bus only │    │ →Service     │   │ - etc.      │
   └──────────┘    └──────────────┘   └─────────────┘
                           │                 │
                           ▼                 ▼
                    ┌──────────────┐   ┌─────────────┐
                    │ Repository   │   │  Interfaces │
                    │ (PostgreSQL) │   │ (injectable)│
                    └──────────────┘   └─────────────┘
```

---

## 2. MCP Server Dependency Graph

### Current Graph

```
┌───────────────────────────────────────────────┐
│              mcp-server/src/index.ts           │
│  Entry point: MCP Server bootstrap, transport  │
└──────┬─────────────┬──────────────┬────────────┘
       │             │              │
       ▼             ▼              ▼
┌──────────┐ ┌──────────────┐ ┌──────────────────┐
│ config.ts│ │ logger.ts   │ │ sse.ts           │
│ env vars │ │ pino        │ │ HTTP+SSE server  │
└──────────┘ └──────────────┘ │ CORS + API key   │
       │                      └──────────────────┘
       │                                │
       │         ┌──────────────────┐   │
       └────────►│ tools.ts         │◄──┘
                  │ 5 tool handlers  │
                  └────────┬─────────┘
                           │
                           ▼
                  ┌──────────────────┐
                  │ client.ts        │
                  │ HTTP API client  │
                  └──────────────────┘
                           │
                           ▼
                  ┌──────────────────┐
                  │ Backend REST API │
                  │ (external)       │
                  └──────────────────┘
```

### Dependency List

| Module | Depends On | Dependent By | Direction |
|--------|-----------|--------------|-----------|
| `config.ts` | `dotenv/config` | `index.ts`, `sse.ts`, `client.ts`, `logger.ts` | Outbound only |
| `logger.ts` | `pino`, `config.ts` | `index.ts`, `sse.ts`, `client.ts`, `tools.ts` | Outbound only |
| `client.ts` | `config.ts`, `logger.ts` | `tools.ts` | Outbound only |
| `tools.ts` | `client.ts`, `logger.ts` | `index.ts` | Outbound only |
| `sse.ts` | `node:http`, `node:crypto`, `@modelcontextprotocol/sdk`, `logger.ts`, `config.ts` | `index.ts` | Outbound only |
| `index.ts` | All of the above | _(none — entry point)_ | Outbound only |

### Architecture Status

**No circular dependencies detected.** The MCP server has a clean DAG. This is significantly better than the backend.

### Issues

| # | Issue | Detail |
|---|-------|--------|
| 1 | No interface for client | `tools.ts` imports `client.ts` concretely. If the backend API changes, all tool handlers must be updated. |
| 2 | Transport ownership split | `index.ts` creates the `Server` object; `sse.ts` creates and connects the transport. If module init order changes, handlers may not be registered. |
| 3 | No graceful shutdown | Neither `index.ts` nor `sse.ts` handle SIGTERM/SIGINT. Connections may be dropped abruptly. |

---

## 3. Android Dependency Graph

### Current Graph

```
                        ┌─────────────────────┐
                        │  AgentCallApp.kt     │
                        │  (@HiltAndroidApp)    │
                        └──────────┬──────────┘
                                   │
                        ┌──────────┴──────────┐
                        │  MainActivity.kt     │
                        │  (@AndroidEntryPoint)│
                        │  NavHost             │
                        └────┬──────────┬─────┘
                             │          │
                   ┌─────────┘          └─────────┐
                   ▼                               ▼
        ┌────────────────────┐        ┌─────────────────────┐
        │  HomeScreen.kt     │        │  SettingsScreen.kt   │
        │  HomeViewModel.kt  │        │  SettingsViewModel   │
        └─────────┬──────────┘        └──────────┬──────────┘
                  │                              │
                  ▼                              ▼
        ┌───────────────────────────────────────────────┐
        │              data/api/                         │
        │  ApiClient.kt, ApiService.kt, TokenManager.kt   │
        └──────┬─────────────────────────────────────────┘
               │
               ▼
        ┌───────────────────────────────────────────────┐
        │           call/SignalingClient.kt              │
        │  OkHttp WebSocket → VoiceBridge backend        │
        └───────────────────────────────────────────────┘
                   │
                   ▼
        ┌───────────────────────────────────────────────┐
        │           call/CallService.kt                  │
        │  GOD OBJECT: TTS, STT, barge-in, commands,     │
        │  WebSocket events, HTTP API, notifications,    │
        │  wake lock, emotion, fillers, breathing        │
        └───────────────────────────────────────────────┘
                   │
                   ▼
        ┌───────────────────────────────────────────────┐
        │           call/CallEventBus.kt                 │
        │           SharedFlow (call service→VM)         │
        └───────────────────────────────────────────────┘
                   │
                   ▼
        ┌───────────────────────────────────────────────┐
        │  CallActivity.kt  │  IncomingCallActivity.kt   │
        │  CallViewModel.kt │                            │
        └───────────────────────────────────────────────┘
```

### Architecture Violations

| # | Violation | Detail |
|---|-----------|--------|
| **V5** | God object | `CallService.kt` depends on: Android TTS, SpeechRecognizer, AudioRecord, OkHttp WebSocket, HTTP (via ApiService), NotificationManager, PowerManager. It is also depended on by `CallViewModel` (via `CallEventBus`), `IncomingCallActivity` (via intents), and `CallActivity` (via intents). Any change to any of its 15+ responsibilities risks breaking the entire call flow. |
| **V6** | No interface separation | `SignalingClient` is a concrete singleton (`@Singleton`). `ApiClient` is a concrete singleton. No interfaces that could be swapped for testing. |
| **V7** | Hardcoded dependencies | `CallService` directly instantiates Android system services (TTS, SpeechRecognizer, AudioRecord, PowerManager) rather than receiving them via Hilt injection. This makes unit testing impossible without instrumentation. |
| **V8** | Event Bus only used one-way | `CallEventBus` only flows `CallService → CallViewModel`. There is no reverse flow (`ViewModel → Service`) or cross-module events. |

---

## 4. Cross-Module Dependencies

### Backend ↔ MCP Server

```
MCP Server (client.ts) ──HTTP──► Backend (routes.ts)
  │                                       │
  │  Calls backend REST API               │  No reverse dependency
  │  Uses SERVICE_TOKEN auth              │  (backend doesn't know MCP exists)
  │                                       │
  ▼                                       ▼
```

**Clean separation.** Backend and MCP communicate only via HTTP. No shared code, no shared state, no shared interfaces. This is correct.

### Backend ↔ Android

```
Android (SignalingClient.kt) ──WebSocket──► Backend (signaling/server.ts)
Android (ApiService.kt)       ──HTTP──────► Backend (routes.ts)
```

**Clean separation.** Android communicates via WebSocket + HTTP only. No shared code.

---

## 5. Hidden Coupling

### 5.1 Shared Event Format (No Contract)
- Backend WebSocket events: `{type, payload}` where `type` is a string like `call_incoming`
- Android `VoiceBridgeEvent`: sealed class mapping to the same strings
- No shared schema or type definition — the contract is implicit
- If backend changes an event name, Android crashes silently

### 5.2 Shared Auth Assumptions
- Backend assumes `SERVICE_TOKEN` is validated (it's not — `getAuthUser()` returns `solo-user`)
- Android assumes WebSocket doesn't need auth (it doesn't — no token is sent)
- These assumptions work in prototype but will break when auth is implemented

### 5.3 Shared State Assumptions
- Backend stores call state in `sessions` Map
- Android stores call state in `CallService` memory
- No heartbeat or reconciliation — if backend restarts, Android has stale state
- If apps restart, they lose all state

---

## 6. Layer Violations

| Layer | Expected | Actual |
|-------|----------|--------|
| Transport | Gateway only | `signaling/server.ts` imports `voicebridge/service.ts` |
| Business Logic | Services only, event-driven | `routes.ts` calls `voicebridge.*` directly |
| Persistence | Repository layer | State is in module-level Maps in `service.ts` |
| Mobile App | Thin client | `CallService.kt` has 15+ responsibilities including AI reasoning |

---

## 7. Circular Dependency Risk Analysis

| Path | Circular? | Risk |
|------|-----------|------|
| `index.ts → routes.ts → service.ts` OR `index.ts → signaling/server.ts → service.ts` | No (both paths to service.ts are acyclic) | Low — but if `service.ts` ever imports from `signaling/` or `routes.ts`, it becomes circular |
| `tools.ts → client.ts → (HTTP) → backend → routes.ts → service.ts` | No (HTTP is external) | None — HTTP is a clean boundary |
| `CallService → CallEventBus → CallViewModel` | No (one-way) | Low — one direction only |
| `CallService → SignalingClient → WebSocket → backend → routes → service` | No (WebSocket is external) | None — external transport |

**Conclusion:** No circular imports exist. Two fragile paths could become circular if backend refactoring adds reverse dependencies.

---

## 8. Dependency Recommendations

| # | Recommendation | Current State | Target State | Effort |
|---|---------------|---------------|--------------|--------|
| 1 | **Extract Event Bus** | No Event Bus — direct calls | Event Bus in `common/event-bus.ts` | 1 week |
| 2 | **Decouple signaling from service** | `server.ts` imports `service.ts` | `server.ts` publishes events consumed by `service.ts` | 3 days |
| 3 | **Add service facade** | `routes.ts` imports `service.ts` directly | Routes depend on `ServiceFacade` interface | 2 days |
| 4 | **Extract repository interfaces** | No repository layer | `ICallRepository`, `IUserRepository`, etc. | 1 week |
| 5 | **Decompose Android CallService** | God object (15+ responsibilities) | Inject `TtsManager`, `SttManager`, `BargeInDetector`, etc. | 2 weeks |
| 6 | **Define shared event schema** | Implicit contract | Shared types or event registry | 3 days |
| 7 | **Add graceful shutdown** | Backend: partial. MCP: none | Uniform shutdown across all services | 2 days |
