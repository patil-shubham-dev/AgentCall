# AgentCall — Technical Debt Report

> **Date:** 2026-07-26
> **Total Estimated Debt:** ~$96,000 (approx. 24 person-weeks)
> **Categories:** 10

---

## Debt Summary

| Category | Items | Est. Effort | Est. Cost | Priority |
|----------|-------|-------------|-----------|----------|
| Architecture | 8 | 7 weeks | $28,000 | Critical |
| Security | 7 | 2 weeks | $8,000 | Critical |
| Infrastructure | 6 | 2 weeks | $8,000 | High |
| Testing | 5 | 4 weeks | $16,000 | Critical |
| Documentation | 4 | 1 week | $4,000 | Medium |
| Code | 12 | 3 weeks | $12,000 | High |
| Performance | 4 | 2 weeks | $8,000 | Medium |
| Mobile | 8 | 3 weeks | $12,000 | High |
| Developer Experience | 5 | 1 week | $4,000 | Medium |
| Scalability | 3 | 2 weeks | $8,000 | Medium |

---

## 1. Architecture Debt

### A1 — Text Enrichment Violation (CRITICAL)
**Description:** `voicebridge/types.ts:107-172` implements `enrichText()` which adds filler words, breathing pauses, and emotion tags to AI output. This violates PRODUCT_VISION.md: "AgentCall must never: Rewrite prompts, Perform reasoning, Enrich AI output, Generate summaries."
**Impact:** Core architecture philosophy violated. Legal and product identity risk.
**Est. Effort:** 1 day to remove the function and all call sites.
**Suggested Solution:** Delete the function. Remove `enrichText()` call from `processTextMessage()` in `service.ts`. Remove emotion-related fields from types.
**Risk of Removal:** Low — it's a cosmetic feature that harms architecture integrity.

### A2 — Emotion/Barge-In Detection (CRITICAL)
**Description:** `voicebridge/types.ts:117-145,184-213` implements `emotionOf()`, `extractEmotionTag()`, `detectBargeIn()` — all perform AI reasoning (text classification, intent detection) that AgentCall must never do.
**Impact:** Same as A1 — architecture philosophy violation.
**Est. Effort:** 1 day to remove.
**Suggested Solution:** Delete all three functions. Remove call sites from `CallService.kt` and `voicebridge/service.ts`.

### A3 — No Event Bus (CRITICAL)
**Description:** Backend has no Event Bus. Services communicate via direct function calls. `IMPLEMENTATION_RULES.md` Rule 3 requires event-driven architecture.
**Impact:** Tight coupling between services. Cannot add new features without modifying existing code.
**Est. Effort:** 1 week to design and implement in-process Event Bus.
**Suggested Solution:** Create `common/event-bus.ts` with `publish(topic, event)` / `subscribe(topic, handler)` interface. Migrate direct calls to events.

### A4 — No Service Boundaries (CRITICAL)
**Description:** `voicebridge/service.ts` contains 5+ service responsibilities. SYSTEM_ARCHITECTURE.md defines 11 distinct runtime services.
**Impact:** Monolithic service is hard to test, maintain, or scale. Violates single responsibility principle.
**Est. Effort:** 3 weeks to decompose into proper services.
**Suggested Solution:** Incrementally extract Call Manager, Callback Engine, Phone Registry, Session Manager, and History Service from the current monolith.

### A5 — No Persistence Layer (CRITICAL)
**Description:** All state is in module-level Maps. No database, no repository layer, no data migration.
**Impact:** All data lost on restart. No scaling. No data integrity.
**Est. Effort:** 3 weeks to add PostgreSQL + Repository layer.
**Suggested Solution:** Implement per IMPLEMENTATION_ROADMAP.md Phase 2.

### A6 — No Provider Abstraction (HIGH)
**Description:** No `Provider` concept in code. `agent_id` is a freeform string. SYSTEM_ARCHITECTURE.md specifies Provider Registry as a core service.
**Impact:** Cannot support multi-provider. Each AI provider integration would require custom code.
**Est. Effort:** 2 weeks.
**Suggested Solution:** Implement Provider Registry per SYSTEM_ARCHITECTURE.md.

