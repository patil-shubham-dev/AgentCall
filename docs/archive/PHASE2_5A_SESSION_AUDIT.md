# Phase 2.5A — Session Architecture Audit

**Status:** Complete (audit only)  
**Date:** 2026-07-26

---

## Session Ownership

All session state is owned by a single file: **`voicebridge/service.ts`**.

There is exactly one authoritative data structure:

```typescript
const sessions = new Map<string, VoiceCallSession>();   // service.ts:28
```

There is no database, no cache, no persistence layer. Session state is in-process memory. A server restart loses everything.

**No other module or file owns or stores session state.** External modules (`routes.ts`, `signaling/server.ts`) call functions on `voicebridge/service.ts` but never hold references to session objects.

---

## Session Lifecycle

### Complete State Machine

```
  createCall()
      │
      ▼
  [pending] ◄──────────────────────────────────┐
      │                                         │
      ├── addMessage (first AI message) ────► [active]
      │                                         │
      ├── scheduleCallback ─────────────────► [paused]
      │                                         │
      │                              setTimeout fires │
      │                                    │
      │                                    ▼
      │                              [pending] ──────┘
      │
      ├── completeCall ──────────────────► [completed]
      │
      └── cancelCall ───────────────────► [cancelled]
```

### Where Sessions Are Created

| Location | Function | Line | Notes |
|---|---|---|---|
| `voicebridge/service.ts` | `createCall()` | 70 | `sessions.set(session.id, session)` — only creation point |

### Where Sessions Are Mutated

| Location | Function | Line | Mutation |
|---|---|---|---|
| `voicebridge/service.ts` | `createCall()` | 48 | Sets initial status to `'pending'` |
| `voicebridge/service.ts` | `addMessage()` (role='ai') | 123-125 | Status `pending` → `'active'`, sets `connectedAt` |
| `voicebridge/service.ts` | `scheduleCallback()` | 163 | Status → `'paused'`, stores in `scheduledCallbacks` |
| `voicebridge/service.ts` | `scheduleCallback` setTimeout | 178 | Status → `'pending'` (resume), deletes from `scheduledCallbacks` |
| `voicebridge/service.ts` | `completeCall()` | 204-205 | Status → `'completed'`, sets `completedAt`, sets `result` |
| `voicebridge/service.ts` | `cancelCall()` | 230-231 | Status → `'cancelled'`, sets `completedAt` |

### Where Sessions Are Deleted

**Nowhere.** `sessions.delete()` is never called. All completed and cancelled calls remain in memory forever. This is a memory leak.

### Where Sessions Are Read (no mutation)

| Location | Function | Returns |
|---|---|---|
| `voicebridge/service.ts` | `getCall(callId)` | Single session or undefined |
| `voicebridge/service.ts` | `getUserActiveCall(userId)` | Iterates `.values()`, returns first pending/active |
| `voicebridge/service.ts` | `getTranscript(callId)` | `session.messages` |
| `voicebridge/service.ts` | `getSessions()` | All sessions as array |
| `routes.ts` | GET `/api/v1/calls/:callId` | Call details |
| `routes.ts` | GET `/api/v1/users/:userId/active-call` | Active call summary |

---

## Session Mutation Graph

```
                      ┌──────────────────┐
                      │   HTTP Routes    │
                      │   (routes.ts)    │
                      └────────┬─────────┘
                               │ calls
                               ▼
                      ┌──────────────────┐
                      │  voicebridge/    │
                      │   service.ts     │  ◄── Authoritative store
                      │                  │
                      │  sessions Map    │──── sessions size: ⌂ unbounded growth
                      │  phoneConnections│──── cleaned on WS close ✓
                      │  scheduledCallbacks│─ cleaned on complete/cancel ✓
                      └────────┬─────────┘
                               │
                ┌──────────────┼──────────────┐
                │              │              │
                ▼              ▼              ▼
        ┌──────────┐   ┌──────────┐   ┌──────────────┐
        │signaling │   │  calls/  │   │notifications │
        │/server.ts│   │publisher │   │ /publisher   │
        │(register │   │(dual-write│  │(dual-write   │
        │ Phone)   │   │  events) │   │  events)     │
        └──────────┘   └──────────┘   └──────────────┘
```

Key observation: **the `sessions` Map is the single source of truth.** All mutations go through `service.ts`. No module bypasses it.

---

## Hidden Coupling Points

### 1. Three Maps, One Module (Critical)

Three independent data structures live in `service.ts`:

| Map | Purpose | Cleanup |
|---|---|---|
| `sessions` | Call session state | **Never cleaned** — memory leak |
| `phoneConnections` | userId → WebSocket | On WS close |
| `scheduledCallbacks` | userId → callback info | On complete, cancel, or after timeout |

These are coupled because:
- `completeCall` and `cancelCall` delete from `scheduledCallbacks` using `session.userId`
- `notifyPhone` uses `phoneConnections` to deliver session-related notifications
- The callback timeout closure captures `session.userId` from the enclosing scope

Extracting sessions into a separate module would require either:
- Passing session references between modules, or
- Having the sessions module import from notifications/presence/etc., or
- Having the sessions module publish events that other modules subscribe to

### 2. Callback Timeout Closure

