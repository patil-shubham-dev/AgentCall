# AgentCall Persistence Architecture

**Status:** Design document (do not implement)  
**Date:** 2026-07-26  
**Scope:** Storage boundaries, repository interfaces, startup recovery, failure scenarios, migration strategy

---

## 1. Storage Boundaries

### What Should Be Persisted

| Entity | Why | Key Fields |
|---|---|---|
| **Sessions** | Core domain object. Survives restart. | `id`, `userId`, `agentId`, `status`, `priority`, `reason`, `context`, `result`, `messages`, `createdAt`, `connectedAt`, `completedAt`, `retentionExpiresAt` |
| **Scheduled callbacks** | Time-sensitive. Lost on restart without persistence. | `userId`, `callId`, `resumeAt` |
| **Lifecycle timestamps** | Enable recovery of `CleanupScheduler` state and sweep decisions. | `retentionExpiresAt` (already on session), callback `resumeAt` |

### What Should NOT Be Persisted

| Entity | Why |
|---|---|
| **WebSocket connections** | Transient network state. Re-established by phone reconnection. |
| **Event Bus subscriptions** | Rebuilt on startup by `register()` calls. Never persisted. |
| **CleanupScheduler state** | Rebuilt from persisted callbacks and retention timestamps. Never persisted directly. |
| **`phoneConnections` Map** | Transient WebSocket map. Rebuilt as phones reconnect. |
| **`scheduledCallbacks` in-memory Map** | Rebuilt from persisted callback records on startup. |
| **Logger state** | Ephemeral. No persistence needed. |
| **Temporary runtime objects** | Any object derived from persisted state, not owning it. |

---

## 2. Repository Interfaces

### SessionRepository (existing, in sweeper.ts)

The current interface is already minimal and persistence-agnostic:

```typescript
// sweeper.ts — current, no changes needed
export interface SessionRepository {
  list(): Iterable<VoiceCallSession>;
  delete(callId: string): VoiceCallSession | undefined;
}
```

**What changes for persistence:** The implementation swaps from in-memory Map to database queries. The interface stays identical.

**New methods needed for persistence:**

```typescript
// Extended interface — add but don't replace existing methods
export interface SessionRepository {
  // Existing (sweeper uses these)
  list(): Iterable<VoiceCallSession>;
  delete(callId: string): VoiceCallSession | undefined;

  // New (needed by service.ts for CRUD)
  findById(callId: string): VoiceCallSession | undefined;
  save(session: VoiceCallSession): void;
  saveMany(sessions: VoiceCallSession[]): void;   // batch insert on startup recovery
  findByUserId(userId: string): VoiceCallSession[];
  findByStatus(status: CallStatus): VoiceCallSession[];  // for sweep recovery
}
```

### CallbackRepository (new)

```typescript
export interface CallbackRepository {
  list(): Iterable<ScheduledCallbackRecord>;
  findByUserId(userId: string): ScheduledCallbackRecord | undefined;
  save(record: ScheduledCallbackRecord): void;
  delete(userId: string): boolean;
}

export interface ScheduledCallbackRecord {
  userId: string;
  callId: string;
  resumeAt: number;           // epoch ms — no timezone ambiguity
  delayMinutes: number;       // original delay for re-publishing events
  createdAt: string;          // ISO-8601
}
```

### In-Memory Implementations (current, unchanged)

```typescript
// service.ts — implicit in-memory implementation via Map
const sessions = new Map<string, VoiceCallSession>();
const scheduledCallbacks = new Map<string, { callId: string; resumeAt: number }>();
```

These become:

```typescript
const sessionRepository: SessionRepository = new InMemorySessionRepository();
const callbackRepository: CallbackRepository = new InMemoryCallbackRepository();
```

Where `InMemorySessionRepository` and `InMemoryCallbackRepository` are the same Map-backed implementations, but wrapped in the interface. This is the migration bridge (see Section 5).

### LifecycleSessionStore (lifecycle-coordinator.ts)

```typescript
// lifecycle-coordinator.ts — current, receives CallbackRepository
export interface LifecycleSessionStore {
  getSession(callId: string): VoiceCallSession | undefined;
  deleteScheduledCallback(userId: string): void;
}
```

This interface is consumed by `LifecycleCoordinator`. It's a subset of `SessionRepository` + `CallbackRepository`. On persistence, the coordinator continues to receive interface implementations — no code change in the coordinator.

