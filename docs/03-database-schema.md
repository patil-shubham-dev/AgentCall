# Database Schema Design

> **HISTORICAL DESIGN DOCUMENT**
>
> This document describes the original design process.
> The implementation may differ.
> Refer to [ARCHITECTURE_BASELINE.md](../ARCHITECTURE_BASELINE.md) for the current architecture.
>
> **Canonical references:** [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) | [API_SPEC.md](../API_SPEC.md)
>
> This document defines the target PostgreSQL schema aligned with the SYSTEM_ARCHITECTURE.md runtime services. Not yet implemented.

## AgentCall MCP

**Version:** 1.0
**Status:** Draft

---

## 1. PostgreSQL Schema

All tables use UUID v7 primary keys (time-ordered) and include `created_at` / `updated_at` timestamps.

### 1.1 Users

```sql
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email           VARCHAR(255) UNIQUE NOT NULL,
    display_name    VARCHAR(100) NOT NULL,
    avatar_url      TEXT,
    timezone        VARCHAR(50) DEFAULT 'UTC',
    do_not_disturb  BOOLEAN DEFAULT false,
    dnd_schedule    JSONB,  -- { "start": "22:00", "end": "07:00", "timezone": "UTC" }
    preferences     JSONB DEFAULT '{}',
    is_active       BOOLEAN DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_users_email ON users(email);
CREATE INDEX idx_users_active ON users(is_active) WHERE is_active = true;
```

### 1.2 OAuth Accounts

```sql
CREATE TABLE oauth_accounts (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider        VARCHAR(50) NOT NULL,  -- 'google', 'github', 'apple'
    provider_user_id VARCHAR(255) NOT NULL,
    provider_email  VARCHAR(255),
    access_token    TEXT,
    refresh_token   TEXT,
    expires_at      TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(provider, provider_user_id)
);

CREATE INDEX idx_oauth_user ON oauth_accounts(user_id);
```

### 1.3 Devices

```sql
CREATE TABLE devices (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    platform        VARCHAR(20) NOT NULL CHECK (platform IN ('android', 'ios', 'web')),
    push_token      TEXT,
    push_token_updated_at TIMESTAMPTZ,
    device_name     VARCHAR(255),
    device_model    VARCHAR(100),
    os_version      VARCHAR(50),
    app_version     VARCHAR(20),
    is_active       BOOLEAN DEFAULT true,
    last_seen_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_user ON devices(user_id, is_active) WHERE is_active = true;
CREATE INDEX idx_devices_push_token ON devices(push_token) WHERE push_token IS NOT NULL;
```

### 1.4 Call Sessions

```sql
CREATE TYPE call_status AS ENUM (
    'requested',
    'ringing',
    'connecting',
    'connected',
    'ended',
    'cancelled',
    'timed_out',
    'failed'
);

CREATE TYPE call_priority AS ENUM ('low', 'normal', 'high', 'urgent');

CREATE TYPE call_reason AS ENUM (
    'clarification',
    'approval',
    'error',
    'input_required'
);

CREATE TABLE call_sessions (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    agent_id        UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    status          call_status NOT NULL DEFAULT 'requested',
    priority        call_priority NOT NULL DEFAULT 'normal',
    reason          call_reason NOT NULL,
    context         JSONB NOT NULL DEFAULT '{}',
    result          JSONB,
    timeout_seconds INTEGER NOT NULL DEFAULT 30,
    expires_at      TIMESTAMPTZ,
    requested_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ringing_at      TIMESTAMPTZ,
    connected_at    TIMESTAMPTZ,
    ended_at        TIMESTAMPTZ,
    duration_ms     INTEGER,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_calls_user_status ON call_sessions(user_id, status);
CREATE INDEX idx_calls_agent_status ON call_sessions(agent_id, status);
CREATE INDEX idx_calls_status_created ON call_sessions(status, created_at) WHERE status IN ('requested', 'ringing', 'connected');
CREATE INDEX idx_calls_expires ON call_sessions(expires_at) WHERE expires_at IS NOT NULL;

-- Call quality metrics (post-call)
CREATE TABLE call_quality_metrics (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    call_id         UUID NOT NULL REFERENCES call_sessions(id) ON DELETE CASCADE,
    avg_jitter_ms   FLOAT,
    max_jitter_ms   FLOAT,
    avg_rtt_ms      FLOAT,
    max_rtt_ms      FLOAT,
    packet_loss_pct FLOAT,
    bitrate_kbps    FLOAT,
    codec           VARCHAR(20) DEFAULT 'opus',
    sample_rate     INTEGER DEFAULT 48000,
    ice_connection_type VARCHAR(20),  -- 'host', 'srflx', 'relay'
    turn_used       BOOLEAN DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_quality_call ON call_quality_metrics(call_id);
```

### 1.5 Call Participants

```sql
CREATE TYPE participant_role AS ENUM ('caller', 'callee', 'observer');

CREATE TABLE call_participants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    call_id         UUID NOT NULL REFERENCES call_sessions(id) ON DELETE CASCADE,
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role            participant_role NOT NULL,
    joined_at       TIMESTAMPTZ,
    left_at         TIMESTAMPTZ,
    muted           BOOLEAN DEFAULT false,
    audio_level     FLOAT,  -- 0.0 to 1.0
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(call_id, user_id)
);

CREATE INDEX idx_participants_call ON call_participants(call_id);
CREATE INDEX idx_participants_user ON call_participants(user_id);
```

