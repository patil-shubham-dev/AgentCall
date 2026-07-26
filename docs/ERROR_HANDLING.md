# ⚠️ HISTORICAL REFERENCE — Error Handling Guide

> **This document describes an aspirational error-handling architecture for a planned multi-service system.**
> **It does NOT describe the current VoiceBridge v1.0 implementation.**
>
> For the actual error format, see [API_SPEC.md](../API_SPEC.md).
> For the actual implementation, see `backend/src/routes.ts` (error handler at line 212).

---

## Error Response Format

The current VoiceBridge implementation uses a flat error format (not nested):

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable description",
  "request_id": "uuid"
}
```

**The nested format below (`{ error: { code, message, correlationId, details } }`) is NOT implemented.**

```json
{
  "error": {
    "code": "ERROR_CODE",
    "message": "Human-readable description",
    "correlationId": "req_uuid",
    "details": {}
  }
}
```

## Error Codes

### 4xx Client Errors

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `VALIDATION_ERROR` | 422 | Input validation failed |
| `UNAUTHORIZED` | 401 | Missing or invalid credentials |
| `FORBIDDEN` | 403 | Valid credentials, insufficient permissions |
| `NOT_FOUND` | 404 | Resource does not exist |
| `CONFLICT` | 409 | Resource state conflict |
| `RATE_LIMITED` | 429 | Too many requests |
| `CALL_NOT_FOUND` | 404 | Call session not found |
| `CALL_INVALID_STATE` | 409 | Invalid state transition |
| `PROVIDER_NOT_FOUND` | 404 | Provider not registered |
| `PROVIDER_DISCONNECTED` | 409 | Provider session expired |
| `DEVICE_NOT_FOUND` | 404 | Device not registered |
| `USER_NOT_FOUND` | 404 | User does not exist |
| `TOKEN_EXPIRED` | 401 | JWT or refresh token expired |
| `TOKEN_REVOKED` | 401 | Token has been revoked |

### 5xx Server Errors

| Code | HTTP Status | Description |
|------|-------------|-------------|
| `INTERNAL_ERROR` | 500 | Unexpected server error |
| `SERVICE_UNAVAILABLE` | 503 | Service temporarily unavailable |
| `GATEWAY_TIMEOUT` | 504 | Upstream service timed out |
| `EVENT_DELIVERY_FAILED` | 500 | Event bus delivery failure |

## Error Handling Patterns

The VoiceBridge service layer does not use a `Result` type. Errors are thrown as `Error` instances and caught by the Fastify error handler in `routes.ts`.

```typescript
// Current pattern (routes.ts):
app.setErrorHandler(async (error, request, reply) => {
  const statusCode = error.statusCode ?? 500;
  return reply.status(statusCode).send({
    error: errAny.code ?? 'INTERNAL_ERROR',
    message: errAny.message ?? 'Unknown error',
    request_id: request.id,
  });
});
```

### Error Classification
- **Validation errors:** Input does not match schema
- **Auth errors:** Missing, invalid, or expired credentials
- **Business errors:** Valid request but operation cannot complete
- **System errors:** Unexpected infrastructure failures

### Logging
- All errors logged with `correlationId` for tracing
- 4xx errors logged at `warn` level
- 5xx errors logged at `error` level
- Include stack traces for 5xx errors only

## Event Bus Error Handling

**Not implemented in VoiceBridge v1.0.** The EventBus dispatches synchronously and logs errors. There is no retry mechanism, dead letter queue, or exponential backoff for event handlers. Repository-level retry is handled separately via `withRetry()` in `backend/src/common/retry.ts`.

## Client Error Handling

- Android: Error responses parsed from JSON
- Network errors: Retry with exponential backoff (3s reconnect interval for WebSocket)
- Auth errors: Show re-authentication prompt
- Show user-friendly error messages
