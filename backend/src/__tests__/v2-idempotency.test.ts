import { describe, it, expect } from 'vitest';
import { IdempotencyStore } from '../v2/idempotency.js';

describe('v2 idempotency store (in-memory)', () => {
  it('evicts the oldest entries beyond the hard cap', async () => {
    const store = new IdempotencyStore(60_000, 3);
    await store.put('k1', 200, { a: 1 });
    await store.put('k2', 200, { a: 2 });
    await store.put('k3', 200, { a: 3 });
    await store.put('k4', 200, { a: 4 });

    expect(await store.size()).toBe(3);
    // k1 was inserted first: it must go first (Map insertion order).
    expect(await store.get('k1')).toBeUndefined();
    expect(await store.get('k4')).toMatchObject({ statusCode: 200, body: { a: 4 } });
  });

  it('drops entries once their TTL has elapsed', async () => {
    // Negative TTL: every entry is already expired the moment it is stored.
    const store = new IdempotencyStore(-1, 100);
    await store.put('k1', 200, { a: 1 });
    expect(await store.get('k1')).toBeUndefined();
    expect(await store.size()).toBe(0); // get() self-sweeps the expired key
  });
});