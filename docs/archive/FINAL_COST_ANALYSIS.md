# Cost Analysis — RC-1

## Memory

| Component | Per Instance | 10 Instances | Notes |
|-----------|-------------|--------------|-------|
| Node.js base | ~40MB | 400MB | V8 baseline |
| InMemory repos (10K sessions) | ~10MB | 100MB | Duplicated across pods |
| MetricsCollector | ~1MB | 10MB | Grows with unique metric names |
| EventBus + subscribers | ~2MB | 20MB | |
| phoneConnections map | ~1KB/connection | ~10MB @10K users | WebSocket references |
| **Total per pod** | **~60-80MB** | **~600-800MB** | Conservative estimate |

**Optimization:** In `database` mode, InMemory repos are dead weight. Removing them saves 10MB/pod.

## CPU

| Operation | CPU Time | Frequency | Notes |
|-----------|----------|-----------|-------|
| Session create (in-memory) | ~0.01ms | Per call | Negligible |
| Session create (DB) | ~2-10ms | Per call | Network + query |
| Message add | ~0.02ms | Per message | Array push + save |
| Sweeper (5min) | ~5ms/1000 sessions | Every 5min | |
| Health check (15s) | ~2ms | Every 15s | DB ping |
| Metrics snapshot | ~1ms | On demand | |

## Database

| Metric | Estimate | Notes |
|--------|----------|-------|
| Connections | 10 per pod × pods | 20-100 connections |
| Storage | ~2KB/session × 10K | ~20MB for sessions |
| JSONB updates | Per message, per status change | Update-heavy workload |
| WAL generation | ~2× data change rate | autovacuum needed |
| Query rate | ~100 reads/s + ~50 writes/s | Per pod, peak |

**Cost estimate (Neon Free Tier):**
- 0.5GB storage included
- 100K compute hours/month
- At 2 pods × 24/7 = 1,460 hours/month → exceeds free tier
- **Paid tier:** ~$20-50/month for 2 pods with 1GB storage

**Cost estimate (RDS/Aurora):**
- db.t4g.micro: ~$15/month
- db.t4g.small: ~$30/month
- Storage: $0.115/GB-month
- **Total: ~$20-40/month**

## Network

| Traffic | Per Request | 1M requests/month |
|---------|-------------|-------------------|
| HTTP request/response | ~2KB | ~2GB |
| WebSocket messaging | ~1KB/message | ~1GB |
| DB queries | ~0.5KB/query | ~50GB (10M queries) |
| Total egress | ~3.5KB/request | ~53GB |

At $0.09/GB egress: ~$4.77/month

## Logging Volume

- pino structured JSON: ~200 bytes/log line
- At 10 lines/request × 1M requests = 10M log lines
- ~2GB of logs/month
- With log retention of 30 days: ~2GB stored
- At $0.03/GB (stdout, captured by Docker/K8s): negligible

## Metrics Storage (Prometheus)

- MetricsCollector produces ~50 metric names
- Each scrape: ~5KB
- At 15s scrape interval: ~864GB/year

**Optimization:** Most metrics are not useful for alerting. Reduce scrape targets to only critical metrics for long-term storage. Use the `/metrics` endpoint for debugging, not for Prometheus.

## Kubernetes Costs (Hetzner)

| Resource | Cost |
|----------|------|
| 2 nodes (CX22, 2CPU, 4GB RAM) | ~$15/month × 2 = $30 |
| Load balancer | ~$5/month |
| Volume storage (10GB) | ~$1/month |
| **Total** | **~$36/month** |

## Total Monthly Cost Estimate

| Component | Low | High |
|-----------|-----|------|
| Compute (Hetzner) | $30 | $60 |
| Database (Neon/RDS) | $0 | $40 |
| Network | $5 | $20 |
| Monitoring | $0 | $30 |
| **Total** | **$35** | **$150** |

## Optimization Opportunities

1. **Remove InMemory repos in `database` mode** — saves 10MB/pod
2. **Reduce HPA max replicas from 10 to 4** — current traffic estimates don't need 10
3. **Metrics pruning** — add metric name bound to prevent unbounded growth
4. **WAL tuning** — set `wal_level = minimal` or appropriate level for backup strategy
5. **Batch health queries** — combine DB ping with session counts to reduce query count
6. **Reduce scrape frequency** — 30s instead of 15s for Prometheus

## Score

**Cost: 8/10** — Reasonable for the feature set. Main cost is compute. Database costs are predictable. No expensive third-party services. Some optimization opportunities but no runaway costs.
