# MCP Server — Render Deployment Guide

## Summary

The `mcp-server/` is ready for Render. It runs in **Streamable HTTP / SSE mode** (MCP transport). ChatGPT connects at the root URL (`POST /`). Claude-style clients connect at `GET /sse`.

## What was done

| Change | File | Reason |
|---|---|---|
| Fixed deprecated `--only=production` | `Dockerfile:23` | `--only=production` removed in modern npm; replaced with `--omit=dev` |
| Added `HEALTHCHECK` | `Dockerfile` | Render requires a health check path (`/health`) |
| Created `.dockerignore` | `.dockerignore` | Excludes `node_modules/`, `.env`, `dist/` from Docker context |
| Generated `MCP_API_KEY` | `.env` | `CPJa9WU6bZcQqk7l5IrSjLTgG8moMDxuEO4hp1BNsV0ydRKt` |
| Updated `.env.example` | `.env.example` | Placeholder key for new devs |
| Added `PORT` fallback to config | `src/config.ts:16` | Reads `PORT` first (Render auto-inject), then `MCP_SERVER_PORT`, then `3000` |
| Replaced GPT Actions docs with correct ChatGPT MCP connector | `MCP_SERVER_RENDER_DEPLOYMENT.md` | GPT Actions are legacy; the correct path is Developer Mode + MCP connector URL |
| Fixed deploy instructions: same repo, root dir | `MCP_SERVER_RENDER_DEPLOYMENT.md` | Matches backend's proven pattern; no separate repo needed |
| Fixed region: Frankfurt → Singapore | `MCP_SERVER_RENDER_DEPLOYMENT.md` | Backend is deployed in Singapore; no Hetzner exists |

## Docker build verified

```text
docker build -t agentcall-mcp-test mcp-server/
# ✓ Build succeeded (two-stage, 31s)

docker run -d \
  -e MCP_TRANSPORT=sse \
  -e MCP_API_KEY=... \
  -e SERVICE_TOKEN=... \
  -e BACKEND_API_URL=https://agentcall-66ke.onrender.com/api/v1 \
  -p 3000:3000 \
  agentcall-mcp-test

curl http://localhost:3000/health
# → {"status":"ok","server":"agentcall-mcp","auth_required":true, ...}

curl -X POST http://localhost:3000 -H 'x-api-key: ...' -d '{}'
# → Auth passed, request reached MCP transport ✓
curl -X POST http://localhost:3000 -d '{}'
# → 401 Unauthorized ✓
```

## Required env vars for Render

| Variable | Value | Notes |
|---|---|---|
| `MCP_TRANSPORT` | `sse` | Switches from stdio to HTTP/SSE transport |
| `PORT` | `3000` | Render auto-injects `PORT`. Our code reads `PORT` first, then `MCP_SERVER_PORT` as fallback |
| `NODE_ENV` | `production` | Controls pino log level |
| `BACKEND_API_URL` | `https://agentcall-66ke.onrender.com/api/v1` | Production backend URL |
| `SERVICE_TOKEN` | `kEmu0ahRK8jCNtOAriHUnlpPxBIJ9evXsc61doyLMQw5Vgb2` | Backend service auth token |
| `MCP_API_KEY` | `CPJa9WU6bZcQqk7l5IrSjLTgG8moMDxuEO4hp1BNsV0ydRKt` | API key for AI clients (ChatGPT, etc.) |

> **Port handling:** The config reads `PORT` first (Render auto-inject), then falls back to `MCP_SERVER_PORT`, then defaults to `3000`. No need to set `MCP_SERVER_PORT` explicitly on Render.

## Render deployment steps

### 1. Create Web Service on Render (same repo, root directory = mcp-server)

No new repo needed — the backend already deploys from this repo with Root Directory = `backend`. Do the same for `mcp-server`.

