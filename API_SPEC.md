# VoiceBridge API Specification

> **Version:** 1.0.0 — Matches implementation in `backend/src/routes.ts`
> **Auth:** Bearer token (`SERVICE_TOKEN`) on all routes except health/ready/metrics

---

## Base URL

```
http://localhost:4000/api/v1
```

Production URL depends on ingress configuration (see `infra/k8s/06-ingress.yaml`).

---

## Authentication

Every request (except health, readiness, and metrics) must include:

```
Authorization: Bearer <SERVICE_TOKEN>
```

Missing or invalid token → `401 UNAUTHORIZED`.

---

## Endpoints

### GET /api/v1/health

Health check endpoint. Unauthenticated (required by K8s liveness probe).

**Rate limit:** 20 requests per 10 seconds

**Response 200:**
```json
{
  "status": "ok",
  "version": "2.0.0",
  "timestamp": "2026-07-26T12:00:00.000Z",
  "uptime": 1234.56,
  "database": {
    "connected": true,
    "pingMs": 2,
    "poolTotal": 5,
    "poolIdle": 3,
    "poolWaiting": 0
  },
  "scheduler": { "timerCount": 3 },
  "callbacks": { "count": 2 },
  "sessions": { "active": 1, "paused": 2, "completed": 5 }
}
```

- `status`: `"ok"` or `"degraded"` (DB unreachable)
- `database` block omitted when no DB configured

---

### GET /api/v1/ready

Readiness probe for K8s. Unauthenticated. Returns `"ok"` only when startup + recovery + DB (if configured) are all ready.

**Rate limit:** 20 requests per 10 seconds

**Response 200 (ready):**
```json
{
  "status": "ok",
  "startupComplete": true,
  "recoveryComplete": true,
  "databaseConnected": true,
  "repositoriesInitialized": true
}
```

**Response 503 (not ready):**
```json
{
  "status": "not_ready",
  "startupComplete": false,
  "recoveryComplete": false,
  "databaseConnected": true,
  "repositoriesInitialized": true
}
```

---

### GET /api/v1/metrics

Operational metrics snapshot. Unauthenticated (required by Prometheus scraping).

**Rate limit:** 10 requests per 10 seconds

**Response 200:**
```json
{
  "counters": {
    "sessions.created": 42,
    "sessions.completed": 10,
    "startup.complete": 1
  },
  "gauges": {
    "sessions.active": 1,
    "sessions.paused": 2,
    "db.pool.total": 5
  },
  "timings": {
    "startup.duration": {
      "count": 1, "min": 1200, "max": 1200, "avg": 1200,
      "p50": 1200, "p95": 1200, "p99": 1200
    }
  },
  "uptime": 3600,
  "timestamp": "2026-07-26T12:00:00.000Z"
}
```

---

### POST /api/v1/calls

Create a new call session. Authenticated.

**Rate limit:** 60 per minute

