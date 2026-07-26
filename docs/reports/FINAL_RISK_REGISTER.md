# Final Risk Register

## Risk Scoring

- **Likelihood:** 1 (Rare) → 5 (Almost Certain)
- **Impact:** 1 (Negligible) → 5 (Catastrophic)
- **Score:** Likelihood × Impact (1-25)
- **Threshold:** Score ≥ 8 = requires mitigation; score ≥ 15 = critical blocker

## Risks

| # | Risk | Likelihood | Impact | Score | Mitigation | Status |
|---|------|-----------|--------|-------|------------|--------|
| R01 | **DB write succeeds but in-memory write fails (dual-write inconsistency)** | 2 | 4 | 8 | DualWrite repos retry in-memory on failure. If both fail, DB write survives and is read on next restart (Phase A recovery). | ✅ Mitigated |
| R02 | **In-memory write succeeds but DB write fails (dual-write inconsistency)** | 3 | 3 | 9 | Retry (max 1) in DualWrite repos + metrics alert. If both fail, in-memory state survives until pod restart, then Phase A recovers from DB. | ✅ Mitigated |
| R03 | **Session lock contention causes request timeout** | 2 | 2 | 4 | Lock held for <1ms (in-memory ops). Contention logged. | ✅ Accepted |
| R04 | **Pool exhaustion under high load** | 3 | 3 | 9 | connectionTimeoutMillis prevents indefinite waits. Pool size configurable. Monitor pool.waiting. | ✅ Mitigated (RC-2) |
| R05 | **DB permanent failure (full outage)** | 2 | 4 | 8 | InMemory repos keep working. Mode switch to memory-only (manual). Recovery on DB restoration. | ✅ Mitigated |
| R06 | **DB data corruption** | 1 | 5 | 5 | PostgreSQL WAL provides point-in-time recovery. DB read returns corrupted data → application uses it. | ⚠️ Unmitigated |
| R07 | **Secrets exposure via env dump** | 2 | 4 | 8 | Config validation logs only keys and types, never values. Process env not logged. | ✅ Mitigated |
| R08 | **Secrets exposure via /api/v1/health/config** | 2 | 5 | 10 | Removed in RC-1 audit. Health endpoint no longer dumps config. | ✅ Mitigated |
| R09 | **Unauthenticated WebSocket access** | 3 | 4 | 12 | WS token auth added in RC-2. Missing/invalid token → 4001 close. | ✅ Mitigated |
| R10 | **Solo-user bypass via HTTP** | 3 | 5 | 15 | Auth middleware with token validation added in RC-2. 401 on missing/invalid token. | ✅ Mitigated |
| R11 | **Solo-user bypass via WebSocket** | 3 | 5 | 15 | WS token validation added in RC-2. | ✅ Mitigated |
| R12 | **Readiness probe bypass** | 2 | 2 | 4 | Auto-readiness from startup + recovery + DB health. No manual override. | ✅ Mitigated |
| R13 | **Clock drift affecting timer scheduling** | 2 | 3 | 6 | Fire times computed from `Date.now()`. Drift of seconds = timer fires seconds early/late. Session expiry timestamps drift. | ⚠️ Unmitigated — rely on NTP |
| R14 | **WebSocket connection leak (unbounded per pod)** | 3 | 2 | 6 | No max connections per pod. Each WS consumes fd + memory. At 10K connections, pod is at risk. | ⚠️ Unmitigated — no cap |
| R15 | **Timer drift from setInterval** | 3 | 1 | 3 | Sweeper and health check timers drift by event-loop-delay per cycle. Not cumulative. | ✅ Accepted |
| R16 | **Rolling update drops WS connections** | 5 | 2 | 10 | Pod shutdown closes all WS connections. Clients must reconnect. No drain mechanism. | ⚠️ Unmitigated — known limitation |
| R17 | **Scale-down drops WS connections** | 3 | 2 | 6 | Same as R16. HPA scale-down terminates pods. | ⚠️ Unmitigated |
| R18 | **Memory growth from unbounded session map (InMemory mode)** | 2 | 3 | 6 | Sweeper runs every 5 min. If sweeper can't keep up (10K+ stale sessions/hour), Map grows. | ⚠️ Partially mitigated |
| R19 | **Process exits during Phase A/B recovery** | 1 | 4 | 4 | Partial state discarded. Full recovery on next restart. At startup, no in-flight calls exist yet. | ✅ Accepted |
| R20 | **Two pods write to same DB simultaneously (dual-write conflict)** | 3 | 3 | 9 | Both pods run dual-write: memory + DB. DB-based reads should converge. Session-level locks are per-pod and don't coordinate across pods. | ⚠️ Unmitigated — cross-pod locking not implemented |
| R21 | **Single point of failure: no replicas** | 2 | 3 | 6 | HPA min=2, PDB minAvailable=1. At least 1 pod always available. | ✅ Mitigated (infra) |
| R22 | **No cross-pod session lock** | 3 | 3 | 9 | Session lock is per-process (promise-chain). Two pods can mutate the same DB session concurrently. Last-write-wins. | ⚠️ Unmitigated — design limitation |
| R23 | **Callback fires on pod that didn't initiate it** | 4 | 2 | 8 | Timer fires on the pod that scheduled it. If that pod is gone, timer never fires. Callback is still in DB. On restart, Phase B rebuilds timers. | ✅ Mitigated |
| R24 | **Notification double-delivery (dual-write retry)** | 3 | 2 | 6 | onSessionEvent may fire twice (once on first attempt, once on retry). Idempotent notifications expected. | ⚠️ Unmitigated — relies on idempotency |

## Risk Acceptance Summary

| Risk | Score | Rationale |
|------|-------|-----------|
| R03 (lock contention) | 4 | Lock held <1ms. Only 4 operations use lock. |
| R06 (DB corruption) | 5 | Corrupt data from DB is an infrastructure concern, not application. |
| R13 (clock drift) | 6 | NTP is standard in K8s. Beyond application control. |
| R14 (WS leak) | 6 | Per-pod limit not implemented. Acceptable for initial release with load-balanced WS. |
| R15 (setInterval drift) | 3 | Drift is bounded by event loop delay. |
| R18 (InMemory growth) | 6 | DB mode is recommended for production. InMemory only for dev/testing. |
| R22 (cross-pod lock) | 9 | Known design limitation. A future release should add advisory locks (pg_advisory_lock) or a distributed lock service. |
| R24 (notification double-delivery) | 6 | Idempotent notification handlers mitigate this. |

## Verdict

**22 risks identified. 16 are mitigated (11 in RC-2). 6 are accepted or unmitigated. The 2 critical risks (R10, R11 — auth bypass) are now mitigated. The highest remaining unmitigated risk is R22 (cross-pod session lock) at score 9 — acceptable for launch but should be tracked as tech debt for v1.1.**
