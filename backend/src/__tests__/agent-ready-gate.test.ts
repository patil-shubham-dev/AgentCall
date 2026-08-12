import { describe, it, expect, vi, beforeEach } from 'vitest';
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

import * as serviceModule from '../voicebridge/service.js';
import { VoiceBridgeService, MAX_RING_RETRIES } from '../voicebridge/service.js';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import type { VoiceCallSession } from '../voicebridge/types.js';
import { CleanupScheduler } from '../common/cleanup-scheduler.js';
import { LifecycleCoordinator } from '../voicebridge/lifecycle-coordinator.js';
import { McpSessionRegistry, type McpManagedSession } from '../mcp/session-registry.js';
import { initializeAiKeys, createAiKey, resolveAiKey, isAiKeyOnlineByName } from '../voicebridge/ai-keys.js';

const flush = (ms = 30): Promise<void> => new Promise((r) => setTimeout(r, ms));

function makeSession(overrides: Partial<VoiceCallSession> = {}): VoiceCallSession {
  return {
    id: 'call-gate-1',
    userId: 'user-gate',
    agentId: 'AI Agent',
    status: 'pending',
    priority: 'normal',
    reason: 'clarification',
    context: { summary: 'gate test' },
    messages: [],
    createdAt: new Date().toISOString(),
    lastActivityAt: new Date().toISOString(),
    ...overrides,
  };
}

