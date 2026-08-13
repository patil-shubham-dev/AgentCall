import { describe, it, expect } from 'vitest';
import { InMemoryEventLogStore } from '../v2/event-log.js';
import { EventPlane } from '../v2/event-plane.js';
import { V2CallService } from '../v2/call-service.js';
import { IdempotencyStore } from '../v2/idempotency.js';
import { ForbiddenError, CallNotFoundError, ValidationError } from '../v2/errors.js';
import { InvalidTransitionError } from '../v2/call-fsm.js';
import { V2_EVENTS } from '../v2/events.js';
import type { V2Event } from '../v2/events.js';
import { ScriptedTtsProvider } from '../v2/providers.js';

const AI_ACTOR = { type: 'ai', identity: 'agent-01' } as const;
const USER_ACTOR = { type: 'user', identity: 'user_123' } as const;
const SERVICE_ACTOR = { type: 'service' } as const;

/** Indexed access without non-null assertions (lint: no-non-null-assertion). */
function must<T>(value: T | undefined, label: string): T {
  if (value === undefined) throw new Error(`missing ${label}`);
  return value;
}

function makeService(): { service: V2CallService; plane: EventPlane } {
  const log = new InMemoryEventLogStore();
  const plane = new EventPlane(log);
  const service = new V2CallService(plane, new IdempotencyStore());
  return { service, plane };
}

async function collectEvents(plane: EventPlane, callId: string, count: number): Promise<V2Event[]> {
  const events: V2Event[] = [];
  plane.subscribe(callId, (e) => events.push(e), { replay: 'all' });
  await new Promise((r) => setTimeout(r, 10));
  expect(events).toHaveLength(count);
  return events;
}

describe('v2 call service lifecycle', () => {
  it('creates a call emitting call.created + call.ringing and lands in ringing', async () => {
    const { service, plane } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01', summary: 'Need approval' }, AI_ACTOR);
    expect(call.state).toBe('ringing');
    expect(call.id).toMatch(/^[0-9a-f-]{36}$/);

    const events = await collectEvents(plane, call.id, 2);
    expect(must(events[0], 'created').type).toBe(V2_EVENTS.CALL_CREATED);
    expect(must(events[0], 'created').payload).toMatchObject({ user_id: 'user_123', agent_id: 'agent-01' });
    expect(must(events[0], 'created').actor).toEqual({ type: 'ai', identity: 'agent-01' });
    expect(must(events[1], 'ringing').type).toBe(V2_EVENTS.CALL_RINGING);
    expect(must(events[1], 'ringing').actor.type).toBe('system');
  });

  it('answers into connected (connecting is transient in M1)', async () => {
    const { service, plane } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    const answered = await service.answerCall(call.id, 'mobile', USER_ACTOR);
    expect(answered.state).toBe('connected');

    const events = await collectEvents(plane, call.id, 4);
    const types = events.map((e) => e.type);
    expect(types).toEqual([
      V2_EVENTS.CALL_CREATED,
      V2_EVENTS.CALL_RINGING,
      V2_EVENTS.CALL_ANSWER_REQUESTED,
      V2_EVENTS.CALL_CONNECTED,
    ]);
  });

  it('sendMessage emits queued → started → completed and appends an AI transcript segment', async () => {
    const { service, plane } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    await service.answerCall(call.id, undefined, USER_ACTOR);
    const { message_id } = await service.sendMessage(call.id, { content: 'Hello there' }, AI_ACTOR);

    const events = await collectEvents(plane, call.id, 10);
    const messageEvents = events.filter((e) => e.type.startsWith('message.'));
    expect(messageEvents.map((e) => e.type)).toEqual([
      V2_EVENTS.MESSAGE_QUEUED,
      V2_EVENTS.MESSAGE_STARTED,
      V2_EVENTS.MESSAGE_COMPLETED,
    ]);
    expect((must(messageEvents[0], 'queued').payload as { message_id: string }).message_id).toBe(message_id);

    // M2 lease: the AI finished its turn and is now waiting for the human.
    const lease = events.at(-1);
    expect(lease?.type).toBe(V2_EVENTS.TURN_LEASE);
    expect(lease?.payload).toMatchObject({ ai_wait_status: { active: true, active_until: null } });

    const transcript = service.getTranscript(call.id, AI_ACTOR);
    expect(transcript.at(-1)).toMatchObject({ role: 'ai', text: 'Hello there' });
  });

  it('submitUtterance emits speech.started/final + transcript + turn.ended, and is idempotent on client_message_id', async () => {
    const { service, plane } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    await service.answerCall(call.id, undefined, USER_ACTOR);

    const first = await service.submitUtterance(call.id, { text: 'yes please', client_message_id: 'cm-1' }, USER_ACTOR);
    const replay = await service.submitUtterance(call.id, { text: 'yes please', client_message_id: 'cm-1' }, USER_ACTOR);

    expect(first.idempotent).toBe(false);
    expect(replay).toMatchObject({ idempotent: true, utterance_id: first.utterance_id });

    const events = await collectEvents(plane, call.id, 8);
    const speechFinal = events.filter((e) => e.type === V2_EVENTS.SPEECH_FINAL);
    expect(speechFinal).toHaveLength(1); // duplicate utterance emitted nothing
    expect(events.some((e) => e.type === V2_EVENTS.TURN_ENDED)).toBe(true);

    const transcript = service.getTranscript(call.id, AI_ACTOR);
    expect(transcript).toHaveLength(1);
    expect(transcript[0]).toMatchObject({ role: 'user', type: 'speech', text: 'yes please', seq: 1 });
  });

  it('hangup ends the call with outcome and duration; re-hangup is an idempotent no-op', async () => {
    const { service, plane } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    await service.answerCall(call.id, undefined, USER_ACTOR);

    const ended = await service.hangupCall(call.id, { outcome: { decision: 'approved' }, note: 'approved on phone' }, AI_ACTOR);
    expect(ended.state).toBe('completed');
    expect(ended.result?.outcome).toEqual({ decision: 'approved' });

    // created, ringing, answer.requested, connected, transcript.updated (note),
    // ending, completed
    const events = await collectEvents(plane, call.id, 7);
    expect(must(events.at(-1), 'completed').type).toBe(V2_EVENTS.CALL_COMPLETED);
    expect(events.some((e) => e.type === V2_EVENTS.CALL_ENDING)).toBe(true);
    expect((must(events.at(-1), 'completed').payload as { duration_ms?: number }).duration_ms).toBeGreaterThanOrEqual(0);

    const again = await service.hangupCall(call.id, {}, AI_ACTOR);
    expect(again.state).toBe('completed');
  });

  it('records a hangup note as a user transcript segment', async () => {
    const { service } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    await service.hangupCall(call.id, { note: 'call me back later' }, USER_ACTOR);
    const transcript = service.getTranscript(call.id, AI_ACTOR);
    expect(transcript.at(-1)).toMatchObject({ role: 'user', text: 'call me back later' });
  });

  it('failCall moves any open call to failed', async () => {
    const { service } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    const failed = await service.failCall(call.id, 'provider_error', 'ERR', SERVICE_ACTOR);
    expect(failed.state).toBe('failed');
  });

  it('sweeps idle calls into the archive while keeping active ones (retention, not a cap)', async () => {
    const { service } = makeService();
    const active = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    const idle = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    // Simulate an abandoned call: no activity for an hour.
    service.getSnapshot(idle.id, SERVICE_ACTOR).lastActivityAt = new Date(Date.now() - 3_600_000).toISOString();

    const archived = await service.sweepIdleCalls(60_000);
    expect(archived).toBe(1);
    expect(() => service.getSnapshot(idle.id, SERVICE_ACTOR)).toThrow(CallNotFoundError);
    expect(service.getSnapshot(active.id, SERVICE_ACTOR).id).toBe(active.id);
  });
});