**Request body:**
```json
{
  "summary": "Need clarification on invoice",
  "reason": "clarification",
  "user_id": "user_123",
  "agent_id": "ai-agent",
  "priority": "normal",
  "context": {
    "task_id": "task_456",
    "options": ["option_a", "option_b"]
  }
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `summary` | Yes | Brief description of the call reason |
| `reason` | Yes | One of: `clarification`, `approval`, `error`, `input_required` |
| `user_id` | No | Default: `"solo-user"` |
| `agent_id` | No | Default: `"ai-agent"` |
| `priority` | No | Default: `"normal"` |
| `context.task_id` | No | Optional task identifier |
| `context.options` | No | Optional selection options |

**Response 201:**
```json
{
  "call_id": "uuid",
  "status": "pending",
  "created_at": "2026-07-26T12:00:00.000Z"
}
```

**Errors:** `VALIDATION_ERROR` (400) if `summary` missing or `reason` invalid.

---

### GET /api/v1/calls/:callId

Get call details. Authenticated.

**Path params:** `callId` — UUID of the call session

**Response 200:**
```json
{
  "call_id": "uuid",
  "status": "active",
  "user_id": "user_123",
  "agent_id": "ai-agent",
  "created_at": "2026-07-26T12:00:00.000Z",
  "connected_at": "2026-07-26T12:00:05.000Z",
  "ended_at": null,
  "result": null,
  "message_count": 5
}
```

**Errors:** `NOT_FOUND` (404) if call does not exist.

---

### POST /api/v1/calls/:callId/messages

Add an AI message to a call. Authenticated.

**Rate limit:** 60 per minute

**Path params:** `callId` — UUID of the call session

**Request body:**
```json
{
  "content": "Hello, how can I help you today?"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `content` | Yes | Message text content |

On first AI message, the call status transitions from `pending` to `active`. The phone receives a push notification via WebSocket.

**Response 201:**
```json
{
  "message_id": "uuid",
  "role": "ai",
  "content": "Hello, how can I help you today?",
  "created_at": "2026-07-26T12:00:05.000Z"
}
```

**Errors:** `VALIDATION_ERROR` (400) if content missing, `NOT_FOUND` (404) if call not found.

---

### POST /api/v1/calls/:callId/user-text

Process a user text message (speech-to-text integration point). Authenticated.

**Rate limit:** 60 per minute

**Path params:** `callId` — UUID of the call session

**Request body:**
```json
{
  "text": "I need help with my account"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `text` | Yes | User's spoken text content (must be non-empty) |

**Response 200:**
```json
{
  "call_id": "uuid",
  "text": "I need help with my account"
}
```

**Errors:** `VALIDATION_ERROR` (400) if text missing or empty, `NOT_FOUND` (404) if call not found.

---

### GET /api/v1/calls/:callId/transcript

Get call transcript (filters out system messages). Authenticated.

**Path params:** `callId` — UUID of the call session

**Response 200:**
```json
{
  "call_id": "uuid",
  "messages": [
    {
      "id": "uuid",
      "role": "ai",
      "type": "text",
      "content": "Hello, how can I help?",
      "createdAt": "2026-07-26T12:00:05.000Z"
    },
    {
      "id": "uuid",
      "role": "user",
      "type": "text",
      "content": "I need help",
      "createdAt": "2026-07-26T12:00:10.000Z"
    }
  ]
}
```

**Errors:** `NOT_FOUND` (404) if call not found.

---

### POST /api/v1/calls/:callId/complete

Complete (resolve) a call. Authenticated.

**Path params:** `callId` — UUID of the call session

**Request body (optional):**
```json
{
  "result": {
    "transcriptSummary": "User needed help with invoice",
    "decision": "approved",
    "selectedOption": "option_a",
    "sentiment": "positive",
    "actionItems": ["send invoice copy"]
  }
}
```

If no result body provided, a default result is generated from the user's messages.

Triggers: status → `completed`, callback deleted (if exists), phone receives `call_ended` notification.

**Response 200:**
```json
{
  "status": "completed",
  "call_id": "uuid"
}
```

**Errors:** `NOT_FOUND` (404) if call not found.

---

### POST /api/v1/calls/:callId/cancel

Cancel a call. Authenticated.

**Path params:** `callId` — UUID of the call session

Triggers: status → `cancelled`, callback deleted, phone receives `call_cancelled` notification.

**Response 200:**
```json
{
  "status": "cancelled",
  "call_id": "uuid"
}
```

**Errors:** `NOT_FOUND` (404) if call not found.

---

### GET /api/v1/users/:userId/active-call

Get the active call for a user. Authenticated.

**Path params:** `userId` — User identifier

Returns the user's most recent call with `pending` or `active` status, if one exists.

**Response 200 (with active call):**
```json
{
  "active_call": {
    "call_id": "uuid",
    "status": "active",
    "reason": "clarification",
    "summary": "Need help with invoice",
    "created_at": "2026-07-26T12:00:00.000Z"
  }
}
```

**Response 200 (no active call):**
```json
{
  "active_call": null
}
```

---

### POST /api/v1/calls/:callId/callback

Schedule a callback (pause and resume). Authenticated.

**Path params:** `callId` — UUID of the call session

**Request body:**
```json
{
  "delay_minutes": 10
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `delay_minutes` | No | Minutes until callback fires (default: 10) |

Triggers: status → `paused`, timer scheduled. When timer fires, status → `pending`. If the scheduling pod dies before the timer fires, the timer is rebuilt from the database on restart (Phase B recovery).

**Response 200:**
```json
{
  "status": "callback_scheduled",
  "call_id": "uuid",
  "resume_in_minutes": 10
}
```

**Errors:** `NOT_FOUND` (404) if call not found.

---

### POST /api/v1/phone/register

Register a phone for WebSocket signaling. Authenticated.

**Request body:**
```json
{
  "user_id": "user_123"
}
```

| Field | Required | Description |
|-------|----------|-------------|
| `user_id` | No | User identifier (default: `"solo-user"`) |

**Response 200:**
```json
{
  "status": "registered",
  "user_id": "user_123",
  "ws_endpoint": "wss://host/phone?user_id=user_123"
}
```

---

## WebSocket Protocol

### Connection

```
ws://host:4000/phone?token=<SERVICE_TOKEN>
```

- Token query parameter is **required** (matching SERVICE_TOKEN)
- Missing or invalid token → close code `4001` (unauthorized)
- Connection rate limited per IP (configurable, default 10 per second)

### Events (Server → Client)

| Event Type | Payload | Trigger |
|-----------|---------|---------|
| `call_incoming` | `{ type, callId, reason, summary, options, priority }` | New call created |
| `ai_message` | `{ type, callId, message }` | AI message added to call |
| `call_ended` | `{ type, callId }` | Call completed |
| `call_cancelled` | `{ type, callId }` | Call cancelled |
| `callback_scheduled` | `{ type, callId, delayMinutes, resumeAt }` | Callback scheduled |
| `connected` | `{ type: "connected", userId }` | WebSocket connected |
| `presence.update` | Internal event only | Phone registered |

### Events (Client → Server)

Published internally via EventBus. Currently no client-to-server message processing is implemented beyond logging.

### Close Codes

| Code | Reason |
|------|--------|
| 1000 | Normal closure |
| 4001 | Unauthorized (invalid/missing token) |

---

## Error Format

All errors follow this structure:

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable description",
  "request_id": "uuid"
}
```

Error codes map to HTTP status codes as implemented in `routes.ts`:

| HTTP | Error Code | Condition |
|------|-----------|-----------|
| 400 | `VALIDATION_ERROR` | Missing or invalid request body fields |
| 400 | `INVALID_REQUEST_BODY` | Malformed JSON or syntax error |
| 401 | `UNAUTHORIZED` | Missing or invalid Bearer token |
| 404 | `NOT_FOUND` | Call session not found |
| 429 | `RATE_LIMITED` | Rate limit exceeded |
| 500 | `INTERNAL_ERROR` | Unexpected server error |

---

## HTTP Status Codes

| Code | Usage |
|------|-------|
| 200 | Success (GET, POST complete/cancel/callback/user-text) |
| 201 | Created (POST calls, messages, phone/register) |
| 400 | Validation error |
| 401 | Unauthorized |
| 404 | Not found |
| 429 | Rate limited |
| 500 | Internal error |

---

## Rate Limits

| Scope | Limit | Window |
|-------|-------|--------|
| Global | 100 requests | 1 minute |
| Moderate endpoints | 60 requests | 1 minute |
| `/health` | 20 requests | 10 seconds |
| `/ready` | 20 requests | 10 seconds |
| `/metrics` | 10 requests | 10 seconds |
| WebSocket messages | 30 messages | 10 seconds (per connection) |
| WebSocket connections | 10 connections | 1 second (per IP) |

---

## Versioning

Current version: `/api/v1`

Breaking changes will use a new path prefix (e.g., `/api/v2`).

---

## Security

- Bearer token authentication on all endpoints except health/ready/metrics
- WebSocket token authentication via query parameter
- Helmet CSP headers on all responses
- CORS: configurable origins
- Rate limiting on all endpoints
- Input validation on all mutation endpoints
- No secrets in logs
- Parameterized SQL queries (no injection vector)

---

## Status Code Summary

| Scope | Limit | Window |
|-------|-------|--------|
| Global | 100 requests | 1 minute |
| Moderate endpoints | 60 requests | 1 minute |
| `/health` | 20 requests | 10 seconds |
| `/ready` | 20 requests | 10 seconds |
| `/metrics` | 10 requests | 10 seconds |
| WebSocket messages | 30 messages | 10 seconds (per connection) |
| WebSocket connections | 10 connections | 1 second (per IP) |
