# VoiceBridge v1.0.0 — Reality Deployability Audit

**Audited:** 2026-07-26  
**Scope:** Entire codebase — code, not docs  
**Method:** Line-by-line verification of every source file  

---

## Executive Summary

**Overall Score: 4/10 — Partially Implemented**

VoiceBridge has a well-structured backend with a complete event bus, repository pattern, WebSocket signaling, and graceful shutdown. However, **critical gaps** in deployment infrastructure, AI integrations, telephony, and mobile tooling mean it **cannot be deployed as a functional product today**.

---

## Phase 1 — Deployment Audit

### Dockerfiles

| File | Status | Notes |
|------|--------|-------|
| `./Dockerfile` | ✅ Implemented | Multi-stage, health check, non-root user, exposes 4000 |
| `./backend/Dockerfile` | ✅ Implemented | Multi-stage, non-root user, health check, read-only fs |
| `./mcp-server/Dockerfile` | ⚠ Partial | Single-stage, missing health check, missing non-root user |

### Docker Compose (`infra/docker-compose.yml`)

| Feature | Status | Notes |
|---------|--------|-------|
| backend-api service | ✅ | Builds from `../backend`, health check, resource limits, read-only |
| mcp-server service | ✅ | Builds from `../mcp-server`, resource limits |
| caddy reverse proxy | ✅ | Caddy 2 with auto TLS, routes `/api/*` and `/phone*` to backend, `/mcp/*` to MCP |
| PostgreSQL | ❌ **Missing** | No PostgreSQL service — requires external DB |
| Redis | ❌ **Missing** | Not used (correctly, by design) |
| coturn/STUN/TURN | ❌ **Missing** | Config exists (`infra/coturn/turnserver.conf`) but not in compose |
| Environment | ⚠ Partial | Uses `../.env` — but root `.env.example` is **out of sync** with `backend/.env.example` |
| Network | ⚠ Partial | No explicit network definition (uses default) |

### Kubernetes Manifests (`infra/k8s/`)

| Manifest | Status | Notes |
|----------|--------|-------|
| 01-namespace.yaml | ✅ | Creates `voicebridge` namespace |
| 02-secret-template.yaml | ✅ | Template for SERVICE_TOKEN + DATABASE_URL |
| 03-configmap.yaml | ✅ | Config with PERSISTENCE_MODE=database |
| 04-deployment.yaml | ✅ | Liveness, readiness, startup probes; security context; 2 replicas |
| 05-service.yaml | ✅ | ClusterIP on port 4000 |
| 06-ingress.yaml | ✅ | nginx ingress with cert-manager TLS |
| 07-hpa.yaml | ✅ | CPU 70% / memory 80%, 2–10 replicas |
| 08-pdb.yaml | ✅ | minAvailable: 1 |
| 09-network-policy.yaml | ✅ | Ingress from ingress-nginx, egress for DNS/HTTPS/PostgreSQL |
| Helm chart | ❌ **Missing** | No Helm chart |

### **Verdict: Cannot deploy without external PostgreSQL**

A user cloning the repo and running `docker compose up` will fail because:
1. No PostgreSQL container in docker-compose — DB_URL must point somewhere
2. Default `PERSISTENCE_MODE=dual-write` requires DATABASE_URL to be set for dual-write mode, but falls back silently to memory-only if URL is missing
3. The root Dockerfile (`./Dockerfile`) copies `backend/package.json` to `/app/package.json` but the WORKDIR is `/app` and it copies relative paths — this works only if built from the repo root

---

## Phase 2 — Backend Audit

### Startup (`backend/src/index.ts`)

| Step | Line | Status |
|------|------|--------|
| validateConfig() | 54 | ✅ |
| MetricsCollector | 56 | ✅ |
| EventBus | 58–62 | ✅ |
| register domain event handlers | 64–67 | ✅ |
| InMemory repos creation | 91–92 | ✅ |
| Persistence mode selection | 104–161 | ✅ |
| RecoveryManager.loadFromDatabase | 118–119, 137–138 | ✅ |
| Instrumented repos wrapping | 164–165 | ✅ |
| VoiceBridgeService | 167 | ✅ |
| LifecycleCoordinator | 252–258 | ✅ |
| RecoveryManager.rebuildTimers | 260–262 | ✅ |
| SessionSweeper | 266–279 | ✅ |
| DatabaseHealthMonitor | 282–285 | ✅ |
| registerRoutes | 298 | ✅ |
| app.listen | 303 | ✅ |
| WebSocket signaling | 306 | ✅ |
| Graceful shutdown (SIGTERM/SIGINT) | 325–380 | ✅ |
| Uncaught exception handler | 373–380 | ✅ |

