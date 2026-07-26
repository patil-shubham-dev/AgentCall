# Initial GitHub Issues — VoiceBridge v1.0.0

---

## Bug Issues

### BUG-001: Session Repository List Returns All Records (No Pagination)
- **Description:** `InMemorySessionRepository.list()` returns every session without pagination. At 100K sessions this returns 100K objects — high memory pressure for health endpoints and sweeper.
- **Acceptance Criteria:**
  - `list()` accepts `offset` and `limit` parameters
  - Default limit of 1000 records per call
  - `count()` method added to repository interface
  - Health endpoint uses count instead of list
- **Labels:** `bug`, `backend`, `performance`
- **Priority:** High
- **Milestone:** v1.1

### BUG-002: WebSocket Connections Dropped During Rolling Update
- **Description:** When a pod terminates during rolling update, all WebSocket connections are closed without notification. Clients must wait for reconnect timer to restore connection.
- **Acceptance Criteria:**
  - SIGTERM triggers draining: existing connections notified with `{"type": "reconnect", "url": "..."}`
  - New connections rejected with 503 during drain
  - Connection close delayed up to `GRACEFUL_SHUTDOWN_TIMEOUT`
- **Labels:** `bug`, `backend`, `ux`
- **Priority:** Critical
- **Milestone:** v1.1

### BUG-003: No Statement Timeout on PostgreSQL Pool
- **Description:** Pool connections lack `statement_timeout`. A slow query can block a connection indefinitely, potentially causing pool exhaustion.
- **Acceptance Criteria:**
  - `SET statement_timeout = '5s'` executed on new connections
  - Timeout errors logged with query context
  - Configurable via `DATABASE_STATEMENT_TIMEOUT` env var
- **Labels:** `bug`, `backend`, `database`
- **Priority:** Medium
- **Milestone:** v1.0.1

### BUG-004: Per-Process Timers Lost on Pod Termination
- **Description:** Callback timers (`setTimeout`) are per-process. If the scheduling pod terminates before the timer fires, the timer is lost until Phase B recovery runs on restart.
- **Acceptance Criteria:**
  - Timer recovery window documented
  - Distributed timer service evaluated (R-002)
  - Timer gap metrics exposed in health endpoint
- **Labels:** `bug`, `backend`, `reliability`
- **Priority:** High
- **Milestone:** v1.1

### BUG-005: Notification Double-Delivery on Repository Retry
- **Description:** When DualWrite repository retries a write, `onSessionEvent` may fire twice (once for failed attempt, once for successful retry). Phone may receive duplicate notifications.
- **Acceptance Criteria:**
  - Idempotency key added to notification events
  - Client-side deduplication documented in phone contract
  - Investigation: should dispatch move out of repository layer?
- **Labels:** `bug`, `backend`, `mobile`
- **Priority:** Low
- **Milestone:** Research

---

## Documentation Issues

### DOC-001: API_SPEC.md Does Not Match Actual Routes
- **Description:** The API specification describes endpoints (`/ready`, `/recovery/complete`) that were removed from `routes.ts`. It also describes a different persistence architecture than what is implemented.
- **Acceptance Criteria:**
  - Every endpoint in API_SPEC.md exists in `routes.ts`
  - Every endpoint in `routes.ts` is documented in API_SPEC.md
  - Removal of POST /ready and POST /recovery/complete from docs
- **Labels:** `documentation`, `backend`
- **Priority:** High
- **Milestone:** v1.0.1

