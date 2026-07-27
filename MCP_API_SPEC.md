# AgentCall — MCP API Specification

> Version 2.0.0 — Protocol: Model Context Protocol (JSON-RPC)
> AgentCall exposes itself AS an MCP server. Every AI that speaks MCP can use it.

---

## 0. Design Philosophy

- **Tools, not REST endpoints.** MCP tools are the API. There is no HTTP API.
- **Resources for state, Tools for actions.** Session state is a resource;
  creating a session is a tool.
- **One tool per capability.** Not one tool per variation.
- **Idempotent where possible.** Re-sending the same message is safe.
- **Errors are structured.** Every error has a code, message, and machine-readable
  details.

---

## 1. Connection

### stdio (default)

```json
// AI agent launches: node agentcall-mcp.js
// Communication happens via stdin/stdout JSON-RPC

{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {
  "name": "request_communication",
  "arguments": {
    "recipient_id": "me",
    "capability": "decision",
    "context": "Which deployment strategy should I use for the database migration?"
  }
}}
```

Auth: API key passed as first line of stdin.

```
ac-sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
{"jsonrpc": "2.0", ...}
```

### SSE (optional)

```
POST /mcp HTTP/1.1
Authorization: Bearer ac-sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Content-Type: application/json

{"jsonrpc": "2.0", ...}
```

---

## 2. Tools

### 2.1 `request_communication`

**Purpose:** Initiate a communication session. Creates a session and routes it
to the human's devices.

**Permission required:** Agent must be `allowed = true` in agents table.

```json
{
  "name": "request_communication",
  "description": "Request to communicate with a human. Creates a session that will be delivered to the user's devices.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "recipient_id": {
        "type": "string",
        "description": "Which human to contact. Default 'me' for single-user setups.",
        "default": "me"
      },
      "capability": {
        "type": "string",
        "enum": ["notify", "message", "decision", "approval", "confirmation", "callback"],
        "description": "Type of communication requested. Determines UI presentation on the device."
      },
      "context": {
        "type": "string",
        "description": "Why you're contacting the human. This is the primary content they'll see.",
        "maxLength": 5000
      },
      "urgency": {
        "type": "string",
        "enum": ["low", "normal", "urgent"],
        "default": "normal",
        "description": "How urgently you need a response. 'urgent' may interrupt DND for trusted agents."
      },
      "options": {
        "type": "array",
        "items": { "type": "string" },
        "description": "For 'decision' capability: choices to present to the human.",
        "maxItems": 10
      },
      "ttl_seconds": {
        "type": "number",
        "description": "Auto-cancel if human doesn't respond in this time. Default: 3600 (1 hour). Max: 86400 (24 hours).",
        "default": 3600,
        "minimum": 60,
        "maximum": 86400
      }
    },
    "required": ["capability", "context"]
  }
}
```

**Response:**

```json
{
  "session_id": "sess_abc123",
  "status": "pending",
  "estimated_wait": "now",
  "created_at": "2026-07-26T10:00:00.000Z",
  "expires_at": "2026-07-26T11:00:00.000Z"
}
```

**Status values:**
- `pending` — session created, delivery in progress
- `delivered` — human's device received it
- `read` — human opened it
- `responded` — human responded (response in `get_session`)
- `completed` — session ended normally
- `cancelled` — AI cancelled via `cancel_session`
- `expired` — TTL passed without response
- `rejected` — human explicitly declined
- `blocked` — policy engine blocked delivery

**Lifecycle:**

```
request_communication → [pending] → [delivered] → [read] → [responded]
                                                                  ↓
                                                          send_message (agent continues)
                                                                  ↓
                                                          cancel_session (agent ends)
```

### 2.2 `send_message`

**Purpose:** Send additional content in an active session. Only works if session
status is `active` or `responded`.

**Permission required:** Agent must own the session.

```json
{
  "name": "send_message",
  "description": "Send a message in an existing session. The human will receive it as a continuation.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "session_id": {
        "type": "string",
        "description": "Session ID from request_communication."
      },
      "content": {
        "type": "string",
        "description": "Message content.",
        "maxLength": 10000
      },
      "content_type": {
        "type": "string",
        "enum": ["text", "structured"],
        "default": "text",
        "description": "How to interpret the content. 'structured' for JSON payloads."
      }
    },
    "required": ["session_id", "content"]
  }
}
```

