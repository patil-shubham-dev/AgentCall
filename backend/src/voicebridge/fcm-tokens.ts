import type { Pool } from 'pg';
import { logger } from '../common/logger.js';

/**
 * FCM device-token registry. Mirrors phone-tokens.ts (same DB-backed + in-memory
 * fallback pattern) but keyed for the ring use case: the backend needs
 * userId → fcmToken to push a ring, and token → userId only for cleanup when
 * FCM reports the token dead (404 / UNREGISTERED).
 */

interface FcmTokenEntry {
  token: string;
  userId: string;
  createdAt: number;
}

const byToken = new Map<string, FcmTokenEntry>();
const byUser = new Map<string, string>();

let dbPool: Pool | undefined;
let dbEnabled = false;

// FCM tokens rotate (app reinstall / token refresh); anything older than this
// is dropped by the cleanup sweep. The app re-registers on every launch, so a
// healthy deployment keeps refreshing well inside this window.
const TOKEN_TTL_MS = 30 * 24 * 60 * 60 * 1000;
const CLEANUP_INTERVAL_MS = 60 * 60 * 1000;

const TABLE = 'fcm_tokens';

export async function initializeFcmTokens(pool?: Pool): Promise<void> {
  dbPool = pool;
  if (pool) {
    try {
      await pool.query(`
        CREATE TABLE IF NOT EXISTS ${TABLE} (
          user_id    TEXT PRIMARY KEY,
          token      TEXT NOT NULL,
          created_at BIGINT NOT NULL
        )
      `);
      dbEnabled = true;
      logger.info('[fcm-tokens] database persistence enabled');
    } catch (err) {
      logger.warn({ err }, '[fcm-tokens] database setup failed, falling back to in-memory');
      dbEnabled = false;
    }
  }
}

setInterval(() => {
  const now = Date.now();
  if (dbEnabled && dbPool) {
    dbPool.query(`DELETE FROM ${TABLE} WHERE created_at < $1`, [now - TOKEN_TTL_MS]).catch(() => {});
  }
  for (const [token, entry] of byToken) {
    if (now - entry.createdAt > TOKEN_TTL_MS) {
      byToken.delete(token);
      if (byUser.get(entry.userId) === token) byUser.delete(entry.userId);
    }
  }
}, CLEANUP_INTERVAL_MS).unref();

export async function registerFcmToken(userId: string, token: string): Promise<void> {
  const entry: FcmTokenEntry = { token, userId, createdAt: Date.now() };
  if (dbEnabled && dbPool) {
    try {
      await dbPool.query(
        `INSERT INTO ${TABLE} (user_id, token, created_at) VALUES ($1, $2, $3)
         ON CONFLICT (user_id) DO UPDATE SET token = EXCLUDED.token, created_at = EXCLUDED.created_at`,
        [userId, token, entry.createdAt],
      );
    } catch (err) {
      logger.warn({ err }, '[fcm-tokens] db upsert failed, falling back to in-memory');
      setInMemory(entry);
    }
  } else {
    setInMemory(entry);
  }
  logger.info({ userId }, '[fcm-tokens] token registered');
}

function setInMemory(entry: FcmTokenEntry): void {
  const previous = byUser.get(entry.userId);
  if (previous) byToken.delete(previous);
  byToken.set(entry.token, entry);
  byUser.set(entry.userId, entry.token);
}

/** Look up the FCM token for a user (null when none registered). */
export async function getFcmToken(userId: string): Promise<string | null> {
  if (dbEnabled && dbPool) {
    try {
      const result = await dbPool.query<{ token: string; created_at: number }>(
        `SELECT token, created_at FROM ${TABLE} WHERE user_id = $1`,
        [userId],
      );
      const row = result.rows[0];
      if (!row) return null;
      if (Date.now() - row.created_at > TOKEN_TTL_MS) {
        await dbPool.query(`DELETE FROM ${TABLE} WHERE user_id = $1`, [userId]).catch(() => {});
        return null;
      }
      return row.token;
    } catch (err) {
      logger.warn({ err }, '[fcm-tokens] db lookup failed, falling back to in-memory');
    }
  }
  const token = byUser.get(userId);
  if (!token) return null;
  const entry = byToken.get(token);
  if (!entry) return null;
  if (Date.now() - entry.createdAt > TOKEN_TTL_MS) {
    byToken.delete(token);
    byUser.delete(userId);
    return null;
  }
  return token;
}

/** Drop a dead token (FCM 404 / UNREGISTERED). Token-keyed so a rotated token
 *  doesn't leave a stale userId → oldToken mapping behind. */
export async function removeFcmToken(token: string): Promise<void> {
  if (dbEnabled && dbPool) {
    try {
      await dbPool.query(`DELETE FROM ${TABLE} WHERE token = $1`, [token]);
    } catch (_) {}
  }
  const entry = byToken.get(token);
  if (entry) {
    byToken.delete(token);
    if (byUser.get(entry.userId) === token) byUser.delete(entry.userId);
  }
  logger.info({ userId: entry?.userId }, '[fcm-tokens] token removed (stale/UNREGISTERED)');
}
