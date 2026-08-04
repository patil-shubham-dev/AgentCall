# VoiceBridge — Architecture Baseline v1.0.0

> Permanent reference architecture. Documents the implemented system only. No future ideas.

---

## System Overview

VoiceBridge is a single-process Node.js application that provides a voice-call bridge between AI agents and human users. It runs as a monolithic service with a REST API + WebSocket signaling server, backed by PostgreSQL (optional) for persistence.

### Design Philosophy

- **Single-service architecture** — all components in one process. No microservices, no sidecars.
- **Repository pattern** — persistence is abstracted behind interfaces, enabling in-memory, database, and dual-write modes.
- **Event-driven internally** — domain events (call created, message added, etc.) are published on an internal EventBus. No external message broker.
- **Single-token auth** — SERVICE_TOKEN authenticates all requests (HTTP + WebSocket). No multi-user, no RBAC, no OAuth.
- **No external dependencies** beyond PostgreSQL. No Redis, no message queue, no service mesh.

### High-Level Architecture

```
┌──────────────────────────────────────────────────────────┐
│                     Fastify HTTP Server                    │
│  port 4000 — /api/v1/* (REST)  /phone (WS upgrade)       │
└──────────────────────────┬───────────────────────────────┘
                           │
              ┌────────────┴────────────┐
              │     Route Handlers       │
              │     (routes.ts)          │
              │  Auth middleware         │
              │  Input validation        │
              │  Metrics collection      │
              └────────────┬────────────┘
                           │
              ┌────────────┴────────────┐
              │   VoiceBridgeService     │
              │   (service.ts)           │
              │   Business logic         │
              │   Session lock wrapper   │
              │   Phone notification     │
              └────────────┬────────────┘
                           │
        ┌──────────────────┼──────────────────┐
        │                  │                  │
   ┌────┴─────┐      ┌────┴─────┐      ┌────┴─────┐
   │ Session  │      │ Callback  │      │ Lifecycle │
   │ Repos    │      │ Repos     │      │ Coord.    │
   └────┬─────┘      └────┬─────┘      └────┬─────┘
        │                  │                  │
   ┌────┴──────────────────┴──────────────────┴────┐
   │         Persistence Layer                      │
   │  InMemory | DualWrite | PrimaryDatabase        │
   │  + Instrumented (timing, retry, slow-query)    │
   └────────────────────┬──────────────────────────┘
                        │
              ┌─────────┴─────────┐
              │   PostgreSQL 16    │
              │   (optional DB)    │
              └───────────────────┘
```

---

## Folder Structure

```
backend/src/
├── index.ts                           # Entry point, startup/shutdown orchestration
├── routes.ts                          # HTTP route definitions + auth middleware
├── common/
│   ├── config.ts                      # Environment variable loading + validation
│   ├── logger.ts                      # pino logger instance
│   ├── metrics-collector.ts           # In-process metrics (counters, gauges, timings)
│   ├── retry.ts                       # Transient-failure retry with exponential backoff
│   ├── cleanup-scheduler.ts           # setTimeout-based timer scheduler with shutdown
│   ├── db-health-monitor.ts           # Periodic PostgreSQL ping + pool metrics
│   └── types.ts                       # CallPriority, CallReason
├── event-bus/
│   ├── index.ts                       # EventBus re-exports
│   ├── bus.ts                         # DefaultEventBus implementation
│   ├── types.ts                       # Event, EventHandler, Subscription types
│   ├── registry.ts                    # Handler registry
│   ├── publisher.ts                   # Publish helper
│   ├── dispatcher.ts                  # Handler dispatch (sync, microtask)
│   ├── errors.ts                      # PublishHandlerError
│   └── hooks.ts                       # Before/after/error hook types + logger hooks
├── signaling/
│   └── server.ts                      # WebSocket server on /phone with rate-limiting + token auth
└── voicebridge/
    ├── types.ts                       # VoiceCallSession, VoiceMessage, CreateCallInput, etc.
    ├── service.ts                     # VoiceBridgeService — all business logic
    ├── session-lock.ts                # Per-callID promise-chain mutex
    ├── sweeper.ts                     # Periodic expired-session deletion (5min interval)
    ├── coordinator.ts                 # DeletionCoordinator for stale session removal
    ├── lifecycle-coordinator.ts       # Callback timer scheduling + resumption
    ├── recovery-manager.ts            # Phase A (DB→memory load) + Phase B (timer rebuild)
    ├── repositories/
    │   ├── index.ts                   # Re-exports all repos
    │   ├── session-repository.ts      # SessionRepository interface
    │   ├── callback-repository.ts     # CallbackRepository interface
    │   ├── db-session-repository.ts   # DatabaseSessionRepository (pg.Pool)
    │   ├── db-callback-repository.ts  # DatabaseCallbackRepository (pg.Pool)
    │   ├── dual-write-session-repo.ts # DualWriteSessionRepository (memory + DB writes)
    │   ├── dual-write-callback-repo.ts# DualWriteCallbackRepository (memory + DB writes)
    │   ├── primary-db-session-repo.ts # PrimaryDatabaseSessionRepository (DB writes only)
    │   ├── primary-db-callback-repo.ts# PrimaryDatabaseCallbackRepository (DB writes only)
    │   ├── instrumented-session-repo.ts # Timing + retry + slow-query wrapper
    │   ├── instrumented-callback-repo.ts # Timing + retry + slow-query wrapper
    │   ├── verifier.ts               # PersistenceVerifier (compares memory vs DB)
    │   ├── burn-in.ts                # Startup burn-in verification
    │   └── errors.ts                 # Repository-specific errors
    ├── calls/                        # Call domain event publishers + subscribers (all log-only)
    ├── notifications/                # Notification domain event publishers + subscribers
    ├── presence/                     # Presence domain event publishers + subscribers
    └── signaling/                    # Signaling domain event publishers + subscribers
```

