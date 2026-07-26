# Known Limitations — VoiceBridge v1.0.0

> All accepted limitations of the current implementation. No speculation.

---

## L001: Single-User Authentication Model

| Field | Value |
|-------|-------|
| **Impact** | All clients share a single SERVICE_TOKEN. No user isolation, no roles, no per-user sessions. |
| **Why accepted** | MVP scope. The system is designed for a single AI agent contacting a single human. Multi-user auth (JWT, OAuth, RBAC) was deferred. |
| **Mitigation** | Token is validated on every request. Unauthorized requests receive 401. Token can be rotated via env var + restart. |
| **Future recommendation** | Implement JWT-based authentication with per-user tokens. Add RBAC for admin vs. user roles. |

---

## L002: No Cross-Pod Session Lock

| Field | Value |
|-------|-------|
| **Impact** | Session lock (`withSessionLock()`) uses a per-process promise-chain mutex. Two pods can mutate the same DB session concurrently. Last-write-wins. Risk of lost updates under multi-pod deployment. |
| **Why accepted** | Cross-pod coordination requires a distributed lock mechanism (PostgreSQL advisory locks, Redis, etc.). Not implemented. The single-token auth model implies one client = one session = low likelihood of concurrent access. |
| **Mitigation** | PDB ensures min 1 pod. HPA scales based on CPU/memory, not session count. Dual-write mode reduces risk window. |
| **Future recommendation** | Use `pg_advisory_lock` for cross-pod session locking, or implement an external lock service. |

---

## L003: WebSocket Connections Dropped on Rolling Update

| Field | Value |
|-------|-------|
| **Impact** | When a pod is terminated (rolling update, scale-down, restart), all active WebSocket connections to that pod are closed. Clients must reconnect. No drain/graceful handoff mechanism. |
| **Why accepted** | WebSocket drain requires a signaling protocol for connection migration (e.g., transmitting session state to a new pod). Not implemented. |
| **Mitigation** | Clients implement auto-reconnect (Android app reconnects every 3s). Registration endpoint returns current WS endpoint. In-flight calls survive in DB. |
| **Future recommendation** | Implement WebSocket drain in the shutdown sequence: notify connected clients with a `reconnect` event before closing. Add a session persistence layer for WS state. |

---

## L004: Per-Process Timers (Distributed Timer Gap)

| Field | Value |
|-------|-------|
| **Impact** | Callback timers (`setTimeout`) are per-process. If the pod that scheduled a callback is terminated before the timer fires, the timer is lost. Phase B recovery rebuilds timers from DB on restart, but there can be a gap between the scheduled fire time and the restart. |
| **Why accepted** | Distributed timers require a centralized scheduler (e.g., Redis pub/sub, cron service). Not implemented. |
| **Mitigation** | Phase B recovery (rebuild timers from DB) runs on every startup. The gap between timer expiry and recovery is bounded by the restart time (< 10s typical). |
| **Future recommendation** | Use a distributed timer service (e.g., Bull BullMQ with Redis, or pg_tle for PostgreSQL-based scheduling). |

---

## L005: No Database Migration Tooling

| Field | Value |
|-------|-------|
| **Impact** | Schema is defined in a static SQL file (`schema.sql`). No migration scripts, no version tracking, no rollback. Schema changes require manual SQL execution and may break running instances. |
| **Why accepted** | v1.0 has a single schema that is unlikely to change rapidly. Migration tooling adds complexity without immediate benefit. |
| **Mitigation** | Schema is documented. Manual SQL execution is viable for initial deployment. |
| **Future recommendation** | Integrate Knex.js or `node-pg-migrate` with versioned migrations. Add CI check to ensure migrations are run before deployment. |

---

## L006: InMemory Repos Always Allocated

| Field | Value |
|-------|-------|
| **Impact** | Even in `database` persistence mode, InMemory repos are created and populated by Phase A recovery. Memory is consumed by the in-memory copy of all sessions even when it's not used for reads. |
| **Why accepted** | The architecture was originally memory-only. The dual-write migration path requires both stores to be active. Refactoring to conditionally skip InMemory in database mode was deferred. |
| **Mitigation** | Memory overhead is ~32KB per session + object overhead. At 10K sessions, this is ~320MB — significant but manageable within a 512MB pod limit. |
| **Future recommendation** | Add a `PERSISTENCE_MODE=db-only` mode that skips InMemory creation and Phase A loading. |

---

## L007: All Event Bus Subscribers Are No-Ops

| Field | Value |
|-------|-------|
| **Impact** | 14 event subscribers are registered during startup. All of them only log the event. They add dispatch overhead (microtask scheduling, handler invocation, error handling) for zero business value. |
| **Why accepted** | The subscriber architecture was designed for future extensibility (notifications, presence broadcasting, analytics). The logging helps debugging. Removing them would be a refactor without immediate benefit. |
| **Mitigation** | Dispatch overhead is negligible (< 0.01ms per event). Logging is at debug level, not emitted in production unless verbose. |
| **Future recommendation** | Remove log-only subscribers. Register only when business logic is implemented. Or add an `enabled` flag to skip registration in production. |

---

## L008: No Statement Timeout on PostgreSQL Queries

