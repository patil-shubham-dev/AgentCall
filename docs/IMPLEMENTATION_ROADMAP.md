# AgentCall — Implementation Roadmap

> **Canonical references:** [PRODUCT_VISION.md](../PRODUCT_VISION.md) | [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) | [API_SPEC.md](../API_SPEC.md) | [PRD.md](./PRD.md)
> **Current state:** See [ARCHITECTURE.md](../ARCHITECTURE.md) for current implementation

---

## Phase 0: Foundation ✅ (Complete)

| Item | Status |
|------|--------|
| Android app scaffold | ✅ |
| WebSocket signaling | ✅ |
| On-device STT/TTS | ✅ |
| Backend signaling server | ✅ |
| MCP server core (stdio + SSE + StreamableHTTP) | ✅ |
| 5 MCP tools | ✅ |
| Docker configuration | ✅ |
| CI pipeline | ✅ |
| Production deployment (Suga) | ✅ |

---

## Phase 1: Core Runtime

**Goal:** Replace in-memory solo-user architecture with the canonical runtime services.

### Deliverables

| # | Delivery | Description |
|---|----------|-------------|
| 1.1 | Authentication Service | JWT issuance, validation, refresh. Provider API key auth. |
| 1.2 | Provider Registry | Register, list, revoke AI providers. Isolated per-provider state. |
| 1.3 | Presence Engine | Online/offline/busy/DND/in-call presence tracking. |
| 1.4 | Notification Engine | In-app notification dispatch. Push notification scaffolding. |
| 1.5 | Callback Engine | Schedule, retry, cancel callbacks with configurable intervals. |
| 1.6 | Complete MCP API | Add query_presence, resume_task, notify_completion tools. |
| 1.7 | Event Bus | In-process event bus for service communication. |
| 1.8 | Multi-user support | Replace solo-user with real user model. |

### Acceptance Criteria

- JWT tokens issued, validated, and refreshed correctly
- Provider isolation: separate sessions/history per provider
- Presence queries return correct user state within 1s
- Notifications delivered in-app (push scaffolding in place)
- Callbacks schedule, fire, and retry correctly
- All 8 MCP tools return correct responses per API_SPEC.md
- Events flow through Event Bus between services
- Multiple users can register and have independent state

### Dependencies

- None (foundational phase, no external services)

### Breaking Changes

- All current endpoints change from SERVICE_TOKEN to JWT auth
- `/phone?user_id=solo-user` replaced with authenticated connection
- In-memory Maps replaced with service-owned state
- MCP tool signatures updated for all 8 tools

### Estimated Duration: 6-8 weeks

### Risks

| Risk | Mitigation |
|------|------------|
| Scope creep adding non-core features | Strictly limit to SYSTEM_ARCHITECTURE.md services |
| Breaking existing Android app | Keep backward-compatible WebSocket protocol during migration |
| Auth complexity delaying delivery | Start with JWT, add OAuth as separate story |

### Testing Requirements

- Unit tests for each service with >85% coverage
- Integration tests for service interactions via Event Bus
- Auth: token issuance, validation, refresh, revocation
- MCP: all 8 tools tested end-to-end
- Presence: concurrent state update correctness

### Documentation Updates

- Update ARCHITECTURE.md with new service structure
- Update API_SPEC.md endpoint documentation
- Update CURRENT_STACK.md with new technologies

---

## Phase 2: Infrastructure

**Goal:** Add persistent storage and production infrastructure.

### Deliverables

| # | Delivery | Description |
|---|----------|-------------|
| 2.1 | PostgreSQL integration | Schema, migrations, repository layer |
| 2.2 | Redis integration | Presence state, pub/sub, rate limiting |
| 2.3 | Device Router | Multi-device support, device registration, routing |
| 2.4 | History Service | Call history, transcript persistence |
| 2.5 | Session Manager | Long-lived provider-user session management |
| 2.6 | Production deployment | Docker Compose, PostgreSQL, Redis, Caddy |

### Acceptance Criteria

- All service state persisted to PostgreSQL
- Redis-backed presence with TTL-based expiry
- Devices register, list, and unregister correctly
- Call history persists and is queryable
- Session lifecycle managed correctly
- Docker Compose deploys full stack with one command

### Dependencies: Phase 1 complete

### Breaking Changes

- State moves from in-memory to database (requires migration)
- API changes for device registration endpoints

### Estimated Duration: 4-6 weeks

### Testing Requirements

- Database migration tests (up/down)
- Repository layer unit tests with test containers
- Redis integration tests
- Full Docker Compose smoke test

---

## Phase 3: Communication Gateway

**Goal:** Replace direct WebSocket signaling with the Communication Gateway.

### Deliverables

