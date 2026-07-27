# AgentCall: Communication OS for AI ↔ Human

> Architect's note: This is not a product spec. It's a first-principles answer to "what
> is the smallest thing that could work?" Everything below is derived from one constraint:
> **AgentCall is not an AI platform, it's infrastructure.**

---

## 1. The Core Insight

AI agents today have no standard way to reach a human. They write text into a chat
window and hope the human is looking. Every platform reinvents this separately —
ChatGPT has notifications, Claude has email, Cursor has desktop alerts. Each is a
fiefdom with its own protocol.

This is the same problem OS kernels solved for programs in the 1960s. Before
operating systems, every program talked directly to hardware. The OS introduced
**abstractions** (files, pipes, sockets) and **arbitration** (scheduling, permissions).
Programs stopped worrying about hardware; they used the OS.

AgentCall is that OS for AI-to-human communication.

### The Beliefs

This architecture only makes sense if you share these beliefs:

1. **The number of AI agents per human will exceed 10 per human within 2 years.**
   Each AI needs a way to reach its human. Having one chat app per AI doesn't scale.
2. **No single company will own the AI agent space.** The ecosystem will fragment
   across model providers, open-source agents, and vertical-specific AIs.
3. **MCP is the winning protocol for AI-to-tool communication.** It's open,
   adopted by Anthropic, OpenAI, and the community.
4. **Humans should control their own communication infrastructure.**
   Your AI access shouldn't be rented from a platform.

### What AgentCall IS

- A **local daemon** that speaks MCP and delivers messages to a human
- A **thin mobile client** that shows those messages
- A **permission boundary** between AI agents and human attention

### What AgentCall IS NOT

- Not an AI platform — no models, no prompts, no knowledge
- Not a SaaS — it's your daemon on your machine
- Not a chat app — it's the delivery layer under chat apps
- Not a VoiceBridge — communication is text-first; audio is a future channel

---

## 2. Architecture (One Diagram)

```
                         YOUR MACHINE
 ┌──────────────────────────────────────────────────────────────┐
 │                     AGENTCALL DAEMON                          │
 │                                                               │
 │  ┌────────────────────────────────────────────────────┐      │
 │  │  MCP Transport (stdio / SSE)                       │      │
 │  │  — request_communication(recipient, context)       │      │
 │  │  — send_message(session_id, content)               │      │
 │  │  — get_session(session_id)                         │      │
 │  └─────────────────────┬──────────────────────────────┘      │
 │                        │                                      │
 │  ┌─────────────────────▼──────────────────────────────┐      │
 │  │  Session Engine                                    │      │
 │  │  — validate agent key                              │      │
 │  │  — create/route/track session                      │      │
 │  │  — check permissions (block, quiet hours, etc.)    │      │
 │  │  — log to audit store                              │      │
 │  └─────────────────────┬──────────────────────────────┘      │
 │                        │                                      │
 │  ┌─────────────────────▼──────────────────────────────┐      │
 │  │  Delivery Router                                   │      │
 │  │  ┌───────────┐ ┌──────────┐ ┌────────────┐        │      │
 │  │  │ Push GW   │ │ WS Relay │ │ Webhook    │        │      │
 │  │  │ (FCM/APN) │ │ (realtime│ │ (async AI) │        │      │
 │  │  └─────┬─────┘ │  bridge) │ └────────────┘        │      │
 │  │        │       └────┬─────┘                        │      │
 │  └────────┼────────────┼──────────────────────────────┘      │
 └───────────┼────────────┼─────────────────────────────────────┘
             │            │
             ▼            ▼
      ┌────────────────┐  ┌────────────────┐
      │ Mobile App     │  │ WebSocket      │
      │ (push + view)  │  │ (if app open)  │
      └────────────────┘  └────────────────┘
             ▲
             │
            MCP
             │
  ┌──────────┴──────────────┬───────────────┐
  │                         │               │
  ▼                         ▼               ▼
 Claude                  ChatGPT         OpenCode
 (MCP client)            (MCP client)    (MCP client)
```

---

## 3. The MCP Contract (Complete API)

