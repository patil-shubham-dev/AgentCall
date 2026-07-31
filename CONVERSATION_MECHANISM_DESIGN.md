# AgentCall — Bounded-Wait Conversation Mechanism Design

## Recommendation Summary

**New tool `send_message_and_wait`**, implemented as a short-polling loop inside the MCP handler with a configurable timeout (default 30s). No new transport, no SSE, no long-lived HTTP connections. The simplest thing that works within MCP's request/response model and Render's free-tier constraints.

---

## 1. Tool Shape

### Recommendation: New separate tool, not a modification to `send_message`

**Rationale:**

| Approach | Pros | Cons |
|----------|------|------|
| Modify `send_message` to optionally wait | Fewer tools to maintain | Breaks backward compat; AI might accidentally block when it meant fire-and-forget; the optional parameter creates ambiguity in the response shape (two different return types from one tool) |
| **New `send_message_and_wait` tool** | Clear semantic; explicit AI intent; backward-compatible; each tool has a single obvious return shape | One more tool to list |
| New `wait_for_reply` tool | Separates sending from waiting; allows AI to send multiple messages, then wait | Awkward — AI has to track "which message am I waiting for a reply to?"; two round-trips instead of one for the common case |

`send_message_and_wait` combines the send and the wait into one atomic action, which maps naturally to how conversation works (you say something, then listen for a response). The AI doesn't need to track state between tool calls.

### Proposed Schema

```jsonc
{
  "name": "send_message_and_wait",
  "description": "Send a text message to the human and wait for a reply (up to timeout_seconds). If the human replies in time, returns their response. If not, returns a timeout so you can continue working and check back later.",
  "inputSchema": {
    "type": "object",
    "required": ["call_id", "content"],
    "properties": {
      "call_id": {
        "type": "string",
        "description": "Call ID from create_call"
      },
      "content": {
        "type": "string",
        "description": "Text message to speak to the human",
        "maxLength": 2000
      },
      "timeout_seconds": {
        "type": "number",
        "description": "Max seconds to wait for a reply (1-120)",
        "default": 30,
        "minimum": 1,
        "maximum": 120
      }
    }
  }
}
```

### Response Shapes

**On successful reply (within timeout):**
```jsonc
{
  "outcome": "reply",
  "reply": {
    "text": "Yes, deploy during off-peak hours",
    "received_at": "2026-07-29T10:00:05.000Z"
  },
  "exchange": {
    "ai_message_id": "msg-abc",
    "user_message_id": "msg-def"
  }
}
```

**On timeout (no reply within window):**
```jsonc
{
  "outcome": "timeout",
  "waited_seconds": 30,
  "message": "No reply received within the timeout window. The call is still active — use get_transcript to check for replies later, or call send_message_and_wait again.",
  "hint": "You can continue working and check back, or schedule a callback via the existing callback mechanism."
}
```

**On call ended while waiting:**
```jsonc
{
  "outcome": "call_ended",
  "reason": "completed",
  "transcript_summary": "The human's last message was: ..."
}
```

The AI checks `outcome` first — this is a clean enum, easy to branch on.

---

## 2. Timeout Behavior

| Parameter | Default | Range | Why |
|-----------|---------|-------|-----|
| `timeout_seconds` | 30 | 1-120 | 30s is long enough for most replies, short enough to not feel frozen. Render's free proxy has ~55s idle timeout for HTTP/1.1 — 30s is safe. |

**Design decisions:**
- Timeout is per-call-configurable, not a fixed constant. Different situations warrant different patience (a yes/no question can wait 5s; a complex approval might need 60s).
- The AI can chain calls: `send_message_and_wait("quick question?", timeout_seconds=10)` for simple things, longer waits for complex decisions.
- The timeout is on the **entire wait**, not per-poll. The MCP handler polls internally but returns to the AI only after the full timeout or a reply.

---

## 3. Server-Side Mechanism

### Architecture: Short polling with server-side state