### Routes (`backend/src/routes.ts`)

| Route | Method | Status | Notes |
|-------|--------|--------|-------|
| `/api/v1/health` | GET | ✅ | Reports DB status, scheduler, sessions, callbacks |
| `/api/v1/ready` | GET | ✅ | startupComplete, recoveryComplete, dbConnected |
| `/api/v1/metrics` | GET | ✅ | MetricsCollector snapshot |
| `/api/v1/calls` | POST | ✅ | Creates call session, validates input |
| `/api/v1/calls/:callId` | GET | ✅ | Get single call |
| `/api/v1/calls/:callId/messages` | POST | ✅ | Add AI message |
| `/api/v1/calls/:callId/user-text` | POST | ✅ | Process user text |
| `/api/v1/calls/:callId/transcript` | GET | ✅ | Get transcript |
| `/api/v1/calls/:callId/complete` | POST | ✅ | Complete call |
| `/api/v1/calls/:callId/cancel` | POST | ✅ | Cancel call |
| `/api/v1/users/:userId/active-call` | GET | ✅ | Get user's active call |
| `/api/v1/calls/:callId/callback` | POST | ✅ | Schedule callback |
| `/api/v1/phone/register` | POST | ✅ | Returns WebSocket endpoint |

### Auth (`backend/src/routes.ts:38–71`)

| Aspect | Status | Notes |
|--------|--------|-------|
| Bearer token auth | ✅ | Compares against SERVICE_TOKEN |
| Health/ready/metrics bypass auth | ✅ | Always accessible |
| No user identity management | ⚠ | Every request resolves to `solo-user` or `service` |
| WebSocket auth | ⚠ | Token passed in query param (`?token=`) — logged in URLs |

### Middleware

| Middleware | Status | Notes |
|-----------|--------|-------|
| @fastify/helmet | ✅ | CSP, security headers |
| @fastify/compress | ✅ | Global compression |
| @fastify/cors | ✅ | Configurable origins |
| @fastify/rate-limit | ✅ | 100/min global, per-route limits |
| Request ID | ✅ | x-request-id header |
| Error handler | ✅ | Structured error responses |
| Auth hook | ✅ | Protects all non-health routes |

### WebSocket Server (`backend/src/signaling/server.ts`)

| Feature | Status | Notes |
|---------|--------|-------|
| Path `/phone` | ✅ | |
| Connection rate limiting | ✅ | Per-IP |
| Message rate limiting | ✅ | Per-connection token bucket |
| Max message size | ✅ | Configurable |
| Auth via token query param | ✅ | Validates against SERVICE_TOKEN |
| Phone registration | ✅ | Registers in phoneConnections Map |
| Cleanup on close/error | ✅ | |

### Event System

| Module | Lines of Code | Status | Notes |
|--------|---------------|--------|-------|
| `event-bus/` | ~550 | ✅ | Fully implemented pub/sub with sync/async, timeouts, priorities, scopes |
| `calls/` | ~260 | ✅ | 8 event types, publishers, subscribers |
| `presence/` | ~115 | ✅ | 3 event types |
| `signaling/` | ~130 | ✅ | 4 event types |
| `notifications/` | ~140 | ✅ | 3 event types |

### Dead Code / Unused Modules

| File | Status | Notes |
|------|--------|-------|
| `repositories/burn-in.ts` | ❌ **Unused** | `PersistenceBurnIn` class exported but **never instantiated** anywhere |
| `event-bus/publisher.ts` | ❌ **Unused** | `Publisher` class exported but never used — `createEventPublisher` from `common/event-publisher.ts` is used instead |
| `common/types.ts` | ✅ | Used (exported types `CallPriority`, `CallReason`) |