### 1.6 Push Notification Log

```sql
CREATE TYPE notification_status AS ENUM ('queued', 'delivered', 'failed', 'expired');

CREATE TABLE notification_log (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    device_id       UUID REFERENCES devices(id) ON DELETE SET NULL,
    call_id         UUID REFERENCES call_sessions(id) ON DELETE SET NULL,
    notification_type VARCHAR(50) NOT NULL,  -- 'call_incoming', 'task_complete', 'call_missed'
    status          notification_status NOT NULL DEFAULT 'queued',
    provider        VARCHAR(10),  -- 'fcm', 'apns'
    provider_message_id TEXT,
    error_message   TEXT,
    delivered_at    TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notif_user ON notification_log(user_id, created_at DESC);
CREATE INDEX idx_notif_status ON notification_log(status) WHERE status = 'queued';
CREATE INDEX idx_notif_call ON notification_log(call_id);
```

### 1.7 Auth Tokens (JWT Refresh & Blacklist)

```sql
CREATE TABLE auth_refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash      VARCHAR(64) NOT NULL UNIQUE,  -- SHA-256 of refresh token
    device_id       UUID REFERENCES devices(id) ON DELETE SET NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked         BOOLEAN DEFAULT false,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_refresh_user ON auth_refresh_tokens(user_id);

CREATE TABLE token_blacklist (
    jti             VARCHAR(64) PRIMARY KEY,  -- JWT ID (SHA-256)
    expires_at      TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_blacklist_expires ON token_blacklist(expires_at);
```

### 1.8 API Keys (for MCP Agent authentication)

```sql
CREATE TABLE api_keys (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name            VARCHAR(100) NOT NULL,
    key_prefix      VARCHAR(8) NOT NULL,  -- First 8 chars of the key for identification
    key_hash        VARCHAR(64) NOT NULL UNIQUE,  -- SHA-256 of full key
    permissions     JSONB DEFAULT '["create_call", "query_presence"]',
    last_used_at    TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    is_active       BOOLEAN DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_apikeys_user ON api_keys(user_id);
CREATE INDEX idx_apikeys_prefix ON api_keys(key_prefix);
```

---

## 2. Redis Data Structures

### 2.1 Presence

```
Key:   presence:{user_id}
Value: {
  "status": "online" | "away" | "busy",
  "device_id": "uuid",
  "platform": "android" | "ios" | "web",
  "last_seen": "ISO8601",
  "ip": "x.x.x.x"
}
TTL:   60 seconds (refreshed every 15s by client heartbeat)
```

### 2.2 Signaling State

```
Key:   signal:{call_id}:{user_id}:{session_id}
Value: {
  "role": "caller" | "callee",
  "sdp_offer": RTCSessionDescription (JSON),
  "sdp_answer": RTCSessionDescription (JSON) | null,
  "ice_candidates": [RTCIceCandidate, ...],
  "connected": bool
}
TTL:   300 seconds (cleaned up after call ends)
```

### 2.3 WebRTC TURN Credentials

```
Key:   turn_creds:{call_id}
Value: {
  "username": "timestamp:user_id",
  "credential": "hmac-sha1-password",
  "ttl": 3600
}
TTL:   3600 seconds
```

### 2.4 Rate Limiting

```
Key:   ratelimit:{endpoint}:{user_id|ip}
Value: { "count": 12, "window_start": "ISO8601" }
TTL:   Varies by endpoint (typically 60s window)
```

### 2.5 Active Call Tracker

```
Key:   active_call:{user_id}
Value: { "call_id": "uuid", "status": "connected", "since": "ISO8601" }
TTL:   Set to max call duration (1800s)
Purpose: Fast lookup of user's current call
```

### 2.6 PubSub Channels

| Channel | Payload | Purpose |
|---------|---------|---------|
| `calls:{call_id}` | `{ type: "state_change"|"participant_joined"|... }` | Inter-service call events |
| `presence:{user_id}` | `{ status, device_id }` | Presence change broadcasts |
| `notifications:dispatch` | `{ user_id, type, payload }` | Push notification job queue |

---

## 3. Migration Strategy

- Use **Knex.js** or **Prisma Migrate** for schema versioning
- All migrations are idempotent and reversible
- Naming convention: `YYYYMMDDHHMMSS_description.ts`
- Each migration wrapped in a transaction
- Add down migration for rollback (dev only)

### Initial Migration Order

1. `users` + `oauth_accounts`
2. `devices`
3. `auth_refresh_tokens` + `token_blacklist`
4. `api_keys`
5. `call_sessions` + `call_participants`
6. `call_quality_metrics`
7. `notification_log`

---

## 4. Backup Strategy

- **WAL-level continuous archiving** (pg_archive_command)
- Daily full backup via `pg_dump` to Hetzner Storage Box
- Retention: 7 daily, 4 weekly, 3 monthly
- Point-in-time recovery via WAL archives (last 7 days)
- Backup verification: weekly restore test
