# VoiceBridge — Production Readiness Guide

## Architecture

```
┌─────────────────────────────────────────────────────┐
│                  Fastify HTTP Server                  │
│  /health  /ready  /metrics  /api/v1/*  /phone  WS    │
└──────────┬──────────────────────────────────┬────────┘
           │                                  │
     ┌─────▼──────┐                   ┌──────▼──────┐
     │   Routes    │                   │  Signaling   │
     │  (routes.ts)│                   │  (WebSocket) │
     └─────┬──────┘                   └─────────────┘
           │
     ┌─────▼──────────────────────────────────┐
     │        VoiceBridgeService               │
     │  (service.ts)                           │
     └─────┬────────────────────────┬─────────┘
           │                        │
     ┌─────▼──────┐          ┌──────▼──────┐
     │  Repository │          │  Lifecycle   │
     │  Layer      │          │  Coordinator │
     └─────┬──────┘          └──────┬───────┘
           │                        │
     ┌─────▼──────────────┐   ┌─────▼──────────┐
     │ PERSISTENCE_MODE   │   │ CleanupScheduler│
     │  memory / dual-write│   │ + SessionSweeper│
     │  database-read /    │   └────────────────┘
     │  database           │
     └─────────────────────┘
           │
     ┌─────▼─────────────────────────────────────┐
     │  MetricsCollector  │  DatabaseHealthMonitor │
     │  Instrumented Repos│  Retry Policy          │
     └───────────────────────────────────────────┘
```

---

## Startup Flow

```
1. validateConfig()          — env vars, PERSISTENCE_MODE
2. Create MetricsCollector   — operational metrics
3. Create EventBus           — pub/sub for domain events
4. Register event handlers   — notifications, presence, calls, signaling
5. Create InMemory repos     — always created (tests, fallback, Phase B)
6. Choose persistence mode:
   ┌ memory    → skip DB, use InMemory repos directly
   ├ dual-write → if DATABASE_URL: create Pool →
   │              Phase A (load DB → memory) →
   │              DualWrite repos (reads: memory, writes: memory+DB)
   ├ database-read → same as dual-write but reads from DB
   └ database  → require DATABASE_URL, create Pool →
                 Phase A (load DB → memory for Phase B) →
                 PrimaryDatabase repos (reads+writes: DB)
7. Wrap repos in Instrumented* — timing, retry, slow-query logging
8. Create VoiceBridgeService  — business logic
9. Create LifecycleCoordinator + CleanupScheduler
10. Phase B (if DB enabled)  — rebuild timer callbacks from recovered state
11. Create SessionSweeper    — periodic expired session cleanup
12. Post-recovery sweep      — immediate cleanup of downtime-expired sessions
13. DatabaseHealthMonitor     — periodic DB ping + pool checks
14. Register routes           — /health, /ready, /metrics, /api/v1/*
15. Start HTTP server         — listen on PORT
16. Mark startup complete     — /ready returns ok
```

---

## Shutdown Flow

```
SIGTERM / SIGINT
  │
  ├── Set shuttingDown flag (prevents re-entry)
  ├── Stop SessionSweeper
  ├── Stop DatabaseHealthMonitor
  ├── Stop PersistenceVerifier
  ├── Shutdown CleanupScheduler (clear all timers)
  ├── Close HTTP server (stop accepting requests)
  ├── Close WebSocket signaling server
  ├── Shutdown EventBus
  ├── Flush logs
  ├── Close database pool (await pending queries)
  ├── Record shutdown metrics
  └── process.exit(0)

Force kill after 10s if shutdown hangs.
```

Uncaught exceptions and unhandled rejections also trigger the shutdown flow.

---

## Persistence Modes

| Mode | Reads | Writes | DB Required | Use Case |
|---|---|---|---|---|
| `memory` | InMemory | InMemory | No | Local dev, tests, ephemeral |
| `dual-write` | InMemory | InMemory + DB | No | Migration, dual-validation |
| `database-read` | DB | InMemory + DB | Yes | Canary, read-from-DB testing |
| `database` | DB | DB | Yes | Production |

### Default: `dual-write`

