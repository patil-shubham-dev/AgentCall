# AgentCall — Technical Risks & Mitigations

> Every architecture makes bets. These are the ones AgentCall v2 makes,
> ranked by probability × impact, with specific mitigations.

---

## Risk Matrix

| ID | Risk | P | I | P×I | Mitigation |
|---|---|---|---|---|---|
| R1 | MCP fails as standard protocol | 20% | 10 | 2.0 | Adapter layer; tools are simple enough to expose as REST+WebSocket |
| R2 | Push notification latency too high | 40% | 6 | 2.4 | WebSocket fallback for foreground; notification grouping for background |
| R3 | Single-user architecture limits adoption | 60% | 4 | 2.4 | Deliberate v1 constraint; multi-user is additive, not a redesign |
| R4 | Users don't want to run a daemon | 40% | 7 | 2.8 | MCP stdio mode requires no daemon management (AI spawns it); SSE mode for headless |
| R5 | Android app rewrites miss UX quality | 50% | 5 | 2.5 | Spec details every screen; build in order of UX priority (SessionList → SessionDetail → agents → settings) |
| R6 | SQLite becomes bottleneck | 10% | 8 | 0.8 | Schema is 5 tables; SQLite handles single-user at any realistic volume; migration path to Postgres is documented |
| R7 | Permission model too complex for users | 30% | 5 | 1.5 | Defaults are sensible (all allowed, trust level 2); complex features are hidden until needed |
| R8 | No E2EE in v1 becomes liability | 25% | 7 | 1.75 | Daemon runs on user's machine; push path uses FCM/APNs TLS; E2EE added as Layer 2 |
| R9 | MCP stdio mode can't support remote AI agents | 15% | 6 | 0.9 | SSE transport exists for remote agents; stdio is for local agents only |
| R10 | Notification delivery unreliable | 35% | 7 | 2.45 | FCM/APNs are mature; fallback to WebSocket + HTTP poll; retry with backoff |
| R11 | Focus on Android alienates iOS users | 40% | 5 | 2.0 | iOS can be rebuilt from Android patterns when resources permit; documented in migration plan |
| R12 | Calendar presence integration breaches privacy | 15% | 6 | 0.9 | Calendar file is local, not uploaded; presence privacy rules limit agent visibility |
| R13 | API key auth is too simple for production | 20% | 4 | 0.8 | Daemon binds to localhost by default; API keys are for agent identification, not network auth |
| R14 | "Not an AI platform" limits practical usefulness | 30% | 6 | 1.8 | AI providers handle intelligence; AgentCall handles communication — this is a feature, not a bug |
| R15 | Daemon written in Node.js is wrong choice | 10% | 3 | 0.3 | Matches existing codebase; SQLite bindings are mature; perf is not the bottleneck for a message router |

**P** = Probability (0–100%), **I** = Impact (1–10)

---

## R1: MCP Fails as Standard Protocol

**What if MCP doesn't become the standard AI-to-tool protocol?**

MCP is already adopted by Anthropic (Claude Desktop), Cursor, and OpenCode.
OpenAI has announced MCP support. Even if a competing protocol (A2A, etc.)
emerges, MCP is a JSON-RPC transport — the simplest possible contract.

**Mitigation:** The daemon's tools are simple enough (4 tools, all
CRUD-on-steroids) that they can be exposed as a REST API in one afternoon.
The MCP transport is a thin adapter on top of the core logic. If MCP dies,
the core lives.

---

## R2: Push Notification Latency

**What if push notifications take 5–30 seconds to arrive?**

FCM typically delivers in < 1s. APNs similarly. But push is not guaranteed —
Doze mode, poor connectivity, and FCM throttling can delay delivery.

**Mitigation:** The app opens a WebSocket connection to the daemon whenever it's
foregrounded. When foregrounded, delivery is instant (sub-50ms). Push is the
"wake up" signal; WebSocket is the data channel. For background, push latency
is acceptable — the user is not actively waiting.

---

## R3: Single-User Architecture

**What if users want to share a daemon with family or team?**

They will. But supporting multi-user in v1 would double the architecture
complexity (user auth, group routing, shared sessions, conflict resolution).
Single-user gets us to working software fastest.

**Mitigation:** The data model already has `recipient_id` on sessions and
`user_id` on devices. Adding multi-user is a matter of:
1. Adding a users table
2. Adding user auth to the MCP transport
3. Scoping queries by user

This is additive, not a redesign.

---

## R4: "I Don't Want to Run a Daemon"

**What if users find daemon management too technical?**

Two paths mitigate this:

1. **MCP stdio mode:** The daemon is a process the AI agent spawns. User never
   sees it. Claude Desktop launches `node daemon/mcp-server.js` as a child
   process. User doesn't know a daemon exists.

2. **SSE mode:** For headless setups (server, NAS, Raspberry Pi), the daemon
   runs as a systemd service. This is for power users, which is the v1 target
   audience.

---

## R5: Android UX Quality

**What if the rewritten Android app has poor UX?**

The spec (`ANDROID_V2_SPEC.md`) details every screen at the interaction level,
not the pixel level. This is intentional — UX polish happens in implementation.

