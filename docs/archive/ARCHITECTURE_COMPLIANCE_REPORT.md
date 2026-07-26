# AgentCall — Architecture Compliance Report

> **Date:** 2026-07-26
> **Mapping:** Every source folder mapped to SYSTEM_ARCHITECTURE.md

---

## 1. Module-to-Service Mapping

### Backend (`backend/src/`)

#### `common/`
**Purpose:** Shared configuration, logging, type definitions.
**Current Responsibility:** Config loading from env, Pino logger setup, basic type exports.
**Expected Responsibility:** As-is (utilities shared across all services).
**Violations:** None.
**Dependencies:** `dotenv`, `pino`, `pino-pretty`.
**Suggested Refactor:** Extract `config.ts` validation — add post-load validation that throws on missing required vars in production.
**Priority:** Low
**Risk:** Low

#### `signaling/`
**Purpose:** WebSocket signaling server for phone connections.
**Current Responsibility:** Manages WebSocket connections at `/phone`, rate limiting, connection lifecycle, forwards events to `voicebridge/service.ts`.
**Expected Responsibility:** Part of the Communication Gateway. Should handle transport only — not call business logic.
**Violations:**
- Imports `voicebridge/service.ts` directly — couples signaling to call management. The Gateway should not know about call services.
- Module-level mutable Maps (`connectionRateLimits`, `clientRateLimits`) — should be instance-level or Redis-backed.
- No SSE support (Communication Gateway must support WebSocket + SSE + WebRTC).
**Dependencies:** `ws`, `node:http`, `common/logger`, `common/config`, `voicebridge/service`.
**Suggested Refactor:** Extract rate limiting into a service. Remove direct dependency on `voicebridge/service.ts` — signaling should emit events consumed by call services via Event Bus.
**Priority:** High
**Risk:** Medium

#### `voicebridge/`
**Purpose:** Core voice call business logic.
**Current Responsibility:** Call lifecycle (create, get, complete, cancel), phone registration, message handling, text enrichment, emotion detection, barge-in detection, callback scheduling, transcript management.
**Expected Responsibility (per SYSTEM_ARCHITECTURE.md):**
- Call Manager: manage call lifecycle only
- Callback Engine: schedule/manage callbacks only
- Transcript/History: handled by dedicated History Service
- Text enrichment/emotion/barge-in: MUST NOT EXIST (AI reasoning violation)

**Violations:**
1. **AI reasoning in communication layer** (CRITICAL): `enrichText()`, `emotionOf()`, `detectBargeIn()` perform AI work — violates PRODUCT_VISION.md.
2. **Missing service boundaries**: This single module (284 LOC + 213 LOC types) contains 4 of the 11 required services (Call Manager, Callback Engine, History, and parts of Gateway).
3. **Types mixed with runtime logic**: `types.ts` exports types AND contains all text enrichment logic. These should be separate files.
4. **Module-level mutable state**: `sessions`, `phoneConnections`, `scheduledCallbacks` are module-level Maps — no encapsulation, cannot be tested in isolation.

**Dependencies:** `node:crypto`, `ws`, `common/logger`, `common/types`, `voicebridge/types`.

**Suggested Refactor:**
1. Delete `enrichText()`, `emotionOf()`, `detectBargeIn()` — they violate the architecture philosophy and must never exist.
2. Split `service.ts` into: `CallManager`, `CallbackEngine`, `PhoneRegistry`.
3. Extract types into pure definitions; move enrichment/barge-in functions to a separate file that gets removed.
4. Wrap state in classes (not module-level Maps) for testability.
5. Add Event Bus integration — services should emit events, not call each other.

**Priority:** Critical
**Risk:** High

#### `routes.ts`
**Purpose:** HTTP route registration for Fastify.
**Current Responsibility:** Defines 10 endpoints: health, call CRUD, user text, phone registration.
**Expected Responsibility:** Route definitions only — validation, auth, and serialization should be middleware/plugins.
**Violations:**
- No schema validation on any route (Fastify schema support unused).
- Auth middleware exists but never checks authentication — purely decorative.
- Routes call `voicebridge.*` functions directly with no service facade.
- `strictRateLimit` declared but never used.