### Placeholder Implementations

| Location | Status | Notes |
|----------|--------|-------|
| All event subscribers in `calls/subscribers.ts`, `presence/subscribers.ts`, `signaling/subscribers.ts`, `notifications/subscribers.ts` | ⚠ **Logging-only** | Every subscriber only logs — no real side effects beyond logging |
| `notifyPhone` in `service.ts:291` | ⚠ | Only sends over connected WebSocket — if phone is offline, message is silently dropped |

---

## Phase 3 — Database Audit

### PostgreSQL

| Feature | Status | Location | Notes |
|---------|--------|----------|-------|
| Connection pooling | ✅ | `backend/src/index.ts:108–113` | `pg.Pool` with configurable min/max/timeout |
| Session repository | ✅ | `repositories/db-session-repository.ts` | Full CRUD with JSONB data column |
| Callback repository | ✅ | `repositories/db-callback-repository.ts` | Full CRUD |
| Transactions | ✅ | Both DB repos support `BEGIN/COMMIT/ROLLBACK` | |
| Schema | ✅ | `repositories/schema.sql` | sessions + callbacks tables with indexes |
| Migrations | ❌ **Missing** | No Knex, no migration runner | Schema is a reference `.sql` file — must be applied manually |
| Connection health checks | ✅ | `common/db-health-monitor.ts` | Periodic `SELECT 1` with pool metrics |
| Retry logic | ✅ | `common/retry.ts` | Transient error detection, exponential backoff |

### Dual-Write Pattern

| Implementation | Status | Notes |
|----------------|--------|-------|
| `DualWriteSessionRepository` | ✅ | Writes to both memory + DB, reads from memory (or DB in `database-read` mode) |
| `DualWriteCallbackRepository` | ✅ | Same pattern |
| `PrimaryDatabaseSessionRepository` | ✅ | Production mode — reads/writes from DB only |
| `PrimaryDatabaseCallbackRepository` | ✅ | Same pattern |
| `PersistenceVerifier` | ✅ | Periodically compares memory vs DB state |
| `RecoveryManager` | ✅ | Loads DB → memory at startup, rebuilds timers |

### **Verdict: Can operate with fresh PostgreSQL, but requires manual schema setup**

A fresh PostgreSQL instance requires:
```bash
psql $DATABASE_URL -f backend/src/voicebridge/repositories/schema.sql
```
No automated migration tooling exists.

---

## Phase 4 — MCP / AI Integration Audit

### Every External Integration

| Integration | Status | Location | Notes |
|-------------|--------|----------|-------|
| **MCP SDK** | ✅ Implemented | `mcp-server/src/index.ts` | Stdio + Streamable HTTP (SSE) transports |
| **MCP Tools** | ✅ Implemented | `mcp-server/src/tools.ts` | 5 tools: create_call, send_message, get_transcript, complete_call, cancel_call |
| **MCP SSE Server** | ✅ Implemented | `mcp-server/src/sse.ts` | HTTP server with CORS, API key auth |
| **MCP → Backend client** | ✅ Implemented | `mcp-server/src/client.ts` | HTTP client calling backend API with SERVICE_TOKEN |
| **OpenAI** | ❌ **Missing** | Nowhere | No OpenAI integration, SDK, or API calls |
| **Anthropic** | ❌ **Missing** | Nowhere | No Anthropic integration |
| **Ollama** | ❌ **Missing** | Nowhere | No Ollama integration |
| **Local models** | ❌ **Missing** | Nowhere | No local model inference |
| **Twilio** | ❌ **Missing** | Nowhere | No Twilio SDK, no phone number support |
| **SIP** | ❌ **Missing** | Nowhere | No SIP stack |
| **TTS (Text-to-Speech)** | ❌ **Missing** | `service.ts:309` log says "Phone-side (Android TextToSpeech)" | No server-side TTS — startup log admits it's phone-side only |
| **STT (Speech-to-Text)** | ❌ **Missing** | `.env` has `STT_ENABLED=true` + `STT_MODEL=Xenova/whisper-base` but **zero references in code** | Config options exist that are never read |
| **WebRTC** | ❌ **Missing** | `mobile/ios-archived/AgentCall/Call/WebRTCClient.swift` exists | iOS has a WebRTC client stub; backend has **no WebRTC** — only WebSocket signaling |
| **Webhooks** | ❌ **Missing** | Nowhere | No outbound webhook support |
| **coturn/STUN/TURN** | ⚠ Configured | `infra/coturn/turnserver.conf` | Config file exists but **not referenced in docker-compose** |

