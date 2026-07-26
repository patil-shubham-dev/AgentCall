# Database Review — RC-1

## Schema

### Sessions Table

```sql
CREATE TABLE sessions (
  id                  TEXT PRIMARY KEY,
  user_id             TEXT NOT NULL,
  status              TEXT NOT NULL,
  data                JSONB NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  connected_at        TIMESTAMPTZ,
  completed_at        TIMESTAMPTZ,
  paused_at           TIMESTAMPTZ,
  resumed_at          TIMESTAMPTZ,
  retention_expires_at TIMESTAMPTZ
);
```

### Callbacks Table

```sql
CREATE TABLE callbacks (
  user_id   TEXT PRIMARY KEY,
  call_id   TEXT NOT NULL,
  resume_at BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

## Indexes

```sql
CREATE INDEX idx_sessions_user_id ON sessions(user_id);
CREATE INDEX idx_sessions_status ON sessions(status);
CREATE INDEX idx_sessions_retention_expires ON sessions(retention_expires_at)
  WHERE retention_expires_at IS NOT NULL;
```

**Gaps:**

1. **No index on `created_at`** — `findByUserId` uses `ORDER BY created_at DESC`. Without an index, PostgreSQL does a sort for every user query. With growth, this becomes a sequential scan + sort.
   - **Severity:** MEDIUM — affects performance at scale
   - **Recommendation:** `CREATE INDEX idx_sessions_created_at ON sessions(created_at DESC)`

2. **No compound index for user queries** — `SELECT * FROM sessions WHERE user_id = $1 ORDER BY created_at DESC` could use a compound index `(user_id, created_at DESC)` to avoid sorting
   - **Severity:** LOW — the single-column index is acceptable for moderate scale

3. **No foreign key from callbacks to sessions** — `callbacks.call_id` references `sessions.id` implicitly but no FK constraint. This means orphan callback records can exist.
   - **Severity:** LOW — application-level consistency is maintained
   - **Recommendation:** Add FK with `ON DELETE CASCADE`

## Queries

### Parameterized Queries

All queries use `$1`, `$2` parameterized statements. SQL injection is not possible.

### `ON CONFLICT DO UPDATE`

The `save()` in `DatabaseSessionRepository` uses:

```sql
INSERT INTO sessions (...) VALUES (...)
ON CONFLICT (id) DO UPDATE SET ...
```

This is an upsert. **Issue:** The SET clause re-writes every column, including `data` (JSONB). If two concurrent requests update the same session, the second one overwrites the first's `messages` array. This is the lost update problem.

### No Transactions

Each repository method is a single query. There are no multi-statement transactions. This means:

- `scheduleCallback()`: updates session status + saves callback in two separate queries with no transaction
- `completeCall()`: reads session, modifies in memory, saves session + deletes callback in two separate queries
- If the callback delete fails after the session save, the callback is orphaned

**Severity:** MEDIUM — inconsistent state if partial failure occurs

## Constraints

- No CHECK constraints on `status` column (any string is valid)
- No CHECK constraints on `resume_at` (negative values possible)
- No `NOT NULL` on relational columns that are supposed to always exist

## Vacuum

- JSONB columns (`data`) are updated frequently (every message push, status change)
- This creates dead tuples
- autovacuum must be configured to handle update-heavy workload
- No specific autovacuum tuning documented

## Connection Pool

Current config:
```typescript
pool = new Pool({
  min: 2,
  max: 10,
  idleTimeoutMillis: 30000,
});
```

**Issue: `poolAcquireTimeoutMs` is configured but never passed to the Pool constructor:**

```typescript
// config.ts — configured:
poolAcquireTimeoutMs: parseIntSafe('DB_POOL_ACQUIRE_TIMEOUT', '10000')

// index.ts — NOT passed to Pool:
pool = new Pool({
  connectionString: config.database.url,
  min: config.database.poolMin,
  max: config.database.poolMax,
  idleTimeoutMillis: config.database.poolIdleTimeoutMs,
  // missing: connectionTimeoutMillis: config.database.poolAcquireTimeoutMs,
});
```

- **Severity:** MEDIUM — pool can wait indefinitely for a connection
- **Recommendation:** Add `connectionTimeoutMillis: config.database.poolAcquireTimeoutMs`

## Statement Timeout

No `statement_timeout` set on the pool. A slow query could block a connection indefinitely.

## Prepared Statements

The `pg` driver does not use server-side prepared statements by default. Each query is parsed, planned, and executed fresh. For repeated queries like `SELECT * FROM sessions WHERE id = $1`, this adds overhead.

## Migration Strategy

- `schema.sql` is a reference file only
- No migration tool (Knex, node-pg-migrate, etc.)
- No migration history tracking
- No rollback scripts
- Schema changes require manual SQL execution

**Severity:** MEDIUM-HIGH for production — any schema change requires downtime or manual scripting

## Score

**Database: 5/10**

Deducted for: missing `connectionTimeoutMillis`, no `statement_timeout`, no transactions for multi-step operations, no migration tooling, lost update problem on concurrent writes, missing compound index for user queries, no FK constraint on callbacks, no CHECK constraints on status.
