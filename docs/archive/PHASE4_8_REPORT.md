# Phase 4.8 — Configurable Read Source Switching

## Summary

`PERSISTENCE_MODE` env var controls which repository serves reads at startup. Four modes:
`memory`, `dual-write` (default), `database-read`, `database` (reserved). Only `index.ts`
chooses the implementation — business logic is unaware.

No in-memory repositories removed. PostgreSQL not made permanently authoritative.

---

## Switching Architecture

```
PERSISTENCE_MODE  │ Reads from  │ Writes to           │ DB required
──────────────────┼─────────────┼─────────────────────┼────────────
memory            │ Memory      │ Memory              │ No
dual-write (def)  │ Memory      │ Memory + Database   │ No (degrades to memory)
database-read     │ Database    │ Memory + Database   │ Yes
database          │ —           │ —                   │ — (startup error)
```

The `DualWriteSessionRepository` and `DualWriteCallbackRepository` now accept a
third constructor parameter `readFromDatabase: boolean` (default `false`):

```
DualWriteSessionRepository(memory, database, readFromDatabase?)
                            ↑        ↑
                         always the write targets (memory first, DB fire-and-forget)
              reads go here when readFromDatabase=false
                                         reads go here when readFromDatabase=true
```

- **Writes** always target memory (awaited) then database (fire-and-forget `.catch()`),
  regardless of mode.
- **Reads** (`findById`, `findByUserId`, `list`) delegate to `reader`, which is
  `this.database` when `readFromDatabase=true`, otherwise `this.memory`.
- **Phase A recovery** still runs in `dual-write` and `database-read` modes when
  `DATABASE_URL` is set — the in-memory repos are populated so writes remain
  consistent.

---

## Configuration

| Variable | Values | Default |
|---|---|---|
| `PERSISTENCE_MODE` | `memory`, `dual-write`, `database-read`, `database` | `dual-write` |

### `validateConfig()` checks (in order):

1. **Invalid value**: throws `Invalid PERSISTENCE_MODE: "xxx". Must be one of: memory, dual-write, database-read, database`
2. **database mode**: throws `PERSISTENCE_MODE=database is reserved for future use and cannot be selected yet`
3. **database-read without DATABASE_URL**: throws `PERSISTENCE_MODE=database-read requires DATABASE_URL to be set`

---

## Wiring (`index.ts`)

```
validateConfig() → creates InMemory repos → reads persistenceMode →
  ├── memory:          skip DB entirely, use in-memory repos directly
  ├── dual-write:      if DATABASE_URL → Phase A → DualWrite(readFromDb=false)
  │                    else → memory-only (degraded)
  ├── database-read:   if DATABASE_URL → Phase A → DualWrite(readFromDb=true)
  │                    else → startup error (caught in validateConfig)
  └── database:        startup error (caught in validateConfig)

Phase B (rebuildTimers) and post-recovery sweep only run when recoveryManager ≠ undefined
(only after Phase A ran).
```

---

## Validation Matrix

| Scenario | Mode | DATABASE_URL | Expected Behaviour |
|---|---|---|---|
| 1 | `memory` | not set | Pure in-memory. No pool, no dual-write, no verifier, no recovery. |
| 2 | `memory` | set | Pure in-memory. DB config ignored. |
| 3 | `dual-write` | not set | Pure in-memory (degraded). Log: "DATABASE_URL not set, running in memory-only mode". |
| 4 | `dual-write` | set | Full dual-write. Reads from memory. Writes to memory + DB. Recovery runs. |
| 5 | `database-read` | not set | Startup error in `validateConfig()`. |
| 6 | `database-read` | set | Reads from DB. Writes to memory + DB. Recovery runs. |
| 7 | `database` | any | Startup error: "reserved for future use". |
| 8 | `invalid-value` | any | Startup error: "Invalid PERSISTENCE_MODE". |

### Rollback from database-read → dual-write

Change `PERSISTENCE_MODE` from `database-read` to `dual-write`, restart.
Reads switch from DB back to memory. Writes unchanged. No data loss.
Phase A recovery repopulates memory from DB on next startup.

---

## Changes

### Modified: `backend/src/common/config.ts`

- Added `config.database.persistenceMode` (reads `PERSISTENCE_MODE`, defaults to `dual-write`)
- Exported `PersistenceMode` type
- Added validation in `validateConfig()` for invalid values, `database` mode, and `database-read` without `DATABASE_URL`

### Modified: `backend/src/voicebridge/repositories/dual-write-session-repository.ts`

- Renamed `primary` → `memory`, `secondary` → `database`
- Added `readFromDatabase: boolean = false` constructor parameter
- `reader` getter returns `this.database` when `readFromDatabase=true`, else `this.memory`
- All read methods (`findById`, `findByUserId`, `list`) use `this.reader`
- All write methods (`create`, `save`, `delete`) always use `this.memory` (awaited) + `this.database` (fire-and-forget)

### Modified: `backend/src/voicebridge/repositories/dual-write-callback-repository.ts`

- Same changes as session repo: renamed params, added `readFromDatabase`, `reader` getter

### Modified: `backend/src/index.ts`

- Read `persistenceMode` from config
- Replace `if (config.database.url)` with `if (persistenceMode === 'dual-write' || persistenceMode === 'database-read')`
- Pass `readFromDb = persistenceMode === 'database-read'` to both DualWrite constructors
- Log persistence mode at startup
- Phase B and post-recovery sweep unchanged (still guarded by `recoveryManager`)

### No changes to:

- `InMemorySessionRepository`, `InMemoryCallbackRepository` — untouched
- `DatabaseSessionRepository`, `DatabaseCallbackRepository` — untouched
- `SessionRepository`, `CallbackRepository` interfaces — untouched
- `RecoveryManager`, `PersistenceVerifier`, `PersistenceBurnIn` — untouched
- `VoiceBridgeService`, `LifecycleCoordinator`, `SessionSweeper`, all routes — untouched

---

## Validation Results

| Check | Result |
|---|---|
| `tsc --noEmit` (backend) | Pass |
| ESLint (backend) | Pass |
| Business logic unchanged | Confirmed — `service.ts`, `lifecycle-coordinator.ts`, `sweeper.ts`, `routes.ts`, all publishers unmodified |
| In-memory repos intact | Confirmed — no changes to `InMemory*Repository` |
| Database repos intact | Confirmed — no changes to `Database*Repository` |
| Repository interfaces intact | Confirmed — no changes to `SessionRepository` or `CallbackRepository` |
| Event Bus unchanged | None |
| No new dependencies | Confirmed |

---

## Regression Analysis

**Low risk.** Changes are contained to three files:

1. **`config.ts`**: Added field + validation. All existing env setups still work (default is `dual-write`, same as before).
2. **`dual-write-*-repository.ts`**: Renamed internal params, added `readFromDatabase` flag, reads go through `reader` getter. All callers updated in the same change set.
3. **`index.ts`**: Restructured the DB setup condition from `if (config.database.url)` to `if (mode === 'dual-write' || mode === 'database-read')`. The default mode `dual-write` with `DATABASE_URL` set behaves identically to before.

### Backward compatibility

- Existing `.env` files without `PERSISTENCE_MODE` default to `dual-write`, same behaviour as before Phase 4.8.
- `DualWrite*Repository` constructor remains backward-compatible — the third parameter defaults to `false`, so any test or tool constructing `new DualWriteSessionRepository(a, b)` still compiles.
- Phase 4.7 recovery, Phase 4.6 burn-in, Phase 4.5 verifier all unchanged.
