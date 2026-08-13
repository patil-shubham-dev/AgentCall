import { describe, it, expect } from 'vitest';
import { InMemoryEventLogStore } from '../v2/event-log.js';
import { EventPlane } from '../v2/event-plane.js';
import { V2CallService } from '../v2/call-service.js';
import { IdempotencyStore } from '../v2/idempotency.js';
import { ScriptedTtsProvider } from '../v2/providers.js';
import { V2_EVENTS } from '../v2/events.js';
import type { V2Event } from '../v2/events.js';

const AI_ACTOR = { type: 'ai', identity: 'agent-01' } as const;
const USER_ACTOR = { type: 'user', identity: 'user_123' } as const;

/** Indexed access without non-null assertions (lint: no-non-null-assertion). */
function must<T>(value: T | undefined, label: string): T {
  if (value === undefined) throw new Error(`missing ${label}`);
  return value;
}

function makeService(tts: ScriptedTtsProvider): { service: V2CallService; plane: EventPlane } {
  const log = new InMemoryEventLogStore();
  const plane = new EventPlane(log);
  const service = new V2CallService(plane, new IdempotencyStore(), tts);
  return { service, plane };
}

async function makeAnsweredCall(
  service: V2CallService,
): Promise<{ callId: string; events: V2Event[]; waitFor: (type: string) => Promise<void>; release: Promise<void> }> {
  const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
  await service.answerCall(call.id, 'mobile', USER_ACTOR);
  const events: V2Event[] = [];
  let releaseResolve!: () => void;
  const release = new Promise<void>((resolve) => {
    releaseResolve = resolve;
  });
  const waiters: Array<{ type: string; resolve: () => void }> = [];
  service.plane.subscribe(
    call.id,
    (e) => {
      events.push(e);
      for (let i = waiters.length - 1; i >= 0; i--) {
        const waiter = waiters[i];
        if (waiter?.type === e.type) {
          waiter.resolve();
          waiters.splice(i, 1);
        }
      }
      if (e.type === V2_EVENTS.MESSAGE_COMPLETED || e.type === V2_EVENTS.CALL_COMPLETED) releaseResolve();
    },
    { replay: 'all' },
  );
  const waitFor = (type: string): Promise<void> =>
    new Promise((resolve) => {
      waiters.push({ type, resolve });
    });
  return { callId: call.id, events, waitFor, release };
}

/** Deterministic mid-stream helper: first token instant, rest delayed. */
const STREAMY_TOKENS = [
  { text: 'Hello ', audio_ms: 500, delay_ms: 0 },
  { text: 'there', audio_ms: 500, delay_ms: 60 },
];

