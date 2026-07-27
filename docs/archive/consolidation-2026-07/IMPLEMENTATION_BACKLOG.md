# AgentCall v2 — Implementation Backlog

> Smallest shippable tasks. Solo-developer order. Working software at every step.
> Each task ≤ 6 hours. Ship or revert by end of day.

---

## Milestone Map

```
M0 ─ Architecture Freeze (6 tasks, ~18h)
 │
 ├──▶ M1 ─ Daemon Core (5 tasks, ~18h) ────────────────────────────────┐
 │    │                                                                  │
 │    ├──▶ M2 ─ MCP Transport + Tools (6 tasks, ~24h) ────┐             │
 │    │                                                     │             │
 │    ├──▶ M3 ─ SQLite Storage (4 tasks, ~16h) ────────────┼──┐          │
 │    │                                                     │  │          │
 │    └─────────────────────────────────────────────────────┼──┼──────────┘
 │                                                          │  │
 │        ┌─────────────────────────────────────────────────┘  │
 │        ▼                                                    ▼
 │   M4 ─ Android Pairing (6 tasks, ~28h) ◀──────────── M3 (sessions)
 │        │
 │        ├──▶ M5 ─ Push Notifications (5 tasks, ~22h)
 │        │    │
 │        │    ├──▶ M6 ─ Offline Queue (4 tasks, ~16h)
 │        │
 │        ├──▶ M7 ─ Presence (4 tasks, ~14h)
 │        │
 │        ├──▶ M8 ─ Discovery (3 tasks, ~12h)
 │        │
 │        └──▶ M9 ─ Session History (3 tasks, ~12h)
 │
 └──▶ M10 ─ Hardening (5 tasks, ~20h)
```

**Critical path (minimal demo):** M0 → M1 → M2 + M3 → M4 → M5 (task 5.5)
**Total to demo:** ~33 tasks, ~130 hours (~6 weeks solo)
**Full backlog:** ~51 tasks, ~200 hours (~8 weeks solo)

---

## M0 — Architecture Freeze (~18h)

Freeze all decisions before writing code. Any architecture change after M0
requires a written ADR signed by the team.

### M0.1 — Create ADR directory and template

