# AgentCall — Code Ownership

> **Date:** 2026-07-26
> **Goal:** Define ownership boundaries, allowed/forbidden dependencies, and public interfaces for every module.
> **After refactoring per REFACTOR_PLAN.md**

---

## Ownership Definitions

Each ownership definition includes:
- **Responsibilities** — what the module owns
- **Allowed dependencies** — what it can import
- **Forbidden dependencies** — what it must never import
- **Public interface** — the contract it exposes to other modules
- **Internal interfaces** — implementation details hidden from other modules

---

## 1. Authentication Service

**Folder:** `backend/src/auth/`
**Status:** To be implemented (not yet existing)

**Responsibilities:**
- JWT token issuance, validation, refresh, revocation
- Provider API key management
- OAuth integration (future)

**Allowed Dependencies:**
- `common/logger`
- `common/config`
- `common/event-bus`
- `database/repositories` (IUserRepository, IApiKeyRepository)
- `node:crypto`
- `jsonwebtoken` (external)

**Forbidden Dependencies:**
- Any service module (calls, providers, presence, etc.)
- Any transport module (signaling, gateway)

**Public Interface:**
```
authenticate(token: string): AuthResult
issueToken(userId: string, deviceId?: string): TokenPair
refreshToken(refreshToken: string): TokenPair
revokeToken(jti: string): void
validateApiKey(key: string): ProviderAuthResult
```

**Internal Interfaces:**
- `TokenBlacklist` (Redis-backed or in-memory)

---

## 2. Provider Registry

**Folder:** `backend/src/providers/`
**Status:** To be implemented (not yet existing)

**Responsibilities:**
- Provider registration, listing, revocation
- Provider-scoped data isolation
- Provider health checking

**Allowed Dependencies:**
- `common/event-bus`
- `database/repositories` (IProviderRepository)
- Auth interfaces (auth types only)

**Forbidden Dependencies:**
- Call Manager, Presence Engine, Callback Engine
- Any transport module

**Public Interface:**
```
registerProvider(params: CreateProviderParams): Provider
getProvider(id: string): Provider
listProviders(): Provider[]
revokeProvider(id: string): void
getProviderForApiKey(keyHash: string): Provider
```

---

## 3. Session Manager

**Folder:** `backend/src/sessions/`
**Status:** To be implemented (not yet existing)

**Responsibilities:**
- Long-lived provider-user session lifecycle
- Session context persistence
- Auto-expiry with TTL

**Allowed Dependencies:**
- `common/event-bus`
- `common/logger`
- `database/repositories` (ISessionRepository)

**Forbidden Dependencies:**
- Call Manager, Presence, Callback Engine, Device Router

**Public Interface:**
```
createSession(params: CreateSessionParams): Session
extendSession(sessionId: string, ttl: number): Session
endSession(sessionId: string): void
getSession(sessionId: string): Session | null
```

---

## 4. Call Manager

**Folder:** `backend/src/calls/`
**Status:** To be extracted from `voicebridge/service.ts`

**Responsibilities:**
- Call lifecycle: created → pending → ringing → answered → active → completed/cancelled/missed
- Call state management
- Message flow: AI message → human response → AI response

**Allowed Dependencies:**
- `common/event-bus`
- `common/logger`
- `database/repositories` (ICallRepository)
- Session Manager (types only, for context)

**Forbidden Dependencies:**
- Transport modules (signaling, gateway)
- Notification Engine
- Presence Engine

**Public Interface:**
```
createCall(params: CreateCallParams): Call
getCall(callId: string): Call | null
completeCall(callId: string, result?: CallResult): Call
cancelCall(callId: string, reason: string): Call
addMessage(callId: string, message: Message): Message
getTranscript(callId: string): Transcript
```

**Events Published:**
- `call.created`
- `call.state_changed` (pending → ringing → answered → active → completed/cancelled/missed)
- `call.message_added`

---

## 5. Presence Engine

**Folder:** `backend/src/presence/`
**Status:** To be implemented (not yet existing)

**Responsibilities:**
- Online/offline/busy/dnd/in-call presence tracking
- Presence queries (single user, bulk)
- Timeout-based auto-offline detection

**Allowed Dependencies:**
- `common/event-bus`
- `common/redis`
- `common/logger`

**Forbidden Dependencies:**
- Call Manager, Session Manager, Device Router, any transport

**Public Interface:**
```
setPresence(userId: string, state: PresenceState): void
getPresence(userId: string): PresenceState
observePresence(userId: string, callback: PresenceCallback): () => void
```

**Events Published:**
- `presence.changed`

---

