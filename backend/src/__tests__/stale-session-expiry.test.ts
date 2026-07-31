import { describe, it, expect } from 'vitest';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemorySessionRepository } from '../voicebridge/repositories/session-repository.js';
import { InMemoryCallbackRepository } from '../voicebridge/repositories/callback-repository.js';
import type { VoiceCallSession } from '../voicebridge/types.js';

const STALE_THRESHOLD_MIN = 30;

function minutesAgo(min: number): string {
  return new Date(Date.now() - min * 60 * 1000).toISOString();
}

function makeSession(overrides: Partial<VoiceCallSession>): VoiceCallSession {
  return {
    id: 'call-1',
    userId: 'user-1',
    agentId: 'agent-1',
    status: 'active',
    priority: 'normal',
    reason: 'clarification',
    context: { summary: 'test' },
    messages: [],
    createdAt: minutesAgo(120),
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

describe('getUserActiveCall stale-session expiry', () => {
  it('returns a long-paused conversation whose last activity is recent, even if createdAt is old', async () => {
    const service = makeService([
      makeSession({
        id: 'call-paused',
        createdAt: minutesAgo(120),
        lastActivityAt: minutesAgo(5),
      }),
    ]);

    const active = await service.getUserActiveCall('user-1');
    expect(active?.id).toBe('call-paused');
  });

  it('does not return a session whose last activity is older than the threshold', async () => {
    const service = makeService([
      makeSession({
        id: 'call-orphaned',
        lastActivityAt: minutesAgo(STALE_THRESHOLD_MIN + 10),
      }),
    ]);

    const active = await service.getUserActiveCall('user-1');
    expect(active).toBeUndefined();
  });

  it('falls back to createdAt when lastActivityAt is absent (pre-upgrade sessions)', async () => {
    const service = makeService([
      makeSession({
        id: 'call-no-activity-field',
        createdAt: minutesAgo(60),
        lastActivityAt: undefined,
      }),
    ]);

    const active = await service.getUserActiveCall('user-1');
    expect(active).toBeUndefined();
  });
});

describe('sweepStaleSessions', () => {
  it('sweeps only sessions idle past the threshold, keeping long-paused but recently-active ones', async () => {
    const service = makeService([
      makeSession({
        id: 'call-orphan',
        lastActivityAt: minutesAgo(STALE_THRESHOLD_MIN + 5),
      }),
      makeSession({
        id: 'call-paused',
        createdAt: minutesAgo(180),
        lastActivityAt: minutesAgo(3),
      }),
    ]);

    const completed = await service.sweepStaleSessions();

    expect(completed).toBe(1);
    const orphan = await service.getCall('call-orphan');
    expect(orphan?.status).toBe('completed');
    expect(orphan?.result?.transcriptSummary).toContain('no conversation activity');
    const paused = await service.getCall('call-paused');
    expect(paused?.status).toBe('active');
  });

  it('leaves sessions with no lastActivityAt but fresh createdAt alone', async () => {
    const service = makeService([
      makeSession({
        id: 'call-fresh',
        createdAt: minutesAgo(10),
        lastActivityAt: undefined,
      }),
    ]);

    const completed = await service.sweepStaleSessions();
    expect(completed).toBe(0);
  });
});

describe('lastActivityAt update semantics', () => {
  it('bumps lastActivityAt on AI message', async () => {
    const service = makeService([makeSession({ id: 'call-1' })]);
    await service.addAiMessage('call-1', 'hello');

    const session = await service.getCall('call-1');
    expect(session?.lastActivityAt).toBeDefined();
    const lastActivityAt = session?.lastActivityAt;
    expect(lastActivityAt).toBeDefined();
    const ageMinutes = (Date.now() - new Date(lastActivityAt ?? 0).getTime()) / 60000;
    expect(ageMinutes).toBeLessThan(1);
  });

  it('bumps lastActivityAt on user message', async () => {
    const service = makeService([makeSession({ id: 'call-1' })]);
    await service.processTextMessage('call-1', 'hi there');

    const session = await service.getCall('call-1');
    expect(session?.lastActivityAt).toBeDefined();
    const lastActivityAt = session?.lastActivityAt;
    expect(lastActivityAt).toBeDefined();
    const ageMinutes = (Date.now() - new Date(lastActivityAt ?? 0).getTime()) / 60000;
    expect(ageMinutes).toBeLessThan(1);
  });
});
