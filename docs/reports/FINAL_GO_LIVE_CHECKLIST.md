# Final Go-Live Checklist

## Pre-Deployment

### Infrastructure

- [ ] **Create K8s namespace:** `kubectl apply -f infra/k8s/01-namespace.yaml`
- [ ] **Create secrets:** Populate `voicebridge-secrets` with actual `SERVICE_TOKEN`, `DB_PASSWORD`, `REDIS_PASSWORD`
  ```bash
  kubectl create secret generic voicebridge-secrets \
    --from-literal=SERVICE_TOKEN=$(openssl rand -hex 32) \
    --from-literal=DB_PASSWORD=<pg-password> \
    --from-literal=REDIS_PASSWORD=<redis-password> \
    -n voicebridge
  ```
- [ ] **Deploy ConfigMap:** `kubectl apply -f infra/k8s/03-configmap.yaml`
- [ ] **Verify PostgreSQL is running** and reachable from the cluster
- [ ] **Verify Redis is running** and reachable from the cluster
- [ ] **Deploy Caddy Ingress Controller** (or ensure nginx ingress is installed with cert-manager)
- [ ] **Apply all K8s manifests in order:**
  1. `01-namespace.yaml`
  2. `02-secret.yaml` (after populating)
  3. `03-configmap.yaml`
  4. `04-deployment.yaml`
  5. `05-service.yaml`
  6. `06-ingress.yaml`
  7. `07-hpa.yaml`
  8. `08-pdb.yaml`
  9. `09-network-policy.yaml`

### Application Configuration

- [ ] Set `PERSISTENCE_MODE=database`
- [ ] Set `DB_POOL_SIZE=50` (minimum; adjust based on traffic)
- [ ] Set `DB_POOL_ACQUIRE_TIMEOUT=10000` (10 seconds)
- [ ] Set `NODE_ENV=production`
- [ ] Set `LOG_LEVEL=info` (use `warn` for high-throughput)
- [ ] Set `NODE_OPTIONS="--max-old-space-size=512"` (per-pod memory limit)
- [ ] Verify `SERVICE_TOKEN` is a cryptographically random string (min 32 bytes)

### Docker Image

- [ ] Build image: `docker build -t voicebridge-backend:latest -f backend/Dockerfile backend/`
- [ ] Push to registry: `docker tag ... && docker push ...`
- [ ] Verify image runs locally: `docker run --rm voicebridge-backend:latest node -e "require('./dist/index')"`
- [ ] Verify non-root user: `docker run --rm voicebridge-backend:latest whoami` (should not be `root`)

## Deployment

### Canary (10% traffic)

- [ ] Deploy 1 canary pod alongside 2+ stable pods
- [ ] Route 10% traffic to canary via service label selector
- [ ] **Monitor for 30 minutes:**
  - [ ] HTTP 5xx rate > 0.1% → Rollback
  - [ ] Memory/CPU > 80% limit → Rollback
  - [ ] p99 latency > 2x baseline → Investigate
  - [ ] Pool waiting > 5 → Investigate
- [ ] If OK after 30 min: promote to 50% traffic

### Canary (50% traffic)

- [ ] Route 50% traffic to canary
- [ ] **Monitor for 30 minutes** (same thresholds as 10%)
- [ ] If OK: promote to 100%

### Full Rollout

- [ ] Scale up to 2+ replicas: `kubectl scale deployment voicebridge-backend --replicas=2`
- [ ] Verify all pods are Ready: `kubectl get pods -n voicebridge -w`
- [ ] Verify ingress DNS resolves: `nslookup voicebridge.yourdomain.com`
- [ ] Verify TLS certificate is valid: `curl -vI https://voicebridge.yourdomain.com/api/v1/health`
- [ ] Verify health endpoint: `curl https://voicebridge.yourdomain.com/api/v1/health`
- [ ] Verify auth enforcement: `curl -v https://voicebridge.yourdomain.com/api/v1/calls` → 401

## Post-Deployment Validation

### Functional Tests

