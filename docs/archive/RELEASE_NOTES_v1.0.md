# Release Notes — VoiceBridge v1.0.0

**Release name:** "Solo Bridge"
**Release date:** July 26, 2026
**Package version:** 2.0.0 (manifest)
**Minimum platform:** Node.js 20 LTS, PostgreSQL 16

---

## Major Features

### Voice Call Bridge
- Create, manage, and complete AI-to-human voice call sessions
- AI message injection with automatic status transition (pending → active)
- User text message processing (speech-to-text integration point)
- Call transcript retrieval (system messages filtered)
- Call completion with result recording (summary, decision, sentiment, action items)
- Call cancellation with shorter retention (5 min vs 60 min for completed)

### Callback Scheduling
- Pause a call with delay-based automatic resumption
- Timer-based callback execution via CleanupScheduler
- Phase B timer recovery on restart (rebuilds timers from database)
- Orphaned pause detection and recovery

### Phone Registration & WebSocket Signaling
- Phone registration endpoint returns WebSocket endpoint
- Real-time push notifications over WebSocket:
  - `call_incoming`, `ai_message`, `call_ended`, `call_cancelled`
  - `callback_scheduled`, `presence.update`
- Connection rate limiting (per IP + per user message rate)
- Auto-reconnect on connection loss (Android client: 3s interval)

### WebSocket Authentication (RC-2)
- Token-based authentication on WebSocket upgrade
- Invalid/missing token → 4001 close code
- Connection replacement on duplicate registration

### REST API Authentication (RC-2)
- Bearer token validation on all routes except health/ready/metrics
- 401 UNAUTHORIZED response for unauthenticated requests

---

## Architecture Improvements

### Multi-Mode Persistence
Four persistence modes, set via `PERSISTENCE_MODE`:

| Mode | Reads | Writes | DB Required |
|------|-------|--------|-------------|
| `memory` | InMemory | InMemory | No |
| `dual-write` | InMemory | InMemory + DB | No |
| `database-read` | DB | InMemory + DB | Yes |
| `database` | DB | DB | Yes |

Default: `dual-write`

### Repository Pattern
- `SessionRepository`/`CallbackRepository` interfaces with 4 implementations each
- `Instrumented*Repository` wrapping adds timing, retry, slow-query logging
- `DualWrite*Repository` maintains consistency during migration
- `PrimaryDatabase*Repository` for production DB-only reads

### Session-Level Locking (RC-2)
- Per-callID promise-chain mutex (`withSessionLock()`)
- Prevents lost updates on concurrent `addMessage`, `scheduleCallback`, `completeCall`, `cancelCall`
- Contention logged at debug level

### Database Transactions (RC-2)
- `transaction()` method on both repository interfaces
- InMemory: synchronous execution with pass-through
- Database: `BEGIN`/`COMMIT`/`ROLLBACK` via shared `PoolClient`
- Used by `scheduleCallback()` (session save + callback save)

### Startup Recovery
- Phase A: Load all sessions and callbacks from DB into memory
- Phase B: Rebuild callback timers and orphan detection
- Post-recovery sweep: delete expired sessions

### Graceful Shutdown
- 10-component shutdown sequence with 10s force-kill timeout
- Stops acceptors, drains operations, closes pool, flushes logs
- Handles SIGTERM, SIGINT, uncaughtException, unhandledRejection

---

## Security Improvements

| Area | RC-1 | v1.0 |
|------|------|------|
| HTTP API | Solo-user pass-through | Bearer token required |
| WebSocket | No auth | Token query param |
| Readiness probe | Manual POST endpoint | Auto-computed from state |
| NetworkPolicy | Ingress only | Egress for PostgreSQL + DNS |
| Secrets exposure | Config dumped in /health | Removed |

### Security posture
- All endpoints authenticated except health/ready/metrics (K8s probes)
- SQL injection: parameterized queries via pg.Pool ($1, $2 placeholders)
- Path traversal: UUID route params (no user-controlled paths)
- Rate limiting: 100/min global, health: 20/10s, metrics: 10/10s
- CORS: configurable origins, default `*`
- Helmet CSP: comprehensive content-security-policy headers
- Docker: non-root user (1001), read-only rootfs, all capabilities dropped

