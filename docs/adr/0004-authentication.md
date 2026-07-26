# ADR-0004: Authentication Model

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

The current system uses a single `SERVICE_TOKEN` for all requests. This does not support multi-user, multi-provider, or per-device authentication. The canonical API_SPEC.md requires JWT and Provider API Key auth.

## Decision

Adopt a two-tier authentication model:

### Tier 1: User Authentication (JWT)

- **Access Token**: JWT, 15-minute expiry, signed with RS256
- **Refresh Token**: Opaque 128-bit random, stored SHA-256 hashed in DB, 30-day expiry, rotation on use
- **JWT Claims**: `sub` (user_id), `did` (device_id), `role`, `iat`, `exp`, `jti`

### Tier 2: Provider Authentication (API Key)

- **API Key**: Prefixed key for identification (e.g., `ac_xxxx...`)
- **Storage**: Full key stored as SHA-256 hash in `api_keys` table
- **Permissions**: Scoped per key (e.g., `["create_call", "query_presence"]`)
- **Service-to-service**: Internal `SERVICE_TOKEN` for MCP Server → Backend (to be replaced)

### Flow

```
User Login:
  User → OAuth Provider → Callback → AgentCall issues JWT + Refresh Token
  ↓
  User stores JWT in device secure storage

Provider Connection:
  Provider → API Key → AgentCall validates → Provider-specific session created
  ↓
  Each provider has independent credentials

API Request:
  Request → JWT or API Key → Auth Service validates → Route to handler
```

## Alternatives Considered

- **Passkey-only**: Too new, limited platform support.
- **OAuth-only**: Requires every client to implement OAuth flow. API Key is simpler for MCP.
- **Session cookies**: Not suitable for mobile apps and MCP tools.

## Consequences

**Positive:**
- Clear separation between user auth and provider auth
- JWT short expiry limits token theft damage
- API keys work well for automated MCP clients
- Refresh tokens enable long-lived sessions

**Negative:**
- JWT revocation requires blacklist (Redis)
- API key rotation adds operational overhead
- Two auth mechanisms increase code surface

## Tradeoffs

- Two auth tiers vs. unified auth: more complexity but better separation of concerns
- RS256 vs. HS256: RS256 allows public key sharing without secret exposure

## Future Work

- OAuth 2.0 implementation (Google, GitHub, Apple)
- Device pairing flow (QR code)
- API key management UI in mobile app
- JWT revocation list garbage collection
