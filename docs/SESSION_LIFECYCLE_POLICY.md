# Session Lifecycle & Cleanup Policy

**Status:** Design document (do not implement)  
**Date:** 2026-07-26  
**Audit reference:** [PHASE2_5A_SESSION_AUDIT.md](./archive/PHASE2_5A_SESSION_AUDIT.md)

---

## Overview

This document defines how session state should be managed long-term: when sessions enter terminal states, when they are cleaned up, how transcripts survive, and how cleanup interacts with future persistence.

Sessions are currently held in an in-process `Map<string, VoiceCallSession>` with **no cleanup** — a memory leak. This policy introduces the concept of **retention windows** and **scheduled eviction** to bound memory growth while preserving useful data.

---

## Current State

```
  ┌──────────┐
  │ Pending  │───► active/expire  ── terminal states ──►   ──►   (never deleted)
  ├──────────┤                                                  
  │ Active   │───► completed/cancelled    ──►   ──►           (never deleted)
  ├──────────┤                                                  
  │ Paused   │───► pending (timeout)      ──►   ──►           (never deleted)
  └──────────┘                                                  
                                                                 
  All sessions accumulate indefinitely in the `sessions` Map.
  No eviction. No TTL. No deletion.
```

---

## 1. When Should Completed Calls Be Removed?

**After a configurable retention window expires.**

Completed calls (`status === 'completed'`) have:
- `completedAt` timestamp
- `result` with transcript summary and metadata
- Message history (transcript)

These are the most valuable sessions to retain because they contain the call outcome. However, they should not live forever in memory.

### Policy

| Aspect | Value | Rationale |
|---|---|---|
| Retention period | **1 hour** after `completedAt` | Long enough for the client to fetch transcript/result via API. Short enough to prevent unbounded memory growth. |
| After expiry | Remove from `sessions` Map | The session is no longer needed. If persistence exists, data was already written to DB. |
| API behavior after expiry | `GET /api/v1/calls/:callId` returns `404` | Clean separation. No zombie sessions. |

### Design

```typescript
// Pseudocode — do NOT implement
const COMPLETED_RETENTION_MS = 60 * 60 * 1000; // 1 hour

// In completeCall(), after setting status:
scheduleRemoval(session.id, 'completed', COMPLETED_RETENTION_MS);
```

---

## 2. When Should Cancelled Calls Be Removed?

**After a shorter retention window than completed calls.**

Cancelled calls (`status === 'cancelled'`) have no result data and minimal value. They represent calls that were abandoned before any meaningful interaction.

### Policy

| Aspect | Value | Rationale |
|---|---|---|
| Retention period | **5 minutes** after `completedAt` | Just enough for the API to confirm cancellation. No transcript/result to preserve. |
| After expiry | Remove from `sessions` Map | Cancelled sessions have no durable value. |
| API behavior after expiry | `GET /api/v1/calls/:callId` returns `404` | Same as completed. |

---

## 3. Should Paused Callbacks Ever Expire?

**Yes — an upper bound on pause duration prevents orphaned sessions.**

Currently, `scheduleCallback` sets a timeout for `delayMinutes` (typically 10-30 minutes) and the session stays in `paused` state until the timeout fires. If the timeout fires successfully, the session transitions back to `pending`. But if the resumed call is never answered, it stays `pending` indefinitely.

Additionally, if the server restarts while a callback is paused, the `setTimeout` is lost (`unref()` means the timer won't keep the process alive, but actually `handle.unref()` just means it doesn't prevent the process from exiting — it still fires if the process is running).

Wait — `handle.unref()` means the timer won't keep the Node.js process alive. If the server shuts down gracefully, the timeout may or may not fire depending on timing. On an ungraceful restart, all timeouts are lost and the session remains permanently `paused`.

### Policy

| Scenario | Action | Rationale |
|---|---|---|
| Normal timeout fires | Status → `pending` (existing behavior) | Callback resume works as intended. |
| Paused session exceeds max pause TTL | Status → `cancelled`, publish `call.expired` | Prevents orphaned paused sessions. |
| Max pause TTL | **24 hours** from pause | Longer than any reasonable callback delay. Covers server restart recovery. |

If a paused session is force-expired, the user would need to initiate a new call (they get a `call.expired` event or notification informing them the callback window closed).

---

## 4. Should Transcripts Survive Deletion?

**Yes — but only if persistence exists.**

Currently, transcripts are part of the `VoiceCallSession.messages` array in memory. When the session is deleted from the `sessions` Map, the transcript is lost.

### Without Persistence (current stack)

Transcripts cannot survive session deletion because they are embedded in the session object. There is no separate transcript store. Deleting a session deletes its transcript.

