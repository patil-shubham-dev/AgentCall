# Phase 2.2 — Migration Architecture Review

**Reviewer:** Opencode (automated static analysis)  
**Scope:** Phase 2.2A (Notifications) + Phase 2.2B (Presence)  
**Commitment:** No code changes, no recommendations implemented — review only.

---

## 1. Are we repeating boilerplate unnecessarily?

**Yes — the duplication is the single biggest concern in the current architecture.**

A side-by-side comparison of the two four-file modules reveals near-identical structure across every file:

### publisher.ts — 15 lines of boilerplate repeated verbatim

Both modules contain:

```
let _eventBus: EventBus | undefined;
export function setEventBus(bus: EventBus): void { _eventBus = bus; }
```

Then a private `makeEvent<T>()` helper that is structurally identical except for the `version` constant and `source` string:

| Notifications | Presence |
|---|---|
| `version: NOTIFICATION_EVENT_VERSION` | `version: PRESENCE_EVENT_VERSION` |
| `source: 'voicebridge.notifications'` | `source: 'voicebridge.presence'` |

Every `publish*()` function follows the same 5-line template:

```
if (!_eventBus) return;
const event = makeEvent<T>(CONSTANT, { ...payload });
_eventBus.publish(event).catch((err) => {
  logger.error({ err, ...context }, 'EventBus publish failed (domain.event)');
});
```

This template is repeated **6 times** across the two modules (3 in notifications, 3 in presence).

### subscribers.ts — registration pattern duplicated

Both `register*Subscribers(eventBus)` functions contain the same loop-like structure:

```
eventBus.subscribe<TPayload>(EVENT_A, async (event) => {
  logger.info({ ...context }, '[EventBus] EventA received ...');
}, { name: 'domain.a-logger', scope: 'domain' });
```

The only variation is the payload type, event constant, log message, and subscription name.

### index.ts — 8-line barrel files, structurally identical

Both re-export `setEventBus`, `register*Subscribers`, and the event payload types. The only thing that changes is the prefix.

### Quantified duplication

| Component | Lines | Unique content | Boilerplate |
|---|---|---|---|
| `notifications/publisher.ts` | 83 | ~35 | ~48 |
| `presence/publisher.ts` | 61 | ~13 | ~48 |
| Total publisher boilerplate | | | **~96 lines duplicated** |

~80% of `publisher.ts` content is boilerplate that could be shared.

---

## 2. Should publishers share common infrastructure?

**Yes, and this is the highest-impact refactoring opportunity.**

The following elements are invariant across all domain publishers:

1. **The module-level `_eventBus` variable + `setEventBus()` setter** — identical in every module
2. **The `makeEvent<T>()` function** — differs only in `version` constant and `source` string
3. **The `if (!_eventBus) return` guard** — universal
4. **The `.catch((err) => logger.error(...))` error handler** — identical pattern
5. **The call to `setEventBus(eventBus)` in `index.ts`** — one line per module, same pattern

A shared `createEventPublisher(domain, version)` factory function could reduce each publisher module from ~60–80 lines to ~20 lines of domain-specific code:

```typescript
// Pseudocode for a shared factory
export const publisher = createPublisher('voicebridge.notifications', 1);
export const publishNotificationRequested = publisher.make('notification.requested',
  (userId: string, notificationType: string, payload: Record<string, unknown>) =>
    ({ userId, notificationType, payload })
);
```

This would also eliminate the `setEventBus` call per module in `index.ts` — a single `EventBus.install(publisher)` call would suffice.

---

## 3. Should subscribers share common infrastructure?

**Yes, but with lower urgency than publishers.**

The subscriber registration pattern is also highly repetitive:

```
eventBus.subscribe<TPayload>(EVENT, async (event) => {
  logger.info({ ...fields }, '[EventBus] EventName received');
}, { name: 'domain.name', scope });
```

A `createLoggingSubscriber(eventBus, eventType, name, scope, logFields)` utility could reduce each subscriber registration to one line. However, subscribers are expected to eventually contain real business logic (actual delivery, not just validation), at which point a shared utility becomes less useful. The boilerplate savings are modest (~3 lines per subscriber, ~9 lines per module).

---

## 4. Are events following a consistent naming convention?

**No — there are three different patterns in use across six events.**

| Event | Pattern | Domain prefix |
|---|---|---|
| `notification.requested` | `{domain}.{action}` | `notification` |
| `notification.delivered` | `{domain}.{action}` | `notification` |
| `notification.failed` | `{domain}.{action}` | `notification` |
| `user.connected` | **`{entity}.{action}`** | `user` |
| `user.disconnected` | **`{entity}.{action}`** | `user` |
| `presence.updated` | `{domain}.{action}` | `presence` |

