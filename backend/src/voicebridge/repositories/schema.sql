-- AgentCall Persistence Schema
-- Target: PostgreSQL 16 (Neon Free Tier compatible)
-- This file is a reference. Migrations should be managed separately.
-- Domain model is authoritative over any archived schema docs.

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
CREATE INDEX IF NOT EXISTS idx_sessions_retention_expires
  ON sessions(retention_expires_at)
  WHERE retention_expires_at IS NOT NULL;

CREATE TABLE IF NOT EXISTS callbacks (
  user_id   TEXT PRIMARY KEY,
  call_id   TEXT NOT NULL,
  resume_at BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS phone_tokens (
  token     TEXT PRIMARY KEY,
  user_id   TEXT NOT NULL,
  created_at BIGINT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_phone_tokens_user_id ON phone_tokens(user_id);
