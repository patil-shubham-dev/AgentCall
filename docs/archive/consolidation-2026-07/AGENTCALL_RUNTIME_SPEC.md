# AgentCall — Runtime Specification

> How the daemon lives, dies, and configures itself.

---

## 1. Process Model

Single process. No clustering for v1.

```
agentcall [--config /path/to/config.json]
```

- `stdio` transport: daemon reads from stdin, writes to stdout. AI agent manages
  lifecycle.
- `sse` transport: daemon listens on `AGENTCALL_HTTP_PORT` (default 7377).
  Daemon manages its own lifecycle.
- No transport (push-only): daemon starts, loads config, listens for push
  registration, waits for MCP connections. Useful for headless setups.

### 1.1 Startup Sequence

```
1. Load config (env → config file → defaults)
2. Initialize SQLite (create schema if not exists)
3. Load delivery channels from channel registry
4. Start MCP transport (stdio or SSE based on config)
5. Start push notification gateway (FCM/APNs)
6. Start WebSocket relay (for foreground delivery)
7. Emit 'ready' event
8. Block on MCP transport (stdio: read stdin; SSE: listen HTTP)
```

Total startup time target: < 100ms.

### 1.2 Shutdown Sequence

```
1. Stop accepting new MCP requests
2. Flush delivery queue (wait for in-flight deliveries, max 5s)
3. Close WebSocket connections
4. Close push notification gateway
5. Close SQLite
6. Exit(0)
```

SIGTERM → graceful shutdown. SIGKILL → on next startup, replay unacknowledged
sessions from SQLite.

### 1.3 Crash Recovery

On startup after unclean shutdown:
1. Load all sessions with status `pending` or `active`
2. For each, check if `expires_at` has passed → mark `expired`
3. For each remaining active session, re-attempt delivery
4. Log recovery summary

No explicit recovery manager needed. The session table IS the recovery log.

---

## 2. Configuration

### 2.1 Environment Variables

```bash
# Core
AGENTCALL_CONFIG_PATH=~/.config/agentcall/config.json
AGENTCALL_DATA_DIR=~/.local/share/agentcall

# MCP Transport
AGENTCALL_MCP_TRANSPORT=stdio     # stdio | sse
AGENTCALL_SSE_PORT=7377           # if transport is sse
AGENTCALL_SSE_HOST=127.0.0.1      # bind address

# Push Notifications
AGENTCALL_FCM_CREDENTIALS=        # path to Firebase service account JSON
AGENTCALL_APNS_KEY=               # path to APNs key file
AGENTCALL_APNS_KEY_ID=
AGENTCALL_APNS_TEAM_ID=

# Delivery
AGENTCALL_DELIVERY_TIMEOUT=30     # seconds before trying next device
AGENTCALL_MAX_DELIVERY_ATTEMPTS=3
AGENTCALL_RETRY_INTERVAL=60       # seconds between retries

# Presence
AGENTCALL_PRESENCE_TIMEOUT=300    # seconds before marking device inactive
AGENTCALL_AWAY_TIMEOUT=1800       # seconds of inactivity → away
AGENTCALL_SLEEP_START=23:00       # quiet hours start (local time)
AGENTCALL_SLEEP_END=07:00         # quiet hours end

# Logging
AGENTCALL_LOG_LEVEL=info          # debug | info | warn | error
AGENTCALL_LOG_FORMAT=text         # text | json
```

### 2.2 Config File (`~/.config/agentcall/config.json`)

```json
{
  "$schema": "https://agentcall.dev/schemas/config.json",

  "mcp": {
    "transport": "stdio",
    "sse_port": 7377,
    "sse_host": "127.0.0.1"
  },

  "push": {
    "fcm_credentials": "/etc/agentcall/fcm.json",
    "apns_key": "/etc/agentcall/apns.p8",
    "apns_key_id": "ABC123",
    "apns_team_id": "TEAM123"
  },

  "delivery": {
    "timeout_seconds": 30,
    "max_attempts": 3,
    "retry_interval_seconds": 60
  },

  "presence": {
    "device_timeout_seconds": 300,
    "away_timeout_seconds": 1800,
    "sleep_start": "23:00",
    "sleep_end": "07:00"
  },

  "storage": {
    "path": "~/.local/share/agentcall/agentcall.db",
    "wal_mode": true
  },

  "devices": {
    "default_device_priority": {
      "android": 10,
      "ios": 10,
      "desktop": 20,
      "browser": 30,
      "watch": 40
    }
  }
}
```