describe('v2 realtime — streaming AI speech (roadmap M2)', () => {
  it('sendMessage streams through the provider: queued → started → completed with provider stats', async () => {
    const provider = new ScriptedTtsProvider([
      { text: 'Hello ', audio_ms: 300, delay_ms: 0 },
      { text: 'world', audio_ms: 300, delay_ms: 10 },
    ]);
    const { service, plane } = makeService(provider);
    const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
    await service.answerCall(call.id, undefined, USER_ACTOR);

    const { message_id } = await service.sendMessage(call.id, { content: 'Hello world' }, AI_ACTOR);

    const events: V2Event[] = [];
    plane.subscribe(call.id, (e) => events.push(e), { replay: 'all' });
    await new Promise((r) => setTimeout(r, 10));

    const types = events.map((e) => e.type);
    expect(types).toEqual([
      V2_EVENTS.CALL_CREATED,
      V2_EVENTS.CALL_RINGING,
      V2_EVENTS.CALL_ANSWER_REQUESTED,
      V2_EVENTS.CALL_CONNECTED,
      V2_EVENTS.MESSAGE_QUEUED,
      V2_EVENTS.MESSAGE_STARTED,
      V2_EVENTS.MESSAGE_COMPLETED,
      V2_EVENTS.TRANSCRIPT_UPDATED,
      V2_EVENTS.TURN_ENDED,
      V2_EVENTS.TURN_LEASE,
    ]);
    const started = must(events.find((e) => e.type === V2_EVENTS.MESSAGE_STARTED), 'started');
    expect(started.payload).toMatchObject({ message_id, tts_provider: 'scripted', streamed: true });
    const completed = must(events.find((e) => e.type === V2_EVENTS.MESSAGE_COMPLETED), 'completed');
    expect(completed.payload).toMatchObject({
      message_id,
      chars_spoken: 11,
      audio_bytes: Math.round((600 / 1000) * 16_000 * 2), // 600 ms of 16 kHz PCM16
    });
  });

  it('speak returns immediately; the stream completes in the background', async () => {
    const provider = new ScriptedTtsProvider(STREAMY_TOKENS);
    const { service } = makeService(provider);
    const { callId, events, waitFor } = await makeAnsweredCall(service);

    const t0 = performance.now();
    const { message_id } = await service.speak(callId, { content: 'Hello there' }, AI_ACTOR);
    const speakLatencyMs = performance.now() - t0;
    expect(speakLatencyMs).toBeLessThan(50); // queued + return, no stream wait

    // The lease is the LAST event of a completed turn — waiting on it (with a
    // guard timeout) means the whole turn's events are durable when we assert.
    await Promise.race([
      waitFor(V2_EVENTS.TURN_LEASE),
      new Promise((_resolve, reject) => setTimeout(() => reject(new Error('turn never completed')), 2000)),
    ]);
    expect(events.map((e) => e.type)).toContain(V2_EVENTS.MESSAGE_COMPLETED);
    expect(events.map((e) => e.type)).toContain(V2_EVENTS.TURN_ENDED);
    expect(events.map((e) => e.type)).toContain(V2_EVENTS.TURN_LEASE);
    const lease = events.find((e) => e.type === V2_EVENTS.TURN_LEASE);
    expect(lease?.payload).toMatchObject({ ai_wait_status: { active: true } });
    expect(must(events.find((e) => e.type === V2_EVENTS.MESSAGE_QUEUED), 'queued').payload).toMatchObject({
      message_id,
    });
  });

  it('snapshot exposes the live active_turn while the AI is streaming', async () => {
    const provider = new ScriptedTtsProvider(STREAMY_TOKENS);
    const { service } = makeService(provider);
    const { callId } = await makeAnsweredCall(service);

    await service.speak(callId, { content: 'Hello there' }, AI_ACTOR);
    const midStream = service.getSnapshot(callId, AI_ACTOR);
    expect(midStream.activeTurn).toMatchObject({ type: 'ai', message_id: expect.any(String) });
    expect(midStream.tts).not.toBeNull();
  });

  it('ai_stop cuts the stream: turn.cancelled + message.failed, lease released', async () => {
    const provider = new ScriptedTtsProvider(STREAMY_TOKENS);
    const { service } = makeService(provider);
    const { callId, events } = await makeAnsweredCall(service);

    const { message_id } = await service.speak(callId, { content: 'Hello there' }, AI_ACTOR);
    const result = await service.stopSpeaking(callId, AI_ACTOR);
    expect(result).toMatchObject({ message_id, stopped: true });

    const types = events.map((e) => e.type);
    expect(types).toContain(V2_EVENTS.MESSAGE_STARTED);
    expect(types).toContain(V2_EVENTS.TURN_CANCELLED);
    expect(types).toContain(V2_EVENTS.MESSAGE_FAILED);
    expect(types).not.toContain(V2_EVENTS.MESSAGE_COMPLETED);
    const cancelled = must(events.find((e) => e.type === V2_EVENTS.TURN_CANCELLED), 'cancelled');
    expect(cancelled.payload).toMatchObject({ turn_type: 'ai', message_id, reason: 'ai_stop' });
    const failed = must(events.find((e) => e.type === V2_EVENTS.MESSAGE_FAILED), 'failed');
    expect(failed.payload).toMatchObject({ message_id, reason: 'ai_stop', partial_audio_ms: expect.any(Number) });
    // The turn never completed → the AI never entered the waiting lease; no
    // turn.lease may be fabricated for a cut that never took the floor.
    expect(events.filter((e) => e.type === V2_EVENTS.TURN_LEASE)).toHaveLength(0);

    const again = await service.stopSpeaking(callId, AI_ACTOR);
    expect(again.stopped).toBe(false); // idempotent no-op
  });

  it('provider failure lands as message.failed + turn.cancelled(tts_error), never completed', async () => {
    const provider = new ScriptedTtsProvider([{ text: 'boom', audio_ms: 100, delay_ms: 0 }], {
      reason: 'provider_error',
    });
    const { service } = makeService(provider);
    const { callId, events } = await makeAnsweredCall(service);

    // sendMessage awaits stream settlement — by the time it resolves, the
    // failure events are durably in the log (no live wait needed).
    await service.sendMessage(callId, { content: 'boom' }, AI_ACTOR);

    const types = events.map((e) => e.type);
    expect(types).toContain(V2_EVENTS.MESSAGE_FAILED);
    expect(types).toContain(V2_EVENTS.TURN_CANCELLED);
    expect(types).not.toContain(V2_EVENTS.MESSAGE_COMPLETED);
    const failed = must(events.find((e) => e.type === V2_EVENTS.MESSAGE_FAILED), 'failed');
    expect(failed.payload).toMatchObject({ reason: 'provider_error' });
    const cancelled = must(events.find((e) => e.type === V2_EVENTS.TURN_CANCELLED), 'cancelled');
    expect(cancelled.payload).toMatchObject({ reason: 'tts_error' });
  });
});