**Acceptable for MVP.** The 1-hour retention window on completed calls provides sufficient time for clients to fetch transcripts via `GET /api/v1/calls/:callId/transcript`.

### With Persistence (future)

Transcripts should be written to a database before session deletion. The extract flow:

```
completeCall(callId):
  set status = completed
  write session + messages to database     ← new: persistence layer
  publish call.ended
  schedule removal after retention window  ← removal now only evicts cache
```

After persistence is added:
- Session deletion from memory is **cache eviction**, not data loss
- `GET /api/v1/calls/:callId` reads from cache first, falls back to database
- Transcripts survive indefinitely in the database (or until user requests deletion)

---

## 5. Should Persistence Eventually Own Cleanup?

**Yes — the persistence layer should be the source of truth for deletion policy.**

### Architecture Evolution

| Phase | Memory Role | Cleanup Owner | Deletion Behavior |
|---|---|---|---|
| **Current (no persistence)** | Single source of truth | In-process eviction | Hard delete from Map |
| **With persistence** | Read-through cache | Database TTL | Evict from cache only; DB retains until archival policy |

When persistence exists, the `sessions` Map becomes a cache. The eviction policy changes:

| Criterion | Current Policy | With Persistence |
|---|---|---|
| Completed session retention | 1 hour → delete from Map | 1 hour → evict from cache (DB retains) |
| Cancelled session retention | 5 min → delete from Map | 5 min → evict from cache (DB retains) |
| Transcript availability | Only during retention window | Indefinitely from DB |
| Server restart | All sessions lost | Sessions reloaded from DB |

### Tension

Cleanup logic should be **designed to work in both modes** without requiring a rewrite. The approach:

1. Abstract cleanup into a `SessionStore` interface with two implementations:
   - `InMemorySessionStore` — current Map-based store, hard-deletes on expiry
   - `DatabaseSessionStore` — future DB-based store, evicts from cache on expiry

2. The eviction scheduler and retention policy are the same regardless of store implementation. Only the deletion behavior differs (hard delete vs cache evict).

---

## 6. How Should Cleanup Interact With Future Persistence?

### Event-Driven Cleanup

When a session is cleaned up, publish an event. The subscriber determines whether the cleanup is a hard delete or a cache eviction.

```
evictionScheduler:
  session TTL expired for call.id = X
  
  publish call.deleted(session.userId, call.id, statusAtDeletion, retentionSeconds)
  
  if using InMemorySessionStore:
    sessions.delete(call.id)                        // hard delete
  if using DatabaseSessionStore:
    localCache.delete(call.id)                      // cache evict only
    // DB record is retained until archival policy
```

### No Event for Retention Start

Do NOT publish an event when the retention timer starts. It's an implementation detail. Only publish when the deletion actually occurs (`call.deleted`).

### Cleanup During Shutdown

On graceful shutdown, there is no need to flush sessions to disk. Current in-memory sessions have no persistence, and when persistence is added, the database is already the source of truth. The cache simply reboots cold.

---

## 7. Should Cleanup Be Immediate, Delayed, Scheduled, or Reference-Counted?

**Delayed retention per session + periodic sweep as safety net.**

### Recommended: Two-Tier Approach

#### Tier 1: Per-Session Delayed Removal (Primary)

On entering a terminal state, schedule a one-time removal after the retention window.

```
completeCall(callId):
  session.status = 'completed'
  session.completedAt = now()
  publish call.ended
  
  queueRemoval(callId, COMPLETED_RETENTION_MS)
  // → sets a timer that fires ONCE after 1 hour
  // → removes session from Map
  // → publishes call.deleted
```

**Why delayed (not immediate):**
- A client may poll `GET /api/v1/calls/:callId` immediately after receiving the completion notification
- The transcript endpoint must resolve for some period
- Immediate cleanup would break the HTTP API contract (200 → 404 race)

**Why delayed (not scheduled sweep only):**
- Per-session timers are precise: each session is removed exactly when its retention expires
- No latency between expiry and removal (cleanup is O(1) per session vs O(n) sweep)
- For low-to-moderate throughput, the timer overhead is negligible
- Timers use `setTimeout` with `.unref()` — they don't block shutdown

#### Tier 2: Periodic Sweep (Safety Net)

A background interval (e.g., every 5 minutes) scans for expired sessions.

```
setInterval(() => {
  const now = Date.now();
  for (const [id, session] of sessions) {
    if (isTerminal(session.status) && session.completedAt) {
      const retention = session.status === 'completed' ? COMPLETED_RETENTION : CANCELLED_RETENTION;
      if (now - new Date(session.completedAt).getTime() > retention) {
        publish call.deleted(...);
        sessions.delete(id);
      }
    }
  }
}, 5 * 60 * 1000).unref();
```

