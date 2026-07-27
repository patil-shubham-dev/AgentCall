# AgentCall v2 — Destructive Architecture Review

> Purpose: Break the architecture before it ships. Find every assumption
> that fails in production. If nothing breaks, explain why.
>
> Role: Hostile reviewer. No benefit of the doubt. Assume the worst case.

---

## 1. MCP-Only is a Bet the Whole Architecture Loses

The architecture has **no fallback protocol.** Every AI agent must speak MCP.
If MCP adoption stalls, fragments, or a simpler protocol wins, the daemon is
orphaned.

**The risk is not that MCP disappears.** The risk is that every AI provider
exposes their own native protocol _in addition to_ MCP, and users gravitate
toward the native integration because it's "more reliable" or "first-party."
AgentCall becomes the MCP-only also-ran that no one configures because the
built-in notification system of their AI platform works well enough.

**Worse:** The daemon has zero REST endpoints for management. There is no
`curl` command to check if it's running, no Swagger UI, no web dashboard.
Troubleshooting requires either raw MCP calls or reading JSON logs. This is a
support nightmare for what should be a 5-minute setup.

**Impact:** If MCP loses, the daemon loses. There is no Plan B.

### Counter-argument response:

The daemon's tools are 4 CRUD operations — `request_communication`, `send_message`,
`get_session`, `cancel_session`. These are simpler than a blogging API. Wrapping
them in REST+OpenAPI is one afternoon of work. The transport layer is a 50-line
adapter. But *the architecture as specified* has no such adapter, so the claim
"the daemon is MCP-only" is a true statement about the current design.

---

## 2. SQLite Will Not Survive Real Usage

Five tables. Single file. No replication. No archiving. No encryption.

```
agents:       will have ~10 rows. Fine.
sessions:     will have thousands. Fine.
messages:     will have tens of thousands. Fine.
devices:      will have ~5 rows. Fine.
policies:     will have ~10 rows. Fine.
```

The problem is not row count. The problem is **write contention.**

`get_session` is called every time:
- An AI agent polls for updates (every 1–30 seconds)
- A device acknowledges delivery
- A user sends a response
- A presence query runs (every query, recomputed)

With 3 AI agents polling every 5 seconds, 1 user interacting sporadically, and
2 devices heartbeating every 60 seconds, the daemon hits SQLite ~40 times per
minute. SQLite handles this — barely, with WAL mode.

**Now add one real-world scenario:** the AI agent calls `get_session` in a tight
loop waiting for a human response that takes 5 minutes. That's 60+ reads in 5
minutes. With 3 agents doing this for 3 different sessions, we're at 180+ reads
per 5 minutes, plus writes for heartbeats, presence calculations, and message
delivery. SQLite starts showing `SQLITE_BUSY` errors under the Node.js
better-sqlite3 wrapper which defaults to no retry logic.

**The real killer:** SQLite has no concurrent writer support. Two AI agents
creating sessions simultaneously? One blocks. An agent creating a session while
a device sends a heartbeat? One blocks. Under load, these micro-blocking events
compound into milliseconds of latency that feel like "the daemon is slow."

**SQLite backup while running:** The spec doesn't mention backup. If the user
copies the SQLite file while the daemon is writing, they get a corrupted backup.
The daemon needs WAL checkpoint management, backup hooks, or a backup API.

**Impact:** SQLite works for dev demos. Fails for daily use with multiple
AI agents. The architecture needs a write-ahead queue or must accept SQLite
limitations explicitly.

---

## 3. Push Notifications Are Not Infrastructure — They're a Dependency

The spec says: "FCM/APNs are acceptable infrastructure dependencies — like DNS
or HTTPS."

**This is false.** DNS and HTTPS are open standards. FCM and APNs are proprietary
services controlled by Google and Apple that can:
- Change pricing (unlikely for FCM, but iCloud push has limits)
- Deprecate APIs (Google deprecated GCM, could deprecate FCM)
- Block traffic by region (FCM is blocked in China)
- Require app store compliance (Apple can reject push notification usage)