```jsonc
// Every AI agent connects via MCP. The daemon IS an MCP server.
// Tools an AI can call:

tool request_communication {
  description: "Request to communicate with a human user"
  input: {
    recipient_id: string,    // which human (default: "me")
    context: string,          // why the AI needs the human
    urgency?: "low" | "normal" | "urgent",
    ttl_seconds?: number      // auto-cancel if not answered
  }
  output: {
    session_id: string,
    status: "pending" | "accepted" | "rejected" | "timed_out",
    estimated_wait: string    // "now" | "5m" | "1h" | "unknown"
  }
}

tool send_message {
  description: "Send a message in an active session"
  input: {
    session_id: string,
    content: string,
    content_type?: "text" | "structured"  // default: "text"
  }
  output: {
    message_id: string,
    status: "delivered" | "read" | "failed"
  }
}

tool get_session {
  description: "Get session state and human's responses"
  input: { session_id: string }
  output: {
    id: string,
    status: string,
    context: string,
    messages: Array<{role, content, created_at}>,
    human_response?: string,
    created_at: string,
    updated_at: string
  }
}

tool cancel_session {
  description: "Cancel a pending or active session"
  input: { session_id: string }
  output: { status: "cancelled" }
}

// Management tools

tool register_agent {
  description: "Register an AI agent identity (one-time)"
  input: {
    name: string,
    contact_uri?: string
  }
  output: {
    agent_id: string,
    api_key: string,
    status: "active"
  }
}

resource agent://{agent_id}/status {
  // Read-only resource exposing agent info
  mimeType: "application/json"
}
```

### Usage Example: Claude asks to call you

```
Human: "Claude, can you call me when you find a bug?"

Claude's MCP client → AgentCall Daemon:
  request_communication(
    recipient_id: "me",
    context: "I found a bug in module X that needs your decision",
    urgency: "normal"
  )

AgentCall Daemon:
  1. Receives request via MCP (stdio/SSE)
  2. Validates Claude's agent key
  3. Checks permissions (Claude is allowed)
  4. Creates session (SQLite)
  5. Routes to push gateway
  6. Sends push notification to mobile app

Mobile app:
  1. Receives push
  2. Shows: "Claude needs you: I found a bug in module X"
  3. User taps → opens session view
  4. User reads context, responds (text)
  5. Response sent back via daemon's WS relay

Claude's MCP client:
  get_session(session_id) → sees human's response
```

---

## 4. Module Migration: Keep / Rewrite / Remove

### Keep (as-is or minor refactor)

| Current Module | Path | Why Keep |
|---|---|---|
| MCP server infra | `mcp-server/src/index.ts` | stdio + SSE transport; just swap tools |
| EventBus | `backend/src/services/EventBus.ts` | Internal pub/sub for daemon events |
| Config loader | `backend/src/config.ts` | Env-based config — works as-is |
| Health check | `backend/src/health.ts` | Daemon health — always needed |
| Error handler middleware | `backend/src/middleware/errorHandler.ts` | JSON error responses |

### Rewrite (same file, new content)

| Current Module | New Purpose |
|---|---|
| `mcp-server/` | Replace VoiceBridge tools with communication OS tools |
| `backend/` root | Replace Express REST API with daemon entry point (start MCP + push gateway + WS relay) |
| `mobile/android/` | Replace call-centric app with push receiver + session viewer |

### Remove

| Module | Reason |
|---|---|
| `backend/src/voicebridge/` (entire directory) | VoiceBridge is a specific AI→human workflow; this is the OS abstraction over it |
| `backend/src/routes.ts` | REST API replaced by MCP protocol |
| `backend/src/voicebridge/repositories/` | Postgres schemas for VoiceBridge calls |
| `backend/src/voicebridge/middleware/` | VoiceBridge-specific middleware |
| All PSTN / coturn / STUN/TURN config | Audio transport is out of scope for MVP |
| `infra/` (docker-compose, caddy) | Daemon is a single binary, no infra needed |
| `mobile/ios-archived/` | Archive; rebuild from Android patterns when needed |

> `backend/src/services/Prometheus.ts`, `MetricsCollector`: keep only if daemon needs
> observability. For MVP they can be removed or made optional — the daemon is simple
> enough that `console.log` + a health endpoint suffice.

### Storage

**Replace PostgreSQL with SQLite.** The daemon has one user (you). SQLite:
- Zero setup — no Docker, no migrations, no connection pooling
- Single file — backup with `cp agentcall.db agentcall.db.bak`
- Sufficient for this workload — hundreds of sessions, not millions
- No separate server process — embedded in the daemon

---

## 5. Phased Migration Plan

### Phase 1 — Daemon Core (week 1)