### A7 — Auth is Vestigial (CRITICAL)
**Description:** `routes.ts:23-33` has a `getAuthUser()` function that always returns `solo-user`. No JWT, no provider keys, no auth enforcement.
**Impact:** Zero security. Any client can call any endpoint.
**Est. Effort:** 2 weeks.
**Suggested Solution:** Implement full JWT auth + provider API key auth per API_SPEC.md lines 43-56.

### A8 — Missing MCP Tools (HIGH)
**Description:** 3 of 8 required MCP tools not implemented: `query_presence`, `resume_task`, `notify_completion`.
**Impact:** API_SPEC.md contract is broken. ChatGPT Actions and Claude integration will fail.
**Est. Effort:** 1 week.
**Suggested Solution:** Implement all three tools. Requires backend services (Presence Engine for `query_presence`).

---

## 2. Security Debt

### S1 — No Auth Enforcement (CRITICAL)
**Duplicates A7.** Listed here for security categorization.

### S2 — Hardcoded Credentials (CRITICAL)
**Description:** `backend/src/common/config.ts:11`, `mcp-server/src/config.ts:10` both default `SERVICE_TOKEN` to `'dev-service-token'`. Android `ApiClient.kt:21` has hardcoded production URL.
**Impact:** Guessable credentials in source code. Easy to exploit.
**Est. Effort:** 1 day.
**Suggested Solution:** Remove default values. Make SERVICE_TOKEN required (fail to start if unset). Move hostnames to build config or env.

### S3 — CORS Wildcard (HIGH)
**Description:** `backend/.env.example:12` has `CORS_ALLOWED_ORIGINS=*`.
**Impact:** Any website can make API calls to the backend.
**Est. Effort:** 1 hour.
**Suggested Solution:** Require explicit origins in production.

### S4 — No HTTPS Enforcement (HIGH)
**Description:** Backend and MCP server listen on plain HTTP. TLS is expected from Caddy but not enforced.
**Impact:** Traffic in cleartext if Caddy is misconfigured or bypassed.
**Est. Effort:** 1 day.
**Suggested Solution:** Add TLS support directly or add middleware that rejects non-TLS connections in production.

### S5 — No Input Validation (HIGH)
**Description:** Zero Zod schemas in use. All request bodies parsed with `as` assertions.
**Impact:** Injection attacks, malformed data, runtime crashes from unexpected input.
**Est. Effort:** 1 week.
**Suggested Solution:** Add Zod schemas for every API endpoint and MCP tool.

### S6 — CSP Disabled (MEDIUM)
**Description:** `backend/src/index.ts:34-35` sets `contentSecurityPolicy: false`.
**Impact:** XSS protection significantly reduced.
**Est. Effort:** 1 day.
**Suggested Solution:** Re-enable CSP with appropriate policy.

### S7 — Android Logging in Release (MEDIUM)
**Description:** `ApiClient.kt:57` sets `HttpLoggingInterceptor.Level.HEADERS` unconditionally.
**Impact:** HTTP headers (including future auth tokens) logged in release builds.
**Est. Effort:** 1 hour.
**Suggested Solution:** Gate with `BuildConfig.DEBUG`.

---

## 3. Infrastructure Debt

### I1 — No PostgreSQL/Redis in Compose (CRITICAL)
**Description:** `docker-compose.yml` does not include PostgreSQL or Redis despite both being in the stated stack.
**Impact:** Cannot run the full stack locally. Integration tests cannot run.
**Est. Effort:** 1 week.
**Suggested Solution:** Add PostgreSQL and Redis services. Add initialization scripts.

### I2 — CI Lint Errors Silenced (HIGH)
**Description:** `.github/workflows/ci.yml:30` uses `|| echo "Lint warnings"` — ESLint failures are hidden.
**Impact:** Lint errors pass CI. Code quality degrades over time.
**Est. Effort:** 1 hour.
**Suggested Solution:** Remove `|| echo` or add `--max-warnings=0`.