### MCP Server Verification

| Aspect | Status | Notes |
|--------|--------|-------|
| Starts successfully | ✅ | |
| Lists tools | ✅ | Via MCP ListTools |
| Calls tools | ✅ | Via MCP CallTool — proxies to backend API |
| SSE transport | ✅ | Streamable HTTP with CORS + API key |
| Stdio transport | ✅ | For local AI agents |
| Health endpoint | ✅ | `/health` returns status |
| Auth (x-api-key) | ✅ | Configurable via MCP_API_KEY |

---

## Phase 5 — Hosting Audit

| Platform | Status | Reason |
|----------|--------|--------|
| **Docker** | ✅ Supported | Dockerfiles for all services |
| **Docker Compose** | ⚠ Needs changes | Missing PostgreSQL, coturn; no DB migration automation |
| **Railway** | ⚠ Needs changes | No `railway.json` or Nixpacks config; requires PostgreSQL add-on config |
| **Render** | ⚠ Needs changes | No `render.yaml`; must configure as Docker service with env vars |
| **Fly.io** | ⚠ Needs changes | No `fly.toml`; requires PostgreSQL cluster setup |
| **DigitalOcean App Platform** | ⚠ Needs changes | No `app.yaml` or DO-specific config |
| **AWS** | ❌ Unsupported | No ECS task def, no CloudFormation/Terraform, no RDS config |
| **Azure** | ❌ Unsupported | No container instance or AKS config |
| **GCP** | ❌ Unsupported | No Cloud Run or GKE config |
| **Kubernetes** | ✅ Supported | Complete manifests including HPA, PDB, NetworkPolicy, Ingress with cert-manager |

---

## Phase 6 — Environment Variables

### Root `.env` (used by docker-compose)

| Variable | Required | Documented | Loaded | Validated | Notes |
|----------|----------|------------|--------|-----------|-------|
| `PORT` | No | ✅ | ✅ | ✅ | Default 4000 |
| `NODE_ENV` | No | ✅ | ✅ | ⚠ Partial | Used for log level, no validation |
| `SERVICE_TOKEN` | **YES** | ✅ | ✅ | ✅ | Validated in `validateConfig()` |
| `CORS_ALLOWED_ORIGINS` | No | ✅ | ✅ | ✅ | Default `*` |
| `BODY_LIMIT_BYTES` | No | ✅ | ✅ | ✅ | Default 1048576 |
| `DATABASE_URL` | Conditional | ✅ | ✅ | ✅ | Required for `database` and `database-read` modes |
| `DB_POOL_MIN` | No | ✅ | ✅ | ✅ | Default 2 |
| `DB_POOL_MAX` | No | ✅ | ✅ | ✅ | Default 10 |
| `DB_POOL_ACQUIRE_TIMEOUT` | No | ✅ | ✅ | ✅ | Default 10000 |
| `DB_POOL_IDLE_TIMEOUT` | No | ✅ | ✅ | ✅ | Default 30000 |
| `PERSISTENCE_MODE` | No | ✅ | ✅ | ✅ | Validated against `memory\|dual-write\|database-read\|database` |
| `DB_VERIFICATION_INTERVAL_MS` | No | ✅ | ✅ | ✅ | Default 0 (disabled) |
| `SIGNALING_MAX_MESSAGE_SIZE` | No | ✅ | ✅ | ✅ | Default 262144 |
| `SIGNALING_RATE_LIMIT_MESSAGES` | No | ✅ | ✅ | ✅ | Default 30 |
| `SIGNALING_RATE_LIMIT_WINDOW` | No | ✅ | ✅ | ✅ | Default 10 |
| `SIGNALING_CONNECTION_RATE_LIMIT` | No | ✅ | ✅ | ✅ | Default 10 |

### Variables in `.env` but NOT in `.env.example`