```
MCP Tool Handler                  Backend
┌─────────────────┐              ┌──────────────────┐
│ POST /messages   │ ───send────→ │ addAiMessage()   │
│ (fire content)   │              │ (sends ai_message │
│                  │              │  via WS to phone) │
│                  │              │                  │
│ ┌─ poll loop ─┐  │              │                  │
│ │ GET /pending-│──│─ poll every──→ │ check for user  │
│ │ reply?seq=N │  ││   2s       │  reply since seq  │
│ │              │  ││              │                  │
│ │ ← null ─────┼──│─ no reply ──→ │ (wait)           │
│ │              │  ││              │                  │
│ │ ← "deploy…" ┼──│─ reply! ────→ │ returns text     │
│ └──────────────┘  │              └──────────────────┘
│ return to AI      │
└─────────────────┘
```

### New backend endpoint: `GET /calls/{callId}/pending-reply?after={messageId}`

- Returns the first user message (role: `"user"`) whose index is greater than `after` in the session's `messages` array.
- If no such message exists, returns `{ "reply": null }`.
- Response: `{ "reply": { "id": "...", "content": "...", "created_at": "..." } | null }`

This is already nearly served by `GET /transcript` — the difference is that `pending-reply` returns only one message (the newest one the caller hasn't seen yet), identified by the `after` parameter.

**Why short polling instead of a blocking HTTP connection:**
- Render's free-tier HTTP proxy has a ~55s idle timeout on HTTP/1.1 connections. A blocking connection longer than that gets silently cut.
- Cold starts: if the backend is spun down, the blocking call would fail. Polling handles this transparently — the next poll after the backend wakes up succeeds.
- No special transport required — works with any MCP transport (stdio, SSE, streamable HTTP).
- The MCP tool handler is just a single async function with a loop — trivial to implement in the existing `tools.ts`.

**Poll interval:** 2 seconds. This is fast enough to feel responsive but doesn't spam the backend. A single `send_message_and_wait(30s)` generates at most 15 GET requests.

### What about holding the AI's attention?

The MCP handler loops internally, polling `pending-reply` every 2s. From the AI's perspective, this is one tool call that takes up to `timeout_seconds` to return. Most AI clients handle multi-second tool calls fine (they already wait for API calls, code execution, etc.). The 30s cap ensures the AI isn't stuck forever.

---

## 4. Multi-Exchange Loop Design

The natural back-and-forth is a plain sequence of tool calls, no persistent state needed:

```
1. create_call("What deployment strategy?")
   → { call_id: "abc-123" }

2. send_message_and_wait("abc-123", "Should we deploy now or during off-peak?", 30)
   → { outcome: "reply", reply: { text: "Off-peak please" } }

3. send_message_and_wait("abc-123", "Got it. I'll schedule it for 2am. Any special rollback plan?", 30)
   → { outcome: "reply", reply: { text: "No, standard rollback is fine" } }

4. complete_call("abc-123", { decision: "off-peak", ... })
   → { status: "completed" }
```

Each step is an independent tool call. The AI doesn't hold any connection or state between them. If the AI wants to do other work between exchanges (check logs, write code, etc.), it simply doesn't call `send_message_and_wait` until it's ready.

**This is the key property:** the "conversation" is actually a series of independent MCP tool calls, each one a self-contained request-response. The `call_id` is the only shared context. This is the simplest possible model and is fully compatible with every MCP client.

---

## 5. Callback/Retry Integration

**On timeout, the tool does NOT automatically schedule a callback.** The AI gets a clean `{ outcome: "timeout" }` result and decides what to do:

- **Poll later:** Call `get_transcript` after doing other work to check for a reply.
- **Retry waiting:** Call `send_message_and_wait` again with the same `call_id`. The pending-reply endpoint will immediately return the reply if it arrived during the AI's other work.
- **Schedule callback:** Call the existing callback mechanism explicitly, which triggers a `callback_scheduled` event to the phone and sets up the lifecycle-coordinator timers.
- **Complete/Cancel:** Decide the human isn't responding and end the call.

The lifecycle coordinator (`lifecycle-coordinator.ts`) already handles timed callbacks with pause/cancel/TTL logic. The `send_message_and_wait` timeout is orthogonal — it's just "the AI got tired of waiting right now," not "the call should pause." These remain separate concerns.

---

## 6. Edge Cases

### 6a. Multiple rapid replies from the human

The `pending-reply?after={messageId}` endpoint is cursor-based. The AI includes the `messageId` of its last known message. Only messages after that cursor are returned. So:

```
Human speaks: "Deploy at 2am"
Human speaks again: "Actually, deploy at 4am instead"

AI calls send_message_and_wait(after=ai_msg_1)
  → Returns: "Deploy at 2am"  (first reply after ai_msg_1)

AI calls send_message_and_wait(after=user_msg_1, "OK, 2am it is")
  → Returns: "Actually, deploy at 4am instead"  (next reply after user_msg_1)
```

Replies are consumed in order. Nothing is lost — each `after` cursor advances through the message stream. If the AI doesn't consume a reply (e.g., it gives up waiting and does other work), the unconsumed replies remain in the transcript and will be returned the next time the AI polls with the correct cursor.

**The cursor is simply the `id` of the latest message the AI has already seen.** The AI determines this from what was returned previously. No server-side cursor state needed.

### 6b. Call ended/cancelled while waiting

The poll to `pending-reply` should also check call status. If `status` is `completed` or `cancelled`, return immediately with `{ outcome: "call_ended", reason: "completed" | "cancelled" }`. The MCP handler breaks out of its poll loop and returns promptly, rather than spinning until timeout.

This is a minor addition to the `pending-reply` endpoint — it already has the session data from `findById`.

### 6c. Render cold start

- If the backend is cold-started during a wait: the next poll (within 2s) will hit the backend, which may take a few extra seconds to spin up. The poll response will be delayed but will eventually return.
- Call sessions are in-memory, so a cold start loses all pending calls. This is a pre-existing limitation (the architecture doc already notes this — persistence mode is needed for production). **Recommendation:** this design does not add any new state that would be lost. The reply data lives in the session's `messages[]` array, same as everything else. If persistence is enabled later, `pending-reply` reads from the same persisted session store.

### 6d. Human doesn't reply and timeout fires

Clean result returned to the AI. The call stays active. The human may reply later — the AI can check via `get_transcript` or call `send_message_and_wait` again. The AI is not forced to make any decision immediately.

### 6e. AI sends message but phone WebSocket is disconnected

`sendMessage()` currently succeeds or fails based on the HTTP response. If the WebSocket is down, `notifyPhone()` logs a warning and returns false, but the message is still appended to the session. When the phone reconnects, it won't get the missed message (the WS is server→phone push only, with no replay). **This is a pre-existing issue,** not specific to this design. The bounded-wait mechanism would still detect a reply via polling if the human somehow manages to send one (e.g., via the HTTP API directly).

---

## Open Questions for Product Decision

1. **Should the phone's voice reply (SpeechRecognizer) be delivered as a WS message to the backend, then resolved via the pending-reply endpoint?** Currently the phone POSTs to `/user-text` over HTTP. This works fine — the pending-reply endpoint reads from the same in-memory session. But for a more polished experience, the phone could send the text over WS so the backend resolves the pending-reply faster (no HTTP round-trip for the phone).

2. **Poll interval:** 2s default? Configurable? A very fast poll (500ms) feels more responsive but generates more requests. A slower poll (5s) is lighter but the human waits longer before the AI "hears" them. 2s is a reasonable balance but should be a constant, not a parameter.

3. **Should timeout auto-retry once?** The AI could get `{ outcome: "timeout" }` and immediately retry. If the AI is programmed to always retry on timeout, the timeout itself becomes meaningless. The decision to retry should stay with the AI.

4. **Is a separate `wait_for_reply` tool needed for the "check back later" pattern?** After an initial timeout, the AI might want to check for a reply without sending a new message. This is already covered by `get_transcript`, but a dedicated `wait_for_reply(call_id, timeout_seconds)` tool (that doesn't send a message) could be added later if the polling-only pattern is popular.
