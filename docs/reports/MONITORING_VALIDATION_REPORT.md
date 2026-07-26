# Monitoring Validation Report — VoiceBridge v1.0.0

> **Status:** CONFIGS VERIFIED. No Prometheus/Grafana/Alertmanager stack available for live validation.

---

## Metrics Endpoint

| Attribute | Detail | Evidence |
|-----------|--------|----------|
| Path | `GET /api/v1/metrics` | `routes.ts:133-140` |
| Format | JSON | `metrics-collector.ts:46-65` |
| Auth | No (unauthenticated for Prometheus scraping) | `routes.ts:57` |
| Rate limit | 10 requests per 10 seconds | `routes.ts:134` |
| Error response | `{ error: 'METRICS_DISABLED', message }` if no collector | `routes.ts:137` |

## Available Metrics

### Counters (6 types)
| Metric Key | Source | When Incremented |
|-----------|--------|-----------------|
| `sessions.created` | `routes.ts:174` | POST /api/v1/calls |
| `sessions.completed` | `routes.ts:281` | POST /api/v1/calls/:callId/complete |
| `sessions.cancelled` | `routes.ts:292` | POST /api/v1/calls/:callId/cancel |
| `callbacks.scheduled` | `routes.ts:323` | POST /api/v1/calls/:callId/callback |
| `startup.complete` | `index.ts:315` | Server startup complete |
| `repo.*.ok`/`repo.*.error` | InstrumentedRepository | Per repository operation |
| `repo.slow_queries` | InstrumentedRepository | Operation > 250ms |
| `dual-write.failures` | DualWriteRepository | DB write failure in dual-write mode |
| `session-lock.conflicts` | Session lock | Lock contention detected |

### Gauges (7+ types)
| Metric Key | Source | When Set |
|-----------|--------|----------|
| `sessions.active` | `routes.ts:89` | Every /health request |
| `sessions.paused` | `routes.ts:90` | Every /health request |
| `sessions.completed` | `routes.ts:91` | Every /health request |
| `callbacks.count` | `routes.ts:94` | Every /health request |
| `scheduler.timers` | `routes.ts:83` | Every /health request |
| `db.pool.total` | `db-health-monitor.ts` | Every ping cycle |
| `db.pool.idle` | `db-health-monitor.ts` | Every ping cycle |
| `db.pool.waiting` | `db-health-monitor.ts` | Every ping cycle |
| `db.ok` | `db-health-monitor.ts` | Every ping cycle |

### Timings (5+ types)
| Metric Key | Source | Unit |
|-----------|--------|------|
| `startup.duration` | `index.ts:314` | ms |
| `shutdown.duration` | `index.ts:359` | ms |
| `db.ping` | `db-health-monitor.ts` | ms |
| `session.findById` | InstrumentedRepository | ms |
| `session.findByUserId` | InstrumentedRepository | ms |
| `session.create` | InstrumentedRepository | ms |
| `session.save` | InstrumentedRepository | ms |
| `session.delete` | InstrumentedRepository | ms |
| `callback.findById` | InstrumentedCallbackRepository | ms |
| `callback.save` | InstrumentedCallbackRepository | ms |
| `callback.delete` | InstrumentedCallbackRepository | ms |
| `callback.list` | InstrumentedCallbackRepository | ms |

## Prometheus Integration

| Artifact | Status | Location |
|----------|--------|----------|
| Prometheus config | ✅ Documented | `GRAFANA_DASHBOARDS.md` |
| Service annotation | ✅ Present | `infra/k8s/05-service.yaml` |
| Alert rules | ✅ Defined (8 alerts) | `GRAFANA_DASHBOARDS.md` |
| AlertManager config | ❌ Not provided | Not in infra/ |
| Prometheus adapter | ❌ Required | JSON endpoint needs translation |

**Note:** The `/api/v1/metrics` endpoint returns JSON, not Prometheus text format. A `json_exporter` or custom adapter is required for Prometheus ingestion. The K8s service annotation `prometheus.io/scrape: "true"` assumes a Prometheus-format endpoint and will not work without an adapter.

## Grafana Dashboard

| Attribute | Detail |
|-----------|--------|
| File | `GRAFANA_DASHBOARDS.md` |
| Dashboards | 2 (Overview + Errors & Alerts) |
| Panels | 6 rows, ~30 queries |
| Metric mappings | Documented (JSON → Prometheus convention) |

## Alert Rules (8 Defined)

| Alert | Severity | Expression | For |
|-------|----------|-----------|-----|
| VoiceBridgeDatabaseUnreachable | critical | `voicebridge_db_connected == 0` | 1m |
| VoiceBridgePoolExhaustion | warning | `voicebridge_db_pool_waiting > 5` | 1m |
| VoiceBridgeHighLatency | warning | `voicebridge_db_ping_ms > 500` | 2m |
| VoiceBridgeErrorRateHigh | warning | `rate(voicebridge_repo_errors_total[5m]) > 10` | 2m |
| VoiceBridgeSlowQueries | warning | `rate(voicebridge_slow_queries_total[5m]) > 5` | 5m |
| VoiceBridgeRecoveryFailure | critical | `voicebridge_recovery_failure_total > 0` | 0 |
| VoiceBridgeHighMemoryUsage | warning | Host memory > 90% | 5m |
| VoiceBridgeHighCPUUsage | warning | CPU > 80% | 5m |

**Note:** Alert rules reference Prometheus metric names (e.g., `voicebridge_db_connected`) that do NOT exist in the application's JSON metrics endpoint. These names assume a Prometheus adapter has been deployed that translates the JSON keys to these Prometheus names.

## Health Endpoint

| Path | Returns | Rate Limit |
|------|---------|------------|
| `GET /api/v1/health` | Status, DB health, pool stats, session counts | 20/10s |
| `GET /api/v1/ready` | Startup + recovery + DB readiness | 20/10s |

## Logging

| Attribute | Detail |
|-----------|--------|
| Logger | pino (structured JSON) |
| Level | info (production), debug (dev) |
| Transport | stdout |
| Format | JSON lines |
| Samples | `{ level, time, msg, callId?, userId?, elapsed? }` |

## Unverifiable Without Infrastructure

| Requirement | Why Unverifiable | Risk |
|-------------|-----------------|------|
| Prometheus scraping | No Prometheus | Low — config ready |
| Grafana dashboard populated | No Grafana | Low — dashboard defined |
| Alerts firing and resolving | No AlertManager | Medium — rules not tested |
| Metric collection correctness | No running instance | Low — code logic verified |
| Log aggregation (Loki) | No Loki config | Low — stdout is acceptable |
| Dashboard panel correctness | No Grafana | Medium — queries not validated against real data |

## Verdict

**Monitoring configuration is complete.** Metrics endpoint works, 6+ counter types, 7+ gauge types, 5+ timing types are collected. Grafana dashboards are defined, alert rules exist. Two gaps: (1) a Prometheus adapter is required (JSON → Prometheus text) and (2) AlertManager configuration is not provided. These are standard post-deployment setup tasks. Deploy Prometheus + Grafana + json_exporter before directing production traffic.
