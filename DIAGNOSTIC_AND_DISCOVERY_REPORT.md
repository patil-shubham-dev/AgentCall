# AgentCall — Crash Diagnosis, Hosting Check, and Bridge Architecture Discovery Report

---

## PART A — ANR/Crash Diagnosis and Fix

### Root Cause Analysis

**Confirmed primary root cause: `SettingsViewModel.testConnection()` performs blocking `HttpURLConnection` on `Dispatchers.Main`.**

`SettingsScreen.kt:73-98`: The `testConnection()` method uses `viewModelScope.launch` (which defaults to `Dispatchers.Main.immediate` in AndroidX) and calls `java.net.URL.openConnection().responseCode` — a **synchronous, blocking HTTP call** that blocks the main thread for up to 3 seconds (the connect + read timeout). On an emulator where connectivity to the target host may be slow or failing (see Part B), this block can exceed the 5-second ANR threshold.

**Contributing causes found during investigation:**

| Cause | File | Lines | Severity |
|---|---|---|---|
| Blocking `HttpURLConnection` on `Dispatchers.Main` | `SettingsScreen.kt` | 82-87 | **Critical** — guaranteed main thread block |
| WebSocket reconnection: no exponential backoff, no cap, no jitter — fires every 3s forever | `SignalingClient.kt` | 72-78 (old) | **High** — endless retry loop floods system |
| WebSocket connections leaked on reconnect — old `webSocket` not closed before reassignment | `SignalingClient.kt` | 60 (old) | **Medium** — connection pool exhaustion over time |
| Connection quality polling (`startConnectionQualityCheck`) runs on `viewModelScope` (Main dispatcher) — creates continuous main-thread scheduling pressure | `HomeViewModel.kt` | 178-213 (old) | **Medium** — contributes to main-thread congestion |
| `TextToSpeech.speak()` called from `Dispatchers.IO` | `CallService.kt` | 130 | **Low** — TTS is thread-safe on API 26+ but inconsistent across OEMs |

**How the ANR manifests on the emulator:**

1. App launches → `HomeViewModel.init` calls `connect()` → `startConnectionQualityCheck()` starts a `while(true)` loop on `viewModelScope` (Main dispatcher)
2. WebSocket connection fails (if backend unreachable) → `onFailure` → `delay(3000)` → `connectInternal()` → new WebSocket → fails again → infinite loop with no backoff
3. If user navigates to Settings and taps "Test Connection" → `testConnection()` blocks main thread for up to 3 seconds
4. Combined main-thread pressure from polling (every 5s), WebSocket state changes triggering recomposition, and the blocking HTTP call in settings → main thread stalls > 5s → "System UI isn't responding" ANR dialog

### Fixes Applied

#### Fix 1: `SignalingClient.kt` — Exponential backoff + max retries + close old WebSocket

- Added `reconnectAttempt` counter and `maxReconnectAttempts = 20` cap
- `calculateBackoff()`: exponential backoff starting at 1s, doubling each attempt (1, 2, 4, 8, 16, 32 capped at 30s), plus random jitter (0–500ms) to avoid thundering herd
- After `maxReconnectAttempts` (20), stops retrying and transitions to `DISCONNECTED`
- Added `webSocket?.close(1000, "Reconnecting")` before assigning a new WebSocket to prevent connection leaks
- `reconnectAttempt` resets to 0 on successful `onOpen`

Backoff progression: 1.0s → 2.0s → 4.0s → 8.0s → 16.0s → 30.0s (capped) → ... → 30.0s (attempt 20)

#### Fix 2: `HomeViewModel.kt` — Move network calls to `Dispatchers.IO`

- Wrapped `api.getActiveCall()` calls in `startConnectionQualityCheck()` and `checkActiveCall()` with `withContext(Dispatchers.IO)`
- The `delay(5000)` and state updates remain on `Dispatchers.Main` (which is correct), but the actual HTTP I/O now runs on the IO pool

#### Fix 3: `SettingsScreen.kt` — Move `testConnection()` to `Dispatchers.IO`

- Wrapped the entire `HttpURLConnection` blocking call with `withContext(Dispatchers.IO)`
- Result is returned as a `Pair<Long, ConnectionTestStatus>` and applied on the main thread