| Field | Value |
|---|---|
| **ID** | `M0.1` |
| **Description** | Create `docs/adr/` directory with ADR template (based on Michael Nygard's format). Write the first ADR: "Use MCP as sole protocol." All architecture decisions must use this template going forward. |
| **Dependencies** | None |
| **Acceptance Criteria** | `docs/adr/README.md` exists. `docs/adr/template.md` exists with Title, Status, Context, Decision, Consequences sections. `docs/adr/001-mcp-protocol.md` is written and approved. |
| **Effort** | 2h |
| **Risk** | Low |

### M0.2 — Write the 5 architecture decision records

| Field | Value |
|---|---|
| **ID** | `M0.2` |
| **Description** | Write ADRs for: 002-sqlite-storage (why not Postgres), 003-sse-default-transport (why SSE over stdio), 004-text-first-capabilities (why text-only v1), 005-single-user-v1 (why multi-user is v2). Each ADR follows the template with clear context, alternatives considered, and consequences. |
| **Dependencies** | `M0.1` |
| **Acceptance Criteria** | 5 ADRs exist in `docs/adr/002.md` through `docs/adr/006.md`. Each ADR lists at least 2 alternatives that were rejected. Each ADR lists known consequences. |
| **Effort** | 4h |
| **Risk** | Low |

### M0.3 — Create daemon directory structure

| Field | Value |
|---|---|
| **ID** | `M0.3` |
| **Description** | Create `daemon/` directory at repo root with `package.json`, `tsconfig.json`, `.eslintrc.js`, `.prettierrc`. Install deps: `typescript`, `vitest`, `better-sqlite3`, `zod`. Configure strict TypeScript mode. No application code yet. |
| **Dependencies** | None |
| **Acceptance Criteria** | `cd daemon && npm run build` succeeds (compiles empty project). `cd daemon && npm test` runs Vitest with 0 tests (passes). ESLint passes on empty project. |
| **Effort** | 3h |
| **Risk** | Low |

### M0.4 — Define all TypeScript types and interfaces

| Field | Value |
|---|---|
| **ID** | `M0.4` |
| **Description** | Create `daemon/src/types.ts` with all shared types: `Session`, `Message`, `Device`, `Agent`, `Policy`, `Presence`, `CommunicationCapability`, `Urgency`, `SessionStatus`, `DeliveryResult`, `PolicyDecision`. No implementation. Every type is documented with JSDoc. |
| **Dependencies** | `M0.3` |
| **Acceptance Criteria** | `daemon/src/types.ts` compiles with `tsc --noEmit`. Every exported type has a JSDoc comment. `CommunicationCapability` is a union of string literals. No `any` types. |
| **Effort** | 4h |
| **Risk** | Low |

### M0.5 — Define all error codes

| Field | Value |
|---|---|
| **ID** | `M0.5` |
| **Description** | Create `daemon/src/errors.ts` with structured error classes. Define every error code from the MCP API spec: `AGENT_NOT_FOUND`, `AGENT_BLOCKED`, `SESSION_NOT_FOUND`, `SESSION_EXPIRED`, `INVALID_CAPABILITY`, `RATE_LIMITED`, `QUIET_HOURS`, `DELIVERY_FAILED`, `INVALID_SCHEMA`, `INTERNAL_ERROR`. Each error has a `code`, `message`, and `details` field. |
| **Dependencies** | `M0.4` |
| **Acceptance Criteria** | All 10 error codes are defined. Each error can be instantiated with `new AgentCallError('DELIVERY_FAILED', { attemptedDevices: 2 })`. Errors are serializable to JSON. |
| **Effort** | 2h |
| **Risk** | Low |

### M0.6 — Write integration test scaffold

| Field | Value |
|---|---|
| **ID** | `M0.6` |
| **Description** | Create `daemon/tests/` directory with Vitest setup. Create `daemon/tests/helpers.ts` with test utilities: `createTestDaemon()`, `createTestSession()`, `createTestAgent()`. Create `daemon/tests/integration/daemon.test.ts` with a placeholder test that verifies the test helper works. |
| **Dependencies** | `M0.3`, `M0.4` |
| **Acceptance Criteria** | `npm test` runs the placeholder test and passes. `createTestDaemon()` helper exists (can be a no-op for now). Test utilities exposed for use by all future test files. |
| **Effort** | 3h |
| **Risk** | Low |

---

## M1 — Daemon Core (~18h)

### M1.1 — Implement config loader

| Field | Value |
|---|---|
| **ID** | `M1.1` |
| **Description** | Implement `daemon/src/config.ts` that loads config from environment variables, `~/.config/agentcall/config.json`, and defaults. Validate all required fields with Zod. Export a typed `Config` object. If config is invalid, log a clear error message and exit. |
| **Dependencies** | `M0.5` |
| **Acceptance Criteria** | `ConfigService.init()` loads and returns a typed config. Missing `AGENTCALL_SSE_PORT` defaults to `7377`. Invalid config exits with `process.exit(1)` and logs the specific validation error. Config is frozen after init (cannot be mutated). |
| **Effort** | 4h |
| **Risk** | Low |

### M1.2 — Implement structured logger

| Field | Value |
|---|---|
| **ID** | `M1.2` |
| **Description** | Implement `daemon/src/logger.ts` with structured JSON logging. Support `debug`, `info`, `warn`, `error` levels. Each log entry includes timestamp, level, component, message, and optional metadata. Logger is configurable (text mode for development, JSON mode for production). Log to stdout by default, optional file output. |
| **Dependencies** | `M1.1` |
| **Acceptance Criteria** | `logger.info('router', 'session_created', { sessionId: 'abc' })` outputs `{"timestamp":"...","level":"info","component":"router","message":"session_created","sessionId":"abc"}` in JSON mode. Text mode outputs `[2026-07-26T10:00:00.000Z] [INFO] [router] session_created sessionId=abc`. |
| **Effort** | 3h |
| **Risk** | Low |

### M1.3 — Implement daemon lifecycle

| Field | Value |
|---|---|
| **ID** | `M1.3` |
| **Description** | Implement `daemon/src/daemon.ts` with the daemon lifecycle: `init()` → `start()` → `shutdown()`. `init()` loads config and initializes all components (storage, MCP, push, relay). `start()` begins accepting connections. `shutdown()` does graceful shutdown (flush queue, close connections). Handle SIGTERM and SIGINT. |
| **Dependencies** | `M1.1`, `M1.2` |
| **Acceptance Criteria** | `Daemon.start()` logs a startup sequence. `Daemon.shutdown()` flushes pending work and exits cleanly. SIGTERM triggers graceful shutdown within 5 seconds. SIGKILL is handled by crash recovery on next start (logged). |
| **Effort** | 5h |
| **Risk** | Medium — lifecycle bugs cause hangs |

### M1.4 — Implement entry point

| Field | Value |
|---|---|
| **ID** | `M1.4` |
| **Description** | Create `daemon/src/index.ts` as the entry point. Parse CLI args (`--config`, `--version`, `--help`). Instantiate daemon, call start, wait for shutdown. No MCP or storage wiring yet — just the skeleton that starts and stops cleanly. |
| **Dependencies** | `M1.3` |
| **Acceptance Criteria** | `node daemon/dist/index.js` starts the daemon, logs startup, and blocks. Ctrl+C triggers shutdown within 2 seconds. `node daemon/dist/index.js --help` prints usage. |
| **Effort** | 2h |
| **Risk** | Low |

### M1.5 — Add health check endpoint

| Field | Value |
|---|---|
| **ID** | `M1.5` |
| **Description** | Implement `daemon/src/health.ts` with a health check function. Returns daemon status: version, uptime, active sessions, registered devices/agents, mcp transport, storage status. Wire into daemon lifecycle so health reflects real component states (not hardcoded). |
| **Dependencies** | `M1.3` |
| **Acceptance Criteria** | `Daemon.health()` returns `{ status: 'ok', version: '2.0.0', uptimeSeconds: 123 }`. When storage is down, returns `{ status: 'degraded', storage: 'error', uptimeSeconds: 123 }`. All fields populated from real state. |
| **Effort** | 4h |
| **Risk** | Low |

---

## M2 — MCP Transport + Tools (~24h)

### M2.1 — Implement MCP transport abstraction

| Field | Value |
|---|---|
| **ID** | `M2.1` |
| **Description** | Create `daemon/src/mcp/transport.ts` with an abstract `MCPTransport` interface. Two implementations: `StdioTransport` (read from stdin, write to stdout) and `SSETransport` (HTTP server with SSE for responses). Both implement the same interface so tools don't know which transport they're running on. |
| **Dependencies** | `M0.4`, `M1.4` |
| **Acceptance Criteria** | `new StdioTransport()` reads JSON-RPC from stdin and writes responses to stdout. `new SSETransport({ port: 7377 })` starts an HTTP server, accepts `POST /mcp` for incoming messages, sends responses via SSE. Both implement `onRequest(handler)` and `sendResponse(response)`. |
| **Effort** | 5h |
| **Risk** | Medium — MCP protocol parsing edge cases |

### M2.2 — Implement API key auth middleware

| Field | Value |
|---|---|
| **ID** | `M2.2` |
| **Description** | Create `daemon/src/mcp/auth.ts` that extracts the API key from MCP requests. For stdio: first line of stdin. For SSE: `Authorization: Bearer <key>` header. Look up key in agents table. Return agent_id if found, or throw `AGENT_NOT_FOUND`. Cache successful lookups for 5 minutes. |
| **Dependencies** | `M2.1`, `M3.2` (agents table must exist) |
| **Acceptance Criteria** | Valid API key → returns agent_id. Invalid API key → throws `AGENT_NOT_FOUND` error. SSE header extraction works. stdio first-line extraction works. Missing key → throws `AUTH_REQUIRED` error. |
| **Effort** | 4h |
| **Risk** | Low |

### M2.3 — Implement MCP server with tool registry

| Field | Value |
|---|---|
| **ID** | `M2.3` |
| **Description** | Create `daemon/src/mcp/server.ts` that implements the MCP server loop: receive JSON-RPC → parse method → route to tool handler → return response. Implement `tools/list` and `tools/call` handlers. Tool registry maps tool names to handler functions with Zod schema validation for inputs. |
| **Dependencies** | `M2.1` |
| **Acceptance Criteria** | `{"jsonrpc":"2.0","method":"tools/list","id":1}` returns list of registered tools (can be empty initially). `{"jsonrpc":"2.0","method":"tools/call","params":{"name":"unknown_tool"},"id":1}` returns method-not-found error. Invalid params return validation error with details. |
| **Effort** | 5h |
| **Risk** | Medium — JSON-RPC protocol compliance |

### M2.4 — Implement `request_communication` tool

| Field | Value |
|---|---|
| **ID** | `M2.4` |
| **Description** | Register the `request_communication` tool in the MCP registry. Handler: validate input with Zod → check agent auth → create session in SQLite → return session_id + status. No delivery yet (just storage). Input schema matches the MCP API spec (recipient_id, capability, context, urgency, options, ttl_seconds). |
| **Dependencies** | `M2.3`, `M3.3` (session CRUD must exist) |
| **Acceptance Criteria** | Calling `request_communication` returns `{ session_id, status: 'pending', created_at, expires_at }`. Invalid capability returns `INVALID_CAPABILITY`. Missing context returns `INVALID_SCHEMA`. Session is persisted in SQLite. |
| **Effort** | 4h |
| **Risk** | Low |

### M2.5 — Implement `send_message` and `get_session` tools

| Field | Value |
|---|---|
| **ID** | `M2.5` |
| **Description** | Register `send_message` and `get_session` tools. `send_message`: validate session_id exists and belongs to agent → append message → return message_id. `get_session`: validate session_id → return full session with messages. Both check auth (agent must own session). |
| **Dependencies** | `M2.4`, `M3.3` |
| **Acceptance Criteria** | `send_message` to valid session returns `{ message_id, status: 'delivered' }`. `send_message` to expired session returns `SESSION_EXPIRED`. `get_session` returns full session with all messages. `get_session` for wrong agent returns `SESSION_NOT_OWNED`. |
| **Effort** | 4h |
| **Risk** | Low |

### M2.6 — Implement `cancel_session`, `register_agent`, `list_sessions` tools

| Field | Value |
|---|---|
| **ID** | `M2.6` |
| **Description** | Register remaining tools. `cancel_session`: set status to cancelled, optional reason. `register_agent`: create agent + generate API key + return key. `list_sessions`: return recent sessions for this agent with optional filters (limit, status, since). |
| **Dependencies** | `M2.5`, `M3.2`, `M3.3` |
| **Acceptance Criteria** | `cancel_session(session_id)` returns `{ status: 'cancelled' }`. `register_agent("Claude")` returns agent_id + API key. Agent key is returned once, stored as bcrypt hash. `list_sessions()` returns only caller's sessions. `list_sessions({ status: 'active' })` filters correctly. |
| **Effort** | 2h |
| **Risk** | Low |

---

## M3 — SQLite Storage (~16h)

### M3.1 — Set up SQLite with auto-schema

| Field | Value |
|---|---|
| **ID** | `M3.1` |
| **Description** | Create `daemon/src/storage/db.ts` that initializes better-sqlite3. On first launch, create `agentcall.db` in `AGENTCALL_DATA_DIR`. Run schema creation if tables don't exist. Enable WAL mode for concurrent access. Wrap in a `Storage` class that provides `init()`, `close()`, and `isHealthy()` methods. |
| **Dependencies** | `M1.1` |
| **Acceptance Criteria** | `Storage.init()` creates the file if it doesn't exist. Schema is created atomically (`CREATE TABLE IF NOT EXISTS`). WAL mode is enabled. `Storage.isHealthy()` returns true after init, false if file is corrupted. `Storage.close()` flushes WAL and closes connection. |
| **Effort** | 3h |
| **Risk** | Low |

### M3.2 — Implement agents table + CRUD

| Field | Value |
|---|---|
| **ID** | `M3.2` |
| **Description** | Implement `daemon/src/storage/agents.ts` with typed CRUD for the agents table. `createAgent(name, trustLevel)`: insert with generated ID + bcrypt-hashed API key. `getByApiKey(key)`: find agent by key hash. `getById(id)`: get agent details. `updatePolicy(id, policy)`: update policy fields. `listAll()`: list all agents (no key returned). |
| **Dependencies** | `M3.1` |
| **Acceptance Criteria** | `createAgent("Claude", 2)` returns agent_id + api_key. `getByApiKey(validKey)` returns agent. `getByApiKey(invalidKey)` returns null. `listAll()` returns all agents without API key. All operations use prepared statements to prevent SQL injection. |
| **Effort** | 4h |
| **Risk** | Low |

### M3.3 — Implement sessions + messages tables + CRUD

| Field | Value |
|---|---|
| **ID** | `M3.3` |
| **Description** | Implement `daemon/src/storage/sessions.ts` with typed CRUD. `createSession(session)`: insert session + return. `getSession(id)`: return session with messages joined. `updateSession(id, fields)`: partial update. `appendMessage(sessionId, message)`: insert message. `listSessions(agentId, filters)`: list with optional filtering. `markDelivered(id, deviceId)`: update delivery status. |
| **Dependencies** | `M3.1` |
| **Acceptance Criteria** | `createSession()` inserts into sessions + returns session with generated ID. `appendMessage()` inserts into messages with FK constraint. `getSession()` returns session with all messages in order. `listSessions({ status: 'pending' })` filters correctly. Invalid FK returns `SESSION_NOT_FOUND` error. |
| **Effort** | 5h |
| **Risk** | Medium — JOIN query performance for getSession |

### M3.4 — Implement devices + policies tables + CRUD

| Field | Value |
|---|---|
| **ID** | `M3.4` |
| **Description** | Implement `daemon/src/storage/devices.ts` and `daemon/src/storage/policies.ts`. Devices: `registerDevice()`, `getUserDevices()`, `updateHeartbeat()`, `markInactive()`. Policies: `getAgentPolicy(id)`, `updateAgentPolicy(id, fields)`, `getGlobalPolicy()`, `updateGlobalPolicy(fields)`. |
| **Dependencies** | `M3.1` |
| **Acceptance Criteria** | `registerDevice()` returns device with generated ID. `getUserDevices("me")` returns all devices for user. `updateHeartbeat()` updates `last_seen_at`. `getAgentPolicy("agent_123")` returns policy with defaults for unset fields. |
| **Effort** | 4h |
| **Risk** | Low |

---

## M4 — Android Pairing (~28h)

### M4.1 — Create new Android project scaffold

| Field | Value |
|---|---|
| **ID** | `M4.1` |
| **Description** | Create `mobile/android/` with a new Android project (not modifying the existing one). Use Jetpack Compose, Hilt, Navigation Compose. Package: `com.agentcall.v2`. Minimum SDK 26. Create the main activity with empty Compose entry point. No business logic yet. |
| **Dependencies** | None (new project, no dependencies on existing code) |
| **Acceptance Criteria** | `./gradlew assembleDebug` succeeds. App launches on emulator showing blank screen. Package name is `com.agentcall.v2`. Target SDK 33+. |
| **Effort** | 4h |
| **Risk** | Low |

### M4.2 — Implement DaemonClient HTTP layer

| Field | Value |
|---|---|
| **ID** | `M4.2` |
| **Description** | Create `com.agentcall.v2.daemon.DaemonClient` — a Kotlin HTTP client (OkHttp) that talks to the daemon's REST API. Implement: `getHealth()`, `getSessions()`, `getSession(id)`, `respondToSession(id, text)`, `registerDevice(pushToken)`, `getAgentList()`, `updateAgentPolicy(id, policy)`. Handle connection errors gracefully. |
| **Dependencies** | `M4.1`, `M1.5` (health endpoint) |
| **Acceptance Criteria** | `DaemonClient("http://192.168.1.100:7377").getHealth()` returns health object. Connection refused returns `DaemonException.ConnectionRefused`. Timeout returns `DaemonException.Timeout`. All methods return typed data classes (not JSON strings). |
| **Effort** | 5h |
| **Risk** | Low — standard HTTP client |

### M4.3 — Implement session list screen

| Field | Value |
|---|---|
| **ID** | `M4.3` |
| **Description** | Create `SessionListScreen` — the main inbox view. Shows sessions grouped by status (pending first, then active, then recent). Each session card shows: agent name/icon, capability icon, context preview (first 80 chars), timestamp, urgency indicator. Pull-to-refresh updates list. Empty state when no sessions. |
| **Dependencies** | `M4.2` |
| **Acceptance Criteria** | Screen fetches sessions from daemon on load. Pending sessions appear at top. Each item shows agent, context, time, urgency. Pull-to-refresh re-fetches. Empty state shows "No sessions yet" message. Loading state shows shimmer. Error state shows retry button. |
| **Effort** | 5h |
| **Risk** | Low |

### M4.4 — Implement session detail screen

| Field | Value |
|---|---|
| **ID** | `M4.4` |
| **Description** | Create `SessionDetailScreen` — shows full session with message list and reply input. Messages displayed as chat bubbles (AI on left, human on right). Reply input at bottom with send button. Auto-scroll to latest message. For `decision` capability: show options as tappable cards instead of text input. For `approval` capability: show approve/reject buttons. |
| **Dependencies** | `M4.3` |
| **Acceptance Criteria** | Session messages displayed chronologically. Reply sends text to daemon and appends to chat. Decision capability shows option cards; tapping sends the chosen option. Approval shows approve/reject buttons. Send button disabled while request in flight. Error state if daemon unreachable. |
| **Effort** | 6h |
| **Risk** | Medium — capability-specific UI adds branching |

### M4.5 — Implement bottom navigation shell

| Field | Value |
|---|---|
| **ID** | `M4.5` |
| **Description** | Create `MainActivity` with bottom navigation. Three tabs: Sessions (SessionListScreen), Agents (AgentListScreen placeholder), Settings (SettingsScreen placeholder). Navigation Compose with proper back stack handling. Session detail pushes onto navigation stack. Deep link from notification → SessionDetailActivity (separate activity). |
| **Dependencies** | `M4.3` |
| **Acceptance Criteria** | Bottom nav shows 3 tabs. Sessions tab is default. Tapping a session opens detail with back navigation. Back from session detail returns to list. Deep link `agentcall://sessions/{id}` opens session detail directly. |
| **Effort** | 4h |
| **Risk** | Low |

### M4.6 — Implement DaemonConnection (WebSocket event stream)

| Field | Value |
|---|---|
| **ID** | `M4.6` |
| **Description** | Implement `DaemonConnection` — WebSocket client that connects to `ws://{daemon}:{port}/events`. Receives real-time events: `session_created`, `session_updated`, `message_received`, `session_acknowledged`. Emits these as Kotlin flows that `SessionListScreen` and `SessionDetailScreen` collect to update UI without polling. Auto-reconnect with exponential backoff (1s, 2s, 4s, max 30s). |
| **Dependencies** | `M4.2` |
| **Acceptance Criteria** | WebSocket connects on app foreground. Events received update session list without refresh. Acknowledgment event removes notification from other devices. Auto-reconnect works after disconnect. Connection state exposed as StateFlow for UI. |
| **Effort** | 4h |
| **Risk** | Medium — WebSocket reconnection edge cases |

---

## M5 — Push Notifications (~22h)

### M5.1 — Set up Firebase project + FCM credentials

| Field | Value |
|---|---|
| **ID** | `M5.1` |
| **Description** | Create Firebase project, download `service-account.json`. Add FCM to `daemon/package.json` (`firebase-admin`). Create `daemon/src/push/fcm.ts` that initializes Firebase Admin SDK from the service account file path in config. Implement `sendPush(token, payload)` that sends a data message. |
| **Dependencies** | `M1.1` (config path), `M3.4` (device tokens) |
| **Acceptance Criteria** | Firebase Admin SDK initializes from service account path. `sendPush(validToken, { sessionId, capability, context })` sends message via FCM. Invalid token returns error without crashing. Service account path configurable via `AGENTCALL_FCM_CREDENTIALS`. |
| **Effort** | 4h |
| **Risk** | Medium — requires Firebase project with billing enabled |

### M5.2 — Add FCM to Android app + PushService

| Field | Value |
|---|---|
| **ID** | `M5.2` |
| **Description** | Add Firebase Messaging to Android project. Create `PushService` extending `FirebaseMessagingService`. `onNewToken`: register token with daemon via `DaemonClient.registerDevice()`. `onMessageReceived`: parse data payload (sessionId, capability, context, agentName), build and show Android notification. |
| **Dependencies** | `M4.2`, `M5.1` |
| **Acceptance Criteria** | `onNewToken` sends token to daemon on first launch and token refresh. Incoming FCM data message shows Android notification. Notification tap opens SessionDetailScreen via deep link. Notification includes capability-appropriate content. |
| **Effort** | 5h |
| **Risk** | Medium — FCM setup requires Google Play Services |

### M5.3 — Implement daemon push delivery router

| Field | Value |
|---|---|
| **ID** | `M5.3` |
| **Description** | Implement `daemon/src/delivery/router.ts` that routes sessions to devices. On session creation: query device registry → filter by capability → sort by priority → attempt push delivery to first-tier device. Implement `attemptDelivery(session, device)` that calls `FcmPush.send()`. If delivery fails, try next device. If all fail, set session status to `failed` and log. |
| **Dependencies** | `M2.4`, `M3.4`, `M5.1` |
| **Acceptance Criteria** | Creating a session triggers delivery to the user's highest-priority device. Successful delivery updates session status to `delivered`. Invalid push token marks device inactive and tries next device. All devices fail → session status `failed`. Delivery attempts logged with device ID and result. |
| **Effort** | 5h |
| **Risk** | Medium — async delivery pipeline |

### M5.4 — Implement device registration endpoint (daemon HTTP API)

| Field | Value |
|---|---|
| **ID** | `M5.4` |
| **Description** | Add HTTP endpoints to the daemon (separate from MCP, for mobile app use): `POST /api/devices` (register device with push token, platform, name, capabilities), `GET /api/sessions` (list sessions), `GET /api/sessions/{id}` (get session detail), `POST /api/sessions/{id}/respond` (send human response). These are simple wrappers around storage CRUD. No auth on these endpoints (daemon binds to localhost by default). |
| **Dependencies** | `M3.3`, `M3.4`, `M1.4` |
| **Acceptance Criteria** | `POST /api/devices` with `{ pushToken, platform, name }` returns device ID. `GET /api/sessions` returns session list. `POST /api/sessions/{id}/respond` with `{ text: "GCP" }` appends human message. Invalid session returns 404. All endpoints return JSON. |
| **Effort** | 4h |
| **Risk** | Low |

### M5.5 — Wire up end-to-end push delivery

| Field | Value |
|---|---|
| **ID** | `M5.5` |
| **Description** | Connect all the pieces: MCP `request_communication` → Session Engine → Delivery Router → FCM → Android PushService → notification → tap → SessionDetailScreen. This is the integration task. Fix any broken connections between components. Write a manual test script for verification. |
| **Dependencies** | `M2.4`, `M5.2`, `M5.3`, `M5.4` |
| **Acceptance Criteria** | Full end-to-end flow works: `request_communication` called via MCP → push notification appears on phone within 10 seconds → tap opens session → user types response → response reaches daemon → `get_session` returns the response. Manual test script documents each step. |
| **Effort** | 4h |
| **Risk** | High — integration bugs across 4 components |

---

## M6 — Offline Queue (~16h)

### M6.1 — Implement local Room database for response queue

| Field | Value |
|---|---|
| **ID** | `M6.1` |
| **Description** | Add Room database to Android project. Create `PendingResponse` entity: `id`, `sessionId`, `text`, `createdAt`, `retryCount`, `status` (pending/sending/failed). Create DAO with insert, query, update, delete. Database accessible via Hilt singleton. |
| **Dependencies** | `M4.1` |
| **Acceptance Criteria** | Room database is created on first app launch. `PendingResponse` can be inserted, queried, updated, and deleted. `getAllPending()` returns responses with `status = pending`. Database migration strategy in place (for future schema changes). |
| **Effort** | 4h |
| **Risk** | Low |

### M6.2 — Implement offline-aware response sending

| Field | Value |
|---|---|
| **ID** | `M6.2` |
| **Description** | Modify `SessionDetailScreen` reply flow: when user sends a response, first save to Room as `pending`, then attempt HTTP POST to daemon. If POST succeeds, delete from Room. If POST fails (connection refused, timeout), keep in Room. Show indicator in UI: "Message queued — will send when connected." |
| **Dependencies** | `M4.4`, `M6.1` |
| **Acceptance Criteria** | Response is saved to Room before HTTP attempt. Successful HTTP → removed from Room. Failed HTTP → stays in Room with `pending` status. UI shows "Queued" indicator next to message. User can continue using app while queued. |
| **Effort** | 4h |
| **Risk** | Low |

### M6.3 — Implement background sync worker

| Field | Value |
|---|---|
| **ID** | `M6.3` |
| **Description** | Use WorkManager to create a periodic background worker that checks for pending responses. On each run: query Room for `pending` responses, attempt HTTP POST to daemon for each, update status. Use exponential backoff between retries (1min, 5min, 15min, 30min, 1h). Worker runs when device has connectivity (NetworkType.CONNECTED constraint). |
| **Dependencies** | `M6.2` |
| **Acceptance Criteria** | Worker registers on app start. Worker fires within 15 minutes of app going to background with pending responses. Successful send removes from Room. Failed send increments retryCount and backs off. Worker stops after 3 consecutive failures for same response (surfaces error to user). |
| **Effort** | 5h |
| **Risk** | Medium — WorkManager timing is device-dependent |

### M6.4 — Implement delivery acknowledgment from daemon

| Field | Value |
|---|---|
| **ID** | `M6.4` |
| **Description** | Add acknowledgment protocol: when Android app receives a session update (via WebSocket or push), it sends `POST /api/sessions/{id}/acknowledge` with `{ deviceId }`. Daemon stores `acknowledgedDevice` on session and suppresses notifications to other devices. Daemon also sends a delivery confirmation back to the AI via a new event (if AI is subscribed). |
| **Dependencies** | `M5.3`, `M5.4` |
| **Acceptance Criteria** | Acknowledgment removes session notification from other WebSocket-connected devices. Daemon logs `session_acknowledged` with device ID. Subsequent push to same session is skipped if already acknowledged. AI agent receives acknowledgment event (if subscribed via MCP resources). |
| **Effort** | 3h |
| **Risk** | Low |

---

## M7 — Presence (~14h)

### M7.1 — Implement device heartbeat

| Field | Value |
|---|---|
| **ID** | `M7.1` |
| **Description** | Add heartbeat endpoint: `POST /api/devices/{id}/heartbeat`. Android app sends heartbeat every 60 seconds while foregrounded, every 5 minutes while backgrounded (via WorkManager periodic task). Daemon updates `last_seen_at` on the device record. Devices with no heartbeat for 5 minutes are considered inactive. |
| **Dependencies** | `M3.4`, `M5.4` |
| **Acceptance Criteria** | Heartbeat updates `last_seen_at`. Device returns to `active` on first heartbeat after being inactive. Devices with heartbeat > 5min ago return `isActive: false` in queries. Background heartbeat uses WorkManager with minimum 15-minute interval (Android constraint). |
| **Effort** | 4h |
| **Risk** | Low |

### M7.2 — Implement presence resolver

| Field | Value |
|---|---|
| **ID** | `M7.2` |
| **Description** | Implement `daemon/src/presence/resolver.ts` — pure function that computes presence from device heartbeats + time of day. Input: list of devices with `last_seen_at`, current time, quiet hours config, manual override. Output: `Presence` object with status, explanation, next_available_at. Algorithm follows the priority-ordered rules from PRESENCE_ENGINE.md (manual override → device check → quiet hours → available). |
| **Dependencies** | `M7.1`, `M3.4` |
| **Acceptance Criteria** | `resolver.resolve(devices, config)` returns correct status for each scenario: device active <5min → available. Device active >5min → idle. Device active >30min → away. No devices → offline. Current time in quiet hours → sleeping (if no device active). Manual override overrides everything. |
| **Effort** | 4h |
| **Risk** | Low |

### M7.3 — Add presence endpoint and MCP resource

| Field | Value |
|---|---|
| **ID** | `M7.3` |
| **Description** | Wire presence resolver to HTTP endpoint `GET /api/presence` and MCP resource `agentcall://presence`. MCP resource returns presence with privacy filtering based on agent trust level (trust_level=1 sees binary; trust_level=2 sees full; trust_level=3 sees explanation). |
| **Dependencies** | `M7.2`, `M2.2` (auth context for privacy filtering) |
| **Acceptance Criteria** | `GET /api/presence` returns full presence object. MCP resource `agentcall://presence` returns partial view for limited-trust agents. Privacy filtering is correct for each trust level. Presence changes are reflected without daemon restart. |
| **Effort** | 3h |
| **Risk** | Low |

### M7.4 — Implement manual presence override in Android

| Field | Value |
|---|---|
| **ID** | `M7.4` |
| **Description** | Add presence override UI to Android settings. User can set status (Available, Busy, Away, DND) with optional duration (30m, 1h, 2h, custom, until changed). Tapping sends `PUT /api/presence` with override. When override expires, daemon removes it automatically. Show current presence status in header bar. |
| **Dependencies** | `M7.3`, `M4.5` |
| **Acceptance Criteria** | Presence override UI accessible from settings. Setting "DND for 1 hour" sends PUT with expires_at. After 1 hour, daemon returns to automatic presence. Header bar shows current presence status with icon. Status updates without app restart. |
| **Effort** | 3h |
| **Risk** | Low |

---

## M8 — Discovery (~12h)

### M8.1 — Implement mDNS advertisement in daemon

| Field | Value |
|---|---|
| **ID** | `M8.1` |
| **Description** | Add mDNS (Bonjour/Avahi) advertisement to the daemon using `multicast-dns` npm package. Daemon advertises `_agentcall._tcp` service on port 7377 with TXT record containing version, hostname, and a randomly generated pairing code. Refresh advertisement every 60 seconds. |
| **Dependencies** | `M1.4` |
| **Acceptance Criteria** | `dns-sd -B _agentcall._tcp` discovers the daemon within 5 seconds of startup. TXT record contains `version=2.0.0`, `hostname=my-pc`, `pairing_code=ABC123`. Advertisement stops when daemon shuts down. No error if mDNS is not available on the network. |
| **Effort** | 4h |
| **Risk** | Medium — mDNS availability varies by OS/network |

### M8.2 — Implement daemon discovery in Android

| Field | Value |
|---|---|
| **ID** | `M8.2` |
| **Description** | Add mDNS discovery to Android app using `javax.jmdns` or `NsdManager`. On first launch (or when no daemon is configured), scan the local network for `_agentcall._tcp` services. Show found daemons in a list. User taps one to connect. Store the selected daemon address for future connections. |
| **Dependencies** | `M8.1`, `M4.2` |
| **Acceptance Criteria** | Discovery screen appears on first launch. Scanning finds the daemon within 10 seconds on local network. Tapping a discovered daemon connects and shows paired state. Discovery works on WiFi (not mobile data). Manual IP entry also available as fallback. |
| **Effort** | 5h |
| **Risk** | Medium — NsdManager behavior varies by Android version |

### M8.3 — Implement QR code pairing fallback

| Field | Value |
|---|---|
| **ID** | `M8.3` |
| **Description** | Add QR code fallback for when mDNS is not available. Daemon prints a QR code to stdout on startup (or serves it at `GET /pair`). QR encodes `agentcall://pair?host=IP&port=7377&code=ABC123`. Android app has "Scan QR code" button that opens camera, scans, and configures the daemon address. |
| **Dependencies** | `M8.2` |
| **Acceptance Criteria** | Daemon logs QR code as ASCII art on startup at info level. Scanning QR code from Android app configures daemon address. Pairing code from QR is sent as verification. QR code workflow works end-to-end on local network. |
| **Effort** | 3h |
| **Risk** | Low |

---

## M9 — Session History (~12h)

### M9.1 — Implement session history screen

| Field | Value |
|---|---|
| **ID** | `M9.1` |
| **Description** | Create `HistoryScreen` — shows all past sessions (completed, cancelled, expired). Grouped by date (Today, Yesterday, This Week, Earlier). Each item shows agent name, capability icon, context preview, timestamp, status. Tapping opens SessionDetailScreen (read-only for completed sessions). |
| **Dependencies** | `M4.5`, `M4.4` (SessionDetailScreen) |
| **Acceptance Criteria** | History screen loads paginated sessions (20 at a time, scroll to load more). Sessions grouped by date. Completed sessions open in read-only mode (no reply input). Timestamps relative ("2m ago", "Yesterday"). Search bar filters by context text. |
| **Effort** | 5h |
| **Risk** | Low |

### M9.2 — Implement session search on daemon

| Field | Value |
|---|---|
| **ID** | `M9.2` |
| **Description** | Add search endpoint: `GET /api/sessions/search?q=deployment&status=completed&limit=20`. Search across session context and message content using SQLite `LIKE` or FTS5 full-text search. Return matching sessions with relevance snippets. FTS5 requires creating a virtual table and keeping it in sync with sessions/messages. |
| **Dependencies** | `M3.3` |
| **Acceptance Criteria** | `GET /api/sessions/search?q=deployment` returns sessions where context or messages contain "deployment". Results include snippet with matching text highlighted. FTS5 virtual table stays in sync (trigger-based). Search is case-insensitive. |
| **Effort** | 4h |
| **Risk** | Medium — FTS5 sync with main tables adds write complexity |

### M9.3 — Add session export

| Field | Value |
|---|---|
| **ID** | `M9.3` |
| **Description** | Add export endpoint: `GET /api/sessions/{id}/export?format=json`. Returns complete session with all messages as downloadable JSON. Add "Export" button to SessionDetailScreen (shares JSON via Android share sheet). Add bulk export: `GET /api/sessions/export?since=2026-01-01` returns all sessions as JSON array. |
| **Dependencies** | `M9.1` |
| **Acceptance Criteria** | Export endpoint returns valid JSON with all session data. Android "Export" button triggers share sheet with JSON file. Bulk export returns array. Export includes metadata (daemon version, export timestamp). |
| **Effort** | 3h |
| **Risk** | Low |

---

## M10 — Hardening (~20h)

### M10.1 — Add rate limiting

| Field | Value |
|---|---|
| **ID** | `M10.1` |
| **Description** | Implement `daemon/src/middleware/rate-limit.ts`. Per-agent rate limiter using in-memory sliding window (no SQLite write). Track `request_communication` and `send_message` calls per agent per hour. Configurable limit (default 50/hour). Return `RATE_LIMITED` error when exceeded. Rate limits reset on daemon restart (intentional — no persistent storage needed). |
| **Dependencies** | `M2.3` |
| **Acceptance Criteria** | Rate limiter tracks calls per agent. Agent exceeding 50 calls/hour gets `RATE_LIMITED` error. Rate limit is configurable per agent via policy. `get_session` and `cancel_session` are not rate limited. Restart resets counters. |
| **Effort** | 3h |
| **Risk** | Low |

### M10.2 — Write integration tests for critical path

| Field | Value |
|---|---|
| **ID** | `M10.2` |
| **Description** | Write Vitest integration tests for the full daemon flow: 1) register agent → returns key, 2) request_communication with valid key → returns session, 3) send_message to session → stores message, 4) get_session → returns session with message, 5) cancel_session → marks cancelled, 6) list_sessions → returns list. Use in-memory SQLite for tests. Mock FCM push. |
| **Dependencies** | `M2.6`, `M3.1`, `M0.6` |
| **Acceptance Criteria** | All 6 flows are tested. Tests use in-memory SQLite (no file I/O). Tests are hermetic (no network calls). Each test clears state between runs. `npm test` passes with >90% coverage on daemon/src/mcp/ and daemon/src/storage/. |
| **Effort** | 5h |
| **Risk** | Low |