### Switching modes

Set `PERSISTENCE_MODE` environment variable and restart:

```
PERSISTENCE_MODE=database npx tsx src/index.ts
```

### Rollback

From `database` to `dual-write`: change env, restart. Reads switch to memory.
Phase A recovery repopulates memory from DB. No data loss.

From `database` to `memory`: change env, unset `DATABASE_URL`, restart.
DB snapshot retained as backup. No reads or writes touch DB.

---

## Recovery

### Phase A — Load from Database

At startup, if a database is configured, `RecoveryManager.loadFromDatabase()` reads
all sessions and callbacks from PostgreSQL into the in-memory repositories.

### Phase B — Rebuild Timers

For each callback record, `LifecycleCoordinator.resumeCallback()` schedules
`resume:callId` and `pause-ttl:callId` timers on the `CleanupScheduler`.

For each paused session without a callback, `recoverOrphanedPause()` schedules
a `pause-ttl:callId` timer. If the TTL already expired during downtime, the
session is cancelled immediately.

### Post-Recovery Sweep

`SessionSweeper.sweep()` runs once immediately after recovery to delete sessions
whose retention period expired while the server was down.

---

## Health Endpoints

### GET /api/v1/health

```json
{
  "status": "ok",
  "version": "2.0.0",
  "timestamp": "2026-07-26T12:00:00.000Z",
  "uptime": 1234.56,
  "database": {
    "connected": true,
    "pingMs": 2,
    "poolTotal": 5,
    "poolIdle": 3,
    "poolWaiting": 0
  },
  "scheduler": { "timerCount": 3 },
  "callbacks": { "count": 2 },
  "sessions": { "active": 1, "paused": 2, "completed": 5 }
}
```

- `status`: `"ok"` if process and DB are healthy, `"degraded"` if DB is unreachable
- Rate limited: 20 requests per 10 seconds

### GET /api/v1/ready

```json
{
  "status": "ok",
  "recoveryComplete": true,
  "databaseConnected": true,
  "repositoriesInitialized": true
}
```

- Returns `"not_ready"` until startup recovery finishes
- Rate limited: 20 requests per 10 seconds

### GET /api/v1/metrics

Returns `MetricsCollector` snapshot as JSON:

```json
{
  "counters": {
    "sessions.created": 42,
    "sessions.completed": 10,
    "startup.complete": 1
  },
  "gauges": {
    "sessions.active": 1,
    "sessions.paused": 2,
    "db.pool.total": 5
  },
  "timings": {
    "session.findById": {
      "count": 100,
      "min": 0,
      "max": 5,
      "avg": 1,
      "p50": 1,
      "p95": 3,
      "p99": 5
    }
  },
  "uptime": 3600,
  "timestamp": "2026-07-26T12:00:00.000Z"
}
```

- Rate limited: 10 requests per 10 seconds
- Returns error if `MetricsCollector` not configured

---

## Metrics Collected

### Counters
| Metric | Source | Description |
|---|---|---|
| `sessions.created` | POST /calls | Total sessions created |
| `sessions.completed` | POST /calls/:callId/complete | Sessions completed |
| `sessions.cancelled` | POST /calls/:callId/cancel | Sessions cancelled |
| `callbacks.scheduled` | POST /calls/:callId/callback | Callbacks scheduled |
| `startup.complete` | Server startup | Incremented once per startup |
| `session.*.ok` | Instrumented repos | Successful session operations |
| `session.*.error` | Instrumented repos | Failed session operations |
| `repo.errors` | Instrumented repos | Total repository errors |

### Gauges
| Metric | Source | Description |
|---|---|---|
| `sessions.active` | /health | Current active sessions |
| `sessions.paused` | /health | Current paused sessions |
| `sessions.completed` | /health | Current completed sessions |
| `callbacks.count` | /health | Current callback records |
| `scheduler.timers` | /health | Active cleanup timers |
| `db.pool.total` | DatabaseHealthMonitor | DB pool total connections |
| `db.pool.idle` | DatabaseHealthMonitor | DB pool idle connections |
| `db.pool.waiting` | DatabaseHealthMonitor | DB pool waiting clients |

