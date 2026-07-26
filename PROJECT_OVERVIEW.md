# AgentCall — Project Overview

## What Is AgentCall?

AgentCall is an open, AI-agnostic communication platform that enables any AI to securely reach humans through voice, notifications, and future communication channels.

It is **not** an AI assistant, chatbot, or MCP client. It is a communication platform — just as GitHub provides repositories and Twilio provides communication APIs, AgentCall provides communication capabilities for AI.

> See [PRODUCT_VISION.md](./PRODUCT_VISION.md) for the full vision and philosophy.

---

## What Problem Does It Solve?

| Problem | How AgentCall Solves It |
|---------|------------------------|
| AI agents can't reach humans when needed | Communication APIs (MCP, REST, WebSocket) let any AI initiate contact |
| Cloud TTS/STT costs money | On-device Android SpeechRecognizer + TextToSpeech = $0 |
| Complex telephony infrastructure | Simple WebSocket + REST bridge translates between AI and human |
| No standard for AI→human communication | MCP protocol + REST provide universal interfaces |
| Users must monitor AI progress | Presence, notifications, and callbacks enable async communication |

---

## Architecture

```
              AI Providers
                    │
        MCP • REST • OpenAPI • Actions
                    │
            AgentCall Runtime
  Authentication • Provider Registry • Session Manager
  Call Manager • Presence Engine • Notification Engine
  Callback Engine • Device Router • History Service
  Communication Gateway • Event Bus
                    │
      Android • Future iOS • Desktop • Web
                    │
                  Human
```

> See [SYSTEM_ARCHITECTURE.md](./SYSTEM_ARCHITECTURE.md) for the full architecture specification.

---

## Core Philosophy

- **AI owns intelligence** — reasoning, planning, memory, context
- **AgentCall owns communication** — voice, notifications, presence, routing, callbacks, devices
- **Humans own decisions** — the user always has the final say

AgentCall never:
- Rewrites prompts
- Performs reasoning
- Enriches AI output
- Generates summaries

---

## What's Implemented Today

| Feature | Status | Details |
|---------|--------|---------|
| WebSocket signaling to phone | ✅ Complete | Auto-connects, auto-reconnects |
| Incoming call UI | ✅ Complete | Lock screen, answer/decline/later |
| Text-to-Speech | ✅ Complete | Android TextToSpeech |
| Speech-to-Text | ✅ Complete | Android SpeechRecognizer |
| 5 MCP tools | ✅ Complete | See API_SPEC.md for full 8-tool spec |
| Production deployment | ✅ Complete | Deployed on Suga |
| Callback scheduling | ✅ Complete | "Call me back in X minutes" |

## What's Missing

| Feature | Status | Priority |
|---------|--------|----------|
| Provider isolation | ❌ Missing | P0 |
| Authentication (JWT/OAuth) | ❌ Missing | P0 |
| Presence system | ❌ Missing | P0 |
| Notification engine | ❌ Missing | P0 |
| Callback engine | ❌ Missing | P0 |
| 3 missing MCP tools | ❌ Missing | P0 |
| Multi-user support | ❌ Missing | P1 |
| Multi-device support | ❌ Missing | P1 |
| Persistent storage | ❌ Missing | P1 |
| iOS support | ❌ Frozen | Future |
| Web client | ❌ Missing | Future |

---

## Assumptions

1. Phone has network connectivity (WebSocket required)
2. Single user currently (multi-user planned)
3. English only (localization planned)
4. Android 8+ (API 26) min SDK
5. AgentCall does not own conversation logic — AI providers handle that

---

## Repo Structure

```
├── backend/          — API server + signaling (Node.js/TypeScript)
├── mcp-server/       — MCP tool server
├── mobile/
│   └── android/      — Android app (Kotlin, Jetpack Compose)
├── infra/            — Docker Compose, Caddyfile, coturn config
└── docs/             — Design documents
```
