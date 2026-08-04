# VoiceBridge v1.0.0

## Version

| Field | Value |
|-------|-------|
| **Version** | 1.0.0 |
| **Package version** | 1.0.0 (package.json) |
| **Release name** | "Solo Bridge" |
| **Release date** | July 26, 2026 |
| **Status** | Feature-complete, production-ready |

## Supported Platforms

| Platform | Support | Notes |
|----------|---------|-------|
| **Backend runtime** | Node.js 20 LTS (Alpine) | Docker image based on `node:20-alpine` |
| **Database** | PostgreSQL 16 | Required for `database` persistence mode |
| **Deployment** | Docker Compose, Kubernetes 1.28+ | K8s manifests provided for production |
| **Reverse proxy** | Caddy 2 | Auto TLS, config provided |
| **STUN/TURN** | coturn | Config provided in `infra/coturn/` |
| **Mobile — Android** | Android 12+ (API 31+) | Kotlin, Jetpack Compose |
| **Mobile — iOS** | iOS 16+ | Swift, SwiftUI (archived — not actively maintained) |
| **MCP client** | Any MCP-compatible AI | OpenCode, Claude, Cursor, etc. |

## Breaking Changes

This is the initial v1.0.0 release. There are no prior versions to break from.

**Design constraints (not breaking changes, but must be understood):**

- Single-service architecture — all components run in one process
- Single-token auth (SERVICE_TOKEN) — no multi-user or RBAC
- Per-process session lock — no cross-pod coordination
- In-memory session map always allocated, even in database-only mode
- 14 no-op event subscribers registered at startup (log-only)

## Migration Notes

### From pre-v1.0 prototypes

If you were running a pre-release prototype:

1. **Auth:** Switch from dev-service-token to a random SERVICE_TOKEN
2. **Persistence:** Set `PERSISTENCE_MODE=database` with `DATABASE_URL`
3. **Schema:** Apply `backend/src/voicebridge/repositories/schema.sql`
4. **Probes:** Remove any manual POST /api/v1/ready calls — readiness is now auto-computed
5. **Config:** Replace any `JWT_SECRET`, `JWT_PUBLIC_KEY`, `POSTGRES_PASSWORD` env vars with `SERVICE_TOKEN` and `DATABASE_URL`

### Rolling back

- From `database` mode to `dual-write`: change env, restart. No data loss.
- From `database` mode to `memory`: change env, unset DATABASE_URL, restart. DB snapshot retained.

## Known Limitations

See [docs/archive/KNOWN_LIMITATIONS.md](./docs/archive/KNOWN_LIMITATIONS.md) for the complete register.

**Key limitations:**
- Single-user auth model (SERVICE_TOKEN shared across all clients)
- No cross-pod session lock (per-process promise-chain only)
- WebSocket connections dropped on rolling update (no drain mechanism)
- Timers are per-process — if the scheduling pod dies, timers are rebuilt on restart
- No migration tooling — schema applied manually
- InMemory repos always allocated (memory overhead in all modes)

## Support Policy

| Channel | Response Time | Scope |
|---------|--------------|-------|
| GitHub Issues | Best-effort | Bug reports, feature requests |
| No SLA | — | Community-supported project |

- Security issues: report via GitHub Issues with `[SECURITY]` tag
- No guaranteed patch cadence
- No long-term support (LTS) branches
- Breaking changes may occur in minor versions before v2.0.0

## Deprecation Policy

- Deprecated features will be marked in release notes one version before removal
- Removal candidates will be listed in a `DEPRECATED.md` file (not yet created)
- No formal deprecation period — this is a v1.0 project