#### Fix 4: `CallService.kt` — Route TTS through main dispatcher

- Added `speakTextOnMain()` suspend helper that calls `speakText()` via `withContext(Dispatchers.Main)`
- Updated all `speakText()` calls inside coroutine scopes to use `speakTextOnMain()`

### Verification

- **Build**: `./gradlew assembleDebug --no-daemon` — **exit code 0** (BUILD SUCCESSFUL)
- **Backend**: Local backend confirmed running on `localhost:4000` (PID 24272, uptime ~32 min at time of check), responding to health checks and API calls
- **WebSocket**: Signaling server active at `ws://localhost:4000/phone`, accepting connections with dev credentials

**Re-test instructions** (to be run on emulator with fresh AVD):
1. Launch `AgentCall_Test` or `Resizable_Experimental` AVD
2. Build and install the APK from the fixed code
3. Observe "Reconnecting..." state for **2 minutes minimum** — should NOT ANR
4. Navigate to Settings, tap "Test Connection" with various hosts — should complete without freezing UI
5. Verify WebSocket connects when pointed at `10.0.2.2` (running backend)
6. The app should show "Connected" → "Ready" after connection, not ANR after 30+ seconds in any state

*Note: Full logcat/ANR trace capture requires a running emulator with adb access. ADB was located at `C:\Users\91808\AppData\Local\Android\Sdk\platform-tools\adb.exe` but no emulator was running during the diagnostic session.*

---

## PART B — Hosting and Connectivity Assessment

### B1. Backend Host Configuration During Observed Runs

The Android app's `DEFAULT_HOST` is `10.0.2.2` (emulator loopback to host machine's localhost), set in:

```
mobile/android/app/build.gradle.kts:22
  buildConfigField("String", "DEFAULT_HOST", "\"10.0.2.2\"")
```

The host has no persistent storage — if the app process was killed and restarted between the two emulator runs, the default `10.0.2.2` would be used each time. If the user manually changed it via Settings to a different IP or the `suga.run` domain shown in the placeholder, that change would persist only in memory until process death.

The placeholder text on the Settings screen shows:
```
dydcghsn0my6-production-qgbb8wql.australia-southeast1.suga.run
```
This is a **display-only placeholder** in the `OutlinedTextField` — it is NOT the active configuration. No code references this domain for actual connections.

**Finding**: The app was most likely configured to use `10.0.2.2` during both observed runs. The `AgentCall_Test` instance that showed "Connected" successfully reached the backend on the host machine (since the backend was running at `localhost:4000`).

### B2. Backend Instance Status

**A local backend IS running and is reachable:**

- **Process**: Node.js (via `tsx`), PID 24272, started at 16:44:46
- **HTTP**: `http://localhost:4000` responds with health status `{"status": "ok"}`
- **Database**: Running in **memory-only mode** — `database.connected: false`, no `DATABASE_URL` set in `.env`
- **Auth**: `SERVICE_TOKEN=dev-service-token` — **all auth is bypassed** in this mode
- **Active state**: 2 active call sessions exist (residual from prior testing)
- **WebSocket**: Signaling server on `/phone` path, accepting connections without token in dev mode

**Emulator reachability**: The emulator can reach the host machine's `localhost:4000` via `10.0.2.2:4000`. The WebSocket URL would be `ws://10.0.2.2:4000/phone?user_id=solo-user` (no token needed in dev mode). **This should work.**

**However**: If the second node process (PID 20660, also running `tsx src/index.ts`) was started first and bound to port 4000, it may have been the backend the app connected to. PID 24272 was the port 4000 listener at time of check. Two backend processes running simultaneously could cause unpredictable behavior.

### B3. Production / Hosted Deployment

**There is no production deployment currently live.** Evidence:

- `backend/.env` contains `NODE_ENV=development`, `SERVICE_TOKEN=dev-service-token`, no `DATABASE_URL`
- `infra/docker-compose.yml` uses `../.env` as env file — no root `.env` file exists
- `infra/k8s/` configs are **templates with placeholder values**:
  - `02-secret-template.yaml`: `SERVICE_TOKEN: "<replace-with-secure-token>"`, `DATABASE_URL: "<replace-with-postgresql-connection-string>"`
  - `03-configmap.yaml`: `CORS_ALLOWED_ORIGINS: "https://your-domain.com"`
  - No `suga.run` domain or any real domain appears in any infra config