## 6. Notification Engine

**Folder:** `backend/src/notifications/`
**Status:** To be implemented (not yet existing)

**Responsibilities:**
- In-app notification dispatch
- Push notification delivery (FCM, APNs future)
- Notification queue and rate-limited delivery

**Allowed Dependencies:**
- `common/event-bus`
- `common/logger`
- `database/repositories` (INotificationRepository)
- Device Router (for push target resolution)

**Forbidden Dependencies:**
- Call Manager directly (subscribes to call events via Event Bus)
- Session Manager

**Public Interface:**
```
dispatchNotification(notification: CreateNotification): Notification
getUserNotifications(userId: string, pagination: Pagination): Notification[]
markRead(notificationId: string): void
registerPushToken(deviceId: string, token: string): void
```

**Events Subscribed:**
- `call.created` → incoming call notification
- `call.state_changed` → missed call notification
- `callback.scheduled` → callback reminder notification

---

## 7. Callback Engine

**Folder:** `backend/src/callbacks/`
**Status:** To be extracted from `voicebridge/service.ts`

**Responsibilities:**
- Callback scheduling with configurable intervals
- Timer-based callback firing
- Retry with exponential backoff
- Dead letter after max retries

**Allowed Dependencies:**
- `common/event-bus`
- `common/logger`
- `database/repositories` (ICallbackRepository)

**Forbidden Dependencies:**
- Call Manager, Presence, Transport, any other service

**Public Interface:**
```
scheduleCallback(params: ScheduleCallbackParams): Callback
cancelCallback(callbackId: string): void
getUserCallbacks(userId: string): Callback[]
```

**Events Published:**
- `callback.scheduled`
- `callback.fired`
- `callback.cancelled`

---

## 8. Device Router

**Folder:** `backend/src/devices/`
**Status:** To be implemented (not yet existing)

**Responsibilities:**
- Device registration, listing, unregistration
- Device capability tracking
- Event routing to correct device
- Push token management

**Allowed Dependencies:**
- `common/event-bus`
- `common/logger`
- `database/repositories` (IDeviceRepository)

**Forbidden Dependencies:**
- Call Manager, Session Manager, Presence Engine

**Public Interface:**
```
registerDevice(params: RegisterDeviceParams): Device
getDevice(deviceId: string): Device | null
listUserDevices(userId: string): Device[]
unregisterDevice(deviceId: string): void
routeEvent(userId: string, event: GatewayEvent): void
```

---

## 9. History Service

**Folder:** `backend/src/history/`
**Status:** To be extracted from `voicebridge/service.ts`

**Responsibilities:**
- Call history persistence
- Transcript storage and retrieval
- Pagination and filtering

**Allowed Dependencies:**
- `common/event-bus`
- `common/logger`
- `database/repositories` (ICallRepository, ITranscriptRepository)

**Forbidden Dependencies:**
- Any active service (Call Manager, Presence, etc.)

**Public Interface:**
```
recordCall(callData: CallData): void
getUserHistory(userId: string, filter: HistoryFilter): PaginatedResult<CallSummary>
getTranscript(callId: string): Transcript
```

**Events Subscribed:**
- `call.completed`
- `call.cancelled`

---

## 10. Communication Gateway

**Folder:** `backend/src/gateway/`
**Status:** To be implemented (not yet existing)

**Responsibilities:**
- Unified transport layer: WebSocket + SSE + WebRTC signaling
- Connection lifecycle management
- Rate limiting at transport level
- Event → correct transport → correct device routing

**Allowed Dependencies:**
- `common/event-bus`
- `common/logger`
- `common/config`
- `ws` (WebSocket library)
- `node:http`

**Forbidden Dependencies:**
- Any service module (Call Manager, Presence, etc.)

**Public Interface:**
```
connectWebSocket(userId: string, deviceId: string): WebSocketConnection
connectSSE(userId: string, deviceId: string): SSEConnection
disconnect(userId: string, deviceId: string): void
sendEvent(userId: string, deviceId: string, event: GatewayEvent): void
broadcast(userId: string, event: GatewayEvent): void
```

**Events Subscribed:**
- All events that need delivery to a device

---

## 11. Event Bus

**Folder:** `backend/src/common/`
**Status:** To be implemented (REFACTOR_PLAN.md Phase 2)

**Responsibilities:**
- Topic-based publish/subscribe
- Async handler execution
- Retry with exponential backoff
- Dead letter queue
- Handler registration and unregistration

**Allowed Dependencies:**
- `common/logger`

**Forbidden Dependencies:**
- Any service module

