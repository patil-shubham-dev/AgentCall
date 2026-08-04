# VoiceBridge v1.0.0 — General Availability

**The open, AI-agnostic communication platform that enables any AI to securely reach humans through voice calls.**

> _"AI owns intelligence. AgentCall owns communication. Humans own decisions."_

---

## Highlights

- **MCP Native** — 8 built-in tools for AI agents: `create_call`, `send_message`, `get_transcript`, `complete_call`, `cancel_call`, `query_presence`, `resume_task`, `notify_completion`
- **Real-Time Voice** — WebSocket-based voice bridge between AI and humans
- **Android App** — Native Kotlin/Jetpack Compose with incoming call notifications
- **Single-Port Architecture** — HTTP, WebSocket, and health probes on one port
- **Event-Driven Core** — In-process EventBus with retry, circuit breaker, dead-letter queue
- **Flexible Persistence** — Memory-only, PostgreSQL, or dual-write modes
- **Privacy First** — No recording, no transcript retention by default
- **Free First** — No paid APIs, no cloud dependencies, fully self-hosted
- **TypeScript Strict** — Zero `any`, full type safety
- **48 Tests** — All passing, zero lint errors, TypeScript strict mode clean

---

## New Features

### Voice Bridge
- Single-port architecture (HTTP + WebSocket + health on `:4000`)
- Phase A recovery: session state restored from DB on startup
- Phase B recovery: callbacks and timers rebuilt after restart
- Session lifecycle coordination with periodic sweeper
- 5-second inactivity disconnect timeout
- Structured production logging: `[HTTP]`, `[WS]`, `[REGISTER]`, `[STT]`, `[TTS]`, `[VOICE]`

### Event Bus
- 14 event subscribers registered for extensibility
- Retry with exponential backoff (max 5 retries)
- Backpressure buffer (10K events soft limit, 100K hard limit)
- Circuit breaker (50% failure rate → open for 30s)
- Dead-letter queue with event metadata preservation

### Android App
- Jetpack Compose UI with light/dark theme
- On-device SpeechRecognizer (no server-side STT)
- Incoming call notification channel
- Connection quality monitoring
- Auto-reconnect with 3-second interval
- Launcher icons, empty states, error snackbar, loading shimmer

### Infrastructure
- Docker multi-stage build (non-root user, HEALTHCHECK, read-only filesystem)
- Kubernetes manifests: 9 files (namespace, configmap, secrets, deployment, HPA, service, ingress, PDB, network policy)
- Docker Compose for local development and production
- Caddy reverse proxy with auto TLS

---

## Architecture Overview

```
AI Providers ──→ MCP Protocol ──→ AgentCall Runtime ──→ Communication Channels ──→ Human
  Claude           create_call       Auth                    Android App
  ChatGPT          send_message      Provider Registry       Future iOS
  Gemini           get_transcript    Session Manager         Desktop
  Cursor           complete_call     Call Manager            Web
  Local LLMs       cancel_call       Presence Engine
                   query_presence    Notification Engine
                   resume_task       Callback Engine
                   notify_completion Device Router
                                     History Service
                                     Communication Gateway
                                     Event Bus
```

For the full architecture diagram, see `README.md`.

---

## Breaking Changes

**None.** This is the initial public release. There are no migrations from a prior version.

### Deprecated Documents (for historical reference only)

- `docs/01-architecture-design.md` — Superseded by `SYSTEM_ARCHITECTURE.md`
- `docs/02-api-protocol-specification.md` — Superseded by `API_SPEC.md`
- `docs/07-mvp-scope-milestone-plan.md` — Superseded by `ROADMAP.md`
- `docs/09-infrastructure-cicd-plan.md` — Superseded by `DEPLOYMENT_GUIDE.md`

---

## Known Limitations

| ID | Limitation | Impact | Target Fix |
|----|-----------|--------|-----------|
| L001 | Single-token auth (no multi-user) | Security — no user isolation | v1.2 |
| L002 | No cross-pod session lock | Data integrity — last-write-wins under multi-pod | v1.1 |
| L003 | WebSocket dropped on rolling update | UX — connections closed without warning | v1.0.2 |
| L004 | Per-process timers | Reliability — timers lost on pod termination | v1.1 |
| L005 | No database migration tooling | Operations — schema changes are manual | v1.1 |
| L006 | InMemory repos always allocated | Memory — ~32KB/session overhead | v1.1 |
| L007 | No-op event subscribers | Performance — 14 log-only handlers | v1.0.2 |
| L008 | No statement timeout on DB pool | Operations — hanging queries possible | v1.0.1 |
| L009 | Unbounded metric Maps | Memory — potential OOM from metric explosion | v2.0 |
| L010 | No WebSocket connection limit | Reliability — unbounded connections per pod | v1.0.2 |
| L011 | Clock drift affects timers | Accuracy — Date.now() drift | v2.0 |
| L012 | Notification double-delivery on retry | UX — duplicates possible (<0.1%) | Research |
| L013 | PrimaryDatabase repos add no value | Cleanup — thin wrappers with debug logging | v1.0.1 |
| L014 | No pagination in session listing | Performance — all sessions returned at once | v1.1 |