### M10.3 — Add crash recovery test

| Field | Value |
|---|---|
| **ID** | `M10.3` |
| **Description** | Write a test that simulates daemon crash: 1) create sessions in SQLite, 2) verify sessions survive process restart (re-init storage, query sessions), 3) verify in-flight sessions are detected and recovery log is written. Recommend crashing via `process.kill(process.pid, 'SIGKILL')` from a child process in the test. |
| **Dependencies** | `M3.1`, `M1.3` |
| **Acceptance Criteria** | Test creates 3 sessions, kills daemon, restarts daemon, verifies all 3 sessions still in SQLite with correct states. Recovery log entry is written on restart documenting recovered sessions. Test passes consistently (not flaky). |
| **Effort** | 4h |
| **Risk** | Low |

### M10.4 — Add Prometheus metrics endpoint

| Field | Value |
|---|---|
| **ID** | `M10.4` |
| **Description** | Add `GET /metrics` endpoint exposing Prometheus metrics: `agentcall_sessions_total` (counter), `agentcall_sessions_active` (gauge), `agentcall_messages_delivered` (counter), `agentcall_messages_failed` (counter), `agentcall_delivery_latency_ms` (histogram), `agentcall_devices_registered` (gauge). Use `prom-client` npm package. Include default Node.js metrics (event loop lag, memory). |
| **Dependencies** | `M1.4` |
| **Acceptance Criteria** | `GET /metrics` returns Prometheus-formatted text. All metrics have HELP and TYPE comments. Delivery latency histogram has buckets [50, 100, 250, 500, 1000, 5000]. Metrics reflect real daemon state (not hardcoded). |
| **Effort** | 4h |
| **Risk** | Low |

