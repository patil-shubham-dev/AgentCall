import { describe, it, expect, vi, afterEach, afterAll } from 'vitest';
import Fastify, { type FastifyInstance } from 'fastify';
import { McpSessionRegistry, type McpManagedSession } from '../mcp/session-registry.js';
import { registerMcpEndpoint } from '../mcp/endpoint.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemorySessionRepository } from '../voicebridge/repositories/session-repository.js';
import { InMemoryCallbackRepository } from '../voicebridge/repositories/callback-repository.js';
import type { VoiceCallSession } from '../voicebridge/types.js';
import type { Server } from '@modelcontextprotocol/sdk/server/index.js';
import type { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';

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

function makeManagedSession(agentName: string): McpManagedSession {
  return {
    server: { close: vi.fn().mockResolvedValue(undefined) } as unknown as Server,
    transport: {} as unknown as StreamableHTTPServerTransport,
    lastActivityAt: Date.now(),
    lastHeartbeatAt: Date.now(),
    agentName,
  };
}

afterEach(() => {
  vi.useRealTimers();
});

describe('McpSessionRegistry close-cause plumbing', () => {
  it("delete() reports 'explicit-delete' when the agent's last session is removed", () => {
    const onAgentGone = vi.fn();
    const registry = new McpSessionRegistry(onAgentGone, () => true);
    registry.set('s1', makeManagedSession('agent-1'));

    expect(registry.delete('s1')).toBe(true);
    expect(onAgentGone).toHaveBeenCalledWith('agent-1', 'explicit-delete');
  });

  it('delete() stays silent while another session of the same agent remains', () => {
    const onAgentGone = vi.fn();
    const registry = new McpSessionRegistry(onAgentGone, () => true);
    registry.set('s1', makeManagedSession('agent-1'));
    registry.set('s2', makeManagedSession('agent-1'));

    registry.delete('s1');
    expect(onAgentGone).not.toHaveBeenCalled();
  });

  it("sweepExpired reports 'idle-timeout'", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000_000);
    const onAgentGone = vi.fn();
    const registry = new McpSessionRegistry(onAgentGone, () => true);
    registry.set('s1', makeManagedSession('agent-1'));

    vi.setSystemTime(1_000_000_000 + 31 * 60 * 1000);
    const closed = await registry.sweepExpired(30 * 60 * 1000);

    expect(closed).toBe(1);
    expect(onAgentGone).toHaveBeenCalledWith('agent-1', 'idle-timeout');
  });
});

describe('endpoint policy: heartbeat silence must not abort, explicit close must', () => {
  let app: FastifyInstance;

  afterAll(async () => {
    await app?.close();
  });

  async function makeWiredService(): Promise<{
    service: VoiceBridgeService;
    sessions: McpSessionRegistry;
    sessionRepo: InMemorySessionRepository;
  }> {
    const sessionRepo = new InMemorySessionRepository();
    const callbackRepo = new InMemoryCallbackRepository();
    const service = new VoiceBridgeService(sessionRepo, callbackRepo);
    // registerMcpEndpoint wires the REAL onAgentGone policy under test.
    app = Fastify();
    const sessions = registerMcpEndpoint(app, service);
    // Mirror index.ts: presence comes from the live registry.
    service.setAgentPresenceProvider(() => sessions.getActiveIdentities());
    return { service, sessions, sessionRepo };
  }

  it('a silent session past the 45s liveness timeout leaves the call untouched', async () => {
    const { service, sessions, sessionRepo } = await makeWiredService();
    await sessionRepo.create(makeSession({ id: 'call-quiet', status: 'pending' }));
    // Agent mid-turn: active wait lease, mid-range deadline.
    const dispose = await service.registerAiWait('call-quiet', 60_000);
    expect(service.getAiWaitStatus('call-quiet').active).toBe(true);

    sessions.set('s1', makeManagedSession('agent-1'));
    expect(service.isAgentReadyForCall('call-quiet', 'agent-1')).toBe(true);

    // Heartbeats stop for 45s+ (backdated, real timers — no clock games).
    const stale = sessions.get('s1');
    if (!stale) throw new Error('session missing');
    stale.lastHeartbeatAt = Date.now() - 46_000;
    const closed = await sessions.sweepDead(45_000);
    expect(closed).toBe(1);

    // Guarantee (a), first half: the call is NOT aborted.
    expect((await service.getCall('call-quiet'))?.status).toBe('pending');
    // The lease was never force-disposed by the sweep.
    expect(service.getAiWaitStatus('call-quiet').active).toBe(true);

    // Guarantee (a), second half: once the wait lapses with no live session,
    // readiness flips to false — the signal the phone's
    // "AI is not currently responding" banner consumes — while the call stays.
    await dispose();
    expect(service.getAiWaitStatus('call-quiet').active).toBe(false);
    expect(service.isAgentReadyForCall('call-quiet', 'agent-1')).toBe(false);
    expect((await service.getCall('call-quiet'))?.status).toBe('pending');
  });

  it('explicit session close still takes the deliberate-disconnect path (aborts)', async () => {
    const { service, sessions, sessionRepo } = await makeWiredService();
    await sessionRepo.create(makeSession({ id: 'call-explicit', status: 'pending' }));
    await service.registerAiWait('call-explicit', 60_000);
    sessions.set('s1', makeManagedSession('agent-1'));

    // This is what transport.onclose invokes after an explicit DELETE /mcp.
    sessions.delete('s1');

    // The deliberate-disconnect chain (forceDisposeAiWaits ->
    // cancelCallsByAgent) runs detached inside the endpoint callback.
    await vi.waitFor(async () => {
      expect((await service.getCall('call-explicit'))?.status).toBe('aborted');
    });
    expect(service.getAiWaitStatus('call-explicit').active).toBe(false);
  });
});

