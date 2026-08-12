# 05 — Database Schema (v2)

> **Deliverable:** 7 (database schema)
> Target: PostgreSQL 16 (Neon free tier / Hetzner / local). All timestamps `TIMESTAMPTZ`.

## 1. Design rules

1. **The event log is the source of truth** (`events`). Session view, transcripts, tool
   invocations, and media rows are *derived projections* that can be rebuilt by replay.
2. Every table carries `created_at`; mutable rows carry `updated_at` and an
   **`fsm_version`/`row_version`** for optimistic concurrency (v1 `data` JSONB blob is
   decomposed — no more opaque `sessions.data`).
3. JSONB retained only for genuinely free-form data (`context`, `result`, `payload`,
   `provider_config`).
4. Indexes serve the read paths: per-call ordering, per-user active-call lookups,
   per-consumer cursors, retention sweeps.
5. v1 tables (`sessions`, `callbacks`, `phone_tokens`) remain untouched for the compatibility
   period; v2 writes both where needed (see migration plan).

---

## 2. Schema

```sql
-- ── 1. Event log (truth) ─────────────────────────────────────────────────
CREATE TABLE events (
  event_id        UUID PRIMARY KEY,             -- UUID v7 (sortable)
  seq             BIGINT NOT NULL,              -- per-call monotonic sequence
  type            TEXT NOT NULL,                -- 'speech.final', 'call.completed', …
  version         INT  NOT NULL DEFAULT 1,
  call_id         TEXT NOT NULL REFERENCES calls(call_id) ON DELETE CASCADE,
  correlation_id  UUID NOT NULL,
  causation_id    UUID,
  occurred_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  actor_type      TEXT NOT NULL,                -- ai|user|system|device
  actor_identity  TEXT,
  payload         JSONB NOT NULL,               -- zod-validated at write
  partition_key   TEXT NOT NULL,                -- 'calls:<call_id>' (scaling)
  UNIQUE (call_id, seq)
);
CREATE INDEX idx_events_call_occurred  ON events(call_id, occurred_at);
CREATE INDEX idx_events_type_occurred  ON events(type, occurred_at);
CREATE INDEX idx_events_partition      ON events(partition_key, seq);

-- ── 2. Calls (derived session view) ──────────────────────────────────────
CREATE TABLE calls (
  call_id           TEXT PRIMARY KEY,           -- same id as v1 sessions where mapped
  user_id           TEXT NOT NULL,
  agent_id          TEXT NOT NULL,
  status            TEXT NOT NULL,              -- ringing|connecting|connected|paused|transferring|ending|completed|failed|archived
  phase             TEXT,                       -- listening|thinking|speaking (when connected)
  fsm_version       BIGINT NOT NULL DEFAULT 0,  -- optimistic concurrency
  priority          TEXT NOT NULL DEFAULT 'normal',
  reason            TEXT NOT NULL DEFAULT 'free_form',
  context           JSONB NOT NULL DEFAULT '{}'::jsonb,  -- task_id, summary, options, custom
  media_config      JSONB NOT NULL DEFAULT '{}'::jsonb,  -- transport/stt/tts/policy
  result            JSONB,                      -- outcome {decision, selected_option, …}
  transcript_seq    BIGINT NOT NULL DEFAULT 0,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ringing_at        TIMESTAMPTZ,
  connected_at      TIMESTAMPTZ,
  paused_at         TIMESTAMPTZ,
  resumed_at        TIMESTAMPTZ,
  ended_at          TIMESTAMPTZ,
  retention_expires_at TIMESTAMPTZ,
  archived_at       TIMESTAMPTZ
);
CREATE INDEX idx_calls_user_active  ON calls(user_id, status) WHERE status IN ('ringing','connecting','connected','paused','transferring');
CREATE INDEX idx_calls_agent_active ON calls(agent_id, status) WHERE status IN ('ringing','connecting','connected','paused','transferring');
CREATE INDEX idx_calls_retention    ON calls(retention_expires_at) WHERE retention_expires_at IS NOT NULL;

-- ── 3. Transcript (derived from speech.final / message.completed) ─────────
CREATE TABLE transcript_segments (
  segment_id      BIGSERIAL PRIMARY KEY,
  call_id         TEXT NOT NULL REFERENCES calls(call_id) ON DELETE CASCADE,
  seq             BIGINT NOT NULL,              -- = transcript_seq at commit
  role            TEXT NOT NULL,                -- user|ai|system
  type            TEXT NOT NULL,                -- speech|text|dtmf|event
  text            TEXT NOT NULL,
  start_ms        INT NOT NULL,                 -- call-relative ms
  end_ms          INT,
  confidence      REAL,                         -- STT confidence 0..1
  utterance_id    TEXT,                         -- user utterances
  message_id      TEXT,                         -- AI messages
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (call_id, seq)
);
CREATE INDEX idx_tseg_call ON transcript_segments(call_id, seq);
CREATE INDEX idx_tseg_search ON transcript_segments USING GIN (to_tsvector('simple', text));

-- ── 4. AI turn / waiting state (lease, no expiry semantics) ───────────────
CREATE TABLE turn_leases (
  call_id        TEXT PRIMARY KEY REFERENCES calls(call_id) ON DELETE CASCADE,
  count          INT NOT NULL DEFAULT 0,
  active_until   TIMESTAMPTZ,                    -- informational only; NOT a conversation cap
  last_active_at TIMESTAMPTZ
);

-- ── 5. Tool invocations ──────────────────────────────────────────────────
CREATE TABLE tool_invocations (
  invocation_id   TEXT PRIMARY KEY,
  call_id         TEXT NOT NULL REFERENCES calls(call_id) ON DELETE CASCADE,
  tool_name       TEXT NOT NULL,
  status          TEXT NOT NULL,                 -- requested|running|completed|failed
  input           JSONB NOT NULL,
  output          JSONB,
  started_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  finished_at     TIMESTAMPTZ,
  latency_ms      INT
);
CREATE INDEX idx_tool_call ON tool_invocations(call_id, started_at);

-- ── 6. Media sessions & recordings ───────────────────────────────────────
CREATE TABLE media_sessions (
  media_id        TEXT PRIMARY KEY,
  call_id         TEXT NOT NULL REFERENCES calls(call_id) ON DELETE CASCADE,
  provider        TEXT NOT NULL,                 -- mobile|webrtc|sip|browser
  transport_type  TEXT NOT NULL,                 -- ws_audio|webrtc_peer|sip_dialog
  state           TEXT NOT NULL,                 -- attaching|connected|reconnecting|closed
  remote_sdp      TEXT,
  connected_at    TIMESTAMPTZ,
  closed_at       TIMESTAMPTZ,
  close_reason    TEXT
);
CREATE INDEX idx_media_call ON media_sessions(call_id);

CREATE TABLE recordings (
  recording_id    TEXT PRIMARY KEY,
  call_id         TEXT NOT NULL REFERENCES calls(call_id) ON DELETE CASCADE,
  uri             TEXT NOT NULL,                 -- s3://… / file path
  encrypted       BOOLEAN NOT NULL DEFAULT TRUE,
  key_id          TEXT,                          -- envelope-encryption key ref (never the key)
  duration_ms     INT,
  size_bytes      BIGINT,
  started_at      TIMESTAMPTZ,
  ended_at        TIMESTAMPTZ
);
CREATE INDEX idx_rec_call ON recordings(call_id);

-- ── 7. Callbacks / timers (v1 compatible) ────────────────────────────────
CREATE TABLE callbacks (
  user_id    TEXT PRIMARY KEY,
  call_id    TEXT NOT NULL,
  resume_at  BIGINT NOT NULL,                    -- epoch ms (v1 shape retained)
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── 8. Auth ─────────────────────────────────────────────────────────────
-- v1 tables retained:
--   phone_tokens(token PK, user_id, created_at)
--   ai_keys(id, name, key_hash, created_at, last_seen_at)   [extended below]
ALTER TABLE ai_keys ADD COLUMN IF NOT EXISTS scopes    TEXT[] NOT NULL DEFAULT '{calls:rw}';
ALTER TABLE ai_keys ADD COLUMN IF NOT EXISTS expires_at TIMESTAMPTZ;
ALTER TABLE ai_keys ADD COLUMN IF NOT EXISTS max_calls  INT;

CREATE TABLE api_keys (                            -- service-level, scoped
  key_id        TEXT PRIMARY KEY,
  name          TEXT NOT NULL,
  key_hash      TEXT NOT NULL,                     -- argon2id/bcrypt of the key
  scopes        TEXT[] NOT NULL DEFAULT '{service:ro}',
  created_by    TEXT,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_used_at  TIMESTAMPTZ,
  revoked_at    TIMESTAMPTZ
);

-- ── 9. Consumer cursors (event resume / dedupe) ──────────────────────────
CREATE TABLE event_cursors (
  consumer_id    TEXT NOT NULL,                    -- ai identity, device, ops
  call_id        TEXT NOT NULL,
  last_event_id  UUID NOT NULL,
  updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (consumer_id, call_id)
);

-- ── 10. Audit log (append-only) ──────────────────────────────────────────
CREATE TABLE audit_log (
  audit_id     BIGSERIAL PRIMARY KEY,
  occurred_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  actor_type   TEXT NOT NULL,                      -- ai|user|system|device|service
  actor_id     TEXT,
  action       TEXT NOT NULL,                      -- create_call|hangup|transfer|key_mint|…
  resource     TEXT NOT NULL,                      -- call:<id> | key:<id>
  call_id      TEXT,
  request_id   TEXT,
  ip           INET,
  before       JSONB,
  after        JSONB
);
CREATE INDEX idx_audit_resource ON audit_log(resource, occurred_at);
CREATE INDEX idx_audit_actor    ON audit_log(actor_id, occurred_at);

-- ── 11. Usage & quality (aggregated for dashboards) ──────────────────────
CREATE TABLE usage_daily (
  day          DATE NOT NULL,
  identity_id  TEXT NOT NULL,
  calls        INT NOT NULL DEFAULT 0,
  stt_seconds  INT NOT NULL DEFAULT 0,
  tts_seconds  INT NOT NULL DEFAULT 0,
  tokens_in    BIGINT NOT NULL DEFAULT 0,
  tokens_out   BIGINT NOT NULL DEFAULT 0,
  tools_used   INT NOT NULL DEFAULT 0,
  PRIMARY KEY (day, identity_id)
);
```

