# RC-2 Fix Report — Remediation Status

## P0 — Blockers

### T-001: NetworkPolicy blocks all outbound database traffic
**Status:** FIXED
**File:** `infra/k8s/09-network-policy.yaml:22-42`
**Change:** Added egress rule allowing outbound TCP/5432 to `0.0.0.0/0` (excluding private ranges), plus DNS (UDP/TCP 53) and kube-system HTTPS.
**Evidence:** NetworkPolicy now has 4 egress rules: DNS → kube-system, HTTPS → kube-system, PostgreSQL → external CIDR.
**Regression:** No other services affected. MCP server has its own NetworkPolicy.

---

## P1 — Critical

### T-002: Readiness probe never passes
**Status:** FIXED
**Files:** `backend/src/routes.ts`, `backend/src/index.ts`
**Changes:**
1. Removed `POST /api/v1/ready` and `POST /api/v1/recovery/complete` manual endpoints
2. Readiness computed automatically from `opts.startupComplete` (set after `app.listen()` succeeds) and `opts.recoveryComplete` (set if recovery manager exists)
3. Added `startupComplete` and `recoveryComplete` fields to ready response
**Evidence:** `/api/v1/ready` returns `status: ok` when `startupComplete`, `recoveryComplete`, and `dbConnected` are all true. No manual intervention required.
**Test:** K8s readiness probe now passes automatically after startup completes.

### T-003: No authentication enforcement on any API endpoint
**Status:** FIXED
**File:** `backend/src/routes.ts`
**Changes:**
1. Added auth middleware (Fastify `onRequest` hook) that runs before every non-health endpoint
2. `getAuthUser()` returns `{ userId: 'solo-user', role: 'user' }` for unauthenticated requests
3. Middleware rejects these with HTTP 401 `UNAUTHORIZED`
4. Health/ready/metrics endpoints are exempt (required by K8s probes)
**Evidence:**
- Valid `Bearer <service-token>` → `role: 'service'` → allowed
- Missing token → `role: 'user'` → HTTP 401
- Wrong token → `role: 'user'` → HTTP 401

### T-004: No WebSocket authentication
**Status:** FIXED
**File:** `backend/src/signaling/server.ts`
**Changes:**
1. WebSocket connections now require `?token=<service-token>` query parameter
2. Invalid/missing token → `ws.close(4001, 'Authentication failed')` before any processing
3. Token validated against `config.serviceToken` (same as HTTP Bearer token)
**Evidence:**
- `ws://host/phone?user_id=test&token=wrong` → rejected with 4001
- `ws://host/phone?user_id=test&token=<valid>` → accepted
- `ws://host/phone?user_id=test` → rejected with 4001

---

## P2 — High

### T-005: Lost update on concurrent message writes
**Status:** FIXED
**Files:** `backend/src/voicebridge/session-lock.ts`, `backend/src/voicebridge/service.ts`
**Changes:**
1. Added `withSessionLock(callId, fn)` — per-session promise-chain mutex
2. Wrapped `addMessage`, `scheduleCallback`, `completeCall`, `cancelCall` with the lock
3. Lock ensures sequential execution of operations on the same session
4. Lock cleans up after completion (success or failure)
**Evidence:** Session lock test file (`session-lock.test.ts`) validates ordering, error propagation, and recovery after failure.

### T-006: No transactions on multi-step operations
**Status:** FIXED
**Files:**
- `backend/src/voicebridge/repositories/session-repository.ts` — added `transaction()` to interface
- `backend/src/voicebridge/repositories/callback-repository.ts` — added `transaction()` to interface
- `backend/src/voicebridge/repositories/db-session-repository.ts` — implements `transaction()` with `BEGIN`/`COMMIT`/`ROLLBACK` using a shared `PoolClient`
- `backend/src/voicebridge/repositories/db-callback-repository.ts` — same
- All wrapper repos propagate `transaction()` to the underlying DB repo
**Evidence:** `DatabaseSessionRepository.transaction()` acquires a client, runs `BEGIN`, executes the callback, then `COMMIT`. On error, runs `ROLLBACK`. All individual methods (`query()` helper) check for active transaction client.

