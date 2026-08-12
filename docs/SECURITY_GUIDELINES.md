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

---

## Privacy data-flow audit (2026-08-12) — VoiceBridge + AgentCall Android

> Backlog item 17 (Phase A — truth audit). Written from the code, not from
> intent. Every claim below is tied to a file.

### Where does audio go?

| Stage | Mechanism | Where it goes |
|-------|-----------|---------------|
| Capture | `CallService.startRecording()` → `SpeechRecognizer` (Android system speech service) | **Never to the AgentCall backend.** Only the recognized text is POSTed to `/api/v1/calls/:id/user-text` (`CallService.enqueueUserText` → `ApiService.sendUserText`).
| Recognition | Device speech service (per Android) | Provider-dependent: on-device or the vendor's cloud (e.g. Google recognizer) per device settings. The app has no say and no visibility.
| Reply | `TextToSpeech` (Android TTS engine) | On-device engine; voice data may come from the vendor's network voices per device settings. No audio leaves through AgentCall.
| Backend storage | In-memory session messages (text only) | `completed` calls: 60 min retention (`COMPLETED_RETENTION_MS`, `service.ts`); `cancelled`: 5 min. `sweepStaleSessions` expires them. Process restart clears all.
| Local storage | Room DB (`call_records`, `transcript_messages`) | On the phone; survives app restarts; wiped on uninstall. No encryption at rest.

### Honest claim check

- "Your voice stays on your device" — **true with respect to AgentCall**: no
  audio bytes ever reach the backend. Caveat: the device speech/TTS engines
  may use their own cloud services per device settings; the app cannot
  guarantee offline recognition.
- "Encrypted in transit, deleted after N days" — transcripts travel over
  HTTPS/WSS in production; server-side retention is ~1 h (completed) in
  memory, not days.
- No right-to-erasure endpoint exists; deleting a call's local data requires
  uninstalling the app (or a future Settings "delete my data" action).

### What the Settings copy says (added this session)

Settings → Privacy & Data states: voice stays on device, backend receives only
text, transcripts are in-memory for ~1 h, and the device-speech-engine caveat.
Copy matches this audit.
