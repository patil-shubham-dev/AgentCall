# VoiceBridge — Disaster Recovery Plan

## Recovery Objectives

| Metric | Target | Notes |
|---|---|---|
| **RPO** (Recovery Point Objective) | 0s | Data loss only on in-flight writes not yet committed to PostgreSQL |
| **RTO** (Recovery Time Objective) | < 60s | Startup recovery + Phase B timer rebuild completes within seconds |

---

## Backup Strategy

### Database (PostgreSQL)

- **Automated backups:** PostgreSQL continuous archiving (WAL) — 15-minute granularity
- **Full backups:** Daily `pg_dump` with `--format=custom`
- **Retention:** 30 days daily, 12 months monthly
- **Verification:** Weekly restore test to staging environment
- **Tool:** `pg_dump`, `pg_restore`, or managed DB backup (RDS/Aurora/Cloud SQL)

```bash
# Daily full backup
pg_dump --host=localhost --port=5432 --username=voicebridge \
  --format=custom --file=/backups/voicebridge-$(date +%Y%m%d).dump voicebridge

# Restore
pg_restore --host=localhost --port=5432 --username=voicebridge \
  --dbname=voicebridge --clean /backups/voicebridge-20260726.dump
```

### Configuration

- All configuration via environment variables (`.env` file)
- `.env` backed up separately (contains secrets)
- Kubernetes ConfigMaps and Secrets version-controlled and backed up

---

## Restore Strategy

### Full Restore (from backup)

```bash
# 1. Stop the application
kubectl scale deployment voicebridge-backend --replicas=0

# 2. Drop and recreate database
dropdb voicebridge
createdb voicebridge

# 3. Restore from backup
pg_restore --dbname=voicebridge --clean /backups/voicebridge-20260726.dump

# 4. Restart the application
kubectl scale deployment voicebridge-backend --replicas=2
```

### Point-in-Time Recovery (WAL)

```bash
# 1. Restore base backup
pg_restore --dbname=voicebridge /backups/voicebridge-20260726.dump

# 2. Recover to specific timestamp
pg_ctl -D /var/lib/postgresql/data promote

# Or for managed services: use built-in PITR console/CLI
```

---

## Database Recovery (PostgreSQL)

### Database server failure

1. Promote standby replica (if using streaming replication)
2. Update `DATABASE_URL` in ConfigMap/Secret
3. Restart application pods

```bash
kubectl set env deployment/voicebridge-backend DATABASE_URL="postgres://user:pass@new-host/db"
kubectl rollout restart deployment/voicebridge-backend
```

### Corrupted data

1. Identify the point before corruption
2. Restore from backup or PITR
3. Verify data integrity via `/health` endpoint (sessions/callbacks counts)
4. Run `PersistenceVerifier` to confirm memory-DB consistency

### Connection failure

1. Check network connectivity (`DatabaseHealthMonitor` logs)
2. Verify PostgreSQL is accepting connections
3. Application reconnects on next query (pool handles transparent reconnection for transient failures)

---

## Zero-Downtime Deployment

### Rolling Update (Kubernetes)

```bash
# Update image and roll out gradually
kubectl set image deployment/voicebridge-backend \
  backend=ghcr.io/org/voicebridge-backend:new-version

# Monitor rollout
kubectl rollout status deployment/voicebridge-backend
```

### Requirements for zero downtime

- `maxUnavailable: 0` (K8s deployment config)
- `minReplicas: 2` (always at least one running)
- Readiness probe at `/api/v1/ready` (traffic only routed when ready)
- PodDisruptionBudget: `minAvailable: 1`

### Blue/Green Deployment

1. Deploy new version to a new deployment (`voicebridge-backend-v2`)
2. Run smoke tests against the new deployment
3. Switch service selector to point to `v2`
4. Scale down `v1`

---

## Rollback Procedure

### Application rollback

```bash
# Revert to previous image
kubectl set image deployment/voicebridge-backend \
  backend=ghcr.io/org/voicebridge-backend:previous-version
kubectl rollout status deployment/voicebridge-backend
```

### Persistence mode rollback

```yaml
# If database mode has issues, switch to dual-write:
# kubectl edit configmap voicebridge-config
data:
  PERSISTENCE_MODE: "dual-write"
```

No data migration required — dual-write keeps both memory and DB in sync.

### Full rollback (application + DB)

```bash
# 1. Restore database to pre-deployment state
pg_restore --dbname=voicebridge --clean /backups/pre-deployment.dump

# 2. Deploy previous application version
kubectl rollout undo deployment/voicebridge-backend

# 3. Verify application health
curl http://voicebridge-backend:4000/api/v1/health
```

---

## Regional Failure

If using a single-region deployment:

1. **Failover region** has a warm standby with replicated database
2. Update DNS to point to failover region
3. Application starts, Phase A recovery loads from regional DB

If using multi-region (future):

1. Database cross-region replication (read replicas)
2. Active-passive with automatic failover
3. Application health checks route traffic to healthy region

---

## Incident Response

### Severity Levels

| Level | Definition | Response Time | Escalation |
|---|---|---|---|
| SEV1 | Service unavailable | 15 minutes | Engineering lead |
| SEV2 | Feature degraded | 1 hour | Engineering team |
| SEV3 | Minor issue | 24 hours | On-call engineer |

### Incident Response Process

1. **Detect:** Alert from Prometheus/Grafana or user report
2. **Triage:** Check `/health`, `/ready`, `/metrics` endpoints
3. **Diagnose:** Review logs for errors (pino-structured JSON logs)
4. **Mitigate:** Apply fix, rollback, or switch persistence mode
5. **Resolve:** Verify health, confirm recovery
6. **Post-mortem:** Document root cause, improve monitoring

### Recovery Verification Checklist

- [ ] `/health` returns `status: "ok"`
- [ ] `/ready` returns `status: "ok"`
- [ ] Database ping latency < 500ms
- [ ] No pending errors in logs
- [ ] Callback timers active (check scheduler metrics)
- [ ] Session counts consistent with expected state
