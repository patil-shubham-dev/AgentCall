import crypto from 'node:crypto';
import type { Pool } from 'pg';
import { logger } from '../common/logger.js';

/**
 * Named API keys for AI clients (MCP connectors).
 *
 * Each key maps to an AI display name ("Claude Desktop", "Opencode", ...).
 * Only the SHA-256 hash of a key is ever stored — the plaintext is returned
 * exactly once at creation time. The legacy SERVICE_TOKEN continues to work
 * as the default identity ("AI Agent") for backward compatibility.
 */

const TABLE = 'ai_keys';

interface AiKeyRow {
  id: string;
  name: string;
  key_hash: string;
  created_at: string;
  last_used_at: string | null;
}

export interface AiKeyInfo {
  id: string;
  name: string;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface AiKeyStatus {
  id: string;
  name: string;
  createdAt: string;
  lastSeenAt: string | null;
  online: boolean;
  busy: boolean;
}

/**
 * An AI is considered "online" if it has authenticated within this window.
 * last_used_at is updated on every key resolution, so it doubles as last_seen.
 */
export const ONLINE_WINDOW_MS = 5 * 60 * 1000;

export interface ResolvedAiKey {
  id: string;
  name: string;
}

interface MemoryKeyEntry {
  id: string;
  name: string;
  keyHash: string;
  createdAt: string;
  lastUsedAt: string | null;
}

let dbPool: Pool | null = null;
const memoryKeys = new Map<string, MemoryKeyEntry>();

export const DEFAULT_AGENT_NAME = 'AI Agent';

export function hashKey(key: string): string {
  return crypto.createHash('sha256').update(key).digest('hex');
}

export async function initializeAiKeys(pool?: Pool): Promise<void> {
  dbPool = pool ?? null;
  if (pool) {
    await pool.query(`
      CREATE TABLE IF NOT EXISTS ${TABLE} (
        id TEXT PRIMARY KEY,
        name TEXT NOT NULL,
        key_hash TEXT NOT NULL UNIQUE,
        created_at BIGINT NOT NULL,
        last_used_at BIGINT
      )
    `);
  } else {
    memoryKeys.clear();
  }
  logger.info({ mode: pool ? 'database' : 'memory' }, '[AiKeys] initialized');
}

async function upsertUsed(keyId: string, usedAt: string): Promise<void> {
  if (dbPool) {
    try {
      await dbPool.query(`UPDATE ${TABLE} SET last_used_at = $1 WHERE id = $2`, [usedAt, keyId]);
    } catch (err) {
      logger.warn({ err, keyId }, '[AiKeys] failed to update last_used_at');
    }
    return;
  }
  const entry = memoryKeys.get(keyId);
  if (entry) {
    entry.lastUsedAt = usedAt;
  }
}

export async function createAiKey(name: string): Promise<{ id: string; name: string; key: string }> {
  const id = crypto.randomUUID();
  const key = `ac_${crypto.randomBytes(24).toString('hex')}`;
  const keyHash = hashKey(key);
  const createdAt = Date.now();

  if (dbPool) {
    try {
      await dbPool.query(
        `INSERT INTO ${TABLE} (id, name, key_hash, created_at) VALUES ($1, $2, $3, $4)`,
        [id, name, keyHash, createdAt],
      );
    } catch (cause) {
      throw new Error(`Failed to create AI key: ${cause instanceof Error ? cause.message : String(cause)}`);
    }
  } else {
    memoryKeys.set(id, { id, name, keyHash, createdAt: String(createdAt), lastUsedAt: null });
  }

  logger.info({ keyId: id, name }, '[AiKeys] created');
  return { id, name, key };
}

export async function resolveAiKey(token: string): Promise<ResolvedAiKey | null> {
  const keyHash = hashKey(token);

  if (dbPool) {
    try {
      const result = await dbPool.query<AiKeyRow>(
        `SELECT id, name FROM ${TABLE} WHERE key_hash = $1`,
        [keyHash],
      );
      const row = result.rows[0];
      if (!row) return null;
      void upsertUsed(row.id, String(Date.now()));
      return { id: row.id, name: row.name };
    } catch (cause) {
      logger.error({ cause }, '[AiKeys] resolve failed');
      return null;
    }
  }

  for (const entry of memoryKeys.values()) {
    if (entry.keyHash === keyHash) {
      void upsertUsed(entry.id, String(Date.now()));
      return { id: entry.id, name: entry.name };
    }
  }
  return null;
}

export async function listAiKeys(): Promise<AiKeyInfo[]> {
  if (dbPool) {
    try {
      const result = await dbPool.query<AiKeyRow>(
        `SELECT id, name, created_at, last_used_at FROM ${TABLE} ORDER BY created_at DESC`,
      );
      return result.rows.map((row) => ({
        id: row.id,
        name: row.name,
        createdAt: new Date(Number(row.created_at)).toISOString(),
        lastUsedAt: row.last_used_at ? new Date(Number(row.last_used_at)).toISOString() : null,
      }));
    } catch (cause) {
      throw new Error(`Failed to list AI keys: ${cause instanceof Error ? cause.message : String(cause)}`);
    }
  }
  return Array.from(memoryKeys.values())
    .map((e) => ({ id: e.id, name: e.name, createdAt: new Date(Number(e.createdAt)).toISOString(), lastUsedAt: e.lastUsedAt ? new Date(Number(e.lastUsedAt)).toISOString() : null }))
    .sort((a, b) => (a.createdAt < b.createdAt ? 1 : -1));
}

export async function deleteAiKey(id: string): Promise<boolean> {
  if (dbPool) {
    try {
      const result = await dbPool.query(`DELETE FROM ${TABLE} WHERE id = $1`, [id]);
      return (result.rowCount ?? 0) > 0;
    } catch (cause) {
      throw new Error(`Failed to delete AI key: ${cause instanceof Error ? cause.message : String(cause)}`);
    }
  }
  return memoryKeys.delete(id);
}

/**
 * Availability status per AI key.
 *
 * - `online`: the key has been used (auth'ed) within ONLINE_WINDOW_MS.
 * - `busy`: an active call is associated with this agent name.
 */
export async function listAiKeyStatuses(activeAgentNames: Set<string>): Promise<AiKeyStatus[]> {
  const now = Date.now();
  const keys = await listAiKeys();
  return keys.map((key) => {
    const lastSeenAt = key.lastUsedAt;
    const lastSeenMs = lastSeenAt ? Date.parse(lastSeenAt) : NaN;
    return {
      id: key.id,
      name: key.name,
      createdAt: key.createdAt,
      lastSeenAt,
      online: Number.isFinite(lastSeenMs) && now - lastSeenMs <= ONLINE_WINDOW_MS,
      busy: activeAgentNames.has(key.name),
    };
  });
}
