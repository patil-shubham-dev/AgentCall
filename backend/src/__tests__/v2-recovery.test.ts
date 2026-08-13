import { describe, it, expect } from 'vitest';
import { InMemoryEventLogStore } from '../v2/event-log.js';
import { EventPlane } from '../v2/event-plane.js';
import { V2CallService } from '../v2/call-service.js';
import { IdempotencyStore } from '../v2/idempotency.js';
import { rehydrate, verifyCallLog, EventLogVerifier } from '../v2/recovery.js';
import { uuidV7 } from '../v2/ids.js';
import { V2_EVENTS } from '../v2/events.js';
import type { V2Event } from '../v2/events.js';

function makeEvent(
  callId: string,
  type: string,
  payload: Record<string, unknown> = {},
  occurredAt = new Date().toISOString(),
): Omit<V2Event, 'sequence'> {
  return {
    id: uuidV7(),
    type,
    version: 1,
    call_id: callId,
    correlation_id: callId,
    occurred_at: occurredAt,
    actor: { type: 'system' },
    payload,
  };
}

function makeService(log: InMemoryEventLogStore): { service: V2CallService; plane: EventPlane } {
  const plane = new EventPlane(log);
  return { service: new V2CallService(plane, new IdempotencyStore()), plane };
}

/** Drives a realistic call: create → answer → AI turn → user utterance. */
async function driveLifecycle(
  log: InMemoryEventLogStore,
): Promise<{ callId: string; before: ReturnType<V2CallService['getSnapshot']> }> {
  const { service } = makeService(log);
  const call = await service.createCall(
    { user_id: 'u1', agent_id: 'a1', policy: { silence_after_ms: 60_000 } },
    { type: 'ai', identity: 'a1' },
  );
  await service.answerCall(call.id, 'phone', { type: 'service' });
  await service.sendMessage(call.id, { content: 'Hello there' }, { type: 'ai', identity: 'a1' });
  await service.submitUtterance(call.id, { text: 'Hi back' }, { type: 'user', identity: 'u1' });
  return { callId: call.id, before: service.getSnapshot(call.id, { type: 'service' }) };
}

/** Asserts a successful rehydrate and narrows for the assertions that follow. */
async function mustRehydrate(
  log: InMemoryEventLogStore,
  callId: string,
): Promise<NonNullable<Awaited<ReturnType<typeof rehydrate>>>> {
  const recovered = await rehydrate(log, callId);
  expect(recovered).not.toBeNull();
  return recovered as NonNullable<Awaited<ReturnType<typeof rehydrate>>>;
}

