# Phase 3.0 — Lifecycle & Resource Management Foundation

**Status:** Complete  
**Date:** 2026-07-26  
**Scope:** Cleanup scheduling infrastructure only — no session deletion, no retention enforcement

---

## Summary

Created a generic `CleanupScheduler` class in `backend/src/common/cleanup-scheduler.ts` that tracks future cleanup work and invokes callbacks when scheduled time arrives. No business logic, no knowledge of sessions or calls, no Event Bus changes. The scheduler is instantiated once at startup and exposed via Fastify DI (`app.decorate`). Nothing is scheduled yet — this is infrastructure only.

---

## Scheduler Design

```
CleanupScheduler (common/cleanup-scheduler.ts)
│
├── schedule(id, executeAt, callback)   → registers a one-shot timer
├── cancel(id)                          → cancels a pending job
├── has(id)                             → checks if a job exists
├── pending()                           → returns sorted list of pending jobs
└── shutdown()                          → cancels all jobs, clears state
```

**Key design choices:**
- Generic: no knowledge of sessions, calls, notifications, or any domain concept
- Uses `setTimeout` + `.unref()` per task (matching existing pattern from `scheduleCallback`)
- `shutdown()` sets a flag and clears all timers; attempts to `schedule()` after shutdown are silently no-ops
- `cancel()` is idempotent-safe: calling `schedule(id, ...)` implicitly cancels any existing task with the same id
- `pending()` returns results sorted by `executeAt` for orderly inspection
- No global mutable state — instance-based

---

## Public API

```typescript
export interface PendingCleanup {
  id: string;
  executeAt: number;    // epoch ms
  remainingMs: number;  // ms until execution
}

export class CleanupScheduler {
  schedule(id: string, executeAt: number | Date, callback: () => void): void;
  cancel(id: string): boolean;
  has(id: string): boolean;
  pending(): PendingCleanup[];
  shutdown(): void;
}
```

---

## Files Created

| File | Lines | Purpose |
|---|---|---|
| `backend/src/common/cleanup-scheduler.ts` | 56 | Generic timer-based cleanup scheduler |

## Files Modified

| File | Change |
|---|---|
| `backend/src/index.ts` | Import `CleanupScheduler`, instantiate, `app.decorate('cleanupScheduler')`, wire `cleanupScheduler.shutdown()` into graceful shutdown, extend Fastify module declaration |

---

## Dependency Injection Flow

```
index.ts:main()
  │
  ├── const cleanupScheduler = new CleanupScheduler()
  │
  ├── app.decorate('cleanupScheduler', cleanupScheduler)
  │     → FastifyInstance.cleanupScheduler (DI)
  │     → Available to all route handlers and plugins
  │
  └── shutdown:
        cleanupScheduler.shutdown()     ← cancels all pending timers
        app.close()
        signalingServer.close()
        eventBus.shutdown()
```

No domain module imports the scheduler yet. No sessions are touched.

---

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| MCP Server `eslint src/ --ext .ts` | Pass (no regression) |
| No runtime behaviour changed | Yes — scheduler is instantiated but never used |
| No Event Bus changes | Yes — `event-bus/` untouched |
| No session deletion | Yes — `sessions.delete()` never called |
| No cleanup execution | Yes — callbacks are never invoked |
| No global mutable state | Yes — instance-based, no static/shared state |

---

## Regression Check

- No existing code paths modified
- No Event Bus internals touched
- No domain modules modified (`service.ts`, `routes.ts`, `calls/`, `signaling/`, etc. all untouched)
- `cleanupScheduler.shutdown()` is added to the shutdown sequence but is a no-op when no tasks exist
- Scheduler does not import or depend on any domain module

---

## Explicitly Not Implemented

- No cleanup is executed
- No sessions are deleted
- No retention timers are wired
- No `call.deleted` event
- No `call.expired` event
- No `SessionStore` abstraction
- No persistence
- No periodic sweep
- No service.ts modifications

Scheduler infrastructure only. Ready for Phase 3.1 (Retention Scheduling).
