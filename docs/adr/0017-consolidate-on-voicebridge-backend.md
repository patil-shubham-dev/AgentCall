# ADR-0017: Consolidate on VoiceBridge Backend

**Status:** Accepted
**Date:** 2026-07-27

---

## Context

After a full codebase audit (see `AGENTCALL_AUDIT_REPORT.md`), the project was found to have two competing architectures that both showed "Accepted" status:

- **VoiceBridge v1** (`backend/` + `mcp-server/`): PostgreSQL persistence, REST API + WebSocket signaling, Android app, Docker/K8s deployment, fully testable with 48 passing tests.
- **AgentCall v2** (`daemon/`): MCP-only protocol, SQLite storage (declared but unimplemented), single-user architecture, local-first runtime. All stores were in-memory-only with no persistence. The daemon had no executable entry point.

The two sets of ADRs (0001-0010 for v1, 0011-0016 for v2) both showed "Accepted" with no supersession between them, creating an ambiguous architectural direction.

## Decision

Consolidate on the VoiceBridge v1 architecture:

- **`backend/`** is the kept foundation — it has real persistence (PostgreSQL + in-memory with 4 modes), call lifecycle management, WebSocket signaling, production-grade observability (Pino, health checks, metrics), and a working Android client.
- **`mcp-server/`** is the kept adapter layer — it bridges AI systems to the backend via the official `@modelcontextprotocol/sdk`, supporting both stdio and SSE/StreamableHTTP transports.
- **`daemon/` (v2) is removed** — the daemon never reached a runnable state (no executable entry point, no persistence despite `better-sqlite3` dependency, no FCM/APNs push despite being referenced in 100+ documentation files).
- **`mobile/ios-archived/` is removed** — the iOS app was archived with placeholder `example.com` URLs and used a fundamentally different architecture (WebRTC) from Android (TTS/STT). Its historical record is preserved in git.
- **ADR-0011 through ADR-0016 are superseded** — they document a v2 direction that was not executed and is now abandoned.
- **ADR-0001 through ADR-0010 remain Accepted** — they describe the VoiceBridge v1 architecture that is the kept foundation.

## Alternatives Considered

### Alternative 1: Continue developing the daemon alongside the backend

The daemon had a more sophisticated config loader (5-layer precedence, Zod validation, deep-freeze) and a richer MCP adapter implementation. However, completing it to a production-ready state would require:
- Implementing SQLite persistence (the stores were in-memory only)
- Creating an executable entry point
- Building phone/device communication (the backend already has this)
- Implementing push notifications (documented but not built)
- All of this duplicates what the backend already provides.

### Alternative 2: Replace the backend with the daemon

The backend has real persistence, a working Android client, production deployment (Docker, K8s, Caddy), and 48 passing tests. Replacing it with the daemon would lose all of this working functionality. The daemon's in-memory-only stores and missing entry point make it unsuitable as a replacement.

## Consequences

### Positive

- Single architectural direction eliminates ambiguity and split-brain development
- Removes ~60MB of unused code (daemon/ with node_modules)
- Documentation can be corrected to reflect reality
- Config pattern from the daemon was ported to `backend/src/config/` (Zod schema + deep-freeze)
- The `mcp-server` continues to provide MCP access to the backend for AI systems

### Negative

- The daemon's richer MCP adapter (with middleware chain, subscription registry, per-tool auth) is lost — the official MCP SDK's built-in handling is now the sole protocol layer
- The daemon's config loader (file-based, CLI args, nested env var overrides) is not available — only the env-based loader was ported
- Any future migration toward SQLite would need to be re-evaluated from scratch
- The iOS codebase is no longer in the working tree (git history preserved)

### Neutral

- ADRs 0011-0016 are retained as historical records of a decision path that was explored and abandoned
- `docs/archive/consolidation-2026-07/` captures historically-interesting-but-obsolete documentation

## Compliance

- No new code should import from `daemon/` or `mobile/ios-archived/` (directories removed from working tree)
- Documentation describing the daemon architecture, iOS app, or unimplemented features (Redis, circuit breaker/DLQ on event bus, FCM push, device routing) must be labeled as historical or removed
- ADRs 0011-0016 remain in the repository but with `Superseded by ADR-0017` status

## Notes

This consolidation was prompted by the full codebase audit in `AGENTCALL_AUDIT_REPORT.md`, which found that the daemon was the single biggest blocker to an end-to-end message flow: it had no executable entry point, no persistence, and no integration with the backend.
