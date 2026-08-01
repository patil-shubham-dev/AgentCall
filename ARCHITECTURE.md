# VoiceBridge — System Architecture

> **This document describes the current implementation architecture.**
> For the permanent reference architecture, see [ARCHITECTURE_BASELINE.md](./ARCHITECTURE_BASELINE.md).

---

## Architecture Overview

```
┌────────────────────────────────────────────────────────────────────────┐
│                      AI PROVIDER LAYER                                 │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────┐ │
│  │ Claude   │  │ ChatGPT  │  │  Gemini  │  │  Ollama  │  │ OpenCode│ │
│  │ (MCP)    │  │(MCP/HTTP)│  │ (MCP API)│  │ (MCP)    │  │ (MCP)   │ │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬─────┘  └────┬────┘ │
│       │ MCP         │ MCP/HTTP    │ MCP          │ MCP         │ HTTP  │
├───────┼──────────────┼─────────────┼──────────────┼─────────────┼───────┤
│       ▼              ▼             ▼              ▼             ▼       │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                MCP ENDPOINT (embedded in backend)                 │  │
│  │  Transport: Streamable HTTP (/mcp)                             │  │
│  │  Tools: create_call | send_message | get_transcript |            │  │
│  │        complete_call | cancel_call | send_message_and_wait │  │
│  └──────────────────────────────┬───────────────────────────────────┘  │
│                                 │ in-process (same service)            │
│  ┌──────────────────────────────▼───────────────────────────────────┐  │
│  │                    BACKEND API (backend/)                         │  │
│  │  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐  │  │
│  │  │ REST API         │  │ WebSocket        │  │ VoiceBridge     │  │  │
│  │  │ (routes.ts)      │  │ Signaling Server  │  │ Service         │  │  │
│  │  │                  │  │ (server.ts)       │  │ (service.ts)    │  │  │
│  │  │ POST   /calls    │  │ /phone?token=    │  │                 │  │  │
│  │  │ GET    /calls/:id│  │                  │  │ Call management │  │  │
│  │  │ POST   .../msgs  │  │ Events:          │  │ Session repos   │  │  │
│  │  │ POST   .../utext │  │ ai_message       │  │ Callback coord  │  │  │
│  │  │ GET    .../trans │  │ call_incoming    │  │ Timer scheduling│  │  │
│  │  │ POST   .../compl │  │ call_ended       │  │                 │  │  │
│  │  │ POST   .../cancl │  │ error            │  │                 │  │  │
│  │  │ GET    /users/   │  │                  │  │                 │  │  │
│  │  │ POST   /phone/reg│  │                  │  │                 │  │  │
│  │  │ GET    /health   │  │                  │  │                 │  │  │
│  │  │ GET    /ready    │  │                  │  │                 │  │  │
│  │  │ GET    /metrics  │  │                  │  │                 │  │  │
│  │  └─────────────────┘  └─────────────────┘  └─────────────────┘  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│  Persistence: 4 modes (memory / dual-write / database-read / database) │
│  Auth: SERVICE_TOKEN (Bearer token for HTTP, query param for WS)       │
├────────────────────────────────────────────────────────────────────────┤
│                         NETWORK (Internet / LAN)                       │
│  Production: K8s with Caddy/nginx ingress                              │
│  Local:      http://localhost:4000                                     │
├────────────────────────────────────────────────────────────────────────┤
│                       ANDROID PHONE LAYER                               │
│  ┌───────────────────────────────────────────┐                        │
│  │  MainActivity                              │                        │
│  │  ├─ HomeScreen (status, calls)              │                        │
│  │  └─ SettingsScreen (server config, test)    │                        │
│  │                                             │                        │
│  │  CallActivity (dark theme, always on)       │                        │
│  │  ├─ Chat bubbles (AI ↔ User)                │                        │
│  │  ├─ Record / Stop / Speaker / Repeat        │                        │
│  │  └─ End Call                                │                        │
│  │                                             │                        │
│  │  IncomingCallActivity (lock screen)          │                        │
│  │  ├─ Answer / Decline / Later                 │                        │
│  │  └─ Priority badges                          │                        │
│  │                                             │                        │
│  │  CallService (foreground service)            │                        │
│  │  ├─ TextToSpeech (on-device)                │                        │
│  │  ├─ SpeechRecognizer (on-device)            │                        │
│  │  ├─ Barge-in detection                       │                        │
│  │  └─ Wake lock                                │                        │
│  │                                             │                        │
│  │  SignalingClient (WebSocket singleton)       │                        │
│  │  ├─ Auto-connect, auto-reconnect (3s)       │                        │
│  │  └─ Event flow: Connected, CallIncoming, etc │                        │
│  └───────────────────────────────────────────┘                        │
└────────────────────────────────────────────────────────────────────────┘
```

---

## Ports & Endpoints

| Service | Port | Protocol | Path |
|---------|------|----------|------|
| Backend API | 4000 | HTTP | `/api/v1/*` |
| WebSocket | 4000 | WS/WSS | `/phone?token=...` |
| MCP Endpoint | 4000 | HTTP/SSE | `/mcp` |

---

## Persistence

### 4 Persistence Modes

| Mode | Reads | Writes | DB Required | Use Case |
|------|-------|--------|-------------|----------|
| `memory` | InMemory Map | InMemory Map | No | Local dev, tests |
| `dual-write` | InMemory | InMemory + DB | No | Safe migration (default) |
| `database-read` | DB | InMemory + DB | Yes | Read-path testing |
| `database` | DB | DB | Yes | Production |

- **InMemory repos always created** (even in database mode) — used by RecoveryManager for Phase B timer rebuild
- **Default mode:** `dual-write` — reads from memory, writes to both stores
- **Recovery:** Phase A loads DB → memory at startup. Phase B rebuilds timers.

### Repository Architecture

```
SessionRepository (interface)
  ├── InMemorySessionRepository   (Map-based, always created)
  ├── DatabaseSessionRepository   (pg.Pool, created when DB configured)
  ├── DualWriteSessionRepository  (memory + DB, wraps both)
  ├── PrimaryDatabaseSessionRepo  (DB-only, wraps DatabaseSessionRepo)
  └── InstrumentedSessionRepo     (timing + retry + slow-query, wraps any)
```

Same structure for `CallbackRepository`.

---

## Auth

- **Single-token model:** All clients share SERVICE_TOKEN
- **HTTP:** Bearer token in `Authorization` header
- **WebSocket:** `?token=` query parameter on WS upgrade
- **Exception:** `/health`, `/ready`, `/metrics` — no auth required (K8s probes)

---

## Infrastructure

```
Docker Compose:  backend, caddy
Kubernetes:      9 manifests (see infra/k8s/)
                 2 replicas min (HPA to 10)
                 PDB: minAvailable=1
                 NetworkPolicy: restrictive ingress + egress (DNS + PostgreSQL)
                 Resource limits: 512MB memory, 1 CPU per pod
```

---

## See Also

- [ARCHITECTURE_BASELINE.md](./ARCHITECTURE_BASELINE.md) — permanent reference with startup/shutdown/recovery/request lifecycles
- [API_SPEC.md](./API_SPEC.md) — REST API + WebSocket protocol
- [PRODUCTION_READINESS.md](./PRODUCTION_READINESS.md) — startup flow, shutdown flow, persistence modes, health endpoints
