import crypto from 'node:crypto';
import type { Pool } from 'pg';
import { logger } from '../common/logger.js';

interface PhoneTokenEntry {
  token: string;
  userId: string;
  createdAt: number;
}

const inMemoryTokens = new Map<string, PhoneTokenEntry>();

let dbPool: Pool | undefined;
let dbEnabled = false;

const TOKEN_TTL_MS = 7 * 24 * 60 * 60 * 1000;
const CLEANUP_INTERVAL_MS = 60 * 60 * 1000;

const TABLE = 'phone_tokens';

export async function initializePhoneTokens(pool?: Pool): Promise<void> {
  dbPool = pool;
  if (pool) {
    try {
      await pool.query(`
        CREATE TABLE IF NOT EXISTS ${TABLE} (
          token     TEXT PRIMARY KEY,
          user_id   TEXT NOT NULL,
          created_at BIGINT NOT NULL
        )
      `);
      dbEnabled = true;
      logger.info('[phone-tokens] database persistence enabled');
    } catch (err) {
      logger.warn({ err }, '[phone-tokens] database setup failed, falling back to in-memory');
      dbEnabled = false;
    }
  }
}

const cleanupTimer = setInterval(() => {
  const now = Date.now();
  if (dbEnabled && dbPool) {
    dbPool.query(`DELETE FROM ${TABLE} WHERE created_at < $1`, [now - TOKEN_TTL_MS]).catch(() => {});
  }
  for (const [token, entry] of inMemoryTokens) {
    if (now - entry.createdAt > TOKEN_TTL_MS) {
      inMemoryTokens.delete(token);
    }
  }
}, CLEANUP_INTERVAL_MS).unref();

export async function createPhoneToken(userId: string): Promise<string> {
  const token = crypto.randomBytes(32).toString('hex');
  const entry: PhoneTokenEntry = { token, userId, createdAt: Date.now() };
  if (dbEnabled && dbPool) {
    try {
      await dbPool.query(`INSERT INTO ${TABLE} (token, user_id, created_at) VALUES ($1, $2, $3)`, [
        token, userId, entry.createdAt,
      ]);
    } catch (err) {
      logger.warn({ err }, '[phone-tokens] db insert failed, falling back to in-memory');
      inMemoryTokens.set(token, entry);
    }
  } else {
    inMemoryTokens.set(token, entry);
  }
  logger.info({ userId }, '[phone-tokens] token created');
  return token;
}

export async function validatePhoneToken(token: string): Promise<string | null> {
  if (dbEnabled && dbPool) {
    try {
      const result = await dbPool.query<{ user_id: string; created_at: number }>(
        `SELECT user_id, created_at FROM ${TABLE} WHERE token = $1`,
        [token],
      );
      const row = result.rows[0];
      if (!row) return null;
      if (Date.now() - row.created_at > TOKEN_TTL_MS) {
        await dbPool.query(`DELETE FROM ${TABLE} WHERE token = $1`, [token]).catch(() => {});
        return null;
      }
      return row.user_id;
    } catch (err) {
      logger.warn({ err }, '[phone-tokens] db lookup failed, falling back to in-memory');
    }
  }
  const entry = inMemoryTokens.get(token);
  if (!entry) return null;
  if (Date.now() - entry.createdAt > TOKEN_TTL_MS) {
    inMemoryTokens.delete(token);
    return null;
  }
  return entry.userId;
}

export async function revokePhoneToken(token: string): Promise<void> {
  if (dbEnabled && dbPool) {
    try {
      await dbPool.query(`DELETE FROM ${TABLE} WHERE token = $1`, [token]);
    } catch (_) {}
  }
  inMemoryTokens.delete(token);
}
