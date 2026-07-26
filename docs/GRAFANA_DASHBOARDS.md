# VoiceBridge — Grafana Dashboards & Alert Rules

## Metrics Exposition

VoiceBridge exposes operational metrics via `GET /api/v1/metrics` (JSON, not Prometheus text format).

> **Important:** Prometheus cannot scrape JSON endpoints directly. To ingest into Prometheus, deploy a **json_exporter** or custom adapter that translates the JSON response to Prometheus text format. The K8s service annotation `prometheus.io/scrape: "true"` assumes a Prometheus-format endpoint; this annotation will only work once an adapter is deployed.

### Recommended Prometheus adapter approach

1. Deploy a sidecar container with the backend that exposes a `/metrics` endpoint in Prometheus format
2. Or use `json_exporter` configured to scrape `/api/v1/metrics` and re-expose as Prometheus text

### Recommended Prometheus config

```yaml
scrape_configs:
  - job_name: 'voicebridge'
    static_configs:
      - targets: ['voicebridge-backend:4000']
    metrics_path: '/api/v1/metrics'
    scheme: http
    scrape_interval: 15s
```

---

## Dashboard 1: VoiceBridge — Overview

### Row 1: Sessions
| Panel | Query | Type |
|---|---|---|
| Active Sessions | `voicebridge_sessions_active` | Stat / Gauge |
| Paused Sessions | `voicebridge_sessions_paused` | Stat / Gauge |
| Completed Sessions | `voicebridge_sessions_completed` | Stat / Gauge |
| Session Rate (created) | `rate(voicebridge_sessions_created_total[5m])` | Time series |

### Row 2: Callbacks & Timers
| Panel | Query | Type |
|---|---|---|
| Active Callbacks | `voicebridge_callbacks_count` | Stat |
| Scheduled Timers | `voicebridge_scheduler_timers` | Stat |
| Callback Schedule Rate | `rate(voicebridge_callbacks_scheduled_total[5m])` | Time series |

### Row 3: Database Health
| Panel | Query | Type |
|---|---|---|
| DB Connected | `voicebridge_db_connected` | Stat / State timeline |
| Ping Latency | `voicebridge_db_ping_ms` | Time series / Heatmap |
| Pool Total | `voicebridge_db_pool_total` | Time series |
| Pool Idle | `voicebridge_db_pool_idle` | Time series |
| Pool Waiting | `voicebridge_db_pool_waiting` | Time series |

### Row 4: Repository Performance
| Panel | Query | Type |
|---|---|---|
| Session Op Duration (p50) | `voicebridge_session_op_duration_p50` | Time series |
| Session Op Duration (p95) | `voicebridge_session_op_duration_p95` | Time series |
| Session Op Duration (p99) | `voicebridge_session_op_duration_p99` | Time series |
| Callback Op Duration | `voicebridge_callback_op_duration_p50` | Time series |
| Error Rate | `rate(voicebridge_repo_errors_total[5m])` | Time series |

### Row 5: Operations
| Panel | Query | Type |
|---|---|---|
| Op Rate (ok) | `rate(voicebridge_session_findById_ok_total[5m])` | Time series |
| Op Rate (error) | `rate(voicebridge_session_findById_error_total[5m])` | Time series |
| Slow Queries | `voicebridge_slow_queries_total` | Time series |

### Row 6: Startup & Shutdown
| Panel | Query | Type |
|---|---|---|
| Startup Duration | `voicebridge_startup_duration_ms` | Stat |
| Shutdown Duration | `voicebridge_shutdown_duration_ms` | Stat |
| Uptime | `voicebridge_uptime_seconds` | Stat |

---

## Dashboard 2: VoiceBridge — Errors & Alerts

| Panel | Query | Type |
|---|---|---|
| Repository Errors | `rate(voicebridge_repo_errors_total[5m])` | Time series |
| Session Op Errors | `rate(voicebridge_session_{...}_error_total[5m])` | Time series |
| Callback Op Errors | `rate(voicebridge_callback_{...}_error_total[5m])` | Time series |
| DB Ping Failures | `voicebridge_db_ping_failures_total` | Time series |
| Slow Queries | `rate(voicebridge_slow_queries_total[5m])` | Time series |