**Dependencies:** `fastify`, `common/config`, `common/logger`, `voicebridge/service`, `voicebridge/types`.

**Suggested Refactor:**
1. Add Zod schemas for every request/response.
2. Implement real auth middleware (JWT validation).
3. Create service facade layer between routes and business logic.
4. Use Fastify schema validation (`schema` property) for auto-generated OpenAPI spec.

**Priority:** High
**Risk:** Medium

#### `index.ts`
**Purpose:** Application entry point and server bootstrap.
**Current Responsibility:** Fastify setup (plugins, error handler, CORS, rate limiting, compression, helmet), server start, graceful shutdown.
**Expected Responsibility:** As-is (bootstrap only).
**Violations:**
- CSP disabled: `contentSecurityPolicy: false`, `crossOriginEmbedderPolicy: false`.
- Duplicate logger config: Fastify internal logger + app Pino logger — two log streams.
- Source maps enabled in production build.
- Hard `process.exit()` in shutdown handlers prevents request drain.

**Dependencies:** `fastify`, `@fastify/cors`, `@fastify/rate-limit`, `@fastify/compress`, `@fastify/helmet`, `node:crypto`, `common/config`, `common/logger`, `routes`, `signaling/server`, `ws`.

**Suggested Refactor:**
1. Re-enable CSP with appropriate policy.
2. Remove `process.exit()` — use server.close() with drain timeout.
3. Disable Fastify logger or align it with app logger.
4. Disable source maps in production build.

**Priority:** Medium
**Risk:** Low

---

### MCP Server (`mcp-server/src/`)

#### `index.ts`
**Purpose:** MCP server bootstrap and tool registration.
**Current Responsibility:** Creates MCP `Server` instance, registers all tools, starts transport (stdio, SSE, or both based on config).
**Expected Responsibility:** As-is.
**Violations:** None structural.
**Issues:**
- No graceful shutdown for HTTP/SSE transport.
- Transport selection logic is in entry point — fine at this scale.
**Dependencies:** `@modelcontextprotocol/sdk`, `common/logger`, `tools`, `sse`, `config`.
**Suggested Refactor:** Add SIGTERM/SIGINT handlers.
**Priority:** Medium
**Risk:** Low

#### `tools.ts`
**Purpose:** MCP tool definitions and handler implementations.
**Current Responsibility:** Defines 5 tool schemas, handles tool execution by calling `client.ts` for each.
**Expected Responsibility:** 8 tools per API_SPEC.md.
**Violations:**
- 3 of 8 required tools missing (`query_presence`, `resume_task`, `notify_completion`).
- Zero input validation on any handler — all arguments cast with `as`.
- Non-null assertions on `result.data!` — type-unsafe.
- Error format does not match API_SPEC.md — no `correlationId`.

**Dependencies:** `client`, `logger`.
**Suggested Refactor:**
1. Implement `query_presence`, `resume_task`, `notify_completion`.
2. Add Zod validation at handler entry for all tool arguments.
3. Switch `ApiResponse<T>` to discriminated union.
4. Add `correlationId` to error responses.

**Priority:** Critical
**Risk:** High

#### `sse.ts`
**Purpose:** HTTP server providing SSE transport for MCP, with CORS handling and API key auth.
**Current Responsibility:** Creates HTTP server, applies CORS headers, validates API key on non-health routes, creates `StreamableHTTPServerTransport` for MCP communication.
**Expected Responsibility:** SSE transport for MCP, plus eventual OpenAPI endpoint.
**Violations:**
- Hardcoded development CORS origins (`localhost:3000`, `:3001`, `:4000`) not gated by NODE_ENV.
- CORS fallback substitutes first allowed origin for unknown origins — too permissive.
- `/health` endpoint reveals internal structure (transport type, endpoints, auth_required).
- No rate limiting.
- No TLS support (expected to be behind reverse proxy but not enforced).

