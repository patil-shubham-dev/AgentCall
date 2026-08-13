import type { Pool } from 'pg';
import type { EventLogStore } from '../event-log.js';
import type { V2Event } from '../events.js';

/**
 * Postgres-backed event log (roadmap M3) implementing the same EventLogStore
 * surface the engine already speaks — the M1 in-memory store swaps for this
 * without touching the engine (event-log.ts doc comment).
 *
 * Per-call `seq` is assigned in the INSERT itself (`MAX(seq)+1`), so sequence
 * allocation is atomic per row: under a unique-constraint race (two workers,
 * roadmap M5) the loser retries once. The outbox write path (EventPlane) keeps
 * append order deterministic within a process; this store additionally
 * tolerates true cross-process concurrency.
 */

interface EventRow {
  event_id: string;
  call_id: string;
  seq: string;
  type: string;
  version: number;
  correlation_id: string;
  causation_id: string | null;
  occurred_at: string | Date;
  actor: unknown;
  payload: unknown;
}

function toEvent(row: EventRow): V2Event {
  const occurredAt =
    row.occurred_at instanceof Date ? row.occurred_at.toISOString() : row.occurred_at;
  return {
    id: row.event_id,
    type: row.type,
    version: row.version,
    call_id: row.call_id,
    correlation_id: row.correlation_id,
    ...(row.causation_id ? { causation_id: row.causation_id } : {}),
    occurred_at: occurredAt,
    sequence: Number(row.seq),
    actor: row.actor as V2Event['actor'],
    payload: row.payload as Record<string, unknown>,
  };
}

/**
 * Sequence assignment is serialized per call with a transaction-scoped
 * advisory lock, so concurrent appends (multi-worker, M5) never race on
 * MAX(seq)+1 — deterministic, no retry storms. An explicit transaction is
 * required: a CTE lock alone is not enough because the INSERT's statement
 * snapshot is taken before the lock is acquired, so MAX(seq) could read
 * stale data. With BEGIN…lock…INSERT…COMMIT the INSERT gets a fresh
 * snapshot after the lock, which is the whole point.
 */
const INSERT_SQL = `
INSERT INTO v2_events (
  event_id, call_id, seq, type, version, correlation_id, causation_id,
  occurred_at, actor, payload, partition_key
)
SELECT $2, $1,
       (SELECT COALESCE(MAX(seq), 0) + 1 FROM v2_events WHERE call_id = $1),
       $3, $4, $5, $6, $7, $8::jsonb, $9::jsonb, $10
RETURNING *`;

const LIST_SQL = `SELECT * FROM v2_events WHERE call_id = $1 ORDER BY seq`;
const AFTER_SQL = `
SELECT * FROM v2_events
WHERE call_id = $1
  AND seq > COALESCE((SELECT seq FROM v2_events WHERE event_id::text = $2), 0)
ORDER BY seq`;
const EXISTS_SQL = `SELECT EXISTS(SELECT 1 FROM v2_events WHERE call_id = $1) AS present`;
const COUNT_SQL = `SELECT COUNT(*) AS total FROM v2_events WHERE call_id = $1`;
const CALL_IDS_SQL = `SELECT DISTINCT call_id FROM v2_events ORDER BY call_id`;

export class PostgresEventLogStore implements EventLogStore {
  constructor(private readonly pool: Pool) {}

  async append(callId: string, event: Omit<V2Event, 'sequence'>): Promise<V2Event> {
    // BEGIN → advisory lock → INSERT → COMMIT: the lock serializes this
    // call's seq allocation and the INSERT reads MAX on a fresh snapshot.
    const client = await this.pool.connect();
    try {
      await client.query('BEGIN');
      await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [callId]);
      const result = await client.query<EventRow>(INSERT_SQL, [
        callId,
        event.id,
        event.type,
        event.version,
        event.correlation_id,
        event.causation_id ?? null,
        event.occurred_at,
        JSON.stringify(event.actor),
        JSON.stringify(event.payload),
        event.call_id, // partition_key: same value as call_id in M3
      ]);
      await client.query('COMMIT');
      return toEvent(result.rows[0] as EventRow);
    } catch (err) {
      await client.query('ROLLBACK').catch(() => undefined);
      const code = (err as { code?: string })?.code;
      if (code === '23505') {
        throw new Error(
          `event log seq race for call ${callId} survived the advisory lock (event ${event.id})`,
        );
      }
      throw err;
    } finally {
      client.release();
    }
  }

  async list(callId: string): Promise<V2Event[]> {
    const result = await this.pool.query<EventRow>(LIST_SQL, [callId]);
    return result.rows.map(toEvent);
  }

  async after(callId: string, eventId: string): Promise<V2Event[]> {
    // Unknown cursor resolves to `seq > 0` — the whole log, matching the
    // in-memory store's at-least-once replay contract (consumers dedupe).
    const result = await this.pool.query<EventRow>(AFTER_SQL, [callId, eventId]);
    return result.rows.map(toEvent);
  }

  async exists(callId: string): Promise<boolean> {
    const result = await this.pool.query<{ present: boolean }>(EXISTS_SQL, [callId]);
    return result.rows[0]?.present ?? false;
  }

  async count(callId: string): Promise<number> {
    const result = await this.pool.query<{ total: string }>(COUNT_SQL, [callId]);
    return Number(result.rows[0]?.total ?? 0);
  }

  async callIds(): Promise<string[]> {
    const result = await this.pool.query<{ call_id: string }>(CALL_IDS_SQL);
    return result.rows.map((r) => r.call_id);
  }

  async ping(): Promise<void> {
    await this.pool.query('SELECT 1');
  }
}

