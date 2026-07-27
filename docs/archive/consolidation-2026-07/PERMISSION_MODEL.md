# AgentCall — Permission Model

> Every AI agent is independently configurable. The user controls access.
> No AI can override user policy. Permission checks happen before delivery.

---

## 1. Philosophy

Permissions answer one question:

> **Should this message from this AI agent reach this human right now?**

The answer depends on:
- Who the agent is (identity)
- What the agent wants (capability, urgency)
- When the request arrives (time, calendar)
- What the user configured (explicit policy)
- What the user is doing (presence)

The model is designed to be **simple by default, powerful when needed**.

Default: all agents are `allowed`, `trust_level=2`, quiet hours 23:00–07:00.
This means any agent can reach you during the day, nobody wakes you up at night.

---

## 2. Per-Agent Policy

```json
{
  "agent_id": "agent_claude_abc123",
  "allowed": true,
  "trust_level": 2,
  "max_sessions_per_hour": 10,
  "allowed_capabilities": ["notify", "message", "decision", "approval", "confirmation", "callback"],
  "can_interrupt_dnd": false,
  "can_request_urgent": true,
  "quiet_hours": {
    "enabled": true,
    "start": "22:00",
    "end": "07:00"
  },
  "notification_sound": "default"
}
```

### Fields

| Field | Type | Default | Description |
|---|---|---|---|
| `allowed` | boolean | true | Master switch. If false, all requests are silently dropped. |
| `trust_level` | 0–3 | 2 | See trust levels below. |
| `max_sessions_per_hour` | number | 10 | Rate limit. 0 = unlimited. |
| `allowed_capabilities` | string[] | all | Which capabilities this agent can use. |
| `can_interrupt_dnd` | boolean | false | If true, messages arrive even during DND (but no sound). |
| `can_request_urgent` | boolean | true | If true, agent can set urgency=urgent to bypass DND (for trusted). |
| `quiet_hours` | object | global default | Per-agent quiet hours override. |
| `notification_sound` | string | "default" | Per-agent notification sound. |

### Trust Levels

| Level | Name | Meaning | Can bypass DND? | Can use urgent? |
|---|---|---|---|---|
| 0 | **Blocked** | All requests silently dropped | No | No |
| 1 | **Untrusted** | Requests delivered but never interrupt; user explicitly approves first contact | No | No |
| 2 | **Default** | Normal delivery, respects quiet hours and DND | No | No |
| 3 | **Trusted** | Can interrupt DND with urgent requests; higher rate limits | Yes | Yes |

**Trust level 0 vs `allowed=false`:**
- `allowed=false`: agent cannot register; API key is rejected at auth layer
- `trust_level=0`: agent can connect but all sessions are silently dropped
  (useful for agents you want to keep registered for history but suppress)

**Trust level escalation:**
- Trust level is user-controlled only (via AgentDetail screen)
- No AI agent can request a trust level change
- Trust level changes take effect immediately (no restart needed)

---

## 3. Global Policy

Global settings apply to all agents unless overridden per-agent.

```json
{
  "default_permission": "allow",
  "quiet_hours": {
    "enabled": true,
    "start": "23:00",
    "end": "07:00",
    "timezone": "America/New_York"
  },
  "global_rate_limit": 50,
  "emergency_override": {
    "enabled": true,
    "max_duration_minutes": 15,
    "cooldown_minutes": 60
  }
}
```

### Emergency Override

When enabled and activated by the user:
- All agents bypass quiet hours and DND for the override duration
- All agents can send urgent requests regardless of trust level
- Used for on-call scenarios, expected urgent communication, etc.

User activates from:
- Presence screen ("Emergency mode: 15 min")
- Quick settings tile
- Notification shortcut

---

## 4. Policy Evaluation Flow