**Response:**

```json
{
  "message_id": "msg_xyz789",
  "status": "delivered"
}
```

**Status values:**
- `delivered` — pushed to device
- `read` — human viewed it
- `failed` — delivery failed (session may still be active)

### 2.3 `get_session`

**Purpose:** Get full session state including all messages and human's response.

**Permission required:** Agent must own the session, OR have `trust_level >= 3`.

```json
{
  "name": "get_session",
  "description": "Get current session state, messages, and human response.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "session_id": { "type": "string" }
    },
    "required": ["session_id"]
  }
}
```

**Response:**

```json
{
  "id": "sess_abc123",
  "agent_id": "agent_claude_abc123",
  "capability": "decision",
  "context": "Which deployment strategy?",
  "status": "responded",
  "urgency": "normal",
  "messages": [
    {
      "id": "msg_1",
      "sender": "agent",
      "content": "Which deployment strategy should I use for the database migration?",
      "content_type": "text",
      "created_at": "2026-07-26T10:00:00.000Z"
    },
    {
      "id": "msg_2",
      "sender": "human",
      "content": "Use rolling update. Canary first, then full rollout.",
      "content_type": "text",
      "created_at": "2026-07-26T10:02:30.000Z"
    }
  ],
  "human_response": "Use rolling update. Canary first, then full rollout.",
  "created_at": "2026-07-26T10:00:00.000Z",
  "updated_at": "2026-07-26T10:02:30.000Z"
}
```

### 2.4 `cancel_session`

**Purpose:** Cancel a pending or active session. Human will be notified that the
session was cancelled.

**Permission required:** Agent must own the session.

```json
{
  "name": "cancel_session",
  "description": "Cancel a session. The human will see it was cancelled.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "session_id": { "type": "string" },
      "reason": {
        "type": "string",
        "description": "Optional reason for cancellation (shown to human).",
        "maxLength": 500
      }
    },
    "required": ["session_id"]
  }
}
```

**Response:**

```json
{
  "status": "cancelled"
}
```

### 2.5 `register_agent` (management)

**Purpose:** Register an AI agent identity. Returns an API key. This is a
one-time setup tool, not called during normal operation.

**Permission required:** Must be called with master admin key (set in config),
or via interactive `agentcall register-agent` CLI.

```json
{
  "name": "register_agent",
  "description": "Register a new AI agent. Returns an API key. One-time setup.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "name": {
        "type": "string",
        "description": "Human-readable agent name (shown in UI).",
        "maxLength": 100
      },
      "trust_level": {
        "type": "integer",
        "enum": [0, 1, 2, 3],
        "default": 2,
        "description": "Initial trust level. Can be changed later via policy."
      },
      "icon": {
        "type": "string",
        "description": "Emoji or icon identifier for UI display.",
        "default": "🤖"
      }
    },
    "required": ["name"]
  }
}
```

**Response:**

```json
{
  "agent_id": "agent_claude_abc123",
  "api_key": "ac-sk-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
  "status": "active"
}
```

### 2.6 `list_sessions` (management)

**Purpose:** List recent sessions. Useful for AI to check history before
requesting new communication.

**Permission required:** Agent can only see its own sessions.

```json
{
  "name": "list_sessions",
  "description": "List recent communication sessions for this agent.",
  "inputSchema": {
    "type": "object",
    "properties": {
      "limit": {
        "type": "integer",
        "default": 10,
        "maximum": 100
      },
      "status": {
        "type": "string",
        "enum": ["pending", "active", "responded", "completed", "cancelled", "expired"],
        "description": "Filter by status. Omit for all."
      },
      "since": {
        "type": "string",
        "format": "date-time",
        "description": "Only sessions created after this time."
      }
    }
  }
}
```

**Response:**

```json
{
  "sessions": [
    {
      "id": "sess_abc123",
      "capability": "decision",
      "context": "Which deployment strategy?",
      "status": "responded",
      "created_at": "2026-07-26T10:00:00.000Z",
      "updated_at": "2026-07-26T10:02:30.000Z"
    }
  ],
  "total": 1
}
```

---

## 3. Resources

MCP resources expose state. They are read-only, always available, and can be
subscribed to for real-time updates.

