# ⚠️ HISTORICAL REFERENCE — Security Guidelines

> **This document describes an aspirational security architecture for a planned multi-service system.**
> **It does NOT describe the current VoiceBridge v1.0 implementation.**
>
> For actual security model, see [ARCHITECTURE_BASELINE.md](../ARCHITECTURE_BASELINE.md) (Security Model section).
> For actual auth implementation, see `backend/src/routes.ts` and `backend/src/signaling/server.ts`.

---

## VoiceBridge v1.0 Security Model

### Authentication
- **Method:** Single Bearer token (`SERVICE_TOKEN`)
- **HTTP:** `Authorization: Bearer <token>` header
- **WebSocket:** `?token=<token>` query parameter on WS upgrade
- **No JWT:** No RS256, no refresh tokens, no token expiry
- **No OAuth:** No Google, GitHub, Apple login

### Authorization
- **Model:** Single-role — any valid token = full access
- **No RBAC:** No user roles, no permissions
- **No multi-user isolation:** All clients share the same token

### Transport Security
- TLS 1.3 when behind Caddy or nginx ingress
- HSTS header set by Caddy (`max-age=63072000`)
- No certificate pinning
- WS (not WSS) in development

### Rate Limiting

| Scope | Limit | Window |
|-------|-------|--------|
| Global | 100 requests | 1 minute |
| Moderate endpoints | 60 requests | 1 minute |
| `/health` | 20 requests | 10 seconds |
| `/ready` | 20 requests | 10 seconds |
| `/metrics` | 10 requests | 10 seconds |
| WebSocket messages | 30 messages | 10 seconds (per connection) |
| WebSocket connections | 10 connections | 1 second (per IP) |

### Input Validation
- Manual field checks in route handlers (no Zod schema validation)
- String length limits enforced by request body size limit (1MB default)
- UUIDs used for resource IDs (no user-controlled paths)

### Security Headers
- Set by `@fastify/helmet` (CSP, X-Content-Type-Options, X-Frame-Options)
- Set by Caddy (HSTS, Referrer-Policy)

### Secrets Management
- SERVICE_TOKEN: required at startup, never logged, validated on every request
- DATABASE_URL: contains credentials, used only for Pool creation
- Config validation logs keys and types, never values

### Audit Logging
- HTTP requests logged with method, URL, auth context
- Session operations logged with callId, elapsed time
- No structured audit log table
- No audit event persistence

---

## Original Design (Not Implemented)

The following security features from the original design are NOT implemented in v1.0:
- JWT tokens with RS256 signing
- Provider API keys with SHA-256 hashing
- Certificate pinning in Android
- Zod schema validation
- SRTP/DTLS-SRTP for WebRTC audio
- bcrypt password hashing
- Refresh token rotation
- Structured audit log table
- Incident response plan
