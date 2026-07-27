# AgentCall v2 — Architecture

> **Status:** Principal Architect Review, Pre-Implementation
> **Metaphor:** Message-Oriented Middleware for AI↔Human communication
> **Not an OS.** Not an AI platform. A message bus with human endpoints.

---

## 0. Challenging the Vision

Before designing, I challenged every assumption in the brief. Here are the
flaws found:

### 0.1 The OS Metaphor is Misleading

An operating system schedules hardware resources (CPU, memory, disk). AgentCall
schedules **human attention** — which is fundamentally different. Human attention
cannot be preempted, quantified, or guaranteed. Building a "microkernel" with
"device drivers" for "channels" adds architectural ceremony without architectural
benefit.

**Better metaphor:** AgentCall is a **message broker** (like RabbitMQ or NATS)
with human endpoints. Messages arrive from AI agents (via MCP), are routed based
on policy, and delivered to human devices. This is simpler, testable, and
well-understood by engineers.

### 0.2 "Plugin System" is Premature

Pluggable channels sound extensible but introduce a host-contract boundary that
will break as the core evolves. For v1, channels are **compiled-in and
config-selected**. A plugin API can be extracted when there are three or more
channel implementations that genuinely need independent release cycles.

### 0.3 Presence as a "Primitive" is Wrong

Presence is **derived state**, not a primitive. Computing it from device
heartbeats + calendar + time-of-day is more accurate than any state machine a
user would manually maintain. A "presence engine" with state transitions and
timeouts is over-engineered for v1.

### 0.4 Push Notifications are a Cloud Dependency

The brief says "no cloud dependency for core functionality." Push notifications
require FCM (Google) or APNs (Apple). These are acceptable infrastructure
dependencies — like DNS or HTTPS — but be explicit: **delivery uses cloud push
services.** The daemon itself runs locally; the notification path touches Google
and Apple infrastructure.

### 0.5 "Offline Tolerant" is Vague

If the daemon is offline, no communication happens. The daemon IS the
communication. The correct property is **crash recovery**: restart → replay
in-flight sessions from SQLite → resume. Calling this "offline tolerance"
sets wrong expectations.

### 0.6 Multi-Device Routing is a Function, Not an Engine

Device routing is a sorted list + acknowledgment timeout + fallback. Codifying
this as a "routing engine" with its own state machine adds complexity without
value. It's a 50-line function in the delivery router.

### 0.7 The Permission Model is Too Complex for v1

Per-agent trust levels, quiet hours, rate limits, emergency overrides, and
consent flows constitute a full policy engine. For v1, a binary allow/block
list + global quiet hours is sufficient. The policy engine can be layered on
without changing the delivery path.

### 0.8 Summary of Corrections

| Original Assumption | Corrected |
|---|---|
| Communication OS | Message broker with human endpoints |
| Plugin system | Compiled-in channels, config-selected |
| Presence engine | Derived state, computed on query |
| No cloud dependency | Daemon is local; push uses FCM/APNs (acceptable) |
| Offline tolerant | Crash recovery |
| Device routing engine | Priority list + timeout function |
| Full permission engine | Binary allow/block v1, policy engine later |

---

## 1. Architecture Overview