### I3 — No Deployment Workflow (CRITICAL)
**Description:** CI builds Docker images but never pushes or deploys them.
**Impact:** Manual deployment required for every change. No staging/prod promotion.
**Est. Effort:** 2 days.
**Suggested Solution:** Add GitHub Actions deploy workflow with Docker registry push and SSH deploy.

### I4 — No .dockerignore (HIGH)
**Description:** No `.dockerignore` at any level.
**Impact:** Docker builds send entire repo context, including node_modules and .git.
**Est. Effort:** 30 minutes.
**Suggested Solution:** Create `.dockerignore` files at root, backend/, and mcp-server/.

### I5 — No Health Check on MCP (HIGH)
**Description:** `mcp-server/Dockerfile` has no `HEALTHCHECK` instruction.
**Impact:** Docker won't restart a crashed MCP server. compose `depends_on` won't detect readiness.
**Est. Effort:** 30 minutes.
**Suggested Solution:** Add HEALTHCHECK.

### I6 — Root Dockerfile Duplicate (MEDIUM)
**Description:** Root `Dockerfile` is 100% identical to `backend/Dockerfile`.
**Impact:** Dead code that will drift from backend/Dockerfile over time.
**Est. Effort:** 30 minutes (delete).
**Suggested Solution:** Delete root `Dockerfile`.

---

## 4. Testing Debt

### T1 — Zero Tests (CRITICAL)
**Description:** 0 test files across ~6,500 LOC of source code.
**Impact:** No regression protection. Cannot verify any refactoring. Bug fixes have no tests.
**Est. Effort:** 4 weeks to achieve 80%+ coverage on core services.
**Suggested Solution:** Prioritize testing in order: service layer → API layer → MCP tools → Android.

### T2 — Vitest Config Broken (CRITICAL)
**Description:** `backend/vitest.config.ts` references non-existent test files and setup. `npm test` will fail.
**Impact:** CI test step fails on first run. Coverage thresholds (80%) are unenforceable.
**Est. Effort:** 1 day.
**Suggested Solution:** Fix vitest config to point to existing files or create placeholder test files.

### T3 — No Android Tests (HIGH)
**Description:** `testInstrumentationRunner` declared in `build.gradle.kts` but no test files exist.
**Impact:** No verification of Android UI logic, ViewModel state, or CallService behavior.
**Est. Effort:** 2 weeks.
**Suggested Solution:** Add unit tests for ViewModels and integration tests for CallService.

### T4 — No Integration Tests (HIGH)
**Description:** No test containers, no compose-based integration tests.
**Impact:** Service interactions are untested. Backend + MCP + Android integration relies on manual testing.
**Est. Effort:** 2 weeks.
**Suggested Solution:** Add integration tests using Docker compose or testcontainers.

### T5 — No MCP Tests (HIGH)
**Description:** Zero tests for MCP tool handlers, client, SSE server.
**Impact:** Tool handlers with 12+ unsafe `as` casts are untested. API client HTTP error handling is untested.
**Est. Effort:** 1 week.
**Suggested Solution:** Unit tests for tools.ts, client.ts. Integration tests for sse.ts with MCP Inspector.

---

## 5. Documentation Debt

### D1 — No Getting Started Guide (MEDIUM)
**Description:** No step-by-step tutorial for new contributors to build and run the project.
**Impact:** High onboarding friction. Developers must piece together setup from .env.example files.
**Est. Effort:** 1 day.
**Suggested Solution:** Create `GETTING_STARTED.md`.

### D2 — API Documentation Aspirational (LOW)
**Description:** API_SPEC.md defines contract but actual implementation implements ~20% of it.
**Impact:** Confusing for contributors — what's specified vs. what's real.
**Est. Effort:** 1 day.
**Suggested Solution:** Add implementation status annotations to API_SPEC.md.

### D3 — No OpenAPI Spec (MEDIUM)
**Description:** API_SPEC.md is Markdown. No OpenAPI 3.1 specification exists.
**Impact:** Cannot use API tooling (Swagger UI, code generation, ChatGPT Actions).
**Est. Effort:** 2 days to generate from Fastify routes.
**Suggested Solution:** Use `@fastify/swagger` to auto-generate OpenAPI spec.

