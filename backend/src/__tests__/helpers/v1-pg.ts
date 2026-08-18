import { Pool } from 'pg';

/**
 * v1 persistence helpers (voicebridge `sessions` table).
 *
 * The v1 DDL has no migration runner — `schema.sql` is a reference document
 * (AGENTS.md points to Knex, but this repo has none for v1). So the DB-backed
 * tests create the table themselves from the same DDL, keeping it a faithful
 * copy of `backend/src/voicebridge/repositories/schema.sql`. If schema.sql
 * drifts, update both.
 *
 * These suites run ONLY when DATABASE_URL is set (see `v2-pg.ts`); the
 * consuming test file gates the whole describe block with `describeDb`.
 */

export const V1_SESSIONS_SCHEMA_SQL = `
CREATE TABLE IF NOT EXISTS sessions (
  id                  TEXT PRIMARY KEY,
  user_id             TEXT NOT NULL,
  status              TEXT NOT NULL,
  data                JSONB NOT NULL,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  connected_at        TIMESTAMPTZ,
  completed_at        TIMESTAMPTZ,
  paused_at           TIMESTAMPTZ,
  resumed_at          TIMESTAMPTZ,
  retention_expires_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_sessions_user_id ON sessions(user_id);
CREATE INDEX IF NOT EXISTS idx_sessions_status ON sessions(status);
`;

/** Applies the v1 sessions DDL idempotently (safe to run on every boot). */
export async function applyV1Schema(pool: Pool): Promise<void> {
  await pool.query(V1_SESSIONS_SCHEMA_SQL);
}

/** Creates the v1 schema and empties the sessions table for a clean slate. */
export async function resetV1Db(pool: Pool): Promise<void> {
  await applyV1Schema(pool);
  await pool.query('TRUNCATE sessions');
}
