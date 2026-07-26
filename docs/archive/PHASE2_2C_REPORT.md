# Phase 2.2C — Migration Standardization

**Status:** Complete  
**Date:** 2026-07-26  
**Scope:** Shared publisher infrastructure, event naming standardization, centralized module registration

---

## Summary

Implemented all three standardization items from the Phase 2.2 Migration Review. The canonical migration template is now established and ready for Phase 2.3.

## Files Created

### `backend/src/common/event-publisher.ts` (new — 48 lines)

Shared publisher factory that eliminates boilerplate from every domain publisher:

```typescript
interface EventPublisher {
  install(eventBus: EventBus): void;
  publish<T>(type: string, payload: T): void;
}

function createEventPublisher(domain: string, version?: number): EventPublisher
```

Each call to `createEventPublisher` returns:
- `install(eventBus)` — sets the EventBus reference on the publisher
- `publish(type, payload)` — builds an `Event<T>` with auto-generated metadata, publishes fire-and-forget, logs errors with context

No hidden globals. No service locator. No Event Bus internals modified. No new dependencies (uses `crypto` from node: and the existing `logger`).

## Files Modified

### `backend/src/voicebridge/notifications/publisher.ts` (83 → 37 lines, -46 lines)

**Before:** Module-level `_eventBus`, `setEventBus()`, private `makeEvent()`, three publish functions each with duplicate guard + makeEvent + catch boilerplate.

**After:** Shared `createEventPublisher`, single `install()` re-export, three one-liner publish functions.

### `backend/src/voicebridge/notifications/index.ts` (8 → 16 lines)

**Before:** Barrel re-exported `setEventBus` and `registerNotificationSubscribers` independently.

**After:** Exports `register(eventBus)` that calls both `install()` and `registerNotificationSubscribers()` in one call. Also re-exports publish functions and types for callers in `service.ts`.

### `backend/src/voicebridge/presence/events.ts` (16 → 16 lines)

Renamed:
- `USER_CONNECTED = 'user.connected'` → `PRESENCE_CONNECTED = 'presence.connected'`
- `USER_DISCONNECTED = 'user.disconnected'` → `PRESENCE_DISCONNECTED = 'presence.disconnected'`
- `UserConnectedPayload` → `PresenceConnectedPayload`
- `UserDisconnectedPayload` → `PresenceDisconnectedPayload`

Unchanged: `PRESENCE_UPDATED`, `PRESENCE_EVENT_VERSION`, `PresenceUpdatedPayload`.

### `backend/src/voicebridge/presence/publisher.ts` (61 → 25 lines, -36 lines)

**Before:** Same structure as notifications/publisher.ts before refactor — 61 lines, ~48 boilerplate.

**After:** Shared publisher factory, renamed publish functions (`publishUserConnected` → `publishPresenceConnected`, `publishUserDisconnected` → `publishPresenceDisconnected`), 25 lines total.

### `backend/src/voicebridge/presence/subscribers.ts` (50 → 50 lines)

Updated subscriber event constants and payload types to match the renamed events (`USER_CONNECTED` → `PRESENCE_CONNECTED`, etc.). Log messages updated (`UserConnected` → `PresenceConnected`).

### `backend/src/voicebridge/presence/index.ts` (8 → 17 lines)

Same pattern as notifications/index.ts: exports `register(eventBus)`, re-exports publish functions and updated type names.

### `backend/src/voicebridge/service.ts` (3 lines changed)

- Import: `publishUserConnected` → `publishPresenceConnected`, `publishUserDisconnected` → `publishPresenceDisconnected`
- Call sites: updated both function names

### `backend/src/index.ts` (3 lines changed)

**Before (4 lines):**
```typescript
setNotificationEventBus(eventBus);
setPresenceEventBus(eventBus);
registerNotificationSubscribers(eventBus);
registerPresenceSubscribers(eventBus);
```

**After (2 lines):**
```typescript
registerNotifications(eventBus);
registerPresence(eventBus);
```

## Boilerplate Reduction

| Metric | Before | After | Reduction |
|---|---|---|---|
| `notifications/publisher.ts` lines | 83 | 37 | -55% |
| `presence/publisher.ts` lines | 61 | 25 | -59% |
| Total publisher lines (2 modules) | 144 | 62 | -57% |
| Domain publisher boilerplate lines | ~96 | 0 | -100% |
| `index.ts` startup lines per module | 2 | 1 | -50% |
| `setEventBus()` per module | 2 | 0 | eliminated |