Full details: `KNOWN_LIMITATIONS.md`

---

## Upgrade Notes

This is the initial release. No upgrade path required.

For future upgrades, see `RELEASE_PROCESS.md`.

---

## Installation

### Docker Compose (Recommended)

```bash
git clone https://github.com/agentcall/agentcall.git
cd agentcall
cp backend/.env.example backend/.env
# Edit backend/.env with your configuration
docker compose -f infra/docker-compose.yml up -d
# → http://localhost:4000
```

### Kubernetes

```bash
git clone https://github.com/agentcall/agentcall.git
cd agentcall
kubectl apply -f infra/k8s/01-namespace.yaml
kubectl apply -f infra/k8s/02-secret-template.yaml
# Edit secrets with your values
kubectl apply -f infra/k8s/
```

### Manual

```bash
git clone https://github.com/agentcall/agentcall.git
cd agentcall/backend
cp .env.example .env
npm install
npm run dev
# → http://localhost:4000
```

---

## Quick Start

### 1. Start the Backend

```bash
cd backend
cp .env.example .env
# Edit .env: set SERVICE_TOKEN to a random 64-char hex string
npm install
npm run dev
```

### 2. Connect the Android App

```bash
# Open mobile/android/ in Android Studio
# Build and deploy to your device
# Enter your server URL in Settings
```

### 3. Connect an AI Agent (via MCP)

```bash
cd mcp-server
npm install
npm run build
claude --mcp-servers "agentcall=node /path/to/mcp-server/dist/index.js"
```

### 4. Create a Call

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "create_call",
    "arguments": {
      "user_id": "user_abc123",
      "phone_number": "+1234567890",
      "context": "Payment confirmation required"
    }
  }
}
```

---

## Documentation

| Area | Document |
|------|----------|
| **Documentation Hub** | [docs/README.md](https://github.com/agentcall/agentcall/blob/main/docs/README.md) |
| **Architecture** | [ARCHITECTURE.md](https://github.com/agentcall/agentcall/blob/main/ARCHITECTURE.md) · [SYSTEM_ARCHITECTURE.md](https://github.com/agentcall/agentcall/blob/main/SYSTEM_ARCHITECTURE.md) · [ARCHITECTURE_BASELINE.md](https://github.com/agentcall/agentcall/blob/main/ARCHITECTURE_BASELINE.md) |
| **API** | [API_SPEC.md](https://github.com/agentcall/agentcall/blob/main/API_SPEC.md) |
| **Deployment** | [DEPLOYMENT_GUIDE.md](https://github.com/agentcall/agentcall/blob/main/DEPLOYMENT_GUIDE.md) · [PRODUCTION_READINESS.md](https://github.com/agentcall/agentcall/blob/main/PRODUCTION_READINESS.md) |
| **Database** | [DATABASE_GUIDE.md](https://github.com/agentcall/agentcall/blob/main/DATABASE_GUIDE.md) |
| **Operations** | [OPERATIONS_BASELINE.md](https://github.com/agentcall/agentcall/blob/main/OPERATIONS_BASELINE.md) |
| **Security** | [SECURITY.md](https://github.com/agentcall/agentcall/blob/main/SECURITY.md) |
| **Development** | [DEVELOPMENT_GUIDE.md](https://github.com/agentcall/agentcall/blob/main/DEVELOPMENT_GUIDE.md) · [CONTRIBUTING.md](https://github.com/agentcall/agentcall/blob/main/CONTRIBUTING.md) |
| **Roadmap** | [ROADMAP.md](https://github.com/agentcall/agentcall/blob/main/ROADMAP.md) |
| **Changelog** | [CHANGELOG.md](https://github.com/agentcall/agentcall/blob/main/CHANGELOG.md) |

---

## Assets

**SHA256 Checksums:**

```
[Generated during release workflow]
```

**Docker Image:**

```bash
docker pull ghcr.io/agentcall/agentcall:1.0.0
```

---

## Contributors

AgentCall is built on open-source foundations: Fastify, TypeScript, MCP Protocol, Jetpack Compose, Vitest.

See [CONTRIBUTING.md](https://github.com/agentcall/agentcall/blob/main/CONTRIBUTING.md) to get involved.

---

## License

[MIT](https://github.com/agentcall/agentcall/blob/main/LICENSE) © 2026 AgentCall