| Variable | Present in `.env` | Present in `.env.example` | Present in code | Notes |
|----------|-------------------|--------------------------|-----------------|-------|
| `SIGNALING_PORT` | ✅ | ❌ | ❌ | **Dead config** — never read by code |
| `STT_ENABLED` | ✅ | ❌ | ❌ | **Dead config** — never read by code |
| `STT_MODEL` | ✅ | ❌ | ❌ | **Dead config** — never read by code |

### MCP Server Environment Variables

| Variable | Required | Documented | Loaded | Validated | Notes |
|----------|----------|------------|--------|-----------|-------|
| `MCP_SERVER_PORT` | No | ✅ | ✅ | ✅ | Default 3000 |
| `BACKEND_API_URL` | No | ✅ | ✅ | ✅ | Default http://localhost:4000/api/v1 |
| `SERVICE_TOKEN` | **YES** | ✅ | ✅ | ✅ | Shared with backend |
| `MCP_API_KEY` | No (prod: yes) | ✅ | ✅ | ✅ | Empty = no auth (dev mode) |
| `MCP_TRANSPORT` | No | ✅ | ✅ | ❌ | Not validated — valid values: stdio, sse, both |
| `NODE_ENV` | No | ✅ | ✅ | ❌ | Not validated |

### Missing Validation

| Issue | Severity | Location |
|-------|----------|----------|
| `MCP_TRANSPORT` not validated against allowed values | Low | `mcp-server/src/config.ts` |
| `NODE_ENV` not validated in either service | Low | Both configs |
| `SIGNALING_PORT` in `.env` but neither read nor validated | Low | `backend/.env` |
| `STT_ENABLED`/`STT_MODEL` in `.env` but never read | Medium | `backend/.env` — suggests planned but unimplemented feature |

---

## Phase 7 — Runtime Audit

Full startup trace verified against `backend/src/index.ts`:

```
main()
  │
  ├── validateConfig()                         [54]    ✅
  ├── new MetricsCollector()                   [56]    ✅
  ├── new DefaultEventBus()                    [58]    ✅
  ├── eventBus.onBeforeEvent/onAfterEvent/onError [59–62] ✅
  ├── registerNotifications(eventBus)          [64]    ✅
  ├── registerPresence(eventBus)               [65]    ✅
  ├── registerCalls(eventBus)                  [66]    ✅
  ├── registerSignaling(eventBus)              [67]    ✅
  ├── Fastify instance                         [69–86] ✅
  ├── new CleanupScheduler() + decorate        [88–89] ✅
  ├── new InMemorySessionRepository()          [91]    ✅
  ├── new InMemoryCallbackRepository()         [92]    ✅
  │
  ├── PERSISTENCE_MODE branching               [104–161]
  │   ├── memory → skip DB                    [159–160] ✅
  │   ├── database → Pool + DB repos + Recovery [104–124] ✅
  │   └── dual-write/database-read → Pool if URL [125–158] ✅
  │
  ├── new InstrumentedSessionRepository()      [164]    ✅
  ├── new InstrumentedCallbackRepository()     [165]    ✅
  ├── new VoiceBridgeService()                 [167]    ✅
  ├── helmet + compress + cors + rateLimit     [169–206] ✅
  ├── onRequest hook (X-Request-Id)            [208–210] ✅
  ├── setErrorHandler                          [212–250] ✅
  ├── new LifecycleCoordinator()               [252–258] ✅
  ├── voiceBridgeService.setLifecycleCoordinator [258]    ✅
  ├── recoveryManager?.rebuildTimers()         [260–262] ✅
  ├── new DeletionCoordinator()                [264]    ✅
  ├── new SessionSweeper()                     [266–271] ✅
  ├── sessionSweeper.sweep() (post-recovery)   [273–277] ✅
  ├── sessionSweeper.start()                   [279]    ✅
  ├── dbHealth?.start()                        [282–285] ✅
  ├── registerRoutes(app, routeOpts)           [298]    ✅
  ├── app.ready() + app.listen()               [302–303] ✅
  ├── createSignalingServer(app.server)        [306]    ✅
  ├── routeOpts.startupComplete = true         [313]    ✅
  │
  └── Shutdown handlers                        [325–380] ✅
```

