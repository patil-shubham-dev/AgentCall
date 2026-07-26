# Phase 4.7 — Startup Recovery

## Summary

In-memory state is reconstructed from PostgreSQL on restart via `RecoveryManager`. Two-phase startup: (A) populate `InMemorySessionRepository` and `InMemoryCallbackRepository` before dual-write wrappers are created, (B) rebuild `CleanupScheduler` timers for callback resumes and orphaned pause-ttl expiry after `LifecycleCoordinator` is created.

No reads flipped to PostgreSQL. No behavioural changes to runtime paths.

---

## Architecture

```
startup
  │
  ├── create InMemorySessionRepository, InMemoryCallbackRepository
  │
  ├── if database configured:
  │     ├── create DatabaseSessionRepository, DatabaseCallbackRepository
  │     │
  │     ├── Phase A: RecoveryManager.loadFromDatabase()
  │     │     ├── dbSessionRepo.list()   → memorySessionRepo.create()  for each
  │     │     └── dbCallbackRepo.list()   → memoryCallbackRepo.save()  for each
  │     │
  │     ├── wrap with DualWrite*Repository (reads now go to populated memory)
  │     └── start PersistenceVerifier
  │
  ├── create VoiceBridgeService, LifecycleCoordinator
  │
  │     └── Phase B: RecoveryManager.rebuildTimers()
  │           ├── rebuildCallbackTimers()
  │           │     └── for each callback: resumeCallback(userId, callId, delayMinutes, resumeAt)
  │           │           → schedules `resume:callId` + `pause-ttl:callId` timers
  │           │
  │           └── rebuildOrphanedPauseTimers()
  │                 └── for each paused session without callback: recoverOrphanedPause(callId, pausedAt)
  │                       → schedules `pause-ttl:callId` timer, or fires expiry immediately if already elapsed
  │
  ├── create SessionSweeper
  │     └── sessionSweeper.sweep()   ← immediate post-recovery sweep (fire-and-forget)
  │
  └── start SessionSweeper interval
```

### Phase A — `loadFromDatabase()`

Iterates `dbSessionRepo.list()` and `dbCallbackRepo.list()`, writes every entity into the corresponding in-memory repository. Runs **before** `DualWrite*` wrapper creation, so dual-write reads (which go to memory) immediately see recovered state.

### Phase B — `rebuildTimers()`

Two sub-phases:

1. **Callback timers**: For each `CallbackEntry` in memory, looks up the session to compute `delayMinutes = floor((resumeAt - pausedAt) / 60000)`, then calls `lifecycleCoordinator.resumeCallback()`. This schedules `resume:callId` and `pause-ttl:callId` timers.

2. **Orphaned pause timers**: Lists all sessions, finds `paused` sessions whose `callId` is NOT in any callback record, and calls `lifecycleCoordinator.recoverOrphanedPause()`. This schedules only the `pause-ttl:callId` timer. If the pause-ttl has already expired during downtime, `handlePauseExpiry()` fires immediately (cancels the session).

### Post-recovery sweep

After timer rebuild, `sessionSweeper.sweep()` fires once immediately (fire-and-forget) to delete any sessions whose retention period expired while the server was down.

---

## Key Behaviours

| Scenario | Behaviour |
|---|---|
| Paused session with callback | Both `resume:callId` and `pause-ttl:callId` timers recreated. Normal callback flow resumes at scheduled time. |
| Paused session without callback (orphaned) | Only `pause-ttl:callId` timer recreated. Session expires after 24h from pausedAt if not resumed. |
| Pause-ttl already expired during downtime | `handlePauseExpiry` fires immediately — session cancelled, user notified via WebSocket (if connected). |
| Retention expired during downtime | Post-recovery sweep deletes the session immediately. |
| No database configured | `recoveryManager` stays `undefined`, no recovery runs — pure in-memory operation unchanged. |

---

## Changes

### New file: `backend/src/voicebridge/recovery-manager.ts`

- `RecoveryManager` class with `loadFromDatabase()` (Phase A) and `rebuildTimers()` (Phase B)
- Constructor takes DB repos and in-memory repos
- `rebuildCallbackTimers()` — iterates callbacks, calls `lifecycleCoordinator.resumeCallback()`
- `rebuildOrphanedPauseTimers()` — iterates paused sessions without callbacks, calls `lifecycleCoordinator.recoverOrphanedPause()`

### Modified: `backend/src/voicebridge/lifecycle-coordinator.ts`

Added `recoverOrphanedPause(callId: string, pausedAt: string): Promise<void>`:
- Checks session exists and is `paused`
- If pause-ttl (24h) already expired: calls `handlePauseExpiry()` immediately
- Otherwise: schedules `pause-ttl:callId` timer on `CleanupScheduler`

### Modified: `backend/src/voicebridge/sweeper.ts`

Changed `private async sweep()` to `public async sweep()` — allows immediate post-recovery invocation.

### Modified: `backend/src/index.ts`

- Import `RecoveryManager`
- Phase A: create `RecoveryManager`, call `loadFromDatabase()` between DB repo creation and dual-write wrapping
- Phase B: call `rebuildTimers()` after `LifecycleCoordinator` creation
- Immediate `sessionSweeper.sweep()` call after sweeper creation (fire-and-forget, `.catch` logged)

---

## Validation Results

| Check | Result |
|---|---|
| `tsc --noEmit` (backend) | Pass |
| ESLint (backend) | Pass |
| Business logic unchanged | Confirmed — `service.ts`, `routes.ts`, `coordinator.ts`, all publisher files unmodified |
| Reads still come from memory | Confirmed — dual-write wrappers unchanged; recovery populates memory before wrapping |
| Database not authoritative | Confirmed — DB is read only during startup; runtime reads go to memory |
| Event Bus unchanged | None |

---

## Regression Analysis

**Minimal risk.** Changes are confined to startup sequencing:

1. **`lifecycle-coordinator.ts`**: One new method. Existing `resumeCallback`, `handleResume`, `handlePauseExpiry` unmodified.
2. **`sweeper.ts`**: Access modifier change only (`private` → `public`). No behavioural change.
3. **`index.ts`**: Startup order unchanged when DB is not configured (short-circuit exists). Only affects DB-enabled startup path.
4. **`recovery-manager.ts`**: New file, not referenced anywhere else.
