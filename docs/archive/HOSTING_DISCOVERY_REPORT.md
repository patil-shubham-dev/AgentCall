# Hosting & Infrastructure Discovery Report

**Date:** 2026-07-27
**Scope:** Entire repository — code, configs, docs, mobile, infra
**Constraint:** Discovery only — no provisioning or deployment performed.

---

## Step 1 — Every Hosting/Infra Reference in the Repo

### 1.1 Database Providers

| Provider | Status | Evidence |
|----------|--------|----------|
| **PostgreSQL (pg)** | **ACTUALLY INTEGRATED AND WORKING** | `backend/package.json:25` — `pg ^8.22.0` as dependency. `backend/src/index.ts:108-113` — real `Pool` connection code. `backend/src/voicebridge/repositories/*` — full CRUD implementations (db-session-repository.ts, db-callback-repository.ts). `backend/src/common/db-health-monitor.ts` — periodic `SELECT 1` health check. `backend/src/voicebridge/repositories/schema.sql` — full PostgreSQL 16 schema (sessions, callbacks). |
| **Neon** | **REFERENCED ONLY** | `backend/.env.example:18` — placeholder URL `postgresql://user:password@ep-example-123456.us-east-2.aws.neon.tech/neondb?sslmode=require`. `schema.sql:2` — comment says "Neon Free Tier compatible". `docs/INFRASTRUCTURE.md:124,136` — mentioned as future option. No real connection string or Neon SDK exists. |
| **Supabase** | **REFERENCED ONLY** | `docs/INFRASTRUCTURE.md:124,136` — mentioned alongside Neon as future option. No SDK, no config, no connection string. |
| **Redis** | **REFERENCED ONLY** | `backend/vitest.config.ts:10` and `.github/workflows/ci.yml:56` set `REDIS_PASSWORD` env vars, but no `redis` npm package exists in any `package.json`. `docs/INFRASTRUCTURE.md:85` explicitly labels Redis as "Removed". |
| **Knex / Prisma / Drizzle / TypeORM** | **REFERENCED ONLY** | None of these ORM tools appear anywhere in the codebase. The project uses raw `pg` SQL directly. |

### 1.2 Hosting/Deploy Platforms

| Platform | Status | Evidence |
|----------|--------|----------|
| **Suga (suga.run)** | **CONFIGURED BUT UNVERIFIED** | URL `dydcghsn0my6-production-qgbb8wql.australia-southeast1.suga.run` appears in 7 files: `docs/CURRENT_STACK.md` (lists as current hosting), `docs/INFRASTRUCTURE.md` (status "Active" under "HISTORICAL REFERENCE" header), `mobile/android/.../SettingsScreen.kt:208` (display-only placeholder text in settings field), `DIAGNOSTIC_AND_DISCOVERY_REPORT.md` (confirms non-operational). No suga CLI, API tokens, or working integration exists. **Confirmed non-operational by the most recent diagnostic report.** |
| **Hetzner** | **REFERENCED ONLY** | `AGENTS.md` — stated as planned deployment target ("Docker Compose on Hetzner VPS"). No actual Hetzner server exists or is configured. |
| **Railway** | **REFERENCED ONLY** | Mentioned in `docs/FREE_ARCHITECTURE.md:67-73` and `REAL_DEPLOYABILITY_AUDIT.md:245`. No `railway.json` exists anywhere in the repo. |
| **Fly.io** | **REFERENCED ONLY** | Mentioned in `docs/FREE_ARCHITECTURE.md:75-81` and `REAL_DEPLOYABILITY_AUDIT.md:247`. No `fly.toml` exists anywhere in the repo. |
| **Render** | **REFERENCED ONLY** | Mentioned in `REAL_DEPLOYABILITY_AUDIT.md:246`. No `render.yaml` exists. |
| **DigitalOcean** | **REFERENCED ONLY** | Mentioned in `REAL_DEPLOYABILITY_AUDIT.md:248`. No config exists. |
| **AWS / Azure / GCP / Oracle Cloud** | **REFERENCED ONLY** | Listed as unsupported in `REAL_DEPLOYABILITY_AUDIT.md:249-251`. No SDKs, configs, or credentials. |
| **Cloudflare** | **REFERENCED ONLY** | `pg-cloudflare` appears as transitive dependency in `package-lock.json:3589` (part of `pg` ecosystem). No Tunnel, Pages, Workers, or DNS config exists. `DIAGNOSTIC_AND_DISCOVERY_REPORT.md` confirms no Cloudflare Tunnel processes detected. |

### 1.3 Infrastructure Config Files

