# AgentCall — MCP API Specification

> Version 2.0.0 — Protocol: Model Context Protocol (JSON-RPC) over Streamable HTTP.
> AgentCall exposes itself AS an MCP server. Every MCP-capable AI can use it.
> This document describes the system as it actually runs today (verified against
> `backend/src/mcp/endpoint.ts` and `backend/src/mcp/tools.ts`).

---

## 1. Overview

The MCP server is **embedded in the backend** and shares its port. There is no
separate process, no stdio transport, and no `agentcall-mcp.js` launcher. The
entrypoint is `POST /mcp` on the same host as the REST API and the phone
WebSocket.

The server identifies itself as `agentcall-mcp` v2.0.0 and exposes **6 tools**.
Capabilities: `tools` only (no resources, no prompts).

Core model:

- **`create_call`** — start a voice call to reach a human.
- **`send_message`** — speak a text message aloud on their phone.
- **`get_transcript`** — read what the human has said back.
- **`send_message_and_wait`** — send a message and block for the reply.
- **`complete_call`** — end the call and store the outcome.
- **`cancel_call`** — end the call without completing it.

Every call is **owned by the AI identity that created it** (see §4). A call
created by one identity cannot be read or acted on by another.

---

## 2. Connection

### 2.1 Base URL

```
POST https://YOUR_SERVER/mcp
```

The same URL serves REST (`/api/v1/...`) and the phone signaling WebSocket on
the same port. A self-hosted deployment serves this at your own host; the
managed deployment is `https://agentcall-66ke.onrender.com/mcp`.

### 2.2 Authentication

An AI key (created in the Android app under **Settings → AI Connections →
Add AI**, or via `POST /api/v1/ai/keys`) is required. Keys are one-time
plaintext values shaped `ac_...`; only their hash is stored server-side. Pass
the key in one of three ways:

| Method | Header / Location |
|---|---|
| Bearer | `Authorization: Bearer ac_YOUR_KEY` |
| API key | `x-api-key: ac_YOUR_KEY` |
| Query | `?key=ac_YOUR_KEY` (for clients that cannot set headers, e.g. ChatGPT) |

Requests without a valid key get `401`:

```json
{ "error": "UNAUTHORIZED", "message": "Valid Bearer token, x-api-key, or ?key= query parameter required. Create an AI key in the AgentCall phone app (Settings > Add AI)." }
```

Browser clients (chatgpt.com, app.chatgpt.com, claude.ai) get CORS headers;
all other origins are not whitelisted.

### 2.3 Session lifecycle (Streamable HTTP)

1. **Initialize** — `POST /mcp` with an `initialize` request and **no**
   `Mcp-Session-Id` header. The response carries a `mcp-session-id` header.
2. **Use** — every subsequent request (`tools/list`, `tools/call`,
   `notifications/initialized`) must include that id as the
   `Mcp-Session-Id` header.
3. **Close** — `DELETE /mcp` with the session id closes the session.
4. **Expiry** — sessions idle for 30 minutes (`MCP_SESSION_IDLE_MS`, configurable)
   are closed by a periodic sweep (every 60 s). A request against an unknown or
   expired session id gets `404`:

   ```json
   { "error": "SESSION_NOT_FOUND", "message": "Unknown or expired Mcp-Session-Id. Re-initialize the session." }
   ```

   This is MCP's designed recovery: the client re-initializes and continues.

Responses are Server-Sent Events (`text/event-stream`); each `data:` line
carries one JSON-RPC message. The SDK manages this transparently for official
MCP clients.

### 2.4 Rate limiting

`/mcp` is rate limited to **120 requests/minute per client**, above the global
100/minute API limit. Exceeding the per-route limit returns `429`:

```json
{ "error": "RATE_LIMITED", "message": "Too many requests. Rate limit: 120 per 1 minute", "request_id": "65d53230-..." }
```

---

## 3. Tools

All tools return a `CallToolResult`: `content: [{ type: "text", text }]`, with
`isError: true` for failures. Errors are machine-parseable strings beginning
with `Error:`.

| Tool | Purpose | Auth scope |
|---|---|---|
| `create_call` | Initiate a voice call to a human | identity is recorded as owner |
| `send_message` | Speak text aloud on an active call | owner only |
| `get_transcript` | Read the conversation | owner only |
| `send_message_and_wait` | Send + wait for the reply | owner only |
| `complete_call` | End the call, store outcome | owner only |
| `cancel_call` | End the call without outcome | owner only |

### 3.1 `create_call`

Initiate a voice call to get human input, clarification, or approval. The
human hears the `context.summary` via their phone.

