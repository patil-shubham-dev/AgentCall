# AgentCall — Refactor Plan

> **Date:** 2026-07-26
> **This is NOT code.** This is a migration plan for incremental refactoring.
> **Principle:** No "big bang" rewrites. Every phase is independently verifiable and reversible.

---

## Phase 0: Philosophy Cleanup

**Goal:** Remove code that violates the core architecture philosophy (AI reasoning in communication layer).
**Duration:** 2 days
**Risk:** Low — removing features, not changing behavior.

### Steps

| Step | Action | Files Affected | Services Affected |
|------|--------|---------------|-------------------|
| 0.1 | Delete `enrichText()` function and all imports | `backend/src/voicebridge/types.ts`, `backend/src/voicebridge/service.ts` | Backend |
| 0.2 | Delete `emotionOf()`, `extractEmotionTag()` functions | `backend/src/voicebridge/types.ts` | Backend |
| 0.3 | Delete `detectBargeIn()` function | `backend/src/voicebridge/types.ts` | Backend |
| 0.4 | Remove `enrichText()` call from `processTextMessage()` | `backend/src/voicebridge/service.ts` | Backend |
| 0.5 | Remove emotion-related types (`EmotionTag`, `EmotionDirective`, `BreathDirective`, `EnrichedMessage`) | `backend/src/voicebridge/types.ts` | Backend |
| 0.6 | Remove emotion/barge-in handling from Android `CallService.kt` | `mobile/android/.../CallService.kt` | Android |
| 0.7 | Remove command pattern classification from `CallService.kt` | `mobile/android/.../CallService.kt` | Android |
| 0.8 | Remove filler words and breathing pauses from `CallService.kt` | `mobile/android/.../CallService.kt` | Android |
| 0.9 | Remove TTS emotion adjustment from `CallService.kt` | `mobile/android/.../CallService.kt` | Android |

### Estimated Commits: 2
1. Backend: remove enrichment/emotion/barge-in code
2. Android: remove all AI reasoning code from CallService

### Testing Requirements
- Backend builds and compiles (types removed from type surface)
- Android builds and compiles
- Call flow still works (AI speaks, user listens, user speaks, AI responds)

### Rollback Strategy
Each step is a single `git revert` of the commit. No data migration needed (no database involved).

### Expected Outcome
- `voicebridge/types.ts` reduced from 213 to ~75 lines (pure types only)
- `CallService.kt` reduced from 751 to ~550 lines
- **Architecture philosophy violation eliminated**

---

## Phase 1: Config & Security Hardening

**Goal:** Fix immediate security issues and configuration bugs.
**Duration:** 3 days
**Risk:** Low — no behavioral changes to business logic.

### Steps

| Step | Action | Files |
|------|--------|-------|
| 1.1 | Remove default `dev-service-token` from both configs; make `SERVICE_TOKEN` required | `backend/src/common/config.ts`, `mcp-server/src/config.ts` |
| 1.2 | Add config validation that throws on missing required vars in production | `backend/src/common/config.ts`, `mcp-server/src/config.ts` |
| 1.3 | Add NaN check on port parsing | `mcp-server/src/config.ts` |
| 1.4 | Gate CORS logging interceptor with `BuildConfig.DEBUG` | `mobile/android/.../ApiClient.kt` |
| 1.5 | Move hardcoded production URL to build config | `mobile/android/.../ApiClient.kt` |
| 1.6 | Re-enable CSP with appropriate policy | `backend/src/index.ts` |
| 1.7 | Gate debug logging (`inspectBody`) behind NODE_ENV | `backend/src/routes.ts` |

### Estimated Commits: 2
1. Backend/MCP config hardening
2. Android security fixes

### Testing Requirements
- Backend starts with valid env, fails fast with invalid env
- MCP server starts with valid env, fails fast with invalid env
- Android builds with debug and release configurations

### Rollback Strategy
Each change is independently revertible. Config changes don't affect business logic.

---

## Phase 2: Event Bus & Service Facade

**Goal:** Decouple backend services through an Event Bus and service facade layer.
**Duration:** 1 week
**Risk:** Medium — requires refactoring service calls.

### Steps

| Step | Action | Files Affected | Services Affected |
|------|--------|---------------|-------------------|
| 2.1 | Create `common/event-bus.ts` with `publish(topic, event)` / `subscribe(topic, handler)` | New file | Backend |
| 2.2 | Define Event Bus topics (CallCreated, CallStateChanged, PhoneRegistered, etc.) | `common/event-bus.ts` or `common/events.ts` | Backend |
| 2.3 | Extract `CallServiceFacade` between routes and service logic | New file `voicebridge/facade.ts` | Backend |
| 2.4 | Migrate `routes.ts` to use facade | `routes.ts` | Backend |
| 2.5 | Decouple `signaling/server.ts` from `voicebridge/service.ts` — signaling publishes events instead of calling service directly | `signaling/server.ts` | Backend |
| 2.6 | Add async event handler infrastructure (retry, dead letter) | `common/event-bus.ts` | Backend |

