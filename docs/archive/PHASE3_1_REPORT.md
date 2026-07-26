# Phase 3.1 — Sweep-Based Retention Foundation

**Status:** Complete  
**Date:** 2026-07-26  
**Scope:** Expiry metadata, `isExpired` helper, dry-run sweep service — no deletion, no events, no scheduling

---

## Summary

Added `retentionExpiresAt` field to `VoiceCallSession`, set it when sessions enter terminal states (`completed` / `cancelled`), created a pure `isExpired` helper for expiry calculations, and built a background `SessionSweeper` that scans sessions at a configurable interval and logs which ones would be removed. No sessions are deleted. No events are published. No state is modified.

---

## Expiry Metadata

### New field on `VoiceCallSession`

```typescript
// types.ts
retentionExpiresAt?: string;  // ISO-8601 timestamp
```

Set in `completeCall()` and `cancelCall()` after `completedAt`:

```typescript
// completeCall (service.ts:211)
session.retentionExpiresAt = new Date(Date.now() + COMPLETED_RETENTION_MS).toISOString();

// cancelCall (service.ts:238)
session.retentionExpiresAt = new Date(Date.now() + CANCELLED_RETENTION_MS).toISOString();
```

### Retention constants

| Constant | Value | Used in |
|---|---|---|
| `COMPLETED_RETENTION_MS` | 3,600,000 (1 hour) | `completeCall` |
| `CANCELLED_RETENTION_MS` | 300,000 (5 minutes) | `cancelCall` |

---

## `isExpired` Helper

```typescript
// service.ts:311-315
export function isExpired(session: VoiceCallSession, now?: number): boolean {
  if (!session.retentionExpiresAt) return false;
  const nowMs = now ?? Date.now();
  return new Date(session.retentionExpiresAt).getTime() <= nowMs;
}
```

Pure function. Returns `false` for sessions without `retentionExpiresAt` (non-terminal states). Optional `now` parameter for testability. No side effects.

---

## Sweep Architecture

```
SessionSweeper (voicebridge/sweeper.ts)
│
├── start()     → begins periodic sweep at configured interval
├── stop()      → stops the interval
│
└── sweep()     ← private, called on each tick
      │
      ├── getSessions()        → iterates all in-memory sessions
      ├── isExpired(session)   → checks retentionExpiresAt against now
      └── log each expired     → [SessionSweeper] dry-run: would be removed
```

**Dry-run behaviour:** The sweep identifies expired sessions and logs their `callId`, `status`, `completedAt`, and `retentionExpiresAt`. It does NOT delete, does NOT publish events, and does NOT modify session state.

**No sessions are touched.** The sweep is read-only observation.

---

## Files Created

| File | Lines | Purpose |
|---|---|---|
| `backend/src/voicebridge/sweeper.ts` | 53 | Background sweep service, dry-run log only |

## Files Modified

| File | Change |
|---|---|
| `backend/src/voicebridge/types.ts` | Added `retentionExpiresAt?: string` to `VoiceCallSession` |
| `backend/src/voicebridge/service.ts` | Added retention constants, set `retentionExpiresAt` in `completeCall`/`cancelCall`, exported `isExpired` helper |
| `backend/src/index.ts` | Imported `getSessions`, `isExpired`, `SessionSweeper`; created and started sweeper after routes; stopped in shutdown |

---

## Dependency Injection Flow

```
index.ts:main()
  │
  ├── registerRoutes(app)
  │
  ├── const sessionSweeper = new SessionSweeper({
  │     getSessions,       ← from service.ts
  │     isExpired,         ← from service.ts
  │     intervalMs: 5 min
  │   })
  │   sessionSweeper.start()
  │
  └── shutdown:
        sessionSweeper.stop()     ← stops interval, cancels all runs
        cleanupScheduler.shutdown()
        app.close()
        ...
```

---

## Validation Results

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| No sessions deleted | Yes — sweeper is read-only, never calls `sessions.delete()` |
| No cleanup executed | Yes — sweeper only logs, never invokes cleanup logic |
| No events published | Yes — sweeper does not import or call any publisher |
| No state modified | Yes — sweeper does not mutate session objects |
| No Event Bus changes | Yes — `event-bus/` untouched |
| No CleanupScheduler used | Yes — sweep interval is standalone `setInterval` |

---

## Regression Check

- `completeCall()` and `cancelCall()` behaviour unchanged — `retentionExpiresAt` is set but not read anywhere yet
- All existing HTTP API endpoints unaffected (sessions are still returned regardless of expiry)
- No existing code path checks `retentionExpiresAt` — zero behavioural impact
- Sweeper starts after routes register, stops before app closes

---

## Explicitly Not Implemented

- No session deletion
- No cleanup execution
- No events published (`call.deleted`, `call.expired`)
- No `CleanupScheduler` usage for retention
- No data store changes

Ready for Phase 3.2 when approved.