### D4 — Architecture Diagrams Are ASCII (LOW)
**Description:** All diagrams in canonical docs are ASCII art. No Mermaid, PlantUML, or diagram tool.
**Impact:** Hard to maintain. No versioning of diagram changes.
**Est. Effort:** 1 day to convert key diagrams to Mermaid.
**Suggested Solution:** Convert critical architecture diagrams to Mermaid markdown.

---

## 6. Code Debt

### C1 — Android CallService God Object (HIGH)
**Description:** 751 lines, 15+ responsibilities. Single file handles TTS, STT, barge-in, command classification, WebSocket, HTTP API, notifications, wake locks.
**Impact:** Maintenance nightmare. Impossible to test. Any change risks breaking unrelated features.
**Est. Effort:** 2 weeks.
**Suggested Solution:** Decompose into: `TtsManager`, `SttManager`, `BargeInDetector`, `CallSession`, `NotificationHelper`.

### C2 — No Input Validation (HIGH)
**Description:** All backend routes use `as Record<string, unknown>` casts. All MCP tool handlers use `as` casts on arguments.
**Impact:** Runtime crashes from unexpected input. TypeScript strict mode undermined.
**Est. Effort:** 1 week.
**Suggested Solution:** Add Zod schemas at every API boundary.

### C3 — Types Mixed with Logic (MEDIUM)
**Description:** `voicebridge/types.ts` contains type definitions AND runtime enrichment functions.
**Impact:** Violates separation of concerns. Harder to reason about.
**Est. Effort:** 1 day.
**Suggested Solution:** Split types and functions into separate files.

### C4 — Module-Level Mutable State (MEDIUM)
**Description:** `service.ts` has module-level `sessions`, `phoneConnections`, `scheduledCallbacks` Maps. `signaling/server.ts` has `connectionRateLimits`, `clientRateLimits` Maps.
**Impact:** Untestable in isolation. Not thread-safe. Cannot be instantiated multiple times.
**Est. Effort:** 3 days.
**Suggested Solution:** Wrap state in classes with factory functions.

### C5 — No Service Facade (MEDIUM)
**Description:** `routes.ts` calls `voicebridge.*` functions directly.
**Impact:** Routes know too much about business logic. Cannot swap implementations.
**Est. Effort:** 2 days.
**Suggested Solution:** Add `CallServiceFacade` between routes and business logic.

### C6 — `zod` Unused Dependency (MEDIUM)
**Description:** Listed in `mcp-server/package.json` but never imported.
**Impact:** Dead weight in node_modules. Missed opportunity for input validation.
**Est. Effort:** 1 hour.
**Suggested Solution:** Use for validation or remove from dependencies.

### C7 — `env()` Empty String Behavior (MEDIUM)
**Description:** `env()` in both backend and MCP config returns `''` for missing vars. `parseInt('')` = `NaN`.
**Impact:** Obscure runtime failures when env vars are missing.
**Est. Effort:** 1 day.
**Suggested Solution:** Add config validation after load. Throw on empty required vars.

### C8 — `scheduledCallbacks` Keyed by userId (MEDIUM)
**Description:** `service.ts:163` uses `userId` as the Map key. Multiple callbacks for the same user overwrite each other. Timers from overwritten callbacks are not cleared.
**Impact:** Stale timer notifications. Lost callbacks.
**Est. Effort:** 1 day.
**Suggested Solution:** Key by `callId`. Track and clear timers.

### C9 — `ws.send()` Unhandled Promise (MEDIUM)
**Description:** `signaling/server.ts:51` calls `ws.send()` without awaiting or catching.
**Impact:** Unhandled promise rejection on socket error.
**Est. Effort:** 1 hour.
**Suggested Solution:** Add `.catch()` or await.

### C10 — Emotion Mapping Duplicated (LOW)
**Description:** Emotion-to-color/emoji/gradient maps exist in 3 places across Android.
**Impact:** Maintenance hazard. Adding an emotion requires 3 file updates.
**Est. Effort:** 1 day.
**Suggested Solution:** Extract to shared `EmotionTheme` object.