describe('v2 call service ownership', () => {
  it('rejects a different AI identity from reading or commanding the call', async () => {
    const { service } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    expect(() => service.getSnapshot(call.id, { type: 'ai', identity: 'agent-02' })).toThrow(ForbiddenError);
    await expect(service.sendMessage(call.id, { content: 'x' }, { type: 'ai', identity: 'agent-02' })).rejects.toThrow(ForbiddenError);
  });

  it('rejects a different user from answering', async () => {
    const { service } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    await expect(service.answerCall(call.id, 'mobile', { type: 'user', identity: 'user_999' })).rejects.toThrow(ForbiddenError);
  });

  it('allows the service role to do anything', async () => {
    const { service } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    const snapshot = service.getSnapshot(call.id, SERVICE_ACTOR);
    expect(snapshot.id).toBe(call.id);
  });

  it('rejects an AI creating a call it does not own', async () => {
    const { service } = makeService();
    await expect(
      service.createCall({ user_id: 'user_123', agent_id: 'agent-other' }, AI_ACTOR),
    ).rejects.toThrow(ForbiddenError);
  });

  it('throws CallNotFound for unknown calls', async () => {
    const { service } = makeService();
    expect(() => service.getSnapshot('nope', AI_ACTOR)).toThrow(CallNotFoundError);
    await expect(service.sendMessage('nope', { content: 'x' }, AI_ACTOR)).rejects.toThrow(CallNotFoundError);
  });
});

describe('v2 call service validation', () => {
  it('rejects empty content and text', async () => {
    const { service } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    await expect(service.sendMessage(call.id, { content: '  ' }, AI_ACTOR)).rejects.toThrow(ValidationError);
    await expect(service.submitUtterance(call.id, { text: '' }, USER_ACTOR)).rejects.toThrow(ValidationError);
  });

  it('rejects commands after the call is terminal', async () => {
    const { service } = makeService();
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    await service.hangupCall(call.id, {}, AI_ACTOR);
    await expect(service.sendMessage(call.id, { content: 'late' }, AI_ACTOR)).rejects.toThrow(InvalidTransitionError);
    await expect(service.submitUtterance(call.id, { text: 'late' }, USER_ACTOR)).rejects.toThrow(InvalidTransitionError);
  });
});