---

## Dependency Graph

```
index.ts
  ├── config.ts (env vars, validation)
  ├── logger.ts (pino)
  ├── metrics-collector.ts
  ├── db-health-monitor.ts
  ├── retry.ts
  ├── cleanup-scheduler.ts
  ├── routes.ts
  │   ├── config.ts
  │   ├── logger.ts
  │   └── service.ts (voicebridge)
  ├── signaling/server.ts
  │   ├── config.ts
  │   ├── logger.ts
  │   └── service.ts (registerPhone, notifyPhone)
  ├── event-bus/* (DefaultEventBus, hooks, subscribers)
  │   └── voicebridge/*/subscribers.ts (all log-only)
  ├── voicebridge/service.ts
  │   ├── session-lock.ts
  │   ├── repositories/* (interfaces + implementations)
  │   ├── lifecycle-coordinator.ts
  │   └── event publishers (calls, notifications, presence)
  ├── voicebridge/recovery-manager.ts
  │   ├── db-session-repository.ts
  │   ├── db-callback-repository.ts
  │   ├── in-memory repos
  │   └── lifecycle-coordinator.ts
  ├── sweeper.ts
  │   ├── session-repository (instrumented)
  │   └── coordinator.ts
  └── coordinator.ts
```

---

## Startup Flow