**The real problem:** Without push, delivery degrades to WebSocket-only, which
only works when the app is foregrounded. If the user closes the app, they cannot
receive communications. This is not a communication OS — it's a chat app that
only works when open.

**And the daemon cannot work around it.** The daemon has no SMS gateway, no
email gateway, no webhook to a fallback delivery service. If FCM is down or
the user's device doesn't have Google Play Services (custom ROM, Huawei),
delivery silently fails. The session is created, the AI agent gets
`status: "pending"`, and it stays pending forever.

**This also means zero offline capability.** If the user is on an airplane,
in a tunnel, or has mobile data disabled, they are unreachable. The spec says
"offline tolerant" was changed to "crash recovery" — but the real issue is
that the HUMAN is offline, not the daemon. A communication OS that can't
communicate when the human has no signal is not an OS, it's a convenience
layer.

**Impact:** Push dependency is acceptable only if documented as a hard
requirement, with specific failure modes and fallback plans. The current
design has no fallback.

---

## 4. The Permission Model is Not Minimal

I critiqued the original brief for proposing a "full policy engine" and then
designed one anyway:

- Four trust levels (0–3) with different behaviors
- Per-agent allow/block, rate limits, capability restrictions
- Global quiet hours with per-agent override
- Emergency override with duration and cooldown
- Consent flow for untrusted agents
- `can_interrupt_dnd`, `can_request_urgent` flags
- Per-agent notification sounds

That's 7 distinct policy dimensions for a v1 that claims to be "binary allow/block
+ global quiet hours." The binary allow/block exists on paper but the actual
implementation in the spec and schema is the full policy engine.

**The contradiction:** I wrote "the policy engine can be layered on without
changing the delivery path" and then designed a delivery path that checks
`can_interrupt_dnd`, `trust_level`, `urgency`, `quiet_hours`, `emergency_override`,
and `consent_status` on every delivery. The delivery path already depends on the
policy engine — they cannot be separated.

**Impact:** Either commit to the full policy engine (and accept its complexity)
or strip it to actual binary allow/block + quiet hours. The current design is
neither minimal nor complete.

---

## 5. The Capability Model is Aspirational

Six capabilities defined: `notify`, `message`, `decision`, `approval`,
`confirmation`, `callback`. Each maps to a different UI presentation.

**Real-world problem:** An AI agent cannot be trusted to correctly categorize
its own communication. Claude sends a "decision" request that is actually a
notification. ChatGPT sends a "message" that expects a decision. The capability
field becomes noise because every agent optimizes for getting the human's
attention (sets `urgency: urgent`, picks the capability with highest priority).

**The architecture relies on AI agents being well-behaved.** There is no
server-side enforcement of capability semantics. What stops an agent from
marking every request as `capability: approval, urgency: urgent` to maximize
delivery probability? Nothing. The permission model has `allowed_capabilities`
but the capability field is self-reported.

**Worse:** The difference between `message` and `decision` is purely UI —
both send text to the human and get text back. The daemon treats them
identically. The capability model adds complexity at every layer (schema,
MCP API, permission engine, Android UI) for zero semantic difference at the
daemon level.

**Impact:** The capability model should either be enforced (daemon validates
that a "decision" actually has options) or eliminated (the capability is a
UI hint, not a protocol primitive). The current design has the worst of both:
it's modeled as a core primitive but enforced as a UI hint.

---

## 6. No Async AI Notification is a Protocol-Level Bug

An AI agent calls `request_communication` and gets `session_id`. The human
responds 5 minutes later. How does the AI know?

**The spec says: call `get_session` (polling).**

Polling is:
- **Wasteful:** The AI must poll at some interval. Poll every 1s = 300 polls
  for a 5-minute response. Each poll is a SQLite read + JSON serialize.
- **Slow:** Poll every 30s = average 15-second delay before the AI knows.
- **Complex:** The AI must implement a polling loop with timeout, backoff, and
  error handling. Every MCP client (Claude Desktop, Cursor, OpenCode) must
  implement this differently.
