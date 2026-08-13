import { it, expect, beforeAll, afterAll } from 'vitest';
import { PostgresIdempotencyStore } from '../v2/db/pg-idempotency.js';
import { describeDb, makeTestPool, resetV2Db } from './helpers/v2-pg.js';
import type { Pool } from 'pg';

describeDb('v2 Postgres idempotency store', () => {
  let pool: Pool;

  beforeAll(async () => {
    pool = makeTestPool();
    await resetV2Db(pool);
  });

  afterAll(async () => {
    await pool.end();
  });

  it('stores and replays the first response', async () => {
    const store = new PostgresIdempotencyStore(pool);
    const key = store.key('identity-1', 'idem-1', 'call-1');

    expect(await store.get(key)).toBeUndefined();
    await store.put(key, 201, { call_id: 'call-1' });

    const stored = await store.get(key);
    expect(stored?.statusCode).toBe(201);
    expect(stored?.body).toEqual({ call_id: 'call-1' });
  });

  it('expires after the TTL', async () => {
    const store = new PostgresIdempotencyStore(pool, 50);
    const key = store.key('identity-1', 'short-lived', 'call-1');

    await store.put(key, 200, { ok: true });
    await new Promise((resolve) => setTimeout(resolve, 120));
    expect(await store.get(key)).toBeUndefined();
  });

  it('first-write-wins under concurrent duplicate puts', async () => {
    const store = new PostgresIdempotencyStore(pool);
    const key = store.key('identity-1', 'race', 'call-1');

    await Promise.all([
      store.put(key, 200, { winner: 'first' }),
      store.put(key, 500, { winner: 'second' }),
    ]);
    const stored = await store.get(key);
    expect(stored?.statusCode).toBe(200);
    expect(stored?.body).toEqual({ winner: 'first' });
  });

  it('sweep removes expired entries and size counts rows', async () => {
    const store = new PostgresIdempotencyStore(pool, 1_000_000);
    await pool.query('TRUNCATE v2_idempotency'); // self-contained: ignore rows left by sibling tests

    await store.put(store.key('identity-1', 'keep', 'call-1'), 200, {});
    const staleKey = store.key('identity-1', 'drop', 'call-1');
    await store.put(staleKey, 200, {});
    // Age exactly one row past the TTL the way time would (one store, one
    // TTL — a deployment never mixes TTLs on the shared table).
    await pool.query('UPDATE v2_idempotency SET stored_at = $1 WHERE key = $2', [
      Date.now() - 1_100_000, // past the 1,000,000 ms TTL; stored_at is BIGINT epoch millis
      staleKey,
    ]);

    expect(await store.sweep()).toBe(1);
    expect(await store.get(store.key('identity-1', 'keep', 'call-1'))).toBeDefined();
    expect(await store.size()).toBe(1);
  });

  it('composes keys identically to the in-memory store', async () => {
    const pgStore = new PostgresIdempotencyStore(pool);
    const { IdempotencyStore } = await import('../v2/idempotency.js');
    const memStore = new IdempotencyStore();

    const pg = pgStore.key('ident', 'key', 'call');
    const mem = memStore.key('ident', 'key', 'call');
    expect(pg).toBe(mem);
  });
});
