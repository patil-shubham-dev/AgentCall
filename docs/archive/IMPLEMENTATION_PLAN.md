# AgentCall — Implementation Plan

> **Date:** 2026-07-26
> **Source:** IMPLEMENTATION_ROADMAP.md converted to engineering work.
> **Structure:** Epic → Story → Task → Subtask

---

## How to Use This Plan

Each Epic maps to one IMPLEMENTATION_ROADMAP.md phase.
Each Story is a vertical slice of functionality.
Each Task is a PR-sized unit of work (1-3 days).
Each Subtask is a single change (a few files, a few hours).

Tasks are ordered within a story. Stories within an epic can be parallelized where noted.

---

## Epic 1: Foundation Cleanup

**Phase 0** | **1 week** | **Priority: Critical**

### Story 1.1: Remove Architecture Philosophy Violations

**Description:** Delete all code that performs AI reasoning (enrichment, emotion detection, barge-in classification, command pattern matching). This is a precondition for all further work.

**Dependencies:** None

| # | Task | Description | Files Affected | Est. Complexity | Est. Effort |
|---|------|-------------|----------------|-----------------|-------------|
| 1.1.1 | Remove `enrichText()` and emotion functions | Delete `enrichText()`, `emotionOf()`, `extractEmotionTag()` from `voicebridge/types.ts` | `voicebridge/types.ts` | Low | 2h |
| 1.1.2 | Remove `detectBargeIn()` | Delete the keyword-matching barge-in detection function | `voicebridge/types.ts` | Low | 1h |
| 1.1.3 | Remove emotion types | Delete `EmotionTag`, `EmotionDirective`, `BreathDirective`, `EnrichedMessage`, `SpeechSegment`, `BargeInResult` from types | `voicebridge/types.ts` | Low | 1h |
| 1.1.4 | Clean up `service.ts` imports and calls | Remove imports of deleted functions, remove `enrichText()` call from `processTextMessage()` | `voicebridge/service.ts` | Low | 1h |
| 1.1.5 | Remove Android command classification | Delete `CommandPattern` enum and classification logic from `CallService.kt` | `CallService.kt` | Medium | 4h |
| 1.1.6 | Remove Android filler words + breathing | Delete filler word insertion and breathing pause logic | `CallService.kt` | Low | 1h |
| 1.1.7 | Remove Android emotion adjustment | Delete `adjustTtsForEmotion()` and related calls | `CallService.kt` | Low | 1h |
| 1.1.8 | Clean up Android emotion display | Remove emotion-based color/emoji/gradient mappings from UI files | `CallActivity.kt`, `CallViewModel.kt` | Low | 2h |

**Acceptance Criteria:**
- No `enrichText`, `emotionOf`, `extractEmotionTag`, or `detectBargeIn` exists in backend code
- No `CommandPattern`, `adjustTtsForEmotion`, filler words, or breathing pauses exist in Android code
- Backend and Android both build and compile
- Basic call flow works end-to-end

**Tests Required:** Manual verification of call flow (no automated tests exist yet)

**Documentation Updates:** Update ARCHITECTURE.md to note removal of text enrichment

### Story 1.2: Config Security Hardening

**Description:** Fix immediate security issues in configuration.

**Dependencies:** None

