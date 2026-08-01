# MCP Server — Render Deployment Guide

> **SUPERSEDED (2026-08-01).** The standalone `mcp-server/` has been deleted.
> The MCP server is now **embedded in the backend** at `POST /mcp` (Streamable HTTP),
> deployed with the main backend service (`srv-d9jqt3km0tmc73bhm450`, live URL
> `https://agentcall-66ke.onrender.com`). The separate `agentcall-mcp` Render service
> is no longer needed.

## Current setup (what to do instead)

1. Create an AI key in the Android app: **Settings → AI Connections → Add AI**
   (the app shows a one-time key `ac_...` with ready-made snippets).
2. Connect your AI agent to `https://YOUR_SERVER/mcp`:
   - **Bearer auth** (Claude, Cursor, Opencode): `Authorization: Bearer ac_...`
   - **Query-param auth** (ChatGPT — no custom headers):
     `https://YOUR_SERVER/mcp?key=ac_...`

Example for Claude:

```json
{
  "mcpServers": {
    "agentcall": {
      "type": "http",
      "url": "https://agentcall-66ke.onrender.com/mcp",
      "headers": { "Authorization": "Bearer ac_YOUR_KEY" }
    }
  }
}
```

## What the old doc described (historical)

The previous `mcp-server/` was a separate Node.js service (Streamable HTTP/SSE)
that proxied to the backend REST API. It was deleted in favor of the embedded
endpoint — one process, one deploy, one auth story (per-AI keys instead of a
shared `MCP_API_KEY`/`SERVICE_TOKEN` pair).