---

## 3. Startup Recovery

### Sequence (top to bottom)

```
Process start
      │
      ▼
  Validate config
      │
      ▼
  Create Event Bus
  Register subscribers (notifications, presence, calls, signaling)
      │
      ▼
  Create repositories
  │  ├── SessionRepository (database-backed)
  │  └── CallbackRepository (database-backed)
      │
      ▼
  Load active sessions from database
  │  Load all sessions where status IN ('pending', 'active', 'paused')
  │  → populate SessionRepository cache
  │  → this is the "cache warming" step
      │
      ▼
  Process orphaned paused sessions
  │  For each paused session loaded from DB:
  │  ├── Check if it has a pending callback record
  │  │     ├── Yes: recalculate remaining time, schedule in CleanupScheduler
  │  │     └── No:  check if 24h pause TTL has passed
  │  │           ├── Yes: immediately expire (publish call.expired)
  │  │           └── No:  schedule remaining pause TTL
  │  └── (The original callback timeout is lost on restart.
  │       The user must re-schedule or we use the pause TTL as backstop.)
      │
      ▼
  Process expired-but-not-deleted sessions
  │  For each session where retentionExpiresAt < now:
  │  ├── These were "ready to delete" when the process crashed
  │  ├── Sweeper will pick them up on first tick
  │  └── No urgent action needed — sweeper handles it
      │
      ▼
  Rebuild CleanupScheduler state
  │  ├── For each callback: schedule resume timer
  │  ├── For each paused session: schedule pause TTL timer
  │  └── This replaces what setTimeout would have done
      │
      ▼
  Create SessionSweeper
  │  └── start() with the same SessionRepository
      │
      ▼
  Create LifecycleCoordinator
  │  └── receives CleanupScheduler + repositories
      │
      ▼
  Create Fastify app
  Register routes
  Start HTTP server
  Start WebSocket server
      │
      ▼
  Accept traffic
```

### Key Decision: Callback Recovery

When the server restarts, all in-flight `setTimeout` / `CleanupScheduler` timers are lost. The callback schedule (`resumeAt`) was persisted in `CallbackRepository`, so we know when it was supposed to fire.

**Recovery options for each callback:**