```
1. validateConfig()
   ├── SERVICE_TOKEN must be set
   ├── PERSISTENCE_MODE must be one of [memory, dual-write, database-read, database]
   └── If mode is database or database-read, DATABASE_URL must be set

2. Create MetricsCollector
3. Create EventBus + register global hooks (logger hooks for before/after/error)
4. Register domain event subscribers (4 modules, 14 handlers — all log-only)
   ├── notifications (5 handlers)
   ├── presence (3 handlers)
   ├── calls (4 handlers)
   └── signaling (2 handlers)

5. Create InMemorySessionRepository + InMemoryCallbackRepository (always created)

6. Choose persistence mode:
   ┌─────────────────────────────────────────────────────────────────┐
   │ memory:       Use InMemory repos directly. Skip DB entirely.    │
   │ dual-write:   If DATABASE_URL set:                              │
   │                 Create pg.Pool                                   │
   │                 Phase A: RecoveryManager.loadFromDatabase()      │
   │                 Wrap in DualWrite* repos (reads: memory,         │
   │                   writes: memory+DB)                             │
   │               If no DATABASE_URL: fall back to memory mode       │
   │ database-read: Same as dual-write but readFromDb=true            │
   │ database:     Require DATABASE_URL.                              │
   │                 Create pg.Pool                                   │
   │                 Phase A: RecoveryManager.loadFromDatabase()      │
   │                 Wrap in PrimaryDatabase* repos (DB reads+writes) │
   └─────────────────────────────────────────────────────────────────┘

7. Wrap repos in InstrumentedSessionRepository/InstrumentedCallbackRepository
   (adds timing recording, retry on transient failure, slow-query logging)

8. Create VoiceBridgeService (inject sessionRepo, callbackRepo)

9. Create LifecycleCoordinator + CleanupScheduler

10. Phase B (if DB mode):
    ├── RecoveryManager.rebuildTimers()
    │   For each callback record in InMemoryCallbackRepository:
    │     LifecycleCoordinator.resumeCallback(userId, callId, delayMinutes, resumeAt)
    │   For each paused session without a callback (orphan):
    │     schedule pause-ttl timer, or cancel if TTL already expired
    └──

11. Create SessionSweeper (5-minute interval, expired session deletion)
    ├── If DB mode: run immediate post-recovery sweep
    └── sessionSweeper.start()

12. DatabaseHealthMonitor.start() (if pool exists, 15-second ping interval)

13. Register routes (registerRoutes)
    ├── /api/v1/health       — health check (no auth)
    ├── /api/v1/ready        — readiness probe (no auth)
    ├── /api/v1/metrics      — metrics snapshot (no auth)
    ├── POST /api/v1/calls        — create call (auth)
    ├── GET  /api/v1/calls/:id    — get call (auth)
    ├── POST /api/v1/calls/:id/messages   — add AI message (auth)
    ├── POST /api/v1/calls/:id/user-text  — process user text (auth)
    ├── GET  /api/v1/calls/:id/transcript — get transcript (auth)
    ├── POST /api/v1/calls/:id/complete   — complete call (auth)
    ├── POST /api/v1/calls/:id/cancel     — cancel call (auth)
    ├── GET  /api/v1/users/:id/active-call — get active call (auth)
    ├── POST /api/v1/calls/:id/callback   — schedule callback (auth)
    └── POST /api/v1/phone/register       — phone registration (auth)

14. app.ready() → app.listen(PORT, '0.0.0.0')

15. Create signaling server (WebSocketServer on /phone, attached to HTTP server)

16. Set startupComplete = true (readiness probe now returns ok)
    ├── Record timing metric: startup.duration
    └── Increment counter: startup.complete
```

---

## Shutdown Flow

```
SIGTERM / SIGINT / uncaughtException
  │
  ├── Set shuttingDown flag (guard — prevents re-entry)
  │
  ├── Stop periodic tasks:
  │   ├── SessionSweeper.stop()         (clearInterval)
  │   ├── DatabaseHealthMonitor.stop()  (clearInterval)
  │   ├── PersistenceVerifier.stop()    (clearInterval)
  │   └── CleanupScheduler.shutdown()   (clear all setTimeout)
  │
  ├── Drain active operations:
  │   ├── await app.close()             (Fastify — stops accepting requests)
  │   └── signalingServer.close()       (WebSocket server)
  │
  ├── await eventBus.shutdown()         (clear registry)
  ├── logger.flush?.()                  (pino flush)
  │
  ├── Close database pool:
  │   └── await pool.end()              (await pending queries)
  │
  ├── Record timing: shutdown.duration
  └── process.exit(0)
```

Force-kill timer: 10 seconds. If shutdown doesn't complete, `process.exit(1)` is called.

---

## Request Lifecycle

### Authenticated REST Request

```
Client → Fastify HTTP Server
  │
  ├── onRequest hook: Auth middleware
  │   ├── If URL is /health, /ready, or /metrics: skip auth
  │   ├── Extract Bearer token from Authorization header
  │   ├── If token matches SERVICE_TOKEN → auth = { userId: 'service', role: 'service' }
  │   └── If no valid token → return 401 UNAUTHORIZED
  │
  ├── Route handler
  │   ├── Parse and validate request body/params
  │   ├── Call VoiceBridgeService method
  │   │   ├── withSessionLock(callId) [for mutations only]
  │   │   ├── Repository CRUD (memory + DB depending on mode)
  │   │   ├── EventBus publish (call.created, etc.)
  │   │   ├── notifyPhone (WebSocket push to connected phone)
  │   │   └── Return result
  │   └── Return HTTP response (JSON)
  │
  └── Response with X-Request-Id header
```

