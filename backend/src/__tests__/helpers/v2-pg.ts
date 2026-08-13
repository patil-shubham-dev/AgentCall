import { Pool } from 'pg';
import { applyV2Schema } from '../../v2/db/schema.js';

/**
 * Integration-test helpers for the v2 durability stores (roadmap M3).
 * These suites run ONLY when DATABASE_URL is set (CI/developer provides one);
 * otherwise the whole describe block is skipped.
 *
 * resetV2Db TRUNCATEs the two v2 tables — safe here because this is a
 * dedicated local/dev test database, never a shared one.
 */

export const hasTestDb = Boolean(process.env.DATABASE_URL);

export const describeDb = hasTestDb ? describe : describe.skip;

export function makeTestPool(): Pool {
  return new Pool({ connectionString: process.env.DATABASE_URL, max: 5 });
}

export async function resetV2Db(pool: Pool): Promise<void> {
  await applyV2Schema(pool);
  await pool.query('TRUNCATE v2_events, v2_idempotency');
}
