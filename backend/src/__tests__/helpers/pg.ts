import { describe } from 'vitest';
import { Pool } from 'pg';

/**
 * Shared DB-gating helpers (v1 integration suites).
 *
 * These suites run ONLY when DATABASE_URL is set — no test DB, no run. The
 * `describeDb`/`makeTestPool` pair previously lived in the v2 engine's
 * `v2-pg.ts` helper; when the dormant v2 engine was deleted (2026-09-23,
 * tag `v2-dormant-archive`) this file was broken out so the v1 integration
 * coverage that genuinely needs a live Postgres survives.
 */

const hasTestDb = !!process.env.DATABASE_URL;

/** Skip the whole suite unless DATABASE_URL points at a real test database. */
export const describeDb = hasTestDb ? describe : describe.skip;

/**
 * A dedicated pool for the test run. The caller is responsible for closing
 * it (afterAll). Kept separate from any app pool so tests can truncate/
 * reset tables freely (see resetV1Db in v1-pg.ts).
 */
export function makeTestPool(): Pool {
  return new Pool({
    connectionString: process.env.DATABASE_URL,
    max: 2,
    idleTimeoutMillis: 5_000,
    connectionTimeoutMillis: 5_000,
  });
}
