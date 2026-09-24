import { describe, it, expect, vi, beforeEach } from 'vitest';
import { WebSocket } from 'ws';

// Mock ws module before importing service (same pattern as agent-ready-gate).
vi.mock('ws', async () => {
  const { EventEmitter } = await import('node:events');
  class MockWebSocket extends EventEmitter {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;
    readyState = MockWebSocket.OPEN;
    send = vi.fn();
    close = vi.fn();
    terminate = vi.fn();
    ping = vi.fn();
  }
  return { WebSocket: MockWebSocket, default: MockWebSocket };
});

// FCM transport is mocked: ok:true models "Google accepted the send" — the
// exact condition that does NOT guarantee the device rang (Doze defers).
vi.mock('../voicebridge/fcm.js', () => ({
  sendFcmPush: vi.fn().mockResolvedValue({ ok: true, tokenRemoved: false, error: null }),
}));

// Force the FCM gate ON regardless of the environment's .env (CI has none,
// and without this the service skips sendFcmPush entirely — the exact
// local/CI divergence run #130 caught). Same pattern as fcm.test.ts:
// spread the real module so config.ts itself still loads with real env.
vi.mock('../common/config.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../common/config.js')>();
  return {
    ...actual,
    config: {
      ...actual.config,
      fcm: {
        enabled: true,
        serviceAccountPath: '/tmp/fake-service-account.json',
        projectId: 'agentcall-test',
      },
    },
  };
});

import * as serviceModule from '../voicebridge/service.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import type { VoiceCallSession } from '../voicebridge/types.js';
import { CleanupScheduler } from '../common/cleanup-scheduler.js';

const { sendFcmPush } = (await import('../voicebridge/fcm.js')) as {
  sendFcmPush: ReturnType<typeof vi.fn>;
};

const flush = (ms = 30): Promise<void> => new Promise((r) => setTimeout(r, ms));

function makeSession(overrides: Partial<VoiceCallSession> = {}): VoiceCallSession {
  return {
    id: 'call-repush-1',
    userId: 'user-repush-a',
    agentId: 'AI Agent',
    status: 'pending',
    priority: 'normal',
    reason: 'clarification',
    context: { summary: 'repush test' },
    messages: [],
    createdAt: new Date().toISOString(),
    lastActivityAt: new Date().toISOString(),
    ...overrides,
  };
}

describe('FCM Doze re-push (live-unplugged ring hardening)', () => {
  let service: VoiceBridgeService;
  let sessionRepo: InMemorySessionRepository;
  let callbackRepo: InMemoryCallbackRepository;
  let capturedRetries: Array<() => void>;

  beforeEach(() => {
    vi.clearAllMocks();
    sessionRepo = new InMemorySessionRepository();
    callbackRepo = new InMemoryCallbackRepository();
    service = new VoiceBridgeService(sessionRepo, callbackRepo);
    service.setAgentPresenceProvider(() => new Set(['AI Agent']));
    const scheduler = new CleanupScheduler();
    capturedRetries = [];
    vi.spyOn(scheduler, 'schedule').mockImplementation((_id: string, _at: number, cb: () => void) => {
      capturedRetries.push(cb);
    });
    service.setRingRetryScheduler(scheduler);
    // Deliberately NO registerPhone: the WS leg is down — the FCM-only idle
    // model for an unplugged phone asleep in Doze.
  });

  it('schedules a re-push when only the FCM leg carried the ring', async () => {
    await service.createCall({
      userId: 'user-repush-a',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
      priority: 'normal',
    });
    await flush();
    expect(sendFcmPush).toHaveBeenCalledTimes(1);
    expect(capturedRetries.length).toBeGreaterThanOrEqual(1);
  });

  it('does not schedule a re-push when the WS leg delivered the ring', async () => {
    const ws = new WebSocket();
    serviceModule.registerPhone('user-repush-ws', ws);
    await service.createCall({
      userId: 'user-repush-ws',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
      priority: 'normal',
    });
    await flush();
    // WS delivered → dispatch is considered confirmed; no Doze insurance.
    expect(capturedRetries.length).toBe(0);
  });

  it('fires the re-push through attemptRing, which re-sends the ring', async () => {
    await service.createCall({
      userId: 'user-repush-b',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
      priority: 'normal',
    });
    await flush();
    const initialCalls = sendFcmPush.mock.calls.length;
    expect(capturedRetries.length).toBeGreaterThanOrEqual(1);
    capturedRetries.forEach((fire) => fire());
    await flush();
    expect(sendFcmPush.mock.calls.length).toBeGreaterThan(initialCalls);
  });

  it('gates the re-push out once the call is no longer pending', async () => {
    const created = await service.createCall({
      userId: 'user-repush-c',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
      priority: 'normal',
    });
    await flush();
    const initialCalls = sendFcmPush.mock.calls.length;
    await service.cancelCall(created.id, undefined, true);
    capturedRetries.forEach((fire) => fire());
    await flush();
    expect(sendFcmPush.mock.calls.length).toBe(initialCalls);
  });

  it('skips the re-push when the ring window is too close to expiry', async () => {
    // Window left = 3 min TTL - 2.5 min elapsed = 30s < FCM_RING_REPUSH_MIN_WINDOW_MS.
    const session = makeSession({
      id: 'call-repush-late',
      userId: 'user-repush-d',
      createdAt: new Date(Date.now() - 150_000).toISOString(),
    });
    await sessionRepo.save(session);
    await service.attemptRing('call-repush-late');
    await flush();
    expect(sendFcmPush).toHaveBeenCalledTimes(1);
    expect(capturedRetries.length).toBe(0);
  });
});