### M10.5 — Write systemd service file + installation script

| Field | Value |
|---|---|
| **ID** | `M10.5` |
| **Description** | Create `daemon/contrib/systemd/agentcall.service` systemd user service file. Create `scripts/install.sh` that: creates config directory, creates data directory, installs the service file, enables and starts the service. Create `scripts/uninstall.sh`. Document in `daemon/README.md`. |
| **Dependencies** | `M1.4` |
| **Acceptance Criteria** | `install.sh` sets up daemon as user service. `systemctl --user start agentcall` starts daemon. `systemctl --user enable agentcall` enables on boot. Daemon logs to journald. `uninstall.sh` stops and removes service. README documents the installation. |
| **Effort** | 4h |
| **Risk** | Low |

---

## Summary

| Milestone | Tasks | Hours | Risk Profile |
|---|---|---|---|
| M0 — Architecture Freeze | 6 | ~18h | All low risk |
| M1 — Daemon Core | 5 | ~18h | 1 medium (lifecycle) |
| M2 — MCP Transport + Tools | 6 | ~24h | 1 medium (MCP parsing), 1 medium (protocol compliance) |
| M3 — SQLite Storage | 4 | ~16h | 1 medium (JOIN performance) |
| M4 — Android Pairing | 6 | ~28h | 1 medium (capability UI), 1 medium (WebSocket reconnect) |
| M5 — Push Notifications | 5 | ~22h | 1 medium (FCM setup), 1 high (end-to-end integration) |
| M6 — Offline Queue | 4 | ~16h | 1 medium (WorkManager timing) |
| M7 — Presence | 4 | ~14h | All low risk |
| M8 — Discovery | 3 | ~12h | 1 medium (mDNS), 1 medium (NsdManager) |
| M9 — Session History | 3 | ~12h | 1 medium (FTS5 sync) |
| M10 — Hardening | 5 | ~20h | All low risk |
| **Total** | **51** | **~200h** | **3 high, 10 medium, 38 low** |