---

## Reliability Improvements

| Area | RC-1 | v1.0 |
|------|------|------|
| Pool timeout | None (indefinite wait) | 10s connectionTimeoutMillis |
| Dual-write failures | Silent .catch() | Retry with metrics + logging |
| Lost updates | Race on addMessage | Session lock (promise-chain mutex) |
| Concurrent mutations | No isolation | Transaction support |
| DB health monitoring | Not implemented | 15s ping, pool metrics |

### Retry Policy
- Retry on transient errors (ECONNRESET, ETIMEDOUT, ECONNREFUSED, EPIPE, ENOTFOUND)
- Max 1 retry per operation (2 total attempts)
- Base delay: 50-100ms (jitter)
- No retry on validation, not-found, or permission errors

### Failure Modes
- DB unavailable → health degrades, in-memory operations continue
- Pool exhaustion → connectionTimeoutMillis prevents hang
- Partial write (dual-write) → retried, surviving store covers
- Pod crash → full recovery on restart (Phase A + B)
- DNS failure → process exits at startup (correct behavior)

---

## Performance Improvements

| Metric | Value |
|--------|-------|
| Operations/sec (InMemory) | 42,000 ops/sec |
| Memory per session | ~32 KB |
| Startup (no DB) | < 500ms |
| Startup (DB, 0 sessions) | < 2s |
| Startup (DB, 10K sessions) | < 10s |
| Shutdown | < 2s |
| Force-kill timeout | 10s |
| Lock duration | < 1ms |

### Load Test Results (InMemory)
```
1,600 operations in 38ms
42,105 ops/sec
Memory Δ: +1MB (32KB per op)
```

---

## Operational Improvements

### Health & Readiness
- `GET /api/v1/health` — status, DB health, pool stats, session counts, timer count
- `GET /api/v1/ready` — startup + recovery + DB readiness for K8s probes
- `GET /api/v1/metrics` — counters, gauges, timings snapshot (JSON)

### Metrics (internal)
- 6 counter types (sessions.created, sessions.completed, etc.)
- 7 gauge types (sessions.active, db.pool.total, etc.)
- 5+ timing types (startup.duration, db.ping, repository operations)
- 1000-sample cap per timing

### Logging
- pino structured JSON logging
- Levels: debug (dev), info (production)
- All routes logged with method, URL, auth context
- Session operations logged with callId, elapsed time
- DB health changes logged

### Monitoring Integration
- Prometheus scrape annotation on K8s service
- Grafana dashboard JSON provided (6 panels, 30+ queries)
- Alert rules defined (8 alerts: DB unreachable, pool exhaustion, high latency, error rate, slow queries, recovery failure, memory, CPU)

---

## Testing Summary

| Area | Count | Result |
|------|-------|--------|
| Unit/Integration tests | 48 tests, 5 files | ✅ 100% pass |
| MetricsCollector | 4 tests | ✅ |
| Retry policy | 6 tests | ✅ |
| InMemory repos | 7 + 6 = 13 tests | ✅ |
| RecoveryManager | 1 test | ✅ |
| Session lock | 5 tests | ✅ |
| Transactions | 2 tests | ✅ |
| Auth (HTTP) | 4 tests | ✅ |
| Auth (WebSocket) | 1 test | ✅ |
| Security (injection, traversal) | 2 tests | ✅ |
| Validation | 4 tests | ✅ |
| Concurrency | 1 test (50 concurrent creates) | ✅ |
| Repository invariants | 2 tests | ✅ |
| Load test | 1600 ops in 38ms | ✅ |

### Code Quality
- ESLint: 0 errors
- TypeScript strict mode: clean
- No `any` types in source

---

## Validation Summary

### RC-1 Audit (15 areas)
- Score: 61/100
- Issues found: 22 (1 P0, 3 P1, 5 P2, 6 P3, 7 P4)
- Recommendation: ❌ NO GO