| # | Task | Description | Files Affected | Est. Complexity | Est. Effort |
|---|------|-------------|----------------|-----------------|-------------|
| 1.2.1 | Remove default SERVICE_TOKEN | Remove `'dev-service-token'` default; throw if SERVICE_TOKEN is unset in production | `backend/src/common/config.ts`, `mcp-server/src/config.ts` | Low | 2h |
| 1.2.2 | Add NaN check on port | Validate port is positive integer; fallback to default if NaN | `mcp-server/src/config.ts` | Low | 1h |
| 1.2.3 | Add config validation function | Validate all required config values after load; throw with clear message if missing | `backend/src/common/config.ts`, `mcp-server/src/config.ts` | Low | 3h |
| 1.2.4 | Gate Android logging interceptor | Wrap `HttpLoggingInterceptor` level with `BuildConfig.DEBUG` | `ApiClient.kt` | Low | 1h |
| 1.2.5 | Move Android host to BuildConfig | Replace hardcoded `DEFAULT_HOST` with BuildConfig field | `ApiClient.kt`, `app/build.gradle.kts` | Medium | 3h |
| 1.2.6 | Re-enable CSP | Replace `contentSecurityPolicy: false` with appropriate policy | `backend/src/index.ts` | Medium | 4h |
| 1.2.7 | Gate debug body logging | Wrap `inspectBody()` call with `NODE_ENV !== 'production'` | `backend/src/routes.ts` | Low | 1h |

**Acceptance Criteria:**
- Backend fails to start with clear error if `SERVICE_TOKEN` is unset in production
- MCP server fails to start with clear error if `SERVICE_TOKEN` is unset in production
- Port parsing handles non-numeric input gracefully
- Android debug build has header logging; release build does not
- Android host URL is configurable via BuildConfig, not hardcoded
- CSP headers are present in backend responses
- Debug logging is inactive in production

**Tests Required:** Manual start/stop verification

**Documentation Updates:** Update `.env.example` comments

---

## Epic 2: Event Bus & Service Facade

**Phase 1** | **1 week** | **Priority: Critical**

### Story 2.1: Event Bus Implementation

**Description:** Create in-process Event Bus for service communication.

**Dependencies:** None

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 2.1.1 | Define Event Bus interface | Create `IEventBus` interface with `publish`, `subscribe`, `unsubscribe` methods | `backend/src/common/event-bus.ts` (new) | Low | 2h |
| 2.1.2 | Implement in-process Event Bus | Topic-based pub/sub, async handler execution, error handling per handler | `backend/src/common/event-bus.ts` | Medium | 1d |
| 2.1.3 | Add dead letter queue | Max retry count, dead letter storage, logging for failed events | `backend/src/common/event-bus.ts` | Medium | 1d |
| 2.1.4 | Define event type registry | Typed event payloads for all system events | `backend/src/common/events.ts` (new) | Low | 3h |

**Acceptance Criteria:**
- `EventBus.publish('call.created', payload)` delivers to all subscribers of `call.created`
- Handler errors don't crash the publisher
- Failed handlers retry with configurable policy before going to dead letter
- TypeScript types enforce correct event payload for each topic

**Tests Required:** Unit tests for publish/subscribe, retry, dead letter

### Story 2.2: Service Facade

**Description:** Create facade layer between HTTP routes and business logic.

**Dependencies:** Story 2.1 (Event Bus)

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 2.2.1 | Create CallServiceFacade interface | Define `ICallServiceFacade` with all public operations | `backend/src/voicebridge/facade.ts` (new) | Low | 2h |
| 2.2.2 | Implement facade | Delegate to existing `voicebridge/service.ts` functions, add Event Bus publishing | `backend/src/voicebridge/facade.ts` | Medium | 1d |
| 2.2.3 | Migrate routes.ts to use facade | Replace direct `voicebridge.*` calls with `facade.*` | `backend/src/routes.ts` | Low | 3h |
| 2.2.4 | Add DTO mapping to facade | Validate and transform route inputs before passing to services | `backend/src/voicebridge/facade.ts` | Medium | 1d |

**Acceptance Criteria:**
- All 10 API endpoints return identical responses before and after migration
- Routes only depend on facade interface, not concrete service
- Events are published for every significant action (CallCreated, CallEnded, etc.)

**Tests Required:** Integration test comparing responses before/after migration

### Story 2.3: Decouple Signaling from Service

**Description:** Remove direct dependency of `signaling/server.ts` on `voicebridge/service.ts`.

