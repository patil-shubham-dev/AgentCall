import { describe, it, expect } from 'vitest';
import type { Pool } from 'pg';
import { CleanupScheduler } from '../common/cleanup-scheduler.js';
import { RecoveryManager } from '../voicebridge/recovery-manager.js';
import { LifecycleCoordinator } from '../voicebridge/lifecycle-coordinator.js';
import {
  DatabaseCallbackRepository,
  InMemorySessionRepository,
  InMemoryCallbackRepository,
} from '../voicebridge/repositories/index.js';
import type { VoiceCallSession } from '../voicebridge/types.js';

function fakePool(rows: unknown[]): Pool {
  return {
    query: async () => ({ rows }),
    connect: async () => {
      throw new Error('connect not used in this test');
    },
  } as unknown as Pool;
}

function makePausedSession(overrides: Partial<VoiceCallSession> = {}): VoiceCallSession {
  return {
    id: 'call-1',
    userId: 'user-1',
    agentId: 'agent-1',
    status: 'paused',
    priority: 'normal',
    reason: 'clarification',
    context: { summary: 'test' },
    messages: [],
    createdAt: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
    pausedAt: new Date(Date.now() - 10 * 60 * 1000).toISOString(),
    ...overrides,
  };
}

describe('DatabaseCallbackRepository BIGINT parsing', () => {
  it('converts pg int8 strings to epoch-ms numbers in findByUserId', async () => {
    const repo = new DatabaseCallbackRepository(
      fakePool([{ user_id: 'user-1', call_id: 'call-1', resume_at: '1785513600000' }]),
    );

    const result = await repo.findByUserId('user-1');
    expect(result).toEqual({ callId: 'call-1', resumeAt: 1785513600000 });
    expect(typeof result?.resumeAt).toBe('number');
  });

  it('converts pg int8 strings to epoch-ms numbers in list', async () => {
    const repo = new DatabaseCallbackRepository(
      fakePool([
        { user_id: 'user-1', call_id: 'call-1', resume_at: '1785513600000' },
        { user_id: 'user-2', call_id: 'call-2', resume_at: '1785513900000' },
      ]),
    );

    const result = await repo.list();
    expect(result).toEqual([
      { userId: 'user-1', callId: 'call-1', resumeAt: 1785513600000 },
      { userId: 'user-2', callId: 'call-2', resumeAt: 1785513900000 },
    ]);
    expect(result.every((entry) => typeof entry.resumeAt === 'number')).toBe(true);
  });
});

describe('LifecycleCoordinator.resumeCallback string resilience', () => {
  it('schedules numeric timers when resumeAt arrives as a string', () => {
    const scheduler = new CleanupScheduler();
    const sessionRepo = new InMemorySessionRepository();
    const callbackRepo = new InMemoryCallbackRepository();
    const coordinator = new LifecycleCoordinator(
      scheduler,
      sessionRepo,
      callbackRepo,
      () => true,
    );

    coordinator.resumeCallback('user-1', 'call-1', 10, '1785513600000' as unknown as number);

    const resume = scheduler.pending().find((t) => t.id === 'resume:call-1');
    const ttl = scheduler.pending().find((t) => t.id === 'pause-ttl:call-1');
    expect(resume?.executeAt).toBe(1785513600000);
    expect(ttl?.executeAt).toBe(1785513600000 + 24 * 60 * 60 * 1000);
    scheduler.shutdown();
  });

  it('throws a clear error for garbage resumeAt instead of crashing in the timer', () => {
    const scheduler = new CleanupScheduler();
    const coordinator = new LifecycleCoordinator(
      scheduler,
      new InMemorySessionRepository(),
      new InMemoryCallbackRepository(),
      () => true,
    );

    expect(() =>
      coordinator.resumeCallback('user-1', 'call-1', 10, 'not-a-timestamp' as unknown as number),
    ).toThrow(TypeError);
    scheduler.shutdown();
  });
});

describe('RecoveryManager rebuildCallbackTimers with DB-shaped data', () => {
  it('rebuilds timers when a persisted callback resumeAt is a string (pg BIGINT)', async () => {
    const scheduler = new CleanupScheduler();
    const sessionRepo = new InMemorySessionRepository();
    const callbackRepo = new InMemoryCallbackRepository();
    const dbSessionRepo = new InMemorySessionRepository();
    const dbCallbackRepo = new InMemoryCallbackRepository();
    const coordinator = new LifecycleCoordinator(scheduler, sessionRepo, callbackRepo, () => true);

    await sessionRepo.create(makePausedSession());
    const resumeAtMs = Date.now() + 15 * 60 * 1000;
    await callbackRepo.save('user-1', {
      callId: 'call-1',
      resumeAt: resumeAtMs.toString() as unknown as number,
    });

    const recovery = new RecoveryManager(dbSessionRepo, dbCallbackRepo, sessionRepo, callbackRepo);
    await recovery.rebuildTimers(scheduler, coordinator);

    const resume = scheduler.pending().find((t) => t.id === 'resume:call-1');
    expect(resume?.executeAt).toBe(resumeAtMs);
    const ttl = scheduler.pending().find((t) => t.id === 'pause-ttl:call-1');
    expect(ttl?.executeAt).toBe(resumeAtMs + 24 * 60 * 60 * 1000);
    scheduler.shutdown();
  });
});