**Nothing is skipped.**

---

## Phase 8 — End-to-End Wiring

### Request Trace: POST /api/v1/calls

```
Client → POST /api/v1/calls
         │
         ├── routes.ts:54-70  → onRequest auth hook (Bearer token check)
         ├── routes.ts:142-182 → handler
         │     ├── Parse body fields (userId, agentId, summary, reason, etc.)
         │     ├── Validate: summary required, reason must be valid enum
         │     ├── voicebridge.createCall(input)       → service.ts:56-103
         │     │     ├── Build VoiceCallSession object
         │     │     ├── sessionRepo.create(session)   → InMemory or DB repo
         │     │     │     └── Instrumented → DualWrite/PrimaryDB/InMemory
         │     │     ├── publishCallCreated()          → EventBus
         │     │     └── notifyPhone(userId, payload)  → WebSocket send
         │     ├── metrics.incrementCounter('sessions.created')
         │     └── Return 201 { call_id, status, created_at }
         │
         └── Response → Client
```

### Dependency Graph Verification

| Component | Wired? | Files |
|-----------|--------|-------|
| `registerRoutes` → `VoiceBridgeService` | ✅ `routes.ts:51` |
| `VoiceBridgeService` → `SessionRepository` | ✅ `service.ts:48` |
| `VoiceBridgeService` → `CallbackRepository` | ✅ `service.ts:49` |
| `VoiceBridgeService` → `LifecycleCoordinator` | ✅ `service.ts:258` |
| `LifecycleCoordinator` → `CleanupScheduler` | ✅ `lifecycle-coordinator.ts:14` |
| `LifecycleCoordinator` → `SessionRepository` | ✅ `lifecycle-coordinator.ts:25` |
| `LifecycleCoordinator` → `CallbackRepository` | ✅ `lifecycle-coordinator.ts:40` |
| `SessionSweeper` → `SessionRepository` | ✅ `sweeper.ts:7` |
| `SessionSweeper` → `DeletionCoordinator` | ✅ `sweeper.ts:9` |
| `RecoveryManager` → `SessionRepository` (DB + memory) | ✅ `recovery-manager.ts:8–11` |
| `RecoveryManager` → `CallbackRepository` (DB + memory) | ✅ `recovery-manager.ts:9–12` |
| `DatabaseHealthMonitor` → `Pool` | ✅ `db-health-monitor.ts:27` |
| `EventBus` → all 4 domain modules | ✅ `index.ts:64–67` |
| `createSignalingServer` → `voicebridge.registerPhone` | ✅ `signaling/server.ts:101` |

**Nothing is mocked in production code.** Tests use `InMemory*Repository` directly.

---

## Phase 9 — Production Readiness

