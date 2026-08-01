# ⚠️ HISTORICAL REFERENCE — Infrastructure

> **This document describes the infrastructure as of an earlier phase of the project.**
> **It does NOT describe the current VoiceBridge v1.0 deployment.**
>
> Current deployment targets:
> - **K8s production:** `infra/k8s/` (9 manifests)
> - **Docker Compose:** `infra/docker-compose.yml`
> - **Caddy ingress:** `infra/Caddyfile`
> - **No cloud PaaS (Suga removed).**
>
> See [DEPLOYMENT_GUIDE.md](../DEPLOYMENT_GUIDE.md) for current deployment instructions.

## What We Use Right Now

### 1. Suga (suga.run) — Primary Hosting

| Detail | Value |
|--------|-------|
| **URL** | `https://dydcghsn0my6-production-qgbb8wql.australia-southeast1.suga.run` |
| **Region** | australia-southeast1 (Sydney) |
| **Service** | Backend API + WebSocket (single process, port 4000) |
| **Pricing** | Unknown — appears to be a PaaS like Railway/Fly.io |
| **Limitations** | Auto-generated subdomain; no custom domain currently configured |

**What it does:** Hosts the Node.js backend server. Provides HTTPS termination, routing, and process management. Replaces what Caddy would do in a self-hosted setup.

**Status:** ✅ Active

---

### 2. GitHub — Source Control + CI

| Detail | Value |
|--------|-------|
| **URL** | github.com/patil-shubham-dev (inferred) |
| **Repository** | AgentCall MCP |
| **CI Pipeline** | `.github/workflows/ci.yml` |
| **CI Jobs** | lint -> typecheck -> test -> build Docker |
| **Pricing** | Free tier (2000 min/month for public repos) |

**What it does:** Source control, CI pipeline (no deploy step).

**Limitations:**
- CI runs for every push to `develop`/`staging`/`main` and PRs to `develop`
- No deployment workflow — builds Docker images but doesn't push or deploy
- No test files exist despite Vitest being configured
- Coverage thresholds configured at 80% but never validated

**Status:** ✅ Active (CI only, no deploy)

---

### 3. No Database (In-Memory Only)

| Detail | Value |
|--------|-------|
| **Storage** | In-memory `Map<string, VoiceCallSession>` |
| **Persistence** | None — all data lost on restart |
| **Sharing** | None — single process, single user |

**What it does:** Stores active call sessions and phone WebSocket connections temporarily.

**Why:** Simplicity and zero cost. The docs describe PostgreSQL + Redis but they were removed in the "strip paid infra" refactor (commit `5d0b60a`).

**Limitations:**
- **Data loss on restart** — active calls and all transcripts disappear
- **No horizontal scaling** — sessions can't be shared across instances
- **No analytics** — no call history, no metrics

**Status:** ⚠️ Acceptable for demo/MVP, not for production

---

### 4. Docker — Containerization

| Detail | Value |
|--------|-------|
| **Dockerfiles** | `backend/Dockerfile` |
| **Docker Compose** | `infra/docker-compose.yml` (2 services: backend, caddy) |
| **Base Images** | `node:20-slim` (backend) |
| **Usage** | CI builds only — not deployed via Docker Compose |

**What it does:** Standardizes builds. The Docker Compose is ready for self-hosting but not currently used in production (Suga handles deployment).

**Status:** ✅ Configured, ⚠️ Docker Compose not deployed

---

### 5. Coturn (STUN/TURN) — Planned

| Detail | Value |
|--------|-------|
| **Config** | `infra/coturn/turnserver.conf` |
| **Status** | 📝 Configured but NOT in docker-compose |
| **Purpose** | WebRTC NAT traversal |
| **Why Missing** | WebRTC not yet implemented |

**Status:** ❌ Not deployed

---

### 6. Caddy (Reverse Proxy) — Planned

| Detail | Value |
|--------|-------|
| **Config** | `infra/Caddyfile` |
| **Status** | 📝 Configured but NOT deployed |
| **Purpose** | TLS termination, path-based routing to backend + MCP |
| **Why Not Used** | Suga handles routing directly |

**Status:** ❌ Not deployed

---

## Cost Breakdown

| Service | Cost | Required? | Alternative |
|---------|------|-----------|-------------|
| Suga hosting | Unknown | ✅ Current | Self-host on Hetzner ($4-12/mo) or Railway free tier |
| GitHub | Free | ✅ Current | GitLab, Gitea |
| Android Build | Free | ✅ Current | Any machine with Android SDK |
| Domain | ~$10/yr | ❌ Optional | Suga provides subdomain |
| PostgreSQL | Free (self-hosted) | ❌ Future | Supabase free tier, Neon free tier |
| Redis | Free (self-hosted) | ❌ Future | Upstash free tier, Redis Cloud 30MB free |
| Coturn | Free | ❌ Future | Self-hosted |
| AI APIs | Varies | ❌ BYO | Ollama (free local), OpenRouter (pay-per-use) |

## Infrastructure Migration Path

```
Current (Phase 1)           Phase 2                   Phase 3
┌──────────────┐       ┌──────────────────┐       ┌──────────────────┐
│ Suga (free?)  │       │ Docker Compose   │       │ Docker Compose   │
│ In-memory     │ ───►  │ + PostgreSQL     │ ───►  │ + PostgreSQL     │
│ No database   │       │ (Supabase/Neon)  │       │ + Redis          │
│               │       │ Single host      │       │ + Coturn         │
│               │       │                  │       │ + Monitoring     │
└──────────────┘       └──────────────────┘       └──────────────────┘
```

## How to Self-Host

The infrastructure is designed for easy self-hosting:

```bash
# 1. Clone the repo
git clone https://github.com/patil-shubham-dev/AgentCall.git

# 2. Configure environment
cd AgentCall
cp backend/.env.example backend/.env
# Edit backend/.env with your settings

# 3. Deploy with Docker Compose
cd infra
CADDY_DOMAIN=yourdomain.com docker compose up -d

# 4. Configure DNS A record pointing to your VPS IP
# 5. Caddy auto-provisions Let's Encrypt TLS certificates
```

**Components exposed:**
- `https://yourdomain.com/api/*` — Backend API
- `https://yourdomain.com/phone*` — WebSocket signaling
- `https://yourdomain.com/mcp*` — MCP endpoint (embedded in backend)
- `https://yourdomain.com/` — Status page ("AgentCall" health check)
