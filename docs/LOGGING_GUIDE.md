# AgentCall — Logging Guide

> **Canonical references:** [IMPLEMENTATION_RULES.md](./IMPLEMENTATION_RULES.md) | [ERROR_HANDLING.md](./ERROR_HANDLING.md)

---

## Logging Framework

**Backend:** Pino (structured JSON logging)
**Android:** Android Logcat + Timber (debug), Crashlytics (production, future)

## Log Format

### Backend (Pino)
```json
{
  "timestamp": "2026-07-26T12:34:56.789Z",
  "level": "info",
  "service": "call-manager",
  "callId": "abc-123",
  "event": "call.status_changed",
  "from": "ringing",
  "to": "connected",
  "durationMs": 1450,
  "correlationId": "req_uuid"
}
```

### Android (Timber)
```kotlin
Timber.tag("CallService").d("Call %s status: %s -> %s", callId, from, to)
```

## Log Levels

| Level | Usage | Examples |
|-------|-------|----------|
| `fatal` | System cannot continue | Database connection lost |
| `error` | Operation failed, manual intervention may be needed | Call creation failed, push delivery failed |
| `warn` | Unexpected but handled | Rate limit approaching, retry exceeded |
| `info` | Normal operation events | Call created, user authenticated |
| `debug` | Development debugging | MCP tool input/output (dev only) |
| `trace` | Detailed flow tracing | Event bus message routing |

## What to Log

### Always Log
- Service startup/shutdown
- Authentication events (login, logout, token refresh)
- Call lifecycle (create, accept, end, cancel)
- Errors and exceptions
- Rate limit violations
- Event bus message failures

### Never Log
- Secrets (passwords, tokens, API keys)
- Full message content (log message IDs only)
- Personally Identifiable Information (PII)
- Raw database queries in production

## Correlation IDs

- Every request receives a UUID correlation ID
- Passed through all service calls and event handlers
- Included in all log entries for the request
- Returned in API error responses for debugging

## Log Storage

- **Development:** Console output via pino-pretty
- **Production:** JSON logs to stdout (containerized)
- **Future:** Centralized logging via Loki + Grafana

## Best Practices

1. Log at the appropriate level — not everything is an error
2. Include context (callId, userId, correlationId)
3. Use structured data, not string concatenation
4. Never log in hot paths (inside loops, high-frequency events)
5. Sanitize all user input before logging