```
Incoming request from agent A for capability C with urgency U
  │
  ├── 1. Is A allowed?
  │     NO → return { allowed: false, reason: "agent_blocked" }
  │
  ├── 2. Is C in A.allowed_capabilities?
  │     NO → return { allowed: false, reason: "capability_not_allowed" }
  │
  ├── 3. Is A rate limited?
  │     YES → return { allowed: false, reason: "rate_limited" }
  │
  ├── 4. Is emergency override active?
  │     YES → skip to step 7 (bypass all restrictions)
  │
  ├── 5. Is it quiet hours for A?
  │     YES → can A.interrupt_dnd?
  │       YES → continue (but no sound)
  │       NO → is U==urgent AND A.can_request_urgent AND A.trust_level>=3?
  │         YES → continue (bypass quiet hours for urgent)
  │         NO → is A.trust_level >= 3?
  │           YES → deliver but queue (wait until quiet hours end)
  │           NO → return { allowed: false, reason: "quiet_hours" }
  │
  ├── 6. Is user in DND?
  │     YES → can A.interrupt_dnd?
  │       YES → continue
  │       NO → is U==urgent AND A.can_request_urgent AND A.trust_level>=3?
  │         YES → continue
  │         NO → return { allowed: false, reason: "dnd_active" }
  │
  ├── 7. Is user in Focus Mode?
  │     YES → same as DND logic
  │
  └── 8. All checks passed → return { allowed: true }
```

---

## 5. User Consent Flow

For `trust_level=1` (untrusted) agents, the first communication request goes
through a consent flow:

```
Agent requests communication → policy check
  → trust_level==1 AND no prior consent?
    → Send push: "Claude wants to contact you. Allow?"
    → Notification actions: [Allow] [Block] [Allow once]
    → User choice:
        "Allow" → set trust_level=2, deliver this request
        "Allow once" → deliver this request, keep trust_level=1
        "Block" → set trust_level=0, drop this request
```

This protects the user from unexpected agents. Once an agent is trusted, it
bypasses consent.

---

## 6. Default Policy by Trust Level

| Trust Level | Notify | Message | Decision | Approval | Urgent | Interrupt DND |
|---|---|---|---|---|---|---|
| 3 Trusted | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 2 Default | ✅ | ✅ | ✅ | ✅ | ✅ | ❌ |
| 1 Untrusted | ✅ (with consent) | ❌ | ❌ | ❌ | ❌ | ❌ |
| 0 Blocked | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ |

---

## 7. Policy Storage

```sql
-- Per-agent overrides
CREATE TABLE agent_policies (
  agent_id TEXT PRIMARY KEY REFERENCES agents(id),
  allowed BOOLEAN DEFAULT TRUE,
  trust_level INTEGER DEFAULT 2,
  max_sessions_per_hour INTEGER DEFAULT 10,
  allowed_capabilities TEXT DEFAULT '["notify","message","decision","approval","confirmation","callback"]',
  can_interrupt_dnd BOOLEAN DEFAULT FALSE,
  can_request_urgent BOOLEAN DEFAULT TRUE,
  quiet_hours_start TEXT,
  quiet_hours_end TEXT,
  notification_sound TEXT DEFAULT 'default',
  updated_at INTEGER NOT NULL
);

-- Global settings (single row)
CREATE TABLE global_policy (
  id INTEGER PRIMARY KEY CHECK(id = 1),
  default_permission TEXT DEFAULT 'allow',
  quiet_hours_start TEXT DEFAULT '23:00',
  quiet_hours_end TEXT DEFAULT '07:00',
  quiet_hours_timezone TEXT DEFAULT 'UTC',
  global_rate_limit INTEGER DEFAULT 50,
  emergency_override_enabled BOOLEAN DEFAULT TRUE,
  emergency_override_active BOOLEAN DEFAULT FALSE,
  emergency_override_until INTEGER,
  updated_at INTEGER NOT NULL
);
```

---

## 8. Permission vs Presence Interaction

Permissions and presence are independent systems that interact at evaluation
time:

```
Policy Engine responds:
  "allowed: true, should_interrupt: false"

Presence Resolver responds:
  "status: dnd, explanation: 'In a meeting until 3pm'"

These are combined by the router:
  "Delivery permitted but queued until meeting ends"
  → Session status: "queued"
  → Notification: "Claude needs an answer — will notify when you're free"
```

The two systems are separate because:
- Policy is about **who** (agent identity)
- Presence is about **when** (user availability)
- They change on different timescales (policy: rare; presence: frequent)

---

## 9. API (Daemon HTTP Endpoints)

```
GET  /api/agents                     → list agents with policy
GET  /api/agents/:id                 → agent detail + policy
PUT  /api/agents/:id/policy          → update agent policy
GET  /api/policy/global              → get global policy
PUT  /api/policy/global              → update global policy
POST /api/policy/emergency           → toggle emergency override
  body: { "active": true, "duration_minutes": 15 }
GET  /api/agents/:id/consent         → get consent status
POST /api/agents/:id/consent         → grant consent (allow/block/once)
```