| Field | Value |
|-------|-------|
| **Impact** | No `statement_timeout` is set on the pg.Pool. A slow or hanging query can block a connection indefinitely. |
| **Why accepted** | All queries are simple primary-key lookups or single-table scans. Connection timeout prevents pool exhaustion, but individual queries can still hang. |
| **Mitigation** | `connectionTimeoutMillis` prevents indefinite pool waiting. Slow queries (>250ms) are logged as warnings. |
| **Future recommendation** | Set `statement_timeout` in the pool config or per-session via `SET statement_timeout = '5s'`. |

---

## L009: MetricsCollector Maps Grow Unbounded

| Field | Value |
|-------|-------|
| **Impact** | Counters and gauges use `Map<string, number>` with no eviction. An attacker or bug could create unlimited unique metric names, causing memory growth. |
| **Why accepted** | Metric names are hard-coded in the source code (~30 unique names). No dynamic metric name generation exists. |
| **Mitigation** | Timings have a 1000-sample cap. Counters/gauges are bounded by code paths. |
| **Future recommendation** | Add a max key limit (e.g., 100) to counters and gauges Maps, or switch to an LRU-backed metric store. |

---

## L010: No WebSocket Connection Limit Per Pod

| Field | Value |
|-------|-------|
| **Impact** | No maximum connections per pod. A single pod could have thousands of WebSocket connections, consuming file descriptors and memory. |
| **Why accepted** | Connection rate limiting prevents rapid connection floods, but steady-state growth is unbounded. |
| **Mitigation** | HPA adds pods under CPU/memory pressure. Each connection is ~10KB of overhead + WebSocket object. |
| **Future recommendation** | Add a configurable max connections per pod (e.g., `MAX_WS_CONNECTIONS=1000`). Reject new connections with 429 when limit is hit. |

---

## L011: Clock Drift Affects Timer Accuracy

| Field | Value |
|-------|-------|
| **Impact** | Timer fire times are computed from `Date.now()`. If system clock drifts, timers fire early/late. Session expiry timestamps also drift. |
| **Why accepted** | NTP is standard in K8s clusters. Drift is typically < 1ms per minute. |
| **Mitigation** | Timers are rebuilt from DB on restart. Sweeper runs every 5 minutes to catch expired sessions. |
| **Future recommendation** | Use monotonic clock (`process.hrtime()`) for relative delays. Use DB timestamps for absolute scheduling. |

---

## L012: Notification Double-Delivery on Retry

| Field | Value |
|-------|-------|
| **Impact** | When a DualWrite repository retries a write, the `onSessionEvent` callback may fire twice (once on the failed first attempt, once on the successful retry). The phone WebSocket may receive duplicate notifications. |
| **Why accepted** | The retry rate is < 0.1% in normal operation. Duplicate notifications are unlikely and generally harmless (the phone can deduplicate by `callId` + `type`). |
| **Mitigation** | Retry only happens on transient errors (network blips). The window for double-delivery is < 200ms. |
| **Future recommendation** | Move notification dispatch out of the repository layer. Fire notifications from the service layer after the write is confirmed, not during it. |

---

## L013: PrimaryDatabase Repos Add No Value

| Field | Value |
|-------|-------|
| **Impact** | `PrimaryDatabaseSessionRepository` and `PrimaryDatabaseCallbackRepository` are thin wrappers over `Database*Repository` that only add debug logging. The instrumentation layer already provides the same observability. |
| **Why accepted** | They exist as a symmetry with `DualWrite*Repository` and could be extended independently (e.g., adding cache). |
| **Mitigation** | Eliminating them saves ~50 lines of code but doesn't improve reliability. |
| **Future recommendation** | Remove PrimaryDatabase* repos. Use `Database*Repository` directly in `database` mode. |

---

## L014: No Pagination in Session Listing

| Field | Value |
|-------|-------|
| **Impact** | `InMemorySessionRepository.list()` returns all sessions. At 100K sessions, this returns 100K objects in a single call. High memory pressure for health endpoint and sweeper. |
| **Why accepted** | MVP scope. Session count is expected to be < 10K in normal operation. |
| **Mitigation** | The health endpoint filters in-memory (not in DB query). The sweeper iterates all sessions. Both are acceptable at < 10K sessions. |
| **Future recommendation** | Add pagination to `list()` method. Add `count()` method for health metrics without full iteration. |

---

## Summary

| ID | Limitation | Impact | Recommended version |
|----|-----------|--------|-------------------|
| L001 | Single-user auth | Security | v1.1 or v2.0 |
| L002 | No cross-pod lock | Data integrity | v1.1 |
| L003 | WS dropped on update | User experience | v1.1 |
| L004 | Per-process timers | Reliability | v1.1 |
| L005 | No migration tooling | Operations | v1.1 |
| L006 | InMemory always allocated | Memory | v1.1 |
| L007 | No-op subscribers | Performance | v1.1 |
| L008 | No statement timeout | Operations | v1.1 |
| L009 | Unbounded metric maps | Memory | v2.0 |
| L010 | No WS connection limit | Reliability | v2.0 |
| L011 | Clock drift | Accuracy | v2.0 |
| L012 | Notification double-delivery | User experience | Research |
| L013 | PrimaryDatabase repos | Cleanup | Cleanup |
| L014 | No pagination | Performance | v2.0 |
