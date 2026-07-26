# AgentCall — Zero-Cost Architecture Guide

> **Canonical references:** [PRODUCT_VISION.md](../PRODUCT_VISION.md) | [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) | [API_SPEC.md](../API_SPEC.md)

---

## How to Run Everything for Free

This document outlines how to run AgentCall entirely on free-tier services, eliminating all infrastructure costs while supporting MCP-based AI agents.

## Architecture (Free Tier)

```
  AI Agent (Your Machine)
  OpenCode / Claude Code
  - stdio MCP from local
  - NO extra cost
       │
       │ stdio MCP
       ▼
  MCP Server (Your Machine / Suga deploy)
  - Connects to backend via HTTPS
       │
       │ HTTPS
       ▼
  Backend (Free Hosting)
  Options: Suga free tier / Railway / Fly.io
  - Single Node.js process (Fastify)
  - In-memory storage (no database cost)
  - WebSocket signaling
       │
       │ WSS
       ▼
  Android Phone
  - On-device STT + TTS
  - No cloud speech cost
```

## Monthly Cost Breakdown: $0

| Service | What It Does | Cost |
|---------|-------------|------|
| Free hosting (Suga/Railway/Fly.io) | Backend hosting | **$0** |
| GitHub | Source control + CI | **$0** |
| SpeechRecognizer | STT (on-device) | **$0** |
| TextToSpeech | TTS (on-device) | **$0** |
| MCP Server | AI tool interface | **$0** |
| AI Agent | Conversation logic | **$0** |
| Total | | **$0** |

## What Free Architecture Sacrifices

| Feature | Free | Paid Alternative |
|---------|------|-----------------|
| Database persistence | ❌ Data lost on restart | PostgreSQL ($0-15/mo) |
| Call history | ❌ Not available | Requires database |
| Multiple concurrent users | ⚠️ Single process limit | Horizontal scaling |
| Push notifications | ❌ Not available | FCM/APNs (free) |
| STUN/TURN | ❌ Not needed (server-relayed) | Coturn (free) |
| Custom domain | ❌ Subdomain only | Domain ($10/yr) |

## How to Deploy Free

### Option A: Suga (Current)
Currently deployed at production URL.

### Option B: Railway
```bash
cd backend
railway init
railway up
railway variables set SERVICE_TOKEN=your-token
```

### Option C: Fly.io
```bash
cd backend
fly launch
fly deploy
fly secrets set SERVICE_TOKEN=your-token
```

## Running AI Agents for Free

| Tool | Cost | Setup |
|------|------|-------|
| **OpenCode** | ✅ Free | `opencode` CLI, configure MCP |
| **Claude Code** | ⚠️ API costs | Anthropic API key |
| **Cline** | ⚠️ BYO API key | VS Code extension |

For zero AI cost: Use local Ollama with Cline or OpenCode.

## Free Tier Anti-Patterns to Avoid

| Anti-pattern | Better Alternative |
|-------------|-------------------|
| Cloud STT | Android on-device SpeechRecognizer 🆓 |
| Cloud TTS | Android on-device TextToSpeech 🆓 |
| Cloud database for demo | In-memory storage or SQLite 🆓 |
| OpenAI API for every response | Ollama local + OpenCode 🆓 |
| Custom domain | Subdomain from hosting provider 🆓 |

## Current Cost Status

```
Backend hosting:    $0  (Suga)
CI/CD:              $0  (GitHub Actions)
Domain:             $0  (Suga subdomain)
Database:           $0  (in-memory)
STT/TTS:            $0  (on-device)
AI Agent:           $0  (none connected yet)
Certificate:        $0  (Let's Encrypt via Suga)
Total:              $0  (demo-ready)
```