- **Non-standard:** MCP has `resources/subscribe` for real-time updates. The
  daemon doesn't implement it. The AI must build a workaround.

**The fix exists but is not designed:** The daemon could push updates to the AI
agent via the MCP transport (SSE has built-in server push; stdio could use a
notification channel). But the spec doesn't define this. The architecture as
designed forces every AI agent to poll.

**Impact:** Every MCP client integration must implement polling, which is
non-standard, inefficient, and creates inconsistent user experiences across
AI platforms. This is a protocol-level oversight.

---

## 7. stdio Transport Has Fundamental Problems

The default transport is stdio: the AI agent spawns the daemon as a child
process and communicates over stdin/stdout.

**Problem 1: One daemon per AI agent.** Each AI agent that wants AgentCall
must spawn its own daemon process. If Claude, ChatGPT, and Cursor each spawn
a daemon, there are three daemon processes running, each with its own SQLite
database, its own device registry, its own session store. The `recipient_id`
("me") is meaningless across three databases.

**Problem 2: Daemon lifecycle is tied to AI agent lifecycle.** When Claude
Desktop closes, its daemon child process is killed. In-flight sessions are
lost. The daemon cannot survive its parent.

**Problem 3: Multi-agent routing is impossible.** Three daemon processes
means three device registries. Two daemons push notifications to the same
phone independently. The phone shows duplicate sessions, duplicate
notifications, and has no single source of truth.

**The SSE transport fixes this** (single daemon, multiple AI agents connect
via network) but SSE is the optional secondary transport. The default (stdio)
doesn't work for multi-agent setups, which is the entire point of AgentCall
(many AI agents → one human).

**Impact:** The stdio transport should be for single-agent testing only. The
default should be SSE. This is a design inversion — the simpler, more secure
transport (stdio) is recommended as default, but it doesn't support the primary
use case.

---

## 8. No Offline Queue Means Lost Messages

The spec says "crash recovery is sufficient." This is wrong for two scenarios:

**Scenario 1: Daemon crashes while delivery is in flight.**
The daemon receives `send_message`, creates the message in SQLite, begins
delivery (calls FCM), and crashes. FCM accepts the request but the daemon dies
before marking the session as `delivered`. On restart, the session is still
`active`, no record of the send exists, the AI agent must retry. If the AI
agent doesn't retry (because it thinks it already sent the message), the
message is lost.

**Scenario 2: Human responds while daemon is down.**
The mobile app receives a push notification while the daemon is crashed. User
taps, sees the session, types a response, hits send. The app tries to POST to
the daemon but gets connection refused. The response is lost. The app shows
"send failed" but the user may not notice. The AI agent never gets the
response. The session stays `pending` forever.

**The mobile app has no offline queue.** It assumes the daemon is always
reachable. This works for LAN but fails for any network interruption.

**Impact:** The architecture assumes the daemon is always available, reliable,
and fast. Real systems are none of these. Without an offline queue on the
mobile app and an upstream acknowledgment protocol, messages will be lost
in production.

---

## 9. The Android App is an Afterthought

The `ANDROID_V2_SPEC.md` defines 20+ screens, but the delivery mechanism is
a single `DaemonClient` class that assumes the daemon is at
`localhost:7377`.

**On a phone, `localhost` is the phone, not the daemon.**

The daemon runs on the user's desktop/server. The phone connects over the
network. The user must:
1. Know their daemon's IP address or hostname
2. Ensure both devices are on the same network (LAN) or configure a VPN/SSH
   tunnel for remote access
3. Handle TLS if connecting over the internet
4. Handle dynamic IP changes (DHCP)
5. Handle NAT (phone and desktop may be on different subnets)

**This is not "it just works."** Email works because there's a server in the
cloud. WhatsApp works because there's a server in the cloud. AgentCall works
only if the user can solve local networking, which most users cannot.

**The spec says "DaemonClient handles connection state machine"** but that's
a state machine for "connected" vs "disconnected" — it doesn't solve the
fundamental problem of the phone reaching the daemon over the network.

