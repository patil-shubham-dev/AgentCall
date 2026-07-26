# Canary Deployment Report

## Canary Strategy

Canary deployment uses Kubernetes Deployment with weighted service routing. Since no real cluster was available, this report defines the canary procedure and expected monitoring thresholds.

## Procedure

```
1. Deploy canary (1 replica) alongside stable (2+ replicas)
   → kubectl apply -f infra/k8s/04-deployment.yaml --selector='canary=stable'
   → kubectl scale deployment voicebridge-backend-canary --replicas=1

2. Route 10% of traffic to canary via service label selector
   → Add canary=true label to canary pod
   → Update service selector to include canary=stable OR canary=true

3. Monitor for 30 minutes:

   METRIC              CANARY THRESHOLD    ACTION
   ──────────────────────────────────────────────────────────
   HTTP 5xx rate       > 0.1%              Rollback immediately
   HTTP p99 latency    > 2x baseline       Investigate; continue if stable
   DB ping latency     > 500ms             Investigate
   Pool waiting        > 5                 Investigate
   Retry rate          > 1%                Investigate
   Memory usage        > 80% limit         Rollback
   CPU usage           > 80% limit         Rollback
   Error rate          > baseline          Rollback

4. Escalate to human if any threshold breached
5. After 30 min with OK metrics: promote to 50% for 30 min
6. After 30 min at 50%: promote to 100%
```

## Expected Resource Usage Per Canary Pod

Based on load test evidence (`npm run test:load`):

| Resource | 100 sessions | 500 sessions | 1000 sessions | Per 10K sessions |
|----------|-------------|-------------|---------------|-------------------|
| Memory Δ | +1MB | +1MB | -1MB (GC) | ~8-10MB |
| Create time | <1ms | 1ms | 1ms | ~10ms |
| Read time | <1ms | <1ms | 1ms | ~5ms |
| Update time | <1ms | <1ms | <1ms | ~5ms |
| Delete time | <1ms | <1ms | 1ms | ~5ms |
| Ops/sec | ∞ | 2,000,000 | 1,333,333 | ~500,000 |

## Monitoring Queries (PromQL)

```promql
# Canary error rate
sum(rate(voicebridge_repo_errors_total{instance=~"canary-.*"}[5m]))
/
sum(rate(voicebridge_repo_errors_total[5m]))
> 0.1

# Canary latency comparison
histogram_quantile(0.99,
  sum(rate(voicebridge_session_findById_duration_bucket{instance=~"canary-.*"}[5m])) by (le)
)
/
histogram_quantile(0.99,
  sum(rate(voicebridge_session_findById_duration_bucket{instance!~"canary-.*"}[5m])) by (le)
)
> 2.0
```

## Rollback Triggers

| Condition | Action | Time |
|-----------|--------|------|
| HTTP 5xx > 0.1% | Immediate rollback | < 1 min |
| Memory > 80% limit | Immediate rollback | < 1 min |
| CPU > 80% limit | Immediate rollback | < 1 min |
| Latency > 2x baseline | Investigation; 5 min threshold | 5 min |
| Error rate > baseline | Investigation; 5 min threshold | 5 min |
| Canary fails readiness probe | Automatic removal (K8s) | < 30s |

## Verdict

**Canary deployment procedure is defined with metric thresholds and rollback triggers. Actual canary execution requires a live Kubernetes cluster with Prometheus monitoring.**
