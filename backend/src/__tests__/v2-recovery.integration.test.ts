import { it, expect, beforeAll, afterAll } from 'vitest';
import { PostgresEventLogStore } from '../v2/db/pg-event-log.js';
import { EventPlane } from '../v2/event-plane.js';
import { V2CallService } from '../v2/call-service.js';
import { IdempotencyStore } from '../v2/idempotency.js';
import { EventLogVerifier } from '../v2/recovery.js';
import { describeDb, makeTestPool, resetV2Db } from './helpers/v2-pg.js';
import type { Pool } from 'pg';

/**
 * M3 exit criteria (roadmap): worker-kill RTO < 5 s, RPO 0. Simulated kill:
 * service A runs a real conversation against the Postgres log, then a fresh
 * service B (as after a restart) recovers from the same log and must hold
 * exactly the state A had before it "died" — nothing settled is lost.
 */
describeDb('v2 recovery over Postgres (RPO 0 / RTO)', () => {
  let pool: Pool;

  beforeAll(async () => {
    pool = makeTestPool();
    await resetV2Db(pool);
  });

  afterAll(async () => {
    await pool.end();
  });

  function freshService(): { service: V2CallService; log: PostgresEventLogStore } {
    const log = new PostgresEventLogStore(pool);
    const plane = new EventPlane(log);
    return { service: new V2CallService(plane, new IdempotencyStore()), log };
  }

  it('restores a call exactly after a simulated worker kill (RPO 0)', async () => {
    const { service: before } = freshService();
    const call = await before.createCall(
      { user_id: 'u1', agent_id: 'a1', policy: { silence_after_ms: 60_000 }, priority: 'high' },
      { type: 'ai', identity: 'a1' },
    );
    await before.answerCall(call.id, 'phone', { type: 'service' });
    await before.sendMessage(call.id, { content: 'How can I help?' }, { type: 'ai', identity: 'a1' });
    await before.submitUtterance(call.id, { text: 'I need support' }, { type: 'user', identity: 'u1' });
    const preKill = before.getSnapshot(call.id, { type: 'service' });

    // "Restart": a brand-new service over the same durable log.
    const { service: after } = freshService();
    const report = await after.recoverAll();
    expect(report).toEqual({ recovered: 1, total: 1 });

    const restored = after.getSnapshot(call.id, { type: 'service' });
    expect(restored.state).toBe(preKill.state); // connected
    expect(restored.connectedAt).toBe(preKill.connectedAt);
    expect(restored.transcript.map((s) => [s.seq, s.role, s.text])).toEqual(
      preKill.transcript.map((s) => [s.seq, s.role, s.text]),
    );
    expect(restored.transcriptSeq).toBe(preKill.transcriptSeq);
    expect(restored.aiWaiting).toBe(preKill.aiWaiting); // lease: true after the AI turn, released by the user's utterance — exactly the pre-crash truth
    expect(restored.userId).toBe('u1');
    expect(restored.agentId).toBe('a1');
    expect(restored.priority).toBe('high');
  });

  it('recovers 300+ events well within the 5 s RTO budget', async () => {
    const { service: before } = freshService();
    const call = await before.createCall({ user_id: 'u1', agent_id: 'a1' }, { type: 'ai', identity: 'a1' });
    await before.answerCall(call.id, 'phone', { type: 'service' });
    for (let i = 0; i < 50; i++) {
      await before.sendMessage(call.id, { content: `turn ${i}` }, { type: 'ai', identity: 'a1' });
    }

    const { service: after } = freshService();
    const started = Date.now();
    const restoredCall = await after.recoverCall(call.id); // scoped to this call; recoverAll re-scans the whole table (which holds sibling tests' calls)
    const elapsed = Date.now() - started;

    expect(restoredCall).not.toBeNull();
    const restored = after.getSnapshot(call.id, { type: 'service' });
    expect(restored.transcript).toHaveLength(50);
    expect(elapsed).toBeLessThan(5_000); // RTO < 5 s
  });

  it('verifier reports a clean log; an injected gap is flagged as corruption', async () => {
    const { service, log } = freshService();
    const call = await service.createCall({ user_id: 'u1', agent_id: 'a1' }, { type: 'ai', identity: 'a1' });
    await service.answerCall(call.id, 'phone', { type: 'service' });

    const verifier = new EventLogVerifier();
    const clean = await verifier.verify(log);
    expect(clean.corruptCalls).toBe(0); // every call in the table — including sibling tests' — is contiguous
    expect(clean.totalEvents).toBeGreaterThanOrEqual(await log.count(call.id));

    // Simulate log corruption: delete a middle row (seq 2) so the remaining
    // 1,3,4,… has a real gap — a truncated tail would still be contiguous.
    await pool.query('DELETE FROM v2_events WHERE call_id = $1 AND seq = 2', [call.id]);
    const damaged = await verifier.verify(log);
    const callCheck = damaged.calls.find((c) => c.callId === call.id);
    expect(callCheck?.contiguous).toBe(false);
    expect(damaged.corruptCalls).toBe(1);
  });
});
