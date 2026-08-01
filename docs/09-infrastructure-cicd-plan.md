# Infrastructure & CI/CD Plan — [DEPRECATED]

> **⚠️ DEPRECATED: This document describes the previous infrastructure plan and is retained for historical context only.**
>
> **Current state:** See [INFRASTRUCTURE.md](./INFRASTRUCTURE.md) for the current deployment.
> **Target architecture:** See [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) for the target infrastructure.
> **Reason for deprecation:** This plan assumed a Hetzner VPS-based deployment with full Docker Compose (PostgreSQL, Redis, coturn, Caddy). The current deployment uses Suga PaaS with in-memory storage. Infrastructure priorities have shifted to align with the new runtime services architecture.

## AgentCall MCP

**Version:** 1.0 (Historical)
**Status:** Deprecated

---

## 1. Infrastructure Overview

**Provider:** Hetzner Cloud
**Model:** Single VPS (CX21: 2 vCPU, 4 GB RAM, 40 GB NVMe)
**OS:** Ubuntu 24.04 LTS
**Orchestration:** Docker Compose

---

## 2. VPS Provisioning

### 2.1 Initial Setup

```bash
# Update system
apt update && apt upgrade -y
apt install -y docker.io docker-compose-plugin ufw fail2ban

# UFW rules
ufw default deny incoming
ufw default allow outgoing
ufw allow 22/tcp       # SSH
ufw allow 443/tcp      # HTTPS (Caddy)
ufw allow 3478/udp     # STUN
ufw allow 5349/tcp     # TURN (TLS)
ufw allow 49152:65535/udp  # TURN relay
ufw enable

# Docker rootless (optional, for production hardening)
# Follow: https://docs.docker.com/engine/security/rootless/
```

### 2.2 Port Mapping

| Port | Protocol | Service | Public |
|------|----------|---------|--------|
| 22 | TCP | SSH | Yes (key only) |
| 443 | TCP | Caddy (HTTPS) | Yes |
| 80 | TCP | Caddy (HTTP redirect) | Yes |
| 3478 | UDP | coturn (STUN) | Yes |
| 5349 | TCP | coturn (TURN/TLS) | Yes |
| 49152-65535 | UDP | coturn (TURN relay) | Yes |
| 5432 | TCP | PostgreSQL | No (Docker network) |
| 6379 | TCP | Redis | No (Docker network) |
| 4000 | TCP | Backend API + MCP Endpoint (embedded) | No (via Caddy proxy) |
| 4000-4001 | TCP | Backend services | No (internal) |

---

## 3. Docker Compose Architecture

```yaml
version: '3.8'

services:
  caddy:
    image: caddy:2-alpine
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./Caddyfile:/etc/caddy/Caddyfile
      - caddy_data:/data
      - caddy_config:/config
    networks:
      - public
      - internal

  backend-api:
    build: ./backend
    env_file: .env
    depends_on:
      postgres:
        condition: service_healthy
      redis:
        condition: service_started
    networks:
      - internal

  postgres:
    image: postgres:16-alpine
    volumes:
      - pg_data:/var/lib/postgresql/data
      - ./init.sql:/docker-entrypoint-initdb.d/init.sql
    env_file: .env
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U $${POSTGRES_USER}"]
      interval: 5s
      timeout: 5s
      retries: 5
    networks:
      - internal

  redis:
    image: redis:7-alpine
    command: redis-server --requirepass ${REDIS_PASSWORD}
    volumes:
      - redis_data:/data
    networks:
      - internal

  coturn:
    image: coturn/coturn:latest
    network_mode: host  # Needs raw UDP access for TURN
    volumes:
      - ./coturn/turnserver.conf:/etc/turnserver.conf
    depends_on:
      - backend-api

volumes:
  pg_data:
  redis_data:
  caddy_data:
  caddy_config:

networks:
  public:
  internal:
    internal: true
```

---

## 4. Caddy Reverse Proxy Configuration

