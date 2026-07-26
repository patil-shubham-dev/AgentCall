# Phase 4.4 — Dual Write Repository

## Summary

Wrapper repositories that write to both in-memory and PostgreSQL backends while reading exclusively from memory.

Business logic is unaware dual-write exists — it depends only on `SessionRepository` and `CallbackRepository` interfaces.

Database failures never affect runtime behaviour.

---

## Repository Wrapper Design

### `DualWriteSessionRepository` (`repositories/dual-write-session-repository.ts`)

```typescript
export class DualWriteSessionRepository implements SessionRepository {
  constructor(
    private primary: SessionRepository,    // InMemorySessionRepository
    private secondary: SessionRepository,  // DatabaseSessionRepository
  ) {}
  // ...
}
```

### `DualWriteCallbackRepository` (`repositories/dual-write-callback-repository.ts`)

```typescript
export class DualWriteCallbackRepository implements CallbackRepository {
  constructor(
    private primary: CallbackRepository,    // InMemoryCallbackRepository
    private secondary: CallbackRepository,  // DatabaseCallbackRepository
  ) {}
  // ...
}
```

Both wrappers accept the interface type, not concrete implementations. This keeps them generic — any future backend swap works without changing the wrapper.

---

## Read/Write Flow

### Read Path (all reads hit memory only)

```
caller → findById() / findByUserId() / list()
         │
         ▼
   DualWriteSessionRepository
         │
         ▼
   InMemorySessionRepository  ← NEVER queries PostgreSQL
         │
         ▼
   returns result
```

### Write Path (memory first, PostgreSQL fire-and-forget)

```
caller → create() / save() / delete()
         │
         ▼
   DualWriteSessionRepository
         │
         ├── 1. await primary.create/save/delete()     ← memory write, caller waits
         │
         ├── 2. secondary.create/save/delete().catch()  ← PostgreSQL write, fire-and-forget
         │         │
         │         └── on error: logger.error()          ← never propagates to caller
         │
         └── return (after memory write completes)
```

### Callback write path is identical

```
DualWriteCallbackRepository.save/delete()
  ├── await primary method
  └── secondary method .catch(logger.error)
```

---

## Failure Behaviour

### Database write failure

| Scenario | Behaviour |
|---|---|
| Database unreachable | Memory write succeeds immediately. DB `.catch()` logs error. Caller gets success. |
| Database timeout | Same — memory write is O(1) Map operation, returns instantly. DB error logged. |
| Database connection pool exhausted | Same — `.catch()` handles the error, caller never sees it. |
| Database schema mismatch | SQL query fails, error logged. Memory continues working. |

### Database read (not used in this phase)

All reads go exclusively to memory. The database's `findById`, `findByUserId`, and `list` methods are never called by the dual-write wrapper. They exist on `DatabaseSessionRepository` but are dead code in this phase.

### Database is fully absent

If `DATABASE_URL` is not configured, `config.database.url` is empty string, and `index.ts` skips creating the database pool and dual-write wrapper entirely. The application uses bare `InMemorySessionRepository` and `InMemoryCallbackRepository` — identical to Phase 4.2 behaviour.

---

## Wiring Decision (index.ts)

```typescript
const sessionRepo = new InMemorySessionRepository();
const callbackRepo = new InMemoryCallbackRepository();

let sessionRepository: SessionRepository = sessionRepo;
let callbackRepository: CallbackRepository = callbackRepo;

if (config.database.url) {
  const pool = new Pool({ connectionString: config.database.url });
  const dbSessionRepo = new DatabaseSessionRepository(pool);
  const dbCallbackRepo = new DatabaseCallbackRepository(pool);
  sessionRepository = new DualWriteSessionRepository(sessionRepo, dbSessionRepo);
  callbackRepository = new DualWriteCallbackRepository(callbackRepo, dbCallbackRepo);
}

// All consumers receive sessionRepository / callbackRepository
// — they have no idea which implementation is behind the interface
```

**Key points:**
- Pool is created inside `if (config.database.url)` — no connection attempted without a URL
- `DualWriteSessionRepository` wraps the concrete in-memory + database repos
- All downstream consumers (`VoiceBridgeService`, `LifecycleCoordinator`, `SessionSweeper`) use the interface-typed variables
- Shutdown calls `pool.end()` if the pool was created

---

## Dependency Graph

```
index.ts
  │
  ├── InMemorySessionRepository ─────┐
  ├── InMemoryCallbackRepository ────┤
  │                                  │
  ├── DatabaseSessionRepository  ────┤  (only if DATABASE_URL set)
  ├── DatabaseCallbackRepository ────┤
  │                                  ▼
  │                     DualWriteSessionRepository
  │                     DualWriteCallbackRepository
  │                                  │
  ├── VoiceBridgeService ────────────┤  (receives via interface)
  ├── LifecycleCoordinator ──────────┤
  ├── SessionSweeper ────────────────┤
  │                                  │
  └── Shutdown: pool.end() ──────────┘  (only if pool created)
```

---

## Validation Results

| Check | Result |
|---|---|
| ESLint (backend) | Pass |
| tsc --noEmit (backend) | Pass |
| ESLint (mcp-server) | Pass |
| Reads come from memory | Confirmed — dual-write delegates all read methods to `primary` |
| Writes reach both backends | Confirmed — write methods call `primary` then fire `secondary` |
| DB failures don't break requests | Confirmed — `secondary` calls wrapped in `.catch()` with `logger.error` |
| No DATABASE_URL = no DB connection | Confirmed — pool created only inside `if (config.database.url)` |
| In-memory repos still work standalone | Confirmed — default path without DATABASE_URL uses bare in-memory |
| Event Bus changes | None |
| Business logic changes | None — `service.ts`, `sweeper.ts`, `lifecycle-coordinator.ts`, `routes.ts` unmodified |

---

## Regression Analysis

**Low risk.** The only runtime-affected file is `index.ts`, where repo variable declarations change from `const sessionRepo` to `let sessionRepository` and conditionally wrap in dual-write.

All consumers receive the same interface-typed values they received before. When `DATABASE_URL` is not set, the behaviour is identical to Phase 4.2 — pure in-memory.

When `DATABASE_URL` is set:
- Reads are unchanged (memory)
- Writes add a fire-and-forget PostgreSQL call after the memory write completes
- If PostgreSQL is unreachable, the application continues working with memory — the catch handler logs and swallows the error
- Pool shutdown is added to the graceful shutdown sequence

---

## Files Created

```
backend/src/voicebridge/repositories/dual-write-session-repository.ts
backend/src/voicebridge/repositories/dual-write-callback-repository.ts
```

## Files Modified

```
backend/src/voicebridge/repositories/index.ts   — exports dual-write classes
backend/src/index.ts                            — conditional dual-write wiring + pool shutdown
```

---

## Remaining Work

| Phase | Description |
|---|---|
| **Phase 4.5** | Database Read Path — flip reads to PostgreSQL, keep memory as cache |
| **Phase 4.6** | Remove in-memory fallback — database is single source of truth |
| **Phase 4.7** | Startup recovery — load active sessions from DB on boot, rebuild CleanupScheduler timers |

In-memory repositories remain the source of truth. Database repositories are written to but never read from. No behavioural changes were introduced.