describe('v2 silence / noactivity escalation (R7)', () => {
  it('emits silence.detected after silence_after_ms and call.noactivity after escalations', async () => {
    const log = new InMemoryEventLogStore();
    const plane = new EventPlane(log);
    const service = new V2CallService(plane, new IdempotencyStore());
    const call = await service.createCall(
      { user_id: 'user_123', agent_id: 'agent-01', policy: { silence_after_ms: 20 } },
      AI_ACTOR,
    );
    await service.answerCall(call.id, undefined, USER_ACTOR);
    await service.sendMessage(call.id, { content: 'Are you there?' }, AI_ACTOR);

    const silence: V2Event[] = [];
    plane.subscribe(call.id, (e) => {
      if (e.type === V2_EVENTS.SILENCE_DETECTED || e.type === V2_EVENTS.CALL_NOACTIVITY) silence.push(e);
    }, { replay: 'all' });

    await new Promise((r) => setTimeout(r, 150));

    const detected = silence.filter((e) => e.type === V2_EVENTS.SILENCE_DETECTED);
    const noactivity = silence.filter((e) => e.type === V2_EVENTS.CALL_NOACTIVITY);
    expect(detected.length).toBeGreaterThanOrEqual(1);
    expect(noactivity.length).toBe(1);
    expect(must(noactivity[0], 'noactivity').payload).toMatchObject({ silence_count: 3 });

    service.dispose();
  });

  it('clears the silence policy on a user utterance and on hangup', async () => {
    const log = new InMemoryEventLogStore();
    const plane = new EventPlane(log);
    const service = new V2CallService(plane, new IdempotencyStore());
    const call = await service.createCall(
      { user_id: 'user_123', agent_id: 'agent-01', policy: { silence_after_ms: 20 } },
      AI_ACTOR,
    );
    await service.answerCall(call.id, undefined, USER_ACTOR);
    await service.sendMessage(call.id, { content: 'Are you there?' }, AI_ACTOR);
    await service.submitUtterance(call.id, { text: 'yes' }, USER_ACTOR);

    await new Promise((r) => setTimeout(r, 100));

    const silence: V2Event[] = [];
    plane.subscribe(call.id, (e) => {
      if (e.type === V2_EVENTS.SILENCE_DETECTED) silence.push(e);
    }, { replay: 'all' });
    expect(silence).toHaveLength(0); // user spoke — no silence events

    service.dispose();
  });

  it('speak() arms the silence policy once the streamed turn settles', async () => {
    const log = new InMemoryEventLogStore();
    const plane = new EventPlane(log);
    const service = new V2CallService(plane, new IdempotencyStore());
    const call = await service.createCall(
      { user_id: 'user_123', agent_id: 'agent-01', policy: { silence_after_ms: 20 } },
      AI_ACTOR,
    );
    await service.answerCall(call.id, undefined, USER_ACTOR);
    // Non-blocking say(): the policy must arm AFTER the turn settles, exactly
    // like sendMessage — otherwise silence.detected never escalates for
    // streamed AI messages.
    await service.speak(call.id, { content: 'Streamed message' }, AI_ACTOR);

    const silence: V2Event[] = [];
    plane.subscribe(call.id, (e) => {
      if (e.type === V2_EVENTS.SILENCE_DETECTED) silence.push(e);
    }, { replay: 'all' });

    await new Promise((r) => setTimeout(r, 100));
    expect(silence.length).toBeGreaterThanOrEqual(1);

    service.dispose();
  });

  it('sweeping an idle call hard-cuts its live TTS stream', async () => {
    const log = new InMemoryEventLogStore();
    const plane = new EventPlane(log);
    // Second token far in the future: the stream is deterministically still in
    // flight when the sweep runs (a short delay would race a loaded runner).
    const provider = new ScriptedTtsProvider([
      { text: 'first', audio_ms: 50, delay_ms: 0 },
      { text: 'second', audio_ms: 50, delay_ms: 10_000 },
    ]);
    const service = new V2CallService(plane, new IdempotencyStore(), provider);
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    await service.answerCall(call.id, undefined, USER_ACTOR);
    await service.speak(call.id, { content: 'still streaming' }, AI_ACTOR);

    const handle = must(service.getSnapshot(call.id, SERVICE_ACTOR).tts?.handle, 'tts handle');
    // Age by well beyond idleMs: the sweep's cutoff is now - idleMs, so aging
    // by exactly idleMs races the same millisecond and the call looks active.
    service.getSnapshot(call.id, SERVICE_ACTOR).lastActivityAt = new Date(Date.now() - 3_600_000).toISOString();

    const archived = await service.sweepIdleCalls(60_000);
    expect(archived).toBe(1);
    expect(handle.stopped).toBe(true); // hard-cut, not left streaming into the void

    service.dispose();
  });
});