```typescript
// service.ts:163-188
const handle = setTimeout(() => {
  const existing = sessions.get(params.callId);
  if (existing && existing.status === 'paused') {
    existing.status = 'pending';
    notifyPhone(session.userId, { type: 'call_incoming', ... });
    scheduledCallbacks.delete(session.userId);
  }
}, params.delayMinutes * 60 * 1000);
```

This closure captures `sessions`, `params.callId`, `session.userId`, `scheduledCallbacks`, and `notifyPhone` from the enclosing scope. It directly mutates session state outside the normal call flow. Any refactoring of sessions must preserve this behavior.

### 3. `VoiceCallSession` is Both a Call and a Session

The `VoiceCallSession` type conflates call state (status, priority, reason) with session state (context, messages, result) and the user identity. It is the single aggregate for everything call-related. There is no separate "session" type.

### 4. No Persistence Boundary

With zero persistence, there's no read-from-database / write-to-database separation. All state is just `Map.get` and `Map.set`. This means there's no natural async boundary where an event could be inserted between "write to database" and "send notification."

---

## Candidate Events

### Events Already Covered by Calls Module

| Session Concept | Existing Call Event | Publisher |
|---|---|---|
| Session created (pending) | `call.created` | `publishCallCreated()` |
| Session activated | `call.answered` | `publishCallAnswered()` |
| Session paused | `call.paused` | `publishCallPaused()` |
| Session ended (completed) | `call.ended` | `publishCallEnded()` |
| Session cancelled | `call.cancelled` | `publishCallCancelled()` |

**5 out of 7 conceptual session transitions already have events.**

### Events NOT Covered (Gaps)

| Session Concept | Exists? | Current Behavior | Event Needed |
|---|---|---|---|
| Session resumed (callback timeout) | **Yes** | `scheduleCallback` setTimeout changes status → `pending` | `call.resumed` or `session.resumed` |
| Session expired (TTL auto-cleanup) | **No** | Doesn't exist — sessions never expire | Not yet — needs implementation first |
| Session deleted | **No** | Doesn't exist — sessions never deleted | Not yet — needs implementation first |

The only existing conceptual transition without an event is the **callback resume** (status changes back to `pending` from `paused` via `setTimeout`).

### New Candidate Event

```typescript
// Only one new event is needed:
session.resumed  (or call.resumed)
// Payload: { userId, callId, delayMinutes, resumeAt }
```

This would be published in the `scheduleCallback` setTimeout callback when `existing.status === 'paused'` and the status is restored to `'pending'`.

---

## Risks

### 1. Memory Leak (No Session Cleanup)

**Severity: High.** Sessions accumulate unboundedly. Every `createCall` adds an entry. No `sessions.delete()` exists. Under sustained load, this will cause OOM.

### 2. False Sense of Durability (No Persistence)

**Severity: High.** All state is in-memory. Server restart destroys all active calls, paused callbacks, and transcripts. There is no recovery mechanism.

### 3. Status Inconsistency

**Severity: Low.** The following can happen:
- `addMessage()` on a completed/cancelled call silently pushes a message
- `scheduleCallback` on a non-paused call sets status to paused regardless
- Multiple pending calls for the same user are allowed (only `getUserActiveCall` warns but doesn't prevent)

### 4. Scaling Blocked

**Severity: High.** In-memory Maps cannot be shared across processes. Adding a second server instance would give each instance its own session state with no synchronization.

---

## Recommended Migration Strategy

### Can the existing dual-write template be used?

**Partially.** The session lifecycle overlaps almost completely with the call lifecycle. The Calls module (`voicebridge/calls/`) already publishes events for 5 of 7 session transitions. A separate "Sessions" module would duplicate what already exists.

**Recommendation: Extend the existing Calls module rather than creating a separate Sessions module.**

Specifically:

1. **Add one event** to `voicebridge/calls/events.ts`:
   - `call.resumed` — published when callback timeout restores session to `pending`

2. **Do NOT create** a separate `voicebridge/sessions/` module. The `VoiceCallSession` type is already the call aggregate. Creating a parallel module would:
   - Duplicate event publishing for the same transitions
   - Create confusion about whether `call.created` or `session.created` is canonical
   - Require cross-module coordination (the Calls and Sessions modules would need to agree on state)

3. **Future session-specific features** (expiry, deletion, TTL-based cleanup) should be added to `service.ts` first, then published as new events on the Calls module.

### What remains outside the template

The callback timeout (`setTimeout` in `scheduleCallback`) is the only async state mutation that doesn't pass through a controller function. It's a closure inside `service.ts` that directly mutates `sessions.get(params.callId).status`. To publish an event here, the publish call must be placed inside the setTimeout callback — which is straightforward with the existing dual-write pattern.

### Summary

| Question | Answer |
|---|---|
| Can dual-write template be used? | **Yes** — but a separate Sessions module would duplicate the Calls module |
| New events needed? | **One**: `call.resumed` for callback timeout resume |
| Separate Sessions module? | **Not recommended** — extend Calls module instead |
| Direct session logic removable? | **No** — same reasoning as all prior phases |
| Biggest risk blocking migration? | **Memory leak** (no session deletion) — should be fixed before or alongside any migration |

### Recommended Next Steps (in order)

1. Add `call.resumed` event to the existing Calls module
2. Implement session TTL/deletion (new code, not just events)
3. Add `session.expired` and `session.deleted` events after implementation exists
4. Add persistence layer (out of scope for current phase)
5. Only then consider extracting a separate Sessions domain module