describe('v2 realtime — barge-in (roadmap M2 exit: p95 ≤ 50 ms)', () => {
  it('first partial cuts the AI turn synchronously with a measured p95 under 50 ms', async () => {
    const provider = new ScriptedTtsProvider(STREAMY_TOKENS);
    const { service } = makeService(provider);

    const latencies: number[] = [];
    for (let i = 0; i < 20; i++) {
      const call = await service.createCall({ user_id: 'user_123', agent_id: 'agent-01' }, AI_ACTOR);
      await service.answerCall(call.id, undefined, USER_ACTOR);
      await service.speak(call.id, { content: 'Hello there' }, AI_ACTOR);

      const t0 = performance.now();
      await service.submitUtterancePartial(call.id, { text: 'act', finalize: true }, USER_ACTOR);
      latencies.push(performance.now() - t0);
    }
    const sorted = [...latencies].sort((a, b) => a - b);
    const p95 = sorted[Math.floor(sorted.length * 0.95) - 1] ?? 0;
    expect(p95).toBeLessThan(50);
  });

  it('barge-in emits user.interrupted then turn.cancelled(barge_in) before speech.started', async () => {
    const provider = new ScriptedTtsProvider(STREAMY_TOKENS);
    const { service } = makeService(provider);
    const { callId, events } = await makeAnsweredCall(service);

    await service.speak(callId, { content: 'Hello there' }, AI_ACTOR);
    await service.submitUtterancePartial(callId, { text: 'actually', finalize: true, client_message_id: 'cm-b1' }, USER_ACTOR);

    const types = events.map((e) => e.type);
    expect(types).toContain(V2_EVENTS.USER_INTERRUPTED);
    expect(types).toContain(V2_EVENTS.TURN_CANCELLED);
    expect(types).not.toContain(V2_EVENTS.MESSAGE_COMPLETED);
    expect(types).not.toContain(V2_EVENTS.MESSAGE_FAILED);

    const interruptedIdx = types.indexOf(V2_EVENTS.USER_INTERRUPTED);
    const cancelledIdx = types.indexOf(V2_EVENTS.TURN_CANCELLED);
    const startedIdx = types.indexOf(V2_EVENTS.SPEECH_STARTED);
    expect(interruptedIdx).toBeGreaterThanOrEqual(0);
    expect(cancelledIdx).toBeGreaterThan(interruptedIdx);
    expect(startedIdx).toBeGreaterThan(cancelledIdx);

    const interrupted = must(events[interruptedIdx], 'interrupted');
    expect(interrupted.payload).toMatchObject({
      interrupted_message_id: expect.any(String),
      interrupted_audio_ms: expect.any(Number),
    });
    const cancelled = must(events[cancelledIdx], 'cancelled');
    expect(cancelled.payload).toMatchObject({ turn_type: 'ai', reason: 'barge_in' });

    // Mid-stream cut: the AI never completed a turn, so no lease transition —
    // the interrupted message is closed by turn.cancelled alone.
    expect(events.filter((e) => e.type === V2_EVENTS.TURN_LEASE)).toHaveLength(0);

    // Replay of the finalized utterance is idempotent.
    const replay = await service.submitUtterancePartial(
      callId,
      { text: 'actually', finalize: true, client_message_id: 'cm-b1' },
      USER_ACTOR,
    );
    expect(replay.idempotent).toBe(true);
  });

  it('TtsHandle.stop is the budget: measured stop latency p95 ≤ 50 ms at the seam', async () => {
    const provider = new ScriptedTtsProvider(STREAMY_TOKENS);
    const latencies: number[] = [];
    for (let i = 0; i < 20; i++) {
      const handle = provider.speak(
        { messageId: `m-${i}`, content: 'Hello there' },
        { onStarted: () => undefined, onToken: () => undefined, onDone: () => undefined, onError: () => undefined },
      );
      const t0 = performance.now();
      handle.stop();
      latencies.push(performance.now() - t0);
    }
    const sorted = [...latencies].sort((a, b) => a - b);
    const p95 = sorted[Math.floor(sorted.length * 0.95) - 1] ?? 0;
    expect(p95).toBeLessThan(50);
    expect(provider.name).toBe('scripted');
  });
});

