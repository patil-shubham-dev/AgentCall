# AgentCall — AI Integration

> **Core Principle:** AgentCall is a communication platform. The AI owns intelligence, AgentCall owns communication.
> AgentCall must never rewrite prompts, perform reasoning, enrich AI output, or generate summaries.
> See [PRODUCT_VISION.md](../PRODUCT_VISION.md) for the complete philosophy.

---

## Current State: NO AI PROVIDER CONNECTED

No AI provider is currently connected to the system. The backend manages call sessions and transports messages, but does **not** generate or enrich AI responses.

### How the Message Flow Works

1. AI calls `create_call` via MCP → Backend creates a session
2. AI calls `send_message` via MCP → Message transported to phone via WebSocket
3. User responds → Response transported back via HTTP POST
4. AI reads transcript via `get_transcript`
5. AI calls `complete_call` or `cancel_call` when done

AgentCall transports the communication. The AI handles all conversation logic.

---

## MCP Tools for AI Agents

The full API contract requires 8 MCP tools (see [API_SPEC.md](../API_SPEC.md)). Currently 5 are implemented:

### Implemented

### Tool 1: `create_call`
```
Input:  user_id, context (reason, summary), priority
Output: call_id, status
Purpose: Initiate a communication session with a human.
```

### Tool 2: `send_message`
```
Input:  call_id, content
Output: message_id
Purpose: Send text to human during an active session.
```

### Tool 3: `get_transcript`
```
Input:  call_id
Output: messages (array of {role, type, content, created_at})
Purpose: Get the conversation transcript so far.
```

### Tool 4: `complete_call`
```
Input:  call_id, result
Output: status
Purpose: End the session and store the result.
```

### Tool 5: `cancel_call`
```
Input:  call_id, reason
Output: status
Purpose: Cancel a pending or active session.
```

### Not Yet Implemented (See API_SPEC.md)

| Tool | Purpose | Priority |
|------|---------|----------|
| `query_presence` | Check if user is available | P0 |
| `resume_task` | Get structured result after call | P0 |
| `notify_completion` | Send task-complete notification | P0 |

---

## How to Connect AI Providers

### Option A: Embedded MCP Endpoint (Streamable HTTP)

```
AI Agent (remote) → HTTP/SSE → /mcp (embedded in backend) → MCP tools → VoiceBridge service
```

The MCP server is embedded in the backend at `POST /mcp` (Streamable HTTP). No separate process or transport config needed.

**Setup:**
1. Create an AI key in the Android app: **Settings → AI Connections → Add AI** (the app shows the one-time key and ready-made snippets).
2. Point your AI agent at the endpoint with the key:

```bash
# Bearer auth (Claude, Cursor, Opencode)
claude mcp add agentcall --transport http --url https://YOUR_SERVER/mcp \
  --header "Authorization: Bearer ac_YOUR_KEY"

# Query-param auth (ChatGPT — no custom header support)
https://YOUR_SERVER/mcp?key=ac_YOUR_KEY
```

### Option B: REST API (for non-MCP providers)

See [API_SPEC.md](../API_SPEC.md) for the complete REST API reference.

---

## Provider Abstraction (Not Implemented)

There is **no provider abstraction layer** yet. The MCP server calls backend REST endpoints directly. Future work includes:

- Per-provider authentication (OAuth per provider)
- Provider isolation (separate history, sessions, callbacks per provider)
- Webhook registration for async response delivery
- Provider-agnostic event dispatching

See [MULTI_PROVIDER_PLAN.md](./MULTI_PROVIDER_PLAN.md) for the multi-provider strategy.
