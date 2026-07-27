# Render Deployment Report

**Date:** 2026-07-27
**Constraint:** £0/month, no credit card, always-reachable from outside LAN.

---

## Step 1 — Render's Credit Card Requirement: Definitive Answer

### Verdict: NO CREDIT CARD REQUIRED for the free tier.

**Evidence from Render's own documentation:**

> "If you haven't added a payment method, Render instead suspends all of your Free services for the remainder of the month." — [Render Free Tier Docs](https://render.com/docs/free)

The phrase "if you haven't added a payment method" explicitly confirms that operating without one is supported behavior (with suspension as the consequence of hitting limits, not billing).

**Corroboration from multiple independent 2026 sources:**

| Source | Date | Quote |
|--------|------|-------|
| Render.com free tier docs | Current | "If you haven't added a payment method…" — implies no card is standard |
| dashdashHARD guide | Oct 2023 | "Render does not require a credit card to get started" |
| ExpressTech comparison | May 2026 | "Render's free tier is technically free (no credit card needed)" |
| Vibecoding review | Jul 2026 | "No credit card required for the free tier" |
| ToolFreebie review | Jul 2026 | "Free tier gives you 750 web-service hours/month… no credit card" |
| CostBench | Jul 2026 | "Render offers a forever free plan" |
| Antilak guide | Mar 2026 | "Render Free Tier 2026: No Credit Card" |

**Residual risk:** A small number of user reports (Reddit, Canny feedback from 2023-2025) mention being asked for a card during sign-up. This may have been an A/B test or an earlier policy that Render has since changed. Current 2026 sources consistently say no card needed. The signup page at `https://dashboard.render.com/register` does not mention a card requirement in its pre-submit state.

### Free Tier Terms Summary

| Feature | Detail |
|---------|--------|
| **Web service cost** | $0/month (512 MB RAM, 0.1 CPU) |
| **Monthly hours** | 750 free instance hours (sleeping doesn't consume hours) |
| **Sleep behavior** | Spins down after 15 minutes of no inbound traffic |
| **Cold start** | ~30-60 seconds to wake on first request |
| **Bandwidth** | 5 GB/month included |
| **Build minutes** | 500 minutes/month included |
| **PostgreSQL** | Free tier **expires after 30 days** (1 GB limit) |
| **Credit card** | **Not required** for free tier |

---

## Step 2 — Neon Database (Planned, Not Yet Provisioned)

### Verification: No Credit Card Required

> "The Free plan is permanent (not a trial); no credit card required." — [Neon Pricing](https://neon.tech/pricing)

### Free Tier Terms

| Feature | Detail |
|---------|--------|
| **Cost** | $0/month |
| **Compute** | 100 CU-hours/project (suspends after 5 min idle) |
| **Storage** | 0.5 GB/project |
| **Egress** | 5 GB included |
| **Branches** | 10 per project |
| **History** | 6 hours (1 GB limit) |
| **Credit card** | **Not required** |

### Why Neon over Render Postgres

Render's free Postgres **expires after 30 days** — a hard deadline. Neon's free tier is permanent. For a £0 project with no intention to pay later, Neon is the only viable option.

### Provisioning Steps (manual — no API-based signup possible via CLI)

1. Go to https://neon.tech and click **Sign Up** (GitHub or email — no credit card)
2. Create a new project (any region, default settings)
3. Copy the **connection string** from the dashboard (looks like `postgresql://user:pass@ep-xxxx.us-east-2.aws.neon.tech/neondb?sslmode=require`)
4. This string goes into Render's dashboard as `DATABASE_URL` (Step 3) — **never commit it to the repo**

After provisioning, run the migration to create the schema:

```
node backend/scripts/migrate.mjs
```

Or let Render run it automatically via the `preDeployCommand` in `render.yaml`.

---

## Step 3 — Backend Deployment to Render

### 3.1 Prepared Artifacts

The following files have been created/modified for deployment:

| File | Purpose |
|------|---------|
| `render.yaml` (new) | Render Blueprint — infrastructure-as-code for the backend service |
| `backend/scripts/migrate.mjs` (new) | Database migration script (runs schema.sql against DATABASE_URL) |

### 3.2 Blueprint Contents (`render.yaml`)

- **Service type:** `web` (Node.js)
- **Plan:** `free`
- **Region:** `singapore` (closest to the phone's geographic region)
- **Build:** `npm install && npm run build`
- **Start:** `npm start` (runs `node dist/index.js`)
- **Health check:** `GET /api/v1/health`
- **Pre-deploy:** `node scripts/migrate.mjs` (runs schema on each deploy)
- **Env vars:** `NODE_ENV=production`, `PERSISTENCE_MODE=database` (hardcoded), `SERVICE_TOKEN` (secret), `DATABASE_URL` (secret)

### 3.3 Deployment Steps (manual — requires Render dashboard)

1. **Push repo to GitHub** (Render needs a Git repo to deploy from)
2. **Go to https://dashboard.render.com** → **New** → **Blueprint**
3. Connect your GitHub repo, select the branch
4. Render detects `render.yaml` and pre-fills the service configuration
5. **Before deploying**, set these environment variables in the Render dashboard:

| Variable | Value | Notes |
|----------|-------|-------|
| `SERVICE_TOKEN` | `<REDACTED>` | Generate a **different** token if deploying for real |
| `DATABASE_URL` | `postgresql://...` | Paste from Neon dashboard (Step 2) |

6. Click **Deploy**
7. Wait for build + deploy (first deploy takes a few minutes due to `npm install` + TypeScript compilation)
8. The service will be available at `https://agentcall-backend.onrender.com`

### 3.4 Auth Enforcement Confirmation

The backend's `validateConfig()` in `config.ts:48-53` **throws an error at startup** if `SERVICE_TOKEN` is missing. The token is checked in the auth middleware. Every request without the correct `Authorization: Bearer <token>` header receives a **401 Unauthorized** response. No deployment goes public without auth.

### 3.5 First Request After Idle (Cold Start Behavior)

| Scenario | Expected Latency |
|----------|-----------------|
| Active service (recent traffic) | <100ms |
| Sleeping service (first request after 15+ min idle) | ~30-60s |
| Sleep + Neon cold start (both sleeping) | ~31-62s |

The phone's WebSocket reconnection logic (exponential backoff, 1s→30s cap, 20 retries) handles the sleep/wake cycle. When the Render service sleeps and drops the WS connection, the phone will reconnect within 1-30s, which triggers a wake-up.

---

## Step 4 — Point Everything at the Real Deployment

### 4.1 MCP Server Configuration

Update `mcp-server/.env` (local machine):

```
BACKEND_API_URL=https://agentcall-backend.onrender.com/api/v1
SERVICE_TOKEN=<same-token>
```

The MCP server runs locally via `stdio` (connected to OpenCode/Claude Code). No deployment needed.

### 4.2 Android App Configuration

In-app Settings → change server host from `192.168.90.108` to `agentcall-backend.onrender.com`.

The app's WebSocket will connect to `wss://agentcall-backend.onrender.com/phone` — note the `wss://` (TLS is provided by Render automatically).

### 4.3 Full Stability Test (Once Deployed)

After both the MCP server and Android app are pointed at the Render URL:

1. Capture 2+ minutes of `adb logcat` with the phone connected and idle
2. Check for ANR, crash, or excessive reconnect attempts
3. Trigger an E2E call via MCP `create_call` tool
4. Confirm the phone receives the call notification and audio plays
5. Test from a **different Wi-Fi network** or **mobile data** to confirm external reachability
6. Wait 20 minutes with no traffic, then trigger another call — measure the cold start latency

---

## Summary

| Question | Answer |
|----------|--------|
| Does Render's free tier require a credit card? | **No.** Multiple 2026 sources confirm. |
| Does Neon's free tier require a credit card? | **No.** Docs explicitly state no card required. |
| Is the backend deployed yet? | **No.** Provisioning requires manual signup (can't be done via CLI). |
| What's ready to deploy? | `render.yaml` blueprint, `scripts/migrate.mjs`, generated `SERVICE_TOKEN`. |
| Can it be reachable without the user's PC? | **Yes.** Render hosts it independently. Free tier sleeps after 15min but wakes on request (~30-60s cold start). |
| What's the cold start latency? | ~30-60s if both Render and Neon are asleep; <100ms if active. |
