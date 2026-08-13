import { it, expect, beforeAll, afterAll } from 'vitest';
import { PostgresEventLogStore } from '../v2/db/pg-event-log.js';
import { uuidV7 } from '../v2/ids.js';
import type { V2Event } from '../v2/events.js';
import { describeDb, makeTestPool, resetV2Db } from './helpers/v2-pg.js';
import type { Pool } from 'pg';

function makeEvent(callId: string, type: string, payload: Record<string, unknown> = {}): Omit<V2Event, 'sequence'> {
  return {
    id: uuidV7(),
    type,
    version: 1,
    call_id: callId,
    correlation_id: uuidV7(), // envelope contract: UUID (TEXT call_id is the test artifact, not this)
    occurred_at: new Date().toISOString(),
    actor: { type: 'system' },
    payload,
  };
}

describeDb('v2 Postgres event log', () => {
  let pool: Pool;
  let log: PostgresEventLogStore;

  beforeAll(async () => {
    pool = makeTestPool();
    await resetV2Db(pool);
    log = new PostgresEventLogStore(pool);
  });

  afterAll(async () => {
    await pool.end();
  });

  it('assigns contiguous per-call sequences and round-trips the envelope', async () => {
    const a = await log.append('call-1', makeEvent('call-1', 'call.created', { user_id: 'u1' }));
    const b = await log.append('call-1', makeEvent('call-1', 'call.ringing'));
    expect([a.sequence, b.sequence]).toEqual([1, 2]);
    expect(a.type).toBe('call.created');
    expect(a.actor).toEqual({ type: 'system' });
    expect(a.payload).toEqual({ user_id: 'u1' });
    expect(a.occurred_at).toMatch(/^\d{4}-\d{2}-\d{2}T/); // ISO round-trip through TIMESTAMPTZ

    const other = await log.append('call-2', makeEvent('call-2', 'call.created'));
    expect(other.sequence).toBe(1); // per-call sequence space
  });

  it('keeps sequences contiguous under concurrent store-level appends', async () => {
    const callId = 'race-call';
    const events = await Promise.all(
      Array.from({ length: 50 }, (_, i) =>
        log.append(callId, makeEvent(callId, 'message.queued', { message_id: `m${i}` })),
      ),
    );
    expect(events.map((e) => e.sequence).sort((a, b) => a - b)).toEqual(
      Array.from({ length: 50 }, (_, i) => i + 1),
    );
    expect(await log.count(callId)).toBe(50);
  });

  it('resumes strictly after a cursor; unknown cursors replay the whole log', async () => {
    const callId = 'cursor-call';
    const first = await log.append(callId, makeEvent(callId, 'call.created'));
    const second = await log.append(callId, makeEvent(callId, 'call.ringing'));
    await log.append(callId, makeEvent(callId, 'call.connected'));

    const after = await log.after(callId, first.id);
    expect(after.map((e) => e.sequence)).toEqual([2, 3]);
    expect(after[0]?.id).toBe(second.id);

    const unknown = await log.after(callId, 'nope');
    expect(unknown.map((e) => e.sequence)).toEqual([1, 2, 3]);
  });

  it('lists in sequence order and answers exists/callIds', async () => {
    const callId = 'list-call';
    await log.append(callId, makeEvent(callId, 'call.created'));
    await log.append(callId, makeEvent(callId, 'call.connected'));

    const events = await log.list(callId);
    expect(events.map((e) => e.sequence)).toEqual([1, 2]);
    expect(await log.exists(callId)).toBe(true);
    expect(await log.exists('ghost-call')).toBe(false);
    expect(await log.count('ghost-call')).toBe(0);

    const callIds = await log.callIds();
    expect(callIds).toContain(callId);
  });
});
