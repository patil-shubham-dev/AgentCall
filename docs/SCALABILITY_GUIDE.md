# ⚠️ HISTORICAL REFERENCE — Scalability Guide

> **This document describes an aspirational scalability architecture for a planned multi-service system.**
> **It does NOT describe the current VoiceBridge v1.0 implementation.**
>
> VoiceBridge v1.0 is a **single-process monolithic service** with optional PostgreSQL persistence.
> There is no Redis, no message broker, no microservices.
>
> For actual architecture, see [ARCHITECTURE_BASELINE.md](../ARCHITECTURE_BASELINE.md).
> For actual operations, see [OPERATIONS_BASELINE.md](../OPERATIONS_BASELINE.md).

---

## Current Scaling Model (VoiceBridge v1.0)

### Horizontal Scaling

- Single-process service scaled via K8s Deployment with HPA (2-10 replicas)
- All replicas share PostgreSQL (read/write)
- No Redis, no shared in-memory state
- WebSocket connections are per-pod (no cross-pod handoff)
- Session locks are per-process (no cross-pod coordination)

### Known Limitations for Scaling

| Limitation | Impact | Mitigation |
|------------|--------|------------|
| No cross-pod session lock | Concurrent DB mutations risk lost updates | Low probability in single-user mode |
| Per-process timers | Timer lost if scheduling pod dies | Phase B rebuilds on restart |
| WebSocket dropped on rolling update | Clients must reconnect | Auto-reconnect in Android client |
| InMemory repos in all modes | Memory overhead per pod (~32KB/session) | Acceptable up to 14K sessions/pod |

### Capacity (per pod)

| Metric | Limit |
|--------|-------|
| Concurrent sessions | ~14,000 (before 512MB memory limit) |
| API requests/sec | ~500 (before pool contention at poolMax=10) |
| WebSocket connections | Unbounded (no per-pod limit) |
| Database connections | poolMax (configurable, default 10) |

### Scaling Recommendations

1. Increase `DB_POOL_MAX` before adding replicas (pool exhaustion is first bottleneck)
2. Add replicas when CPU > 70% (HPA configured)
3. Monitor `pool.waiting` — if > 5, increase pool or add replicas
4. Keep sessions per pod under 14,000
5. For higher availability: ensure `minReplicas >= 2` and PDB configured

---

## Original Design (Not Implemented)

The following sections describe the original target architecture that was planned but not implemented in v1.0:

- Redis-backed Event Bus for cross-instance messaging
- PgBouncer for connection pooling
- Partitioned database tables
- Redis PubSub for presence
- Sticky sessions for WebSocket affinity
- Kafka/RabbitMQ for event streaming
