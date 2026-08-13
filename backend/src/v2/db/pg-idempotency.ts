import type { Pool } from 'pg';
import { config } from '../../common/config.js';
import { composeIdempotencyKey } from '../idempotency.js';
import type { IdempotencyBackend, StoredIdempotentResponse } from '../idempotency.js';

/**
 * Durable idempotency store (roadmap M3): the same composite key and 24h TTL
 * as the in-memory store, persisted to `v2_idempotency` so HTTP replays keep
 * working across a worker restart (RPO 0 for the idempotency contract).
 *
 * put() is first-write-wins (ON CONFLICT DO NOTHING): under a concurrent
 * duplicate race the first stored response is the one replayed, which is the
 * idempotency guarantee the spec demands.
 */
export class PostgresIdempotencyStore implements IdempotencyBackend {
  constructor(
    private readonly pool: Pool,
    private readonly ttlMs: number = config.v2.idempotencyTtlMs,
  ) {}

  key(identity: string, idempotencyKey: string, callId?: string): string {
    return composeIdempotencyKey(identity, idempotencyKey, callId);
  }

  async get(key: string): Promise<StoredIdempotentResponse | undefined> {
    const cutoff = Date.now() - this.ttlMs;
    const result = await this.pool.query<{
      status_code: number;
      body: unknown;
      stored_at: string;
    }>(
      'SELECT status_code, body, stored_at FROM v2_idempotency WHERE key = $1 AND stored_at >= $2',
      [key, cutoff],
    );
    const row = result.rows[0];
    if (!row) return undefined;
    return { statusCode: row.status_code, body: row.body, storedAt: Number(row.stored_at) };
  }

  async put(key: string, statusCode: number, body: unknown): Promise<void> {
    await this.pool.query(
      `INSERT INTO v2_idempotency (key, status_code, body, stored_at)
       VALUES ($1, $2, $3::jsonb, $4)
       ON CONFLICT (key) DO NOTHING`,
      [key, statusCode, JSON.stringify(body), Date.now()],
    );
  }

  async sweep(): Promise<number> {
    const cutoff = Date.now() - this.ttlMs;
    const result = await this.pool.query('DELETE FROM v2_idempotency WHERE stored_at < $1', [
      cutoff,
    ]);
    return Number(result.rowCount ?? 0);
  }

  async size(): Promise<number> {
    const result = await this.pool.query<{ total: string }>(
      'SELECT COUNT(*) AS total FROM v2_idempotency',
    );
    return Number(result.rows[0]?.total ?? 0);
  }
}