```
                          ┌────────────────────────┐
                          │    AI AGENTS (MCP)      │
                          │  Claude  ChatGPT  Cursor │
                          │  OpenCode  Codex  Gemini │
                          └───────────┬────────────┘
                                      │
                                      │ MCP (stdio or SSE)
                                      │ JSON-RPC over stdio pipe
                                      │ or Server-Sent Events
                                      ▼
┌─────────────────────────────────────────────────────────────────┐
│                     AGENTCALL DAEMON                            │
│                                                                 │
│  ┌────────────────────────────────────────────────────────┐     │
│  │  MCP TRANSPORT LAYER                                   │     │
│  │  • stdio (default) — for local AI agents               │     │
│  │  • SSE — for remote AI agents (optional, config)       │     │
│  │  • Auth: API key in MCP headers (stdin or SSE header)  │     │
│  └────────────────────────┬───────────────────────────────┘     │
│                           │                                      │
│  ┌────────────────────────▼───────────────────────────────┐     │
│  │  MESSAGE ROUTER (CORE)                                 │     │
│  │                                                         │     │
│  │  The router is stateless. It validates, enriches,       │     │
│  │  and routes messages between AI agents and human        │     │
│  │  devices. It calls services for policy decisions.       │     │
│  │                                                         │     │
│  │  route(message) → [validate] → [policy check] →         │     │
│  │    [enrich] → [deliver] → [acknowledge]                  │     │
│  └────────────────────────┬───────────────────────────────┘     │
│                           │                                      │
│  ┌────────────────────────▼───────────────────────────────┐     │
│  │  SERVICES (called by router, not inline)                │     │
│  │                                                         │     │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │     │
│  │  │ Session  │ │ Policy   │ │ Presence │ │ Device   │  │     │
│  │  │ Engine   │ │ Engine   │ │ Resolver │ │ Registry │  │     │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │     │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐               │     │
│  │  │ Delivery │ │ Notifica-│ │ Channel  │               │     │
│  │  │ Queue    │ │ tion Svc │ │ Registry │               │     │
│  │  └──────────┘ └──────────┘ └──────────┘               │     │
│  └────────────────────────┬───────────────────────────────┘     │
│                           │                                      │
│  ┌────────────────────────▼───────────────────────────────┐     │
│  │  DELIVERY BUS                                          │     │
│  │                                                         │     │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │     │
│  │  │ Push GW  │ │ WS Relay │ │ Webhook  │ │ HTTP Poll│  │     │
│  │  │(FCM/APN) │ │(realtime)│ │(async)   │ │(fallback)│  │     │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │     │
│  └────────────────────────┬───────────────────────────────┘     │
│                           │                                      │
│  ┌────────────────────────▼───────────────────────────────┐     │
│  │  STORAGE (SQLite, single file)                         │     │
│  │  • sessions, messages, devices, agents, policies        │     │
│  │  • No migrations — auto-schema on first launch          │     │
│  │  • WAL mode for concurrent read/write                   │     │
│  └────────────────────────────────────────────────────────┘     │
└─────────────────────────────────────────────────────────────────┘
           │
           │ Push notification (FCM) or WebSocket
           │ or HTTP (polling fallback)
           ▼
┌─────────────────────────────────────────────────────────────────┐
│                     HUMAN DEVICES                                 │
│                                                                   │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│  │ Android  │ │ Desktop  │ │ Browser  │ │ Future   │            │
│  │ App      │ │ App      │ │ UI       │ │ (watch,  │            │
│  │          │ │ (future) │ │ (future) │ │  car...) │            │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘            │
└─────────────────────────────────────────────────────────────────┘
```

---

## 2. Component Descriptions

### 2.1 MCP Transport Layer

**Purpose:** Accept connections from MCP-compatible AI agents.

**Two transports:**
- **stdio (default):** AI agent spawns daemon as subprocess, communicates over
  stdin/stdout. Zero networking. Maximum security. Works with Claude Desktop,
  Cursor, OpenCode, and any MCP host that supports stdio.
- **SSE (optional):** Daemon exposes HTTP endpoint. AI agents connect via
  Server-Sent Events. Required when AI agent cannot spawn local processes
  (e.g., cloud-hosted agents).

**Auth:** API key passed as MCP `authorization` header (SSE) or first line of
stdin (stdio). Key identifies the agent. No sessions, no JWTs — just a key
lookup on every request.

### 2.2 Message Router (Core)

**Purpose:** Central routing logic. Stateless. Idempotent.

The router is the only mandatory component. It:

1. **Validates** the incoming request (schema, agent auth, session exists if
   continuing)
2. **Calls Policy Engine** to check if this agent can communicate with this
   user at this time
3. **Calls Presence Resolver** to determine user availability
4. **Calls Device Registry** to find active devices
5. **Calls Session Engine** to create/update session record
6. **Enqueues** delivery via Delivery Bus
7. **Returns** session_id and status to the AI agent

The router does NOT:
- Store state (that's the Session Engine's job)
- Make policy decisions (that's the Policy Engine's job)
- Deliver messages directly (that's the Delivery Bus's job)

### 2.3 Session Engine

**Purpose:** Create, read, update communication sessions.

A session is the core abstraction. It represents one AI→human communication
episode, which may contain multiple messages.

