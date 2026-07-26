# Phase 4.2 — Repository Extraction

## Objective

Move ownership of all in-memory Maps into dedicated repository classes. Business logic no longer knows Maps exist. All dependencies are injected via constructors.

## Repository Classes

### `backend/src/voicebridge/repositories/session-repository.ts`

**`SessionRepository` interface:**
```typescript
findById(callId: string): Promise<VoiceCallSession | undefined>
findByUserId(userId: string): Promise<VoiceCallSession[]>
list(): Promise<VoiceCallSession[]>
create(session: VoiceCallSession): Promise<void>
delete(callId: string): Promise<VoiceCallSession | undefined>
```

**`InMemorySessionRepository`** — owns the `sessions` Map privately. All CRUD and iteration go through the interface.

### `backend/src/voicebridge/repositories/callback-repository.ts`

**`CallbackRepository` interface:**
```typescript
findByUserId(userId: string): Promise<CallbackData | undefined>
save(userId: string, data: CallbackData): Promise<void>
delete(userId: string): Promise<void>
```

**`InMemoryCallbackRepository`** — owns the `scheduledCallbacks` Map privately.

## Ownership Diagram

```
┌─────────────────────────────────────────────────────┐
│                   index.ts (startup)                 │
│                                                      │
│  creates InMemorySessionRepository                   │
│  creates InMemoryCallbackRepository                  │
│  creates VoiceBridgeService(repos)                   │
│  creates LifecycleCoordinator(repos, notifyPhone)    │
│  creates SessionSweeper(repo, isExpired, coordinator)│
│  creates DeletionCoordinator()                       │
└───────┬──────────────┬──────────────┬────────────────┘
        │              │              │
        ▼              ▼              ▼
┌───────────────┐ ┌──────────┐ ┌──────────────┐
│InMemorySession│ │InMemory  │ │module-level  │
│Repository     │ │Callback  │ │Maps:         │
│               │ │Repository│ │phoneConnections│
│ .sessions Map │ │.callbacks│ │ (WebSocket)  │
└───┬───────────┘ └───┬──────┘ └──────────────┘
    │                 │
    │  injected into  │
    ▼                 ▼
┌─────────────────────────────────────┐
│         VoiceBridgeService          │
│  (all business logic, no Map refs)  │
└─────────────────────────────────────┘
    │                 │
    │  injected into  │
    ▼                 ▼
┌─────────────────────────────────────┐
│         LifecycleCoordinator        │
│  (callback resume + pause TTL)       │
└─────────────────────────────────────┘

┌─────────────────────────────────────┐
│         SessionSweeper              │
│  (uses sessionRepo.list/delete)      │
└─────────────────────────────────────┘
```

## Dependency Graph

| Component | Depends on |
|---|---|
| `VoiceBridgeService` | `SessionRepository`, `CallbackRepository`, `LifecycleCoordinator` (setter) |
| `LifecycleCoordinator` | `CleanupScheduler`, `SessionRepository`, `CallbackRepository`, `notifyPhone` |
| `SessionSweeper` | `SessionRepository`, `DeletionCoordinator`, `isExpired` |
| `DeletionCoordinator` | *(none — standalone, only logger + publisher)* |
| `registerRoutes` | `VoiceBridgeService` (passed as parameter) |

No module-level globals remain for session or callback state. The only remaining module-level Map is `phoneConnections` (WebSocket connections), which is isolated in `service.ts` and used only by `registerPhone()` and `notifyPhone()` — these are standalone functions, not part of any repository.

## Maps Removed from service.ts

| Map | Owner | Reason to stay module-level? |
|---|---|---|
| `sessions` | `InMemorySessionRepository.sessions` | No — belongs in repository |
| `scheduledCallbacks` | `InMemoryCallbackRepository.callbacks` | No — belongs in repository |
| `phoneConnections` | `service.ts` (module-level) | Yes — WebSocket connection pool, not session/CRUD data |

## LifecycleSessionStore Removed

The `LifecycleSessionStore` interface in `lifecycle-coordinator.ts` is deleted. The coordinator now accepts `SessionRepository` and `CallbackRepository` directly in its constructor.

## Consumers Updated

| File | Change |
|---|---|
| `routes.ts` | `registerRoutes` now takes `(app, voicebridge: VoiceBridgeService)` instead of `(app)`; all service calls `await`ed |
| `index.ts` | Repos created in `main()`, injected into service/coordinator/sweeper; `setLifecycleCoordinator` module-level function removed; `registerRoutes` gets service instance |
| `sweeper.ts` | `SessionRepository` imported from `./repositories/index.js` instead of locally defined |
| `lifecycle-coordinator.ts` | Constructor changed from `(scheduler, LifecycleSessionStore, notifyPhone)` to `(scheduler, SessionRepository, CallbackRepository, notifyPhone)` |
| `signaling/server.ts` | No change — uses `voicebridge.registerPhone` via namespace import, still exported from service.ts |

## Code Removed

- `service.ts`: `sessions` Map, `scheduledCallbacks` Map, `lifecycleCoordinator` module-level variable, `setLifecycleCoordinator` module-level function, `deleteScheduledCallback` module-level function
- `lifecycle-coordinator.ts`: `LifecycleSessionStore` interface

## Validation

| Check | Result |
|---|---|
| ESLint (backend) | Pass |
| tsc --noEmit (backend) | Pass |
| ESLint (mcp-server) | Pass |
| Behavioural changes | None — same synchronous Map operations, same async interfaces |
| Event Bus changes | None |
| Persistence added | None |
| New module-level globals | None |

## Regression Analysis

**Low risk.** Every change is structural:

1. `service.ts` → class: all functions become methods using `this.sessionRepo` / `this.callbackRepo` instead of module-level Maps. The internal behaviour is identical — `findById` on `InMemorySessionRepository` calls `.get()` on its private Map.
2. `routes.ts` → parameter injection: the only structural change is `registerRoutes(app, voicebridge)` instead of `registerRoutes(app)`. Every route handler now `await`s service calls, which was already the pattern for async route handlers.
3. Index.ts wiring → DI: repos created up-front, same in-memory behaviour, same singleton lifecycle.
4. Signaling server: unchanged — `registerPhone` is still a module-level export, namespace import `import * as voicebridge` works with class + function exports.
5. The `phoneConnections` Map stays module-level because it represents live WebSocket connections (connection pool, not data). It is purely a transport concern, not a persistence candidate.

## Files Created

```
backend/src/voicebridge/repositories/session-repository.ts
backend/src/voicebridge/repositories/callback-repository.ts
backend/src/voicebridge/repositories/index.ts
```

## Files Modified

```
backend/src/voicebridge/service.ts
backend/src/voicebridge/sweeper.ts
backend/src/voicebridge/lifecycle-coordinator.ts
backend/src/routes.ts
backend/src/index.ts
```