---

## 3. Write paths (outbox discipline)

```
command ──► validate ──► FSM guard ──►
  (a) INSERT event(s) INTO events        (the truth — one transaction, with state projection
  (b) UPSERT calls/transcript projection   updated in the SAME transaction)
  (c) publish to EventPlane               (after commit — in-process bus or Redis Streams)
  (d) fan out to subscribers              (SSE/WS/MCP/webhooks — idempotent by event_id)
```

If (c)/(d) fail, the event is still durably present; a `EventRelayer` background task re-publishes
unpublished events (status via a `published_at` column added to `events`, or via the event
plaque in the bus). This replaces v1's `dual-write` persistence mode as the *only* production
mode — memory-only survives as a dev/test mode.

## 4. v1 → v2 mapping

| v1 table | v2 |
|----------|----|
| `sessions` (id, status, data JSONB, timestamps) | `calls` (normalized) — same `call_id` values, migrated via `data` decomposition; v1 `data` JSONB content (messages array, result, context) → `transcript_segments` + `calls.result/context` |
| `callbacks` | unchanged |
| `phone_tokens`, `ai_keys` | unchanged + `ai_keys` extension columns |
| — | new: `events`, `transcript_segments`, `turn_leases`, `tool_invocations`, `media_sessions`, `recordings`, `api_keys`, `event_cursors`, `audit_log`, `usage_daily` |

Migration is a **new-schema-in-parallel** rollout (v2 tables created alongside v1; backfill
job decomposes old `sessions.data` into `calls` + `transcript_segments`; verification job
compares counts; switchover via env flag `PERSISTENCE_MODE=v2`). No destructive change to v1
tables at any point. See [07-migration-plan.md](./07-migration-plan.md).

## 5. Backup & retention

- Daily full + continuous WAL archiving (Neon handles natively; Hetzner: pgBackRest).
- `events`: 90 days hot, then archived to object storage (parquet/JSONL) — enables long-term
  analytics without table bloat.
- `calls`/`transcript_segments`: kept per retention policy on `calls.retention_expires_at`
  (v1 default: 60min completed / 5min cancelled → v2 default 30 days, configurable per call).
- `recordings`: object storage lifecycle rules; encryption keys rotated quarterly.