**Dependencies:** Story 2.1 (Event Bus)

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 2.3.1 | Replace `registerPhone()` call with event | Signaling publishes `phone.connected` event; service subscribes to register phone | `signaling/server.ts`, `voicebridge/facade.ts` | Medium | 4h |
| 2.3.2 | Remove direct import of service from signaling | Delete `import * as voicebridge from '../voicebridge/service.js'` from signaling | `signaling/server.ts` | Low | 1h |
| 2.3.3 | Add signaling event handlers to facade | Service listens for signaling events and responds appropriately | `voicebridge/facade.ts` | Medium | 4h |

**Acceptance Criteria:**
- `signaling/server.ts` no longer imports from `voicebridge/service.ts`
- Phone registration still works end-to-end
- All WebSocket events still flow correctly

**Tests Required:** Integration test for signaling→event→service flow

---

## Epic 3: Service Decomposition

**Phase 2** | **2 weeks** | **Priority: High**

### Story 3.1: Extract CallManager

**Description:** Extract call lifecycle management from `voicebridge/service.ts`.

**Dependencies:** Epic 2 (Event Bus + Facade)

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 3.1.1 | Create CallManager class | Move `createCall`, `getCall`, `getUserActiveCall`, `completeCall`, `cancelCall` to new class | `voicebridge/call-manager.ts` (new) | Medium | 1d |
| 3.1.2 | Add Event Bus publishing | Publish `call.created`, `call.state_changed`, `call.completed`, `call.cancelled` | `voicebridge/call-manager.ts` | Low | 2h |
| 3.1.3 | Update facade | Delegate call operations to CallManager | `voicebridge/facade.ts` | Low | 2h |

**Acceptance Criteria:** Call lifecycle works identically to before extraction.

### Story 3.2: Extract CallbackEngine

**Description:** Extract callback scheduling from `voicebridge/service.ts`.

**Dependencies:** Story 3.1

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 3.2.1 | Create CallbackEngine class | Move `scheduleCallback` to new class. Fix key from userId to callId. Track and clear setTimeout handles. | `voicebridge/callback-engine.ts` (new) | Medium | 1d |
| 3.2.2 | Add proper timer management | Track all pending timeouts, clear on cancel/complete | `voicebridge/callback-engine.ts` | Medium | 4h |
| 3.2.3 | Add Event Bus integration | Publish `callback.scheduled`, `callback.fired`, `callback.cancelled` | `voicebridge/callback-engine.ts` | Low | 2h |

**Acceptance Criteria:**
- Multiple callbacks for the same user work correctly
- Callback timers are cleaned up on cancel/complete
- Events are published for callback lifecycle

### Story 3.3: Extract PhoneRegistry

**Description:** Extract phone registration and connection management.

**Dependencies:** Story 2.3

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 3.3.1 | Create PhoneRegistry class | Move `registerPhone`, `notifyPhone`, connection tracking | `voicebridge/phone-registry.ts` (new) | Medium | 1d |
| 3.3.2 | Move phone connection state | Transfer `phoneConnections` Map to PhoneRegistry | `voicebridge/phone-registry.ts` | Low | 2h |

### Story 3.4: Extract SessionManager

**Description:** Create session lifecycle management.

**Dependencies:** None (new functionality)

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 3.4.1 | Define Session data model | id, userId, providerId, createdAt, expiresAt, context | `voicebridge/session-manager.ts` | Low | 2h |
| 3.4.2 | Create SessionManager | createSession, extendSession, endSession, getSession, auto-expiry | `voicebridge/session-manager.ts` | Medium | 1d |

### Story 3.5: Extract CallHistoryService

**Description:** Extract transcript and history management.

**Dependencies:** None (from existing code)

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 3.5.1 | Create CallHistoryService | Move `getTranscript`, `addMessage`, `addAiMessage` | `voicebridge/history-service.ts` (new) | Medium | 1d |

### Story 3.6: Clean Up Service.ts

