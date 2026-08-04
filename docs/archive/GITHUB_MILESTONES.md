# GitHub Milestones — VoiceBridge

## v1.0.1 — Quick Fix Release

### Objectives
- Fix documentation inaccuracies that could mislead operators
- Add statement timeout to prevent hung DB connections
- Remove dead code (PrimaryDatabase repos)

### Target Issues
- D-001: Fix API_SPEC.md to match routes (2h)
- D-002: Fix DEPLOYMENT_GUIDE.md env vars (1h)
- D-003: Fix ARCHITECTURE.md description (1h)
- D-004: Fix DATABASE_GUIDE.md pg.Pool (30min)
- B-001: Add statement timeout to pg.Pool (15min)
- C-001: Remove PrimaryDatabase repos (30min)

### Expected Deliverables
- Accurate API spec, deployment guide, architecture doc
- Statement_timeout=5s on all DB connections
- Cleaner codebase without PrimaryDatabase wrappers

### Effort
~5.25 hours

---

## v1.0.2 — Stability Patch

### Objectives
- Improve operational reliability
- Add WebSocket connection limits
- Clean up logging overhead

### Target Issues
- B-002: Per-pod WebSocket connection limit (4h)
- C-002: Remove no-op event subscribers (2h)
- B-003: WebSocket drain on shutdown (4h)

### Expected Deliverables
- Configurable MAX_WS_CONNECTIONS per pod
- WebSocket drain with reconnect notification on SIGTERM
- Cleaner startup with no-op subscribers removed

### Effort
~10 hours

### Dependencies
- v1.0.1 (documentation must be accurate first)

---

## v1.1 — Production Hardening

### Objectives
- Cross-pod data integrity with distributed locking
- Production operations with schema migration tooling
- Scale to 100K+ sessions with pagination and InMemory skip
- Complete R-002 (lock tech comparison) and R-005 (schema tool evaluation)

### Target Issues
- F-001: Cross-pod session lock via pg_advisory_lock (16h)
- F-002: Database migration tooling (8h)
- F-003: Session list pagination + count() (6h)
- F-004: InMemory skip for db-only mode (8h)
- F-006: Health endpoint use DB count (2h)
- F-007: Move phoneConnections to injected service (2h)

### Expected Deliverables
- Distributed session locking with pg_advisory_lock
- Versioned database migrations with CI validation
- Paginated session listing and count-based health metrics
- 32KB/session memory savings in database-only mode

### Effort
~42 hours

### Dependencies
- R-002 (lock tech research) — must complete before F-001
- R-005 (schema tool research) — must complete before F-002

---

## v1.2 — Multi-User & Notifications

### Objectives
- Replace single-token auth with JWT
- Add notification service for event subscribers
- Notification deduplication

### Target Issues
- R-001: Notification deduplication strategy
- F-005: Multi-user JWT authentication (40h)

### Expected Deliverables
- JWT-based auth with user registration and token refresh
- RBAC with admin and user roles
- Notification service that replaces no-op subscribers
- Deduplication guarantee for client notifications

### Effort
~40h+

### Dependencies
- v1.1 (cross-pod lock and pagination must be in place)

---

## v2.0 — Distributed Architecture

### Objectives
- Distributed timer service (Redis-based)
- Stable WebSocket connection migration
- Metrics aggregation and Prometheus endpoint
- iOS app re-activation
- Pagination in DB queries at scale

### Target Issues
- TD-20: Distributed timer service (40h)
- TD-24: WS connection migration (40h)
- TD-22: Prometheus metrics endpoint (16h)
- TD-23: DB query pagination (16h)
- TD-25: Circuit breaker for DB dual-write (8h)

### Expected Deliverables
- Redis-backed timer service replacing per-process setTimeout
- Zero-downtime WebSocket migration between pods
- Prometheus-format metrics for Grafana dashboards
- iOS app (SwiftUI or React Native)
- Production-ready distributed deployment

### Effort
~120h+

### Dependencies
- v1.1 (foundation for distributed features)
- Redis infrastructure in deployment stack

---

## Milestone Timeline

```
v1.0.1 (5h) ──→ v1.0.2 (10h) ──→ v1.1 (42h) ──→ v1.2 (40h) ──→ v2.0 (120h+)
   Docs fixes        Stability        Production       Multi-user      Distributed
   Statement t/o     WS limit         Hardening        JWT auth        Architecture
   Dead code rm      WS drain         Migration        Notifications   WS migration
                                      Pagination                       Prometheus
                                      Cross-pod lock                   iOS app
```

## Total Effort Estimate

| Milestone | Effort | Target |
|-----------|--------|--------|
| v1.0.1 | ~5h | Month 1 |
| v1.0.2 | ~10h | Month 2 |
| v1.1 | ~42h | Quarter 2 |
| v1.2 | ~40h | Quarter 3 |
| v2.0 | ~120h+ | Year 2 |