```typescript
interface Session {
  id: string;
  agent_id: string;
  recipient_id: string;
  channel_type: string;
  capability: CommunicationCapability;
  context: string;
  status: SessionStatus;
  urgency: Urgency;
  created_at: number;
  updated_at: number;
  expires_at: number;
  messages: Message[];
  human_response?: string;
  delivery_attempts: number;
  acknowledged_device?: string;
}
```

**Session lifecycle:**
```
request_communication → [pending]
    ↓ agent receives session_id
agent sends first message → [active]
    ↓ human responds
agent gets response → [active] (can send more)
    ↓
agent ends → [completed]
human ignores → [expired] (after ttl)
agent cancels → [cancelled]
```

### 2.4 Policy Engine

**Purpose:** Answer "should this message reach the human?"

Decoupled from the router so policy logic can change without touching routing.

```typescript
interface PolicyDecision {
  allowed: boolean;
  reason?: string;
  channel_restrictions?: string[];
  should_interrupt: boolean;
  estimated_delay: string;
}
```

For v1, the policy engine reads agent permissions + global settings and returns
a binary decision. Future versions add ML-based urgency detection, learning from
user behavior, etc. — without changing the router.

### 2.5 Presence Resolver

**Purpose:** Answer "what is the user's current availability?"

Presence is **computed on demand**, not stored as state. The resolver checks:

1. **Device heartbeat:** Any device active in last 5min?
2. **Calendar:** Current event? (optional, local calendar file)
3. **Time of day:** Within quiet hours?
4. **Manual status:** User-set presence override?
5. **Focus mode:** User-set focus session active?

Returns a presence enum + metadata explaining the decision (for display).

```typescript
interface Presence {
  status: 'available' | 'busy' | 'away' | 'sleeping' | 'offline' | 'dnd';
  since: number;
  explanation: string;
  next_available_at?: number;
}
```

### 2.6 Device Registry

**Purpose:** Track all devices registered to a user.

Each device has:
- A unique device ID
- Push token (for FCM/APNs)
- Capabilities (can receive text? voice? structured?)
- Priority (which device to try first)
- Last seen timestamp
- Active/inactive status

The registry is essentially a CRUD table in SQLite.

### 2.7 Delivery Bus

**Purpose:** Deliver messages to human devices.

The delivery bus is a **fan-out router**:

1. Look up all devices for the user from Device Registry
2. Filter by device capabilities (can this device handle this channel type?)
3. Sort by device priority
4. Attempt delivery to highest-priority device
5. Wait for acknowledgment (via WebSocket or push open confirmation)
6. If no ack in N seconds, try next device
7. If all devices fail, queue for retry

Delivery channels are pluggable via a simple interface:

```typescript
interface DeliveryChannel {
  name: string;
  priority: number;
  canDeliver(session: Session): boolean;
  deliver(session: Session, message: Message): Promise<DeliveryResult>;
}
```

### 2.8 Notification Service

**Purpose:** Manage push notification lifecycle.

Handles:
- Token registration / unregistration
- Push notification sending (FCM for Android, APNs for iOS)
- Notification dismissal (when user reads on another device)
- Notification grouping (multiple messages, one notification)

### 2.9 Channel Registry

**Purpose:** Register and discover delivery channels.

For v1, channels are compiled-in:
- Push (FCM/APNs) — primary delivery channel
- WebSocket — for foreground delivery
- HTTP Poll — fallback for desktop/browser

Future channels (Voice, Video, etc.) register here.

---

## 3. Communication Model

Neither "calls" nor "messages." AgentCall uses **communication capabilities**.

A capability is a type of communication an AI can request:

```typescript
type CommunicationCapability =
  // v1
  | 'notify'          // "Hey, here's something to look at"
  | 'message'         // "Reply when you can"
  | 'decision'        // "Choose between A, B, or C"
  | 'approval'        // "Approve or reject this"
  | 'confirmation'    // "Confirm you've seen this"
  | 'callback'        // "Ask me to call you back"

  // v2+
  | 'voice'           // "Let's talk"
  | 'video'           // "Face-to-face"
  | 'screenshare'     // "Look at this"
  | 'file'            // "Here's a document"
  | 'transaction'     // "Sign this"
```

Each capability maps to a specific UI presentation on the mobile app:

| Capability | UI Treatment |
|---|---|
| `notify` | Notification + one-tap dismiss |
| `message` | Chat thread |
| `decision` | Card with options, one-tap choose |
| `approval` | Card with approve/reject buttons |
| `confirmation` | "Seen" acknowledgment |
| `callback` | Schedule picker (like current "Later") |