### Demo Milestone (End-to-End)

The demo works after **M5.5 — Wire up end-to-end push delivery**. That's
~33 tasks and ~130 hours (~6 weeks solo).

Demo flow confirmed working after M5.5:

```
Claude Desktop (MCP client)
  → request_communication(capability: "message", context: "Hello from Claude!")
  → Daemon MCP server (M2.4)
  → Session Engine creates session in SQLite (M3.3)
  → Delivery Router queries Device Registry (M3.4)
  → FCM Push Gateway sends notification (M5.1)
  → Android PushService receives (M5.2)
  → Notification appears on phone (M5.2)
  → User taps → SessionDetailScreen (M4.4)
  → User types response → DaemonClient HTTP POST (M4.2)
  → Daemon stores response in SQLite (M3.3)
  → Claude calls get_session() → sees response (M2.5)
  → Demo complete ✓
```

### Priority for Solo Developer

```
Week 1: M0 (architecture freeze) + M1 (daemon skeleton)
Week 2: M3 (SQLite storage)
Week 3: M2 (MCP transport + tools)
Week 4: M4 (Android app — can start after M3.3)
Week 5: M4 continued + M5.1 (FCM setup, early)
Week 6: M5 (push notifications wired end-to-end) ← DEMO READY
Week 7: M6 (offline queue)
Week 8: M7 (presence) + M8 (discovery)
Week 9: M9 (history) + M10 (hardening)
```