describe('ai-wait durability across a simulated restart', () => {
  function freshRepoFromDump(rows: VoiceCallSession[]): InMemorySessionRepository {
    // Faithful to the DB path: rows round-trip through JSON (sessions.data
    // JSONB), so only plain-data fields can survive — exactly what the
    // aiWait* fields must be.
    const repo = new InMemorySessionRepository();
    for (const row of rows) {
      const copy = JSON.parse(JSON.stringify(row)) as VoiceCallSession;
      void repo.create(copy);
    }
    return repo;
  }

  it('persists the wait fact on the session row on register', async () => {
    const repo = new InMemorySessionRepository();
    const service = new VoiceBridgeService(repo, new InMemoryCallbackRepository());
    await repo.create(makeSession({ id: 'call-wait', status: 'active' }));

    await service.registerAiWait('call-wait', 60_000);

    const row = await repo.findById('call-wait');
    expect(row?.aiWaitCount).toBe(1);
    expect(row?.aiWaitActiveUntil).toBeTruthy();
    expect(Number.isNaN(Date.parse(row?.aiWaitActiveUntil ?? ''))).toBe(false);
    expect(row?.aiWaitLastActiveAt).toBeTruthy();
  });

  it('clears the persisted fact on dispose and on terminal transitions', async () => {
    const repo = new InMemorySessionRepository();
    const service = new VoiceBridgeService(repo, new InMemoryCallbackRepository());
    await repo.create(makeSession({ id: 'call-wait', status: 'active' }));

    const dispose = await service.registerAiWait('call-wait', 60_000);
    expect((await repo.findById('call-wait'))?.aiWaitCount).toBe(1);
    await dispose();
    expect((await repo.findById('call-wait'))?.aiWaitCount).toBe(0);
    expect((await repo.findById('call-wait'))?.aiWaitActiveUntil).toBeNull();

    await service.registerAiWait('call-wait', 60_000);
    await service.completeCall('call-wait');
    const row = await repo.findById('call-wait');
    expect(row?.status).toBe('completed');
    expect(row?.aiWaitCount).toBe(0);
    expect(row?.aiWaitActiveUntil).toBeNull();
  });

  it('restores a live lease on a fresh service after a simulated restart', async () => {
    const repo = new InMemorySessionRepository();
    const serviceA = new VoiceBridgeService(repo, new InMemoryCallbackRepository());
    await repo.create(makeSession({ id: 'call-restart', status: 'active' }));
    await serviceA.registerAiWait('call-restart', 60_000);

    // "Restart": fresh service over JSON-round-tripped rows (what
    // RecoveryManager.loadFromDatabase hands back in database/dual-write
    // modes). The wake plumbing is gone — only the row fact survives.
    const serviceB = new VoiceBridgeService(
      freshRepoFromDump(await repo.list()),
      new InMemoryCallbackRepository(),
    );
    expect(serviceB.getAiWaitStatus('call-restart').active).toBe(false);

    const restored = await serviceB.restoreAiWaits();
    expect(restored).toBe(1);
    const status = serviceB.getAiWaitStatus('call-restart');
    expect(status.active).toBe(true);
    expect(status.activeUntil).toBeTruthy();
  });

  it('does not revive expired deadlines or terminal calls', async () => {
    const repo = new InMemorySessionRepository();
    const serviceA = new VoiceBridgeService(repo, new InMemoryCallbackRepository());
    await repo.create(makeSession({ id: 'call-live', status: 'active' }));
    await repo.create(makeSession({ id: 'call-done', status: 'active' }));
    await serviceA.registerAiWait('call-live', 60_000);
    await serviceA.registerAiWait('call-done', 60_000);
    await serviceA.completeCall('call-done');

    // Backdate the live call's deadline past expiry on the row itself.
    const dumped = (await repo.list()).map((row) => {
      const copy = JSON.parse(JSON.stringify(row)) as VoiceCallSession;
      if (copy.id === 'call-live') copy.aiWaitActiveUntil = new Date(Date.now() - 1_000).toISOString();
      return copy;
    });
    const serviceB = new VoiceBridgeService(
      freshRepoFromDump(dumped),
      new InMemoryCallbackRepository(),
    );

    const restored = await serviceB.restoreAiWaits();
    expect(restored).toBe(0);
    expect(serviceB.getAiWaitStatus('call-live').active).toBe(false);
    expect(serviceB.getAiWaitStatus('call-done').active).toBe(false);
  });
});
