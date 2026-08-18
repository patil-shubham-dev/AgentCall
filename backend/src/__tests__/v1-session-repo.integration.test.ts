import { it, expect, beforeAll, afterAll, beforeEach } from 'vitest';
import { DatabaseSessionRepository } from '../voicebridge/repositories/index.js';
import type { VoiceCallSession } from '../voicebridge/types.js';
import { describeDb, makeTestPool } from './helpers/v2-pg.js';
import { resetV1Db } from './helpers/v1-pg.js';
import type { Pool } from 'pg';

/**
 * DB-backed validation of `DatabaseSessionRepository.findByAgentId`.
 *
 * The v1 `sessions` table stores the whole session inside the JSONB `data`
 * blob (no dedicated `agent_id` column), and findByAgentId reaches it via
 * `data->>'agentId'`. These tests exist because the previous integration run
 * only exercised the in-memory repo — the JSONB query itself had no coverage
 * against a real Postgres. Gated on DATABASE_URL like the other DB suites.
 */

function createTestSession(overrides: Partial<VoiceCallSession> = {}): VoiceCallSession {
  const now = new Date().toISOString();
  return {
    id: overrides.id ?? `db-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    userId: overrides.userId ?? 'test-user',
    agentId: 'agent-a',
    status: 'pending',
    priority: 'normal',
    reason: 'input_required',
    context: { summary: 'Test session' },
    messages: [],
    createdAt: now,
    ...overrides,
  };
}

describeDb('DatabaseSessionRepository (v1 sessions table, real Postgres)', () => {
  let pool: Pool;
  let repo: DatabaseSessionRepository;

  beforeAll(async () => {
    pool = makeTestPool();
    await resetV1Db(pool);
    repo = new DatabaseSessionRepository(pool);
  });

  afterAll(async () => {
    await pool.end();
  });

  beforeEach(async () => {
    await pool.query('TRUNCATE sessions');
  });

  it('finds sessions by agent id through the JSONB data->>\'agentId\' query', async () => {
    await repo.create(createTestSession({ id: 'call-1', agentId: 'agent-a' }));
    await repo.create(createTestSession({ id: 'call-2', agentId: 'agent-b' }));
    await repo.create(createTestSession({ id: 'call-3', agentId: 'agent-a' }));

    const found = await repo.findByAgentId('agent-a');
    const ids = found.map((s) => s.id).sort();
    expect(ids).toEqual(['call-1', 'call-3']);

    const other = await repo.findByAgentId('agent-b');
    expect(other.map((s) => s.id)).toEqual(['call-2']);
  });

  it('returns an empty list for an unknown agent', async () => {
    await repo.create(createTestSession({ id: 'call-1', agentId: 'agent-a' }));
    expect(await repo.findByAgentId('agent-ghost')).toEqual([]);
  });

  it('matches exactly, not by substring', async () => {
    // `data->>'agentId'` is exact string equality — a session for 'agent-alpha'
    // must NOT match a lookup for 'agent' or 'agent-a'.
    await repo.create(createTestSession({ id: 'call-1', agentId: 'agent-alpha' }));
    await repo.create(createTestSession({ id: 'call-2', agentId: 'agent-a' }));

    expect((await repo.findByAgentId('agent')).map((s) => s.id)).toEqual([]);
    const found = await repo.findByAgentId('agent-a');
    expect(found.map((s) => s.id)).toEqual(['call-2']);
  });

  it('orders results by created_at DESC', async () => {
    await repo.create(
      createTestSession({ id: 'old', agentId: 'agent-a', createdAt: '2026-01-01T00:00:00.000Z' }),
    );
    await repo.create(
      createTestSession({ id: 'mid', agentId: 'agent-a', createdAt: '2026-02-01T00:00:00.000Z' }),
    );
    await repo.create(
      createTestSession({ id: 'new', agentId: 'agent-a', createdAt: '2026-03-01T00:00:00.000Z' }),
    );

    const found = await repo.findByAgentId('agent-a');
    expect(found.map((s) => s.id)).toEqual(['new', 'mid', 'old']);
  });

  it('round-trips the full session through the JSONB blob', async () => {
    const session = createTestSession({
      id: 'call-1',
      userId: 'user-7',
      agentId: 'agent-a',
      status: 'aborted',
      priority: 'high',
      reason: 'urgent',
      context: { summary: 'Round trip', options: ['yes', 'no'], taskId: 'task-1' },
      messages: [
        {
          id: 'm1',
          role: 'ai',
          type: 'text',
          content: 'Hello!',
          createdAt: '2026-01-01T00:00:00.000Z',
        },
        {
          id: 'm2',
          role: 'user',
          type: 'text',
          content: 'Hi',
          clientMessageId: 'cm-2',
          createdAt: '2026-01-01T00:00:01.000Z',
        },
      ],
      result: {
        transcriptSummary: 'Quick hello',
        userResponse: 'Hi',
        decision: 'approved',
        selectedOption: 'yes',
        sentiment: 'positive',
        actionItems: ['call back'],
      },
      connectedAt: '2026-01-01T00:00:02.000Z',
      pausedAt: '2026-01-01T00:00:03.000Z',
      resumedAt: '2026-01-01T00:00:04.000Z',
      completedAt: '2026-01-01T00:00:05.000Z',
      retentionExpiresAt: '2026-02-01T00:00:00.000Z',
    });
    await repo.create(session);

    const found = await repo.findByAgentId('agent-a');
    expect(found).toHaveLength(1);
    // TIMESTAMPTZ columns come back as Date objects (rowToSession overrides the
    // JSONB blob's string values with the typed column), so compare timestamps
    // via ISO normalization instead of strict equality.
    const actual = found[0];
    expect(actual).toBeDefined();
    if (!actual) throw new Error('expected one session');
    const iso = (v: string | Date | undefined) =>
      v === undefined ? undefined : new Date(v).toISOString();
    expect(actual.id).toBe(session.id);
    expect(actual.userId).toBe(session.userId);
    expect(actual.agentId).toBe(session.agentId);
    expect(actual.status).toBe(session.status);
    expect(actual.priority).toBe(session.priority);
    expect(actual.reason).toBe(session.reason);
    expect(actual.context).toEqual(session.context);
    expect(actual.messages).toEqual(session.messages);
    expect(actual.result).toEqual(session.result);
    expect(iso(actual.createdAt)).toBe(session.createdAt);
    expect(iso(actual.connectedAt)).toBe(session.connectedAt);
    expect(iso(actual.pausedAt)).toBe(session.pausedAt);
    expect(iso(actual.resumedAt)).toBe(session.resumedAt);
    expect(iso(actual.completedAt)).toBe(session.completedAt);
    expect(iso(actual.retentionExpiresAt)).toBe(session.retentionExpiresAt);
  });

  it('findById and findByAgentId agree on the same stored row', async () => {
    const session = createTestSession({ id: 'call-1', agentId: 'agent-a', status: 'active' });
    await repo.create(session);

    const byAgent = await repo.findByAgentId('agent-a');
    const byId = await repo.findById('call-1');
    expect(byAgent).toHaveLength(1);
    expect(byAgent[0]).toEqual(byId);
    expect(byId?.agentId).toBe('agent-a');
  });

  it('save (upsert) keeps the row findable by agent id with updated status', async () => {
    const session = createTestSession({ id: 'call-1', agentId: 'agent-a', status: 'pending' });
    await repo.create(session);
    session.status = 'completed';
    await repo.save(session);

    const found = await repo.findByAgentId('agent-a');
    expect(found).toHaveLength(1);
    expect(found[0]?.status).toBe('completed');
    expect(found[0]?.agentId).toBe('agent-a');
  });

  it('delete removes the row from agent lookups', async () => {
    await repo.create(createTestSession({ id: 'call-1', agentId: 'agent-a' }));
    await repo.delete('call-1');
    expect(await repo.findByAgentId('agent-a')).toEqual([]);
  });
});