The model is extensible: adding a new capability requires:
1. Define the capability string
2. Implement the UI component in the app
3. Register the capability in the capability registry (if it changes routing)

No daemon changes needed for new capabilities that use existing delivery
channels.

---

## 4. Storage Schema

SQLite, single file (`agentcall.db`), auto-created.

```sql
-- Only 5 tables for v1

CREATE TABLE agents (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  key_hash TEXT NOT NULL,
  trust_level INTEGER DEFAULT 2,
  allowed BOOLEAN DEFAULT TRUE,
  icon TEXT,
  created_at INTEGER NOT NULL
);

CREATE TABLE sessions (
  id TEXT PRIMARY KEY,
  agent_id TEXT NOT NULL REFERENCES agents(id),
  recipient_id TEXT NOT NULL DEFAULT 'me',
  channel_type TEXT NOT NULL,
  capability TEXT NOT NULL,
  context TEXT NOT NULL,
  urgency TEXT NOT NULL DEFAULT 'normal',
  status TEXT NOT NULL DEFAULT 'pending',
  human_response TEXT,
  expires_at INTEGER,
  acknowledged_device TEXT,
  created_at INTEGER NOT NULL,
  updated_at INTEGER NOT NULL
);

CREATE TABLE messages (
  id TEXT PRIMARY KEY,
  session_id TEXT NOT NULL REFERENCES sessions(id),
  sender TEXT NOT NULL CHECK(sender IN ('agent', 'human')),
  content TEXT NOT NULL,
  content_type TEXT NOT NULL DEFAULT 'text',
  payload TEXT, -- JSON for structured content (decisions, approvals)
  created_at INTEGER NOT NULL
);

CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  user_id TEXT NOT NULL DEFAULT 'me',
  name TEXT NOT NULL,
  platform TEXT NOT NULL,
  push_token TEXT NOT NULL,
  capabilities TEXT NOT NULL DEFAULT '["text"]', -- JSON array
  priority INTEGER NOT NULL DEFAULT 10,
  is_active BOOLEAN DEFAULT TRUE,
  last_seen_at INTEGER,
  created_at INTEGER NOT NULL
);

CREATE TABLE policies (
  agent_id TEXT NOT NULL,
  setting TEXT NOT NULL,
  value TEXT NOT NULL,
  PRIMARY KEY (agent_id, setting)
);
-- Example rows:
-- ('claude', 'quiet_hours_start', '22:00')
-- ('claude', 'quiet_hours_end', '07:00')
-- ('chatgpt', 'max_sessions_per_hour', '5')
-- ('*', 'default_permission', 'allow')
```

That's 5 tables. No migrations. No ORM. Schema created on first run.

---

## 5. Key Design Decisions

| Decision | Choice | Rationale |
|---|---|---|
| Storage | SQLite | Single file, zero config, embedded in daemon |
| Protocol | MCP only | AI agents already speak MCP; no custom SDK |
| Transport | stdio > SSE | stdio is more secure, simpler, zero networking |
| Channels | Compiled-in | Plugin system is premature for v1 |
| Presence | Derived state | More accurate than manual state, less code |
| Permissions | Binary allow/block v1 | Policy engine later |
| Device routing | Priority list + timeout | A function, not an engine |
| Session model | Capability-based | Extensible without breaking core |
| Push dependenc | FCM/APNs (acceptable) | Only cloud dependency; no AI platform dep |
| Auth | API key per agent | Simple, stateless, MCP-compatible |
| Daemon language | TypeScript/Node.js | Matches current codebase; no rewrite needed |
| Human app | Android first | Existing codebase; iOS from patterns later |

---

## 6. Boundaries (What AgentCall Does NOT Do)

This is as important as what it does.

AgentCall does NOT:
- Run AI models
- Store AI context or memory
- Generate responses
- Decide when to communicate (that's the AI's job)
- Know what the AI is doing (that's the AI's job)
- Provide a UI for AI configuration
- Host AI services
- Provide authentication for AI platforms
- Implement business logic for communication (that's the policy engine's job,
  and it's user-configured)

AgentCall IS:
- A pipe between AI agents and humans
- A permission boundary
- A state tracker for communication sessions
- A delivery mechanism