describe('Phase-2 agent-ready gate', () => {
  let phoneWs: WebSocket;
  let service: VoiceBridgeService;
  let sessionRepo: InMemorySessionRepository;
  let callbackRepo: InMemoryCallbackRepository;
  let agentSet: Set<string>;
  let capturedRetries: Array<() => void>;

  beforeEach(() => {
    phoneWs = new WebSocket();
    vi.clearAllMocks();
    sessionRepo = new InMemorySessionRepository();
    callbackRepo = new InMemoryCallbackRepository();
    service = new VoiceBridgeService(sessionRepo, callbackRepo);
    agentSet = new Set<string>();
    service.setAgentPresenceProvider(() => agentSet);
    const scheduler = new CleanupScheduler();
    capturedRetries = [];
    vi.spyOn(scheduler, 'schedule').mockImplementation((_id: string, _at: number, cb: () => void) => {
      capturedRetries.push(cb);
    });
    service.setRingRetryScheduler(scheduler);
    serviceModule.registerPhone('user-gate', phoneWs);
  });

  it('pushes call_incoming immediately when the agent has a live MCP presence', async () => {
    agentSet.add('AI Agent');

    const session = await service.createCall({
      userId: 'user-gate',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
    });
    const callId = session?.id ?? '';

    await flush();
    expect(session).toBeDefined();
    expect(phoneWs.send).toHaveBeenCalledTimes(1);
    const sent = JSON.parse((phoneWs.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    expect(sent.type).toBe('call_incoming');
    expect(sent.payload.callId).toBe(callId);
  });

  it('defers the ring when the agent is offline and retries while still pending', async () => {
    const session = await service.createCall({
      userId: 'user-gate',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
    });
    const callId = session?.id ?? '';

    await flush();
    expect(phoneWs.send).not.toHaveBeenCalled();
    expect(await sessionRepo.findById(callId)).toMatchObject({ status: 'pending' });
    expect(capturedRetries.length).toBe(1);

    // Agent comes online; the next retry delivers the ring.
    agentSet.add('AI Agent');
    capturedRetries[0]();
    await flush();
    expect(phoneWs.send).toHaveBeenCalledTimes(1);
    const sent = JSON.parse((phoneWs.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    expect(sent.type).toBe('call_incoming');
    expect(sent.payload.callId).toBe(callId);
  });

  it('stops retrying once the ring window has expired (call becomes a missed call)', async () => {
    // Session created 4 minutes ago: ring window (3 min) already closed.
    const oldCreatedAt = new Date(Date.now() - 4 * 60 * 1000).toISOString();
    const session = makeSession({ id: 'call-gate-expired', createdAt: oldCreatedAt });
    await sessionRepo.create(session);

    agentSet.add('AI Agent');
    await service.attemptRing('call-gate-expired', MAX_RING_RETRIES);
    await flush();

    expect(phoneWs.send).not.toHaveBeenCalled();
    expect(capturedRetries.length).toBe(0);
    expect(await sessionRepo.findById('call-gate-expired')).toMatchObject({ status: 'pending' });
  });

  it('an active ai_wait lease is sufficient readiness even with no presence', async () => {
    const session = await service.createCall({
      userId: 'user-gate',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
    });
    const callId = session?.id ?? '';
    await flush();
    expect(phoneWs.send).not.toHaveBeenCalled();

    // Agent picks up the deferred call and starts working it.
    service.registerAiWait(callId, 60_000);
    await service.attemptRing(callId, MAX_RING_RETRIES);
    await flush();

    // First push was the ai_wait_status; the last must be the ring.
    const sends = (phoneWs.send as ReturnType<typeof vi.fn>).mock.calls;
    expect(sends.length).toBe(2);
    const last = JSON.parse(sends[sends.length - 1][0]);
    expect(last.type).toBe('call_incoming');
  });

  it('behaves like the legacy version (always rings) when no presence provider is wired', async () => {
    const legacy = new VoiceBridgeService(sessionRepo, callbackRepo);
    legacy.setRingRetryScheduler(new CleanupScheduler());
    serviceModule.registerPhone('user-legacy', phoneWs);

    await legacy.createCall({ userId: 'user-legacy', agentId: 'AI Agent', reason: 'clarification', summary: 'hi' });
    await flush();

    expect(phoneWs.send).toHaveBeenCalledTimes(1);
    const sent = JSON.parse((phoneWs.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    expect(sent.type).toBe('call_incoming');
  });

  it('retries exhaust without pushing when the agent never comes back', async () => {
    const session = await service.createCall({
      userId: 'user-gate',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
    });
    const callId = session?.id ?? '';
    await flush();
    expect(capturedRetries.length).toBe(1);

    // Burn through the whole retry budget: each attempt reschedules when
    // offline, until attemptsLeft hits zero.
    let guard = 0;
    while (capturedRetries.length > 0 && guard < MAX_RING_RETRIES + 2) {
      const cb = capturedRetries.shift();
      if (!cb) break;
      cb();
      await flush();
      guard++;
    }
    await flush();

    expect(phoneWs.send).not.toHaveBeenCalled();
    expect(capturedRetries.length).toBe(0);
    // Still pending: the pending-TTL sweep cancels it (covered by the
    // pending-call-ttl tests) â€” a missed call, not a late ring.
    expect(await sessionRepo.findById(callId)).toMatchObject({ status: 'pending' });
  });
});

describe('Phase-2 gate: resumed callbacks', () => {
  it('routes resumed callbacks through the injected ringCall hook', async () => {
    const sessionRepo = new InMemorySessionRepository();
    const callbackRepo = new InMemoryCallbackRepository();
    const notifyPhone = vi.fn();
    const ringCall = vi.fn().mockResolvedValue(undefined);
    const scheduler = new CleanupScheduler();
    const coordinator = new LifecycleCoordinator(scheduler, sessionRepo, callbackRepo, notifyPhone, ringCall);

    const resumedAt = Date.now() - 10_000;
    await sessionRepo.create(
      makeSession({
        id: 'call-gate-resume',
        userId: 'user-gate',
        status: 'paused',
        createdAt: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
      }),
    );

    const scheduled: Array<() => void> = [];
    const scheduleSpy = vi
      .spyOn(scheduler, 'schedule')
      .mockImplementation((_id: string, _at: number, cb: () => void) => {
        scheduled.push(cb);
      });

    coordinator.resumeCallback('user-gate', 'call-gate-resume', 10, resumedAt);
    const resumeCb = scheduled.find((_, i) => scheduleSpy.mock.calls[i]?.[0] === `resume:call-gate-resume`);
    expect(resumeCb).toBeDefined();
    if (resumeCb) {
      resumeCb();
    }
    await flush();

    expect(ringCall).toHaveBeenCalledWith('call-gate-resume');
    expect(notifyPhone).not.toHaveBeenCalled();
    expect(await sessionRepo.findById('call-gate-resume')).toMatchObject({
      status: 'pending',
    });
  });

  it('keeps the direct push path when no ringCall hook is injected (legacy)', async () => {
    const sessionRepo = new InMemorySessionRepository();
    const callbackRepo = new InMemoryCallbackRepository();
    const notifyPhone = vi.fn().mockReturnValue(true);
    const scheduler = new CleanupScheduler();
    const coordinator = new LifecycleCoordinator(scheduler, sessionRepo, callbackRepo, notifyPhone);

    const resumedAt = Date.now() - 10_000;
    await sessionRepo.create(
      makeSession({
        id: 'call-gate-resume-legacy',
        userId: 'user-gate',
        status: 'paused',
        createdAt: new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString(),
      }),
    );

    const scheduled: Array<() => void> = [];
    vi.spyOn(scheduler, 'schedule').mockImplementation((_id: string, _at: number, cb: () => void) => {
      scheduled.push(cb);
    });

    coordinator.resumeCallback('user-gate', 'call-gate-resume-legacy', 10, resumedAt);
    const legacyResumeCb = scheduled.shift();
    expect(legacyResumeCb).toBeDefined();
    if (legacyResumeCb) {
      legacyResumeCb();
    }
    await flush();

    expect(notifyPhone).toHaveBeenCalledTimes(1);
    const payload = notifyPhone.mock.calls[0][1] as Record<string, unknown>;
    expect(payload.type).toBe('call_incoming');
    expect(payload.isCallback).toBe(true);
  });
});

describe('Phase-2 gate: agent status sources', () => {
  it('isAiKeyOnlineByName: false before first use, true within the online window', async () => {
    await initializeAiKeys();
    const { key } = await createAiKey('AgentX');

    expect(await isAiKeyOnlineByName('AgentX')).toBe(false);
    expect(await isAiKeyOnlineByName('Unknown')).toBe(false);

    await resolveAiKey(key);
    await flush(50);
    expect(await isAiKeyOnlineByName('AgentX')).toBe(true);
  });

  it('McpSessionRegistry.getAgentStatus reflects live sessions and last activity', () => {
    const registry = new McpSessionRegistry();
    const stub = (lastActivityAt: number): McpManagedSession =>
      ({ server: {}, transport: {}, lastActivityAt, agentName: 'AgentY' }) as unknown as McpManagedSession;

    expect(registry.getAgentStatus('AgentY')).toEqual({ online: false, lastSeenAt: null });

    registry.set('s1', stub(1000));
    registry.set('s2', stub(5000));
    expect(registry.getAgentStatus('AgentY')).toEqual({ online: true, lastSeenAt: new Date(5000).toISOString() });
    expect(registry.getAgentStatus('Other')).toEqual({ online: false, lastSeenAt: null });
  });

  it('does not schedule retries when no retry scheduler is wired (safe default)', async () => {
    const bareWs = new WebSocket();
    const bare = new VoiceBridgeService(new InMemorySessionRepository(), new InMemoryCallbackRepository());
    bare.setAgentPresenceProvider(() => new Set<string>());
    serviceModule.registerPhone('user-bare', bareWs);

    await bare.createCall({ userId: 'user-bare', agentId: 'AI Agent', reason: 'clarification', summary: 'hi' });
    await flush();

    expect(bareWs.send).not.toHaveBeenCalled();
    expect(await bare.getUserActiveCall('user-bare')).toBeDefined();
  });
});

describe('Phase-2 gate: call.delayed observability event', () => {
  it('publishes call.delayed with reason agent_offline when the agent has no presence', async () => {
    const { DefaultEventBus } = await import('../event-bus/index.js');
    const { install } = await import('../voicebridge/calls/publisher.js');
    const { CALL_DELAYED } = await import('../voicebridge/calls/events.js');

    const bus = new DefaultEventBus();
    install(bus);
    const received: Array<{ payload: { callId: string; reason: string } }> = [];
    bus.subscribe(CALL_DELAYED, (event) => {
      received.push(event as { payload: { callId: string; reason: string } });
    });

    const ws = new WebSocket();
    const svc = new VoiceBridgeService(new InMemorySessionRepository(), new InMemoryCallbackRepository());
    svc.setAgentPresenceProvider(() => new Set<string>());
    svc.setRingRetryScheduler(new CleanupScheduler());
    serviceModule.registerPhone('user-delayed', ws);

    const session = await svc.createCall({
      userId: 'user-delayed',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
    });
    await flush();

    expect(ws.send).not.toHaveBeenCalled();
    expect(received.length).toBe(1);
    expect(received[0].payload.callId).toBe(session?.id);
    expect(received[0].payload.reason).toBe('agent_offline');
  });

  it('reports reason agent_busy when the agent is mid-turn on another call', async () => {
    const { DefaultEventBus } = await import('../event-bus/index.js');
    const { install } = await import('../voicebridge/calls/publisher.js');
    const { CALL_DELAYED } = await import('../voicebridge/calls/events.js');

    const bus = new DefaultEventBus();
    install(bus);
    const received: Array<{ payload: { callId: string; reason: string } }> = [];
    bus.subscribe(CALL_DELAYED, (event) => {
      received.push(event as { payload: { callId: string; reason: string } });
    });

    const ws = new WebSocket();
    const svc = new VoiceBridgeService(new InMemorySessionRepository(), new InMemoryCallbackRepository());
    svc.setAgentPresenceProvider(() => new Set<string>());
    svc.setRingRetryScheduler(new CleanupScheduler());
    serviceModule.registerPhone('user-busy', ws);

    // Agent is actively working a different call.
    svc.registerAiWait('call-other', 60_000);

    await svc.createCall({
      userId: 'user-busy',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'hi',
    });
    await flush();

    expect(received.length).toBe(1);
    expect(received[0].payload.reason).toBe('agent_busy');
  });
});

describe('Phase-3 outgoing calls (user origin)', () => {
  it('skips the ring gate for user-origin calls: no ring push, no retries', async () => {
    const ws = new WebSocket();
    const repo = new InMemorySessionRepository();
    const svc = new VoiceBridgeService(repo, new InMemoryCallbackRepository());
    svc.setAgentPresenceProvider(() => new Set<string>());
    const scheduler = new CleanupScheduler();
    const retries: Array<() => void> = [];
    vi.spyOn(scheduler, 'schedule').mockImplementation((_id: string, _at: number, cb: () => void) => {
      retries.push(cb);
    });
    svc.setRingRetryScheduler(scheduler);
    serviceModule.registerPhone('user-outgoing', ws);

    const session = await svc.createCall({
      userId: 'user-outgoing',
      agentId: 'AI Agent',
      reason: 'clarification',
      summary: 'User wants to talk',
      origin: 'user',
    });
    await flush();

    expect(ws.send).not.toHaveBeenCalled();
    expect(retries).toHaveLength(0);
    expect(await repo.findById(session.id)).toMatchObject({ status: 'pending' });
  });
});