**Public Interface:**
```
publish<T>(topic: string, event: T): void
subscribe<T>(topic: string, handler: EventHandler<T>): () => void
unsubscribe(topic: string, handler: EventHandler): void
```

---

## 12. Shared Utilities (`common/`)

**Folder:** `backend/src/common/`

**Responsibilities:**
- Configuration loading and validation
- Pino logger setup
- Shared type definitions
- Redis client management
- Event Bus infrastructure

**Allowed Dependencies:**
- External packages only (pino, dotenv, ioredis)
- No internal modules

**Forbidden Dependencies:**
- Any service or transport module
- Any database repository

**Public Interfaces:**
```
config — application configuration
logger — Pino logger instance
eventBus — Event Bus instance
redis — Redis client (once implemented)
types — shared types (CallPriority, etc.)
```

---

## 13. Database Layer

**Folder:** `backend/src/database/`

**Responsibilities:**
- Knex.js configuration
- Migration management
- Repository interfaces and implementations
- Connection pool management

**Allowed Dependencies:**
- `common/logger`
- `common/config`
- `knex`, `pg` (external)

**Forbidden Dependencies:**
- Any service module
- Any transport module

**Public Interfaces:**
```
knex — configured Knex instance
IUserRepository, IProviderRepository, ICallRepository, etc.
createRepositories(db: Knex): AllRepositories
```

---

## 14. MCP Server

**Folder:** `mcp-server/src/`

**Responsibilities:**
- MCP tool definitions and schemas
- HTTP/SSE transport for MCP
- API client to backend
- Request validation

**Allowed Dependencies:**
- `@modelcontextprotocol/sdk` (external)
- `zod` (for validation)
- `node:http`, `node:crypto`
- `pino`

**Forbidden Dependencies:**
- Any backend internal module (communicates via HTTP only)
- Any Android/iOS code

**Public Interfaces:**
```
tools — array of MCP tool definitions
startSSEServer — creates HTTP server with SSE transport
createConfiguredServer — creates MCP Server with tool handlers
client — HTTP client for backend API
```

---

## 15. Android App

**Folder:** `mobile/android/`

**Responsibilities:**
- Communication endpoint for the user
- Login/authentication
- Call UI and controls
- Settings and configuration
- Notification handling
- Presence display

**Architecture (target):**
```
AppModule (Hilt DI)
├── TtsManager
├── SttManager
├── BargeInDetector
├── CallSession
├── SignalingClient → WebSocket → Gateway
├── ApiClient → HTTP → Backend API
├── NotificationHelper
├── HomeViewModel
├── CallViewModel
└── UI (Compose screens)
```

**Allowed Dependencies:**
- AndroidX libraries
- Jetpack Compose + Material 3
- OkHttp + Retrofit
- Hilt
- Coroutines + Flow

**Forbidden Dependencies:**
- iOS code
- Backend internal code (communicates via HTTP/WebSocket only)
- MCP server code

---

## 16. Infrastructure

**Folder:** `infra/`

**Responsibilities:**
- Docker Compose orchestration
- Caddy reverse proxy configuration
- Coturn TURN/STUN configuration
- Health check definitions

**Allowed Dependencies:**
- Docker
- External Docker images only (postgres:16, redis:7, caddy, coturn/coturn)

**Forbidden Dependencies:**
- Source code references (no hardcoded paths to source)

---

## Ownership Dependency Graph (Target)

```
                    ┌─────────────┐
                    │  Event Bus   │◄── All services publish/subscribe
                    └─────────────┘
                          │
    ┌─────────────────────┼─────────────────────┐
    │                     │                     │
    ▼                     ▼                     ▼
┌──────────┐     ┌──────────────┐     ┌────────────────┐
│ Gateway   │     │ Auth Service │     │  All Services  │
│ (transport)│     │              │     │ ┌────────────┐ │
│           │     │ Validates    │     │ │ Call Mgr   │ │
│ Events →  │     │ tokens for:  │     │ │ Presence   │ │
│ Bus       │     │ • Gateway    │     │ │ Notify     │ │
└──────────┘     │ • API        │     │ │ Callback   │ │
                 │ • MCP        │     │ │ Sessions   │ │
                 └──────────────┘     │ │ History    │ │
                                      │ │ Providers  │ │
                                      │ │ Devices    │ │
                                      │ └────────────┘ │
                                      └────────────────┘
                                               │
                                               ▼
                                      ┌────────────────┐
                                      │  Repositories  │
                                      │  (PostgreSQL)  │
                                      └────────────────┘
```

**Key rule:** Arrows point from dependent to dependency. No service imports another service. All communication is via Event Bus.
