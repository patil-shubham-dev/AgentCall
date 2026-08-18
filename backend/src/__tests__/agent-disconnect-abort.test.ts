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

function makeService(sessions: VoiceCallSession[]): {
  service: VoiceBridgeService;
  sessionRepo: InMemorySessionRepository;
  callbackRepo: InMemoryCallbackRepository;
} {
  const sessionRepo = new InMemorySessionRepository();
  const callbackRepo = new InMemoryCallbackRepository();
  for (const s of sessions) {
    void sessionRepo.create(s);
  }
  return { service: new VoiceBridgeService(sessionRepo, callbackRepo), sessionRepo, callbackRepo };
}

describe('cancelCallsByAgent (agent disconnect)', () => {
  it('aborts pending, active and paused calls owned by the agent', async () => {
    const { service } = makeService([
      makeSession({ id: 'call-pending', status: 'pending' }),
      makeSession({ id: 'call-active', status: 'active' }),
      makeSession({ id: 'call-paused', status: 'paused' }),
    ]);

    const count = await service.cancelCallsByAgent('agent-1', 'agent_disconnected');

    expect(count).toBe(3);
    expect((await service.getCall('call-pending'))?.status).toBe('aborted');
    expect((await service.getCall('call-active'))?.status).toBe('aborted');
    expect((await service.getCall('call-paused'))?.status).toBe('aborted');
  });

  it('ignores terminal calls and calls owned by other agents', async () => {
    const { service } = makeService([
      makeSession({ id: 'call-completed', status: 'completed' }),
      makeSession({ id: 'call-cancelled', status: 'cancelled' }),
      makeSession({ id: 'call-aborted', status: 'aborted' }),
      makeSession({ id: 'call-other', agentId: 'agent-2', status: 'active' }),
    ]);

    const count = await service.cancelCallsByAgent('agent-1', 'agent_disconnected');

    expect(count).toBe(0);
    expect((await service.getCall('call-completed'))?.status).toBe('completed');
    expect((await service.getCall('call-other'))?.status).toBe('active');
  });

  it('skips calls with an active ai_wait lease (agent waiter still alive)', async () => {
    const { service } = makeService([
      makeSession({ id: 'call-leased', status: 'active' }),
      makeSession({ id: 'call-free', status: 'active' }),
    ]);
    const dispose = service.registerAiWait('call-leased', null);

    const count = await service.cancelCallsByAgent('agent-1', 'agent_disconnected');

    expect(count).toBe(1);
    expect((await service.getCall('call-leased'))?.status).toBe('active');
    expect((await service.getCall('call-free'))?.status).toBe('aborted');

    // Once the waiter is gone, the same sweep can abort the call.
    dispose();
    await service.cancelCallsByAgent('agent-1', 'agent_disconnected');
    expect((await service.getCall('call-leased'))?.status).toBe('aborted');
  });

  it('deletes the callback row for a paused call', async () => {
    const { service, callbackRepo } = makeService([
      makeSession({ id: 'call-paused', status: 'paused' }),
    ]);
    await callbackRepo.save('user-1', { callId: 'call-paused', resumeAt: Date.now() + 60_000 });

    await service.cancelCallsByAgent('agent-1', 'agent_disconnected');

    expect(await callbackRepo.findByUserId('user-1')).toBeUndefined();
  });

  it('returns 0 when the agent has no open calls', async () => {
    const { service } = makeService([]);
    expect(await service.cancelCallsByAgent('agent-ghost', 'agent_disconnected')).toBe(0);
  });
});

describe('abortCall', () => {
  it('sets status aborted with completedAt and retention', async () => {
    const { service } = makeService([makeSession({ id: 'call-1', status: 'active' })]);

    const aborted = await service.abortCall('call-1', 'agent_disconnected');

    expect(aborted?.status).toBe('aborted');
    expect(aborted?.completedAt).toBeDefined();
    expect(aborted?.retentionExpiresAt).toBeDefined();
  });

  it('is idempotent for an already-aborted call', async () => {
    const { service } = makeService([
      makeSession({ id: 'call-1', status: 'aborted', completedAt: new Date().toISOString() }),
    ]);

    const again = await service.abortCall('call-1', 'agent_disconnected');

    expect(again?.status).toBe('aborted');
  });

  it('does not flip a completed or cancelled call to aborted', async () => {
    const { service } = makeService([
      makeSession({ id: 'call-completed', status: 'completed' }),
      makeSession({ id: 'call-cancelled', status: 'cancelled' }),
    ]);

    await service.abortCall('call-completed', 'agent_disconnected');
    await service.abortCall('call-cancelled', 'agent_disconnected');

    expect((await service.getCall('call-completed'))?.status).toBe('completed');
    expect((await service.getCall('call-cancelled'))?.status).toBe('cancelled');
  });

  it('returns undefined for an unknown call', async () => {
    const { service } = makeService([]);
    expect(await service.abortCall('call-missing', 'agent_disconnected')).toBeUndefined();
  });

  it('is terminal for answer/complete/cancel (no resurrection)', async () => {
    const { service } = makeService([
      makeSession({ id: 'call-1', status: 'aborted', completedAt: new Date().toISOString() }),
    ]);

    expect((await service.answerCall('call-1'))?.status).toBe('aborted');
    expect((await service.completeCall('call-1'))?.status).toBe('aborted');
    expect((await service.cancelCall('call-1'))?.status).toBe('aborted');
  });
});

describe('aborted calls and delivery paths', () => {
  it('excludes aborted calls from getUserActiveCall', async () => {
    const { service } = makeService([
      makeSession({ id: 'call-aborted', status: 'aborted', completedAt: new Date().toISOString() }),
      makeSession({ id: 'call-active', status: 'active' }),
    ]);

    const active = await service.getUserActiveCall('user-1');

    expect(active?.id).toBe('call-active');
  });

  it('finds sessions by agentId through the repository', async () => {
    const { sessionRepo } = makeService([
      makeSession({ id: 'a1', agentId: 'agent-1', status: 'pending' }),
      makeSession({ id: 'a2', agentId: 'agent-1', status: 'active' }),
      makeSession({ id: 'b1', agentId: 'agent-2', status: 'pending' }),
    ]);

    const forAgent1 = await sessionRepo.findByAgentId('agent-1');

    expect(forAgent1.map((s) => s.id).sort()).toEqual(['a1', 'a2']);
  });
});
