import type { Pool, PoolClient, QueryResultRow } from 'pg';
import type { VoiceCallSession } from '../types.js';
import type { SessionRepository } from './session-repository.js';
import { RepositoryError } from './errors.js';
import { logger } from '../../common/logger.js';

interface SessionRow extends QueryResultRow {
  id: string;
  user_id: string;
  status: string;
  data: Record<string, unknown>;
  created_at: string;
  connected_at: string | null;
  completed_at: string | null;
  paused_at: string | null;
  resumed_at: string | null;
  retention_expires_at: string | null;
}

function rowToSession(row: SessionRow): VoiceCallSession {
  const data = row.data as unknown as VoiceCallSession;
  return {
    ...data,
    id: row.id,
    userId: row.user_id,
    status: row.status as VoiceCallSession['status'],
    createdAt: row.created_at,
    connectedAt: row.connected_at ?? undefined,
    completedAt: row.completed_at ?? undefined,
    pausedAt: row.paused_at ?? undefined,
    resumedAt: row.resumed_at ?? undefined,
    retentionExpiresAt: row.retention_expires_at ?? undefined,
  };
}

function sessionToRow(session: VoiceCallSession): Record<string, unknown> {
  const { ...data } = session;
  return {
    id: session.id,
    user_id: session.userId,
    status: session.status,
    data: JSON.stringify(data),
    created_at: session.createdAt,
    connected_at: session.connectedAt ?? null,
    completed_at: session.completedAt ?? null,
    paused_at: session.pausedAt ?? null,
    resumed_at: session.resumedAt ?? null,
    retention_expires_at: session.retentionExpiresAt ?? null,
  };
}

export class DatabaseSessionRepository implements SessionRepository {
  private txClient: PoolClient | null = null;

  constructor(private pool: Pool) {}

  private async query<T extends QueryResultRow>(text: string, params?: unknown[]): Promise<import('pg').QueryResult<T>> {
    if (this.txClient) {
      return this.txClient.query<T>(text, params);
    }
    return this.pool.query<T>(text, params);
  }

  async transaction<T>(fn: () => Promise<T>): Promise<T> {
    if (this.txClient) {
      return fn();
    }
    const client = await this.pool.connect();
    this.txClient = client;
    try {
      await client.query('BEGIN');
      const result = await fn();
      await client.query('COMMIT');
      return result;
    } catch (err) {
      try {
        await client.query('ROLLBACK');
      } catch (rollbackErr) {
        logger.error({ err: rollbackErr }, '[DB] transaction rollback failed');
      }
      throw err instanceof RepositoryError ? err : new RepositoryError(`Transaction failed`, err);
    } finally {
      this.txClient = null;
      client.release();
    }
  }

  async findById(callId: string): Promise<VoiceCallSession | undefined> {
    try {
      const result = await this.query<SessionRow>(
        'SELECT * FROM sessions WHERE id = $1',
        [callId],
      );
      return result.rows[0] ? rowToSession(result.rows[0]) : undefined;
    } catch (cause) {
      throw new RepositoryError(`Failed to find session by id: ${callId}`, cause);
    }
  }

  async findByUserId(userId: string): Promise<VoiceCallSession[]> {
    try {
      const result = await this.query<SessionRow>(
        'SELECT * FROM sessions WHERE user_id = $1 ORDER BY created_at DESC',
        [userId],
      );
      return result.rows.map(rowToSession);
    } catch (cause) {
      throw new RepositoryError(`Failed to find sessions for user: ${userId}`, cause);
    }
  }

  async findByAgentId(agentId: string): Promise<VoiceCallSession[]> {
    try {
      // The sessions table keeps agent_id inside the JSONB data blob (no
      // dedicated column); the ->' '>' operator reaches it without a cast.
      const result = await this.query<SessionRow>(
        "SELECT * FROM sessions WHERE data->>'agentId' = $1 ORDER BY created_at DESC",
        [agentId],
      );
      return result.rows.map(rowToSession);
    } catch (cause) {
      throw new RepositoryError(`Failed to find sessions for agent: ${agentId}`, cause);
    }
  }

  async list(): Promise<VoiceCallSession[]> {
    try {
      const result = await this.query<SessionRow>(
        'SELECT * FROM sessions ORDER BY created_at DESC',
      );
      return result.rows.map(rowToSession);
    } catch (cause) {
      throw new RepositoryError('Failed to list sessions', cause);
    }
  }

  async create(session: VoiceCallSession): Promise<void> {
    try {
      const row = sessionToRow(session);
      const columns = Object.keys(row).join(', ');
      const values = Object.values(row);
      const placeholders = values.map((_, i) => `$${i + 1}`).join(', ');

      await this.query(
        `INSERT INTO sessions (${columns}) VALUES (${placeholders})`,
        values,
      );
    } catch (cause) {
      throw new RepositoryError(`Failed to create session: ${session.id}`, cause);
    }
  }

  async save(session: VoiceCallSession): Promise<void> {
    try {
      const row = sessionToRow(session);
      const columns = Object.keys(row);
      const values = Object.values(row);
      const placeholders = columns.map((_, i) => `$${i + 1}`).join(', ');
      const setClause = columns
        .map((col, i) => `${col} = $${i + 1}`)
        .join(', ');

      await this.query(
        `INSERT INTO sessions (${columns.join(', ')}) VALUES (${placeholders})
         ON CONFLICT (id) DO UPDATE SET ${setClause}`,
        values,
      );
    } catch (cause) {
      throw new RepositoryError(`Failed to save session: ${session.id}`, cause);
    }
  }

  async delete(callId: string): Promise<VoiceCallSession | undefined> {
    try {
      const result = await this.query<SessionRow>(
        'DELETE FROM sessions WHERE id = $1 RETURNING *',
        [callId],
      );
      return result.rows[0] ? rowToSession(result.rows[0]) : undefined;
    } catch (cause) {
      throw new RepositoryError(`Failed to delete session: ${callId}`, cause);
    }
  }
}