**Description:** Remove original monolithic service file.

**Dependencies:** All stories in Epic 3

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 3.6.1 | Delete or deprecate service.ts | All functions migrated to individual services | `voicebridge/service.ts` | Low | 1h |

---

## Epic 4: Persistence

**Phase 3** | **3 weeks** | **Priority: High**

### Story 4.1: PostgreSQL Setup

**Description:** Install, configure, and create schema.

**Dependencies:** None

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 4.1.1 | Install Knex.js and pg | Add dependencies to backend/package.json | `backend/package.json` | Low | 1h |
| 4.1.2 | Configure Knex | Create knexfile with connection pool, env-based config | `backend/src/database/knex.ts` | Low | 3h |
| 4.1.3 | Add PostgreSQL to docker-compose | PostgreSQL 16 service with volume, health check, env vars | `infra/docker-compose.yml` | Low | 2h |
| 4.1.4 | Create initial migration | users, providers, devices, call_sessions, notifications, callbacks, api_keys | `backend/src/database/migrations/` | Medium | 1d |

### Story 4.2: Repository Layer

**Description:** Implement repository pattern for data access.

**Dependencies:** Story 4.1

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 4.2.1 | Create IUserRepository | Interface + PostgresUserRepository | `backend/src/database/repositories/` | Medium | 4h |
| 4.2.2 | Create IProviderRepository | Interface + PostgresProviderRepository | (same) | Medium | 3h |
| 4.2.3 | Create IDeviceRepository | Interface + PostgresDeviceRepository | (same) | Medium | 3h |
| 4.2.4 | Create ICallRepository | Interface + PostgresCallRepository | (same) | Medium | 4h |
| 4.2.5 | Create INotificationRepository | Interface + PostgresNotificationRepository | (same) | Medium | 3h |
| 4.2.6 | Create ICallbackRepository | Interface + PostgresCallbackRepository | (same) | Medium | 3h |

### Story 4.3: Migrate State to PostgreSQL

**Description:** Replace in-memory Maps with repository calls.

**Dependencies:** Story 4.2, Epic 3 (Service Decomposition)

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 4.3.1 | Migrate CallManager state | Replace in-memory Maps with ICallRepository calls | `call-manager.ts` | Medium | 1d |
| 4.3.2 | Migrate CallbackEngine state | Replace in-memory Map with ICallbackRepository | `callback-engine.ts` | Medium | 4h |
| 4.3.3 | Migrate PhoneRegistry state | Replace in-memory Map with IDeviceRepository | `phone-registry.ts` | Medium | 4h |

### Story 4.4: Redis Integration

**Description:** Add Redis for presence, rate limiting, and pub/sub.

**Dependencies:** Story 4.1

| # | Task | Description | Files Affected | Complexity | Effort |
|---|------|-------------|----------------|------------|--------|
| 4.4.1 | Install ioredis | Add dependency to backend/package.json | `backend/package.json` | Low | 1h |
| 4.4.2 | Configure Redis client | Connection pool, env-based config, sentinel placeholder | `backend/src/common/redis.ts` | Low | 3h |
| 4.4.3 | Add Redis to docker-compose | Redis 7 service with health check | `infra/docker-compose.yml` | Low | 1h |
| 4.4.4 | Implement PresenceEngine with Redis | Online/offline/busy/in-call with TTL-based expiry | `backend/src/presence/presence-engine.ts` (new) | Medium | 1d |

---

## Epic 5: Missing Services

**Phase 4** | **6 weeks** | **Priority: High**

### Story 5.1: Authentication Service

**Dependencies:** Story 2.1 (Event Bus)

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 5.1.1 | Generate RS256 key pair infrastructure | Medium | 4h |
| 5.1.2 | Implement JWT issuance (POST /auth/login) | Medium | 1d |
| 5.1.3 | Implement JWT validation middleware | Medium | 1d |
| 5.1.4 | Implement JWT refresh flow | Medium | 1d |
| 5.1.5 | Implement token revocation | Medium | 4h |
| 5.1.6 | Implement provider API key auth | Medium | 1d |

