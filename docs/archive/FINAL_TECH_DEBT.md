# Technical Debt Register — RC-1

All items ranked by priority (P0=blocker, P1=critical, P2=high, P3=medium, P4=low).

---

## P0 — Blockers

### T-001: NetworkPolicy blocks all outbound database traffic

**File:** `infra/k8s/09-network-policy.yaml:22-31`

The NetworkPolicy egress rule only allows traffic to `kube-system` namespace. External PostgreSQL connections are blocked.

**Fix:** Add an egress rule for the database host or use a wider CIDR.

**Effort:** 15 minutes

---

## P1 — Critical

### T-002: Readiness probe never passes

**File:** `backend/src/index.ts:294,313` and `backend/src/routes.ts:335-338`

The `/api/v1/ready` endpoint returns `not_ready` until `POST /api/v1/ready` is called. No deployment code calls this endpoint. Kubernetes readiness probe never passes → pods never receive traffic.

**Fix:** Set `startupComplete = true` when `app.listen()` succeeds, or remove the manual ready toggle.

**Effort:** 30 minutes

### T-003: No authentication enforcement on any API endpoint

**File:** `backend/src/routes.ts:38-48`

`getAuthUser()` returns `{ userId: 'solo-user', role: 'user' }` for any unauthenticated request. The auth context is logged but never used for authorization.

**Fix:** Add auth middleware that rejects requests without valid tokens, or accept single-user mode explicitly.

**Effort:** 2 hours

### T-004: No WebSocket authentication

**File:** `backend/src/signaling/server.ts:89-90`

WebSocket connections authenticate via a query parameter with zero validation.

**Fix:** Require token-based auth for WebSocket upgrade path.

**Effort:** 4 hours

---

## P2 — High

### T-005: Lost update on concurrent message writes

**File:** `backend/src/voicebridge/service.ts:120-147`

Two concurrent `addMessage` calls can lose one message due to read-modify-write race.

**Fix:** Use append-only message insert (push to array, no read-modify-write) or add version field with optimistic locking.

**Effort:** 4 hours

### T-006: No transactions on multi-step operations

Several operations modify multiple records without a transaction:
- `scheduleCallback()`: session save + callback save
- `completeCall()`: session save + callback delete

**Fix:** Wrap in DB transaction or add compensating actions on failure.

**Effort:** 8 hours

### T-007: `connectionTimeoutMillis` not passed to Pool

**File:** `backend/src/index.ts:108-113`

`poolAcquireTimeoutMs` is configured but never used. Pool can wait indefinitely.

**Fix:** Add `connectionTimeoutMillis` to Pool constructor options.

**Effort:** 15 minutes

### T-008: Dual-write DB failures are silent

**File:** `backend/src/voicebridge/repositories/dual-write-session-repository.ts:30-35`

DB write failures are fire-and-forget with `.catch(logger.error)`. The caller gets no indication of partial failure.

**Fix:** Surface DB write failures or implement write-verification with retry.

**Effort:** 4 hours

### T-009: `PrimaryDatabase*Repository` adds zero value

**File:** `backend/src/voicebridge/repositories/primary-db-session-repository.ts`
**File:** `backend/src/voicebridge/repositories/primary-db-callback-repository.ts`

These are thin wrappers that only add debug logging. The instrumentation layer already provides observability.

**Fix:** Remove these classes and use `Database*Repository` directly in `database` mode.

**Effort:** 1 hour

---

## P3 — Medium

### T-010: InMemory repos always created, even in database mode

**File:** `backend/src/index.ts:91-92`

InMemory repos are allocated and populated by RecoveryManager even when `PERSISTENCE_MODE=database` makes them unnecessary.

**Fix:** Skip InMemory creation in database mode, or remove RecoveryManager's dependency on them.

**Effort:** 2 hours

### T-011: All event bus subscribers are no-ops

**Files:** `backend/src/voicebridge/*/subscribers.ts`

14 event subscribers registered during startup. All of them only log. They add dispatch overhead (queueMicrotask, handler invocation, error handling) for zero business value.

**Fix:** Remove log-only subscribers. Only register subscribers that do actual work.

**Effort:** 30 minutes

### T-012: No migration tooling

**File:** `backend/src/voicebridge/repositories/schema.sql`

Schema is defined in a reference SQL file. No migration tool, no version tracking, no rollback.

**Fix:** Add Knex or node-pg-migrate with migration scripts.

**Effort:** 4 hours

### T-013: `phoneConnections` is a module-level global

**File:** `backend/src/voicebridge/service.ts:33`

The Map is module-level state, not injected. This makes it impossible to test `VoiceBridgeService` without mocking the entire module.

**Fix:** Inject the connection map or connection manager into the service.

**Effort:** 2 hours

### T-014: MetricsCollector maps grow unbounded

**File:** `backend/src/common/metrics-collector.ts`

Counters and gauges use `Map<string, number>` with no eviction. An attacker or bug could create unlimited unique metric names.

**Fix:** Add max key limit or use LRU eviction.

**Effort:** 2 hours

### T-015: No statement timeout or query timeout

`pg.Pool` configured without `statement_timeout`. Slow queries can block connections indefinitely.

**Fix:** Set `statement_timeout` via `pool.query('SET statement_timeout = 5000')` or session variable.

**Effort:** 30 minutes

---

## P4 — Low

### T-016: `POST /api/v1/ready` and `POST /api/v1/recovery/complete` are unauthenticated

Anyone can toggle readiness or recovery state.

**Fix:** Remove or protect these endpoints.

**Effort:** 30 minutes

### T-017: No connection draining for WebSocket on shutdown

Shutdown sequence closes signaling server but doesn't notify connected phones.

**Fix:** Send shutdown notification to all connected WebSockets before closing.

**Effort:** 2 hours

### T-018: No indexes on `created_at` or compound indexes

Missing performance optimization.

**Fix:** Add compound index `(user_id, created_at DESC)`.

**Effort:** 15 minutes

### T-019: Docker HEALTHCHECK uses Node.js for each check

Spinning up a Node process for health checks is resource-intensive.

**Fix:** Install `wget` or use `curl` in the Docker image.

**Effort:** 15 minutes

### T-020: CI/CD deploys on push to main without PR requirement

**File:** `.github/workflows/ci-cd.yml:4-6`

Pipeline triggers on push to main. Direct pushes bypass code review.

**Fix:** Enable branch protection requiring PRs for main branch.

**Effort:** 15 minutes (GitHub settings)

### T-021: HPA scale-down can kill active WebSocket connections

No stabilization window for scale-down. Pods with active connections can be terminated.

**Fix:** Add `behavior.scaleDown.stabilizationWindowSeconds`.

**Effort:** 15 minutes

### T-022: Load test runs in CI with no pass/fail criteria

`test:load` prints results but never fails the pipeline.

**Fix:** Add assertions (e.g., ops/sec > threshold).

**Effort:** 1 hour

---

## Summary

| Priority | Count | Effort |
|----------|-------|--------|
| P0 (blocker) | 1 | 15 min |
| P1 (critical) | 3 | 6.5 hrs |
| P2 (high) | 5 | 17.25 hrs |
| P3 (medium) | 6 | 9 hrs |
| P4 (low) | 7 | 4.75 hrs |
| **Total** | **22** | **~37.5 hrs** |

## Score

**Technical Debt: 5/10** — 22 identified issues including 1 blocker and 3 critical items that would cause production failures.