**Dependencies:** `node:http`, `node:crypto`, `@modelcontextprotocol/sdk`, `logger`, `config`.
**Suggested Refactor:**
1. Gate CORS origins by NODE_ENV.
2. Restrict CORS fallback to reject unknown origins or return 403.
3. Minimize health endpoint response.
4. Add rate limiting.
**Priority:** High
**Risk:** Medium

#### `client.ts`
**Purpose:** HTTP client for backend REST API communication.
**Current Responsibility:** Makes fetch() calls to backend API with service token auth.
**Expected Responsibility:** As-is (the API client for MCP-to-backend communication).
**Violations:**
- No timeout on HTTP requests.
- No connection reuse (no HTTP agent/keepalive).
- Error handling is basic — catches everything and returns generic error.
**Dependencies:** `config`, `logger`.
**Suggested Refactor:**
1. Add request timeout.
2. Add HTTP keepalive (Node.js `http.Agent` with `keepAlive: true`).
3. Improve error categorization (network vs API vs auth errors).
**Priority:** Medium
**Risk:** Low

#### `config.ts`
**Purpose:** Environment configuration with defaults.
**Current Responsibility:** Reads env vars with fallbacks, exports config object.
**Expected Responsibility:** As-is.
**Violations:**
- `env()` returns empty string for missing required vars.
- `parseInt` without NaN check on port.
- Default `SERVICE_TOKEN` is `dev-service-token`.

**Dependencies:** `dotenv/config`.
**Suggested Refactor:**
1. Add post-load config validation.
2. Add NaN check on port parse.
3. Remove `dev-service-token` default or make it error in production.
**Priority:** Medium
**Risk:** Low

#### `logger.ts`
**Purpose:** Pino logger instance.
**Current Responsibility:** Creates and exports configured Pino logger.
**Expected Responsibility:** As-is.
**Violations:** None.
**Dependencies:** `pino`, `config`.
**Suggested Refactor:** None.
**Priority:** None
**Risk:** None

---

### Mobile — Android (`mobile/android/app/src/main/java/com/agentcall/app/`)

#### `di/AppModule.kt`
**Purpose:** Hilt dependency injection module.
**Current Responsibility:** Provides `ApiClient`, `ApiService`, `TokenManager`, `SignalingClient` as singletons.
**Expected Responsibility:** DI module.
**Violations:** None.
**Dependencies:** Hilt.
**Suggested Refactor:** Add scoped providers once services are decomposed (scoped to call, user, etc.).
**Priority:** Low
**Risk:** Low

#### `data/api/`
**Purpose:** API client, service interface, token management.
**Current Responsibility:**
- `ApiClient.kt` — OkHttp client with logging, base URL config, HTTP/WS URL builders.
- `ApiService.kt` — Retrofit service interface for backend API calls.
- `TokenManager.kt` — EncryptedSharedPreferences for token storage.
**Expected Responsibility:** Network layer.
**Violations:**
- `DEFAULT_HOST` is hardcoded production URL.
- Logging interceptor is always HEADERS level, even in release.
- `TokenManager` wired but never populated — `accessToken` always null.

**Dependencies:** Retrofit, OkHttp, kotlinx-serialization, security-crypto.
**Suggested Refactor:**
1. Move `DEFAULT_HOST` to BuildConfig or environment config.
2. Gate logging interceptor with `BuildConfig.DEBUG`.
3. Remove or properly integrate TokenManager with auth flow.
**Priority:** High (hardcoded URL) / Medium (logging) / Medium (TokenManager dead)
**Risk:** High

