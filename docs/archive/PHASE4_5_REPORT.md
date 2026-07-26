# Phase 4.5 — Persistence Verification

## Summary

A `PersistenceVerifier` class that compares in-memory and PostgreSQL state during dual-write. Read-only — no writes, no repairs, no mutations.

The application continues reading exclusively from memory. The verifier is a parallel observer that logs mismatches without affecting runtime behaviour.

---

## Verification Architecture

```
┌──────────────────────────────────────────────────────────┐
│                    PersistenceVerifier                    │
│                                                          │
│  verify() once at startup (fire-and-forget)              │
│  verify() periodically (configurable intervalMs)         │
│  stop() on shutdown                                      │
│                                                          │
│  ┌──────────────────────────────────────────────────┐    │
│  │  Comparison Algorithm                            │    │
│  │                                                  │    │
│  │  1. List sessions from in-memory repo             │    │
│  │  2. List sessions from database repo              │    │
│  │  3. Index both by ID                              │    │
│  │  4. For each memory session:                      │    │
│  │     ├── Missing in DB → session_missing_in_db     │    │
│  │     ├── Status different → status_mismatch        │    │
│  │     ├── Retention different → retention_mismatch  │    │
│  │     └── Timestamp different → timestamp_mismatch  │    │
│  │  5. For each DB session not in memory:            │    │
│  │     └── → session_missing_in_memory               │    │
│  │  6. Same for callbacks (callId, resumeAt)         │    │
│  │  7. Log structured results                        │    │
│  └──────────────────────────────────────────────────┘    │
│                                                          │
│  Reads directly from raw InMemory* repos                 │
│  Reads directly from raw Database* repos                 │
│  (bypasses DualWrite wrapper — reads from both sources)  │
└──────────────────────────────────────────────────────────┘
```

The verifier accesses the raw repository instances directly — not through the `DualWrite*` wrappers. This is essential: the dual-write wrapper always reads from memory, so using it would never reveal database-side data. The verifier needs to compare both backends independently.

---

## Comparison Algorithm

### Session comparison

For each session in memory, the verifier checks existence in the database and compares these fields:

| Field | Comparison | Mismatch Type |
|---|---|---|
| (existence) | Present in DB? | `session_missing_in_db` |
| `status` | Exact string match | `status_mismatch` |
| `retentionExpiresAt` | Normalized (null/undefined treated as equivalent) | `retention_mismatch` |
| `createdAt` | Normalized | `timestamp_mismatch` |
| `connectedAt` | Normalized | `timestamp_mismatch` |
| `completedAt` | Normalized | `timestamp_mismatch` |
| `pausedAt` | Normalized | `timestamp_mismatch` |
| `resumedAt` | Normalized | `timestamp_mismatch` |

For each session in the database not found in memory, a `session_missing_in_memory` mismatch is recorded.

### Callback comparison

For each callback in memory, the verifier checks existence in the database and compares:

| Field | Comparison | Mismatch Type |
|---|---|---|
| (existence) | Present in DB? | `callback_missing_in_db` |
| `callId` | Exact string match | `callback_field_mismatch` |
| `resumeAt` | Exact number match | `callback_field_mismatch` |

For each callback in the database not found in memory, a `callback_missing_in_memory` mismatch is recorded.

### Null/undefined normalization

Database queries return `null` for missing optional fields. In-memory repos return `undefined`. The verifier treats both as equivalent — `null` and `undefined` are not reported as mismatches.

---

## Metrics

Every `verify()` call produces a `VerificationMetrics` object:

```typescript
export interface VerificationMetrics {
  durationMs: number;            // how long the comparison took
  sessionsInMemory: number;      // count from memory repo
  sessionsInDatabase: number;    // count from database repo
  callbacksInMemory: number;     // count from memory repo
  callbacksInDatabase: number;   // count from database repo
  mismatches: Mismatch[];        // all differences found
  dbQueryFailures: number;       // how many DB queries failed
}
```

### Logging

**When verified successfully (no mismatches, no failures):**
```
[PersistenceVerifier] verified — no mismatches
  { durationMs: 12, sessionsCompared: 3, callbacksCompared: 0 }
```

