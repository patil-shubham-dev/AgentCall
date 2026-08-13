import { Pool } from 'pg';
import { config } from '../common/config.js';
import { applyV2Schema } from '../v2/db/schema.js';

/**
 * `npm run db:up` — applies the v2 durability schema to the configured
 * database (idempotent: CREATE TABLE IF NOT EXISTS only, safe to re-run).
 * Uses plain `pg` (no ORM/migration framework is present in this repo).
 */
async function main(): Promise<void> {
  if (!config.database.url) {
    throw new Error('DATABASE_URL is required');
  }
  const pool = new Pool({ connectionString: config.database.url });
  try {
    await applyV2Schema(pool);
    console.log('[db:up] v2 schema applied (v2_events, v2_idempotency)');
  } finally {
    await pool.end();
  }
}

main().catch((err) => {
  console.error('[db:up] failed:', err);
  process.exit(1);
});
