# AgentCall — API Guidelines

> **Canonical references:** [API_SPEC.md](../API_SPEC.md) | [IMPLEMENTATION_RULES.md](./IMPLEMENTATION_RULES.md)

---

## Design Principles

- Provider agnostic
- Device agnostic
- Stateless
- Versioned
- JSON-first
- Event-driven

## REST API

### Base URL
```
https://api.agentcall.dev/api/v1
```

### URL Structure
```
/api/v1/{resource}
/api/v1/{resource}/{id}
/api/v1/{resource}/{id}/{subresource}
```

### HTTP Methods

| Method | Purpose |
|--------|---------|
| GET | Retrieve resource(s) |
| POST | Create resource |
| PATCH | Partial update |
| DELETE | Remove resource |
| PUT | Full replace (rare) |

### Request/Response Format

#### Success
```json
{
  "data": { ... },
  "meta": {
    "page": 1,
    "total": 42
  }
}
```

#### Error
```json
{
  "error": {
    "code": "CALL_NOT_FOUND",
    "message": "Call does not exist",
    "correlationId": "req_abc123",
    "details": {}
  }
}
```

### Status Codes

| Code | Usage |
|------|-------|
| 200 | Success |
| 201 | Created |
| 400 | Bad Request (validation) |
| 401 | Unauthorized (missing/invalid auth) |
| 403 | Forbidden (valid auth, insufficient permissions) |
| 404 | Not Found |
| 409 | Conflict |
| 422 | Validation Error |
| 429 | Rate Limited |
| 500 | Internal Server Error |

### Pagination

```
GET /api/v1/calls?page=1&limit=20

Response:
{
  "data": [...],
  "meta": {
    "page": 1,
    "limit": 20,
    "total": 142,
    "totalPages": 8
  }
}
```

## MCP Tools

### Tool Definition
```typescript
interface MCPTool {
  name: string;           // snake_case
  description: string;
  inputSchema: JSONSchema;
  outputSchema: JSONSchema;
}
```

### Validation
- Every tool input validated with Zod
- Required fields clearly documented
- Sensible defaults where applicable

## WebSocket Events

### Format
```json
{
  "type": "event.name",
  "version": 1,
  "payload": {},
  "timestamp": "2026-07-26T12:00:00Z"
}
```

### Naming
- Dotted notation: `call.created`, `presence.changed`
- Past tense for server events: `notification.sent`
- Present tense for client actions: `call.accept`

## SSE Events

### Format
```
event: notification.created
data: {"type":"call.incoming","callId":"abc"}
```

### Reconnection
- Client should reconnect with `Last-Event-Id`
- Server replays missed events from ID

## Versioning

See [ADR-0008](./adr/0008-api-versioning.md) for the complete versioning strategy.

## Authentication

Every request must include:
- `Authorization: Bearer <JWT>` (user) or
- `X-Provider-Key: <provider_api_key>` (provider)

## Rate Limiting

| Endpoint | Window | Limit |
|----------|--------|-------|
| POST /calls | 60s | 10 per user |
| GET /presence | 60s | 60 per user |
| POST /auth/login | 60s | 5 per IP |
| WebSocket | 1s | 50 per connection |