### Timings
| Metric | Source | Description |
|---|---|---|
| `startup.duration` | Server startup | Milliseconds to start |
| `shutdown.duration` | Server shutdown | Milliseconds to shut down |
| `db.ping` | DatabaseHealthMonitor | DB round-trip latency |
| `session.*` | Instrumented repos | Repository operation duration |
| `callback.*` | Instrumented repos | Callback repository operation duration |

---

## Deployment Checklist

### Environment Variables

Required:
- `SERVICE_TOKEN` — API auth token

Optional:
- `PORT` — HTTP port (default: 4000)
- `NODE_ENV` — `production` or `development`
- `DATABASE_URL` — PostgreSQL connection string
- `PERSISTENCE_MODE` — `memory`, `dual-write`, `database-read`, `database`
- `DB_POOL_MIN` — Min pool connections (default: 2)
- `DB_POOL_MAX` — Max pool connections (default: 10)
- `DB_POOL_IDLE_TIMEOUT` — Idle connection timeout (default: 30000)
- `DB_POOL_ACQUIRE_TIMEOUT` — Acquire timeout (default: 10000)
- `DB_VERIFICATION_INTERVAL_MS` — Persistence verification interval (default: 0 = off)
- `CORS_ALLOWED_ORIGINS` — CORS origins (default: `*`)
- `BODY_LIMIT_BYTES` — Max request body size (default: 1048576)
- `SIGNALING_*` — WebSocket signaling configuration

### Pre-flight checks

1. Run `npm run lint` — ESLint
2. Run `npm run typecheck` — TypeScript
3. Run `npm test` — Vitest unit + integration tests
4. Verify `PERSISTENCE_MODE=database` with valid `DATABASE_URL`
5. Test `/health`, `/ready`, `/metrics` endpoints respond
6. Verify startup recovery (check logs for `[RecoveryManager] loaded state from database`)
7. Verify graceful shutdown (SIGTERM, check logs for `Server shut down gracefully`)

---

## Troubleshooting

### Database connection fails on startup
- Verify `DATABASE_URL` is correct
- Check network connectivity to the database host
- Ensure `pg_hba.conf` allows connections from the application IP
- Check `PERSISTENCE_MODE` — `database` and `database-read` require `DATABASE_URL`

### Recovery reports 0 sessions but DB has data
- Check `PERSISTENCE_MODE` — `memory` mode skips DB entirely
- Check database credentials have `SELECT` access on `sessions` and `callbacks` tables
- Verify the pool configuration (min/max) is appropriate

### Slow queries logged
Repository operations taking >250ms are logged as warnings. Check:
- Database server load
- Connection pool saturation (check `db.pool.waiting` metric)
- Network latency between app and database

### Health endpoint reports `degraded`
Database is unreachable or ping failed. Check:
- Database server status
- Network connectivity
- Connection pool exhaustion (check `db.pool.waiting` gauge)

---

## Operational Runbook

### Daily checks
1. `GET /health` — verify status is `"ok"`
2. `GET /metrics` — review counters and timings
3. Check logs for `[DatabaseHealthMonitor]` warnings
4. Monitor pool utilization — should stay below 90%

### Incident: Database unavailable

1. Server degrades gracefully: `/health` returns `"degraded"`
2. Repository operations retry transient failures (up to 2 retries, exponential backoff)
3. If database is permanently down:
   - Set `PERSISTENCE_MODE=memory` and restart for uninterrupted service
   - Data will be lost on restart — recover by switching back to `database` mode when DB is available

### Incident: Server stuck

1. Send `SIGTERM` — graceful shutdown with 10s timeout
2. If shutdown hangs, process forces exit after 10s
3. Check shutdown logs for hanging component
4. Restart and verify recovery completes

### Deploying a new version

1. Set `PERSISTENCE_MODE=database` (production)
2. Start new instance with new code
3. Verify `/ready` returns `"ok"`
4. Check `/health` for database connectivity
5. Gradually shift traffic to new instance
6. Monitor metrics for anomalies
7. Keep old instance running for rollback
