import { describe, it, expect } from 'vitest';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemorySessionRepository } from '../voicebridge/repositories/session-repository.js';
import { InMemoryCallbackRepository } from '../voicebridge/repositories/callback-repository.js';
import type { VoiceCallSession } from '../voicebridge/types.js';

function minutesAgo(min: number): string {
  return new Date(Date.now() - min * 60 * 1000).toISOString();
}

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
    createdAt: minutesAgo(1),
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

describe('answerCall', () => {
  it('flips a pending call to active with connectedAt set', async () => {
    const service = makeService([makeSession({ id: 'call-1' })]);

    const answered = await service.answerCall('call-1');

    expect(answered?.status).toBe('active');
    expect(answered?.connectedAt).toBeDefined();
  });

  it('is idempotent for an already-active call', async () => {
    const connectedAt = minutesAgo(1);
    const service = makeService([
      makeSession({ id: 'call-1', status: 'active', connectedAt }),
    ]);

    const answered = await service.answerCall('call-1');

    expect(answered?.status).toBe('active');
    expect(answered?.connectedAt).toBe(connectedAt);
  });

  it('is a no-op for terminal calls', async () => {
    const service = makeService([
      makeSession({ id: 'call-done', status: 'completed' }),
      makeSession({ id: 'call-cancelled', status: 'cancelled' }),
    ]);

    const completed = await service.answerCall('call-done');
    const cancelled = await service.answerCall('call-cancelled');

    expect(completed?.status).toBe('completed');
    expect(cancelled?.status).toBe('cancelled');
  });

  it('resumes a paused call and clears the pending callback', async () => {
    const sessionRepo = new InMemorySessionRepository();
    const callbackRepo = new InMemoryCallbackRepository();
    await sessionRepo.create(makeSession({ id: 'call-paused', status: 'paused' }));
    const service = new VoiceBridgeService(sessionRepo, callbackRepo);
    await service.scheduleCallback({ callId: 'call-paused', delayMinutes: 10, reason: 'user_requested' });
    expect(await callbackRepo.findByUserId('user-1')).toBeDefined();

    const answered = await service.answerCall('call-paused');

    expect(answered?.status).toBe('active');
    expect(answered?.connectedAt).toBeDefined();
    expect(await callbackRepo.findByUserId('user-1')).toBeUndefined();
  });

  it('returns undefined for an unknown call', async () => {
    const service = makeService([]);
    expect(await service.answerCall('nope')).toBeUndefined();
  });
});