### Estimated Commits: 4
1. Event Bus infrastructure + topic definitions
2. Service facade with route migration
3. Signaling → Event Bus migration
4. Event handler retry + dead letter

### Testing Requirements
- All existing endpoints return same responses
- Events flow correctly between signaling and service
- Event handler retry works

### Rollback Strategy
Keep the old direct-call path alongside the event path. Use a feature flag to switch between them. Once validated, remove the old path.

### Risk
- **Timing differences**: Event Bus introduces async behavior. Some callers expect synchronous completion. Mitigation: use sync event handlers initially, migrate to async in a later phase.

### Expected Outcome
- `signaling/server.ts` no longer depends on `voicebridge/service.ts`
- `routes.ts` depends on `ServiceFacade` interface, not concrete service
- Event Bus infrastructure ready for service decomposition

---

## Phase 3: Service Decomposition

**Goal:** Split `voicebridge/service.ts` into focused services.
**Duration:** 2 weeks
**Risk:** High — most complex refactoring.

### Steps

| Step | Action | Files Affected | Services Affected |
|------|--------|---------------|-------------------|
| 3.1 | Extract `CallManager` — call lifecycle + state management | `voicebridge/call-manager.ts` | Backend |
| 3.2 | Extract `CallbackEngine` — callback scheduling + retry | `voicebridge/callback-engine.ts` | Backend |
| 3.3 | Extract `PhoneRegistry` — phone registration + connection management | `voicebridge/phone-registry.ts` | Backend |
| 3.4 | Extract `SessionManager` — session lifecycle | `voicebridge/session-manager.ts` | Backend |
| 3.5 | Extract `CallHistoryService` — transcript + history | `voicebridge/history-service.ts` | Backend |
| 3.6 | Wrap each service in class with injected Event Bus | All new service files | Backend |
| 3.7 | Update `ServiceFacade` to delegate to individual services | `voicebridge/facade.ts` | Backend |
| 3.8 | Add interfaces for each service | `voicebridge/interfaces.ts` | Backend |

### Estimated Commits: 8
1 per service extraction + 1 facade update + 1 interface extraction

### Testing Requirements
- Every existing API endpoint returns identical responses before and after
- Unit tests for each extracted service (>80% coverage)
- Integration test for cross-service flows (call → callback → notification)

### Rollback Strategy
Keep original `service.ts` as a delegating wrapper. Each extraction moves a function from `service.ts` to the new file but keeps the same public API. Rollback means repointing the facade.

### Risk
- **Regression**: Any extracted function could have implicit dependencies on module-level state. Mitigation: careful audit of each function's state access before extraction.
- **Circular dependencies**: Avoid by ensuring extracted services only depend on Event Bus and interfaces, never on each other's concrete implementations.

### Expected Outcome
- `voicebridge/service.ts` eliminated (or reduced to a thin compatibility shim)
- 5 focused services, each <100 lines
- All services depend on Event Bus + interfaces, not concrete implementations

---

## Phase 4: Persistence Layer

**Goal:** Add database persistence.
**Duration:** 3 weeks
**Risk:** High — data migration required.

This aligns with IMPLEMENTATION_ROADMAP.md Phase 2 (Infrastructure).

### Steps
See IMPLEMENTATION_PLAN.md for detailed breakdown. Key refactoring steps:

| Step | Action | Files |
|------|--------|-------|
| 4.1 | Add PostgreSQL service to docker-compose | `infra/docker-compose.yml` |
| 4.2 | Configure Knex.js with connection pool | `backend/src/database/knex.ts` (new) |
| 4.3 | Create migration files for all entities | `backend/src/database/migrations/` (new) |
| 4.4 | Implement Repository interfaces | `backend/src/database/repositories/` (new) |
| 4.5 | Migrate in-memory state to PostgreSQL | All service files |
| 4.6 | Add Redis service to docker-compose | `infra/docker-compose.yml` |
| 4.7 | Add Redis for presence + rate limiting | `backend/src/common/redis.ts` (new) |

### Rollback Strategy
Keep in-memory fallback. Repository interface allows switching between in-memory and PostgreSQL implementations.

---

## Phase 5: Missing Service Implementation

**Goal:** Implement the 8 missing services from SYSTEM_ARCHITECTURE.md.
**Duration:** 6 weeks
**Risk:** Medium — new code, no legacy to break.

This aligns with IMPLEMENTATION_ROADMAP.md Phases 1-3.

### Services to Implement (in order)

| Order | Service | Depends On | Duration |
|-------|---------|------------|----------|
| 1 | Authentication Service | Event Bus | 2 weeks |
| 2 | Provider Registry | Auth Service | 1 week |
| 3 | Presence Engine | Redis | 1 week |
| 4 | Device Router | Auth Service | 1 week |
| 5 | Notification Engine | Event Bus | 1 week |
| 6 | Communication Gateway | All transport services | 2 weeks |
| 7 | History Service | PostgreSQL | 1 week |

