import { describe, it, expect } from 'vitest';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemorySessionRepository } from '../voicebridge/repositories/session-repository.js';
import { InMemoryCallbackRepository } from '../voicebridge/repositories/callback-repository.js';
import type { VoiceCallSession } from '../voicebridge/types.js';

function makeSession(overrides: Partial<VoiceCallSession>): VoiceCallSession {
  return {
    id: 'call-1',
    userId: 'user-1',
    agentId: 'agent-1',
    status: 'pending',
    priority: 'normal',
    reason: 'clarification',
    context: { summary: 'test' },
    messages: [],
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

function makeService(sessions: VoiceCallSession[]): VoiceBridgeService {
  const sessionRepo = new InMemorySessionRepository();
  const callbackRepo = new InMemoryCallbackRepository();
  for (const s of sessions) {
    void sessionRepo.create(s);
  }
  return new VoiceBridgeService(sessionRepo, callbackRepo);
}

describe('cancelCall idempotency', () => {
  it('cancels a pending call and records completedAt', async () => {
    const service = makeService([makeSession({ id: 'call-pending' })]);

    const cancelled = await service.cancelCall('call-pending');

    expect(cancelled?.status).toBe('cancelled');
    expect(cancelled?.completedAt).toBeDefined();
  });

  it('does not cancel a live (active) call — stale declines are no-ops', async () => {
    const service = makeService([makeSession({ id: 'call-active', status: 'active' })]);

    const session = await service.cancelCall('call-active');

    expect(session?.status).toBe('active');
    expect(session?.completedAt).toBeUndefined();
  });

  it('does not cancel a paused (previously answered) call', async () => {
    const service = makeService([makeSession({ id: 'call-paused', status: 'paused' })]);

    const session = await service.cancelCall('call-paused');

    expect(session?.status).toBe('paused');
  });

  it('returns the session unchanged when already cancelled (retry path)', async () => {
    const service = makeService([makeSession({ id: 'call-cancelled', status: 'cancelled' })]);

    const session = await service.cancelCall('call-cancelled');

    expect(session?.status).toBe('cancelled');
    const after = await service.getCall('call-cancelled');
    expect(after?.completedAt).toBeUndefined();
  });

  it('does not flip a completed call to cancelled', async () => {
    const service = makeService([makeSession({
      id: 'call-completed',
      status: 'completed',
      completedAt: new Date().toISOString(),
    })]);

    const session = await service.cancelCall('call-completed');

    expect(session?.status).toBe('completed');
  });

  it('returns undefined for an unknown call', async () => {
    const service = makeService([]);

    const session = await service.cancelCall('call-missing');

    expect(session).toBeUndefined();
  });

  it('records a decline note as a user message before cancelling', async () => {
    const service = makeService([makeSession({ id: 'call-decline' })]);

    const cancelled = await service.cancelCall('call-decline', 'The user is busy and will call back.');

    expect(cancelled?.status).toBe('cancelled');
    expect(cancelled?.messages).toEqual([
      expect.objectContaining({
        role: 'user',
        type: 'text',
        content: 'The user is busy and will call back.',
      }),
    ]);
  });

  it('does not append the note again on a cancelled retry', async () => {
    const service = makeService([makeSession({ id: 'call-retry' })]);

    await service.cancelCall('call-retry', 'decline note');
    await service.cancelCall('call-retry', 'decline note');

    const after = await service.getCall('call-retry');
    const notes = after?.messages.filter((m) => m.role === 'user' && m.content === 'decline note');
    expect(notes).toHaveLength(1);
  });

  it('ignores a blank note', async () => {
    const service = makeService([makeSession({ id: 'call-blank' })]);

    const cancelled = await service.cancelCall('call-blank', '   ');

    expect(cancelled?.messages).toHaveLength(0);
  });
});

describe('stale-decline race (2026-09-11 live validation, call e2439048)', () => {
  it('an AI message answering a ringing call survives a trailing timeout cancel', async () => {
    const service = makeService([makeSession({ id: 'call-race' })]);

    // The AI speaks first: addMessage flips pending->active server-side
    // (CallAnswered), exactly as the 17:39:04Z live event did.
    const msg = await service.addAiMessage('call-race', 'Hello, I am here with your answer.');
    expect(msg).toBeDefined();
    expect((await service.getCall('call-race'))?.status).toBe('active');

    // The phone never learned of the answer (parked WS, FCM ring-only) and
    // its 60s ring-timeout fires POST /cancel with a decline note.
    const after = await service.cancelCall('call-race', 'Sorry I missed your call.');

    expect(after?.status).toBe('active');
    expect(after?.completedAt).toBeUndefined();
    // The stale decline note must not pollute the live transcript.
    expect(after?.messages.filter((m) => m.content === 'Sorry I missed your call.')).toHaveLength(0);
    // The AI turn that won the race is intact.
    expect(after?.messages.map((m) => m.content)).toContain('Hello, I am here with your answer.');
  });

  it('a decline racing the answer keeps working while the call is still pending', async () => {
    const service = makeService([makeSession({ id: 'call-prompt-decline' })]);

    const cancelled = await service.cancelCall('call-prompt-decline', 'Busy right now.');

    expect(cancelled?.status).toBe('cancelled');
  });

  it('answer-then-cancel in either order keeps the live call (lock serializes)', async () => {
    const service = makeService([makeSession({ id: 'call-order' })]);

    await service.answerCall('call-order');
    const afterCancel = await service.cancelCall('call-order', 'late decline');

    expect(afterCancel?.status).toBe('active');
    expect((await service.getCall('call-order'))?.messages).toHaveLength(0);
  });
});

describe('completeCall idempotency', () => {
  it('completes a pending call and records completedAt', async () => {
    const service = makeService([makeSession({ id: 'call-pending' })]);

    const completed = await service.completeCall('call-pending');

    expect(completed?.status).toBe('completed');
    expect(completed?.completedAt).toBeDefined();
  });

  it('completes an active call', async () => {
    const service = makeService([makeSession({ id: 'call-active', status: 'active' })]);

    const completed = await service.completeCall('call-active');

    expect(completed?.status).toBe('completed');
  });

  it('does not overwrite an existing completed session (retry path)', async () => {
    const service = makeService([makeSession({
      id: 'call-done',
      status: 'completed',
      completedAt: new Date().toISOString(),
      result: { decision: 'original' },
    })]);

    const session = await service.completeCall('call-done', { decision: 'retry-clobber' });

    expect(session?.status).toBe('completed');
    expect(session?.result?.decision).toBe('original');
  });

  it('does not flip a cancelled call to completed', async () => {
    const service = makeService([makeSession({ id: 'call-cancelled', status: 'cancelled' })]);

    const session = await service.completeCall('call-cancelled');

    expect(session?.status).toBe('cancelled');
  });

  it('stores the result on first completion', async () => {
    const service = makeService([makeSession({ id: 'call-result' })]);

    await service.completeCall('call-result', { decision: 'approved', userResponse: 'yes' });

    const after = await service.getCall('call-result');
    expect(after?.result?.decision).toBe('approved');
  });

  it('returns undefined for an unknown call', async () => {
    const service = makeService([]);

    const session = await service.completeCall('call-missing');

    expect(session).toBeUndefined();
  });
});