describe('v2 realtime — streaming STT partials (roadmap M2)', () => {
  it('partials replace until finalize: started, partial×3, final, transcript, cleared, turn.ended', async () => {
    const provider = new ScriptedTtsProvider(STREAMY_TOKENS);
    const { service } = makeService(provider);
    const { callId, events } = await makeAnsweredCall(service);

    const p1 = await service.submitUtterancePartial(callId, { text: 'I' }, USER_ACTOR);
    await service.submitUtterancePartial(callId, { text: 'I want' }, USER_ACTOR);
    await service.submitUtterancePartial(callId, { text: 'I want it' }, USER_ACTOR);
    const fin = await service.submitUtterancePartial(callId, { text: 'I want it now', finalize: true }, USER_ACTOR);

    expect(p1.final).toBe(false);
    expect(fin.final).toBe(true);
    expect(fin.utterance_id).toBe(p1.utterance_id);

    const types = events.map((e) => e.type);
    const partials = events.filter((e) => e.type === V2_EVENTS.SPEECH_PARTIAL);
    expect(partials).toHaveLength(3);
    expect(partials.map((e) => (e.payload as { text: string }).text)).toEqual(['I', 'I want', 'I want it']);
    expect(types.filter((t) => t === V2_EVENTS.SPEECH_STARTED)).toHaveLength(1);
    expect(types.filter((t) => t === V2_EVENTS.SPEECH_FINAL)).toHaveLength(1);
    expect(types.filter((t) => t === V2_EVENTS.TRANSCRIPT_UPDATED)).toHaveLength(1);
    expect(types).toContain(V2_EVENTS.TRANSCRIPT_PARTIAL_CLEARED);
    expect(types).toContain(V2_EVENTS.TURN_ENDED);
    // No AI stream existed → no lease events at all.
    expect(types.filter((t) => t === V2_EVENTS.TURN_LEASE)).toHaveLength(0);

    const final = must(events.find((e) => e.type === V2_EVENTS.SPEECH_FINAL), 'final');
    expect(final.payload).toMatchObject({ utterance_id: fin.utterance_id, text: 'I want it now' });

    const transcript = service.getTranscript(callId, AI_ACTOR);
    expect(transcript).toHaveLength(1);
    expect(transcript[0]).toMatchObject({ role: 'user', type: 'speech', text: 'I want it now', seq: 1 });
    expect(transcript[0]?.is_partial).toBeUndefined();
  });

  it('getTranscript(includePartials) exposes the live partial with is_partial, then replaces it', async () => {
    const provider = new ScriptedTtsProvider(STREAMY_TOKENS);
    const { service } = makeService(provider);
    const { callId } = await makeAnsweredCall(service);

    await service.submitUtterancePartial(callId, { text: 'so far' }, USER_ACTOR);
    const withPartials = service.getTranscript(callId, AI_ACTOR, undefined, 200, true);
    expect(withPartials.at(-1)).toMatchObject({ role: 'user', text: 'so far', is_partial: true });
    const settledOnly = service.getTranscript(callId, AI_ACTOR);
    expect(settledOnly).toHaveLength(0);

    await service.submitUtterancePartial(callId, { text: 'so far so good', finalize: true }, USER_ACTOR);
    const after = service.getTranscript(callId, AI_ACTOR, undefined, 200, true);
    expect(after.at(-1)).toMatchObject({ role: 'user', text: 'so far so good', seq: 1 });
    expect(after.at(-1)?.is_partial).toBeUndefined();
  });

  it('partials barge a live stream and finalize atomically with language', async () => {
    const provider = new ScriptedTtsProvider(STREAMY_TOKENS);
    const { service } = makeService(provider);
    const { callId, events } = await makeAnsweredCall(service);

    await service.speak(callId, { content: 'Here is your answer' }, AI_ACTOR);
    const partial = await service.submitUtterancePartial(callId, { text: 'wait', utterance_id: 'utt-1' }, USER_ACTOR);
    expect(partial.utterance_id).toBe('utt-1');
    expect(partial.final).toBe(false);
    expect(events.map((e) => e.type)).toContain(V2_EVENTS.USER_INTERRUPTED);

    const fin = await service.submitUtterancePartial(
      callId,
      { text: 'wait — I meant the other one', finalize: true, utterance_id: 'utt-1', language: 'en' },
      USER_ACTOR,
    );
    expect(fin.utterance_id).toBe('utt-1');
    expect(fin.final).toBe(true);
    const final = events.find((e) => e.type === V2_EVENTS.SPEECH_FINAL);
    expect(final?.payload).toMatchObject({ utterance_id: 'utt-1', language: 'en' });
  });
});