---

## Phase 6: Input Validation

**Goal:** Add Zod validation at all API boundaries.
**Duration:** 1 week
**Risk:** Low — additive change.

### Steps

| Step | Action | Files |
|------|--------|-------|
| 6.1 | Create shared Zod schemas for all API entities | `backend/src/common/schemas.ts` |
| 6.2 | Add Zod validation to all Fastify routes | `backend/src/routes.ts` |
| 6.3 | Add Zod validation to all MCP tool handlers | `mcp-server/src/tools.ts` |
| 6.4 | Use discriminated union for ApiResponse | `mcp-server/src/client.ts` |

---

## Phase 7: Android CallService Decomposition

**Goal:** Decompose the `CallService` god object.
**Duration:** 2 weeks
**Risk:** High — core mobile functionality.

### Steps

| Step | Action | Files | Est. Commits |
|------|--------|-------|-------------|
| 7.1 | Extract `TtsManager` — TTS engine + utterance management | New file | 1 |
| 7.2 | Extract `SttManager` — SpeechRecognizer management | New file | 1 |
| 7.3 | Extract `BargeInDetector` — AudioRecord + PCM detection | New file | 1 |
| 7.4 | Extract `CallSession` — call state + WebSocket event handling | New file | 1 |
| 7.5 | Extract `NotificationHelper` — notification creation + management | New file | 1 |
| 7.6 | Replace `CallService` with thin lifecycle wrapper + delegation | `CallService.kt` | 1 |
| 7.7 | Add Hilt injection for all extracted managers | `AppModule.kt` | 1 |

### Rollback Strategy
Each extraction keeps the original method in `CallService` as a delegating wrapper. Reverse by removing delegation.

---

## Phase 8: Testing Foundation

**Goal:** Add test infrastructure and initial test coverage.
**Duration:** 4 weeks (parallel with other phases)
**Risk:** Low — additive.

### Steps

| Step | Action | Est. Effort |
|------|--------|-------------|
| 8.1 | Fix vitest config | 1 day |
| 8.2 | Add unit tests for `common/config.ts` and `common/logger.ts` | 1 day |
| 8.3 | Add unit tests for `voicebridge/call-manager.ts` (post-refactor) | 3 days |
| 8.4 | Add unit tests for `voicebridge/callback-engine.ts` | 2 days |
| 8.5 | Add unit tests for `mcp-server/tools.ts` | 2 days |
| 8.6 | Add unit tests for `mcp-server/client.ts` | 1 day |
| 8.7 | Add unit tests for Android ViewModels | 3 days |
| 8.8 | Add integration tests for cross-service flows | 5 days |
| 8.9 | Add CI workflow for test coverage enforcement | 1 day |

---

## Phase 9: Infrastructure & CI/CD Hardening

**Goal:** Production-ready infrastructure.
**Duration:** 1 week
**Risk:** Low — additive.

### Steps

| Step | Action |
|------|--------|
| 9.1 | Delete root `Dockerfile` (duplicate) |
| 9.2 | Add `.dockerignore` files |
| 9.3 | Add HEALTHCHECK to MCP server |
| 9.4 | Add resource limits to docker-compose |
| 9.5 | Fix CI ESLint silent failure |
| 9.6 | Add MCP server linting to CI |
| 9.7 | Add deployment workflow |
| 9.8 | Add pre-commit hooks |
| 9.9 | Add Dependabot config |

---

## Refactoring Dependency Graph

```
Phase 0 (Philosophy cleanup)
    │
    ▼
Phase 1 (Security hardening)
    │
    ├──────────────────────────┐
    ▼                          ▼
Phase 2 (Event Bus)    Phase 8 (Testing)
    │
    ▼
Phase 3 (Service Decomposition)
    │
    ├──────────────────────────┐
    ▼                          ▼
Phase 4 (Persistence)   Phase 6 (Validation)
    │
    ├──────────────────────────┐
    ▼                          ▼
Phase 5 (Missing Services)  Phase 7 (Android)
    │
    ▼
Phase 9 (Infrastructure)
```

## Total Estimated Duration: 14 weeks (parallelizable)

| Phase | Duration | Can Parallelize With |
|-------|----------|---------------------|
| 0 Philosophy cleanup | 2 days | — |
| 1 Security hardening | 3 days | Phase 0 |
| 2 Event Bus | 1 week | Phase 1 |
| 3 Service Decomposition | 2 weeks | Phase 2 completion required |
| 4 Persistence | 3 weeks | Phase 3 completion required |
| 5 Missing Services | 6 weeks | Phase 4 required for some |
| 6 Input Validation | 1 week | Phase 2+ |
| 7 Android Decomposition | 2 weeks | Phases 2-6 (independent) |
| 8 Testing Foundation | 4 weeks | Phases 0-7 (parallel) |
| 9 Infrastructure | 1 week | Phases 2-7 (parallel) |