### DOC-002: DEPLOYMENT_GUIDE.md References Nonexistent Env Vars
- **Description:** Documents reference `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `COTURN_SECRET` which were removed. Operators following the guide will be confused.
- **Acceptance Criteria:**
  - Remove all references to removed env vars
  - Update to match current `.env.example`
  - Add PERSISTENCE_MODE documentation
- **Labels:** `documentation`, `ops`
- **Priority:** Medium
- **Milestone:** v1.0.1

### DOC-003: ARCHITECTURE.md Describes Different System
- **Description:** References removed components (Suga, emotion enrichment, WebRTC). Does not describe current event bus, DB persistence modes, or session lifecycle.
- **Acceptance Criteria:**
  - Remove Suga/emotion enrichment references
  - Add event bus, persistence modes, session lifecycle sections
  - Cross-link to SYSTEM_ARCHITECTURE.md as detailed reference
- **Labels:** `documentation`, `backend`
- **Priority:** Medium
- **Milestone:** v1.0.1

### DOC-004: Missing Screenshots in README
- **Description:** README has "Screenshots coming soon" placeholder but no actual screenshots. First-time visitors cannot see the Android app UI.
- **Acceptance Criteria:**
  - At least one Android app screenshot added
  - Backend startup log screenshot (optional)
  - Screenshots stored in `docs/screenshots/`
- **Labels:** `documentation`, `frontend`
- **Priority:** Medium
- **Milestone:** v1.0.1

### DOC-005: No Contribution Triage Guide
- **Description:** No document explaining how maintainers should triage issues, review PRs, or manage the backlog. New maintainers have no reference.
- **Acceptance Criteria:**
  - Triage process documented (see MAINTAINER_GUIDE.md)
  - Label definitions and usage documented
  - Response time SLAs defined
- **Labels:** `documentation`, `community`
- **Priority:** Medium
- **Milestone:** v1.0.1

---

## Security Issues

### SEC-001: Single-User Authentication (No Multi-User Support)
- **Description:** All clients share a single SERVICE_TOKEN. No user isolation, no roles, no per-user sessions. This prevents multi-tenant deployments.
- **Acceptance Criteria:**
  - JWT-based authentication with per-user tokens
  - RBAC with admin and user roles
  - Token refresh mechanism with rotation
  - Backward compatible migration path from SERVICE_TOKEN
- **Labels:** `security`, `enhancement`, `backend`
- **Priority:** High
- **Milestone:** v2.0

### SEC-002: No Rate Limiting on WebSocket Connections
- **Description:** No maximum WebSocket connections per pod. A malicious or buggy client could exhaust pod resources by opening unlimited connections.
- **Acceptance Criteria:**
  - Configurable `MAX_WS_CONNECTIONS` (default 1000)
  - New connections rejected with 429 when limit reached
  - Metric exposed for WebSocket connection count
- **Labels:** `security`, `enhancement`, `backend`
- **Priority:** Medium
- **Milestone:** v1.1

### SEC-003: Unbounded MetricsCollector Maps
- **Description:** Counters and gauges use `Map<string, number>` with no eviction. An attacker or bug could create unlimited unique metric names, causing memory growth.
- **Acceptance Criteria:**
  - Max key limit (100) added to counters and gauges
  - LRU-backed metric store evaluated
  - Metric name validation on record
- **Labels:** `security`, `backend`, `performance`
- **Priority:** Low
- **Milestone:** v2.0

---

## Performance Issues

### PERF-001: InMemory Repos Always Allocated in Database Mode
- **Description:** Even in `database` persistence mode, InMemory repos are created and populated (Phase A recovery). Memory overhead is ~32KB per session — ~320MB at 10K sessions.
- **Acceptance Criteria:**
  - `PERSISTENCE_MODE=db-only` skips InMemory creation
  - Phase A loading skipped in db-only mode
  - Dual-write mode continues to populate both stores
- **Labels:** `performance`, `enhancement`, `backend`
- **Priority:** High
- **Milestone:** v1.1

### PERF-002: Full Table Scans on Session Recovery and Health
- **Description:** Phase A does a full table scan of sessions and callbacks. Health endpoint iterates all in-memory sessions. Both are O(n) and don't scale past 100K sessions.
- **Acceptance Criteria:**
  - Pagination added to DB reads
  - Health endpoint uses `SELECT count(*)` instead of full iteration
  - Phase A load time metrics exposed
- **Labels:** `performance`, `backend`, `database`
- **Priority:** Medium
- **Milestone:** v1.1

---

## Enhancement Issues

### ENH-001: Cross-Pod Session Lock (Distributed Locking)
- **Description:** `withSessionLock()` uses a per-process promise-chain mutex. Two pods can mutate the same DB session concurrently. Implement PostgreSQL advisory lock for cross-pod safety.
- **Acceptance Criteria:**
  - `pg_advisory_lock` acquired before session mutations
  - Lock released on transaction commit or rollback
  - Timeout configurable (default 5s)
  - Metrics exposed for lock acquisition and contention
- **Labels:** `enhancement`, `backend`, `database`
- **Priority:** Critical
- **Milestone:** v1.1

### ENH-002: Database Migration Tooling
- **Description:** Schema is defined in a static SQL file. No migration scripts, version tracking, or rollback. Every schema change is risky.
- **Acceptance Criteria:**
  - Knex.js or node-pg-migrate integrated
  - Migrations versioned with timestamp prefix
  - CI check: migrations run before deployment
  - Rollback procedure documented
- **Labels:** `enhancement`, `backend`, `devops`
- **Priority:** High
- **Milestone:** v1.1

### ENH-003: WebSocket Drain on Graceful Shutdown
- **Description:** No drain mechanism during shutdown. Connections are abruptly closed. Implement graceful drain with reconnect notification.
- **Acceptance Criteria:**
  - SIGTERM triggers drain sequence
  - Existing connections receive `reconnect` event
  - New connections rejected with 503
  - Drain timeout configurable
- **Labels:** `enhancement`, `backend`, `ux`
- **Priority:** Critical
- **Milestone:** v1.1

### ENH-004: Multi-User JWT Authentication
- **Description:** Replace single SERVICE_TOKEN with JWT-based multi-user authentication supporting registration, login, and role-based access.
- **Acceptance Criteria:**
  - User registration endpoint (`POST /api/v1/auth/register`)
  - Login endpoint returning access + refresh tokens
  - JWT middleware replacing current auth
  - Admin and user roles
  - Token revocation support
- **Labels:** `enhancement`, `security`, `backend`
- **Priority:** High
- **Milestone:** v2.0

---

## Developer Experience Issues

### DX-001: Move phoneConnections to Injected Service
- **Description:** `phoneConnections` is a module-level global Map. This prevents mock injection in tests and makes the module harder to reason about.
- **Acceptance Criteria:**
  - `PhoneConnectionService` class with injected dependencies
  - Module-level global replaced with service instance
  - Existing tests updated to use mock service
- **Labels:** `enhancement`, `backend`, `testing`
- **Priority:** Low
- **Milestone:** v1.1

### DX-002: Add Typecheck Script to CI Pipeline
- **Description:** CI pipeline does not include `tsc --noEmit`. Type errors can reach production.
- **Acceptance Criteria:**
  - `npm run typecheck` script added to `package.json`
  - CI pipeline runs typecheck after lint
  - Pipeline fails on type errors
- **Labels:** `enhancement`, `devops`, `testing`
- **Priority:** Medium
- **Milestone:** v1.0.1

### DX-003: No Local Development Database Setup Script
- **Description:** No script to provision a local PostgreSQL database for development. Developers must manually create and configure the database.
- **Acceptance Criteria:**
  - `npm run db:setup` script for PostgreSQL provisioning
  - Docker Compose profile for dev database
  - Seed data script for test scenarios
- **Labels:** `enhancement`, `devops`, `documentation`
- **Priority:** Medium
- **Milestone:** v1.1

---

## Testing Issues

### TEST-001: No Integration Tests for Database Layer
- **Description:** Database repository tests use in-memory implementations. No tests verify actual PostgreSQL query behavior, connection management, or migration execution.
- **Acceptance Criteria:**
  - Integration test suite for Database*Repository classes
  - Test PostgreSQL via Docker Compose
  - CI runs integration tests when database service is available
- **Labels:** `testing`, `backend`, `database`
- **Priority:** Medium
- **Milestone:** v1.1

### TEST-002: No Load Test for Multi-Pod Scenarios
- **Description:** Load testing (42K ops/sec) was done on a single pod. No testing validates cross-pod behavior, lock contention, or WebSocket drain under load.
- **Acceptance Criteria:**
  - Multi-pod load test using Docker Compose scale
  - Lock contention metrics under concurrent load
  - WebSocket drain validated during rolling update scenario
- **Labels:** `testing`, `infra`, `performance`
- **Priority:** Low
- **Milestone:** v1.1

---

## Infrastructure Issues

### INFRA-001: Missing Prometheus Metrics Endpoint
- **Description:** Metrics are returned as JSON via health endpoint. No Prometheus-format endpoint for direct scrape by monitoring systems.
- **Acceptance Criteria:**
  - `/metrics` endpoint returns Prometheus text format
  - All current metrics (counters, gauges, timings) mapped to Prometheus types
  - Histogram for request duration
  - No additional dependencies required
- **Labels:** `enhancement`, `infra`, `monitoring`
- **Priority:** Medium
- **Milestone:** v2.0

### INFRA-002: No Redis Dependency Yet Blocks v1.1 Features
- **Description:** Several v1.1 features (distributed timers, WS session handoff) require Redis. There is no Redis service defined in Docker Compose or K8s configs.
- **Acceptance Criteria:**
  - Redis service added to `docker-compose.yml`
  - Redis Deployment + Service in K8s manifests
  - Health check for Redis connection on startup
- **Labels:** `infra`, `devops`
- **Priority:** Medium
- **Milestone:** v1.1

---

## Summary

| Category | Count |
|----------|-------|
| Bug | 5 |
| Documentation | 5 |
| Security | 3 |
| Performance | 2 |
| Enhancement | 4 |
| Developer Experience | 3 |
| Testing | 2 |
| Infrastructure | 2 |
| **Total** | **26** |