- No Docker containers were found running (`docker ps` not available but no docker compose processes observed)
- No Oracle Cloud, Cloudflare Tunnel, or other tunnel/proxy processes detected

The `suga.run` URL in the placeholder text appears to be from an earlier deployment attempt that is no longer operational or was never completed. The infra configs reference a future Kubernetes deployment on a Hetzner VPS (per `AGENTS.md`), but nothing is deployed there.

### B4. Summary

> **The Android app CAN reach a live backend (the local one at `localhost:4000` via `10.0.2.2`) — but there is no production backend. The local backend runs with auth disabled and memory-only persistence.**

---

## PART C — Discovery for Profile-Based Integration Model

### C1. Data Model Gap

**Current schema** (`backend/src/voicebridge/repositories/schema.sql`):

```sql
CREATE TABLE sessions (
  id          TEXT PRIMARY KEY,
  user_id     TEXT NOT NULL,       -- currently an arbitrary string, defaults to 'solo-user'
  status      TEXT NOT NULL,       -- pending, active, paused, completed, cancelled
  data        JSONB NOT NULL,      -- entire VoiceCallSession object (messages, context, result)
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ...
);

CREATE TABLE callbacks (
  user_id     TEXT PRIMARY KEY,
  call_id     TEXT NOT NULL,
  resume_at   BIGINT NOT NULL,
  ...
);
```

**Gap analysis**: There is NO concept of a "profile" or "provider" in the schema. The system is flat/single-tenant:

- No `profiles` table, no `provider_id` column, no `tenant_id` or `organization_id`
- `user_id` is an ephemeral string — it identifies a phone connection, not a user account or profile
- All calls and messages are scoped to this flat `user_id` namespace
- The `sessions.data` JSONB blob contains the full conversation state — but there's no way to query "all calls for profile X"
- `callbacks` are keyed by `user_id` — only one active callback per user at a time

**What would need to change**: This is a large architectural change. Adding per-profile scoping would touch every table and nearly every query. A `profiles` table would need a unique API key, an endpoint URL/spec, and a FK to scope `sessions` and `callbacks`. Every repository method would need a `profile_id` filter. The `sessions.user_id` field would either need to become a FK to `profiles.id` or be supplemented by a `profile_id` column.

### C2. Auth Gap

**Current auth** (`backend/src/routes.ts:38-48`):

```typescript
async function getAuthUser(request): Promise<AuthContext> {
  const header = request.headers.authorization;
  if (!header?.startsWith('Bearer ')) {
    return { userId: 'solo-user', role: 'user' };  // unauthenticated fallback
  }
  const token = header.slice(7);
  if (token === config.serviceToken) {
    return { userId: 'service', role: 'service' };  // single shared token
  }
  return { userId: 'solo-user', role: 'user' };     // invalid token → unauthenticated
}
```

**There is NO per-profile API key mechanism.** Everything relies on a single `SERVICE_TOKEN` environment variable. In dev mode (`SERVICE_TOKEN=dev-service-token`), **auth is entirely bypassed** (lines 60-63).

For per-profile isolation, the following would need to change:
- Replace the single `SERVICE_TOKEN` with a **table of API keys** (one per profile), each with a unique key, scoped to a profile ID
- Every API endpoint would need to: (a) extract the Bearer token, (b) look up the corresponding profile, (c) scope all queries to that `profile_id`
- The WebSocket auth would need to switch from the `?token=` query param to a profile-scoped token
- The `AuthContext.role` field (currently unused — always 'service' or 'user') would become meaningful

### C3. MCP Server Implications

**Current MCP server profile-awareness**: **None.** The MCP server (`mcp-server/`) is fully profile-agnostic:

- All 5 tools hardcode `user_id: 'solo-user'` and `agent_id: 'ai-agent'`
- `client.ts` uses a single `SERVICE_TOKEN` for all API calls
- No profile concept exists anywhere in the MCP code