**Mitigation:** Build screens in priority order:
1. SessionList (P0 — without this, the app is useless)
2. SessionDetail + reply (P0 — without this, communication is one-way)
3. Push notification handling (P0 — without this, user never knows)
4. AgentList + AgentDetail (P1 — can ship without per-agent config)
5. Settings (P1 — daemon connection is the minimum)
6. DeviceList, History, Profile (P2 — quality of life)

---

## R6: SQLite Bottleneck

**What if the daemon grows beyond SQLite's capabilities?**

5 tables. Single user. Hundreds of sessions, not millions. SQLite handles this
trivially. SQLite's WAL mode supports concurrent read/write without locks.

**If multi-user is needed in the future:** The schema is 5 tables with no
stored procedures, no triggers, no views. Migrating to Postgres is:

```
1. Install pgvector (optional)
2. Create same 5 tables with serial PKs
3. Point daemon at Postgres instead of SQLite
4. Run tests
```

Estimated effort: 2 days.

---

## R7: Permission Model Complexity

**What if users find per-agent permissions overwhelming?**

The v1 permission model has sensible defaults:
- All agents are `allowed` and `trust_level=2`
- Quiet hours are 23:00–07:00
- No manual setup needed for basic operation

The per-agent UI is hidden behind a detail screen. The agent list shows the
basics (name, status, brief permission summary). Complexity is opt-in.

---

## R8: No End-to-End Encryption

**What if messages in the daemon's SQLite file are exposed?**

The daemon runs on the user's machine. SQLite file permissions are 600
(owner read/write only). Push notifications are encrypted by FCM/APNs TLS.

**For sensitive communication:** E2EE can be added as a Layer 2 concern:
1. Daemon generates a keypair on first launch
2. Mobile app gets the public key during device registration
3. Messages are encrypted at rest in SQLite (daemon's private key decrypts)
4. Push payloads are encrypted (daemon encrypts, mobile app decrypts)

This is a future phase. v1 trusts the local machine.

---

## R9: Remote AI Agents Need SSE

**What if the user's AI agents are cloud-hosted and can't use stdio?**

SSE transport exists for exactly this case. The daemon binds to localhost
(configurable) and exposes an HTTP endpoint. The remote AI agent connects
over the network.

If the AI agent is on a different machine, the user can:
1. Expose the daemon via SSH tunnel (`ssh -L 7377:localhost:7377 server`)
2. Use a reverse proxy with auth (Caddy + basicauth)
3. Run the daemon on a VPS with TLS

All of these are user's choice, not our infrastructure.

---

## R10: Notification Delivery Reliability

**What if FCM/APNs consistently fail to deliver?**

FCM and APNs are mature platforms with 99.9%+ delivery rates. The more common
problem is the user's device being offline (airplane mode, dead battery, etc.).

**Mitigation:** Three-layer delivery strategy:
1. **WebSocket (foreground)** — instant, reliable
2. **Push (background)** — FCM/APNs, best-effort
3. **HTTP Poll** — if push fails for N consecutive attempts, daemon marks
   session as "failed" and stores it. Mobile app polls on next launch.

---

## Architectural Risks (Not in Matrix)

### AR1: Scope Creep

The biggest risk is bolting "just one more feature" onto the daemon. The daemon
has 4 MCP tools and 5 SQLite tables. If we add more before shipping, we lose.

**Rule:** No new tables. No new tools. No new capabilities. Ship v1 with the
spec as written. Add features after the foundation is proven.

### AR2: The "OS" Metaphor Misleads Design

If the team starts building "kernel modules," "device drivers," or "ABI
stability guarantees," we've over-engineered. The daemon is a message broker
with a JSON-RPC interface. It's closer to NATS than Linux.

**Rule:** Every component must justify its existence with a concrete use case
in the v1 flow. If it can't, it doesn't exist.

### AR3: Shipping Nothing While Perfecting Architecture

Architecture documents don't ship software. By week 9, we should have working
code that passes the test:

> AI agent → MCP → daemon → push notification → phone → user responds → AI sees response

If this doesn't work end-to-end by week 9, the architecture is wrong.

**Rule:** Week 1: MCP tool creates a session in SQLite. Week 3: push reaches
phone. Week 6: user can respond. Week 9: the full loop works.

---

## Decision Record

| Decision | Date | Rationale | Reversible? |
|---|---|---|---|
| No plugin system | 2026-07-26 | Premature; channels are compiled-in | Yes, extract from channel registry when needed |
| No E2EE in v1 | 2026-07-26 | Local-first trust model sufficient for v1 | Yes, additive layer |
| Text-first only | 2026-07-26 | Voice/video are channel options, not primitives | Yes, add channel implementation |
| SQLite not Postgres | 2026-07-26 | Single user, zero config | Yes, documented migration path |
| No iOS in v1 | 2026-07-26 | Android-only due to existing codebase | Yes, rebuild from Android patterns |
| Single-user daemon | 2026-07-26 | v1 is personal; multi-user is v2+ | Yes, data model already supports it |
| Node.js not Rust | 2026-07-26 | Match existing codebase, faster iteration | Yes, can be rewritten if perf is insufficient |