**Goal:** An AI agent can request communication and the daemon stores it.

Actions:
1. Create `/daemon/` directory at repo root with:
   - `src/mcp-server.ts` — MCP transport with 4 tools (request, send, get, cancel)
   - `src/session-engine.ts` — create/route/track sessions
   - `src/config.ts` — env config
   - `src/db.ts` — SQLite (better-sqlite3)
2. Implement agent key auth (API keys, one per agent)
3. Wire up: MCP request → validate → create session → store in SQLite
4. Remove: `backend/src/voicebridge/`, `backend/src/routes.ts`, all AI-specific code

Deliverable:
```bash
npx @anthropic/mcp-runner request_communication \
  --recipient_id "me" \
  --context "testing the daemon" \
  --transport stdio \
  --command "node daemon/src/mcp-server.js"
# → { session_id: "abc123", status: "pending" }
```

### Phase 2 — Push Delivery (week 2)

**Goal:** The daemon can deliver a session request to the mobile app via push.

Actions:
1. Add push gateway to daemon:
   - FCM (Android) with a no-op stub for local dev
   - WebSocket relay for when the app is foregrounded
2. Add `register_device` tool to MCP server
3. Mobile app: register device token on launch
4. Wire up: session created → push notification → phone buzzes

Key files to create:
- `/daemon/src/push/fcm.ts` — Firebase Admin SDK wrapper
- `/daemon/src/relay/ws.ts` — WebSocket relay for foreground delivery
- `/daemon/src/router.ts` — delivery router (push vs WS vs both)

### Phase 3 — Mobile App (week 3)

**Goal:** User can receive, view, and respond to communication on their phone.

Actions:
1. Strip Android app:
   - Remove: `CallService`, `CallActivity`, `IncomingCallActivity`, `SignalingClient`, `CallViewModel`, `CallEventBus`, `HomeViewModel`
   - Keep: DI module, theme, API client, navigation shell
2. Rewrite Android app:
   - `SessionActivity.kt` — message list + reply input
   - `SessionListScreen.kt` — history of sessions
   - `PushService.kt` — FCM message handler
   - `DaemonClient.kt` — HTTP/WS client to local daemon
3. App flow: push → tap → session view → read → reply → sent back

Wire up: push notification → user taps → opens session → user types response →
response POSTed to daemon → stored in SQLite → available via `get_session`.

### Phase 4 — Hardening (week 4)

**Goal:** Production-ready for real use.

1. Permission presets per agent:
   - `always_allow` — auto-accept (for trusted agents)
   - `ask_always` — always push first (default)
   - `quiet_hours` — suppress 10pm–8am
   - `block` — silent deny
2. Rate limiting: max N sessions per minute per agent
3. Session history page in app
4. Daemon auto-start (systemd user service / launchd)
5. Environment-based config:
   - `AGENTCALL_PUSH_ENABLED` — set to false for LAN-only dev
   - `AGENTCALL_DB_PATH` — where to store SQLite
   - `AGENTCALL_MCP_TRANSPORT` — stdio (default) or SSE (for remote agents)

---

## 6. What Gets Deleted

This is the most important section. Deleting the wrong things leaves dead weight;
deleting the right things is the whole point.

### Delete Entirely

```
backend/src/voicebridge/
  ├── callbacks/
  ├── events.ts
  ├── lifecycle.ts
  ├── middleware/
  ├── notifications.ts
  ├── recovery.ts
  ├── repositories/
  ├── service.ts
  ├── signaling.ts
  ├── types.ts
  └── validation.ts

backend/src/routes.ts

backend/src/middleware/validateApiKey.ts  (no API keys — agent keys are MCP-level)
backend/src/services/StunTurnService.ts    (if exists)
backend/src/services/PhoneService.ts       (if exists)

mobile/android/app/src/main/java/com/agentcall/app/
  ├── call/CallService.kt      ← full rewrite
  ├── call/CallActivity.kt     ← full rewrite
  ├── call/CallViewModel.kt    ← full rewrite
  ├── call/IncomingCallActivity.kt  ← full rewrite
  ├── call/SignalingClient.kt  ← full rewrite
  ├── call/CallEventBus.kt     ← full rewrite
  └── home/HomeViewModel.kt    ← merge into simpler state

mobile/ios-archived/
infra/
```

### Rewrite Scope