### C11 — Infinite Reconnection Loop (HIGH)
**Description:** Android `SignalingClient.kt` recursively calls `connectInternal()` on every WebSocket failure with no max retries or backoff.
**Impact:** Battery drain if server is down. Unbounded coroutine chain.
**Est. Effort:** 1 day.
**Suggested Solution:** Add exponential backoff (1s, 2s, 4s, 8s, max 30s) and max retry limit.

### C12 — API Response Type Not Type-Safe (LOW)
**Description:** `ApiResponse<T>` in MCP `client.ts` has all optional fields — allows invalid states like `{data: undefined, error: undefined}`.
**Impact:** Non-null assertions (`result.data!`) in every tool handler are unsafe.
**Est. Effort:** 1 day.
**Suggested Solution:** Use discriminated union: `{data: T} | {error: string, message: string}`.

---

## 7. Performance Debt

### P1 — Non-Deterministic Text Enrichment (MEDIUM)
**Description:** `Math.random()` in `enrichText()` means every request has unpredictable processing cost and output.
**Impact:** Cannot cache, cannot reproduce issues. Wastes CPU cycles.
**Est. Effort:** 1 day (remove the function).
**Suggested Solution:** Remove enrichment entirely (per A1).

### P2 — Android 5s HTTP Polling (MEDIUM)
**Description:** `HomeViewModel` polls `getActiveCall` every 5 seconds via HTTP.
**Impact:** Battery drain. Network overhead. Unnecessary load on backend.
**Est. Effort:** 1 day.
**Suggested Solution:** Replace with WebSocket event or SSE.

### P3 — No Connection Pooling (LOW)
**Description:** MCP `client.ts` uses `fetch()` which creates a new connection per request. Backend uses Fastify defaults.
**Impact:** Higher latency. Connection churn.
**Est. Effort:** 1 day.
**Suggested Solution:** Add keepalive agent to MCP client. Configure Fastify connection pool.

### P4 — Unbounded Rate-Limit Maps (LOW)
**Description:** `signaling/server.ts` rate-limit Maps grow without bound. Eviction runs every 30s.
**Impact:** Memory leak under high traffic with many unique IPs.
**Est. Effort:** 1 day.
**Suggested Solution:** Use bounded LRU cache or Redis-backed rate limiting.

---

## 8. Mobile Debt

### M1 — Android CallService God Object (HIGH)
**Already covered as C1.** Listed here for mobile categorization.

### M2 — No Authentication Flow (HIGH)
**Description:** Android hardcodes `solo-user`. No login/register screen. `TokenManager` is wired but never populated.
**Impact:** Cannot support multi-user. Zero security.
**Est. Effort:** 2 weeks.
**Suggested Solution:** Implement login/register screens with JWT auth flow.

### M3 — No Presence UI (MEDIUM)
**Description:** PRODUCT_VISION.md specifies presence indicators (online, offline, busy, DND, in-call). Not implemented.
**Impact:** Users cannot see their availability status. AI cannot check presence.
**Est. Effort:** 1 week.
**Suggested Solution:** Add presence state management and UI indicators.

### M4 — No Provider Management (MEDIUM)
**Description:** PRODUCT_VISION.md specifies connected providers list (ChatGPT, Claude, Gemini, etc.). Not implemented.
**Impact:** Users cannot see or manage which AIs can reach them.
**Est. Effort:** 1 week.
**Suggested Solution:** Add provider list screen and connection management.

### M5 — No Push Notification Integration (MEDIUM)
**Description:** FCM dependency is not declared. Push notification channels exist but no FCM token registration or push handling.
**Impact:** No push notifications. App must be running to receive calls.
**Est. Effort:** 1 week.
**Suggested Solution:** Integrate FCM. Add token registration on login.

### M6 — No WebSocket Heartbeat (MEDIUM)
**Description:** No WebSocket ping/pong. Connection health checked via wasteful 5s HTTP polling.
**Impact:** Unreliable connection state detection. Battery waste.
**Est. Effort:** 2 days.
**Suggested Solution:** Add WebSocket ping/pong or application-level heartbeat.

### M7 — Dead ViewModel Methods (LOW)
**Description:** `showAITyping()`, `setBargeIn()`, `setPaused()` defined but never called.
**Impact:** Dead code. Confusing for maintenance.
**Est. Effort:** 1 hour.
**Suggested Solution:** Remove or implement the missing call paths.

