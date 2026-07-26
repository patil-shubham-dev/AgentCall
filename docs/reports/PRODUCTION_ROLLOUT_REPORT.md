# Production Rollout Report — VoiceBridge v1.0.0

> **⚠️ UNVERIFIED — No production infrastructure available.**
>
> This document describes the production rollout procedure. It must only be
> executed after staging sign-off is complete and all validation steps pass.

## Pre-Rollout Checks

- [ ] Staging sign-off obtained (see STAGING_SIGNOFF.md)
- [ ] Database schema migrated (run `schema.sql` against production DB)
- [ ] SERVICE_TOKEN rotated from staging (generate fresh)
- [ ] DATABASE_URL points to production PostgreSQL instance
- [ ] TLS certificate valid (Caddy auto-TLS or cert-manager)
- [ ] Monitoring stack deployed and alerting configured
- [ ] Backups configured (database snapshot + config)
- [ ] Rollback plan documented (see Rollback section below)
- [ ] Team notified of maintenance window (if any)

## Canary Procedure (First Week)

1. Deploy to a single pod/replica
2. Monitor for 10 minutes: error rate, latency, memory, CPU
3. If stable, scale to 2 replicas
4. If stable for 30 minutes, scale to full replica count
5. If any alert fires during canary → rollback immediately

## Production Deploy

```bash
# Tag the release
git tag -a v1.0.0 -m "VoiceBridge v1.0.0 — Solo Bridge"
git push origin v1.0.0

# CI/CD will auto-deploy to staging then production (see ci-cd.yml)
# Or manually:
kubectl set image deployment/voicebridge-backend -n voicebridge \
  backend=ghcr.io/<repo>/voicebridge-backend:latest --record

kubectl rollout status deployment/voicebridge-backend -n voicebridge --timeout=120s
```

## Post-Deploy Smoke Tests

- [ ] Health endpoint returns 200
- [ ] WebSocket handshake succeeds
- [ ] Call creation and completion works
- [ ] Messages are relayed correctly
- [ ] Database persistence is working (check call records)
- [ ] Metrics are being scraped (Prometheus target up)
- [ ] Logs are flowing to centralized logging
- [ ] AlertManager is not firing

## Rollback Plan

```bash
# Rollback to previous version
kubectl rollout undo deployment/voicebridge-backend -n voicebridge
kubectl rollout status deployment/voicebridge-backend -n voicebridge --timeout=120s

# If database migration needs rollback:
# 1. Restore pre-deployment database snapshot
# 2. Re-deploy previous image version
```

## Sign-Off

| Role | Name | Date | Signature |
|------|------|------|-----------|
| Deployer | | | |
| On-call Engineer | | | |
| Approver | | | |

**Status:** ❌ NOT SIGNED OFF — requires real production environment.