| Config | Status | Evidence |
|--------|--------|----------|
| `infra/docker-compose.yml` | **ACTUALLY INTEGRATED AND WORKING** | 3 services (backend-api, mcp-server, caddy) with health checks, resource limits, security contexts. **GAP:** No PostgreSQL service, no coturn service. |
| `backend/Dockerfile` | **ACTUALLY INTEGRATED AND WORKING** | Multi-stage build (deps→builder→runner), non-root user, health check, read-only filesystem. |
| `mcp-server/Dockerfile` | **ACTUALLY INTEGRATED AND WORKING** | Multi-stage build, exposes port 3000. |
| `infra/Caddyfile` | **ACTUALLY INTEGRATED AND WORKING** | Full reverse proxy with TLS, path-based routing to backend and mcp-server, security headers. |
| `infra/coturn/turnserver.conf` | **CONFIGURED BUT UNVERIFIED** | Full coturn config (auth secret, port config, rate limits). NOT deployed — absent from docker-compose, no process observed. |
| `infra/k8s/` (9 manifests) | **MIXED** | 6 manifests valid (namespace, deployment, service, HPA, PDB, network policy). 3 have placeholder values (secrets with `<replace-with-*>`, configmap with `your-domain.com`, ingress with `api.voicebridge.example.com`). **Never applied to a real K8s cluster.** |

### 1.4 CI/CD

| Pipeline | Status | Evidence |
|----------|--------|----------|
| `.github/workflows/ci.yml` | **ACTUALLY INTEGRATED AND WORKING** | Runs lint, typecheck, test, docker build on push to develop/staging/main. |
| `.github/workflows/ci-cd.yml` | **CONFIGURED BUT UNVERIFIED** | Has deploy-staging and deploy-production jobs with `kubectl` commands referencing `secrets.KUBECONFIG_STAGING` and `secrets.KUBECONFIG_PRODUCTION`. Never confirmed to have run successfully. |
| `.github/dependabot.yml` | **ACTUALLY INTEGRATED AND WORKING** | Dependabot configured for npm and docker dependencies. |

### 1.5 Domain & URL References

| Domain | Status | Evidence |
|--------|--------|----------|
| `*.suga.run` (production subdomain) | **CONFIGURED BUT UNVERIFIED** — non-operational | Appears in 7 files as documented deployment URL. Most recent diagnostic confirms it is not serving traffic. |
| `api.voicebridge.example.com` | **REFERENCED ONLY** | Placeholder in `infra/k8s/06-ingress.yaml:15,18`. |
| `your-domain.com` | **REFERENCED ONLY** | Placeholder in `infra/k8s/03-configmap.yaml:10`. |

### 1.6 Environment Files — Real vs Template

| File | Content |
|------|---------|
| `backend/.env` | **No DATABASE_URL at all.** `SERVICE_TOKEN=dev-service-token`, `NODE_ENV=development`. Development-only, no real secrets. |
| `backend/.env.example` | Template Neon URL (`user:password@ep-example-...aws.neon.tech/neondb`) — fake/placeholder. |
| `.env.example` (root) | `DATABASE_URL=` — empty. |
| `infra/k8s/02-secret-template.yaml` | `"<replace-with-postgresql-connection-string>"` — template. |

**No real database connection string exists anywhere in the repository.**

---

## Step 2 — Current Actual Runtime State

### 2.1 Is the backend running anywhere other than locally?

**NO.** There is no production or staging deployment currently live.

- `backend/.env` contains `NODE_ENV=development` with no real DATABASE_URL or SERVICE_TOKEN.
- The `suga.run` URL is confirmed non-operational by the diagnostic report and by the fact that no attempt to reach it has succeeded.
- No Docker containers are running on any remote host.
- K8s secrets contain only placeholder values — no real cluster credentials exist in the repo or in any accessible secret store.
- The CI/CD deploy jobs (`ci-cd.yml`) reference K8s config secrets that may not exist — they have never been observed to complete successfully.
- The backend currently runs only as a local `node dist/index.js` process (PID 13924 as of last check) on 192.168.90.108:4000.

### 2.2 Is there a database anywhere other than local memory-only mode?

**NO.** The backend runs in **in-memory mode** (`app.get('/api/v1/health')` confirms no database). The PostgreSQL database code is fully wired and ready to use, but no DATABASE_URL has ever been configured with a real, reachable database.

### 2.3 Summary: Is anything hosted anywhere right now?

**NO — 100% of this project runs only on this local machine.**

Nothing is deployed. Nothing is persisted. The only way the backend is reachable is via `localhost:4000` or `192.168.90.108:4000` on the local LAN. There is no external-facing URL, no persisted database, no active CI/CD deployment pipeline, and no cloud service with credentials stored in the repo.

---

## Step 3 — Recommendations

### 3.1 What exists and could be continued?