---

## 3. File System Layout

```
~/.config/agentcall/
  └── config.json          # User config

~/.local/share/agentcall/
  ├── agentcall.db         # SQLite database
  └── logs/
      └── agentcall.log    # Optional, if file logging enabled

~/.local/share/agentcall/keys/
  ├── claude.key           # Agent key files (one per agent)
  ├── chatgpt.key
  └── opencode.key
```

---

## 4. Health Endpoint (SSE mode only)

```
GET /health → 200 OK

{
  "status": "ok",
  "version": "2.0.0",
  "uptime_seconds": 12345,
  "mcp_transport": "sse",
  "active_sessions": 3,
  "registered_devices": 2,
  "registered_agents": 4,
  "push_gateway": "connected",   // or "disconnected"
  "storage": "ok",              // or "error"
  "last_crash_recovery": null   // or ISO timestamp
}
```

---

## 5. Dependencies

### Runtime (required)
- Node.js 18+ (LTS)
- SQLite 3.x (via better-sqlite3)

### Push (optional, without → delivery degrades to WebSocket/HTTP)
- Firebase Admin SDK (for FCM)
- Node APNs library (for iOS)

### Dev/Test
- Vitest (unit tests)
- Playwright (Android test, if applicable)

### Anti-Dependencies (NOT used)
- PostgreSQL (SQLite replaces)
- Redis (no caching needed at this scale)
- Docker (single process)
- Kafka/RabbitMQ (in-process delivery queue)
- Express/Fastify (optional, only for SSE mode)
- Any AI SDK (AgentCall is AI-agnostic)

---

## 6. Packaging

### 6.1 npm Global Install (Primary)

```bash
npm install -g @agentcall/daemon
agentcall
```

### 6.2 Docker (Secondary)

```dockerfile
FROM node:20-alpine
RUN npm install -g @agentcall/daemon
EXPOSE 7377
VOLUME /data
VOLUME /config
ENTRYPOINT ["agentcall"]
```

### 6.3 Systemd User Service

```ini
[Unit]
Description=AgentCall daemon
After=network.target

[Service]
ExecStart=%h/.local/bin/agentcall
Restart=on-failure
RestartSec=5
Environment=AGENTCALL_CONFIG_PATH=%h/.config/agentcall/config.json

[Install]
WantedBy=default.target
```

---

## 7. Security

### 7.1 Agent API Keys

Generated by `register_agent` tool. Stored as bcrypt hash in SQLite. Key is
shown once at creation:

```
agentcall register-agent "Claude"
→ Agent ID: agent_claude_abc123
→ API Key: ac-sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
→ Store this key securely. It will not be shown again.
```

### 7.2 File Permissions

- Config file: 600 (owner read/write)
- Key files: 600
- Database: 600
- Log files: 600

### 7.3 Network

- SSE mode binds to `127.0.0.1` by default (localhost only)
- No TLS in daemon (use reverse proxy if remote access needed)
- Push notifications use FCM/APNs TLS (outbound only)

---

## 8. Monitoring

### 8.1 Log Format (JSON mode)

```json
{
  "timestamp": "2026-07-26T10:00:00.000Z",
  "level": "info",
  "component": "router",
  "event": "session_created",
  "session_id": "sess_abc123",
  "agent_id": "agent_claude_abc123",
  "capability": "decision",
  "duration_ms": 12
}
```

### 8.2 Metrics (optional, Prometheus endpoint in SSE mode)

```
GET /metrics

agentcall_sessions_total 42
agentcall_sessions_active 3
agentcall_messages_delivered 156
agentcall_messages_failed 2
agentcall_devices_registered 2
agentcall_delivery_latency_ms{channel="push"} 850
agentcall_delivery_latency_ms{channel="ws"} 45
```
