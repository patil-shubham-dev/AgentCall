# Phase 4.3 — Database Repository Implementation

## Summary

PostgreSQL-backed repository implementations were created behind the existing repository interfaces. The application continues to use in-memory repositories exclusively. Database repositories compile but are **not wired into the runtime**.

| Repo | In-Memory (active) | Database (compiled, unused) |
|---|---|---|
| `SessionRepository` | `InMemorySessionRepository` | `DatabaseSessionRepository` |
| `CallbackRepository` | `InMemoryCallbackRepository` | `DatabaseCallbackRepository` |

---

## Repository Implementations

### `DatabaseSessionRepository` (`repositories/db-session-repository.ts`)

- Backed by `pg.Pool` (node-postgres)
- Sessions stored in a `sessions` table using **JSONB** for the full `VoiceCallSession` object
- Queried columns (`id`, `user_id`, `status`, `created_at`, `connected_at`, `completed_at`, `paused_at`, `resumed_at`, `retention_expires_at`) stored as relational indexed columns
- Implements all 6 methods: `findById`, `findByUserId`, `list`, `create`, `save`, `delete`
- `save()` uses `INSERT ... ON CONFLICT (id) DO UPDATE SET` (upsert) pattern

### `DatabaseCallbackRepository` (`repositories/db-callback-repository.ts`)

- Backed by `pg.Pool`
- Callbacks stored in a `callbacks` table with columns `user_id` (PK), `call_id`, `resume_at`
- Implements all 3 methods: `findByUserId`, `save`, `delete`
- `save()` uses `INSERT ... ON CONFLICT (user_id) DO UPDATE SET` (upsert) pattern

---

## Interface Extension

### `SessionRepository` gains `save()`

```typescript
// Added to SessionRepository interface
save(session: VoiceCallSession): Promise<void>;
```

Both `InMemorySessionRepository` and `DatabaseSessionRepository` implement it. The in-memory version delegates to `Map.set()` (identical to `create`). The database version uses upsert.

This is the only interface change from Phase 4.2.

---

## Database Mapping Strategy

### Session mapping

```
VoiceCallSession (domain model)          sessions table (PostgreSQL)
───────────────────────────────           ──────────────────────────
id: string                     ─────►    id TEXT PRIMARY KEY
userId: string                 ─────►    user_id TEXT (indexed)
status: CallStatus             ─────►    status TEXT (indexed)
createdAt: string              ─────►    created_at TIMESTAMPTZ (indexed)
connectedAt?: string           ─────►    connected_at TIMESTAMPTZ
completedAt?: string           ─────►    completed_at TIMESTAMPTZ
pausedAt?: string              ─────►    paused_at TIMESTAMPTZ
resumedAt?: string             ─────►    resumed_at TIMESTAMPTZ
retentionExpiresAt?: string    ─────►    retention_expires_at TIMESTAMPTZ
(all other fields)             ─────►    data JSONB (entire object serialized)
```

`rowToSession()`: Extracts `data` JSONB + indexed columns, merges into `VoiceCallSession`.
`sessionToRow()`: Splits `VoiceCallSession` into indexed columns + serialized `data` JSONB.

### Callback mapping

```
CallbackData (domain)          callbacks table (PostgreSQL)
────────────────────           ──────────────────────────
callId: string      ─────►    call_id TEXT
resumeAt: number    ─────►    resume_at BIGINT
(userId from arg)   ─────►    user_id TEXT PRIMARY KEY
```

`rowToData()`: Maps `call_id` / `resume_at` back to `CallbackData`.

---

## Dependency Graph

```
index.ts (startup)
  │
  ├── imports InMemorySessionRepository    ← still active
  ├── imports InMemoryCallbackRepository   ← still active
  ├── creates VoiceBridgeService(repos)    ← still uses in-memory
  └── (Database repos exist but are NOT imported or instantiated)

repositories/index.ts
  ├── exports InMemorySessionRepository    ← wired
  ├── exports InMemoryCallbackRepository   ← wired
  ├── exports DatabaseSessionRepository    ← available but unwired
  └── exports DatabaseCallbackRepository   ← available but unwired
```

---

## New Dependencies

| Package | Version | Purpose |
|---|---|---|
| `pg` | latest | PostgreSQL driver (node-postgres) |
| `@types/pg` | latest | TypeScript type definitions for `pg` |

---

## Configuration

### `config.ts` additions

```typescript
database: {
  url: env('DATABASE_URL', ''),                    // PostgreSQL connection string
  poolMin: parseIntSafe('DB_POOL_MIN', '2'),        // Minimum pool connections
  poolMax: parseIntSafe('DB_POOL_MAX', '10'),       // Maximum pool connections
  poolAcquireTimeoutMs: parseIntSafe(...),           // Connection acquire timeout
  poolIdleTimeoutMs: parseIntSafe(...),              // Idle connection timeout
}
```