### WebSocket Connection

```
Client → HTTP Upgrade to /phone?token=<SERVICE_TOKEN>
  │
  ├── Token validation (ws.on('connection'))
  │   ├── Extract token from URL query parameters
  │   ├── If token missing or invalid: ws.close(4001, 'unauthorized')
  │   └── If valid: proceed
  │
  ├── Connection rate limiting (per IP, configurable)
  │
  ├── registerPhone(userId, ws)
  │   ├── Store WebSocket in phoneConnections Map
  │   ├── If existing connection: close old, replace with new
  │   └── Publish presence.connected or presence.updated
  │
  ├── On message:
  │   ├── Message size validation (< maxMessageSize)
  │   ├── Rate limit check (token bucket)
  │   └── Publish signaling.message_received
  │
  └── On close/error:
      ├── Remove WebSocket from phoneConnections
      └── Publish presence.disconnected or signaling.failed
```

---

## Recovery Lifecycle

### Phase A — Load from Database (startup)

```
RecoveryManager.loadFromDatabase()
  │
  ├── Load all sessions from PostgreSQL sessions table
  │   └── For each session: sessionRepo.create(session) into InMemory
  │
  ├── Load all callbacks from PostgreSQL callbacks table
  │   └── For each callback: callbackRepo.save(userId, callback) into InMemory
  │
  └── Log summary: loaded X sessions and Y callbacks
```

### Phase B — Rebuild Timers (after Phase A)

```
RecoveryManager.rebuildTimers(cleanupScheduler, lifecycleCoordinator)
  │
  ├── For each callback in InMemoryCallbackRepository:
  │   └── lifecycleCoordinator.resumeCallback(userId, callId, delayMinutes, resumeAt)
  │       └── cleanupScheduler.schedule(resume:callId, resumeAt, handler)
  │
  ├── For each session with status 'paused' that has no callback:
  │   └── lifecycleCoordinator.recoverOrphanedPause(callId)
  │       ├── If pause TTL expired during downtime: cancel session immediately
  │       └── If TTL not yet expired: schedule pause-ttl timer
  │
  └── recoveryComplete = true
```

### Post-Recovery Sweep

```
sessionSweeper.sweep() — called once immediately after recovery
  ├── Iterate all sessions
  ├── If session.retentionExpiresAt < now: delete session + notify
  └── Normal sweeper continues on 5-minute interval
```

---

## Repository Architecture

```
                    ┌─────────────────────────────┐
                    │  SessionRepository (interface) │
                    │  findById, findByUserId, create │
                    │  save, delete, list, transaction │
                    └─────────────────────────────┘
                              ▲
              ┌───────────────┼───────────────┐
              │               │               │
   ┌──────────┴──┐   ┌────────┴────────┐   ┌──┴──────────┐
   │ InMemory    │   │ DualWrite       │   │ PrimaryDB    │
   │ (Map-based) │   │ memory + DB     │   │ (DB only)    │
   └─────────────┘   └────────┬────────┘   └──────┬──────┘
                              │                    │
                              ▼                    ▼
                    ┌──────────────────┐  ┌──────────────────┐
                    │ DatabaseSession  │  │ DatabaseSession   │
                    │ Repository (pg)  │  │ Repository (pg)   │
                    └──────────────────┘  └──────────────────┘
                              ▲                    ▲
                              │                    │
                    ┌─────────┴────────────────────┴─────────┐
                    │     InstrumentedSessionRepository       │
                    │  (timing, retry, slow-query wrapper)    │
                    └───────────────────────────────────────┘
```

Same structure for `CallbackRepository`.

### Persistence Modes

| Mode | Writes go to | Reads go to | DB required | Use case |
|------|-------------|-------------|-------------|----------|
| `memory` | InMemory Map | InMemory Map | No | Local dev, tests |
| `dual-write` | InMemory + DB | InMemory Map | No* | Migration, dual-validation |
| `database-read` | InMemory + DB | DB | Yes | Canary, read-path testing |
| `database` | DB | DB | Yes | Production |

\* dual-write falls back to memory-only if DATABASE_URL is not set.

### Instrumentation Layer