```caddyfile
agentcall.example.com {
    # MCP Endpoint (embedded in backend)
    handle_path /mcp* {
        reverse_proxy backend-api:4000
    }

    # Backend API
    handle_path /api/* {
        reverse_proxy backend-api:4000
    }

    # WebSocket signaling
    handle_path /ws/* {
        reverse_proxy backend-api:4001 {
            header_up Upgrade {>Upgrade}
            header_up Connection {>Connection}
        }
    }

    # Web client (if built)
    root * /var/www/web
    try_files {path} /index.html

    # Security headers
    header {
        Strict-Transport-Security "max-age=63072000"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
        Content-Security-Policy "default-src 'self'"
        Referrer-Policy "strict-origin-when-cross-origin"
    }

    # Rate limiting
    rate_limit {
        zone dynamic {
            key {remote_host}
            events 100
            window 1m
        }
    }
}
```

---

## 5. Coturn Configuration

```ini
# /coturn/turnserver.conf
listening-port=3478
tls-listening-port=5349

# Use Hetzner VPS public IP
relay-ip=YOUR_VPS_IP
external-ip=YOUR_VPS_IP

# Use auth-secret method
use-auth-secret
static-auth-secret=YOUR_COTURN_SECRET

# Realm
realm=agentcall.example.com

# Total relay quota per session (1 MBit/s)
max-bps=1024000

# User quota
user-quota=100

# Total quota
total-quota=1000

# Fingerprints
fingerprint

# No STUN logging
no-stun

# Deny peers with private IPs (unless desired)
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255

# TLS certificate (Let's Encrypt via certbot or Caddy)
cert=/etc/letsencrypt/live/agentcall.example.com/fullchain.pem
pkey=/etc/letsencrypt/live/agentcall.example.com/privkey.pem

# Logging
log-file=/var/log/turnserver.log
no-stdout-log
simple-log
```

---

## 6. CI/CD Pipeline

### 6.1 Branch Strategy

```
main
  └── production deployment
      └── (merge from staging after validation)

staging
  └── pre-production deployment
      └── (merge from develop after CI passes)

develop
  └── active development
      └── (feature branches merged here)
```

### 6.2 GitHub Actions Pipeline

```yaml
name: CI/CD

on:
  push:
    branches: [develop, staging, main]
  pull_request:
    branches: [develop]

jobs:
  test:
    # Runs on every PR and push to develop

  build-and-publish:
    needs: [test]
    if: github.ref == 'refs/heads/staging' || github.ref == 'refs/heads/main'
    steps:
      - name: Build Docker images
        run: docker compose build

      - name: Push to registry
        run: |
          docker tag backend-api ghcr.io/${{ github.repository }}/backend-api:${{ github.sha }}
          docker push ghcr.io/${{ github.repository }}/backend-api:${{ github.sha }}
          # Tag as "latest" for main branch

  deploy-staging:
    needs: [build-and-publish]
    if: github.ref == 'refs/heads/staging'
    runs-on: ubuntu-latest
    steps:
      - name: Deploy to staging VPS
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.STAGING_HOST }}
          username: ${{ secrets.SSH_USER }}
          key: ${{ secrets.SSH_KEY }}
          script: |
            cd /opt/internetcalling
            docker compose pull
            docker compose up -d --remove-orphans
            docker system prune -f

  deploy-production:
    needs: [build-and-publish]
    if: github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    environment: production
    steps:
      - name: Deploy to production VPS
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.PROD_HOST }}
          username: ${{ secrets.SSH_USER }}
          key: ${{ secrets.SSH_KEY }}
          script: |
            cd /opt/internetcalling
            docker compose pull
            docker compose up -d --remove-orphans
            docker system prune -f
```

### 6.3 Deployment Command (Manual Fallback)

```bash
# One-command deploy script
#!/bin/bash
set -e

git pull origin main
docker compose build
docker compose up -d --remove-orphans
docker system prune -f

# Verify
docker compose ps
curl -sf https://agentcall.example.com/api/v1/health
```

---

## 7. Monitoring & Observability

### 7.1 Metrics (Prometheus + Grafana)