1. **If `resumeAt > now` (callback hasn't fired yet):** Re-schedule in `CleanupScheduler`. The callback fires at the originally scheduled time.

2. **If `resumeAt <= now` (callback should have already fired):** The callback was missed. Re-schedule immediately or fire inline. Fire inline is safer — the user should be notified now, not in another N minutes.

**Recommended: Fire inline for missed callbacks.** The `handleResume` logic runs immediately during startup for any callback whose `resumeAt` is in the past. This is equivalent to the setTimeout having fired during the crash window.

### Key Decision: Pause TTL Recovery

The 24-hour max pause TTL is also lost on restart. For each paused session loaded from the database:

1. Compute `pauseStartedAt` from `callback.resumeAt - callback.delayMinutes * 60000`
2. Compute `pauseExpiresAt = pauseStartedAt + 24h`
3. If `pauseExpiresAt > now`: Re-schedule in CleanupScheduler.
4. If `pauseExpiresAt <= now`: Run `handlePauseExpiry` immediately.

---

## 4. Failure Scenarios

### Crash During Callback Resume

```
Scenario: Server crashes between session.status = 'pending' and publishCallResumed()
         or between publishCallResumed() and notifyPhone()
```

**Recovery:**
1. On restart, callback is loaded from `CallbackRepository`
2. Session status is checked: if already `'pending'` (state change persisted before crash), the callback guard (`existing.status !== 'paused'`) prevents double-resume
3. If the crash happened before the status change was persisted, the callback fires as normal
4. The `notifyPhone` notification is not replayed — the phone will reconnect and can fetch current state via API

**Eventual consistency:** The phone client may miss one `call_incoming` notification if the server crashes during `notifyPhone`. The client is expected to poll `GET /api/v1/users/:userId/active-call` on reconnect — this will show the `pending` session.

**Mitigation:** None needed. This is an edge case with a valid recovery path (client-side polling).

### Crash During Deletion

```
Scenario: Server crashes between repository.delete(callId) and coordinator.handleDeleted()
         or between handleDeleted() and publishCallDeleted()
```

**Recovery:**
1. On restart, `SessionRepository.list()` no longer returns the deleted session (DB delete was committed or not)
2. If the DB delete was not committed: the session is still present, `retentionExpiresAt < now` will be true, and the sweeper will pick it up on the next tick
3. If the DB delete was committed but `call.deleted` was not published: the event is lost. This is acceptable — `call.deleted` is an observation event, not a command. No downstream depends on it for correctness

**Mitigation:** Use a transactional outbox pattern if exactly-once `call.deleted` delivery becomes a requirement. For MVP, at-most-once is acceptable.

### Restart During Pause TTL

```
Scenario: Server restarts while a session is paused.
         The callback resume timer and the pause TTL timer are both lost.
```

**Recovery:**
1. The paused session is loaded from DB
2. `CallbackRepository` provides the callback record (resumeAt, delayMinutes)
3. If `resumeAt <= now`: Resume immediately (user gets `call_incoming` notification)
4. If `resumeAt > now`: Re-schedule both the callback resume and the pause TTL
5. If no callback record exists: Check pause TTL independently via `resumeAt` computation from session's `completedAt`... wait, the session wouldn't have `completedAt` if it's paused

Actually, for pause TTL recovery without a callback record:
- The pause TTL is 24 hours from when the session entered `paused` state
- But we don't persist `pausedAt` on the session
- We derive it from the callback record

**If the callback record is lost (DB inconsistency):** The paused session has no recovery path. The pause TTL cannot be computed. Options:
1. Add `pausedAt` timestamp to the session (recommended)
2. Auto-expire any paused session without a callback record after a safe default

**Recommendation:** Add `pausedAt?: string` to `VoiceCallSession`. Set when `scheduleCallback` pauses the session. Remove the derivation from callback record.

### Restart After Expiry

```
Scenario: Server restarts after retentionExpiresAt has passed.
         The session should have been deleted by the sweeper.
```

**Recovery:**
1. On restart, the session is loaded from DB (if it wasn't deleted before crash)
2. `retentionExpiresAt < now` is true
3. The sweeper picks it up on the first tick
4. If the sweeper interval is 5 minutes, deletion happens within 5 minutes of startup

**If the session was already deleted (DB delete committed) but `call.deleted` was not published:** Same as crash during deletion — at-most-once event delivery. Acceptable for MVP.

---

## 5. Migration Strategy

### Principle: Interface-Swap, Not Rewrite

The migration moves from in-memory Maps to database-backed repositories by swapping implementations behind the same interfaces. No consumer code changes.

### Step 1: Wrap Existing Maps in Interfaces

**Current state:** Maps are accessed directly in `service.ts`.

```typescript
// service.ts — current (implicit)
const sessions = new Map<string, VoiceCallSession>();
function getCall(id) { return sessions.get(id); }
function deleteSession(id) { sessions.delete(id); }
```

**Step 1 Target:** Maps are accessed through interfaces.

```typescript
// service.ts — after Step 1
import { InMemorySessionRepository } from './repositories/session-repository.js';

const sessionRepo: SessionRepository = new InMemorySessionRepository();
// All existing functions delegate to sessionRepo
```

All existing callers (`getCall`, `getSessions`, `deleteSession`, etc.) delegate to the repository. Zero behavioural change. The `InMemorySessionRepository` wraps the same `Map` that was there before.

### Step 2: Introduce Database Repository (Parallel)

**Create a second implementation:**

```typescript
// backend/src/voicebridge/repositories/pg-session-repository.ts
export class PgSessionRepository implements SessionRepository {
  constructor(private db: Database) {}
  async list(): Promise<VoiceCallSession[]> { ... }
  async findById(id: string): Promise<VoiceCallSession | undefined> { ... }
  async save(session: VoiceCallSession): Promise<void> { ... }
  async delete(id: string): Promise<VoiceCallSession | undefined> { ... }
}
```

Note: `SessionRepository.list()` currently returns `Iterable<VoiceCallSession>` (synchronous). A database-backed version would be async. This is a **breaking interface change**.

**Resolution:** The repository interface must become async-friendly. Options:
1. Make all methods return `Promise` (breaks current sync callers — sweeper, index.ts wiring)
2. Keep sync for `list()` and `delete()` — not possible with DB
3. Create an async `AsyncSessionRepository` and adapt the sweeper

**Recommendation:** Change `SessionRepository` to return `Promise` now, and update the in-memory implementation to return `Promise.resolve(...)`. This is a one-time change that makes the interface forward-compatible.

### Step 3: Dual-Write (One Process, Two Backends)

During migration, write to both backends but read only from the in-memory one:

```typescript
export class DualWriteSessionRepository implements SessionRepository {
  constructor(
    private memory: InMemorySessionRepository,
    private database: PgSessionRepository,
  ) {}

  async save(session: VoiceCallSession): Promise<void> {
    this.memory.save(session);       // synchronous — fast path
    await this.database.save(session); // asynchronous — durability path
  }

  async list(): Promise<VoiceCallSession[]> {
    return this.memory.list();        // read from memory
  }
}
```

**Why dual-write:** Zero risk. If the database is down, the system still works (in-memory is the source of truth). The database is written in the background. Switchover happens when confidence is high.

### Step 4: Flip Read-From to Database

When the database has been proven in dual-write mode:
1. Change `list()` and `findById()` to read from the database
2. Keep in-memory as a read-through cache
3. Remove in-memory write path
4. The `sessions` Map is now a cache, not the source of truth

### Step 5: Remove In-Memory Fallback

Once database reads have been validated under load:
1. Remove the in-memory Map entirely
2. The database is the single source of truth
3. Optionally add Redis or in-process LRU cache for hot sessions

### Migration Summary

| Step | In-Memory | Database | Reads From | Writes To |
|---|---|---|---|---|
| 0 (current) | Source of truth | — | Memory | Memory |
| 1 | Wrapped in interface | — | Memory | Memory |
| 2 | Wrapped in interface | Implemented | Memory | Memory |
| 3 | Active | Passive | Memory | Both |
| 4 | Cache | Source of truth | DB | DB |
| 5 | Removed | Source of truth | DB | DB |

---

## 6. Sequence Diagrams

### Startup With Persistence

```
Startup
  │
  ├── Config validated
  │
  ├── Event Bus created + subscribers registered
  │
  ├── repositories = new PgSessionRepository(db)
  │   new PgCallbackRepository(db)
  │
  ├── activeSessions = await repositories.sessions.findByStatus(['pending','active','paused'])
  │
  ├── For each paused session:
  │   ├── callback = await repositories.callbacks.findByUserId(session.userId)
  │   ├── if callback:
  │   │     if callback.resumeAt <= now:
  │   │       coordinator.handleResume(...)    ← inline resume
  │   │     else:
  │   │       cleanupScheduler.schedule('resume:{id}', callback.resumeAt, ...)
  │   │       cleanupScheduler.schedule('pause-ttl:{id}', callback.resumeAt + 24h, ...)
  │   └── if no callback:
  │         if session.pausedAt + 24h <= now:
  │           coordinator.handlePauseExpiry(...)  ← inline expire
  │         else:
  │           cleanupScheduler.schedule('pause-ttl:{id}', pausedAt + 24h, ...)
  │
  ├── sweeper = new SessionSweeper({ repository, ... })
  │   sweeper.start()
  │
  ├── coordinator = new LifecycleCoordinator(cleanupScheduler, ...)
  │
  ├── HTTP + WebSocket servers start
  │
  └── Accept traffic
```

### Normal Call Lifecycle With Persistence

```
createCall(input)
  │
  ├── session = new VoiceCallSession(...)
  ├── sessionRepo.save(session)                  ← write to DB
  ├── publishCallCreated(...)
  └── notifyPhone(call_incoming)

completeCall(callId)
  │
  ├── session.status = 'completed'
  ├── session.completedAt = now()
  ├── session.retentionExpiresAt = now() + 1h
  ├── sessionRepo.save(session)                  ← update in DB
  ├── callbackRepo.delete(session.userId)        ← remove pending callback
  ├── publishCallEnded(...)
  └── notifyPhone(call_ended)

Sweeper sweep (expired found)
  │
  ├── sessionRepo.delete(callId)                 ← delete from DB
  ├── coordinator.handleDeleted(session)
  │     ├── audit log
  │     └── publishCallDeleted(...)
  └── log count
```

### Crash Recovery Flow

```
Crash during callback resume
  │
  ├── Process restarts
  ├── Load paused session from DB
  ├── Find callback record in CallbackRepository
  ├── resumeAt <= now → true (missed)
  ├── Coordinator.handleResume() fires inline
  │     ├── session.status = 'pending'
  │     ├── sessionRepo.save(session)
  │     ├── publishCallResumed(...)
  │     └── notifyPhone(call_incoming)
  └── Normal operation resumes
```

---

## 7. Risks

### Risk 1: Async Interface Breakage (Medium)

**Problem:** `SessionRepository.list()` returns `Iterable<VoiceCallSession>` (synchronous). Database queries are inherently async (`Promise`). Changing the interface to return `Promise` breaks all current callers (sweeper, index.ts wiring, service.ts functions).

**Mitigation:** Make the change early, when the callers are few and well-understood. The in-memory implementation returns `Promise.resolve(...)` wrappers. The breakage is mechanical — every current caller that awaits the result will work identically.

### Risk 2: Dual-Write Latency (Low)

**Problem:** Writing to both in-memory and database doubles write latency in the critical path (`createCall`, `completeCall`, etc.).

**Mitigation:** The database write is fire-and-forget in dual-write mode. The in-memory write returns immediately. If the DB write fails, log an error but do not block the response. The in-memory copy is the source of truth during dual-write.

### Risk 3: Stale Callback Records After Completion (Low)

**Problem:** When a call is completed, `callbackRepo.delete()` is called. If this DB delete fails, a stale callback record remains. On restart, the stale callback fires a spurious resume for a completed call.

**Mitigation:** The `handleResume` guard (`existing.status !== 'paused'`) catches this — it won't resume a completed call. The stale record is harmless. A periodic sweep can clean orphaned callback records.

### Risk 4: Time Skew Between Servers (Low)

**Problem:** `resumeAt` and `retentionExpiresAt` are computed using `Date.now()`. If a backup server has a different clock, timers may fire early or late.

**Mitigation:** Use epoch milliseconds (UTC). All timestamps are monotonic and timezone-independent. Clock skew is bounded by NTP. For the in-process single-server architecture, this is not a concern.

### Risk 5: Inline Callback Recovery Causes Notification Storm (Medium)

**Problem:** If the server was down for hours, many missed callbacks will fire inline during startup, each sending a `call_incoming` notification to the phone. This could overwhelm the phone (and the user).

**Mitigation:**
- Deduplicate by `userId`: if a user has multiple pending callbacks, only fire the most recent one
- Rate-limit inline recoveries: batch notifications, or add a small stagger between inline callbacks
- Consider skipping inline fire entirely for callbacks that were meant to fire more than 5 minutes ago — the phone can poll active call on reconnect

### Risk 6: Schema Evolution (Medium)

**Problem:** The `VoiceCallSession` type evolves. Adding fields, changing field types, or removing fields requires database migrations.

**Mitigation:** Store sessions as JSONB in PostgreSQL (or a document column). This avoids schema migrations for every field addition. Index the queryable fields (status, userId, callId) as separate columns or generated indexes.

### Risk 7: Transactional Consistency at Deletion (Low)

**Problem:** When the sweeper deletes a session and publishes `call.deleted`, these are two separate operations. If the process crashes between the DB delete and the event publish, the session is lost but no event fires.

**Mitigation:** This is acceptable for MVP. `call.deleted` is an observation event, not a command. If exactly-once semantics become required, implement a transactional outbox pattern: write the deletion + pending event in the same DB transaction, and a background worker publishes pending events.

---

## Summary

| Aspect | Decision |
|---|---|
| Persisted entities | Sessions, callbacks, lifecycle timestamps |
| Not persisted | WebSocket connections, Event Bus state, CleanupScheduler state, runtime objects |
| SessionRepository | Extend with `findById`, `save`, `saveMany`, `findByStatus`. Make async-compatible now. |
| CallbackRepository | New interface for lifecycle-coordinator usage |
| Migration path | Interface-swap (5 steps: wrap → implement → dual-write → flip reads → remove memory) |
| Startup recovery | Load active sessions, rebuild timers, fire missed callbacks inline, skip callback-lost paused sessions to TTL |
| Failure recovery | Status guards prevent double-mutation; at-most-once event delivery for `call.deleted`; sweeper handles missed deletions |
| Primary risk | Async interface breakage — mitigate by changing early |
