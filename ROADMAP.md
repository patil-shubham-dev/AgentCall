# AgentCall — Roadmap

> **Canonical references:** [PRODUCT_VISION.md](./PRODUCT_VISION.md) | [SYSTEM_ARCHITECTURE.md](./SYSTEM_ARCHITECTURE.md) | [API_SPEC.md](./API_SPEC.md)
> **Detailed engineering roadmap:** [IMPLEMENTATION_ROADMAP.md](./docs/IMPLEMENTATION_ROADMAP.md)

---

## Legend

| Icon | Meaning |
|------|---------|
| ✅ | Done |
| 🟡 | In Progress |
| 📝 | Planned |
| ❌ | Not Planned |

---

## What's Done ✅

- Android app (Kotlin/Compose) with WebSocket signaling
- On-device STT and TTS
- Backend signaling server (Fastify + ws)
- MCP server (stdio + SSE + StreamableHTTP)
- 5 MCP tools (create_call, send_message, get_transcript, complete_call, cancel_call)
- Docker configuration and CI pipeline
- Production deployment on Suga

## What's Next 📝

### Phase 1: Core Runtime (Current Priority)
Build the foundational services: Authentication (JWT), Provider Registry, Presence Engine, Notification Engine, Callback Engine. Complete all 8 MCP tools.

### Phase 2: Infrastructure
PostgreSQL, Redis, Device Router, History Service, production deployment.

### Phase 3: Communication Gateway
Unified transport layer, SSE stream, push notifications.

### Phase 4: Multi-Provider
Provider adapter interface, OpenAI, ChatGPT, Claude integrations.

### Phase 5: Mobile Evolution
Android auth flow, iOS app (MVP), push notifications.

### Phase 6: Platform API
OpenAPI spec, webhooks, function calling support.

### Phase 7: Production Hardening
Security audit, load testing, monitoring, disaster recovery.

---

## Timeline

```
Phase 1: Core Runtime      ── Q3 2026
Phase 2: Infrastructure    ── Q4 2026
Phase 3: Gateway           ── Q4 2026
Phase 4: Multi-Provider    ── Q1 2027
Phase 5: Mobile            ── Q1 2027
Phase 6: Platform API      ── Q2 2027
Phase 7: Production        ── Q2 2027
```

See [IMPLEMENTATION_ROADMAP.md](./docs/IMPLEMENTATION_ROADMAP.md) for detailed phase breakdown with acceptance criteria, dependencies, and risks.

## Questions?

Open a [Discussion](https://github.com/patil-shubham-dev/AgentCall/discussions) to ask about the roadmap.