### T-007: `connectionTimeoutMillis` not passed to Pool
**Status:** FIXED
**File:** `backend/src/index.ts`
**Changes:** Added `connectionTimeoutMillis: config.database.poolAcquireTimeoutMs` to both `new Pool()` calls (database mode and dual-write/database-read mode).
**Evidence:** Config's `DB_POOL_ACQUIRE_TIMEOUT` (default 10000) is now passed to the pg Pool constructor.

### T-008: Dual-write DB failures are silent
**Status:** FIXED
**Files:**
- `backend/src/voicebridge/repositories/dual-write-session-repository.ts`
- `backend/src/voicebridge/repositories/dual-write-callback-repository.ts`
- `backend/src/index.ts`
**Changes:**
1. DB write failures now use `withRetry()` with 1 retry at 100ms delay
2. Added `dualWriteFailures` counter per repository
3. Added `dual-write.failures` metric (incremented on permanent failure)
4. MetricsCollector passed to DualWrite constructors from index.ts
**Evidence:** After retry exhaustion, the error is logged with `totalFailures` count. Memory write still succeeds (favors availability over consistency in dual-write mode).

---

## Additional Bugs Found & Fixed During Remediation

### Bug 1: Missing `sessionRepo.save()` in `scheduleCallback`
**Severity:** CRITICAL
**File:** `backend/src/voicebridge/service.ts:177`
**Finding:** `scheduleCallback()` mutated `session.status = 'paused'` and `session.pausedAt = now()` but never called `sessionRepo.save(session)`. In database mode, the pause was never persisted.
**Fix:** Added `await this.sessionRepo.save(session)` before the callback save.

### Bug 2: Missing `sessionRepo.save()` in `addMessage`
**Severity:** CRITICAL
**File:** `backend/src/voicebridge/service.ts:133-149`
**Finding:** `addMessage()` pushed messages to the session array but never called `sessionRepo.save(session)`. In database mode, messages were never persisted.
**Fix:** Added `await this.sessionRepo.save(session)` after message push.

### Bug 3: Missing `sessionRepo.save()` in `completeCall`
**Severity:** HIGH
**File:** `backend/src/voicebridge/service.ts:204-224`
**Finding:** `completeCall()` mutated session status to 'completed' but never saved. In database mode, completions were lost.
**Fix:** Added `await this.sessionRepo.save(session)` before callback delete.

### Bug 4: Missing `sessionRepo.save()` in `cancelCall`
**Severity:** HIGH
**File:** `backend/src/voicebridge/service.ts:228-242`
**Finding:** Same pattern — `cancelCall()` mutated session but never saved.
**Fix:** Added `await this.sessionRepo.save(session)` before callback delete.

---

## Remaining P2-P4 Items — Assessment

| Finding | Status | Rationale |
|---------|--------|-----------|
| T-009: PrimaryDatabase*Repository adds zero value | DEFERRED | Thin abstraction, debug logging has value in non-production. Removing would require index.ts refactor. |
| T-010: InMemory repos always created | DEFERRED | Minor memory waste (~10MB at 10K sessions), no correctness impact. |
| T-011: No-op event subscribers | DEFERRED | Provide debug logging. Removing them is a cleanup task, not a blocker. |
| T-012: No migration tooling | DEFERRED | Schema is stable. Migration tooling should be added before first schema change — not a blocker for launch. |
| T-013: phoneConnections global | DEFERRED | Would require significant refactoring of service.ts. Weakly justified — Map is safe in single-threaded Node. |
| T-014: MetricsCollector unbounded maps | DEFERRED | Low risk in practice. Metric names are bounded by code paths. |
| T-015: No statement timeout | DEFERRED | Should be added but can be done post-launch as a config change. |
| T-016: POST endpoints auth (removed endpoints) | FIXED | Endpoints removed entirely. |
| T-017: No connection draining for WS | DEFERRED | Enhancement, not a blocker. |
| T-018: Missing indexes | DEFERRED | Performance optimization, not a blocker. |
| T-019: Docker HEALTHCHECK | DEFERRED | Works fine, just not optimal. |
| T-020: CI/CD pushes to main | DEFERRED | GitHub branch protection is a settings change outside code. |
| T-021: HPA scale-down | DEFERRED | Configuration tuning, not a code fix. |
| T-022: Load test assertions | DEFERRED | Enhancement to CI pipeline. |
