# VoiceBridge — Deployment Guide

> **Canonical references:** [docs/archive/ARCHITECTURE_BASELINE.md](./docs/archive/ARCHITECTURE_BASELINE.md) | [docs/archive/OPERATIONS_BASELINE.md](./docs/archive/OPERATIONS_BASELINE.md)

---

## Deployment Options

VoiceBridge supports two deployment modes:

1. **Docker Compose** — single-host development/staging
2. **Kubernetes** — production deployment

---

## Architecture

```
Caddy (reverse proxy, TLS)
  │
  └── Backend API (port 4000)
        ├── REST API (/api/v1/*)
        ├── MCP Endpoint (/mcp — embedded, Streamable HTTP)
        └── WebSocket Gateway (/phone)
              │
              └── PostgreSQL 16 (port 5432)
```

---

## Docker Compose (Self-Hosted)

### Prerequisites

- Docker and Docker Compose
- Domain name pointing to the host (for TLS)

### Steps

```bash
# 1. Clone the repository
git clone <repo-url>
cd AgentCall

# 2. Configure environment
cp backend/.env.example backend/.env
# Edit backend/.env with your secrets (SERVICE_TOKEN, DATABASE_URL if using DB)

# 3. Deploy
cd infra
CADDY_DOMAIN=yourdomain.com docker compose up -d

# 4. Verify
curl https://yourdomain.com/api/v1/health
```

### Environment Variables

Required:
```
SERVICE_TOKEN=<random-64-chars>   # API authentication (all requests must include this)
```

Optional:
```
PORT=4000                          # HTTP port (default: 4000)
NODE_ENV=production                # 'production' or 'development'
DATABASE_URL=postgresql://...      # PostgreSQL connection string
PERSISTENCE_MODE=database          # memory | dual-write | database-read | database
DB_POOL_MIN=2                      # Min pool connections (default: 2)
DB_POOL_MAX=10                     # Max pool connections (default: 10)
DB_POOL_ACQUIRE_TIMEOUT=10000      # Acquire timeout in ms (default: 10000)
DB_POOL_IDLE_TIMEOUT=30000         # Idle timeout in ms (default: 30000)
DB_VERIFICATION_INTERVAL_MS=300000 # Verification interval (0 = off)
CORS_ALLOWED_ORIGINS=*             # CORS origins (default: *)
BODY_LIMIT_BYTES=1048576           # Max request body size (default: 1MB)
SIGNALING_MAX_MESSAGE_SIZE=262144  # Max WS message size (default: 256KB)
SIGNALING_RATE_LIMIT_MESSAGES=30   # WS messages per window (default: 30)
SIGNALING_RATE_LIMIT_WINDOW=10     # Rate limit window in seconds (default: 10)
SIGNALING_CONNECTION_RATE_LIMIT=10 # Max WS connections/sec per IP (default: 10)
```

---

## Kubernetes (Production)

### Prerequisites

- Kubernetes 1.28+ cluster
- cert-manager installed (for TLS)
- nginx ingress controller installed
- PostgreSQL 16 accessible from the cluster

### Deploy in Order

```bash
# 1. Create namespace
kubectl apply -f infra/k8s/01-namespace.yaml

# 2. Create secrets
kubectl create secret generic voicebridge-secrets \
  --from-literal=SERVICE_TOKEN=$(openssl rand -hex 32) \
  --from-literal=DATABASE_URL=postgresql://user:password@host:5432/db \
  -n voicebridge

# 3. Apply ConfigMap
kubectl apply -f infra/k8s/03-configmap.yaml

# 4. Deploy application
kubectl apply -f infra/k8s/04-deployment.yaml
kubectl apply -f infra/k8s/05-service.yaml
kubectl apply -f infra/k8s/06-ingress.yaml
kubectl apply -f infra/k8s/07-hpa.yaml
kubectl apply -f infra/k8s/08-pdb.yaml
kubectl apply -f infra/k8s/09-network-policy.yaml

# 5. Verify
kubectl get pods -n voicebridge -w
# Wait for all pods to be Ready

# 6. Test
curl https://api.voicebridge.example.com/api/v1/health
```

### Health Checks (Built-in)

The deployment manifest configures three probes:

| Probe | Path | Delay | Period | Timeout |
|-------|------|-------|--------|---------|
| Liveness | `GET /api/v1/health` | 15s | 10s | 5s |
| Readiness | `GET /api/v1/ready` | 5s | 10s | 5s |
| Startup | `GET /api/v1/ready` | 5s | 5s | 5s |

### Scaling

- **HPA:** Auto-scales between 2-10 replicas based on CPU (70%) and memory (80%)
- **PDB:** Always at least 1 pod available during voluntary disruptions
- **Rolling update:** `maxUnavailable=0, maxSurge=1` — zero-downtime deploys

### Monitoring

- Prometheus scrape configured via service annotation
- Metrics at `GET /api/v1/metrics` (JSON format — requires adapter for Prometheus ingestion)
- Grafana dashboard JSON in `GRAFANA_DASHBOARDS.md`
- All logs emitted as structured JSON to stdout (pino)

---

## Database Setup

```bash
# Apply schema before first deployment
psql $DATABASE_URL -f backend/src/voicebridge/repositories/schema.sql
```

---

## CI/CD Pipeline

### GitHub Actions

```yaml
on:
  push:
    branches: [main]

jobs:
  test:
    # lint → typecheck → unit tests

  build-and-publish:
    needs: [test]
    # Build Docker images → push to registry

  deploy:
    needs: [build-and-publish]
    # SSH to VPS → docker compose pull → docker compose up
```

### Manual Deploy

```bash
git pull origin main
docker compose build
docker compose up -d --remove-orphans
docker system prune -f
curl -sf https://yourdomain.com/api/v1/health
```

---

## Environment Strategy

| Environment | Purpose | URL | PERSISTENCE_MODE |
|-------------|---------|-----|------------------|
| Development | Local dev | `http://localhost:4000` | `memory` (default) |
| Staging | Pre-production | Staging domain | `dual-write` or `database` |
| Production | Live | Production domain | `database` |

---

## Backups

PostgreSQL backups are an infrastructure responsibility, not managed by the application. Recommended:

- Daily pg_dump to object storage (7 day retention)
- Point-in-time recovery via WAL archiving (if enabled)
- Recovery Time Objective (RTO): < 2 hours
- Recovery Point Objective (RPO): < 24 hours

---

## Rollback

```bash
# Kubernetes: undo last deployment
kubectl rollout undo deployment voicebridge-backend -n voicebridge

# Docker Compose: redeploy previous image tag
CADDY_DOMAIN=yourdomain.com docker compose up -d

# Switch persistence mode (if DB migration caused issues)
# Set PERSISTENCE_MODE=dual-write and restart
```

---

## Keeping the Render Free Tier Warm

The production backend runs on a Render **free** web service, which spins
down after ~15 minutes without inbound traffic; the next request pays a
20-30s cold start. Warm-up is GitHub Actions-only (kept off the phone for
battery reasons):

| Layer | Mechanism | Interval | Notes |
|-------|-----------|----------|-------|
| GitHub Actions | `.github/workflows/keep-render-warm.yml` burst | schedule `*/10` + hourly anchor | Each run pings `/health` every 60s for ~9 min. GitHub heavily **delays** scheduled runs (observed 4-6h gaps on a `*/5` cron), so a run whenever it fires re-warms and holds. The repo is public, so Actions minutes are free. |
| Agent harness | Any MCP call to `/mcp` | on demand | The first agent request spins the instance up; subsequent tool calls are warm. Rings also wake a sleeping instance directly via FCM→app→backend traffic. |

A former on-device layer, `WarmupPingWorker` (15-min `/health` heartbeat), was
removed — newer app builds cancel its enqueued periodic job on startup
(`AgentCallApp.onCreate`), so no manual cleanup is needed on updated devices.

Render's free plan grants ~750 instance-hours/month. The all-GitHub burst
pattern (4 runs/hour x ~9 min held warm) consumes roughly 430 of those,
leaving headroom for rings and agent traffic. Do not add a second free web
service on the same account without disabling a warmer.

If GitHub scheduler lag ever degrades below acceptable spin-up behavior, an
external pinger (cron-job.org, UptimeRobot) is the documented drop-in; the
app stays a passive receiver either way.
