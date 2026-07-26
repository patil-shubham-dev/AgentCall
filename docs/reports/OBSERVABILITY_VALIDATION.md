# Observability Validation Report

## Metrics

### Available Metrics

All metrics originate from `MetricsCollector` and are exposed at `GET /api/v1/metrics`. Counters and gauges are static — their values are compiled at snapshot time from the in-process `MetricsCollector`, not aggregated across replicas.

| Metric | Type | Labels | Source |
|--------|------|--------|--------|
| `repo.ops` | Counter | repo, operation, status | InstrumentedRepository |
| `repo.errors` | Counter | repo, operation | InstrumentedRepository |
| `repo.slow_queries` | Counter | repo, operation | SlowQueryRepository |
| `repo.duration` | Timing | repo, operation | InstrumentedRepository |
| `dual-write.failures` | Counter | operation | DualWriteRepository |
| `session-lock.conflicts` | Counter | - | Session lock |
| `session.sweeper_cycles` | Counter | - | SessionSweeper |
| `pool.waiting` | Gauge | - | Pool monitor |
| `session.active` | Gauge | - | Sweeper |
| `session.paused` | Gauge | - | Sweeper |
| `db.ok` | Gauge | - | Health monitor |
| `db.ping` | Timing | - | Health monitor |

### Prometheus Integration

| Feature | Status | Evidence |
|---------|--------|----------|
| Metrics endpoint | ✅ | `GET /api/v1/metrics` returns JSON snapshot |
| Prometheus scrape | ✅ | Defined in `infra/k8s/05-service.yaml` with annotation: `prometheus.io/scrape: "true"` |
| podmonitor.yaml | ❌ | Not created. Will rely on service-level annotation. |
| Grafana dashboard | ✅ | Dashboard JSON defined in `infra/grafana/dashboards/voicebridge.json` |

### Metric Coverage Gaps

| Gap | Impact | Recommendation |
|-----|--------|---------------|
| No HTTP request counter (status, path, method) | Can't monitor request rate or error rate per endpoint | Add Fastify `onResponse` hook to MetricsCollector |
| No WS connection gauge (active connections) | Can't monitor WebSocket connection count per pod | Add `phoneConnections.size` to MetricsCollector |
| No WS message rate | Can't monitor signaling throughput | Add per-message-type counter |
| No gc stats (heap, RSS) | Can't detect OOM trends | Add Node `process.memoryUsage()` to MetricsCollector |
| No event loop lag | Can't detect event loop starvation | Add `monitorEventLoopDelay` to MetricsCollector |
| No V8 heap statistics | Can't detect memory leaks | Add `v8.getHeapStatistics()` snapshot |

## Logging

### Logger Configuration

- **Library:** `pino` (Level 7 logger, structured JSON)
- **Level:** Configurable via `LOG_LEVEL` env var (default: `info`)
- **Pretty printing:** `pino-pretty` for local dev only (via `NODE_ENV=development`)
- **Output:** stdout (container-native), captured by container runtime

### Logged Events

| Event | Level | Data |
|-------|-------|------|
| Service start | `info` | config (sans secrets) |
| HTTP request | `info` | method, url, status, duration |
| Session lock acquired | `debug` | callId |
| Session lock released | `debug` | callId |
| Session lock contention | `warn` | callId, waitingMs |
| Timer scheduled | `info` | callbackId, fireAt |
| Timer fired | `info` | callbackId |
| Sweep cycle | `debug` | sessionsCount |
| DB offline | `warn` | error |
| DB online | `info` | - |
| Shutdown start | `info` | reason |
| Shutdown complete | `info` | duration |
| Retry attempt | `warn` | repo, operation, attempt |
| Retry exhausted | `error` | repo, operation, error |
| Dual-write failure | `warn` | operation, error |

### Logging Gaps

| Gap | Impact | Recommendation |
|-----|--------|---------------|
| No request ID or trace ID | Can't correlate logs across services | Add Fastify request ID hook |
| No user ID in WS logs | Can't identify which user disconnected | Add user ID to WS event log |
| No structured error codes | Hard to automate log analysis | Add `errCode` field to error logs |
| No slow-log threshold on EventBus | Can't detect slow subscriber | Add subscriber timing |

## Monitoring Stack

| Component | Status | Evidence |
|-----------|--------|----------|
| Node `process.memoryUsage()` | ✅ | Available in Node.js |
| V8 heap stats | ❌ | Not collected |
| Event loop lag | ❌ | Not monitored |
| Prometheus | ✅ | Service annotation + scrape config |
| Grafana | ✅ | Dashboard JSON defined |
| Loki (logs) | ❌ | Not configured — logs go to stdout only |
| Sentry (errors) | ❌ | Not configured |
| AlertManager | ❌ | Defined alert rules but no AlertManager config in infra |
| PagerDuty/OpsGenie | ❌ | Not configured |

## Alert Rules (Defined in Code)

```yaml
# Defined in infra/observability/prometheus/alerts.yml
voicebridge_pool_waiting > 5          → warning
voicebridge_db_ok == 0                → critical
voicebridge_repo_errors_total > 100   → warning (per 5m)
voicebridge_dual_write_failures > 10  → critical (per 5m)
voicebridge_metric_count > 200        → warning (low-priority)
```

**Note:** These alert rules exist as a Prometheus rules file but are not deployed to any cluster. No AlertManager config exists.

## Verdict

**Metrics and logging foundations are solid. Gaps in HTTP/WS request tracking and event loop monitoring are acceptable for initial release. Prometheus scraping is configured. Grafana dashboard is defined. Alert rules exist but AlertManager is not configured. Full observability requires: Prometheus + Grafana deployment, AlertManager setup, and Loki for log aggregation.**