describe('v2 recovery — rehydrate fold', () => {
  it('replays a full lifecycle into the pre-crash aggregate', async () => {
    const log = new InMemoryEventLogStore();
    const { callId, before } = await driveLifecycle(log);

    const { call, silence } = await mustRehydrate(log, callId);
    expect(call.state).toBe(before.state); // connected
    expect(call.connectedAt).toBe(before.connectedAt);
    expect(call.transcript.map((s) => [s.seq, s.role, s.text])).toEqual(
      before.transcript.map((s) => [s.seq, s.role, s.text]),
    );
    expect(call.transcriptSeq).toBe(before.transcriptSeq);
    expect(call.aiWaiting).toBe(before.aiWaiting); // lease: true after the AI turn, false after the human spoke — exactly the pre-crash truth
    expect(call.clientMessageIds.size).toBe(0); // in-memory only, by design
    expect(call.activeTurn).toBeNull();
    expect(silence).toBeNull(); // the human's utterance cleared the policy — matches pre-crash state
  });

  it('drops a crashed AI turn (message.started without completed)', async () => {
    const log = new InMemoryEventLogStore();
    const callId = 'crash-ai';
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CREATED, { user_id: 'u1', agent_id: 'a1' }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_RINGING));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_ANSWER_REQUESTED));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CONNECTED, { connected_at: new Date().toISOString() }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.MESSAGE_QUEUED, { message_id: 'm1', content: 'partial' }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.MESSAGE_STARTED, { message_id: 'm1' }));

    const { call, silence } = await mustRehydrate(log, callId);
    expect(call.state).toBe('connected');
    expect(call.transcript).toEqual([]); // no phantom transcript
    expect(call.aiWaiting).toBe(false);
    expect(call.activeTurn).toBeNull(); // dead stream, by design
    expect(silence).toBeNull(); // no completed turn to arm from
  });

  it('reconstructs an in-flight user utterance from speech.partial', async () => {
    const log = new InMemoryEventLogStore();
    const callId = 'crash-utterance';
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CREATED, { user_id: 'u1', agent_id: 'a1' }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_RINGING));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_ANSWER_REQUESTED));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CONNECTED, { connected_at: new Date().toISOString() }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.SPEECH_STARTED, { utterance_id: 'ut-1', speaker: 'user' }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.SPEECH_PARTIAL, { utterance_id: 'ut-1', text: 'hel', end_ms: 0 }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.SPEECH_PARTIAL, { utterance_id: 'ut-1', text: 'hello', end_ms: 0 }));

    const { call } = await mustRehydrate(log, callId);
    expect(call.openUtterance?.utterance_id).toBe('ut-1');
    expect(call.openUtterance?.text).toBe('hello');
    expect(call.activeTurn?.type).toBe('user');
    expect(call.transcript).toEqual([]); // not settled — nothing in the transcript
  });

  it('returns the silence re-arm budget from the last completed AI turn', async () => {
    const log = new InMemoryEventLogStore();
    const callId = 'silence-call';
    const completedAt = new Date(Date.now() - 4_000).toISOString();
    await log.append(
      callId,
      makeEvent(callId, V2_EVENTS.CALL_CREATED, {
        user_id: 'u1',
        agent_id: 'a1',
        policy: { silence_after_ms: 10_000 },
      }),
    );
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_RINGING));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_ANSWER_REQUESTED));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CONNECTED, { connected_at: new Date().toISOString() }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.MESSAGE_COMPLETED, { message_id: 'm1' }, completedAt));

    const { silence } = await mustRehydrate(log, callId);
    expect(silence).toEqual({ afterMs: 10_000, armedAt: Date.parse(completedAt) });
  });

  it('clears the silence candidate once the human speaks or hangs up', async () => {
    const log = new InMemoryEventLogStore();
    const callId = 'silence-cleared';
    const base = {
      user_id: 'u1',
      agent_id: 'a1',
      policy: { silence_after_ms: 10_000 },
    };
    const t = new Date().toISOString();
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CREATED, base));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CONNECTED, { connected_at: t }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.MESSAGE_COMPLETED, { message_id: 'm1' }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.SPEECH_STARTED, { utterance_id: 'ut-1', speaker: 'user' }));

    const { silence } = await mustRehydrate(log, callId);
    expect(silence).toBeNull();
  });

  it('returns null for archived calls, empty logs, and logs without call.created', async () => {
    const log = new InMemoryEventLogStore();
    const archived = 'archived-call';
    await log.append(archived, makeEvent(archived, V2_EVENTS.CALL_CREATED, { user_id: 'u1', agent_id: 'a1' }));
    await log.append(archived, makeEvent(archived, V2_EVENTS.CALL_ARCHIVED));
    expect(await rehydrate(log, archived)).toBeNull();

    expect(await rehydrate(log, 'unknown-call')).toBeNull();

    const noCreated = 'no-created';
    await log.append(noCreated, makeEvent(noCreated, V2_EVENTS.CALL_CONNECTED, { connected_at: new Date().toISOString() }));
    expect(await rehydrate(log, noCreated)).toBeNull();
  });

  it('tolerates FSM-invalid lifecycle events instead of failing boot', async () => {
    const log = new InMemoryEventLogStore();
    const callId = 'corrupt';
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CREATED, { user_id: 'u1', agent_id: 'a1' }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_RINGING));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_ANSWER_REQUESTED));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CONNECTED, { connected_at: new Date().toISOString() }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_RINGING));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_ANSWER_REQUESTED));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CONNECTED, { connected_at: new Date().toISOString() })); // invalid: already connected

    const { call } = await mustRehydrate(log, callId);
    expect(call.state).toBe('connected'); // invalid event skipped
  });

  it('skips malformed event payloads instead of failing boot', async () => {
    const log = new InMemoryEventLogStore();
    const callId = 'corrupt-payload';
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CREATED, { user_id: 'u1', agent_id: 'a1' }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_RINGING));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_ANSWER_REQUESTED));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CONNECTED, { connected_at: new Date().toISOString() }));
    // Malformed lease: `ai_wait_status.active` throws on null — the old fold
    // aborted the WHOLE rehydrate, taking every call on the instance down.
    await log.append(callId, makeEvent(callId, V2_EVENTS.TURN_LEASE, { ai_wait_status: null }));

    const { call } = await mustRehydrate(log, callId);
    expect(call.state).toBe('connected');
    expect(call.aiWaiting).toBe(false); // corrupt lease skipped — lease truth untouched
  });

  it('falls back to now when a completed-turn timestamp is unparseable', async () => {
    const log = new InMemoryEventLogStore();
    const callId = 'bad-timestamp';
    await log.append(
      callId,
      makeEvent(callId, V2_EVENTS.CALL_CREATED, {
        user_id: 'u1',
        agent_id: 'a1',
        policy: { silence_after_ms: 10_000 },
      }),
    );
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_RINGING));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CONNECTED, { connected_at: new Date().toISOString() }));
    // Garbage occurred_at: NaN armedAt would make the recovered silence timer
    // fire immediately (setTimeout(fn, NaN)) — a corrupt row must never do that.
    await log.append(callId, makeEvent(callId, V2_EVENTS.MESSAGE_COMPLETED, { message_id: 'm1' }, 'not-a-timestamp'));

    const { silence } = await mustRehydrate(log, callId);
    expect(silence).not.toBeNull();
    expect(Number.isNaN(silence?.armedAt)).toBe(false);
    expect(silence?.armedAt).toBeGreaterThanOrEqual(Date.now() - 5_000);
  });

  it('re-arms the recovered silence policy with the remaining budget', async () => {
    const log = new InMemoryEventLogStore();
    const callId = 'rearm';
    const completedAt = Date.now() - 40;
    await log.append(
      callId,
      makeEvent(callId, V2_EVENTS.CALL_CREATED, {
        user_id: 'u1',
        agent_id: 'a1',
        policy: { silence_after_ms: 50 },
      }),
    );
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_RINGING));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_ANSWER_REQUESTED));
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CONNECTED, { connected_at: new Date().toISOString() }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.MESSAGE_COMPLETED, { message_id: 'm1' }, new Date(completedAt).toISOString()));
    await log.append(callId, makeEvent(callId, V2_EVENTS.TURN_ENDED, { turn_type: 'ai', message_id: 'm1' }));
    await log.append(callId, makeEvent(callId, V2_EVENTS.TURN_LEASE, { ai_wait_status: { active: true, active_until: null, last_active_at: new Date().toISOString() } }));

    const { service, plane } = makeService(log);
    const recovered = await service.recoverCall(callId);
    expect(recovered).not.toBeNull();

    const detected: string[] = [];
    plane.subscribe(callId, (e) => detected.push(e.type));
    await new Promise((resolve) => setTimeout(resolve, 300));
    expect(detected).toContain(V2_EVENTS.SILENCE_DETECTED); // ~10 ms remaining — fires promptly
  });
});

