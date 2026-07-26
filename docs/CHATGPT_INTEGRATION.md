# AgentCall — ChatGPT Integration Guide

> **Canonical references:** [API_SPEC.md](../API_SPEC.md) | [PRODUCT_VISION.md](../PRODUCT_VISION.md) | [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md)

---

## Overview

ChatGPT can communicate with AgentCall through several mechanisms. This document analyzes each option.

---

## Integration Option 1: ChatGPT Actions (Custom GPT)

**Best for:** Non-technical end users, ChatGPT Plus/Pro subscribers

### Requirements
| Item | Needed? | Details |
|------|---------|---------|
| OpenAPI spec | ✅ Required | Must describe all endpoints |
| Public HTTPS URL | ✅ Required | ChatGPT must reach the server |
| Authentication | ⚠️ Optional | API key recommended |
| Rate limiting | ⚠️ Recommended | Prevent abuse |

### Implementation

The MCP Server's SSE transport already supports CORS for `chatgpt.com` and optional API key auth.

### Pros/Cons

**Pros:** Works with ChatGPT Plus, Custom GPTs can be shared
**Cons:** Requires public HTTPS URL, ChatGPT Plus subscription required

---

## Integration Option 2: MCP + Client Mode (ChatGPT Desktop)

**Best for:** Developers using ChatGPT Desktop app

### Implementation
```json
{
  "mcpServers": {
    "agentcall": {
      "command": "node",
      "args": ["/path/to/mcp-server/dist/index.js"],
      "env": {
        "MCP_TRANSPORT": "stdio",
        "BACKEND_API_URL": "https://api.agentcall.dev/api/v1",
        "SERVICE_TOKEN": "your-token"
      }
    }
  }
}
```

---

## Integration Option 3: OpenAI Responses API (Server-side)

**Best for:** Custom applications, production deployments

### Endpoints Required (per [API_SPEC.md](../API_SPEC.md))

| What | Endpoint |
|------|----------|
| Initiate call | `POST /api/v1/calls` |
| Send AI message | `POST /api/v1/calls/:callId/messages` |
| Send user text | `POST /api/v1/calls/:callId/user-text` |
| Get transcript | `GET /api/v1/calls/:callId/transcript` |
| Complete call | `POST /api/v1/calls/:callId/complete` |
| Cancel call | `POST /api/v1/calls/:callId/cancel` |
| Query presence | `GET /api/v1/users/:userId/presence` |
| Send notification | `POST /api/v1/notifications` |

---

## Integration Option 4: Custom Connector Service

Build a middleware service that provides a unified interface for any AI provider.

---

## Comparison Matrix

| Factor | Option 1: Actions | Option 2: Desktop MCP | Option 3: API | Option 4: Custom |
|--------|------------------|---------------------|--------------|-----------------|
| **Complexity** | Low | Medium | High | Medium |
| **Cost** | ChatGPT Plus ($20/mo) | ChatGPT Plus ($20/mo) | OpenAI API token cost | Hosting + API costs |
| **Public URL needed** | ✅ Yes | ❌ No | ✅ Yes | ✅ Yes |
| **Multi-AI support** | ❌ ChatGPT only | ChatGPT only | Any (via API) | ✅ Any provider |
| **Setup time** | Hours | Minutes | Days | Days |

## Recommendation

**Short-term:** Option 2 (Desktop MCP) — works today with zero deployment.

**Medium-term:** Option 1 (Custom GPT Action) — expose MCP SSE endpoint for ChatGPT users.

**Long-term:** Option 4 (Custom Connector Service) — aligns with multi-AI vision.

---

## Current Status (July 2026)

| Item | Status |
|------|--------|
| MCP server supports stdio + SSE + StreamableHTTP | ✅ |
| API endpoints match API_SPEC.md | 🟡 Partial |
| JWT auth | ❌ Missing |
| Deployment on public URL | ✅ (Suga) |
| ChatGPT Desktop MCP config | ❌ Not configured |
| Custom GPT manifest | ❌ Not created |
| Multi-provider abstraction | ❌ Missing |
