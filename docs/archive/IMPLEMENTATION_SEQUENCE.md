# AgentCall — Implementation Sequence

> **Date:** 2026-07-26
> **Purpose:** Determine the safest implementation order that minimizes risk and maximizes parallelization.

---

## Sequence Overview

```
Week 0:  ─── Precondition ───
          Remove architecture violations (1 week)
          Fix vitest config (1 day)

Week 1-2: ─── Foundation ───
          Event Bus (1 week)
          Service Facade (3 days)
          Config hardening (2 days)
          Test infrastructure (parallel)

Week 3-4: ─── Decoupling ───
          Service Decomposition (2 weeks)
          Input validation (parallel)
          CI/CD fixes (parallel)

Week 5-7: ─── Persistence ───
          PostgreSQL (2 weeks)
          Repository Layer (1 week)
          State Migration (1 week)
          Redis (1 week, parallel)

Week 8-13: ─── New Services ───
          Auth Service (2 weeks)
          Provider Registry (1 week)
          Presence Engine (1 week)
          Notification Engine (1 week)
          Device Router (1 week)
          Gateway (2 weeks)
          Missing MCP Tools (1 week)

Week 1-13: ─── Android (parallel) ───
          CallService decomposition (2 weeks)
          WebSocket fix (1 week)
          Dead code removal (1 day)

Week 1-13: ─── Testing (parallel) ───
          Unit tests (3 weeks)
          Integration tests (2 weeks)
          Android tests (1 week)

Week 13:  ─── Finalize ───
          Infrastructure hardening (1 week)
          Documentation updates (2 days)
          Repository cleanup (1 week)
```

---

## Phase 0: Philosophy Cleanup (Precondition)

**Duration:** 1 week
**Why first:** The text enrichment, emotion detection, and barge-in classification code violates the core architecture philosophy. Every subsequent change would be built on a foundation that contradicts PRODUCT_VISION.md. These functions also make the system non-deterministic (`Math.random()`), making testing impossible.

**Activities:**
- Delete `enrichText()`, `emotionOf()`, `extractEmotionTag()`, `detectBargeIn()` from backend
- Delete `CommandPattern`, filler words, breathing pauses, emotion adjustment from Android
- Remove associated types from backend type definitions
- Fix vitest config to enable testing

**Risks:**
- Removing emotion features may affect the user experience (AI no longer sounds "human")
- Mitigation: This is intentional — AgentCall is a communication platform, not an AI personality layer. The AI provider handles personality.

---

## Phase 1: Event Bus & Service Facade

**Duration:** 1.5 weeks
**Why second:** The Event Bus is the architectural backbone for all service communication. Without it, we cannot decompose services, add new ones, or test in isolation. The Service Facade provides a clean API boundary between HTTP and business logic.

**Order within phase:**
1. Event Bus infrastructure (pub/sub, typed events, retry, dead letter)
2. Define system event types
3. Service Facade interface + implementation
4. Migrate routes to use facade
5. Decouple signaling from service via Event Bus

**Why Event Bus before Service Facade:**
- The facade publishes events, so it needs Event Bus first
- Decoupling signaling requires Event Bus first

---

## Phase 2: Service Decomposition

**Duration:** 2 weeks
**Why third:** Once the Event Bus is in place, we can safely extract services from the `voicebridge/service.ts` monolith. Each extraction publishes/receives events, making it truly independent.

**Order within phase:**
1. CallManager (most stable, fewest dependencies)
2. CallbackEngine (fixes real bugs — untracked timers, userId keying)
3. PhoneRegistry (depends on Event Bus — was previously coupled to signaling)
4. SessionManager (new service, built from scratch)
5. CallHistoryService (extracts transcript logic)

**Why CallManager first:**
- It's the largest piece and the most well-understood
- Everything depends on calls, so extracting it first maximizes downstream benefit

---

## Phase 3: Input Validation

**Duration:** 1 week (can parallelize with Phase 2)
**Why parallel:** Adding Zod schemas is purely additive — it doesn't change any behavior. It can be done independently of service decomposition.

**Activities:**
- Create shared Zod schemas
- Add validation to all REST endpoints
- Add validation to all MCP tool handlers
- Fix `ApiResponse<T>` to use discriminated union

---

## Phase 4: CI/CD Fixes

**Duration:** 1 week (can parallelize with Phases 2-3)
**Why parallel:** CI fixes don't affect source code. They improve engineering hygiene without touching business logic.

**Activities:**
- Fix ESLint silent failure in CI
- Add MCP server linting
- Add pre-commit hooks
- Add Dependabot config
- Add deploy workflow
- Add .dockerignore

---

## Phase 5: Persistence

**Duration:** 3 weeks
**Why fifth:** Persistence requires stable service interfaces (from Phase 2) and the repository abstraction. Adding PostgreSQL before the services are decomposed would require migrating the monolith, which is riskier.

**Order within phase:**
1. PostgreSQL setup + migrations
2. Repository interfaces + implementations
3. Inject repositories into services (no behavioral change)
4. Remove in-memory state from services (behavioral change — test carefully)
5. Redis setup
6. Presence Engine on Redis

**Why repositories before state migration:**
- Repository pattern allows in-memory + PostgreSQL implementations side by side
- Can test PostgreSQL implementation against in-memory reference
- Rollback means switching back to in-memory

---

## Phase 6: Missing Services

**Duration:** 6 weeks
**Why sixth:** New services require both Event Bus (Phase 1) and Persistence (Phase 5). Building them before the persistence layer would mean writing in-memory implementations that must be rewritten.

**Order within phase:**