### RC-2 Remediation
- All P0/P1/P2 items fixed (8 issues)
- 4 additional bugs fixed (missing sessionRepo.save() calls)
- Score: 77/100
- Recommendation: ✅ GO FOR PRODUCTION

### Phase 7 Production Validation
- 10 reports generated covering deployment, smoke tests, canary, stability, failure injection, observability, performance, risk
- Verdict: CONDITIONAL PASS

### Final Risk Register
- 22 risks identified
- 16 mitigated (11 in RC-2)
- 6 accepted or unmitigated
- No blocking risks

---

## Deployment Summary

### Docker Compose (Development/Staging)
```bash
git clone <repo>
cp backend/.env.example backend/.env  # edit SERVICE_TOKEN, DATABASE_URL
cd infra
CADDY_DOMAIN=yourdomain.com docker compose up -d
```

### Kubernetes (Production)
```bash
# 1. Create namespace
kubectl apply -f infra/k8s/01-namespace.yaml

# 2. Create secrets (SERVICE_TOKEN, DATABASE_URL)
kubectl create secret generic voicebridge-secrets --from-literal=SERVICE_TOKEN=... -n voicebridge

# 3. Apply manifests in order
kubectl apply -f infra/k8s/03-configmap.yaml
kubectl apply -f infra/k8s/04-deployment.yaml
kubectl apply -f infra/k8s/05-service.yaml
kubectl apply -f infra/k8s/06-ingress.yaml
kubectl apply -f infra/k8s/07-hpa.yaml
kubectl apply -f infra/k8s/08-pdb.yaml
kubectl apply -f infra/k8s/09-network-policy.yaml

# 4. Verify
kubectl get pods -n voicebridge -w
curl https://yourdomain.com/api/v1/health
```

### Database Setup
```sql
-- Apply schema before first deployment
psql $DATABASE_URL -f backend/src/voicebridge/repositories/schema.sql
```

---

## Known Limitations

See [KNOWN_LIMITATIONS.md](./KNOWN_LIMITATIONS.md) for the full register.

**Key:**
- L001: Single-user auth (SERVICE_TOKEN shared)
- L002: No cross-pod session lock
- L003: WebSocket dropped on rolling update
- L004: Per-process timers
- L005: No migration tooling

---

## Upgrade Guidance

### From pre-v1.0 prototypes

1. **Auth:** Generate a random SERVICE_TOKEN (openssl rand -hex 32)
2. **Persistence:** Set `PERSISTENCE_MODE=database` with `DATABASE_URL`
3. **Schema:** Apply schema.sql before starting the new version
4. **Remove POST /ready calls:** Readiness is auto-computed
5. **Replace env vars:** Drop JWT_SECRET, POSTGRES_PASSWORD etc. — only SERVICE_TOKEN + DATABASE_URL required

### From v1.0 to v1.1 (expected)

- Schema migrations will be versioned (migration tooling TBD)
- API will remain backward-compatible
- Breaking changes will be announced in release notes

---

## Files Changed Since RC-1

| File | Change |
|------|--------|
| `src/index.ts` | connectionTimeoutMillis, metrics wired to DualWrite |
| `src/routes.ts` | Auth middleware, auto-readiness, removed POST /ready |
| `src/signaling/server.ts` | WS token auth |
| `src/voicebridge/service.ts` | Missing sessionRepo.save() fixes, withSessionLock() |
| `src/voicebridge/session-lock.ts` | NEW — per-call promise-chain mutex |
| `src/voicebridge/repositories/session-repository.ts` | transaction() on interface |
| `src/voicebridge/repositories/callback-repository.ts` | transaction() on interface |
| `src/voicebridge/repositories/db-session-repository.ts` | query() helper, transaction() impl |
| `src/voicebridge/repositories/db-callback-repository.ts` | query() helper, transaction() impl |
| `src/voicebridge/repositories/dual-write-session-repository.ts` | Retry + metrics |
| `src/voicebridge/repositories/dual-write-callback-repository.ts` | Retry + metrics |
| `infra/k8s/09-network-policy.yaml` | Egress for PostgreSQL |
| Various test files | +48 tests total |