### M8 — AudioRecord Lifecycle Bug (MEDIUM)
**Description:** `AudioRecord.startRecording()` is called in a coroutine but not properly cleaned up on service destroy.
**Impact:** AudioRecord resource leak. Potential microphone stuck state.
**Est. Effort:** 1 day.
**Suggested Solution:** Add ensure proper release in `onDestroy()` with try/finally.

---

## 9. Developer Experience Debt

### DX1 — No Pre-commit Hooks (MEDIUM)
**Description:** No husky, no lint-staged. Developers can commit code that fails lint or typecheck.
**Impact:** CI catches issues late. Code quality degrades.
**Est. Effort:** 1 day.
**Suggested Solution:** Install husky + lint-staged with eslint and tsc checks on staged files.

### DX2 — No Root-Level Scripts (LOW)
**Description:** Must `cd` into subdirectories to run `npm test`, `npm run lint`, etc.
**Impact:** Friction for new developers. No workspace orchestration.
**Est. Effort:** 1 day.
**Suggested Solution:** Add root `package.json` with workspace scripts.

### DX3 — No Commit Convention Enforcement (LOW)
**Description:** `AGENTS.md` specifies Conventional Commits but nothing enforces them.
**Impact:** Inconsistent commit messages. Harder to generate changelog.
**Est. Effort:** 1 hour.
**Suggested Solution:** Add `commitlint` with husky hook.

### DX4 — No .editorconfig (LOW)
**Description:** No `.editorconfig` file.
**Impact:** IDE formatting inconsistency across team.
**Est. Effort:** 15 minutes.
**Suggested Solution:** Create `.editorconfig`.

### DX5 — Vitest Setup Broken (HIGH)
**Already covered as T2.** Listed here for DX categorization.

---

## 10. Scalability Debt

### SC1 — In-Memory State Only (CRITICAL)
**Description:** All services use module-level Maps for state. No database, no Redis.
**Impact:** Cannot scale horizontally. All state lost on restart. Single point of failure.
**Est. Effort:** 3 weeks (per I1/A5).
**Suggested Solution:** Implement PostgreSQL + Redis per roadmap.

### SC2 — Singleton Service Modules (MEDIUM)
**Description:** Backend services are module-level singletons (state at module scope). Cannot instantiate multiple copies.
**Impact:** Cannot load-balance within a process. Testing requires module-level state reset.
**Est. Effort:** 1 week.
**Suggested Solution:** Move state to class instances with factory functions or DI.

### SC3 — No Connection Pool (LOW)
**Description:** MCP client uses `fetch()` without connection reuse. Backend has no database connection pooling.
**Impact:** Connection churn under load. Resource exhaustion.
**Est. Effort:** 2 days.
**Suggested Solution:** Add HTTP agent for keepalive. Add database connection pool configuration.

---

## Debt Repayment Priority

| Priority | Item | Effort | Impact |
|----------|------|--------|--------|
| P0 | A1/A2 — Remove enrichment/emotion/barge-in (philosophy violation) | 1 day | Unblocks architecture compliance |
| P0 | T2 — Fix vitest config | 1 day | Enables testing |
| P0 | T1 — Add basic tests for core services | 4 weeks | Foundation for all refactoring |
| P1 | S1/A7 — Implement real auth | 2 weeks | Security |
| P1 | A3 — Event Bus | 1 week | Unblocks service decomposition |
| P1 | A5/I1 — PostgreSQL + Repository layer | 3 weeks | Persistence foundation |
| P2 | C1/M1 — Decompose CallService | 2 weeks | Mobile code quality |
| P2 | A4 — Service decomposition | 3 weeks | Architecture |
| P2 | S5 — Input validation | 1 week | Security/correctness |
| P3 | C11 — Fix reconnection loop | 1 day | Mobile reliability |
| P3 | I2 — Fix CI lint | 1 hour | Code quality |
| P3 | I3 — Deploy workflow | 2 days | Release process |
| P4 | All remaining items | ~6 weeks | Quality of life |