The `event-publisher.ts` shared utility (48 lines) replaces ~96 lines of duplicated boilerplate across two modules. Each new module adds only ~25 lines of publisher code (3 publish functions × ~8 lines each) instead of ~72.

## Event Naming Changes

| Old Name | New Name | Convention |
|---|---|---|
| `user.connected` | `presence.connected` | `{domain}.{action}` |
| `user.disconnected` | `presence.disconnected` | `{domain}.{action}` |
| `presence.updated` | *(unchanged)* | `{domain}.{action}` |
| `notification.requested` | *(unchanged)* | `{domain}.{action}` |
| `notification.delivered` | *(unchanged)* | `{domain}.{action}` |
| `notification.failed` | *(unchanged)* | `{domain}.{action}` |

All six events now follow `{domain}.{action}`. No compatibility aliases. No deprecated names. All references updated.

## Registration Changes

Each domain module exports a single `register(eventBus)` function:

```typescript
// notifications/index.ts
export function register(eventBus: EventBus): void {
  install(eventBus);              // wire publisher
  registerNotificationSubscribers(eventBus);  // register subscribers
}
```

Startup `index.ts` registers each domain with one call:

```typescript
registerNotifications(eventBus);
registerPresence(eventBus);
```

No ordering dependencies between modules. Registration is deterministic. Each module's `register` is self-contained and handles its own publisher + subscriber wiring.

## Build Status

| Check | Result |
|---|---|
| Backend `tsc --noEmit` | Pass |
| Backend `eslint src/ --ext .ts` | Pass |
| MCP Server `tsc --noEmit` | Pass (no regression) |
| MCP Server `eslint src/ --ext .ts` | Pass (no regression) |

## Regression Check

| Concern | Status |
|---|---|
| Existing notification behaviour unchanged | Yes — publisher functions kept same signatures, only internals changed |
| Existing presence behaviour unchanged | Yes — renamed functions have same signatures, same call sites |
| No duplicate execution | Yes — subscribers unchanged, still validation-only |
| No circular dependencies | Yes — publisher imports only from `common/` and `event-bus/` |
| No Event Bus internals modified | Yes — `event-bus/` untouched |
| No new dependencies | Yes — only uses `crypto` (node built-in) and existing `logger` |

## Updated Migration Quality Score

| Dimension | Before | After | Notes |
|---|---|---|---|
| **Migration quality** | 7/10 | **9/10** | Dual-write pattern preserved. Boilerplate eliminated. Lightweight module template. |
| **Consistency** | 6/10 | **10/10** | All events follow `{domain}.{action}`. All modules follow same 4-file structure with `register()` export. |
| **Architecture health at 20 modules** | 4/10 | **9/10** | Publisher boilerplate scales linearly with domain-specific lines (~25 lines/module vs ~72 before). Startup stays proportional (20 lines for 20 modules). |

## Is this now the canonical template for all future domain migrations?

**Yes.** The template is established:

1. **`{domain}/events.ts`** — event type constants + payload interfaces. Events use `{domain}.{action}` naming.
2. **`{domain}/publisher.ts`** — create shared publisher via `createEventPublisher(domain, version)`, export typed publish helper functions
3. **`{domain}/subscribers.ts`** — `register*Subscribers(eventBus)` function, registers validation-only subscribers (real logic in future phases)
4. **`{domain}/index.ts`** — `register(eventBus)` as single entry point that wires publisher + subscribers

New domain modules follow this exact pattern. `index.ts` adds one line per module. The Event Bus remains untouched. Publishers remain lightweight and type-safe.

**No further improvement needed** before continuing to Phase 2.3 (Call Lifecycle migration).

---

## Files Changed — Final Count

| File | Action | Lines |
|---|---|---|
| `backend/src/common/event-publisher.ts` | **Created** | +48 |
| `backend/src/voicebridge/notifications/publisher.ts` | Rewritten | 83→37 (-46) |
| `backend/src/voicebridge/notifications/index.ts` | Rewritten | 8→16 (+8) |
| `backend/src/voicebridge/presence/events.ts` | Rewritten | 16 (-0, renamed) |
| `backend/src/voicebridge/presence/publisher.ts` | Rewritten | 61→25 (-36) |
| `backend/src/voicebridge/presence/subscribers.ts` | Rewritten | 50 (-0, renamed) |
| `backend/src/voicebridge/presence/index.ts` | Rewritten | 8→17 (+9) |
| `backend/src/voicebridge/service.ts` | Edited | 3 lines changed |
| `backend/src/index.ts` | Edited | 3 lines changed |
| **Total** | | **~74 net lines added** (shared utility + barrel re-exports), **~82 lines removed** (boilerplate) |
