import type { Pool } from 'pg';

/**
 * v2 durability schema (roadmap M3; docs/v2/05-database-schema.md §2).
 *
 * Deviation from the design doc, stated on purpose: tables are prefixed `v2_`
 * (the doc's `events`/`calls` projection is v1-namespaced and out of M3 scope)
 * and `v2_events` carries NO foreign key to a `calls` table — the v2 engine
 * keeps its call aggregates in memory and rebuilds them from this log at
 * recovery (event-sourcing-lite, roadmap R1). The FK would add integrity
 * without a target table, so it is deferred until the projection lands.
 *
 * All DDL here is additive (`IF NOT EXISTS`, no drops, no data mutation) —
 * safe to apply on every boot and via `npm run db:up`.
 */

export const V2_EVENT_LOG_SQL = `
CREATE TABLE IF NOT EXISTS v2_events (
  event_id       UUID        PRIMARY KEY,
  call_id        TEXT        NOT NULL,
  seq            BIGINT      NOT NULL,
  type           TEXT        NOT NULL,
  version        INTEGER     NOT NULL,
  correlation_id UUID        NOT NULL,
  causation_id   UUID,
  occurred_at    TIMESTAMPTZ NOT NULL,
  actor          JSONB       NOT NULL,
  payload        JSONB       NOT NULL,
  partition_key  TEXT,
  -- Per-call total order: contiguous 1..N; a gap or duplicate is corruption
  -- (the EventLogVerifier detects both).
  CONSTRAINT v2_events_call_seq UNIQUE (call_id, seq)
);

CREATE INDEX IF NOT EXISTS v2_events_call_id_seq_idx
  ON v2_events (call_id, seq);

CREATE TABLE IF NOT EXISTS v2_idempotency (
  key         TEXT   PRIMARY KEY,
  status_code INTEGER NOT NULL,
  body        JSONB  NOT NULL,
  stored_at   BIGINT NOT NULL
);

-- TTL sweep (stored_at < cutoff) full-scans without this; the table grows by
-- one row per idempotent command per TTL, so the sweep must stay index-backed.
CREATE INDEX IF NOT EXISTS v2_idempotency_stored_at_idx
  ON v2_idempotency (stored_at);
`;

/** Applies the v2 schema idempotently (safe to run on every boot). */
export async function applyV2Schema(pool: Pool): Promise<void> {
  await pool.query(V2_EVENT_LOG_SQL);
}
