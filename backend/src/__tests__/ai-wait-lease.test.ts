import { afterEach, describe, expect, it, vi } from 'vitest';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemorySessionRepository } from '../voicebridge/repositories/session-repository.js';
import { InMemoryCallbackRepository } from '../voicebridge/repositories/callback-repository.js';
import type { VoiceCallSession } from '../voicebridge/types.js';

function makeSession(overrides: Partial<VoiceCallSession>): VoiceCallSession {
  return {
    id: 'call-lease',
    userId: 'user-lease',
    agentId: 'agent-1',
    status: 'active',
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

afterEach(() => {
  vi.useRealTimers();
});

describe('AI wait lease primitive', () => {
  it('reports inactive with null fields when no lease was ever registered', () => {
    const service = makeService([makeSession({ id: 'call-no-lease' })]);
    expect(service.getAiWaitStatus('call-no-lease')).toEqual({
      active: false,
      activeUntil: null,
      lastActiveAt: null,
    });
  });

  it('reports active with an ISO activeUntil right after registration', () => {
    const service = makeService([makeSession()]);
    service.registerAiWait('call-lease', 30_000);
    const status = service.getAiWaitStatus('call-lease');
    expect(status.active).toBe(true);
    expect(status.activeUntil).toBeTruthy();
    expect(Number.isNaN(Date.parse(status.activeUntil ?? ''))).toBe(false);
    expect(status.lastActiveAt).toBeTruthy();
  });

  it('returns inactive after dispose clears the last lease', () => {
    const service = makeService([makeSession()]);
    const dispose = service.registerAiWait('call-lease', 30_000);
    expect(service.getAiWaitStatus('call-lease').active).toBe(true);
    dispose();
    expect(service.getAiWaitStatus('call-lease').active).toBe(false);
  });

  it('reference counts overlapping registrations', () => {
    const service = makeService([makeSession()]);
    const disposeA = service.registerAiWait('call-lease', 30_000);
    const disposeB = service.registerAiWait('call-lease', 30_000);
    disposeA();
    expect(service.getAiWaitStatus('call-lease').active).toBe(true);
    disposeB();
    expect(service.getAiWaitStatus('call-lease').active).toBe(false);
  });

  it('re-activation after full dispose starts a fresh lease', () => {
    const service = makeService([makeSession()]);
    service.registerAiWait('call-lease', 30_000)();
    expect(service.getAiWaitStatus('call-lease').active).toBe(false);
    service.registerAiWait('call-lease', 30_000);
    expect(service.getAiWaitStatus('call-lease').active).toBe(true);
  });

  it('expires passively when activeUntil passes and no one disposes', () => {
    vi.useFakeTimers();
    const service = makeService([makeSession()]);
    service.registerAiWait('call-lease', 1_000);
    expect(service.getAiWaitStatus('call-lease').active).toBe(true);
    vi.advanceTimersByTime(1_500);
    const status = service.getAiWaitStatus('call-lease');
    expect(status.active).toBe(false);
    expect(status.activeUntil).toBeNull();
    expect(status.lastActiveAt).toBeTruthy();
  });

  it('keeps the furthest deadline when waits overlap, never regressing an in-flight wait', () => {
    vi.useFakeTimers();
    const service = makeService([makeSession()]);
    const disposeLong = service.registerAiWait('call-lease', 30_000);
    service.registerAiWait('call-lease', 5_000);
    vi.advanceTimersByTime(6_000);
    // The 5s wait has expired, but the 30s wait is still in flight: still active.
    expect(service.getAiWaitStatus('call-lease').active).toBe(true);
    vi.advanceTimersByTime(25_000);
    expect(service.getAiWaitStatus('call-lease').active).toBe(false);
    disposeLong();
    expect(service.getAiWaitStatus('call-lease').active).toBe(false);
  });

  it('a new registration after the old lease expired starts a fresh deadline', () => {
    vi.useFakeTimers();
    const service = makeService([makeSession()]);
    const disposeOld = service.registerAiWait('call-lease', 1_000);
    vi.advanceTimersByTime(2_000);
    const disposeNew = service.registerAiWait('call-lease', 10_000);
    expect(service.getAiWaitStatus('call-lease').active).toBe(true);
    vi.advanceTimersByTime(5_000);
    expect(service.getAiWaitStatus('call-lease').active).toBe(true);
    disposeOld();
    disposeNew();
    expect(service.getAiWaitStatus('call-lease').active).toBe(false);
  });

  it('clears the lease when the call is completed', async () => {
    const service = makeService([makeSession()]);
    service.registerAiWait('call-lease', 30_000);
    expect(service.getAiWaitStatus('call-lease').active).toBe(true);
    await service.completeCall('call-lease');
    expect(service.getAiWaitStatus('call-lease')).toEqual({
      active: false,
      activeUntil: null,
      lastActiveAt: null,
    });
  });

  it('clears the lease when the call is cancelled', async () => {
    const service = makeService([makeSession({ id: 'call-cancel' })]);
    service.registerAiWait('call-cancel', 30_000);
    expect(service.getAiWaitStatus('call-cancel').active).toBe(true);
    await service.cancelCall('call-cancel');
    expect(service.getAiWaitStatus('call-cancel').active).toBe(false);
    expect(service.getAiWaitStatus('call-cancel').lastActiveAt).toBeNull();
  });
});