1. Go to [dashboard.render.com](https://dashboard.render.com) → **New +** → **Web Service**
2. Connect the **existing GitHub repo** (the one that already deploys the backend)
3. Configure:
   - **Name:** `agentcall-mcp`
   - **Region:** Singapore (same as the existing backend service)
   - **Branch:** `main`
   - **Root Directory:** `mcp-server`
   - **Runtime:** Docker
   - **Health Check Path:** `/health`
   - **Auto-Deploy:** Yes

4. **Environment Variables** (all required):

```
MCP_TRANSPORT=sse
PORT=3000
NODE_ENV=production
BACKEND_API_URL=https://agentcall-66ke.onrender.com/api/v1
SERVICE_TOKEN=kEmu0ahRK8jCNtOAriHUnlpPxBIJ9evXsc61doyLMQw5Vgb2
MCP_API_KEY=CPJa9WU6bZcQqk7l5IrSjLTgG8moMDxuEO4hp1BNsV0ydRKt
```

5. **Plan:** Starter (free tier) is sufficient for initial use
6. Click **Deploy Web Service**

### 2. Verify deployment

After deploy completes, test:

```bash
curl https://agentcall-mcp.onrender.com/health
# → {"status":"ok","server":"agentcall-mcp","auth_required":true, ...}
```

## Connecting ChatGPT (Developer Mode + MCP Connector)

**Do NOT use GPT Actions / OpenAPI schema.** GPT Actions are a legacy mechanism that wraps JSON-RPC in a REST schema. The correct approach is ChatGPT's **Developer Mode with MCP connector**, which discovers tools automatically via MCP protocol.

### How the server's transport works

The MCP server uses `StreamableHTTPServerTransport` from the MCP SDK (`src/sse.ts:40`). It handles all paths routed through `transport.handleRequest()`:

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `POST /mcp/<key>` | `/mcp/<API_KEY>` | **ChatGPT connector URL** — Streamable HTTP, key embedded in path | URL path segment |
| `POST /` | Root | **Streamable HTTP** — Claude/header-capable clients | `x-api-key` header |
| `GET /sse` | `/sse` | **SSE stream** — Claude-style long-lived connection | `x-api-key` header |
| `GET /health` | `/health` | Health check (public, no auth required) | None |

For ChatGPT Developer Mode, use connector URL: `https://agentcall-mcp.onrender.com/mcp/CPJa9WU6bZcQqk7l5IrSjLTgG8moMDxuEO4hp1BNsV0ydRKt`

### Prerequisites

- **ChatGPT plan:** Plus, Pro, Business, Enterprise, or Edu. (Free does not support custom connectors.)
- **Full MCP support (write actions):** Business, Enterprise, or Edu plans. Pro users get read-only tools.
- **Web app:** Setup is web-only (not mobile).

### Step 1: Enable Developer Mode

1. Open ChatGPT web app → **Settings** → **Apps** → **Advanced settings**
2. Toggle **Developer mode** ON
   - Business: admins/owners enable in **Workspace Settings → Permissions & Roles → Connected Data Developer mode**
   - Enterprise/Edu: admins grant access via RBAC; then toggle in **Settings → Apps → Advanced settings**

### Step 2: Create the connector

1. Go to **Settings → Apps** → click **Create** (or the + button)
2. Fill in:
   - **Name:** `AgentCall MCP`
   - **Server URL:** `https://agentcall-mcp.onrender.com/mcp/CPJa9WU6bZcQqk7l5IrSjLTgG8moMDxuEO4hp1BNsV0ydRKt`
3. **Authentication:** Select **None** (the API key is embedded in the URL path itself)
4. Click **Scan Tools** — ChatGPT will connect and discover the 5 tools automatically
5. Click **Create**

### Step 3: Using the connector

1. Start a new chat
2. Click the tools menu (+) and select **Developer Mode** from the model picker
3. Choose the **AgentCall MCP** connector
4. Try a prompt like: *"Call John at +65xxxxxx to ask about the project status"*

### How auth works — two methods, one key

ChatGPT's MCP connector UI does not support custom HTTP headers (only OAuth 2.0 or no-auth). The `x-api-key` header auth that works for Claude Desktop/Code is not possible in ChatGPT's connector.

**Solution: URL-embedded API key.** The API key is embedded directly in the URL path. ChatGPT treats the connector URL as opaque and sends all requests to that exact path. The server extracts the key from the path segment and validates it.

- **ChatGPT** uses URL: `https://agentcall-mcp.onrender.com/mcp/CPJa9WU6bZcQqk7l5IrSjLTgG8moMDxuEO4hp1BNsV0ydRKt` — key validated from `/mcp/<key>` path
- **Claude Desktop/Code** uses `x-api-key` header with the same key at `POST /` or `GET /sse`
- **Both work simultaneously**, both use the same `MCP_API_KEY` env var

**Tradeoff:** URL-embedded secrets can appear in server access logs, browser history, and referrer headers. For a single-user personal project this is acceptable; for multi-user or enterprise use, implement OAuth 2.0 (see [MCP auth spec](https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization)).

### Tools exposed via MCP

| Tool | Description |
|---|---|
| `create_call` | Initiate a voice call to a phone number |
| `send_message` | Send TTS message during an active call |
| `get_transcript` | Get conversation transcript |
| `complete_call` | Mark call complete with optional result |
| `cancel_call` | Cancel pending/active call |

## Security notes

- **`MCP_API_KEY`** protects the MCP endpoint from unauthorized access. Two auth methods share the same key:
  - `x-api-key` header (Claude Desktop/Code, curl)
  - URL path segment at `/mcp/<key>` (ChatGPT Developer Mode)
- **`SERVICE_TOKEN`** is the backend-to-backend credential for the MCP server to call the REST API. It's distinct from `MCP_API_KEY`.
- URL-embedded secrets can leak into server access logs, browser history, and referrer headers — acceptable for a single-user personal project, but not for multi-user or enterprise use. For production at scale, implement OAuth 2.0.
- If the key is compromised, generate a new one and update both `.env` and Render env vars. No code change needed.

## Local testing (for future changes)

```bash
cd mcp-server

# Run in SSE mode (simulates Render)
MCP_TRANSPORT=sse MCP_API_KEY=test-key npx tsx src/index.ts

# Test health (public)
curl http://localhost:3000/health

# Test auth — x-api-key header (Claude/curl)
curl -X POST http://localhost:3000/ \
  -H 'Content-Type: application/json' \
  -H 'x-api-key: test-key' \
  -d '{}'

# Test auth — URL-embedded key (ChatGPT)
curl -X POST http://localhost:3000/mcp/test-key \
  -H 'Content-Type: application/json' \
  -d '{}'

# Test auth — missing key (should return 401)
curl -X POST http://localhost:3000/ \
  -H 'Content-Type: application/json' \
  -d '{}'
```
