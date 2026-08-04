# v1.1 Backlog — VoiceBridge

## Bug Fixes

### B-001: Add Statement Timeout to PostgreSQL Pool
- **Description:** No `statement_timeout` is set on the pg.Pool. A slow or hanging query can block a connection indefinitely. Set `SET statement_timeout = '5s'` on pool connect.
- **Source:** L008, TD-08
- **Priority:** Medium
- **Effort:** 15 minutes
- **Milestone:** v1.0.1

### B-002: Add Per-Pod WebSocket Connection Limit
- **Description:** No maximum WebSocket connections per pod. A single pod could accumulate thousands of connections, exhausting file descriptors and memory.
- **Source:** L010, TD-12
- **Priority:** Medium
- **Effort:** 4 hours
- **Milestone:** v1.1

### B-003: Implement WebSocket Drain on Shutdown
- **Description:** When a pod terminates (rolling update, scale-down), all active WebSocket connections are dropped. Notify clients with a `reconnect` event before closing.
- **Source:** L003, TD-17
- **Priority:** Critical
- **Effort:** 4 hours
- **Milestone:** v1.1

## New Features

### F-001: Cross-Pod Session Lock (pg_advisory_lock)
- **Description:** Implement distributed session locking using PostgreSQL advisory locks to prevent concurrent session mutations across pods.
- **Source:** L002, TD-16
- **Priority:** Critical
- **Effort:** 16 hours
- **Milestone:** v1.1

### F-002: Database Migration Tooling
- **Description:** Integrate Knex.js or node-pg-migrate with versioned migrations. Add CI check to ensure migrations run before deployment.
- **Source:** L005, TD-13
- **Priority:** High
- **Effort:** 8 hours
- **Milestone:** v1.1

### F-003: Session List Pagination
- **Description:** Add pagination to `SessionRepository.list()` with `offset` and `limit` parameters. Add `count()` method to repo interface.
- **Source:** L014, TD-09, TD-14
- **Priority:** High
- **Effort:** 6 hours
- **Milestone:** v1.1

### F-004: InMemory Skip Optimization for db-only Mode
- **Description:** Add `PERSISTENCE_MODE=db-only` that skips InMemory repository creation and Phase A loading, saving ~32KB/session in DB mode.
- **Source:** L006, TD-10
- **Priority:** High
- **Effort:** 8 hours
- **Milestone:** v1.1

### F-005: Multi-User JWT Authentication
- **Description:** Replace single-token SERVICE_TOKEN with JWT-based authentication. Add user registration, token refresh, and per-user sessions.
- **Source:** L001
- **Priority:** High
- **Effort:** 40 hours
- **Milestone:** v2.0

### F-006: Health Endpoint DB Count
- **Description:** Use database `SELECT count(*)` instead of iterating all in-memory sessions for health endpoint metrics.
- **Source:** TD-15
- **Priority:** Medium
- **Effort:** 2 hours
- **Milestone:** v1.1

### F-007: Move phoneConnections to Injected Service
- **Description:** Move the `phoneConnections` module-level global Map to an injected service for testability.
- **Source:** TD-18
- **Priority:** Medium
- **Effort:** 2 hours
- **Milestone:** v1.1

## Documentation

### D-001: Fix API_SPEC.md to Match Actual Routes
- **Description:** The API specification describes a different system (includes `/ready`, `/recovery/complete` endpoints that were removed). Align with actual `routes.ts`.
- **Source:** TD-03
- **Priority:** High
- **Effort:** 2 hours
- **Milestone:** v1.0.1

### D-002: Fix DEPLOYMENT_GUIDE.md Env Vars
- **Description:** Remove references to nonexistent environment variables (`POSTGRES_PASSWORD`, `COTURN_SECRET`, etc.).
- **Source:** TD-04
- **Priority:** Medium
- **Effort:** 1 hour
- **Milestone:** v1.0.1

### D-003: Fix ARCHITECTURE.md to Describe Actual System
- **Description:** Remove outdated references (Suga, emotion enrichment) and add DB persistence modes, event bus, and current architecture.
- **Source:** TD-05
- **Priority:** Medium
- **Effort:** 1 hour
- **Milestone:** v1.0.1

### D-004: Fix DATABASE_GUIDE.md to Use pg.Pool
- **Description:** Document uses Knex.js which is not a project dependency. Replace with pg.Pool configuration.
- **Source:** TD-06
- **Priority:** Low
- **Effort:** 30 minutes
- **Milestone:** v1.0.1

## Cleanup

### C-001: Remove PrimaryDatabase Repos
- **Description:** Remove `PrimaryDatabaseSessionRepository` and `PrimaryDatabaseCallbackRepository`. Use `Database*Repository` directly in database mode.
- **Source:** L013, TD-02
- **Priority:** Low
- **Effort:** 30 minutes
- **Milestone:** v1.0.1

### C-002: Remove No-Op Event Subscribers
- **Description:** Remove 14 log-only event subscribers that provide no business value. Dispatch overhead adds <0.01ms per event but is unnecessary code.
- **Source:** L007, TD-11
- **Priority:** Medium
- **Effort:** 2 hours
- **Milestone:** v1.1

## Research

### R-001: Notification Deduplication Strategy
- **Description:** Investigate whether notification dispatch should move out of the repository layer to prevent double-delivery on retry.
- **Source:** L012, TD-26
- **Priority:** Low
- **Effort:** Research
- **Milestone:** v1.1

### R-002: Distributed Lock Technology Comparison
- **Description:** Compare pg_advisory_lock vs Redis-based distributed locking for latency, operational complexity, and reliability.
- **Source:** TD-27
- **Priority:** Critical (F-001 dependency)
- **Effort:** Research
- **Milestone:** v1.1

### R-003: Monotonic Clock for Timer Scheduling
- **Description:** Evaluate whether `process.hrtime()` can replace `Date.now()` for relative timer delays without breaking absolute DB timestamp scheduling.
- **Source:** L011, TD-28
- **Priority:** Low
- **Effort:** Research
- **Milestone:** v1.1

### R-004: MetricsCollector LRU Eviction
- **Description:** Evaluate adding a max key limit (100) or LRU-backed metric store for counters and gauges Maps.
- **Source:** L009, TD-29
- **Priority:** Low
- **Effort:** Research
- **Milestone:** v2.0

### R-005: Schema Evolution Tool Evaluation
- **Description:** Compare Knex.js, node-pg-migrate, and raw SQL scripts for schema migration. Evaluate rollback support and CI integration.
- **Source:** TD-30
- **Priority:** High (F-002 dependency)
- **Effort:** Research
- **Milestone:** v1.1

### R-006: iOS App Re-activation
- **Description:** Evaluate whether to rebuild the iOS app, and which framework (SwiftUI vs React Native vs Flutter).
- **Source:** [Unreleased]
- **Priority:** Low
- **Effort:** Research
- **Milestone:** v1.1 or v2.0

## Backlog Summary

| Category | Count | Total Effort |
|----------|-------|-------------|
| Bug Fixes | 3 | ~8.25h |
| New Features | 6 | ~78h |
| Documentation | 4 | ~4.5h |
| Cleanup | 2 | ~2.5h |
| Research | 6 | Ongoing |
| **Total** | **21** | **~93h** |