| Metric | Source | Dashboard |
|--------|--------|-----------|
| Active calls gauge | Call Manager | Call Overview |
| Call duration histogram | Call Manager | Call Overview |
| Call success rate | Call Manager | Call Overview |
| Signaling WS connections | Signaling Server | Signaling |
| Push delivery latency | Notification Service | Push |
| Push success rate | Notification Service | Push |
| Redis operations/second | Redis Exporter | Redis |
| PostgreSQL query latency | Postgres Exporter | PostgreSQL |
| CPU/Memory/Disk | Node Exporter | System |
| TURN bandwidth | coturn metrics | TURN |

### 7.2 Logging

```yaml
# Centralized logging via Docker's logging driver
# For MVP: docker logs + journald
# For scale: Loki + Promtail

services:
  backend-api:
    logging:
      driver: "journald"
      options:
        tag: "backend"
```

**Log format (structured JSON):**

```json
{
  "timestamp": "2026-07-07T12:34:56.789Z",
  "level": "info",
  "service": "call-manager",
  "call_id": "abc-123",
  "event": "call.status_changed",
  "from": "ringing",
  "to": "connected",
  "duration_ms": 1450
}
```

### 7.3 Alerting

| Alert | Condition | Channel |
|-------|-----------|---------|
| Service down | Container restart count > 3 in 5min | Email + Push |
| Call success rate drop | < 95% over 5min window | Email |
| High TURN usage | Relay bandwidth > 50Mbps | Email |
| Disk usage | > 80% | Email |
| SSL cert expiry | < 30 days | Email |

---

## 8. Disaster Recovery

### 8.1 Backup Strategy

```bash
# PostgreSQL daily backup
0 2 * * * pg_dump -U app internetcalling | gzip > /backups/db/daily/ic_$(date +%Y%m%d).sql.gz

# Weekly encrypted backup to Hetzner Storage Box
0 3 * * 0 gpg --encrypt --recipient admin@example.com /backups/db/daily/ic_$(date +%Y%m%d).sql.gz
                     && scp /backups/db/daily/ic_$(date +%Y%m%d).sql.gz.gpg storagebox:/backups/

# Retention: 7 daily, 4 weekly, 3 monthly
```

### 8.2 Recovery Procedure

```bash
# Full recovery
# 1. Provision new VPS
# 2. Install Docker + Compose
# 3. Clone repository
# 4. Restore PostgreSQL:
scp storagebox:/backups/ic_20260701.sql.gz.gpg .
gpg --decrypt ic_20260701.sql.gz.gpg | gunzip > restore.sql
docker compose exec -T postgres psql -U app < restore.sql
# 5. Start services
docker compose up -d

# RTO: < 2 hours
# RPO: < 24 hours (daily backup) or < 1 hour (with WAL archiving)
```

---

## 9. Staging Environment

- **Purpose:** Pre-production validation, load testing, integration testing
- **Setup:** Identical Docker Compose on separate Hetzner CX11 ($6/month)
- **Data:** Anonymized production snapshot (weekly)
- **Domain:** staging.agentcall.example.com

---

## 10. Secrets Management

```bash
# .env file (never committed, generated per environment)
# For MVP: .env file on VPS, backed up to password manager
# For scale: HashiCorp Vault or Docker secrets

# Security: regenerate .env for production, never reuse staging values in production

POSTGRES_PASSWORD=<random-64-chars>
REDIS_PASSWORD=<random-64-chars>
JWT_SECRET=<rs256-private-key>
JWT_PUBLIC_KEY=<rs256-public-key>
SERVICE_TOKEN=<random-64-chars>
COTURN_SECRET=<random-64-chars>
FCM_SERVER_KEY=<from-firebase-console>
APNS_KEY_ID=<from-apple-developer>
APNS_TEAM_ID=<from-apple-developer>
APNS_PRIVATE_KEY=<p8-key-content>
OAUTH_GOOGLE_CLIENT_ID=<...>
OAUTH_GOOGLE_CLIENT_SECRET=<...>
OAUTH_GITHUB_CLIENT_ID=<...>
OAUTH_GITHUB_CLIENT_SECRET=<...>
OAUTH_APPLE_CLIENT_ID=<...>
OAUTH_APPLE_PRIVATE_KEY=<...>
```
