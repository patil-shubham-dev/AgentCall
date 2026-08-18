import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { McpSessionRegistry, type McpManagedSession } from '../mcp/session-registry.js';
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

function makeManagedSession(agentName: string, serverClose: () => Promise<void> = async () => {}): McpManagedSession {
  return {
    server: { close: serverClose } as unknown as McpManagedSession['server'],
    transport: {} as McpManagedSession['transport'],
    lastActivityAt: Date.now(),
    lastHeartbeatAt: Date.now(),
    agentName,
  };
}

describe('McpSessionRegistry heartbeat vs activity', () => {
  it('touch refreshes both lastActivityAt and lastHeartbeatAt', () => {
    const registry = new McpSessionRegistry();
    const session = makeManagedSession('agent-1');
    registry.set('s1', session);
    session.lastActivityAt = 100;
    session.lastHeartbeatAt = 100;

    registry.touch('s1');

    expect(session.lastActivityAt).toBeGreaterThan(100);
    expect(session.lastHeartbeatAt).toBeGreaterThan(100);
  });

  it('heartbeat refreshes only lastHeartbeatAt, not lastActivityAt', () => {
    const registry = new McpSessionRegistry();
    const session = makeManagedSession('agent-1');
    registry.set('s1', session);
    session.lastActivityAt = 100;
    session.lastHeartbeatAt = 100;

    registry.heartbeat('s1');

    // Activity must stay untouched: a ping loop must never keep the 30-min
    // idle sweep from firing.
    expect(session.lastActivityAt).toBe(100);
    expect(session.lastHeartbeatAt).toBeGreaterThan(100);
  });
});

describe('McpSessionRegistry sweepDead', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  it('closes a session whose heartbeat stopped while the agent has open calls', async () => {
    const onAgentGone = vi.fn();
    const registry = new McpSessionRegistry(onAgentGone, () => true);
    const session = makeManagedSession('agent-1');
    registry.set('s1', session);
    session.lastHeartbeatAt = Date.now();

    // Advance past the 45s liveness timeout.
    vi.advanceTimersByTime(46_000);
    const closed = await registry.sweepDead(45_000);

    expect(closed).toBe(1);
    expect(onAgentGone).toHaveBeenCalledWith('agent-1');
  });

  it('does not close a session with a fresh heartbeat', async () => {
    const onAgentGone = vi.fn();
    const registry = new McpSessionRegistry(onAgentGone, () => true);
    const session = makeManagedSession('agent-1');
    registry.set('s1', session);
    session.lastHeartbeatAt = Date.now();

    vi.advanceTimersByTime(10_000);
    const closed = await registry.sweepDead(45_000);

    expect(closed).toBe(0);
    expect(onAgentGone).not.toHaveBeenCalled();
  });

  it('does not close a dead session whose agent has NO open calls', async () => {
    const onAgentGone = vi.fn();
    // hasOpenCalls returns false: the session is left to the 30-min idle sweep.
    const registry = new McpSessionRegistry(onAgentGone, () => false);
    const session = makeManagedSession('agent-1');
    registry.set('s1', session);
    session.lastHeartbeatAt = Date.now();

    vi.advanceTimersByTime(46_000);
    const closed = await registry.sweepDead(45_000);

    expect(closed).toBe(0);
    expect(onAgentGone).not.toHaveBeenCalled();
    // Still registered for the idle sweep.
    expect(registry.get('s1')).toBeDefined();
  });

  it('fires onAgentGone only when the closed session was the agent last', async () => {
    const onAgentGone = vi.fn();
    const registry = new McpSessionRegistry(onAgentGone, () => true);
    const dead = makeManagedSession('agent-1');
    const alive = makeManagedSession('agent-1');
    registry.set('dead', dead);
    registry.set('alive', alive);
    dead.lastHeartbeatAt = Date.now();
    alive.lastHeartbeatAt = Date.now();

    vi.advanceTimersByTime(46_000);
    // Only 'dead' is stale; 'alive' heartbeat is fresh... but both were set at
    // the same timestamp. Make the live one fresh after the advance.
    alive.lastHeartbeatAt = Date.now();
    const closed = await registry.sweepDead(45_000);

    expect(closed).toBe(1);
    // Agent-1 still has a live session, so onAgentGone must NOT fire.
    expect(onAgentGone).not.toHaveBeenCalled();

    // Once the last session goes stale too, it fires.
    alive.lastHeartbeatAt = Date.now() - 46_000;
    const closed2 = await registry.sweepDead(45_000);
    expect(closed2).toBe(1);
    expect(onAgentGone).toHaveBeenCalledWith('agent-1');
  });
});

describe('forceDisposeAiWaits (dead-agent sweep bypasses the ai_wait guard)', () => {
  it('clears leases so cancelCallsByAgent aborts calls a lease was protecting', async () => {
    const sessionRepo = new InMemorySessionRepository();
    const callbackRepo = new InMemoryCallbackRepository();
    const service = new VoiceBridgeService(sessionRepo, callbackRepo);
    await sessionRepo.create(makeSession({ id: 'call-leased', agentId: 'agent-1', status: 'active' }));
    await sessionRepo.create(makeSession({ id: 'call-free', agentId: 'agent-1', status: 'active' }));

    // The dead agent left a lease behind (its dispose() never ran).
    service.registerAiWait('call-leased', null);

    // Without force-dispose, the guard skips the leased call.
    await service.cancelCallsByAgent('agent-1', 'agent_disconnected');
    expect((await service.getCall('call-leased'))?.status).toBe('active');
    expect((await service.getCall('call-free'))?.status).toBe('aborted');

    // The liveness sweep force-disposes first, then cancels: both abort now.
    await service.forceDisposeAiWaits('agent-1');
    await service.cancelCallsByAgent('agent-1', 'agent_disconnected');
    expect((await service.getCall('call-leased'))?.status).toBe('aborted');
    expect((await service.getCall('call-free'))?.status).toBe('aborted');
  });

  it('is a no-op for an agent with no leases', async () => {
    const sessionRepo = new InMemorySessionRepository();
    const service = new VoiceBridgeService(sessionRepo, new InMemoryCallbackRepository());
    await sessionRepo.create(makeSession({ id: 'call-1', agentId: 'agent-1', status: 'pending' }));

    await expect(service.forceDisposeAiWaits('agent-1')).resolves.toBe(0);
    await expect(service.forceDisposeAiWaits('agent-ghost')).resolves.toBe(0);
  });

  it('does not touch leases belonging to other agents', async () => {
    const sessionRepo = new InMemorySessionRepository();
    const service = new VoiceBridgeService(sessionRepo, new InMemoryCallbackRepository());
    await sessionRepo.create(makeSession({ id: 'mine', agentId: 'agent-1', status: 'active' }));
    await sessionRepo.create(makeSession({ id: 'theirs', agentId: 'agent-2', status: 'active' }));

    service.registerAiWait('mine', null);
    service.registerAiWait('theirs', null);

    await service.forceDisposeAiWaits('agent-1');
    await service.cancelCallsByAgent('agent-1', 'agent_disconnected');

    // agent-1's call is aborted; agent-2's lease + call survive untouched.
    expect((await service.getCall('mine'))?.status).toBe('aborted');
    expect((await service.getCall('theirs'))?.status).toBe('active');
    expect(service.getAiWaitStatus('theirs').active).toBe(true);
  });
});
