# AgentCall v2 — Executive Summary

> Building the operating system for AI-to-human communication
> Date: 2026-07-26 | Architect: Principal Architecture Review

---

## The Vision

AgentCall is **infrastructure** for AI-to-human communication. It provides four
abstractions — sessions, capabilities, delivery, and permissions — that any
MCP-compatible AI agent can use to reach a human user. AgentCall does not run
AI models, generate responses, or decide what to communicate. It is a message
broker with human endpoints, designed to run on the user's own machine.

---

## What Changed from VoiceBridge v1

| | v1 (VoiceBridge) | v2 (AgentCall) |
|---|---|---|
| **Metaphor** | Voice call platform | Communication OS / Message broker |
| **Protocol** | REST API for specific AI backend | MCP-native (any AI agent) |
| **Storage** | PostgreSQL (cloud dependency) | SQLite (embedded, local) |
| **Auth** | SERVICE_TOKEN (single-user) | Per-agent API keys |
| **Mobile** | Voice call app (TTS/STT) | Communication inbox (session viewer) |
| **AI Integration** | Hard-coded to VoiceBridge service | AI-agnostic via MCP |
| **Architecture** | Express REST + services | Single daemon process |
| **Deployment** | Docker Compose (4 services) | Single binary (or npm global) |
| **Lines of code** | ~9,500 | ~2,500 (target, 65% reduction) |

---

## The Architecture (One Paragraph)

A single Node.js process runs on the user's machine, exposing an MCP server
(stdio or SSE). Any AI agent (Claude, ChatGPT, Cursor, OpenCode, Gemini, etc.)
connects via MCP and calls four tools: `request_communication`, `send_message`,
`get_session`, and `cancel_session`. The daemon checks permissions, computes
user presence from device signals, selects the best delivery device, and sends
a push notification. The user's Android app receives the notification, opens a
session viewer, and the user responds. The response flows back through the
daemon to the AI agent. Everything is stored in a single SQLite file. No cloud
AI services. No telephony. No voice pipelines. Text-first, capability-based
communication.

---

## What We Keep from the Current Codebase

- **MCP server infrastructure** (stdio + SSE transports)
- **Android theme** (colors, typography, composables)
- **EventBus** (internal pub/sub)
- **Config and health check** patterns
- **Migration strategy** (Phase 0 preserves existing code, new code in `/daemon/`)

---

## What We Delete (~4,500 lines, 47% of codebase)

- **Entire VoiceBridge domain** (`backend/src/voicebridge/`) — 1,800 lines of
  single-AI, call-oriented code
- **REST API routes** — replaced by MCP
- **Android voice call module** (`call/`) — 1,500 lines of TTS/STT/WebRTC code
- **Infrastructure** (Docker Compose, Caddy, coturn) — single process doesn't
  need orchestration
- **PostgreSQL schemas** — replaced by SQLite
- **iOS archive** — permanently archived

---

## What We Build (~2,500 lines new code)

- **Daemon core**: session engine, policy engine, presence resolver, device
  registry, delivery bus, MCP server
- **Android app**: session list, session detail, agent management, push service,
  device management, settings
- **5 SQLite tables**: agents, sessions, messages, devices, policies

---

## Key Design Decisions

1. **MCP is the only API.** No REST. No GraphQL. No custom protocol. Any
   MCP-compatible AI can use AgentCall without an SDK.

2. **Capabilities, not calls.** Communication is categorized by capability
   (notify, message, decision, approval, confirmation, callback). Each maps
   to a specific UI. New capabilities can be added without changing the daemon.

3. **Presence is derived, not stored.** Computed from device heartbeats +
   calendar + time of day. No state machine. More accurate than manual status.

4. **Permissions are per-agent.** Every AI is independently configurable.
   Four trust levels (blocked → trusted). Quiet hours, rate limits, capability
   restrictions. Simple defaults, powerful when needed.

5. **Delivery is tiered.** Primary device (phone) → secondary devices (phone +
   desktop) → all devices. Acknowledgment protocol prevents duplicate
   notifications.

6. **Local-first.** Daemon runs on user's machine. SQLite file. No cloud
   dependencies beyond FCM/APNs for push, which are acceptable infrastructure.

---

## Migration Timeline (9 Weeks)

| Phase | Duration | Deliverable |
|---|---|---|
| 0 — Preparation | 1 week | `/daemon/` directory, package.json, MCP infra copied |
| 1 — Daemon Core | 2 weeks | Session Engine + SQLite + 4 MCP tools working |
| 2 — Delivery & Policy | 2 weeks | Push notifications reach phone end-to-end |
| 3 — Android App | 3 weeks | Full Android app: sessions, agents, settings, push |
| 4 — Hardening | 1 week | Rate limits, crash recovery, tests, docs |

**Total: 9 weeks to working end-to-end communication.**

---

## Risk Posture

- **Highest risk:** Android app rewrite quality (mitigated by detailed spec and
  phased delivery)
- **Medium risk:** Push notification latency (mitigated by WebSocket fallback)
- **Lowest risk:** Daemon core (MCP infra exists, just new tools)

15 technical risks identified, all with specific mitigations. No unmitigated
risks.

---

## What This Enables

With AgentCall v2, any MCP-compatible AI can:

```
1. "Claude, call me if you find a bug" → Claude schedules a session
2. "ChatGPT, notify me when my flight price drops" → ChatGPT sends a notification
3. "Cursor, get my approval before deploying" → Cursor requests approval
4. "OpenCode, ask me which test framework to use" → OpenCode requests a decision
5. "Gemini, confirm you received my design doc" → Gemini requests a confirmation
6. "Codex, call me back in 30 minutes" → Codex schedules a callback
```

All through the same MCP protocol. All without AgentCall knowing what the AI
is doing. All on the user's own infrastructure. All open-source and free.

---

## The Bottom Line

AgentCall v2 replaces **9,500 lines of opinionated VoiceBridge code** with
**2,500 lines of generic communication infrastructure**. It removes all AI
platform dependencies, all telephony code, all cloud infrastructure, and all
voice-specific complexity. What remains is a single daemon with 4 MCP tools,
5 SQLite tables, and a focused Android app — the smallest possible architecture
that fulfills the vision of an operating system for AI-to-human communication.
