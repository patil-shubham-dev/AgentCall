# ADR-0012: SQLite Storage

**Status:** Superseded by ADR-0017
**Date:** 2026-07-26

---

## Context

The daemon needs persistent storage for sessions, messages, devices, agents,
and policies. The storage layer must be embedded (no separate server process),
zero-configuration, and reliable for a single-user workload.

The existing VoiceBridge v1 codebase uses PostgreSQL, which requires a separate
server process, connection pooling, and migration tooling. For the v2
local-first architecture, this is excessive.

## Decision

Use SQLite via the `better-sqlite3` Node.js binding as the sole storage engine.

- Single file database in `~/.local/share/agentcall/agentcall.db`
- WAL mode enabled for concurrent read/write
- Schema created automatically on first launch (no migration tool)
- Prepared statements for all queries
- No ORM — raw SQL wrapped in typed repository functions

## Alternatives Considered

### Alternative 1: PostgreSQL

Full-featured relational database with excellent concurrency.

**Rejected because:** Requires a separate server process, connection pooling,
user management, and migration tooling. A single-user daemon does not need
this complexity. Over-engineered for the workload.

### Alternative 2: JSON file storage

Store all data as JSON files on disk. Simple, no dependencies.

**Rejected because:** No query capability, no atomicity, no concurrency control.
Reading a session requires loading and parsing all sessions. Race conditions
on concurrent writes.

### Alternative 3: LevelDB / RocksDB

Embedded key-value store with excellent write performance.

**Rejected because:** No relational query capability. Session queries need
filtering by agent, status, and time range. Building this on top of a KV store
reimplements SQLite poorly.

### Alternative 4: LiteFS / Turso

Distributed SQLite with replication.

**Rejected because:** Adds complexity for a single-user daemon. If multi-user
is needed in the future, the schema is simple enough to migrate to Postgres.

## Consequences

### Positive

- Zero configuration — database is created on first launch
- No separate server process — embedded in the daemon
- WAL mode provides concurrent read/write without contention
- Schema is created atomically — no migration scripts
- Backup is a file copy

### Negative

- No built-in replication — single point of failure
- Write throughput limited to ~100k writes/second — fine for single-user
- SQLite file can grow unbounded without archiving
- Not suitable for multi-user workloads without redesign

### Neutral

- Schema changes require adding columns with `ALTER TABLE` or versioned
  migrations in future versions
- Database file location is configurable via `AGENTCALL_DB_PATH`

## Compliance

All storage must use SQLite via `better-sqlite3`. Pull requests introducing
other storage engines must include an ADR superseding this one.