- [ ] Create a call: `curl -X POST https://.../api/v1/calls -H "Authorization: Bearer $TOKEN" -d '{"from":"+1234","to":"+5678"}'`
- [ ] Get call by ID: `curl https://.../api/v1/calls/:callId -H "Authorization: Bearer $TOKEN"`
- [ ] Add AI message: `curl -X POST https://.../api/v1/calls/:callId/messages -H "Authorization: Bearer $TOKEN" -d '{"role":"ai","content":"Hello"}'`
- [ ] Add user text: `curl -X POST https://.../api/v1/calls/:callId/user-text -H "Authorization: Bearer $TOKEN" -d '{"text":"Hi"}'`
- [ ] Complete call: `curl -X POST https://.../api/v1/calls/:callId/complete -H "Authorization: Bearer $TOKEN"`
- [ ] Cancel call: `curl -X POST https://.../api/v1/calls/:callId/cancel -H "Authorization: Bearer $TOKEN"`
- [ ] Schedule callback: `curl -X POST https://.../api/v1/calls/:callId/callback -H "Authorization: Bearer $TOKEN" -d '{"scheduledAt":"2026-07-27T12:00:00Z"}'`
- [ ] Get transcript: `curl https://.../api/v1/calls/:callId/transcript -H "Authorization: Bearer $TOKEN"`
- [ ] Get user active call: `curl https://.../api/v1/users/:userId/active-call -H "Authorization: Bearer $TOKEN"`
- [ ] Register phone: `curl -X POST https://.../api/v1/phone/register -H "Authorization: Bearer $TOKEN" -d '{"userId":"user1"}'` → returns WS endpoint
- [ ] WebSocket connection: `wscat -c wss://voicebridge.yourdomain.com/phone?token=$SERVICE_TOKEN`

### Observability

- [ ] Verify metrics scraping: `curl https://.../api/v1/metrics -H "Authorization: Bearer $TOKEN"`
- [ ] Confirm Prometheus is scraping the service (check Prometheus target list)
- [ ] Deploy Grafana dashboard: `infra/grafana/dashboards/voicebridge.json`
- [ ] Verify dashboard shows data (not empty panels)
- [ ] Configure AlertManager (defined alert rules in `infra/observability/prometheus/alerts.yml`)
- [ ] Set up notification channels (PagerDuty, Slack, email)

### Performance Validation

- [ ] Run integration tests: `npm run test:integration` (needs live DB)
- [ ] Run load test: `npm run test:load` (baseline comparison)
- [ ] Run 5-minute soak test (verify stable memory)
- [ ] Run 10-minute soak test (verify stable latency)

### Monitoring Check

- [ ] `pool.waiting` metric ≤ 5
- [ ] `session.active` gauge matches expected count
- [ ] `repo.errors` counter = 0 (or near-zero)
- [ ] `repo.slow_queries` counter = 0 (or near-zero)
- [ ] `dual-write.failures` counter = 0
- [ ] `db.ok` gauge = 1
- [ ] `session-lock.conflicts` counter = 0 (or near-zero)

### Failure Mode Validation

- [ ] Restart a pod: `kubectl delete pod voicebridge-backend-xxx` → verify recovery
- [ ] Simulate DB restart: restart PostgreSQL pod → verify health degrades → recovers
- [ ] Scale down to 1 replica: `kubectl scale deployment voicebridge-backend --replicas=1` → verify PDB allows it

## Rollback Plan

### Immediate Rollback Triggers

| Condition | Action | 
|-----------|--------|
| HTTP 5xx rate > 1% | `kubectl rollout undo deployment voicebridge-backend` |
| p99 latency > 5s for 5 min | `kubectl rollout undo deployment voicebridge-backend` |
| Memory/CPU > 90% limit | Scale up: `kubectl scale deployment voicebridge-backend --replicas=5` |
| DB connection errors | Verify DB pod health. Restart DB if needed. |

### Rollback Command

```bash
# Fast: restore previous deployment
kubectl rollout undo deployment voicebridge-backend

# Fast: scale down to stop traffic
kubectl scale deployment voicebridge-backend --replicas=0

# Full: delete and reapply known-good version
kubectl delete -f infra/k8s/04-deployment.yaml
kubectl apply -f infra/k8s/04-deployment.yaml  # with previous image tag
```

## Post-Launch Tasks

- [ ] Verify all production monitoring alerts are firing correctly (test alerts)
- [ ] Create a post-mortem template for production incidents
- [ ] Schedule follow-up performance review (1 week post-launch)
- [ ] Schedule capacity review (1 month post-launch)
- [ ] Add cross-pod locking to backlog (v1.1)
- [ ] Add WebSocket connection per-pod limit to backlog (v1.1)
- [ ] Add E2E test suite to backlog (v1.1)
- [ ] Update runbooks for all failure modes
- [ ] Document production configuration in team wiki
