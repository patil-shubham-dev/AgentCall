# Phase 4.9 — Production Persistence Cutover

## Summary

PostgreSQL is now the primary persistence implementation when `PERSISTENCE_MODE=database`.
`PrimaryDatabaseSessionRepository` and `PrimaryDatabaseCallbackRepository` serve all reads
and writes directly from PostgreSQL. DualWrite is demoted to migration tooling for the
`dual-write` and `database-read` modes. In-memory repos are retained for tests, local
development, and fallback modes.

No business logic changes. No new repository interfaces.

---

## Production Architecture

```
PERSISTENCE_MODE=database
                        ┌───────────────────────────┐
                        │  PrimaryDatabaseSessionRepo │
                        │  PrimaryDatabaseCallbackRepo│
                        └──────┬────────────────────┘
                               │ delegates to
                        ┌──────▼────────────────────┐
                        │  DatabaseSessionRepository  │
                        │  DatabaseCallbackRepository │
                        └──────┬────────────────────┘
                               │ pg Pool
                        ┌──────▼────────────────────┐
                        │       PostgreSQL           │
                        └───────────────────────────┘

Memory is populated at startup for Phase B timer reconstruction only.
Runtime reads and writes bypass memory entirely.
```

## Runtime Wiring

```
PERSISTENCE_MODE  │ Repository Implementation         │ Read Source  │ Write Source        │ DB Required
──────────────────┼───────────────────────────────────┼──────────────┼─────────────────────┼────────────
memory            │ InMemory repos directly           │ Memory       │ Memory              │ No
dual-write        │ DualWrite repos                   │ Memory       │ Memory + DB (async) │ No (degrades)
database-read     │ DualWrite repos (readFromDb=true) │ DB           │ Memory + DB (async) │ Yes
database          │ PrimaryDatabase repos             │ DB           │ DB                  │ Yes
```

### `database` mode wiring in index.ts:

```
validateConfig() → create InMemory repos →
  │
  ├── PERSISTENCE_MODE=database:
  │     ├── require DATABASE_URL (startup error if missing)
  │     ├── create pg Pool
  │     ├── create DatabaseSessionRepository, DatabaseCallbackRepository
  │     ├── Phase A: RecoveryManager.loadFromDatabase() → populate memory for Phase B
  │     ├── sessionRepository = PrimaryDatabaseSessionRepository(dbSessionRepo)
  │     ├── callbackRepository = PrimaryDatabaseCallbackRepository(dbCallbackRepo)
  │     └── No verifier (memory diverges from DB — expected)
  │
  ├── Phase B: RecoveryManager.rebuildTimers() (uses memory repos populated by Phase A)
  └── Post-recovery sweep: sessionSweeper.sweep()
```

### Key properties of `database` mode:

- **Writes** go directly to PostgreSQL via `PrimaryDatabaseSessionRepository.save()` / `.create()` / `.delete()`.
  No dual-write, no memory write.
- **Reads** come directly from PostgreSQL via `PrimaryDatabaseSessionRepository.findById()` /
  `.findByUserId()` / `.list()`. No memory read.
- **Phase A recovery** still populates the in-memory repos from the database. This data is
  **not** used for runtime reads — it exists solely for Phase B timer reconstruction
  (`RecoveryManager.rebuildTimers()` iterates memory repos to rebuild `CleanupScheduler` timers
  for callbacks and pause-ttl expiry).
- **Phase B** runs after `LifecycleCoordinator` creation, using the same `RecoveryManager`
  logic. When timers fire, `LifecycleCoordinator` reads session state from the
  session repository (now `PrimaryDatabase*` → DB).
- **No `PersistenceVerifier`** — comparing in-memory state (stale snapshot from startup)
  against the live database (current source of truth) would produce meaningless mismatches.
- **Post-recovery sweep** still runs via `sessionSweeper.sweep()`, which reads from the
  primary (DB) repository.
- **Startup recovery** (`loadFromDatabase` → `rebuildTimers` → `sweep`) works identically
  to other modes. Recovery Manager is unchanged.

---

## PrimaryDatabase* Implementation

### `PrimaryDatabaseSessionRepository` (`primary-db-session-repository.ts`)

- Implements `SessionRepository`
- Wraps `DatabaseSessionRepository` — all methods delegate to the underlying
  `DatabaseSessionRepository` instance
- Adds `logger.debug()` calls for each operation (production observability at DEBUG level)
- Methods: `findById`, `findByUserId`, `list`, `create`, `save`, `delete`

### `PrimaryDatabaseCallbackRepository` (`primary-db-callback-repository.ts`)

- Implements `CallbackRepository`
- Wraps `DatabaseCallbackRepository` — all methods delegate to the underlying
  `DatabaseCallbackRepository` instance
- Adds `logger.debug()` calls for each operation
- Methods: `findByUserId`, `save`, `delete`, `list`

Both classes are thin delegation layers. They do not introduce new interfaces or
data-access logic. The delegation pattern provides a seam for adding production-specific
behavior (metrics, distributed tracing, connection health checks) without modifying
the shared `Database*Repository` implementations.

---

## Rollback Strategy

### Rollback from `database` to `dual-write`:

1. Set `PERSISTENCE_MODE=dual-write`
2. Restart
3. Reads go back to memory, writes go to both memory and DB
4. Phase A recovery repopulates memory from DB on next startup
5. Verifier resumes comparing memory vs DB

### Rollback from `database` to `memory`:

1. Set `PERSISTENCE_MODE=memory`
2. Optionally unset `DATABASE_URL` to avoid unused pool
3. Restart
4. Pure in-memory — no DB reads, no DB writes
5. Data in PostgreSQL remains as a backup snapshot

No data migration required for any rollback — all data lives in PostgreSQL during
`database` mode (no dual-write divergence).

---

## Validation Matrix

| Scenario | Mode | DATABASE_URL | Expected Behaviour |
|---|---|---|---|
| 1 | `memory` | not set | Pure in-memory. No pool, no DB access. |
| 2 | `memory` | set | Pure in-memory. DB config ignored. |
| 3 | `dual-write` | not set | Memory-only (degraded). Log: "running in memory-only mode". |
| 4 | `dual-write` | set | Dual-write. Reads from memory. Writes to memory + DB. Recovery runs. |
| 5 | `database-read` | not set | Startup error. |
| 6 | `database-read` | set | Reads from DB. Writes to memory + DB. Recovery runs. |
| 7 | `database` | not set | Startup error. |
| 8 | `database` | set | Pure DB. Reads from DB. Writes to DB. Phase A + Phase B run. No verifier. |
| 9 | `invalid-value` | any | Startup error. |

### All scenarios verify:

| Component | Behaviour in `database` mode |
|---|---|
| Startup recovery | Phase A loads DB → memory. Phase B rebuilds timers. Same code path. |
| Restart | Full reload from DB. No data loss. |
| Callback recovery | Timer rebuilt by Phase B. On fire, reads session from DB. |
| SessionSweeper | `sweep()` reads sessions from DB, deletes expired via DB. |
| LifecycleCoordinator | All `findById`/`findByUserId` go to DB (through PrimaryDatabase*). |
| PersistenceVerifier | Not wired (memory != DB in production mode — expected). |
| PersistenceBurnIn | Standalone tool. Not wired at runtime. Still available for manual validation. |

---

## Changes

### New files:

```
backend/src/voicebridge/repositories/primary-db-session-repository.ts
backend/src/voicebridge/repositories/primary-db-callback-repository.ts
```

### Modified files:

**`backend/src/common/config.ts`**:
- `database` mode is now allowed (no longer throws "reserved" error)
- Both `database` and `database-read` modes now require `DATABASE_URL` with a dynamic error message

**`backend/src/voicebridge/repositories/index.ts`**:
- Exports `PrimaryDatabaseSessionRepository` and `PrimaryDatabaseCallbackRepository`

**`backend/src/index.ts`**:
- Imports `PrimaryDatabaseSessionRepository` and `PrimaryDatabaseCallbackRepository`
- New `if (persistenceMode === 'database')` block before existing `dual-write`/`database-read` block:
  - Requires `DATABASE_URL`, creates Pool, creates `Database*Repository` instances
  - Phase A recovery populates in-memory repos
  - Wraps DB repos in `PrimaryDatabase*Repository` for runtime
  - No `PersistenceVerifier` (memory != DB in this mode)
- Existing `dual-write`/`database-read`/`memory` modes unchanged

### No changes to:

- `InMemorySessionRepository`, `InMemoryCallbackRepository`
- `DatabaseSessionRepository`, `DatabaseCallbackRepository`
- `DualWriteSessionRepository`, `DualWriteCallbackRepository`
- `SessionRepository`, `CallbackRepository` interfaces
- `RecoveryManager`, `PersistenceVerifier`, `PersistenceBurnIn`
- `VoiceBridgeService`, `LifecycleCoordinator`, `SessionSweeper`, `DeletionCoordinator`
- All routes, publishers, signaling — untouched

---

## Validation Results

| Check | Result |
|---|---|
| `tsc --noEmit` (backend) | Pass |
| ESLint (backend) | Pass |
| Business logic unchanged | Confirmed — `service.ts`, `lifecycle-coordinator.ts`, `sweeper.ts`, `routes.ts`, all publishers unmodified |
| In-memory repos intact | Confirmed — no changes to `InMemory*Repository` |
| Database repos intact | Confirmed — no changes to `Database*Repository` |
| DualWrite repos intact | Confirmed — no changes to `DualWrite*Repository` |
| Repository interfaces intact | Confirmed — no changes to `SessionRepository` or `CallbackRepository` |
| Event Bus unchanged | None |
| No new dependencies | Confirmed |

---

## Regression Analysis

**Low risk.** Changes are additive and configuration-gated:

1. **`primary-db-*-repository.ts`** — new files, unreferenced by existing code.
2. **`config.ts`** — removed the "reserved" guard. All existing env setups that do NOT
   set `PERSISTENCE_MODE=database` are unaffected (default is `dual-write`).
3. **`index.ts`** — added a new `if (persistenceMode === 'database')` block. Existing
   `else if` branches are structurally identical to before. The default `dual-write` mode
   behaviour is unchanged.

The `database` mode is opt-in: no existing deployment will switch to it without explicitly
setting `PERSISTENCE_MODE=database` and providing `DATABASE_URL`.
