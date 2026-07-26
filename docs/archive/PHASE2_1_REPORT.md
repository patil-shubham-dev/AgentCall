# Phase 2.1 — Event Bus Integration Report

**Status:** Complete  
**Date:** 2026-07-26  
**Files changed:** 1 (`backend/src/index.ts`, +14 lines)

## Summary

Integrated the Event Bus into application startup. No business logic was migrated, no real events are published, no services are subscribed.

## Changes

### `backend/src/index.ts`

1. **Imports:** Added `DefaultEventBus` and `createEventLoggerHook` from the Event Bus module.
2. **Instantiation:** Created `DefaultEventBus` instance in `main()`, before Fastify app creation.
3. **Logger hooks:** Registered `createEventLoggerHook()` on the bus (before/after/error).
4. **Composition root:** Registered the bus on Fastify via `app.decorate('eventBus', eventBus)` with TypeScript declaration merging, making it available via `request.eventBus` or `app.eventBus` for future services.
5. **Graceful shutdown:** Called `await eventBus.shutdown()` in the `shutdown` handler after `app.close()` and `signalingServer?.close()`.

## Design decisions

- **No global mutable state** — the bus is scoped to `main()` and accessible only through Fastify's DI.
- **Shutdown order:** HTTP server closed first → WebSocket server closed → Event Bus shutdown (marks bus as shut down, clears registry and hooks) → exit.
- **No factory/module-locals** — kept inline in `main()` for simplicity; extraction to a `createApp` factory can happen when DI is formalized.

## Verification

- `npm run typecheck` (backend) — pass
- `npm run lint` (backend) — pass
- `npm run typecheck` (mcp-server) — pass (no regression)
- `npm run lint` (mcp-server) — pass (no regression)
- No new files introduced
- No circular dependencies introduced
- Zero business logic changes