| # | Delivery | Description |
|---|----------|-------------|
| 3.1 | Communication Gateway | Unified transport layer for WebSocket, SSE, WebRTC |
| 3.2 | SSE stream | Server-Sent Events for real-time updates |
| 3.3 | WebRTC signaling | Native WebRTC offer/answer/ICE via Gateway |
| 3.4 | Push notifications | FCM (Android), APNs (iOS future) |
| 3.5 | Connection quality monitoring | RTT, jitter, packet loss metrics |

### Acceptance Criteria

- Gateway handles all WebSocket + SSE + WebRTC signaling
- Events delivered to correct device via Gateway
- Push notifications delivered within 2s
- Connection quality metrics collected and stored

### Dependencies: Phase 2 complete

### Breaking Changes

- WebSocket endpoints move under Gateway
- Mobile app must connect to Gateway instead of direct backend

### Estimated Duration: 4-6 weeks

---

## Phase 4: Multi-Provider

**Goal:** Enable any AI provider to integrate through a standardized adapter.

### Deliverables

| # | Delivery | Description |
|---|----------|-------------|
| 4.1 | Provider Adapter interface | Canonical adapter specification |
| 4.2 | MCP Adapter | Internal MCP tool → adapter bridge |
| 4.3 | OpenAI Adapter | Function calling → AgentCall |
| 4.4 | ChatGPT Adapter | Custom GPT Actions → AgentCall |
| 4.5 | Webhook Registration | Async response delivery via webhooks |

### Acceptance Criteria

- Three provider adapters working end-to-end
- Provider isolation: separate auth, sessions, history per provider
- Webhook-based async response flow
- Provider can register via REST API

### Dependencies: Phase 3 complete

### Estimated Duration: 6-8 weeks

---

## Phase 5: Mobile Evolution

**Goal:** Evolve Android app to match new architecture; add iOS.

### Deliverables

| # | Delivery | Description |
|---|----------|-------------|
| 5.1 | Android auth flow | JWT login, token refresh, provider management |
| 5.2 | Android presence | Presence indicators, status management |
| 5.3 | Android notifications | Notification channels, push handling |
| 5.4 | Android callbacks | Callback scheduling UI, management |
| 5.5 | iOS app (MVP) | Basic iOS app with core calling features |

### Acceptance Criteria

- Android app works with JWT auth
- Presence indicators reflect real-time state
- Push notifications received and handled
- Callbacks can be scheduled and managed
- iOS app builds and connects

### Dependencies: Phases 1-3 complete

### Estimated Duration: 8-10 weeks

---

## Phase 6: Platform API

**Goal:** Expose all AgentCall capabilities through OpenAPI and webhooks.

### Deliverables

| # | Delivery | Description |
|---|----------|-------------|
| 6.1 | OpenAPI spec | Complete OpenAPI 3.1 specification |
| 6.2 | Webhook system | Outbound webhooks for events |
| 6.3 | Function Calling support | OpenAI-compatible function definitions |
| 6.4 | API versioning | /api/v1, /api/v2 support |

### Acceptance Criteria

- OpenAPI spec validated and publishable
- Webhooks deliver events to registered endpoints
- Function calling works with OpenAI-compatible clients
- API versioning works correctly

### Dependencies: Phase 4 complete

### Estimated Duration: 4-6 weeks

---

## Phase 7: Production Hardening

**Goal:** Production readiness across security, scale, and reliability.

### Deliverables

| # | Delivery | Description |
|---|----------|-------------|
| 7.1 | Security audit | Full penetration testing, vulnerability scan |
| 7.2 | Load testing | 50 concurrent calls, sustained load |
| 7.3 | Monitoring | Prometheus + Grafana dashboards |
| 7.4 | Disaster recovery | Backup, restore, failover procedures |
| 7.5 | Documentation | All docs finalized, README updated |

### Acceptance Criteria

- Security audit passes with no critical findings
- 50 concurrent calls with <2s setup time
- Monitoring covers all services
- Recovery procedures tested
- Documentation is complete and consistent

### Dependencies: All phases 1-6 complete

### Estimated Duration: 4-6 weeks

---

## Total Timeline: 36-50 weeks

```
Phase 0: Foundation          ✅
Phase 1: Core Runtime        ── 6-8 weeks
Phase 2: Infrastructure      ── 4-6 weeks
Phase 3: Communication       ── 4-6 weeks
Phase 4: Multi-Provider      ── 6-8 weeks
Phase 5: Mobile Evolution    ── 8-10 weeks
Phase 6: Platform API        ── 4-6 weeks
Phase 7: Production          ── 4-6 weeks
                              ──────────
              Total          36-50 weeks
```

> See [ROADMAP.md](../ROADMAP.md) for the community-facing roadmap summary.