---

## Prometheus Alert Rules

### alerts.yml

```yaml
groups:
  - name: voicebridge
    interval: 30s

    rules:
      - alert: VoiceBridgeDatabaseUnreachable
        expr: voicebridge_db_connected == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "VoiceBridge database is unreachable"
          description: "Database ping has failed for more than 1 minute"

      - alert: VoiceBridgePoolExhaustion
        expr: voicebridge_db_pool_waiting > 5
        for: 1m
        labels:
          severity: warning
        annotations:
          summary: "VoiceBridge DB pool has waiting clients"
          description: "{{ $value }} clients waiting for a database connection"

      - alert: VoiceBridgeHighLatency
        expr: voicebridge_db_ping_ms > 500
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "VoiceBridge database ping latency high"
          description: "Ping: {{ $value }}ms (threshold: 500ms)"

      - alert: VoiceBridgeErrorRateHigh
        expr: rate(voicebridge_repo_errors_total[5m]) > 10
        for: 2m
        labels:
          severity: warning
        annotations:
          summary: "VoiceBridge repository error rate high"
          description: "{{ $value }} errors/sec over 5 minutes"

      - alert: VoiceBridgeSlowQueries
        expr: rate(voicebridge_slow_queries_total[5m]) > 5
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "VoiceBridge slow query rate high"
          description: "{{ $value }} slow queries/sec over 5 minutes"

      - alert: VoiceBridgeRecoveryFailure
        expr: voicebridge_recovery_failure_total > 0
        labels:
          severity: critical
        annotations:
          summary: "VoiceBridge startup recovery failed"
          description: "Startup recovery reported failure"

      - alert: VoiceBridgeHighMemoryUsage
        expr: (node_memory_MemTotal_bytes - node_memory_MemAvailable_bytes) / node_memory_MemTotal_bytes > 0.9
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "VoiceBridge host memory usage > 90%"

      - alert: VoiceBridgeHighCPUUsage
        expr: rate(process_cpu_seconds_total[5m]) > 0.8
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "VoiceBridge CPU usage > 80%"
```

---

## Metric Mapping (JSON → Prometheus Label Convention)

The `/metrics` JSON endpoint returns nested objects. If using a custom adapter:

| JSON Path | Prometheus Metric |
|---|---|
| `.counters["sessions.created"]` | `voicebridge_sessions_created_total` |
| `.counters["sessions.completed"]` | `voicebridge_sessions_completed_total` |
| `.counters["sessions.cancelled"]` | `voicebridge_sessions_cancelled_total` |
| `.counters["callbacks.scheduled"]` | `voicebridge_callbacks_scheduled_total` |
| `.counters["startup.complete"]` | `voicebridge_startup_complete_total` |
| `.counters["repo.errors"]` | `voicebridge_repo_errors_total` |
| `.counters["session.*.ok"]` | `voicebridge_session_{operation}_ok_total` |
| `.counters["session.*.error"]` | `voicebridge_session_{operation}_error_total` |
| `.gauges["sessions.active"]` | `voicebridge_sessions_active` |
| `.gauges["sessions.paused"]` | `voicebridge_sessions_paused` |
| `.gauges["sessions.completed"]` | `voicebridge_sessions_completed` |
| `.gauges["callbacks.count"]` | `voicebridge_callbacks_count` |
| `.gauges["scheduler.timers"]` | `voicebridge_scheduler_timers` |
| `.gauges["db.pool.total"]` | `voicebridge_db_pool_total` |
| `.gauges["db.pool.idle"]` | `voicebridge_db_pool_idle` |
| `.gauges["db.pool.waiting"]` | `voicebridge_db_pool_waiting` |
| `.timings["db.ping"].avg` | `voicebridge_db_ping_ms` |
| `.timings["startup.duration"].avg` | `voicebridge_startup_duration_ms` |
| `.timings["session.*"].*` | `voicebridge_session_{operation}_duration_{quantile}` |
| `.uptime` | `voicebridge_uptime_seconds` |