### Story 5.2: Provider Registry

**Dependencies:** Story 5.1

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 5.2.1 | Define Provider data model + repository | Low | 3h |
| 5.2.2 | Implement ProviderRegistry service | Medium | 1d |
| 5.2.3 | Implement provider API endpoints | Medium | 1d |

### Story 5.3: Notification Engine

**Dependencies:** Story 2.1 (Event Bus)

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 5.3.1 | Define notification data model | Low | 2h |
| 5.3.2 | Implement in-app notification dispatch | Medium | 1d |
| 5.3.3 | Implement push notification scaffolding (FCM) | Medium | 2d |

### Story 5.4: Device Router

**Dependencies:** Story 5.1

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 5.4.1 | Define Device data model + repository | Low | 3h |
| 5.4.2 | Implement DeviceRegistry service | Medium | 1d |
| 5.4.3 | Implement device endpoints | Medium | 1d |

### Story 5.5: History Service (REST)

**Dependencies:** Epic 4 (Persistence)

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 5.5.1 | Implement HistoryService with Pagination | Medium | 1d |
| 5.5.2 | Implement history API endpoints | Medium | 1d |

### Story 5.6: Communication Gateway

**Dependencies:** Stories 5.1, 5.4

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 5.6.1 | Design Gateway transport abstraction | High | 2d |
| 5.6.2 | Implement WebSocket transport in Gateway | Medium | 1d |
| 5.6.3 | Implement SSE transport in Gateway | Medium | 1d |
| 5.6.4 | Implement event routing through Gateway | Medium | 1d |

### Story 5.7: Missing MCP Tools

**Dependencies:** Stories 5.3, 5.5

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 5.7.1 | Implement `query_presence` tool | Medium | 1d |
| 5.7.2 | Implement `resume_task` tool | Medium | 1d |
| 5.7.3 | Implement `notify_completion` tool | Medium | 1d |

---

## Epic 6: Validation & Error Handling

**Phase 5** | **1 week** | **Priority: High**

### Story 6.1: Input Validation

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 6.1.1 | Create Zod schemas for all entities | Medium | 1d |
| 6.1.2 | Add Zod validation to all REST endpoints | Medium | 1d |
| 6.1.3 | Add Zod validation to all MCP tool handlers | Medium | 1d |
| 6.1.4 | Add Fastify schema validation | Medium | 1d |

### Story 6.2: Error Format Compliance

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 6.2.1 | Standardize error envelope per API_SPEC.md | Medium | 4h |
| 6.2.2 | Add correlationId to all error responses | Medium | 4h |
| 6.2.3 | Add structured error codes to all services | Medium | 1d |

---

## Epic 7: Android Decomposition

**Phase 6** | **2 weeks** | **Priority: High**

### Story 7.1: Decompose CallService

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 7.1.1 | Extract TtsManager | Medium | 1d |
| 7.1.2 | Extract SttManager | Medium | 1d |
| 7.1.3 | Extract BargeInDetector | Medium | 1d |
| 7.1.4 | Extract CallSession | Medium | 1d |
| 7.1.5 | Extract NotificationHelper | Medium | 4h |
| 7.1.6 | Wire with Hilt injection | Medium | 4h |

### Story 7.2: Fix WebSocket Reconnection

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 7.2.1 | Add exponential backoff to SignalingClient | Medium | 4h |
| 7.2.2 | Add max retry limit with circuit breaker | Medium | 4h |
| 7.2.3 | Add WebSocket heartbeat | Medium | 4h |