### 3.1 `agentcall://sessions/{session_id}`

Full session state (same as `get_session` response).

### 3.2 `agentcall://presence`

Current user presence.

```json
{
  "status": "available",
  "since": "2026-07-26T09:45:00.000Z",
  "explanation": "Device active 2 minutes ago",
  "next_available_at": null
}
```

### 3.3 `agentcall://agents/{agent_id}/status`

Agent's own status (quota remaining, recent activity).

```json
{
  "agent_id": "agent_claude_abc123",
  "sessions_today": 5,
  "sessions_remaining": 45,
  "last_session_at": "2026-07-26T10:00:00.000Z"
}
```

### 3.4 `agentcall://capabilities`

List of supported communication capabilities.

```json
{
  "capabilities": ["notify", "message", "decision", "approval", "confirmation", "callback"]
}
```

---

## 4. Prompts (MCP Prompts)

MCP prompts provide templates for common interactions. Agents can use them to
structure their communication requests.

### 4.1 `ask_decision`

```
You need the human to choose between options.
Use this template to structure your request_communication call.

Context: [What needs a decision]
Options:
- [Option A]
- [Option B]
- [Option C]

Capability: decision
Urgency: [low | normal | urgent]
```

### 4.2 `request_approval`

```
You need the human to approve or reject something.

Context: [What needs approval]
Details: [Supporting information]

Capability: approval
Urgency: [low | normal | urgent]
```

### 4.3 `send_notification`

```
You want to inform the human without requiring a response.

Context: [What they need to know]

Capability: notify
Urgency: [low | normal]
```

---

## 5. Error Codes

| Code | Message | When |
|---|---|---|
| `AGENT_NOT_FOUND` | Agent not found | Invalid or missing API key |
| `AGENT_BLOCKED` | Agent is blocked | Policy engine rejected |
| `SESSION_NOT_FOUND` | Session not found | Invalid session_id |
| `SESSION_NOT_OWNED` | Session not owned | Agent doesn't own this session |
| `SESSION_EXPIRED` | Session has expired | TTL passed |
| `SESSION_INACTIVE` | Session is not active | Status is cancelled/completed |
| `INVALID_CAPABILITY` | Unknown capability | Capability not in registry |
| `RATE_LIMITED` | Too many requests | Per-agent rate limit hit |
| `QUIET_HOURS` | Quiet hours active | Policy blocked due to time |
| `DELIVERY_FAILED` | All delivery channels failed | No device reachable |
| `INVALID_SCHEMA` | Request validation failed | Missing required field |
| `INTERNAL_ERROR` | Internal error | Bug (should not happen) |

Error response format:

```json
{
  "code": "DELIVERY_FAILED",
  "message": "All delivery channels failed",
  "details": {
    "attempted_devices": 2,
    "last_error": "push_token_invalid"
  }
}
```

---

## 6. Tool Summary

| Tool | Purpose | Perm | Idempotent | Rate Limited |
|---|---|---|---|---|
| `request_communication` | Create session | agent allowed | no (creates each time) | yes |
| `send_message` | Continue session | session owner | yes (by message_id) | yes |
| `get_session` | Read session | session owner | yes | no |
| `cancel_session` | End session | session owner | yes | no |
| `register_agent` | Create agent | admin key | no | no |
| `list_sessions` | List history | session owner | yes | no |

---

## 7. Backward Compatibility

MCP API is versioned via the tool names, not the transport. If breaking changes
are needed:

- Old tools are deprecated (kept for 6 months)
- New tools get `v2` suffix: `request_communication_v2`
- Deprecation logged on every old-tool call

---

## 8. Example: Full Communication Flow

```
AI Agent → request_communication(recipient: "me", capability: "decision",
  context: "Which cloud provider for the new service?", options: ["AWS", "GCP", "Azure"])

AgentCall → { session_id: "sess_123", status: "pending" }
  ↓ (push notification to phone)
Human taps notification → session opens in app
Human taps "GCP" → response sent back
  ↓
AI Agent → get_session("sess_123")
  → { status: "responded", human_response: "GCP" }

AI Agent → send_message("sess_123", "Thanks. I'll provision GCP resources.")
  → { status: "delivered" }

AI Agent → cancel_session("sess_123", "Task complete")
  → { status: "cancelled" }
```
