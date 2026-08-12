import { describe, it, expect } from 'vitest';
import { WebSocket } from 'ws';

// Mock ws module before importing service
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

import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemorySessionRepository } from '../voicebridge/repositories/session-repository.js';
import { InMemoryCallbackRepository } from '../voicebridge/repositories/callback-repository.js';
import type { VoiceCallSession } from '../voicebridge/types.js';
import * as serviceModule from '../voicebridge/service.js';

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

describe('pending-call ring TTL', () => {
  it('cancels a pending call older than the ring TTL even with fresh activity', async () => {
    // Fresh lastActivityAt simulates AI message traffic / phone polls keeping
    // the session alive — the hard createdAt-based TTL must still fire.
    const service = makeService([
      makeSession({
        id: 'call-ghost',
        status: 'pending',
        createdAt: minutesAgo(120),
        lastActivityAt: minutesAgo(1),
      }),
    ]);

    expect(await service.sweepStaleSessions()).toBe(1);
    const session = await service.getCall('call-ghost');
    expect(session?.status).toBe('cancelled');
  });

  it('keeps a pending call inside the ring TTL', async () => {
    const service = makeService([
      makeSession({ id: 'call-fresh', status: 'pending', createdAt: minutesAgo(1) }),
    ]);

    expect(await service.sweepStaleSessions()).toBe(0);
    const session = await service.getCall('call-fresh');
    expect(session?.status).toBe('pending');
  });

  it('anchors the TTL at resumedAt so callback resumes get a fresh ring window', async () => {
    const service = makeService([
      makeSession({
        id: 'call-resumed',
        status: 'pending',
        createdAt: minutesAgo(600),
        resumedAt: minutesAgo(1),
      }),
    ]);

    expect(await service.sweepStaleSessions()).toBe(0);
  });

  it('excludes ring-expired pending calls from the active-call lookup', async () => {
    const service = makeService([
      makeSession({
        id: 'call-ghost',
        status: 'pending',
        createdAt: minutesAgo(120),
        lastActivityAt: minutesAgo(1),
      }),
    ]);

    const active = await service.getUserActiveCall('user-1');
    expect(active).toBeUndefined();
  });

  it('returns pending calls still inside the ring window from active-call', async () => {
    const service = makeService([
      makeSession({ id: 'call-live', status: 'pending', createdAt: minutesAgo(1) }),
    ]);

    const active = await service.getUserActiveCall('user-1');
    expect(active?.id).toBe('call-live');
  });

  it('leaves active calls untouched by the pending TTL', async () => {
    const service = makeService([
      makeSession({
        id: 'call-active-old',
        status: 'active',
        createdAt: minutesAgo(600),
        lastActivityAt: minutesAgo(10),
      }),
    ]);

    expect(await service.sweepStaleSessions()).toBe(0);
    const session = await service.getCall('call-active-old');
    expect(session?.status).toBe('active');
  });

  it('notifies the phone when the sweep cancels a ring-expired pending call', async () => {
    const userId = 'user-1';
    const phoneWs = new WebSocket();
    serviceModule.registerPhone(userId, phoneWs);
    await Promise.resolve();
    (phoneWs.send as ReturnType<typeof vi.fn>).mockClear();

    const service = makeService([
      makeSession({
        id: 'call-ghost',
        userId,
        status: 'pending',
        createdAt: minutesAgo(120),
      }),
    ]);

    await service.sweepStaleSessions();
    await Promise.resolve();

    expect(phoneWs.send).toHaveBeenCalledTimes(1);
    const sent = JSON.parse((phoneWs.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    // Phase 2.3: the sweep reports a ring-timeout as call_expired (missed
    // call), distinct from an explicit cancel.
    expect(sent.type).toBe('call_expired');
    expect(sent.payload.callId).toBe('call-ghost');
    expect(sent.payload.reason).toBe('ring_ttl_expired');
  });
});