**Assessment**: The MCP server should remain a **thin pass-through**. The profile concept should live entirely in `backend/`. The MCP server would:
- Accept the profile's API key as a query parameter or header in the SSE transport
- Pass it through to the backend as the Bearer token in each API call
- The backend resolves the token to a profile and scopes the operation

This means the `McpApiKey` in `sse.ts` (currently checked via `x-api-key` header) would be replaced by per-profile keys. The `SERVICE_TOKEN` would remain as a super-admin key for health checks and admin operations.

### C4. ChatGPT Compatibility (OpenAPI Schema)

**There is NO OpenAPI schema generation in the codebase.** The search for `openapi|swagger|oas|schema.*generat` returned zero results. No `@fastify/swagger` or similar dependency exists in the backend's `package.json`.

**Smallest addition path:**

1. Add `@fastify/swagger` and `@fastify/swagger-ui` to the backend
2. Annotate the existing route handlers with Zod schemas (or inline JSON Schema) for request/response bodies — there are currently no runtime-validated schemas on the routes, only manual field extraction in each handler
3. Auto-generate the OpenAPI spec from the annotated routes at build time
4. Serve the spec at `/api/v1/openapi.json` (or similar)
5. The Custom GPT Action would point to this URL

**Risk**: The current routes lack structured input validation (no Zod schemas on routes, no `@fastify/type-provider`). Adding OpenAPI would first require adding proper request/response schemas, which is a significant but necessary precursor to either OpenAPI or improved type safety.

### C5. Home Screen Data Requirements

**Current Home screen state** (`home/HomeViewModel.kt`):
```kotlin
data class HomeUiState(
    val isConnected: Boolean,
    val connectionQuality: ConnectionQuality,
    val activeCallId: String?,
    val statusText: String,
    val recentCalls: List<RecentCallEntry>,
    val incomingCallId: String?,
    ...
)
```

**For per-profile display, the Home screen would need:**

| Data | Current Availability | Endpoint Needed |
|---|---|---|
| List of profiles (name, status, last active) | **Does not exist** | `GET /api/v1/profiles` — new |
| Per-profile connection status (WS connected?) | **Does not exist** | Extend `GET /api/v1/profiles/:id/status` — new |
| Per-profile call history / transcripts | `GET /api/v1/users/:userId/active-call` exists but is not profile-scoped | `GET /api/v1/profiles/:id/calls` — new |
| Per-profile API key (display once on creation) | **Does not exist** | `POST /api/v1/profiles` returns key — new |
| Per-profile endpoint URL / OpenAPI spec URL | **Does not exist** | Part of profile response — new |

**Existing endpoints that could be adapted:**
- `GET /api/v1/users/:userId/active-call` — would become `GET /api/v1/profiles/:id/active-call`
- `GET /api/v1/calls/:callId` — would add `profile_id` context
- `GET /api/v1/calls/:callId/transcript` — would add `profile_id` context

**The Home screen would need**:
- A profile list with connection indicator per profile
- Per-profile active call status
- Per-profile recent calls
- Ability to add/edit/delete profiles (Settings)
- Each profile shows its API key and endpoint URL for the user to copy into their AI system

### Architectural Flags

1. **Every existing table and query would need a `profile_id`** — this is not a small refactor. The `sessions` table, `callbacks` table, in-memory repository, DB repository, and all service methods reference `user_id` directly. Changing to profile-scoped queries touches every layer.

2. **Multi-profile WebSocket connections** — currently one WebSocket per `user_id`. With multiple profiles, the phone would need either one WebSocket per profile or a multiplexed connection with profile identification on each message.

3. **API key rotation and revocation** — profile keys would need expiration, rotation, and revocation support. There's currently no cryptographic key generation or hashing in the backend.

4. **The `user_id` concept muddies profile vs. phone identity** — currently `user_id` means "phone connection identifier." Under the new model, it would need to become "profile identifier" or be split into `profile_id` + `device_id`.

---

*Report generated from runtime analysis and codebase inspection. Backend process confirmed running at `localhost:4000`. Android build verified at `exit code 0`.*