| Feature | Status | Evidence |
|---------|--------|----------|
| **Logging** | ✅ **Implemented** | pino structured JSON, request serialization, header redaction, log levels per env |
| **Monitoring** | ⚠ **Partially implemented** | In-memory MetricsCollector accessible via `/api/v1/metrics` endpoint |
| **Metrics** | ⚠ **In-memory only** | No Prometheus format — `/api/v1/metrics` returns custom JSON |
| **Prometheus** | ❌ **Missing** | No `/metrics` endpoint in Prometheus text format |
| **Grafana** | ❌ **Missing** | No dashboard JSON found (docs reference `GRAFANA_DASHBOARDS.md` but file doesn't exist) |
| **AlertManager** | ❌ **Missing** | No alert rules or configuration |
| **Backups** | ❌ **External setup required** | Docs recommend `pg_dump` — no automated backup |
| **TLS** | ✅ **Configured** | Caddy auto TLS + K8s cert-manager |
| **Secrets** | ⚠ **Basic** | SERVICE_TOKEN used for both API and WS auth; MCP has optional API key |
| **Graceful shutdown** | ✅ **Implemented** | Drain connections, close pool, flush logs, 10s force kill |
| **Retries** | ✅ **Implemented** | Transient DB error detection with exponential backoff |
| **Recovery** | ✅ **Implemented** | Phase A (load DB→memory) + Phase B (rebuild timers) + post-recovery sweep |
| **Health checks** | ✅ **Implemented** | `/health`, `/ready`, Docker HEALTHCHECK, K8s probes |
| **Rate limiting** | ✅ **Implemented** | Global + per-route + WebSocket rate limits |
| **Input validation** | ⚠ **Basic** | Manual field checks in routes — no Zod schemas |
| **CORS** | ✅ **Configured** | Configurable origins |

---

## Phase 10 — Reality Report

### Scoring (out of 10)

| Category | Score | Rationale |
|----------|-------|-----------|
| **Deployment** | 5/10 | Dockerfiles + Docker Compose + K8s exist; missing PostgreSQL, coturn, DB migrations |
| **Backend** | 8/10 | Fully wired event-driven architecture; minor dead code (burn-in.ts, event-bus Publisher class) |
| **Database** | 5/10 | Full repository pattern with dual-write; no migration tooling, no automated schema setup |
| **Hosting** | 4/10 | K8s ready; Docker Compose incomplete; no cloud-specific configs for Railway/Render/Fly/etc. |
| **Infrastructure** | 3/10 | No Prometheus, no Grafana, no alerts, no backups, no CI/CD pipeline |
| **AI integrations** | 2/10 | MCP server works as tool proxy; **zero** AI provider integrations (no OpenAI, Anthropic, Ollama) |
| **MCP integrations** | 7/10 | Full MCP SDK implementation with stdio + SSE; missing webhook callbacks |
| **Configuration** | 6/10 | Env vars loaded and validated; dead config vars (STT_*, SIGNALING_PORT) suggest stale plans |
| **Environment** | 5/10 | Root and backend `.env.example` out of sync; actual `.env` has undocumented/unused vars |
| **Runtime** | 9/10 | Complete startup flow, graceful shutdown, error handling, recovery; nothing skipped |
| **Overall** | **4/10** | Polished backend engine with **no AI brains, no telephony, no mobile app integration in production** |

### Answers to the 10 Questions

**1. Can I deploy this today?**  
**No.** You cannot deploy a functional VoiceBridge instance today. The backend will start, but:
- There is no PostgreSQL in docker-compose 
- The schema must be manually applied
- There is no actual TTS/STT — the phone app must handle it
- There is no phone number or telephony integration
- There are no AI provider integrations — the MCP server has no one to talk to

**2. Can I run it locally?**  
**Partially.** With `PERSISTENCE_MODE=memory` (the default when DATABASE_URL is unset), you can start the backend and MCP server. You can create calls via the API. However:
- No phone app is actually connected (Android app exists but needs building/running)
- No AI agent can call the MCP server unless you configure one manually
- STT/TTS doesn't exist on the server side

**3. Can I deploy it to a VPS?**  
**Partially, with significant manual work.** You'd need to:
- Set up PostgreSQL 16 manually or via Docker
- Apply schema.sql manually
- Configure Caddy with a real domain
- Set up coturn manually for WebRTC
- Build and deploy the Android app or write a test client

**4. Can I deploy it to Kubernetes?**  
**Partially.** The K8s manifests are complete and well-structured. You'd still need:
- A PostgreSQL 16 instance accessible from the cluster (e.g., Cloud SQL, Neon, or run in-cluster)
- cert-manager + nginx ingress controller installed
- A container registry with the built images
- The MCP server isn't included in K8s manifests (only backend is)

**5. Can I deploy it to Railway/Render?**  
**Not without significant adaptation.** No platform-specific configuration files exist. You'd need to:
- Write a `railway.json` or `render.yaml`
- Configure PostgreSQL add-ons
- Set up environment variables manually

**6. Are all backend services actually connected?**  
**Yes.** Every dependency is wired:  
`Fastify → Routes → VoiceBridgeService → Repositories (InMemory/DB/DualWrite) → EventBus → 4 domains (calls/presence/signaling/notifications) → WebSocket signaling`. Nothing is disconnected.

**7. Are there any fake implementations?**  
**No "fake" implementations**, but the event subscribers are **logging-only**. They receive events and log them, but don't trigger real side effects beyond the inline calls in `service.ts`.

**8. Are there any placeholder integrations?**  
**Yes:**
- `STT_ENABLED`/`STT_MODEL` in `.env` — code never reads them (`backend/.env:5-6`)
- `SIGNALING_PORT` in `.env` — never read by any code (`backend/.env:3`)
- "TTS engine: Phone-side (Android TextToSpeech)" in startup log (`service.ts:309`) — admission that server-side TTS doesn't exist
- `infra/coturn/turnserver.conf` — config exists but isn't deployed in docker-compose or K8s
- iOS app in `mobile/ios-archived/` — labeled "archived", suggests abandoned

**9. Is every MCP integration actually functional?**  
**The MCP server itself is functional** (starts, lists tools, handles calls). But it has **no AI provider to connect to**. It's an API adapter without an API consumer. The MCP server:
- ✅ Starts with stdio and/or SSE transport
- ✅ Proxies 5 tool calls to the backend API
- ❌ Has no connected AI client (OpenAI, Anthropic, Claude, ChatGPT, etc.)
- ❌ Has no authentication from the MCP consumer perspective (when MCP_API_KEY is empty)

**10. What are the TOP 20 remaining blockers?**

| # | Blocker | Severity | Category |
|---|---------|----------|----------|
| 1 | **No TTS implementation** — all speech claims are "phone-side" | CRITICAL | AI Integration |
| 2 | **No STT implementation** — `STT_ENABLED=true` in .env is a lie | CRITICAL | AI Integration |
| 3 | **No AI provider integration** — no OpenAI, Anthropic, Ollama, or any LLM | CRITICAL | AI Integration |
| 4 | **No telephony/PSTN** — no Twilio, no SIP, no phone number to call | CRITICAL | Telephony |
| 5 | **No WebRTC in backend** — only WebSocket signaling, no media plane | CRITICAL | Signaling |
| 6 | **PostgreSQL not in docker-compose** — requires external DB setup | HIGH | Deployment |
| 7 | **No DB migration tool** — schema.sql must be applied manually | HIGH | Database |
| 8 | **No Prometheus metrics endpoint** — /api/v1/metrics is custom JSON | HIGH | Monitoring |
| 9 | **No Grafana dashboards** — referenced but don't exist | HIGH | Monitoring |
| 10 | **No CI/CD pipeline** — GitHub Actions workflow doesn't exist | HIGH | Infrastructure |
| 11 | **coturn not in docker-compose** — config exists but isn't deployed | HIGH | Deployment |
| 12 | **Android app needs building/verification** — source exists but build status unknown | HIGH | Mobile |
| 13 | **iOS app is archived** — `ios-archived` suggests abandonment | HIGH | Mobile |
| 14 | **Root vs backend .env.example mismatch** — confusing for new users | MEDIUM | Configuration |
| 15 | **Dead config vars** — `SIGNALING_PORT`, `STT_ENABLED`, `STT_MODEL` in .env never read | MEDIUM | Configuration |
| 16 | **`burn-in.ts` exported but never called** — dead code | LOW | Code Quality |
| 17 | **`event-bus/publisher.ts` exported but never used** — dead code | LOW | Code Quality |
| 18 | **WebSocket token in query param** — logged in URLs, potential leak | MEDIUM | Security |
| 19 | **Event subscribers are logging-only** — no real side effects | LOW | Architecture |
| 20 | **No input validation library** — Zod not used despite docs referencing validation patterns | MEDIUM | Code Quality |

### Final Verdict

VoiceBridge is a **well-architected backend skeleton** with an excellent event system, persistence layer, and operational maturity (graceful shutdown, health checks, retries, recovery). However, it is **not a deployable product**. The critical missing pieces are the actual AI integration (LLMs, TTS, STT) and telephony connectivity (Twilio/SIP/WebRTC) that would make it a "voice bridge." The MCP server functions as an API gateway but has no AI consumers connected.

To reach "fully operational," the project needs approximately **3–6 months of focused development** on:
1. AI provider integration (at minimum one of OpenAI/Anthropic/Ollama)
2. TTS/STT implementation (server-side or integrated provider)
3. Telephony integration (Twilio or SIP)
4. WebRTC media plane
5. Mobile app completion (build, test, publish)
6. Deployment infrastructure completion (DB in compose, CI/CD, monitoring)