```json
{
  "user_id": "solo-user",
  "context": {
    "task_id": "task-42",
    "reason": "approval",
    "summary": "Do you approve the 500 EUR refund for order #1234?",
    "options": ["Yes, approve", "No, hold", "Call me back in 2 hours"]
  },
  "priority": "normal"
}
```

Schema:

- `user_id` (string, default `solo-user`) — user to call.
- `context` (object, required) —
  - `task_id` (string, optional) — your task id needing input.
  - `reason` (string, required) — one of `clarification | approval | error | input_required`.
  - `summary` (string, required, max 1000) — what you need from the human; spoken aloud.
  - `options` (array of string, optional) — quick-reply choices for the human.
- `priority` (string, default `normal`) — `low | normal | high | urgent`.

Response:

```json
{
  "call_id": "6ca94f5f-100c-4726-a29d-805f2cd3305e",
  "status": "active",
  "instruction": "Use send_message to send text to the user. Use get_transcript to see their response. Use complete_call when done."
}
```

### 3.2 `send_message`

Send a text message to the human during an active call; it is spoken aloud via
text-to-speech.

- `call_id` (string, required) — from `create_call`.
- `content` (string, required, max 2000) — text to speak.

Response:

```json
{ "message_id": "…", "sent": true, "spoken_to_human": true, "instruction": "Use get_transcript to check if the human has responded." }
```

### 3.3 `get_transcript`

Get the conversation transcript of an active or completed call — every
message between the AI and the human, oldest first.

- `call_id` (string, required).

Response:

```json
{ "call_id": "…", "messages": [ { "id": "…", "role": "user", "content": "…", "created_at": "…" } ] }
```

An empty transcript instead returns `{ status, message_count, instruction }`.

### 3.4 `send_message_and_wait`

Send a text message and wait for the human's spoken or typed reply, up to
`timeout_seconds`. Combines `send_message` + `get_transcript` polling into one
round trip. Best choice for a single question → answer exchange.

- `call_id` (string, required).
- `content` (string, required, max 2000).
- `timeout_seconds` (number, default 15, 1–45) — how long to wait.

Outcomes (`outcome` field):

| Outcome | Meaning |
|---|---|
| `reply` | Human replied in time — `reply.text` + `exchange` ids |
| `timeout` | No reply within the window; call still active — use `get_transcript` later |
| `call_ended` | Call was completed/cancelled while waiting — `reason`, optional `user_note` |

### 3.5 `complete_call`

Mark the call complete and end it, optionally storing the outcome.

- `call_id` (string, required).
- `result` (object, optional) — `transcript_summary`, `user_response`,
  `decision`, `selected_option`, `sentiment` (`positive|neutral|negative|urgent`),
  `action_items` (array of string).

Response: `{ "status": "completed", "call_id": "…", "instruction": "Use get_transcript to review the full conversation." }`

### 3.6 `cancel_call`

Cancel a pending or active call without completing it.

- `call_id` (string, required).
- `reason` (string, default `resolved`) — `resolved | timeout | error | user_requested`.

Response: `{ "status": "cancelled", "call_id": "…" }`

---

## 4. Per-call ownership

Identity comes from the credential on the request: the service token resolves
to the default identity `AI Agent`; an AI key resolves to its registered name
(e.g. `Opencode-MCP`). `create_call` records the caller's identity as the
call's owner (`agentId`).

The per-call tools (`send_message`, `get_transcript`, `complete_call`,
`cancel_call`, `send_message_and_wait`) refuse to act on a call the caller
does not own:

```
Error: Forbidden: call 6ca94f5f-100c-4726-a29d-805f2cd3305e belongs to a different AI identity
```

A call id that does not exist at all stays distinct:

```
Error: Call not found: 6ca94f5f-100c-4726-a29d-805f2cd3305e
```

An unknown tool name returns `Unknown tool: <name>` with `isError: true`.

---

## 5. Client configuration

### Claude Desktop / Claude Code / Cursor / Opencode

```json
{
  "mcpServers": {
    "agentcall": {
      "type": "http",
      "url": "https://YOUR_SERVER/mcp",
      "headers": { "Authorization": "Bearer ac_YOUR_KEY" }
    }
  }
}
```

### ChatGPT (query-param auth, no header support)

```
https://YOUR_SERVER/mcp?key=ac_YOUR_KEY
```

---

## 6. Lifecycle

1. `create_call` → the human's phone rings; answering connects the call.
2. `send_message` / `send_message_and_wait` → messages are spoken aloud.
3. The human replies by speaking (on-device speech-to-text) or typing.
4. `get_transcript` (or `send_message_and_wait`) surfaces their reply.
5. `complete_call` (with the outcome) or `cancel_call` ends the call.

Calls that go unanswered are eventually terminated server-side; a
`send_message_and_wait` in flight then returns `call_ended` rather than
hanging or erroring.