**When differences are found:**
```
[PersistenceVerifier] verification found differences
  { mismatchCount: 2, mismatches: [
    "status_mismatch:abc-123=memory=\"active\" db=\"paused\"",
    "session_missing_in_db:abc-456=status=\"completed\""
  ], ... }
```

**When a database query fails:**
```
[PersistenceVerifier] failed to list sessions from database
  { err: ... }
```

---

## Startup Behaviour

The verifier runs its initial check immediately after creation:

```typescript
verifier.verify().catch((err) => {
  logger.error({ err }, '[PersistenceVerifier] initial check failed');
});
```

This is fire-and-forget — it does not block the startup sequence. If the database is unavailable, the error is logged and the application continues.

## Periodic Verification

Configured via `DB_VERIFICATION_INTERVAL_MS` environment variable:

- `0` (default) — no periodic verification, only runs on explicit `verify()` call
- `> 0` — runs `verify()` on the given interval, using `setInterval` with `.unref()`

The interval timer does not prevent process shutdown.

---

## Interface Change: `CallbackRepository.list()`

The `CallbackRepository` interface gained a `list()` method:

```typescript
list(): Promise<CallbackEntry[]>;
```

Where `CallbackEntry` includes the `userId` key:

```typescript
export interface CallbackEntry {
  userId: string;
  callId: string;
  resumeAt: number;
}
```

Implemented by:
- `InMemoryCallbackRepository` — iterates the internal Map
- `DatabaseCallbackRepository` — `SELECT * FROM callbacks`
- `DualWriteCallbackRepository` — delegates to `primary.list()` (reads from memory)

This was required because the verifier needs to enumerate all callbacks, and no prior method supported that. It does not affect existing consumers (`LifecycleCoordinator`, `VoiceBridgeService`).

---

## Validation Results

| Check | Result |
|---|---|
| ESLint (backend) | Pass |
| tsc --noEmit (backend) | Pass |
| ESLint (mcp-server) | Pass |
| Business logic changes | None — `service.ts`, `lifecycle-coordinator.ts`, `sweeper.ts`, `routes.ts` unmodified |
| Reads still come from memory | Confirmed — `DualWrite*` wrappers unchanged, continue reading from `primary` |
| Database never becomes authoritative | Confirmed — verifier is read-only, no writes, no repairs |
| Event Bus unchanged | None |
| No DATABASE_URL = no verifier | Confirmed — verifier created only inside `if (config.database.url)` |

---

## Regression Analysis

**Low risk.** The only runtime changes to existing files:

1. **`index.ts`**: `PersistenceVerifier` is created and run inside the `if (config.database.url)` block. When no database is configured, the verifier is never instantiated — zero impact.

2. **`CallbackRepository` interface**: `list()` added. All existing implementations (`InMemoryCallbackRepository`, `DatabaseCallbackRepository`, `DualWriteCallbackRepository`) implement it. Existing consumers (`LifecycleCoordinator`) don't call `list()` — only the `PersistenceVerifier` does.

3. **`config.ts`**: `verificationIntervalMs` added with default `0` (disabled). No change to existing behaviour.

4. **`.env.example`**: Optional env var added. No existing deployments are affected.

5. **New files**: `verifier.ts` — self-contained, no dependencies on runtime business logic.

---

## Files Created

```
backend/src/voicebridge/repositories/verifier.ts
```

## Files Modified

```
backend/src/voicebridge/repositories/callback-repository.ts       — added CallbackEntry + list()
backend/src/voicebridge/repositories/db-callback-repository.ts     — implemented list()
backend/src/voicebridge/repositories/dual-write-callback-repository.ts — implemented list()
backend/src/voicebridge/repositories/index.ts                      — exports CallbackEntry, PersistenceVerifier
backend/src/common/config.ts                                       — added verificationIntervalMs
backend/.env.example                                                — added DB_VERIFICATION_INTERVAL_MS
backend/src/index.ts                                               — wired PersistenceVerifier in startup + shutdown
```
