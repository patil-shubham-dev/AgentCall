import type { Pool, PoolClient, QueryResultRow } from 'pg';
import type { CallbackRepository, CallbackData, CallbackEntry } from './callback-repository.js';
import { RepositoryError } from './errors.js';
import { logger } from '../../common/logger.js';

interface CallbackRow extends QueryResultRow {
  user_id: string;
  call_id: string;
  resume_at: string;
}

function rowToData(row: CallbackRow): CallbackData {
  return {
    callId: row.call_id,
    // node-postgres returns BIGINT (int8) columns as strings — epoch-ms
    // timestamps fit safely in JS numbers, so normalize at the boundary.
    resumeAt: Number(row.resume_at),
  };
}

export class DatabaseCallbackRepository implements CallbackRepository {
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

  async findByUserId(userId: string): Promise<CallbackData | undefined> {
    try {
      const result = await this.query<CallbackRow>(
        'SELECT user_id, call_id, resume_at FROM callbacks WHERE user_id = $1',
        [userId],
      );
      return result.rows[0] ? rowToData(result.rows[0]) : undefined;
    } catch (cause) {
      throw new RepositoryError(`Failed to find callback for user: ${userId}`, cause);
    }
  }

  async save(userId: string, data: CallbackData): Promise<void> {
    try {
      await this.query(
        `INSERT INTO callbacks (user_id, call_id, resume_at)
         VALUES ($1, $2, $3)
         ON CONFLICT (user_id) DO UPDATE SET call_id = $2, resume_at = $3`,
        [userId, data.callId, data.resumeAt],
      );
    } catch (cause) {
      throw new RepositoryError(`Failed to save callback for user: ${userId}`, cause);
    }
  }

  async delete(userId: string): Promise<void> {
    try {
      await this.query(
        'DELETE FROM callbacks WHERE user_id = $1',
        [userId],
      );
    } catch (cause) {
      throw new RepositoryError(`Failed to delete callback for user: ${userId}`, cause);
    }
  }

  async list(): Promise<CallbackEntry[]> {
    try {
      const result = await this.query<CallbackRow>(
        'SELECT user_id, call_id, resume_at FROM callbacks',
      );
      return result.rows.map((row) => ({
        userId: row.user_id,
        callId: row.call_id,
        resumeAt: Number(row.resume_at),
      }));
    } catch (cause) {
      throw new RepositoryError('Failed to list callbacks', cause);
    }
  }
}
