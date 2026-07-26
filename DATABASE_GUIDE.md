# VoiceBridge — Database Guide

> **Canonical reference:** [ARCHITECTURE_BASELINE.md](./ARCHITECTURE_BASELINE.md)

---

## Technology

- **Primary:** PostgreSQL 16
- **Client:** `pg` (node-postgres) via `pg.Pool`
- **Schema:** Defined in `backend/src/voicebridge/repositories/schema.sql`
- **No ORM:** Raw parameterized SQL queries

---

## Schema

Defined in `backend/src/voicebridge/repositories/schema.sql`. Two tables:

### sessions

| Column | Type | Constraints |
|--------|------|-------------|
| id | UUID | PRIMARY KEY |
| user_id | TEXT | NOT NULL |
| agent_id | TEXT | NOT NULL |
| status | TEXT | NOT NULL |
| priority | TEXT | |
| reason | TEXT | |
| context | JSONB | |
| messages | JSONB | |
| result | JSONB | |
| created_at | TIMESTAMPTZ | DEFAULT NOW() |
| connected_at | TIMESTAMPTZ | |
| paused_at | TIMESTAMPTZ | |
| completed_at | TIMESTAMPTZ | |
| retention_expires_at | TIMESTAMPTZ | |

### callbacks

| Column | Type | Constraints |
|--------|------|-------------|
| user_id | TEXT | PRIMARY KEY |
| call_id | UUID | NOT NULL |
| resume_at | BIGINT | NOT NULL |

---

## Repository Pattern

```typescript
interface SessionRepository {
  findById(id: string): Promise<VoiceCallSession | undefined>;
  findByUserId(userId: string): Promise<VoiceCallSession[]>;
  create(session: VoiceCallSession): Promise<void>;
  save(session: VoiceCallSession): Promise<void>;
  delete(id: string): Promise<VoiceCallSession | undefined>;
  list(): Promise<VoiceCallSession[]>;
  transaction<T>(fn: (repo: SessionRepository) => Promise<T>): Promise<T>;
}

interface CallbackRepository {
  findById(userId: string): Promise<CallbackRecord | undefined>;
  save(userId: string, callback: CallbackRecord): Promise<void>;
  delete(userId: string): Promise<void>;
  list(): Promise<CallbackRecord[]>;
  transaction<T>(fn: (repo: CallbackRepository) => Promise<T>): Promise<T>;
}
```

---

## Implementations

| Implementation | File | Use |
|---------------|------|-----|
| `InMemorySessionRepository` | `inmemory-session-repository.ts` | Memory mode, Phase B timer rebuild |
| `DatabaseSessionRepository` | `db-session-repository.ts` | Direct DB access (pg.Pool) |
| `DualWriteSessionRepository` | `dual-write-session-repository.ts` | Writes to both, reads from memory |
| `PrimaryDatabaseSessionRepository` | `primary-db-session-repository.ts` | Reads + writes DB only |
| `InstrumentedSessionRepository` | `instrumented-session-repository.ts` | Timing + retry + slow-query wrapper |

---

## Connection Management

- Connection pool via `pg.Pool`
- Configurable via env vars: `DB_POOL_MIN`, `DB_POOL_MAX`, `DB_POOL_IDLE_TIMEOUT`, `DB_POOL_ACQUIRE_TIMEOUT`
- Queries use parameterized binding (`$1`, `$2`) — no string interpolation
- Transactions via `BEGIN`/`COMMIT`/`ROLLBACK` on shared `PoolClient`

### Pool Configuration (recommended production)

| Parameter | Value | Notes |
|-----------|-------|-------|
| min | 5 | Prevent cold-start latency |
| max | 50 | Support 500+ req/s |
| acquireTimeoutMillis | 10000 | Timeout waiting for connection |
| idleTimeoutMillis | 30000 | Close idle connections after 30s |

---

## Persistence Modes

| Mode | Reads | Writes | DB Required |
|------|-------|--------|-------------|
| `memory` | InMemory | InMemory | No |
| `dual-write` | InMemory | InMemory + DB | No |
| `database-read` | DB | InMemory + DB | Yes |
| `database` | DB | DB | Yes |

---

## Setup

```bash
# Apply schema before first deployment
psql $DATABASE_URL -f backend/src/voicebridge/repositories/schema.sql
```

No migration tooling is currently implemented. Schema changes must be applied manually. See `TECHNICAL_DEBT_REGISTER_v1.md` (TD-13) for planned migration tooling.