**Why also sweep:**
- Catches sessions missed by per-session timers (e.g., timer lost on restart, or timer failed to fire)
- Handles edge cases without complex recovery logic
- Simple and robust

#### Not Recommended: Reference Counting

Reference counting doesn't apply because:
- No module holds long-lived references to session objects (they're looked up by ID on demand)
- Sessions are value objects, not shared mutable state
- GC will collect them once removed from the Map

---

## Future Events

### `call.expired`

Published when a paused session's safety TTL expires without being resumed.

**Justification:** This is a real conceptual boundary. A paused callback that is never resumed represents a distinct lifecycle path. Downstream subscribers may want to notify the user or update the provider.

```typescript
// Payload
interface CallExpiredPayload {
  userId: string;
  callId: string;
  reason: 'paused_ttl_expired';
  pausedDurationMinutes: number;
}
```

**Condition:** Published only when the session is in `paused` state and exceeds the max pause TTL. Not published for normal timeout-based resume.

### `call.deleted`

Published when a session is removed from the `sessions` Map after its retention window expires.

**Justification:** This is a real conceptual boundary. A session transitioning from "available for query" to "no longer in memory" is significant. When persistence exists, this event signals cache eviction rather than data loss.

```typescript
// Payload
interface CallDeletedPayload {
  userId: string;
  callId: string;
  statusAtDeletion: CallStatus;
  retentionMs: number;
}
```

**Condition:** Published only when the session is actually removed from memory. Not published for sessions that were never stored (e.g., creation failed before storing).

### `call.archived`

**Not justified at this stage.** Archival is a persistence concept. When persistence exists, the decision to move a record from hot storage to cold storage is a database operation, not a runtime event. Revisit when a dedicated archival layer is designed.

---

## Retention Policy Summary

| State | Max Age in Memory | After Expiry | Event Published |
|---|---|---|---|
| `pending` | Until transition or server restart | N/A (active state) | — |
| `active` | Until transition or server restart | N/A (active state) | — |
| `paused` | 24 hours max pause TTL | Force-cancelled | `call.expired` |
| `completed` | 1 hour after `completedAt` | Hard delete (or cache evict) | `call.deleted` |
| `cancelled` | 5 minutes after `completedAt` | Hard delete (or cache evict) | `call.deleted` |

Sessions in non-terminal states (`pending`, `active`, `paused`) are not removed by the retention policy — they are active by definition. They are removed only by explicit status transition or the max pause TTL.

---

## Memory Management Strategy

### Current (no persistence)

```
sessions Map
  │
  ├── Terminal states (completed, cancelled)
  │     └── Retention timer → delete from Map → publish call.deleted
  │
  ├── Active states (pending, active)
  │     └── No retention — removed only on explicit status change
  │
  └── Paused state
        └── Max pause TTL (24h) → force cancel → publish call.expired
```

### With Persistence (future)

```
sessions Map (cache)
  │
  ├── Terminal states
  │     └── Retention timer → evict from cache → publish call.deleted
  │                            (DB retains the record)
  │
  └── database (source of truth)
        └── Archival policy (owned by DB, not runtime)
```

### Memory Bounds

Assuming average session size of ~4KB (session metadata + 10 messages at 200 bytes each):

| Scenario | Sessions/hour | Memory/hour | Memory/day |
|---|---|---|---|
| Light load | 100 | 400 KB | 9.6 MB |
| Moderate | 1,000 | 4 MB | 96 MB |
| Heavy | 10,000 | 40 MB | 960 MB — exceed retention |

With retention limits, the steady-state memory is bounded by throughput × retention:

```
Steady state max memory = (sessions/hour) × avg_size × max_retention_hours
For heavy load: 10,000 × 4KB × 1h = 40 MB steady state
```

This is acceptable for a single-process Node.js server.

---

## Recommended Implementation Order

| Step | Description | Depends On |
|---|---|---|
| 1 | Add `call.resumed` event to Calls module | Phase 2.3 complete |
| 2 | Implement `scheduleRemoval()` utility in service.ts | — |
| 3 | Wire retention timers in `completeCall()` and `cancelCall()` | Step 2 |
| 4 | Implement periodic sweep for expired sessions | Step 2 |
| 5 | Add max pause TTL (24h) for paused sessions | Steps 3-4 |
| 6 | Add `call.expired` event to Calls module | Step 5 |
| 7 | Add `call.deleted` event to Calls module | Steps 3-4 |
| 8 | Add persistence layer (out of scope for Phase 2) | Steps 1-7 |
| 9 | Replace `InMemorySessionStore` with `DatabaseSessionStore` | Step 8 |