| File | From | To |
|---|---|---|
| `mcp-server/src/index.ts` | 5 VoiceBridge tools | 4+2 communication OS tools |
| `backend/src/index.ts` | Express server on port 4000 | Daemon entry: MCP + push + WS |
| `mobile/android/app/.../call/` | Voice call UI + service | Session viewer + push handler |
| `mobile/android/.../home/HomeScreen.kt` | Signaling status, active call | Session list, quick status |
| `mobile/android/.../data/api/` | Retrofit → VoiceBridge API | HTTP client → daemon API |

### Lines of Code Estimate

```
Deleted:   ~4,000 lines (voicebridge, routes, infra, iOS archive)
Rewritten: ~2,000 lines (mcp tools, daemon, android session)
New:       ~1,500 lines (push gateway, session engine, SQLite)
Net:       ~2,500 lines remaining, from ~7,000 today
           ≈ 65% reduction
```

---

## 7. Design Decisions

### Why MCP and not REST?

MCP is the protocol AI agents already speak. If AgentCall exposes REST, every AI
needs a custom integration. If AgentCall exposes MCP, any MCP-compatible AI
(Claude Desktop, Cursor, OpenCode, etc.) can connect in one line:

```bash
# In Claude Desktop config:
{
  "mcpServers": {
    "agentcall": {
      "command": "node", "args": ["daemon/mcp-server.js"],
      "env": { "AGENTCALL_API_KEY": "sk-..." }
    }
  }
}
```

No SDK. No REST client. No authentication dance. The MCP transport IS the API.

### Why text-first, not voice?

Voice is a delivery channel, not a communication primitive. By making the
core text, AgentCall can deliver via push notification (which is text). Voice can
be added later as a channel option — the session model doesn't change.

More importantly, voice has platform-specific complexity (WebRTC, STUN/TURN,
platform STT/TTS) that would balloon the MVP. Start with text. Add audio when
the core works.

### Why SQLite over PostgreSQL?

The daemon runs on one machine for one user. PostgreSQL requires a server
process, connection pooling, and migration tooling. SQLite is embedded in the
daemon process, zero-config, and fast enough for this workload.

If the daemon needs to serve multiple users in the future, the SQLite schema is
simple enough that migrating to Postgres is straightforward.

### Why strip the Android app?

The current Android app has ~3,000 lines of code for voice call handling
(CallService, CallActivity, IncomingCallActivity, SignalingClient, etc.). In
the new architecture, the mobile app does three things:

1. Register device token
2. Receive push notifications
3. Show session messages + accept reply input

That's ~500 lines. The signal-to-noise ratio of the current app is low for the
new vision.

### What if the AI goes offline?

The daemon stores sessions in SQLite. When the AI reconnects and calls
`get_session`, it sees the human's response. This works naturally with MCP —
resources are stateful, not ephemeral.

### What about end-to-end encryption?

Phase 4 or later. For MVP, the daemon runs on the user's machine and the mobile
app connects to it (directly on LAN or via a tunnel). The daemon → phone path
uses push notification encryption (TLS + FCM/APNs encryption). For a
privacy-first version, add E2EE in a later phase.

---

## 8. Risk Register

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| MCP doesn't become the standard protocol | Medium | High | MCP is already adopted by Anthropic + OpenAI. If it fragments, daemon adds adapter layer. |
| Users don't want a daemon | Low | High | They run it like they run Ollama or Docker. It's a developer tool, not a consumer product. |
| Push notification reliability is poor | Medium | Medium | FCM/APNs are mature. Fallback: WebSocket when app is foregrounded, poll when push fails. |
| AI agents spam the user | Low | Medium | Permission presets + rate limiting + quiet hours. Default is "ask always." |
| Single binary daemon is too complex | Low | Low | Node.js daemon with SQLite and FCM — simpler than current VoiceBridge. |

---

## 9. Defining "Done"

```
Phase 1 done when:
  - AI agent runs: request_communication → sees session_id
  - AI agent runs: send_message → sees message_id
  - Daemon stores everything in SQLite
  - Agent key auth works

Phase 2 done when:
  - Push notification reaches phone on session creation
  - WebSocket relay works when app is open
  - Device registration works

Phase 3 done when:
  - User receives push → taps → sees session
  - User types reply → stored in daemon
  - AI agent calls get_session → sees user's reply

Phase 4 done when:
  - Permission presets work
  - Rate limiting works
  - Session history in app
  - Daemon auto-starts on boot
```
