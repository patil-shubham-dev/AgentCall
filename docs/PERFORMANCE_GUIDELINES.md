# ⚠️ HISTORICAL REFERENCE — Performance Guidelines

> **This document describes aspirational performance targets for a planned multi-service system.**
> **It does NOT describe the current VoiceBridge v1.0 implementation.**
>
> For actual performance baselines, see [OPERATIONS_BASELINE.md](../OPERATIONS_BASELINE.md).
> For actual load test results, see [PERFORMANCE_BASELINE.md](../reports/PERFORMANCE_BASELINE.md).

---

## VoiceBridge v1.0 Performance Baseline

| Metric | Measured Value | Notes |
|--------|---------------|-------|
| Operations/sec (InMemory) | 42,000 ops/sec | Load test: 1600 ops in 38ms |
| Per-op latency (InMemory) | < 0.025ms | Load test |
| Memory per session | ~32 KB | Load test |
| Idle memory | ~40 MB | Node.js baseline + Fastify |
| Startup (no DB) | < 500ms | Code-path analysis |
| Startup (DB, 0 sessions) | < 2s | Phase A + B |
| Shutdown | < 2s | Graceful shutdown |
| Force-kill timeout | 10s | Safety limit |

### Database Mode (projected)

| Operation | Expected latency | Warning threshold |
|-----------|-----------------|-------------------|
| SELECT by primary key | 1-10ms | > 250ms |
| INSERT | 2-10ms | > 250ms |
| UPDATE by primary key | 2-10ms | > 250ms |
| DELETE by primary key | 1-5ms | > 250ms |
| db.ping | 2-30ms | > 500ms |

### Bottlenecks

1. **Pool size:** At 500+ req/s with poolMax=10, pool contention is the first bottleneck
2. **Session listing:** `list()` returns all sessions — O(n) memory per call
3. **Phase A load:** Full table scan with no pagination
4. **Event dispatch:** 14 log-only subscribers add overhead (negligible)

### Instrumentation

All repository operations are wrapped with:
- **Timing:** recorded via `MetricsCollector.recordTiming()`
- **Retry:** 1 retry on transient errors (50-100ms delay)
- **Slow query warning:** logged at `warn` if operation > 250ms

---

## Original Targets (Not Implemented)

The following performance targets from the original design are not measured in v1.0:
- Call setup time (not measured — no WebRTC)
- Voice latency RTT (not applicable — no audio pipeline)
- Push notification delivery (not implemented)
- Event bus latency histogram (not implemented)
- WebSocket heartbeat (not implemented)