### `.env.example` additions

```
DATABASE_URL=postgresql://user:password@ep-example-123456.us-east-2.aws.neon.tech/neondb?sslmode=require
DB_POOL_MIN=2
DB_POOL_MAX=10
DB_POOL_ACQUIRE_TIMEOUT=10000
DB_POOL_IDLE_TIMEOUT=30000
```

`DATABASE_URL` defaults to empty string — no connection is attempted unless explicitly configured.

---

## SQL Schema

A reference schema file is at `repositories/schema.sql`:

```sql
CREATE TABLE IF NOT EXISTS sessions (
  id                   TEXT PRIMARY KEY,
  user_id              TEXT NOT NULL,
  status               TEXT NOT NULL,
  data                 JSONB NOT NULL,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  connected_at         TIMESTAMPTZ,
  completed_at         TIMESTAMPTZ,
  paused_at            TIMESTAMPTZ,
  resumed_at           TIMESTAMPTZ,
  retention_expires_at TIMESTAMPTZ
);
-- Indexes on user_id, status, retention_expires_at

CREATE TABLE IF NOT EXISTS callbacks (
  user_id    TEXT PRIMARY KEY,
  call_id    TEXT NOT NULL,
  resume_at  BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

This is a reference only — no migration system is wired. The domain model is authoritative; the schema is derived from it.

---

## Error Handling Strategy

All database errors are caught and wrapped in `RepositoryError`:

```typescript
export class RepositoryError extends Error {
  constructor(message: string, public cause?: unknown) {
    super(message);
    this.name = 'RepositoryError';
  }
}
```

Each repository method wraps its query in `try/catch`:

```typescript
try {
  const result = await this.pool.query(...);
  return result.rows[0] ? rowToSession(result.rows[0]) : undefined;
} catch (cause) {
  throw new RepositoryError(`Failed to find session by id: ${callId}`, cause);
}
```

Raw `pg` errors are never exposed to business logic. The original error is preserved in `RepositoryError.cause` for debugging.

---

## Validation Results

| Check | Result |
|---|---|
| ESLint (backend) | Pass |
| tsc --noEmit (backend) | Pass |
| ESLint (mcp-server) | Pass |
| In-memory repos still source of truth | Confirmed — `index.ts` imports only `InMemory*` |
| Database repos compile | Confirmed — both `Database*` classes satisfy interfaces |
| Business logic changes | None — `service.ts`, `lifecycle-coordinator.ts`, `sweeper.ts`, `routes.ts` unmodified |
| Event Bus changes | None |

---

## Regression Analysis

**Low risk.** The only changes to existing code are:

1. **`SessionRepository` interface** — `save()` added. All implementations (`InMemorySessionRepository`, `DatabaseSessionRepository`) implement it. No consumer calls `save()` yet — the application uses `create()` for new sessions and mutates in-place for updates.

2. **`config.ts`** — `database` config block added with defaulted values. No code reads `config.database` yet (DB repos are not wired). No startup impact.

3. **`repositories/index.ts`** — Two new exports added. No existing import is affected.

4. **`package.json`** — `pg` and `@types/pg` added as dependencies. They are installed but the codebase does not import them from any module that runs at startup.

5. **New files** — All self-contained: `db-session-repository.ts`, `db-callback-repository.ts`, `errors.ts`, `schema.sql`.

No behavioural change at runtime. The in-memory repositories remain the singular source of truth.

---

## Remaining Work (Future Phases)

| Phase | Description |
|---|---|
| **Phase 4.4** | Dual-write repository — wraps in-memory + database, writes to both, reads from memory |
| **Phase 4.5** | Flip read path to database — database becomes source of truth, memory becomes cache |
| **Phase 4.6** | Remove in-memory fallback — database is single source of truth |
| **Phase 4.7** | Startup recovery — load active sessions from DB on boot, rebuild CleanupScheduler timers |

---

## Files Created

```
backend/src/voicebridge/repositories/db-session-repository.ts
backend/src/voicebridge/repositories/db-callback-repository.ts
backend/src/voicebridge/repositories/errors.ts
backend/src/voicebridge/repositories/schema.sql
```

## Files Modified

```
backend/src/voicebridge/repositories/session-repository.ts   — added save() to interface + InMemorySessionRepository
backend/src/voicebridge/repositories/index.ts                — exports DatabaseSessionRepository, DatabaseCallbackRepository
backend/src/common/config.ts                                 — added database config block
backend/.env.example                                         — added DATABASE_URL + pool settings
backend/package.json                                         — added pg, @types/pg
```