Every repository operation passes through:
1. **Timing:** `metrics.recordTiming(repo.operation, durationMs)`
2. **Counters:** `metrics.incrementCounter(repo.operation.ok|error)`
3. **Retry:** `withRetry()` — 1 retry on transient errors, 50-100ms base delay
4. **Slow query:** Warning log if operation > 250ms

---

## Security Model

| Vector | Mechanism | Implementation |
|--------|-----------|----------------|
| HTTP API auth | Bearer token | routes.ts:38-48, onRequest hook compares token to SERVICE_TOKEN |
| WebSocket auth | Query parameter token | signaling/server.ts: token extracted from URL, compared to SERVICE_TOKEN |
| No-auth endpoints | Health, readiness, metrics | Explicit whitelist in onRequest hook |
| Input validation | Manual field checks | routes.ts: summary, reason, content, text validation |
| SQL injection | Parameterized queries | pg.Pool with $1, $2 placeholders |
| Path traversal | UUID pattern | route params are UUIDs (random) |
| Rate limiting | Fastify rate-limit plugin | 100/min global, 60/min moderate, 20/10s health |
| CORS | @fastify/cors | Configurable via CORS_ALLOWED_ORIGINS |
| Helmet CSP | @fastify/helmet | Content-Security-Policy headers |
| Secrets exposure | Config mask | validateConfig logs keys/types, never values |
| Docker | Non-root | appuser (1001), read-only rootfs |

---

## Deployment Architecture

```
Internet
    │
    ▼
┌──────────┐   TLS/HTTPS   ┌──────────┐
│  Client   │──────────────│  Caddy   │
│  (browser,│              │  Proxy   │
│   phone)  │◯────────────│  :443    │
└──────────┘   WS/WSS      └────┬─────┘
                                │
                    ┌───────────┴───────────┐
                    │  Backend Pod :4000     │
                    │  REST API + WS (/phone)│
                    └───────────────────────┘
                                │
                    ┌───────────┴───────────┐
                    │  PostgreSQL 16        │
                    │  (external or RDS)    │
                    └───────────────────────┘
```

### K8s Structure

- **Deployment:** 2 replicas (min), 10 (max via HPA)
- **Service:** ClusterIP, port 4000
- **Ingress:** nginx, TLS via cert-manager (Let's Encrypt)
- **HPA:** CPU 70%, memory 80% target utilization
- **PDB:** minAvailable=1
- **NetworkPolicy:** Restrictive ingress (nginx only), egress for DNS + PostgreSQL
- **Resource limits:** 512MB memory, 1 CPU per pod

---

## Observability Architecture

### Metrics (in-process)

```
MetricsCollector
  ├── Counters    (Map<string, number>)  — sessions.created, startup.complete, repo.*.ok/error
  ├── Gauges      (Map<string, number>)  — sessions.active, sessions.paused, db.pool.*
  └── Timings     (Map<string, number[]>) — startup.duration, db.ping, repo.*
        └── 1000-sample cap per timing key
```

Exposed at `GET /api/v1/metrics` as JSON. To ingest into Prometheus: deploy a json_exporter or custom adapter.

### Logging

- **Library:** pino (structured JSON)
- **Level:** info (production), debug (development)
- **Transport:** stdout (container-native)
- **Event logging:** HTTP requests, session operations, timer events, DB health changes, shutdown

### Health Checking

- `/api/v1/health`: process health + DB connectivity + pool stats + session counts
- `/api/v1/ready`: startup + recovery + DB = readiness for K8s probes
- Both rate-limited, both unauthenticated

---

## Event Bus Architecture

```
DefaultEventBus
  ├── Registry — Map<eventType, Set<handler>>
  ├── Hooks — beforeEvent, afterEvent, error
  ├── Publish — synchronous dispatch + microtask for async handlers
  └── Shutdown — clears all subscriptions

Domain events published: 19 event types
  ├── call.created, call.answered, call.paused, call.ended, call.cancelled
  ├── notification.requested, notification.delivered, notification.failed
  ├── presence.connected, presence.disconnected, presence.updated
  └── signaling.connected, signaling.disconnected, signaling.message_received, signaling.failed
     (2 more from calls module? verified: 5+3+3+4 = 15 events across 4 modules)

Current state: all 14(+) subscribers are log-only. No business logic runs in event handlers.
```
