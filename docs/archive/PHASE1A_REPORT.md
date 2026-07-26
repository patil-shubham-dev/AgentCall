# Phase 1A — Foundation Hardening: Completion Report

**Date:** 2026-07-26
**Status:** PASS (backend + MCP server verified)

---

## Summary

Phase 1A hardened the project foundations by removing default credentials, adding config validation, fixing the ESLint configuration (which was broken), gating debug-only code paths behind environment checks, re-enabling Content Security Policy, removing dead code, and tightening TypeScript types.

### Backend — Verified Clean

| Check | Result |
|-------|--------|
| `tsc --noEmit` | **PASS** — 0 errors |
| `eslint src/ --ext .ts` | **PASS** — 0 errors, 0 warnings |

### MCP Server — Verified Clean

| Check | Result |
|-------|--------|
| `tsc --noEmit` | **PASS** — 0 errors |
| `eslint src/ --ext .ts` | **PASS** — 0 errors, 0 warnings |

### Android

Changes made to `ApiClient.kt`, `Color.kt`, `Theme.kt`, and `build.gradle.kts`. **Build not verified** — no Android SDK available in this environment. Code changes are backward-compatible and follow standard Android conventions.

---

## Changes Made

### 1. Hardened service credentials

- **`backend/src/common/config.ts`** — Removed `'dev-service-token'` default from `SERVICE_TOKEN`. Added `parseIntSafe()` that throws on NaN for all port-like values. Removed unused `backendApiUrl`. Added `validateConfig()` that throws when required env vars (`SERVICE_TOKEN`) are missing at startup.
- **`mcp-server/src/config.ts`** — Same treatment: removed `'dev-service-token'` default, added NaN-safe parsing, added `validateConfig()`.
- **`backend/src/index.ts`** — Calls `validateConfig()` at startup.
- **`mcp-server/src/index.ts`** — Calls `validateConfig()` at startup.
- **`.env.example`** — Changed `SERVICE_TOKEN=dev-service-token` to `SERVICE_TOKEN=` with comment marking it required.

### 2. Re-enabled Content Security Policy

- **`backend/src/index.ts`** — Replaced `contentSecurityPolicy: false` with explicit policy (`default-src 'self'`, `connect-src 'self' ws: wss:`, etc.). Kept `crossOriginEmbedderPolicy: false` for WebSocket compatibility.

### 3. Gated debug logging in production

- **`backend/src/routes.ts`** — `inspectBody()` returns `''` in production (`config.nodeEnv === 'production'`), preventing request-body logging in production.

### 4. Fixed ESLint configuration

- **Deleted root `.eslintrc.json`** — Was broken because ESLint resolved plugins relative to the root directory, but `@typescript-eslint/eslint-plugin` was installed under `backend/node_modules/`.
- **Created `backend/.eslintrc.json`** — Same config, now correctly found by ESLint when running from `backend/`.
- **Created `mcp-server/.eslintrc.json`** — Same config for MCP server.

### 5. Fixed pre-existing lint errors

- **`backend/src/signaling/server.ts:56`** — Removed unused `_state` destructuring from `clientRateLimits` loop.
- **`backend/src/signaling/server.ts:110`** — Added comment to empty catch block.
- **`backend/src/voicebridge/service.ts:8`** — Removed unused `CallStatus` import.
- **`mcp-server/src/tools.ts:2`** — Removed unused `logger` import.
- **`mcp-server/src/tools.ts:53,54,79,105,106,113`** — Replaced `result.data!` non-null assertions with proper type narrowing via discriminated union.
- **`mcp-server/src/sse.ts:28`** — Replaced `ALLOWED_ORIGINS[0]!` with `ALLOWED_ORIGINS[0] ?? '*'`.

### 6. Fixed MCP client type safety

- **`mcp-server/src/client.ts`** — Changed `ApiResponse<T>` from an interface with optional fields to a discriminated union, enabling TypeScript type narrowing in tools.ts without non-null assertions.

### 7. Removed dead code

- **`backend/src/voicebridge/types.ts`** — Removed unused `SendMessageInput` and `AudioChunk` interfaces (6 lines).
- **`backend/src/routes.ts`** — Removed unused `strictRateLimit` declaration.
- **`mobile/android/.../Color.kt`** — Removed unused `WaveformIdle`, `WaveformSpeaking`, `WaveformMuted` colors.
- **`mobile/android/.../Theme.kt`** — Removed unused `waveformIdle`, `waveformSpeaking`, `waveformMuted` theme properties.

### 8. Android hardening

- **`mobile/android/.../ApiClient.kt`** — Gated `HttpLoggingInterceptor` level behind `BuildConfig.DEBUG` (HEADERS in debug, NONE in release). Changed `DEFAULT_HOST` constant to `BuildConfig.DEFAULT_HOST` for build-config-driven configuration.
- **`mobile/android/app/build.gradle.kts`** — Enabled `buildConfig = true`, added `buildConfigField` for `DEFAULT_HOST`.

---

## Files Changed (Phase 1A only)

| File | Change |
|------|--------|
| `backend/.eslintrc.json` | **NEW** — ESLint config moved from root |
| `mcp-server/.eslintrc.json` | **NEW** — ESLint config |
| `.eslintrc.json` | **DELETED** — moved to project dirs |
| `backend/src/common/config.ts` | Removed default token, NaN-safe parsing, validation |
| `backend/src/index.ts` | Re-enabled CSP, added validateConfig() call |
| `backend/src/routes.ts` | Gated inspectBody in prod, removed strictRateLimit |
| `backend/src/signaling/server.ts` | Fixed lint: unused var, empty catch |
| `backend/src/voicebridge/service.ts` | Fixed lint: unused import |
| `backend/src/voicebridge/types.ts` | Removed SendMessageInput, AudioChunk |
| `mcp-server/src/config.ts` | Removed default token, NaN-safe parsing, validation |
| `mcp-server/src/client.ts` | ApiResponse → discriminated union |
| `mcp-server/src/index.ts` | Added validateConfig() call |
| `mcp-server/src/sse.ts` | Replaced non-null assertion |
| `mcp-server/src/tools.ts` | Removed unused import, replaced non-null assertions |
| `.env.example` | Marked SERVICE_TOKEN as required |
| `mobile/android/.../Color.kt` | Removed 3 unused colors |
| `mobile/android/.../Theme.kt` | Removed 3 unused theme properties |
| `mobile/android/.../ApiClient.kt` | BuildConfig gating, DEFAULT_HOST from BuildConfig |
| `mobile/android/app/build.gradle.kts` | buildConfig=true, buildConfigField |

---

## Remaining Blockers / Future Work

1. **Android build unverifiable** — Android SDK not available in this environment. Changes are standard-practice but should be verified by a developer with Android Studio.
2. **iOS** — Archived iOS code has unused `waveformIdle` / `waveformMuted` colors in `AppTheme.swift`. Not addressed here since the iOS directory is archived.
3. **Phase 1B scope** — No Event Bus, no service decomposition, no provider abstraction, no persistence layer implemented in this phase.
