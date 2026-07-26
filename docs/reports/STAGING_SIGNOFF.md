# Staging Sign-Off — VoiceBridge v1.0.0

> **⚠️ UNVERIFIED — No staging infrastructure available.**
>
> This document describes the staging deployment procedure and validation steps.
> It serves as the deployment runbook for the operator. Every step must be
> executed and verified against a real staging environment before production rollout.

## Prerequisites

- [ ] Docker or Kubernetes cluster (see infra/)
- [ ] PostgreSQL 16 instance accessible
- [ ] DNS record for staging domain pointed to cluster ingress
- [ ] SERVICE_TOKEN generated (`openssl rand -hex 32`)
- [ ] DATABASE_URL configured with staging credentials

## Deployment Procedure

### Option A: Docker Compose

```bash
# Set environment
export SERVICE_TOKEN=$(openssl rand -hex 32)
export DATABASE_URL=postgresql://user:pass@host:5432/voicebridge_staging?sslmode=require
export PERSISTENCE_MODE=database

# Pull latest image
docker pull ghcr.io/<repo>/voicebridge-backend:latest

# Start stack
docker compose -f infra/docker-compose.yml up -d

# Verify
docker compose -f infra/docker-compose.yml ps
```

### Option B: Kubernetes

```bash
# Create namespace and secrets
kubectl apply -f infra/k8s/01-namespace.yaml
kubectl apply -f infra/k8s/02-secret-template.yaml

# Apply config and deployment
kubectl apply -f infra/k8s/03-configmap.yaml
kubectl apply -f infra/k8s/04-deployment.yaml
kubectl apply -f infra/k8s/05-service.yaml
kubectl apply -f infra/k8s/06-ingress.yaml
kubectl apply -f infra/k8s/07-hpa.yaml
kubectl apply -f infra/k8s/08-pdb.yaml
kubectl apply -f infra/k8s/09-network-policy.yaml

# Monitor rollout
kubectl rollout status deployment/voicebridge-backend -n voicebridge --timeout=120s
```

## Validation Steps

- [ ] `GET /api/v1/health` returns 200
- [ ] WebSocket connection to `ws://<staging-host>/ws` succeeds
- [ ] `POST /api/v1/calls` creates a call and returns 201
- [ ] Client can send/receive messages through WebSocket
- [ ] `GET /api/v1/calls/:id` returns call transcript
- [ ] `POST /api/v1/calls/:id/complete` ends the call
- [ ] Logs show expected `[HTTP]`, `[WS]`, `[VOICE]` prefixes
- [ ] Prometheus metrics endpoint (`GET /metrics`) returns data
- [ ] PostgreSQL connection pool establishes successfully (check logs)
- [ ] `PERSISTENCE_MODE=database` creates session records in DB

## Sign-Off

| Role | Name | Date | Signature |
|------|------|------|-----------|
| Deployer | | | |
| QA | | | |
| Approver | | | |

**Status:** ❌ NOT SIGNED OFF — requires real staging environment.