Three conventions for six events:

- **`notification.{action}`** — domain-first (consistent with Event Bus design)
- **`user.{action}`** — entity-first (inconsistent)
- **`presence.{action}`** — domain-first (returns to convention)

The `user.connected` / `user.disconnected` events should arguably be `presence.connected` / `presence.disconnected` to follow the same `{domain}.{action}` pattern as notifications. Alternatively, all events could adopt `{entity}.{action}`, but that would require renaming the notification events.

**Recommendation:** Standardize on `{domain}.{action}` (the Event Bus's native convention), since the domain is the unit of modularity and `EventBus.subscribe` already groups by type string prefix.

---

## 5. Are payloads sufficiently minimal?

**Yes. No issue here.**

| Event | Payload fields | Notes |
|---|---|---|
| `notification.requested` | `userId`, `notificationType`, `payload` | Contains the original payload — needed for audit/mirroring |
| `notification.delivered` | `userId`, `notificationType` | Minimal identifier |
| `notification.failed` | `userId`, `notificationType`, `error` | Error context included |
| `user.connected` | `userId` | Minimal |
| `user.disconnected` | `userId` | Minimal |
| `presence.updated` | `userId` | Minimal |

No payload contains business logic, session state, or full domain objects. All payloads are flat identifier maps. This is correct.

---

## 6. Are modules remaining independent?

**Yes — at the module level. But assembly coupling is emerging.**

Independence check:

| Criterion | Notifications | Presence |
|---|---|---|
| Own file tree | `voicebridge/notifications/` | `voicebridge/presence/` |
| Cross-imports between modules | None | None |
| Depends on service.ts internals | Yes (calls from `notifyPhone`) | Yes (calls from `registerPhone`) |
| Depends on Event Bus | Yes (via setter) | Yes (via setter) |
| Depends on logger | Yes | Yes |

No module imports another domain module. This is correct. However, both modules are coupled to `service.ts` through their publisher functions being called from within that file. This is inherent to the dual-write pattern (publishing must happen inside the existing function) and is acceptable — it's not a cross-module dependency.

---

## 7. Are there hidden dependencies forming?

**Two concerns, neither critical yet.**

1. **Assembly bottleneck in `index.ts`**

   ```typescript
   setNotificationEventBus(eventBus);
   setPresenceEventBus(eventBus);
   registerNotificationSubscribers(eventBus);
   registerPresenceSubscribers(eventBus);
   ```

   Each new domain module adds two lines to `index.ts` (one `setEventBus`, one `register*Subscribers`). At 20+ modules this becomes a maintenance burden and creates an implicit ordering dependency — all `setEventBus` calls must happen before any subscriber registration. The ordering constraint is not enforced by the type system.

2. **Service.ts publisher import accumulation**

   ```typescript
   import { publishNotificationRequested, ... } from './notifications/publisher.js';
   import { publishUserConnected, ... } from './presence/publisher.js';
   ```

   `service.ts` will accumulate one import per domain per function that needs dual-write. This is not a hidden dependency per se (it's explicit), but it means `service.ts` knows about every domain that uses it as an interception point. Over 20+ domains this file becomes a hub.

---

## 8. Is dual-write implemented consistently?

**Yes — the pattern is identical across both modules.**

```
Existing function():
  publishDomain.EventBefore();      // <-- Event Bus publish
  existingDirectCall();             // <-- unchanged
  publishDomain.EventAfter();       // <-- Event Bus publish (on success)
  publishDomain.EventError();       // <-- Event Bus publish (on failure)
```

Both modules:
- Publish a "requested" event before the direct call
- Publish a "delivered/success" event after successful completion
- Publish a "failed" event after failure or no-op

The difference in function name (`notifyPhone` vs `registerPhone`) is appropriate — they are different business operations that happen to follow the same lifecycle pattern.

---

## 9. Will this pattern still be maintainable after 20+ modules?

**No — not without standardization.**

Projecting the current pattern to 20+ domains:

| Resource | Now (2 domains) | At 20 domains | Problem |
|---|---|---|---|
| Publisher files | 2 | 20 | Each is ~60–80 lines, ~80% boilerplate |
| Subscriber files | 2 | 20 | Each is ~50 lines, ~70% boilerplate |
| Event type files | 2 | 20 | ~20 lines each (manageable) |
| `index.ts` startup lines | 4 | ~40 | Assembly bottleneck, unwieldy |
| `service.ts` publisher imports | 2 | 10–20 (only domains that intercept service.ts) | Accumulating |
| Total new files | 8 | 80 | 80 files to maintain |

The two scaling bottlenecks are:

1. **Publisher boilerplate** — 20 publishers × ~80 lines each = 1600 lines, ~1200 of which are boilerplate
2. **Assembly in `index.ts`** — 40 lines of `set*`/`register*` calls with no type enforcement of ordering

---

## 10. Should anything be standardized before continuing?

**Yes — three things should be standardized before continuing to Phase 2.3.**

### Priority 1: Shared publisher infrastructure

A `common/event-publisher.ts` utility that provides:

- A `createEventPublisher(domain: string, version: number)` factory returning a publisher instance with:
  - `makeEvent<T>(type, payload)` — private, builds Event<T> with auto-generated metadata
  - `publish<T>(event)` — public, with standardized error logging
  - No module-level `_eventBus` variable needed (factory captures it)
- A single `EventBus.setPublisher(publisher)` method or a `publisher.install(eventBus)` method to wire all publishers in one call

This eliminates:
  - The `let _eventBus` + `setEventBus()` boilerplate in every module
  - The `makeEvent()` copy in every module
  - The `.catch()` handler copy in every module
  - Individual `setEventBus()` calls in `index.ts`

### Priority 2: Standardized event naming convention

Adopt `{domain}.{action}` throughout. Rename:
- `user.connected` → `presence.connected`
- `user.disconnected` → `presence.disconnected`
- `presence.updated` → stays as is

This makes event type strings predictable and groupable by domain prefix.

### Priority 3: Centralized module registration

A `registerDomainModules(eventBus, modules)` function or a `DomainModule` interface:

```typescript
interface DomainModule {
  name: string;
  register(eventBus: EventBus): void;
}
```

Each domain module exports a single `register(eventBus)` function that both sets up publishers and registers subscribers. `index.ts` becomes:

```typescript
registerDomainModules(eventBus, [
  notificationsModule,
  presenceModule,
]);
```

Or simpler: just define a `register(eventBus)` export in each domain's `index.ts`:

```typescript
// voicebridge/notifications/index.ts
export function register(eventBus: EventBus): void {
  setPublisherEventBus(eventBus);
  registerNotificationSubscribers(eventBus);
}
```

Then `index.ts` becomes:

```typescript
import { register as registerNotifs } from './voicebridge/notifications/index.js';
import { register as registerPresence } from './voicebridge/presence/index.js';

registerNotifs(eventBus);
registerPresence(eventBus);
```

This consolidates the two-call-per-module pattern into a single call.

---

## Overall Scores

| Dimension | Score | Notes |
|---|---|---|
| **Migration quality** | **7/10** | Semantics and constraints are correct. Dual-write pattern is cleanly implemented. Payloads are minimal. Pattern consistency is high. Penalized for boilerplate. |
| **Consistency** | **6/10** | Excellent within each module, inconsistent between modules. Event naming has drift. Assembly pattern is already diverging from what it would need at scale. |
| **Architecture health at 20 modules** | **4/10** | Does not scale as-is. Boilerplate explosion and assembly entanglement will become blockers around module 8–10. |

## Verdict: Continue migrations — but standardize first.

The dual-write pattern itself is correct and should continue. The per-module semantic design (events, payloads, subscriber structure) is sound. The concern is purely structural: the scaffolding around each module has too much duplication and too little standardization.

**Standardize the three items above before Phase 2.3.** The cost of retrofitting 20 modules later is far higher than refactoring 2 modules now.

---

## Summary Table

| # | Question | Answer |
|---|---|---|
| 1 | Repeating boilerplate unnecessarily? | **Yes** — ~80% of publisher code is duplicated across modules |
| 2 | Should publishers share infrastructure? | **Yes** — a shared factory would eliminate ~48 lines of boilerplate per module |
| 3 | Should subscribers share infrastructure? | **Yes, lower priority** — modest savings, and subscribers will diverge when they gain real logic |
| 4 | Consistent event naming? | **No** — three patterns across six events (`notification.*`, `user.*`, `presence.*`) |
| 5 | Payloads sufficiently minimal? | **Yes** — flat identifier maps, no business logic |
| 6 | Modules remaining independent? | **Yes** — no cross-module imports, each is self-contained |
| 7 | Hidden dependencies forming? | **Minor** — assembly bottleneck in `index.ts`, publisher import accumulation in `service.ts` |
| 8 | Dual-write implemented consistently? | **Yes** — identical before/after/error pattern in both modules |
| 9 | Maintainable at 20+ modules? | **No** — 1600+ lines of publisher boilerplate, 40-line assembly in index.ts |
| 10 | Standardize before continuing? | **Yes** — shared publisher infra, consistent event naming, centralized module registration |
