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

  it('cancels an active call', async () => {
    const service = makeService([makeSession({ id: 'call-active', status: 'active' })]);

    const cancelled = await service.cancelCall('call-active');

    expect(cancelled?.status).toBe('cancelled');
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