**Impact:** The architecture has no addressing mechanism, no discovery protocol,
no NAT traversal, and no relay. For the phone to reach the daemon, the user
must solve networking. This limits AgentCall to tech-savvy users with
controllable networks (i.e., not most Android users).

---

## 10. The "Not an AI Platform" Claim is Weakened by the Design

AgentCall processes and stores AI-generated content (session context, messages).
It makes routing decisions based on AI agent identity and trust level. It
presents AI-generated decisions and approvals to the human.

**If the daemon is compromised, an attacker can:**
1. Read every AI↔human conversation (no encryption at rest)
2. Inject fake AI messages into sessions
3. Impersonate AI agents to request sensitive decisions
4. Modify session responses before they reach the AI
5. Register rogue devices to receive all communications

**The architecture trusts the local machine completely.** For a "communication
OS," this is equivalent to an operating system with no user/kernel separation.
Any process with access to the filesystem can read, modify, or inject
communication.

**This is fine for v1 with a documented trust model.** But "not an AI platform"
implies neutrality, not trustlessness. The architecture should be explicit:
"AgentCall trusts the local machine implicitly. Do not run on shared machines."

---

## 11. What DIDN'T Break

After the above, here's what survived the review:

**Session model:** Sessions as the core abstraction (not calls, not messages)
is correct. It maps well to real AI↔human interaction patterns. The lifecycle
(pending → active → completed) is clean and extensible.

**Capability as UI hint:** Despite the enforcement concern, treating capability
as a UI hint rather than a protocol constraint is pragmatic. The daemon doesn't
need to validate what a "decision" looks like. The AI self-categorizes, the
app renders accordingly. If the AI lies, the user sees a mislabeled message
but doesn't lose functionality.

**Presence as derived state:** This survived. Computing presence from device
signals is more accurate than manual state. The privacy rules (trust_level
determines visibility) are well-designed.

**Tiered device routing:** Simple, correct, extensible. The ack protocol with
suppression prevents duplicate notifications. The priority system is
user-configurable. No flaws found.

**Per-agent permissions:** Correct in concept. The execution is over-engineered
for v1 (see #4) but the model is sound. Trust levels map well to real-world
relationships (blocked, untrusted, default, trusted).

**SQLite for single-user:** If we accept single-user as a hard constraint,
SQLite is the right choice. The issue is write contention under load, which
can be mitigated with a simple in-memory write queue + batch flush.

**MCP for protocol:** MCP is the right protocol choice. The flaw is not having
a fallback, not the choice itself.

---

## 12. Verdict

**Should the architecture be built?** Yes, with five changes:

1. **Make SSE the default transport.** stdio becomes the testing/embedded
   transport. SSE solves multi-agent, solves daemon lifecycle, solves
   addressing. The daemon is a network service, not a child process.

2. **Add async AI notification.** Implement `resources/subscribe` so AI agents
   receive real-time updates when the human responds. Eliminate polling. This
   is a protocol requirement, not a nice-to-have.

3. **Add a mobile offline queue.** The Android app must queue responses
   locally and send them when the daemon is reachable. This prevents message
   loss during network interruptions.

4. **Strip the permission model to binary for v1.** `allowed: boolean`,
   `trust_level: 1|2` (default). Remove rate limits, per-agent quiet hours,
   consent flow, emergency override. Add these after v1 ships.

5. **Add an mDNS/Discovery protocol** so the Android app can find the daemon
   on the local network without manual IP configuration. Or document that the
   user must configure the daemon address and provide a QR code setup flow.

These are fixes, not redesigns. The architecture is fundamentally sound for
its stated purpose: a local-first, MCP-native, single-user communication
bridge between AI agents and a human. The flaws are in the implementation
details, not the core model.

**The one thing that would kill this architecture:** If MCP fails as a
protocol. Every other flaw is fixable within the architecture. MCP failure
is an existential risk that cannot be mitigated at the architecture level —
only at the project strategy level (multi-protocol support).
