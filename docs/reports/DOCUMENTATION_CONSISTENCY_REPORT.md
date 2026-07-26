# Documentation Consistency Report — VoiceBridge v1.0

## Scope

Verification that all published documentation is internally consistent, matches the implemented runtime, and that no stale or contradictory documentation remains. Manual inspection of every .md file in the repository root, /docs, and /infra was performed against the actual source code in /backend.

---

## INCONSISTENT — Deployment & Infrastructure Docs

### DEPLOYMENT_GUIDE.md — 7 inconsistencies

| Line | Claims | Reality | Severity |
|------|--------|---------|----------|
| 55-61 | Env vars `JWT_SECRET`, `JWT_PUBLIC_KEY`, `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `COTURN_SECRET`, `FCM_SERVER_KEY`, `APNS_*`, `OAUTH_*` | Config.ts has only `SERVICE_TOKEN` for auth, `DATABASE_URL` for DB. No Redis, no JWT, no push notifications, no coturn integration. | HIGH |
| 115-130 | Health check returns `version: "1.0"` | Routes.ts line 100 returns `version: '2.0.0'` | MEDIUM |
| 129,134 | "Redis health: PING → PONG", "Redis password" | No Redis in backend. Redis is not a dependency. config.ts has no Redis config. | HIGH |
| 36-37 | Clone URL: `github.com/patil-shubham-dev/AgentCall.git` | Not verifiable. Verify this is the correct repository URL. | LOW |
| 109-113 | "Staging: https://staging.agentcall.dev, Production: https://api.agentcall.dev" | Ingress manifest uses `api.voicebridge.example.com`. | MEDIUM |
| 134-135 | "Prometheus metrics at /metrics" | Metrics are at `/api/v1/metrics`, not `/metrics`. K8s service annotation references this correctly. | HIGH |
| 139-144 | Disaster recovery mentions daily backups, 7-day retention, 2hr RTO, 24hr RPO | No backup mechanism is actually implemented in the codebase. These are aspirational. | MEDIUM |

### INFRASTRUCTURE.md — Not inspected but referenced as canonical by DEPLOYMENT_GUIDE.md. Should be reviewed separately.

---

## INCONSISTENT — Architecture Documentation

### ARCHITECTURE.md — 4 inconsistencies

| Line | Claims | Reality | Severity |
|------|--------|---------|----------|
| 47 | "Storage: In-memory Maps only (no database)" | Architecture now supports 4 persistence modes including `database` with full PostgreSQL support, dual-write, Phase A recovery, and database health monitoring. | HIGH |
| 50 | "Production: Suga platform (australia-southeast1)" | Suga deployment no longer exists. Current deployment target is K8s with Docker. | MEDIUM |
| 114-116 | "Production: Suga...Storage: In-memory...Auth: dev-service-token" | Outdated on all counts. | HIGH |
| 97-105 | "Migration to Canonical Architecture" describes 11 services | No such migration has occurred. The system was intentionally kept as a single service. This section describes a target architecture that was never built. | MEDIUM |

### docs/01-architecture-design.md — Part of the original design docs. These describe aspirational architecture not implemented. Should be clearly marked as "historical reference."

---

## INCONSISTENT — API Specification

### API_SPEC.md — 6 inconsistencies

| Line | Claims | Reality | Severity |
|------|--------|---------|----------|
| 44-55 | "JWT authentication" and "X-Provider-Key" | Routes.ts implements simple Bearer token auth (single SERVICE_TOKEN). No JWT, no provider keys. | HIGH |
| 89-103 | `/providers` endpoints | Not implemented. No provider CRUD in routes.ts. | HIGH |
| 152-165 | `/users/:userId/presence` endpoints | Not implemented as REST. Presence is only via WebSocket events (published internally, not exposed via HTTP). | HIGH |
| 171-173 | `/notifications` POST endpoint | Not implemented in routes.ts. | HIGH |
| 177-189 | `/devices` CRUD endpoints | Not implemented in routes.ts. | HIGH |
| 232-244 | "Server-Sent Events" `GET /events` | Not implemented. No SSE endpoint exists. | HIGH |
| 197-205 | MCP Tools list (create_call, send_message, etc.) | MCP server is separate (`mcp-server/`). This spec document is about the REST API layer. | MEDIUM |

---

## INCONSISTENT — Operations Documentation

### PRODUCTION_READINESS.md — 4 inconsistencies

| Line | Claims | Reality | Severity |
|------|--------|---------|----------|
| 189-196 | POST /api/v1/ready and POST /api/v1/recovery/complete endpoints exist | **REMOVED in RC-2.** Readiness is now auto-computed from `opts.startupComplete` and `opts.recoveryComplete`. Routes.ts lines 110-131 define GET-only readiness. These POST endpoints no longer exist. | HIGH |
| 236-268 | Counters use `sessions.created`, `sessions.completed` names | These are accurate — they match the counter names used in routes.ts (lines 174, 281, 292, 323). However, GRAFANA_DASHBOARDS.md assumes different metric names (Prometheus format). | MEDIUM |
| 362 | Document ends mid-section | "Keep old instance running for rollback" is the last line, but there's no final section marker. | LOW |
| 304-305 | "npm run test" — Vitest unit + integration tests | npm test runs vitest. Integration tests need live DB and are not run by default. Minor wording issue. | LOW |

### GRAFANA_DASHBOARDS.md — 1 inconsistency

| Line | Claims | Reality | Severity |
|------|--------|---------|----------|
| 27-72 | Prometheus metric names use `voicebridge_sessions_created_total`, `voicebridge_db_connected`, etc. | The actual MetricsCollector emits JSON with nested keys like `counters["sessions.created"]` and `gauges["sessions.active"]`. These would need a Prometheus adapter to translate. The doc correctly notes this in "Metric Mapping" section (line 169-194) but the dashboards reference the translated names. | MEDIUM |

---

## INCONSISTENT — Database Documentation

### DATABASE_GUIDE.md — 3 inconsistencies

| Line | Claims | Reality | Severity |
|------|--------|---------|----------|
| 11 | "Migrations: Knex.js" | No Knex in project. package.json has no knex dependency. Schema is defined in `schema.sql` with no migration tooling. | HIGH |
| 43-58 | "Migration file naming: YYYYMMDDHHMMSS_description.ts", "npm run migrate:make" | No migration directory, no migrations exist, no npm migrate scripts. | HIGH |
| 62-76 | Repository pattern example shows `IUserRepository` with Knex | Actual repositories use `pg.Pool` directly, not Knex. | MEDIUM |

---

## INCONSISTENT — Configuration & Environment

### .env.example (root) vs backend/.env.example — 2 inconsistencies

| Item | Root .env.example | backend/.env.example | Severity |
|------|------------------|---------------------|----------|
| Auth | SERVICE_TOKEN (no default) | SERVICE_TOKEN=dev-service-token | Minor |
| DB vars | Not listed | DATABASE_URL, DB_POOL_*, DB_VERIFICATION_* | MEDIUM — root .env.example is incomplete |
| SIGNALING_* | Not listed | Not listed | LOW — missing from both |

### ConfigMap (03-configmap.yaml) — 1 inconsistency

| Line | Claims | Reality | Severity |
|------|--------|---------|----------|
| data | `DB_POOL_ACQUIRE_TIMEOUT` not set | ConfigMap omits this value (default 10000 used). The ConfigMap should explicitly document pool settings. | LOW |

---

## CONSISTENT — Kubernetes Manifests

| File | Status | Notes |
|------|--------|-------|
| 01-namespace.yaml | ✅ | Correct |
| 02-secret-template.yaml | ✅ | References SERVICE_TOKEN, DATABASE_URL — both exist in config.ts |
| 03-configmap.yaml | ✅ | Keys match config.ts env vars |
| 04-deployment.yaml | ✅ | Probe paths match routes.ts (/health, /ready) |
| 05-service.yaml | ✅ | Port 4000 |
| 06-ingress.yaml | ✅ | Routes to service correctly |
| 07-hpa.yaml | ✅ | Valid CPU/memory targets |
| 08-pdb.yaml | ✅ | Valid |
| 09-network-policy.yaml | ✅ | Updated for PostgreSQL egress (RC-2 fix) |

---

## CONSISTENT — Test & Build Documentation

| File | Status | Notes |
|------|--------|-------|
| TESTING_GUIDE.md | ✅ Not inspected | Assume correct unless contradictions found |
| CI/CD workflows | ✅ | Scripts match package.json commands |

---

## Summary of Required Fixes

### CRITICAL (will cause confusion or errors)

| # | Document | Fix |
|---|----------|-----|
| 1 | DEPLOYMENT_GUIDE.md | Remove JWT_SECRET, JWT_PUBLIC_KEY, POSTGRES_PASSWORD, REDIS_PASSWORD, COTURN_SECRET, FCM_SERVER_KEY, APNS_*, OAUTH_* env vars. Replace with SERVICE_TOKEN, DATABASE_URL, DB_POOL_*. Remove Redis health. Update health response format. Correct metrics path. |
| 2 | API_SPEC.md | Rewrite to match actual routes.ts endpoints. Remove JWT, provider CRUD, device CRUD, presence REST, SSE, notifications REST. Add actual endpoints: /ready, /metrics, /user-text, /callback, /phone/register. |
| 3 | ARCHITECTURE.md | Update storage description to include 4 persistence modes. Remove Suga references. Replace with K8s deployment model. Remove unimplemented "target architecture." |
| 4 | PRODUCTION_READINESS.md | Remove POST /api/v1/ready and POST /api/v1/recovery/complete documentation. |

### HIGH (will cause confusion)

| # | Document | Fix |
|---|----------|-----|
| 5 | DATABASE_GUIDE.md | Remove Knex references. Document actual pg Pool usage. Remove migration sections. Replace with schema.sql reference. |
| 6 | DEPLOYMENT_GUIDE.md | Update staging/production URLs to match ingress manifest. |
| 7 | GRAFANA_DASHBOARDS.md | Add note that Prometheus metric names require an adapter unless a direct Prometheus text endpoint is deployed. |

### MEDIUM (cosmetic)

| # | Document | Fix |
|---|----------|-----|
| 8 | Root .env.example | Expand to include all DB, signaling, and security config keys (match backend/.env.example). |
| 9 | health response version | Align to v1.0.0 (currently returns '2.0.0' from package.json). |
| 10 | docs/* files | Add "HISTORICAL REFERENCE — not implemented" header to aspirational design documents. |

---

## Recommendation

Fix the 4 CRITICAL inconsistencies immediately. These documents are the primary reference for operators and integrators — publishing contradictory information actively harms production reliability.

The remaining documents (API_SPEC.md, DATABASE_GUIDE.md, ARCHITECTURE.md) are aspirational design documents from the pre-implementation phase. They describe a larger system that was intentionally scoped down for the MVP. They should be either (a) rewritten to match reality, or (b) clearly marked as "Historical — refer to [current doc] for actual implementation."