### Story 7.3: Remove Dead Code

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 7.3.1 | Remove unused ViewModel methods (showAITyping, setBargeIn, setPaused) | Low | 1h |
| 7.3.2 | Fix AudioRecord lifecycle bug in onDestroy | Medium | 3h |
| 7.3.3 | Consolidate emotion maps to single source | Low | 2h |

---

## Epic 8: Infrastructure Hardening

**Phase 7** | **1 week** | **Priority: Medium**

### Story 8.1: Fix CI Pipeline

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 8.1.1 | Fix ESLint silent failure (remove `|| echo`) | Low | 1h |
| 8.1.2 | Add MCP server linting to CI | Low | 1h |
| 8.1.3 | Add npm audit to CI | Low | 1h |
| 8.1.4 | Add Docker image vulnerability scanning | Medium | 1d |

### Story 8.2: Deployment Workflow

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 8.2.1 | Add Docker registry push to CI | Medium | 4h |
| 8.2.2 | Add SSH deploy workflow | Medium | 1d |
| 8.2.3 | Add staging deployment environment | Medium | 1d |

### Story 8.3: Docker Cleanup

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 8.3.1 | Delete root Dockerfile | Low | 5min |
| 8.3.2 | Add .dockerignore files | Low | 30min |
| 8.3.3 | Add HEALTHCHECK to MCP server Dockerfile | Low | 30min |
| 8.3.4 | Add container resource limits | Low | 1h |

---

## Epic 9: Testing

**Phase 8** | **4 weeks** | **Priority: Critical (parallel)**

### Story 9.1: Test Infrastructure

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 9.1.1 | Fix vitest config (remove references to non-existent files) | Low | 1h |
| 9.1.2 | Add test coverage configuration with realistic thresholds | Low | 1h |
| 9.1.3 | Install Android test dependencies (JUnit, MockK, Turbine) | Low | 2h |

### Story 9.2: Backend Unit Tests

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 9.2.1 | Unit tests for config.ts | Low | 3h |
| 9.2.2 | Unit tests for logger.ts | Low | 2h |
| 9.2.3 | Unit tests for event-bus.ts | Medium | 4h |
| 9.2.4 | Unit tests for CallManager | Medium | 1d |
| 9.2.5 | Unit tests for CallbackEngine | Medium | 1d |
| 9.2.6 | Unit tests for routes | Medium | 1d |

### Story 9.3: MCP Unit Tests

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 9.3.1 | Unit tests for client.ts (with mock fetch) | Medium | 4h |
| 9.3.2 | Unit tests for tools.ts (validate handler responses) | Medium | 1d |
| 9.3.3 | Unit tests for config.ts | Low | 2h |

### Story 9.4: Integration Tests

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 9.4.1 | Auth → Token → API call flow | Medium | 1d |
| 9.4.2 | Call creation → notification flow | Medium | 1d |
| 9.4.3 | Callback scheduling → firing flow | Medium | 1d |
| 9.4.4 | MCP tool → backend → response flow | Medium | 1d |

### Story 9.5: Android Tests

| # | Task | Complexity | Effort |
|---|------|------------|--------|
| 9.5.1 | ViewModel unit tests (HomeViewModel, CallViewModel) | Medium | 1d |
| 9.5.2 | SignalingClient unit tests | Medium | 1d |
| 9.5.3 | ApiClient + ApiService unit tests | Medium | 1d |

---

## Implementation Order Summary

```
Week 1:   Epic 1 (Foundation Cleanup) + Epic 2 start (Event Bus)
Week 2:   Epic 2 (Event Bus + Facade) + Epic 9 start (Testing infra)
Week 3-4: Epic 3 (Service Decomposition)
Week 5-7: Epic 4 (Persistence)
Week 8:   Epic 6 (Validation) + Epic 7 (Android)
Week 9-13: Epic 5 (Missing Services)
Week 13:  Epic 8 (Infrastructure)
Week 1-13: Epic 9 (Testing - parallel)
```

**Total: 13 weeks of engineering work** (assuming 1-2 engineers)