describe('v2 recovery — service integration', () => {
  it('recoverAll restores every logged call into a fresh aggregate map', async () => {
    const log = new InMemoryEventLogStore();
    const { callId } = await driveLifecycle(log);

    const { service } = makeService(log);
    const report = await service.recoverAll();
    expect(report).toEqual({ recovered: 1, total: 1 });

    const restored = service.getSnapshot(callId, { type: 'service' });
    expect(restored.state).toBe('connected');
    expect(restored.transcript).toHaveLength(2);
  });
});

describe('v2 event log verifier', () => {
  it('flags gaps, duplicates, and non-contiguous sequences as corrupt', () => {
    const base = {
      version: 1,
      call_id: 'c',
      correlation_id: 'c',
      occurred_at: new Date().toISOString(),
      actor: { type: 'system' as const },
      payload: { user_id: 'u1', agent_id: 'a1' },
    };
    const e = (id: string, sequence: number): V2Event => ({ ...base, id, type: 'call.created', sequence });

    const clean = verifyCallLog('c', [e('a', 1), e('b', 2), e('c', 3)]);
    expect(clean.contiguous).toBe(true);
    expect(clean.corrupt).toBe(false);
    expect(clean.count).toBe(3);
    expect(clean.lastEventHash).toMatch(/^[0-9a-f]{64}$/);

    const gapped = verifyCallLog('c', [e('a', 1), e('c', 3)]);
    expect(gapped.contiguous).toBe(false);
    expect(gapped.corrupt).toBe(true);

    const duplicated = verifyCallLog('c', [e('a', 1), e('a', 2), e('b', 3)]);
    expect(duplicated.duplicateIds).toBe(1);
    expect(duplicated.corrupt).toBe(true);

    const reordered = verifyCallLog('c', [e('a', 2), e('b', 1)]);
    expect(reordered.contiguous).toBe(false);

    expect(verifyCallLog('c', []).corrupt).toBe(false);
  });

  it('reports per-call and total stats over a log', async () => {
    const log = new InMemoryEventLogStore();
    await log.append('c1', makeEvent('c1', 'call.created', { user_id: 'u1', agent_id: 'a1' }));
    await log.append('c1', makeEvent('c1', 'call.ringing'));
    await log.append('c2', makeEvent('c2', 'call.created', { user_id: 'u2', agent_id: 'a2' }));

    const report = await new EventLogVerifier().verify(log);
    expect(report.totalEvents).toBe(3);
    expect(report.corruptCalls).toBe(0);
    expect(report.calls.map((c) => [c.callId, c.count])).toEqual([
      ['c1', 2],
      ['c2', 1],
    ]);
  });

  it('verifier flags payloads that violate their registered schema', async () => {
    const log = new InMemoryEventLogStore();
    const callId = 'bad-payload';
    await log.append(callId, makeEvent(callId, V2_EVENTS.CALL_CREATED, { user_id: 'u1', agent_id: 'a1' }));
    // message.queued requires {message_id, content} — a wrong-shaped payload is
    // a data-quality signal even though the fold still processed the event.
    await log.append(callId, makeEvent(callId, V2_EVENTS.MESSAGE_QUEUED, { n: 1 }));

    const report = await new EventLogVerifier().verify(log);
    const callCheck = report.calls.find((c) => c.callId === callId);
    expect(callCheck?.payloadViolations).toBe(1);
    expect(callCheck?.corrupt).toBe(true);
    expect(report.payloadViolations).toBe(1);
    expect(report.corruptCalls).toBe(1);
  });
});