| Order | Service | Why This Order |
|-------|---------|----------------|
| 1 | Authentication Service | Everything needs auth. Must exist before Device Router or Provider Registry. |
| 2 | Provider Registry | Depends on auth (provider API keys). Need providers before sessions. |
| 3 | Device Router | Depends on auth (user identity). Need devices before Gateway. |
| 4 | Notification Engine | Depends on Device Router (push targets). Event-driven — subscribes to call events. |
| 5 | Presence Engine | Depends on Redis (already in Phase 5). Independent of other services. |
| 6 | Communication Gateway | Depends on Device Router + Auth. Must exist before MCP tools that need it. |
| 7 | Missing MCP Tools | Depends on Presence Engine (query_presence) + Gateway (notify_completion). |

---

## Phase 7: Android Decomposition

**Duration:** 2 weeks (can start in parallel with Phase 2)
**Why parallel:** Android decomposition is independent of backend changes. The Android app communicates via HTTP + WebSocket — the backend API contract doesn't change during refactoring.

**Order within phase:**
1. Extract TtsManager (no dependencies on other extracted classes)
2. Extract SttManager (same)
3. Extract BargeInDetector (same)
4. Extract CallSession (depends on TtsManager + SttManager + BargeInDetector)
5. Extract NotificationHelper (independent)
6. Thin CallService (delegates to extracted managers)
7. Fix WebSocket reconnection (exponential backoff, max retries, heartbeat)
8. Remove dead ViewModel methods

---

## Phase 8: Testing

**Duration:** 4 weeks (parallel with all phases from Week 1)
**Why parallel:** Testing should start immediately and continue throughout implementation. It's not a phase that happens after coding — it's an integral part of every phase.

**Prioritization within testing:**
1. Fix vitest config (Week 0 — precondition)
2. Unit tests for Event Bus (Week 2 — validates Phase 1)
3. Unit tests for extracted services (Week 4 — validates Phase 2)
4. Unit tests for repositories (Week 6 — validates Phase 5)
5. Integration tests for cross-service flows (Week 7)
6. MCP tool tests (Week 8)
7. Android ViewModel tests (Week 8)
8. Android signaling tests (Week 10)

---

## Phase 9: Infrastructure Hardening

**Duration:** 1 week (near the end)
**Why late:** Infrastructure hardening (deployment workflow, container security, monitoring) is important but doesn't block earlier phases. Development can proceed with manual Docker Compose and npm scripts.

**Activities:**
- Add deployment workflow
- Add Docker registry push
- Add container resource limits
- Fix root Dockerfile (delete duplicate)
- Add Docker image vulnerability scanning
- Add health checks

---

## Phase 10: Repository Cleanup

**Duration:** 1 week (final phase)
**Why last:** Most cleanup items (dead code removal, file restructuring, git cleanup) are safer at the end when the codebase is stable. Removing dead code earlier would require updating diffs as the codebase changes.

**Activities:**
- Remove archived iOS project
- Remove orphan dist files
- Remove dead code (confirmed none is referenced)
- Git cleanup (remove tracked dist files)
- Standardize naming
- Documentation updates

---

## Dependency Graph

```
Phase 0 (Philosophy Cleanup)
    │
    ▼
Phase 1 (Event Bus & Facade) ←───── Phase 6 (Testing — parallel)
    │
    ├───────────────────────────────┐
    ▼                               ▼
Phase 2 (Service Decomp)   Phase 3 (Validation)   Phase 7 (Android)
    │                               │
    ▼                               ▼
Phase 5 (Persistence) ←──── Phase 4 (CI/CD — parallel)
    │
    ▼
Phase 6 (Missing Services)
    │
    ▼
Phase 9 (Infrastructure)
    │
    ▼
Phase 10 (Repository Cleanup)
```

---

## Why NOT This Order

| Alternative Order | Why Not |
|------------------|---------|
| Persistence first | The monolith is tightly coupled. Adding a database before decoupling would mean migrating a single 284-LOC service that does everything. Far riskier. |
| Testing first | Tests need stable interfaces. Writing tests against `voicebridge/service.ts` before extraction means rewriting them after. |
| Android first | Independent of backend. Can proceed in parallel. No reason to prioritize. |
| Missing services first | New services need Event Bus and Persistence. Building them without the foundation means double work. |
| Infrastructure first | Doesn't block development. CI/CD improvements can happen incrementally. |

---

## Risk by Phase

| Phase | Risk Level | Primary Risk | Mitigation |
|-------|------------|-------------|------------|
| 0 Philosophy Cleanup | Low | Removing features users may rely on | These are internal enrichment features not exposed to users directly |
| 1 Event Bus | Medium | Performance overhead of Event Bus vs direct calls | Start with in-process sync handlers, measure, migrate to async if needed |
| 2 Service Decomposition | High | Regression from extracting code | Keep original service.ts as delegating shim; A/B test |
| 3 Validation | Low | Breaking changes to API responses | Add validation alongside existing code, don't replace error handling yet |
| 4 CI/CD | Low | CI breaking during changes | Test CI changes in a separate branch first |
| 5 Persistence | High | Data loss during migration | Keep in-memory fallback; dual-write during migration |
| 6 New Services | Medium | Creating services that are too coupled to each other | Strictly enforce Event Bus communication |
| 7 Android | High | Breaking the Android app | Keep original CallService alongside extracted managers during transition |
| 8 Testing | Low | Tests passing but wrong | Test both old and new implementations side by side |
| 9 Infrastructure | Low | No significant risk | Additive changes |
| 10 Cleanup | Low | Accidentally removing something still needed | All removals validated by grep first |