#### `data/model/Models.kt`
**Purpose:** Serializable data models for API communication.
**Current Responsibility:** Defines `VoiceBridgeEvent`, `CallData`, `TranscriptMessage`, `ApiResponse` serializable classes.
**Expected Responsibility:** Shared models.
**Violations:** None significant.
**Dependencies:** kotlinx-serialization.
**Suggested Refactor:** None.
**Priority:** None
**Risk:** None

#### `home/`
**Purpose:** Home screen with connection state, incoming calls, recent calls.
**Current Responsibility:**
- `HomeScreen.kt` — Compose UI for home (connection state display, call list, incoming call notification).
- `HomeViewModel.kt` — State management for home (connection state, recent calls, incoming call handling).
**Expected Responsibility:** Home screen per PRODUCT_VISION.md (online status, connected providers, connected devices, active call, recent notifications).
**Violations:**
- No provider list display (expected per PRODUCT_VISION.md).
- No presence indicators (expected per PRODUCT_VISION.md).
- No device management UI.
- Call history is in-memory only, not persisted.
- Reconnection logic uses infinite recursion (unbounded).
- `clearActiveCall()` defined but never called.

**Dependencies:** `data/api`, `data/model`, `call/SignalingClient`.
**Suggested Refactor:**
1. Add provider list with connection status.
2. Add presence indicators.
3. Implement persistent call history (once backend supports it).
4. Fix reconnection with exponential backoff and max retries.
5. Remove dead code (`clearActiveCall`).
**Priority:** Medium
**Risk:** Low

#### `call/`
**Purpose:** All call-related functionality (service, UI, signaling).
**Current Responsibility:**
- `CallService.kt` (751 LOC, 15+ responsibilities): TTS, STT, barge-in, command parsing, WebSocket events, HTTP API calls, notifications, wake lock management.
- `CallActivity.kt` (534 LOC): Active call Compose UI (transcript, controls, waveform, emotion display).
- `IncomingCallActivity.kt` (414 LOC): Incoming call UI (ring animation, answer/decline/callback, priority badge).
- `CallViewModel.kt` (197 LOC): State management for active call (messages, emotions, typing indicator, audio state).
- `CallEventBus.kt` (25 LOC): SharedFlow event bus for CallService→CallViewModel communication.
- `SignalingClient.kt` (163 LOC): WebSocket client for VoiceBridge protocol.

**Expected Responsibility:**
- Call Service: manage communication session only (no TTS/STT).
- Call UI: conversation display and controls.
- Signaling: transport only.
- TTS, STT, Barge-in: separate modules.

**Violations:**
- **God object:** `CallService.kt` has 15+ responsibilities — worst violation in the codebase.
- **Command pattern classification** (lines 75-106): AI reasoning in the mobile app — `CommandPattern` enum with `INQUIRY`, `DECISION`, `ACKNOWLEDGMENT`, `NEGATIVE_RESPONSE`, `CLARIFICATION_REQUEST`, `CALLBACK_REQUEST`, `EMERGENCY` — all AI intent classification, NOT communication.
- **TTS emotion adjustment** `adjustTtsForEmotion()`: AgentCall must not modify AI output.
- **Filler word insertion** (lines 246-251): "um", "uh", "hmm" — AgentCall must not enrich AI output.
- **Breathing pauses** (lines 256-264): AgentCall must not modify conversation flow.

**Dependencies:** Hilt, Compose, AndroidX, OkHttp, coroutines.
**Suggested Refactor:**
1. Decompose `CallService.kt` into: `TtsManager`, `SttManager`, `BargeInDetector`, `CommandRouter`, `CallSession`, `NotificationHelper`.
2. Remove command pattern classification (AI reasoning).
3. Remove filler words, breathing pauses, emotion adjustment (AI output enrichment).
4. Add proper lifecycle management to `AudioRecord` barge-in.
5. Add WebSocket heartbeat (remove 5s HTTP polling).
6. Fix `CallViewModel` dead methods or remove them.
**Priority:** Critical
**Risk:** High

