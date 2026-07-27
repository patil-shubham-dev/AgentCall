# Architecture Consolidation Report

**Date:** 2026-07-27
**Decision:** ADR-0017 — Consolidate on VoiceBridge Backend

---

## Deleted (hard removed from working tree)

| Path | Reason |
|------|--------|
| `daemon/` (entire directory) | v2 alpha — never reached executable state. 39 source files, 9 test files, full node_modules (~60MB). No executable entry point, all stores in-memory-only. |
| `mobile/ios-archived/` (entire directory) | Abandoned iOS app. Placeholder `example.com` URLs. WebRTC architecture diverged from Android's TTS/STT approach. Git history preserved. |
| `Dockerfile` (repo root) | Superseded by `backend/Dockerfile` (multi-stage, non-root user, HEALTHCHECK, read-only FS) |

## Moved to Archive

### `docs/archive/consolidation-2026-07/`

| Path | Reason |
|------|--------|
| `AGENTCALL_RUNTIME_SPEC.md` | v2 daemon runtime spec — abandoned |
| `AGENTCALL_V2_ARCHITECTURE.md` | v2 architecture — superseded by ADR-0017 |
| `ANDROID_V2_SPEC.md` | v2 Android plans — abandoned |
| `VISION_V2.md` | v2 vision document — superseded |
| `PRESENCE_ENGINE.md` | v2 presence engine spec — unimplemented |
| `PERMISSION_MODEL.md` | v2 permission model — unimplemented |
| `DEVICE_ROUTING.md` | v2 device routing spec — unimplemented |
| `MIGRATION_PLAN.md` | Plan to migrate v1→v2 — moot |
| `VOICEBRIDGE_FUTURE_PLAN.md` | Future plans based on abandoned direction |
| `SYSTEM_ARCHITECTURE.md` | 11-microservice architecture — obsolete (doc already had WARNING header) |
| `ARCHITECTURE_STRESS_TEST.md` | Entirely about the daemon architecture |
| `EXECUTIVE_SUMMARY.md` | Described daemon as current architecture |
| `IMPLEMENTATION_BACKLOG.md` | Milestones referencing daemon v2 |
| `docs/01-architecture-design.md` through `docs/10-privacy-compliance.md` (7 files) | Deprecated 11-microservice design docs (already had deprecation headers) |

## Fixed In Place (corrected, not deleted)

| Document | Changes |
|----------|---------|
| `README.md` | "8 MCP tools" → "5 MCP tools". Removed "circuit breaker, dead-letter queue" claim from feature list. Replaced 11-microservice architecture diagram with accurate single-port + MCP-server diagram. Updated architecture reference link. |
| `CHANGELOG.md` | Added entry noting daemon removal, iOS removal, Redis removal from plans. Corrected v1.0.0 entry: "circuit breaker, dead-letter queue" → "retry with exponential backoff" with note that circuit breaker/DLQ were not implemented. Removed unverifiable "42,000 ops/sec" specific claim from load test entry. |
| `ARCHITECTURE.md` | No changes needed — already accurately described the current implementation. |
| `docs/CURRENT_STACK.md` | Corrected "Storage: None (in-memory Maps)" → actual storage options. Replaced "Planned" table (PostgreSQL, Redis, FCM, etc.) with accurate status markers. Fixed backend version from v2.0.0 → v1.0.0. |
| `ROADMAP.md` | Updated references from SYSTEM_ARCHITECTURE.md → ARCHITECTURE.md. Removed "8 tools" → "5 tools". Removed Redis from Phase 2. |
| `AGENTS.md` | Removed "Redis 7" from project context database listing. |
| `KNOWN_LIMITATIONS.md` | No changes needed — already accurate and well-written. |

## ADR Changes

| # | Title | Change |
|---|-------|--------|
| ADR-0011 | MCP as Sole Protocol | Status: `Accepted` → `Superseded by ADR-0017` |
| ADR-0012 | SQLite Storage | Status: `Accepted` → `Superseded by ADR-0017` |
| ADR-0013 | SSE as Default MCP Transport | Status: `Accepted` → `Superseded by ADR-0017` |
| ADR-0014 | Text-First Communication | Status: `Accepted` → `Superseded by ADR-0017` |
| ADR-0015 | Single-User Architecture | Status: `Accepted` → `Superseded by ADR-0017` |
| ADR-0016 | Binary Permission Model | Status: `Accepted` → `Superseded by ADR-0017` |
| ADR-0017 | Consolidate on VoiceBridge Backend | **NEW** — Created documenting the consolidation decision |

## Salvaged From the Daemon

**Config loading pattern** — ported to `backend/src/config/`:
- `backend/src/config/schema.ts` — Zod schema for all config values with type coercion and validation
- `backend/src/config/loader.ts` — env-based loading with deep-freeze immutability (daemon had 5-layer; backend only needs env-based)
- `backend/src/config/config.ts` — barrel file
- `backend/src/config/version.ts` — config schema version constant
- Added `zod` as a dependency in `backend/package.json`

Not ported:
- The daemon's file-based config, CLI args, and AGENTCALL_CONFIG JSON env var support — backend doesn't need these
- The daemon's custom JSON-RPC protocol layer — the official `@modelcontextprotocol/sdk` handles this correctly

## File/Size Impact

| Metric | Pre-Consolidation | Post-Consolidation | Delta |
|--------|------------------|-------------------|-------|
| Total files (excl node_modules) | ~2,263 | ~2,194 | **-69** |
| TypeScript source files | 130 | 85 | **-45** (daemon removal + 4 new config files net) |
| Test files | 13 | 5 | **-8** (daemon tests removed) |
| Markdown files | 219 | 222 | **+3** (ADR-0017 + archive NOTES.md + this report) |
| Directories removed | — | daemon/, mobile/ios-archived/ | **2 directories** |
| Dependencies removed | — | better-sqlite3, @types/better-sqlite3 | **2 packages** (was in daemon only) |

Dependency added: `zod` in `backend/package.json` (needed for config schema validation).

## Risk Items Discovered

1. **No breaking imports** — verified that `backend/` and `mcp-server/` had zero imports from `daemon/`. Deletion was clean.

2. **CI still valid** — `ci.yml` and `ci-cd.yml` only reference `backend/` and `mcp-server/`. No daemon references. No changes needed.

3. **`EXECUTIVE_SUMMARY.md` and `ARCHITECTURE_STRESS_TEST.md` described the daemon as the primary architecture** — both moved to archive. These were not linked from `docs/README.md` but could have confused new readers.

4. **The `zod` import in the new `backend/src/config/` files needs the dependency in `backend/package.json`** — confirmed added. The module compiles cleanly (`tsc --noEmit` passes).

5. **Root-level Dockerfile was never the active build file** — `infra/docker-compose.yml` and `Dockerfile` in `backend/` and `mcp-server/` all reference the `backend/Dockerfile`. Root Dockerfile was a legacy artifact.

6. **`mobile/android/` references to the default host `dydcghsn0my6-production-*.suga.run` in `ApiClient.kt`** — still present. This was out of scope for this consolidation pass. A developer must modify the Android source to point to a local instance.
