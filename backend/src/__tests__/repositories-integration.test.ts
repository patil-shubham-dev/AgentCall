import { describe, it, expect } from 'vitest';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import type { VoiceCallSession } from '../voicebridge/types.js';

function createTestSession(overrides: Partial<VoiceCallSession> = {}): VoiceCallSession {
  const now = new Date().toISOString();
  return {
    id: overrides.id ?? `test-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    userId: overrides.userId ?? 'test-user',
    agentId: 'test-agent',
    status: 'pending',
    priority: 'normal',
    reason: 'input_required',
    context: { summary: 'Test session' },
    messages: [],
    createdAt: now,
    ...overrides,
  };
}

describe('InMemorySessionRepository', () => {
  let repo: InMemorySessionRepository;

  beforeEach(() => {
    repo = new InMemorySessionRepository();
  });

  it('creates and finds a session by id', async () => {
    const session = createTestSession();
    await repo.create(session);
    const found = await repo.findById(session.id);
    expect(found).toBeDefined();
    if (found) expect(found.id).toBe(session.id);
  });

  it('returns undefined for non-existent session', async () => {
    const found = await repo.findById('non-existent');
    expect(found).toBeUndefined();
  });

  it('lists all sessions', async () => {
    await repo.create(createTestSession());
    await repo.create(createTestSession());
    const list = await repo.list();
    expect(list.length).toBe(2);
  });

  it('finds sessions by user id', async () => {
    await repo.create(createTestSession({ userId: 'user-a' }));
    await repo.create(createTestSession({ userId: 'user-b' }));
    await repo.create(createTestSession({ userId: 'user-a' }));

    const userASessions = await repo.findByUserId('user-a');
    expect(userASessions.length).toBe(2);
  });

  it('updates session via save', async () => {
    const session = createTestSession({ status: 'pending' });
    await repo.create(session);
    session.status = 'active';
    await repo.save(session);

    const updated = await repo.findById(session.id);
    expect(updated).toBeDefined();
    if (updated) expect(updated.status).toBe('active');
  });

  it('deletes a session', async () => {
    const session = createTestSession();
    await repo.create(session);
    const deleted = await repo.delete(session.id);
    expect(deleted).toBeDefined();
    expect(await repo.findById(session.id)).toBeUndefined();
  });

  it('returns undefined when deleting non-existent session', async () => {
    const result = await repo.delete('non-existent');
    expect(result).toBeUndefined();
  });
});

describe('InMemoryCallbackRepository', () => {
  let repo: InMemoryCallbackRepository;

  beforeEach(() => {
    repo = new InMemoryCallbackRepository();
  });

  it('saves and finds callback by user id', async () => {
    await repo.save('user-1', { callId: 'call-1', resumeAt: Date.now() + 60000 });
    const found = await repo.findByUserId('user-1');
    expect(found).toBeDefined();
    if (found) expect(found.callId).toBe('call-1');
  });

  it('returns undefined for user without callback', async () => {
    const found = await repo.findByUserId('no-callback');
    expect(found).toBeUndefined();
  });

  it('overwrites existing callback on save', async () => {
    await repo.save('user-1', { callId: 'call-1', resumeAt: 1000 });
    await repo.save('user-1', { callId: 'call-2', resumeAt: 2000 });
    const found = await repo.findByUserId('user-1');
    expect(found).toBeDefined();
    if (found) expect(found.callId).toBe('call-2');
  });

  it('deletes callback', async () => {
    await repo.save('user-1', { callId: 'call-1', resumeAt: 1000 });
    await repo.delete('user-1');
    expect(await repo.findByUserId('user-1')).toBeUndefined();
  });

  it('lists all callbacks', async () => {
    await repo.save('user-1', { callId: 'call-1', resumeAt: 1000 });
    await repo.save('user-2', { callId: 'call-2', resumeAt: 2000 });
    const list = await repo.list();
    expect(list.length).toBe(2);
  });
});

describe('InMemorySessionRepository transaction', () => {
  it('executes transaction callback', async () => {
    const repo = new InMemorySessionRepository();
    const result = await repo.transaction(async () => {
      const session = createTestSession();
      await repo.create(session);
      return 'done';
    });
    expect(result).toBe('done');
  });

  it('commit persists changes', async () => {
    const repo = new InMemorySessionRepository();
    const session = createTestSession();
    await repo.transaction(async () => {
      await repo.create(session);
    });
    const found = await repo.findById(session.id);
    expect(found).toBeDefined();
  });
});

describe('InMemoryCallbackRepository transaction', () => {
  it('executes transaction callback', async () => {
    const repo = new InMemoryCallbackRepository();
    const result = await repo.transaction(async () => {
      await repo.save('user', { callId: 'call', resumeAt: 1000 });
      return 'done';
    });
    expect(result).toBe('done');
  });
});

describe('RecoveryManager scenario', () => {
  it('loads sessions from DB into memory (simulated)', async () => {
    const memSessionRepo = new InMemorySessionRepository();
    const memCallbackRepo = new InMemoryCallbackRepository();

    const dbSessionRepo = new InMemorySessionRepository();
    const dbCallbackRepo = new InMemoryCallbackRepository();

    const session = createTestSession({ status: 'paused', pausedAt: new Date().toISOString() });
    await dbSessionRepo.create(session);
    await dbCallbackRepo.save('test-user', { callId: session.id, resumeAt: Date.now() + 60000 });

    const { RecoveryManager } = await import('../voicebridge/recovery-manager.js');
    const recovery = new RecoveryManager(dbSessionRepo, dbCallbackRepo, memSessionRepo, memCallbackRepo);
    const result = await recovery.loadFromDatabase();

    expect(result.sessions).toBe(1);
    expect(result.callbacks).toBe(1);

    const memSessions = await memSessionRepo.list();
    expect(memSessions.length).toBe(1);
    expect(memSessions[0]?.id).toBe(session.id);

    const memCallbacks = await memCallbackRepo.list();
    expect(memCallbacks.length).toBe(1);
    expect(memCallbacks[0]?.callId).toBe(session.id);
  });
});