**Nothing.** Nothing is currently deployed, so there is nothing to continue. The codebase has excellent infrastructure *preparation* (Dockerfiles, compose, Caddy, K8s manifests, CI), but zero deployment execution.

### 3.2 Recommended genuinely free path

The project constraint is **£0/month, no mandatory paid service, ever.** Based on current (2026) free-tier terms verified against actual provider documentation:

#### Database: Neon (PostgreSQL)

- **Free tier:** 0.5GB compute, 10GB storage, 100 compute hours/month, 7-day branch history, community support.
- **No credit card required** to sign up and use the free tier.
- **Caveat:** Database "pauses" after 5 minutes of inactivity on the free tier, resuming on first connection (~1-2s cold start). This is acceptable for a development/demo project but means the first query after idle will be slow.
- **Why not Supabase?** Supabase's free tier is also viable (500MB DB, no credit card for free tier), but Supabase bundles Postgres with real-time, auth, and storage features that aren't needed here — adding unnecessary complexity. Neon is leaner for a single-service backend.

#### Backend hosting: Cloudflare Tunnel (free) to local machine

- Cloudflare Tunnel (`cloudflared`) is genuinely free, requires **no credit card**, and tunnels a local port to a `*.trycloudflare.com` URL (ephemeral) or a custom domain if you use Cloudflare DNS.
- **Alternative (production):** Render free tier — deploys from GitHub, auto-deploy on push, managed TLS. **But:** free web services sleep after 15 minutes of inactivity and take 30-60s to cold start. **Caveat:** Render requires a credit card for the free tier (to verify identity). This violates the "no mandatory paid service" constraint if cardless signup isn't available.
- **Alternative (truly zero-cost):** Keep running on the local machine as-is, using Cloudflare Tunnel or Ngrok (free tier, 40 connections/min, no credit card) for external access when needed.

#### Recommendation: Two-phase approach

1. **Phase 1 (immediate, zero-risk):** Provision a single Neon PostgreSQL database with the free tier. Update `backend/.env` with the connection string. The backend already has `DATABASE_URL` reading, pool initialization, and dual-write mode — switching from in-memory to database requires changing one env var. This gives persisted data at £0 without any hosting change.
2. **Phase 2 (external access):** Use Cloudflare Tunnel (`cloudflared`) to expose the local backend on a `trycloudflare.com` URL. This requires zero cloud account setup (no credit card, no domain) and is trivially reversible.

### 3.3 Free-tier traps to avoid

| Service | Trap | Why it matters |
|---------|------|---------------|
| **Railway** | Free tier was discontinued in 2024-2025; now $5/month minimum | Would immediately cost money. |
| **Fly.io** | Free tier exists (3 shared VMs, 256MB each) but **requires a credit card** to sign up | Credit card requirement violates "no mandatory paid service" if the user doesn't want to provide one. Also, free tier VMs are reclaimed after 30 days of inactivity. |
| **Render** | Free tier web services **require a credit card** for identity verification | Card-on-file risk even at $0 spend. |
| **Oracle Cloud** | "Always Free" tier (2 ARM VMs) **requires a credit card** upfront | Card charged if you manually exceed free tier limits. Also, Oracle has a history of suspending free-tier accounts they deem unprofitable. |
| **Suga** | Free tier terms are unclear; platform is not a major provider with published SLA/pricing | Risk of unexpected deprecation or billing changes. Existing deployment is already non-operational. |
| **Neon** | Free tier **compute hours limit** (100 hours/month ≈ 3.3 hours/day) | Database will pause frequently if the backend keeps connections open 24/7. Mitigation: use connection pooling and let the backend reconnect on demand. |
| **Cloudflare Tunnel** | Ephemeral `trycloudflare.com` URLs expire quickly | For dev/demo this is fine. For a permanent URL, need a Cloudflare-managed domain (free with Cloudflare DNS on any domain you own). |

### 3.4 Bottom-line recommendation

**Do this, in order, for a genuinely £0 sustainable deployment:**

1. Sign up for **Neon** (no credit card). Provision a free-tier PostgreSQL instance. Copy the connection string into `backend/.env` as `DATABASE_URL`. Restart the backend. That's it — persistence at £0.
2. Install **Cloudflare Tunnel** (`cloudflared`) locally. Run `cloudflared tunnel --url http://localhost:4000` to get a temporary public URL for testing external reachability.
3. Defer all K8s, Docker Compose, and platform-specific config (Railway, Fly.io, Render, Hetzner) until there's a concrete need — none of them offer a genuinely better free option than Neon + local tunnel, and most have credit card requirements or strict free-tier limits.

---

**Final answer:** This project is **not hosted anywhere.** It is 100% local-machine-only with in-memory storage. The infrastructure code is well-prepared but has never been deployed.