#### `settings/SettingsScreen.kt`
**Purpose:** Settings screen with server configuration.
**Current Responsibility:** Server host config, connection test, URL display, connect/reset.
**Expected Responsibility:** Settings per PRODUCT_VISION.md (voice, notifications, retry, callback, presence, theme, permissions, privacy, logs, developer mode).
**Violations:**
- Only server configuration is implemented. All other settings (voice, notifications, theme, etc.) are missing.
- `onReconnect` parameter defined with default `{}` but never invoked — dead parameter.

**Dependencies:** Compose, `data/api/ApiClient`.
**Suggested Refactor:** Add remaining settings screens per PRODUCT_VISION.md.
**Priority:** Low
**Risk:** Low

#### `ui/theme/`
**Purpose:** Material 3 theme definitions.
**Current Responsibility:** Color palette, typography, theme composables.
**Expected Responsibility:** Theme.
**Violations:** None significant.
**Suggested Refactor:** None.
**Priority:** None
**Risk:** None

---

### iOS Archive (`mobile/ios-archived/AgentCall/AgentCall/`)

#### `Auth/`
**Purpose:** Login/authentication UI and state.
**Current Responsibility:** `AuthView.swift` (168 LOC), `AuthViewModel.swift` (36 LOC) — login flow.
**Violations:** None structural.
**Issues:** Hardcoded example.com URLs. No token refresh logic observed.

