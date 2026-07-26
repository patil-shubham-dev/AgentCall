# Concurrency Review — RC-1

## Node.js Model

Node.js event loop is single-threaded. `async/await` yields to the event loop at each `await`. This means state can change between `await` calls in the same function.

## Race Condition: Lost Message Update

**File:** `service.ts:120-147`

```typescript
async addMessage(callId: string, role, content, type) {
  const session = await this.sessionRepo.findById(callId);  // ← yields
  if (!session) return undefined;

  const msg = { id: newId(), role, type, content, createdAt: now() };
  session.messages.push(msg);  // ← both requests modify the same array

  // ...
  await this.sessionRepo.save(session);  // ← second save overwrites first
}
```

**Scenario:**
1. Request A reads session (messages = [m1])
2. Request B reads session (messages = [m1]) 
3. Request A pushes m2, saves (messages = [m1, m2])
4. Request B pushes m3, saves (messages = [m1, m3]) ← overwrites A's save

**Severity:** HIGH — messages are silently lost
**Impact:** Users lose messages in concurrent scenarios
**Likelihood:** MODERATE — requires concurrent requests to the same call
**Reproduction:** Fire two `POST /api/v1/calls/:callId/messages` simultaneously

## Race Condition: Double Complete/Cancel

```typescript
async completeCall(callId, result) {
  const session = await this.sessionRepo.findById(callId);
  if (!session) return undefined;
  // ... modify session ...
  await this.callbackRepo.delete(session.userId);
  // ...
}
```

**Scenario:**
1. Request A reads session (status = 'active')
2. Request B reads session (status = 'active')
3. Request A sets status to 'completed', saves
4. Request B sets status to 'completed', saves

**Severity:** MEDIUM — no data corruption, but duplicate events and unnecessary DB writes
**Impact:** Double `publishCallEnded` events, double `notifyPhone` calls

## Race Condition: Concurrent Pause and Resume

```typescript
// service.ts scheduleCallback sets status to 'paused'
// lifecycle-coordinator.ts handleResume sets status to 'pending'
```

If `scheduleCallback` and `handleResume` fire simultaneously:
1. `scheduleCallback` reads session, sets `paused`, saves callback
2. `handleResume` reads session (status = 'paused'), sets `pending`, deletes callback
3. If they execute interleaved: both succeed, but the final state depends on timing

Correct behavior: `handleResume` checks `status !== 'paused'` and returns early if not paused. But the interleaving can cause:
- Session ends up 'paused' if scheduleCallback's save happens after handleResume's save

## Race Condition: Dual-Write DB vs Memory

In dual-write mode, writes are:
1. Memory (awaited)
2. DB (fire-and-forget)

After memory write succeeds but before DB write completes:
- Reads from memory = new data
- Reads from DB (database-read mode) = old data
- This inconsistency is expected and handled by `readFromDatabase` flag

**But:** If the DB write fails silently (fire-and-forget `.catch(logger.error)`), the memory and DB diverge permanently until the next sweeper or verifier run.

## Race Condition: SessionSweeper During Operations

`SessionSweeper.sweep()` runs every 5 minutes. It lists all sessions and deletes expired ones.

**Scenario:**
1. User calls `completeCall` for session X
2. Sweeper runs, lists sessions (includes X with status 'completed')
3. Sweeper deletes X from repo
4. `completeCall` saves X back to repo (sweeper already deleted it, but `save()` recreates it in InMemory via `Map.set`)

In InMemory mode: `save()` re-inserts — no issue
In database mode with upsert: `ON CONFLICT DO UPDATE` — no issue

But during the gap between sweeper read and sweeper delete, if a concurrent request modifies the session, the sweeper's delete might delete a different version than expected.

## Race Condition: phoneConnections Global Map

```typescript
const phoneConnections = new Map<string, WebSocket>();
```

This is a module-level global. All operations (get/set/delete) are synchronous single micro-operations. In Node.js single-threaded model, this is safe. However:

**Scenario:** Connection replacement race
1. Client A connects as `user-1`, stored as WS_A
2. Client B connects as `user-1`, stored as WS_B (replaces WS_A)
3. WS_A's `on('close')` fires (from the close initiated in step 2)
4. `phoneConnections.get(userId) === ws` check: if WS_B was stored between the close event scheduling and execution, WS_A will NOT delete the entry. CORRECT.
5. But if step 4 happens fast enough, WS_A might be the current value in step 4 and delete WS_B's entry, leaving the map without user-1's connection.

**This is actually safe** because the close handler in step 2 runs synchronously BEFORE the new connection replaces it in the map. Wait — let me re-read:

```typescript
ws.on('close', () => {
  if (phoneConnections.get(userId) === ws) {
    phoneConnections.delete(userId);
  }
});
```

Timeline:
1. WS_A closes → `close` event fires → checks `phoneConnections.get(userId) === ws` → TRUE → deletes user-1
2. WS_B connects → stored as user-1

This is correct — the close event for the old connection fires before the new connection is established, because `existing.close(1000)` is synchronous inside `registerPhone`, and the `close` handler fires synchronously or in next tick.

**Verdict:** Safe, but fragile. The correctness depends on Node.js event loop ordering.

## Retry Races

`withRetry` is used in `Instrumented*Repository` with `maxRetries: 1`. If a transient DB error occurs:

1. First attempt fails (ECONNRESET)
2. Wait 50ms (base delay)
3. Retry attempt succeeds

During the 50ms wait, another request might succeed or fail. No issue with correctness, but the retry delay is included in the timing metric, making the recorded time inaccurate.

## Pool Starvation

With `max: 10` connections and 1000 concurrent requests:
- 10 requests get a connection
- 990 wait in queue
- Without `connectionTimeoutMillis`, the queue grows unbounded

**Severity:** MEDIUM — affects performance but not correctness

## Score

**Concurrency: 5/10**

Deducted for: lost update on concurrent message writes (HIGH), no optimistic locking, no transaction boundaries for multi-step operations, fire-and-forget dual-write creating permanent divergence on failure, pool queue growing unbounded without acquire timeout.