#### `Call/`
**Purpose:** Call service, WebRTC, signaling, call UI.
**Current Responsibility:** Full WebRTC peer connection (vs Android's non-WebRTC approach).
**Violations:** Same AI reasoning concerns as Android but less pronounced — iOS does not have the command classification or enrichment code.

#### `Push/PushHandler.swift`
**Purpose:** Push notification handling and VoIP push token management.
**Violations:** VoIP push tokens stored in UserDefaults, not Keychain.

---

### Infrastructure (`infra/`)

#### `docker-compose.yml`
**Purpose:** Service orchestration for deployment.
**Current Responsibility:** Defines `backend-api`, `mcp-server`, `caddy` services.
**Expected Responsibility:** Full stack including PostgreSQL, Redis, coturn.
**Violations:** Missing 3 of 6 expected services. No network isolation. No resource limits.
**Priority:** Critical
**Risk:** High

#### `Caddyfile`
**Purpose:** Reverse proxy configuration.
**Current Responsibility:** Routes, TLS (self-signed), security headers.
**Expected Responsibility:** Production TLS (Let's Encrypt), rate limiting, load balancing.
**Violations:** `tls internal` uses self-signed cert with no production certificate provisioning.
**Priority:** Medium
**Risk:** Medium

#### `coturn/turnserver.conf`
**Purpose:** TURN/STUN server configuration for WebRTC.
**Current Responsibility:** TURN server with static auth.
**Expected Responsibility:** TURN + STUN for WebRTC.
**Violations:**
- `no-stun` means STUN is disabled — WebRTC needs it for NAT traversal.
- `listening-ip=0.0.0.0` binds all interfaces.
- No `min-port`/`max-port` relay range.
- Logs to file, not stdout.
**Priority:** Medium
**Risk:** Medium

---

## 2. Missing Services

| Service | SYSTEM_ARCHITECTURE.md | Status | Impact |
|---------|----------------------|--------|--------|
| Authentication Service | Line 88 | ❌ Not implemented | No security |
| Provider Registry | Line 89 | ❌ Not implemented | No provider abstraction |
| Session Manager | Line 90 | ❌ Not implemented | No session lifecycle |
| Presence Engine | Line 92 | ❌ Not implemented | No presence info |
| Notification Engine | Line 93 | ❌ Not implemented | No push/notifications |
| Device Router | Line 95 | ❌ Not implemented | No device management |
| History Service | Line 96 | ❌ Not implemented | No persistence |
| Communication Gateway | Line 97 | ❌ Not implemented | No unified transport |
| Event Bus | Line 98 | ❌ Not implemented | No event-driven architecture |

## 3. Extra Services

| Service | Location | Why Extra | Action |
|---------|----------|-----------|--------|
| Text Enrichment | `voicebridge/types.ts:107-172` | Violates PRODUCT_VISION.md philosophy | Remove |
| Emotion Detection | `voicebridge/types.ts:117-145` | AI reasoning in communication layer | Remove |
| Barge-in Classification | `voicebridge/types.ts:184-213` | AI reasoning in communication layer | Remove |
| Command Pattern Classification | `CallService.kt:75-106` | AI reasoning in mobile app | Remove |

## 4. Wrong Dependencies

| From | To | Why Wrong | Fix |
|------|----|-----------|-----|
| `signaling/server.ts` | `voicebridge/service.ts` | Signaling should not depend on call business logic | Use Event Bus |
| `voicebridge/service.ts` | `ws` (WebSocket) | Service should not know about transport | Inject via callback/Event Bus |
| `routes.ts` | `voicebridge/service.ts` (direct) | Routes should use a service facade | Add service facade layer |

## 5. Circular Dependencies

No circular imports detected in TypeScript code (verified by clean DAG in both backend and MCP).

## 6. God Classes

| Class/File | Lines | Responsibilities | Normalized Count |
|------------|-------|------------------|------------------|
| `CallService.kt` | 751 | TTS, STT, barge-in, command classification, WebSocket handling, HTTP API calls, notifications, wake lock management | 8+ |
| `voicebridge/service.ts` | 284 | Call lifecycle, phone registration, message handling, callback scheduling, transcript, notification | 5 |
| `voicebridge/types.ts` | 213 | Type definitions + enrichment + emotion + barge-in + filler words | 4 |
| `HomeScreen.kt` | 614 | Connection state, call list, incoming call, animation, skeleton loading | 5 |
| `CallActivity.kt` | 534 | Transcript, controls, waveform, emotion display, all in one file | 4 |

## 7. Large Files (Top 5 by LOC)

| File | LOC | Assessment |
|------|-----|------------|
| `CallService.kt` | 751 | Must decompose. Top refactor target. |
| `HomeScreen.kt` | 614 | Large but typical for Compose UI with animation. Acceptable. |
| `CallActivity.kt` | 534 | Should extract composable components. |
| `SettingsScreen.kt` | 502 | Large for a settings screen. Should extract sections. |
| `IncomingCallActivity.kt` | 414 | Acceptable for incoming call UI with animation. |

## 8. Duplicated Logic

| Logic | Locations | Impact |
|-------|-----------|--------|
| Emotion-to-color/emoji/gradient maps | `CallActivity.kt:75-97`, `CallService.kt:646-660`, `CallViewModel.kt:179-185` | Maintenance hazard — 3 places to update per emotion |
| Backend API URL construction | `backend/config.ts`, `mcp-server/config.ts`, `android/ApiClient.kt` | Different logic in each — drift risk |
| WebSocket message envelope | `backend/signaling/server.ts`, `android/SignalingClient.kt`, `ios/SignalingClient.swift` | Different envelope formats — integration risk |

## 9. Wrong Abstractions

| Abstraction | Problem | Correct Abstraction |
|-------------|---------|---------------------|
| `voicebridge/types.ts` as type + logic file | Mixes type definitions with runtime functions | Split into `types.ts` (pure types) and separate service files |
| `voicebridge/service.ts` single module | Handles 5 distinct service concerns | 5 separate files: `CallManager`, `CallbackEngine`, `PhoneRegistry`, etc. |
| `ApiResponse<T>` with all optional fields | Not type-safe — allows `{data: undefined, error: undefined}` | Discriminated union `{data: T} | {error: string, message: string}` |
| `CallService.kt` as foreground service wrapper | Mixes Android lifecycle with all call logic | Separate `CallService` (thin lifecycle wrapper) from `CallManager`, `TtsManager`, etc. |
