# AgentCall — Presence Engine

> Presence is derived state, not a stored value. Computing it from signals
> is more accurate than asking the user to maintain it.

---

## 1. Philosophy

Presence answers one question:

> **What is the user doing right now, and when will they be available?**

The answer is computed from four signal sources:

1. **Device signals** — heartbeat, screen on/off, app foreground/background
2. **Calendar signals** — current event, next event, free/busy (optional, local)
3. **Time signals** — time of day, day of week, configured quiet hours
4. **Manual signals** — explicit user override ("set to DND for 1 hour")

**No single signal is authoritative.** The engine cross-references all available
signals and returns the most likely status + confidence level.

---

## 2. Presence States

```typescript
type PresenceStatus =
  | 'available'      // Actively using a device, no conflicts
  | 'idle'           // Device active but no interaction in N minutes
  | 'busy'           // Calendar event or active screen focus
  | 'away'           // No device interaction in N+ minutes
  | 'sleeping'       // Within quiet hours
  | 'dnd'            // Explicit Do Not Disturb
  | 'focus'          // Explicit Focus Mode
  | 'offline'        // No device connected
  | 'unknown';       // Insufficient data
```

### State Descriptions

| State | Meaning | Typical Transition |
|---|---|---|
| `available` | Ready to receive | Device heartbeat < 5min, no calendar conflict |
| `idle` | Near device but not active | No interaction 5–15min |
| `busy` | Occupied | Calendar event or Focus Mode |
| `away` | Not near devices | No heartbeat > 15min |
| `sleeping` | Should not be disturbed | Current time in quiet hours |
| `dnd` | Explicit Do Not Disturb | User set DND manually |
| `focus` | Deep work mode | User set Focus Mode |
| `offline` | No connectivity | No device contact > 30min |

---

## 3. State Machine

Presence does NOT have a stored state machine. It is computed on every query.
But the computation follows deterministic rules.

```
On every presence query:
  │
  ├── Has user set manual override?
  │     YES → return manual status (with "since" time)
  │
  ├── Is emergency override active?
  │     YES → return 'available' (override overrides everything)
  │
  ├── Any device active in last 5 min?
  │     NO → check 5-15 min:
  │       YES → 'idle'
  │       NO → check 15-30 min:
  │         YES → 'away'
  │         NO → 'offline'
  │
  ├── Is current time in quiet hours?
  │     YES → 'sleeping'
  │
  ├── Is there a calendar event now?
  │     YES → 'busy'
  │
  ├── Is Focus Mode active?
  │     YES → 'focus'
  │
  ├── Is DND active?
  │     YES → 'dnd'
  │
  └── None of the above → 'available'
```

Rules are evaluated in priority order (manual override beats everything).
Only the FIRST matching rule fires.

---

## 4. Signal Sources

### 4.1 Device Heartbeat

Every device sends a heartbeat every 60 seconds while the app is foregrounded,
and every 5 minutes while backgrounded (via periodic background work).

```json
POST /api/devices/{device_id}/heartbeat
{
  "foreground": true,
  "screen_on": true,
  "battery_level": 85,
  "timestamp": "2026-07-26T10:00:00.000Z"
}
```

Daemon stores `last_heartbeat_at` per device. Computes `max(last_heartbeat_at
across all devices)` for presence calculation.

### 4.2 Calendar Signals (Optional)

Daemon reads a local calendar file (`.ics` or CalDAV) if configured.

```bash
AGENTCALL_CALENDAR_PATH=~/.calendar/events.ics
```

On each presence query:
1. Parse calendar file
2. Check if current time falls within any event
3. If yes, return `busy` with event title and end time

No cloud calendar integration. Local file only.

### 4.3 Time Signals

Always available. Based on system timezone.

```typescript
function isQuietHours(): boolean {
  const now = new Date();
  const currentMin = now.getHours() * 60 + now.getMinutes();
  const quietStart = parseTime(config.quietHoursStart); // 23:00 → 1380
  const quietEnd = parseTime(config.quietHoursEnd);     // 07:00 → 420

  if (quietStart < quietEnd) {
    // Same day: 08:00–22:00
    return currentMin >= quietStart && currentMin < quietEnd;
  } else {
    // Crosses midnight: 22:00–08:00
    return currentMin >= quietStart || currentMin < quietEnd;
  }
}
```

### 4.4 Manual Override

User can set presence manually from the app:

```
/settings/presence

Status: [Available] [Busy] [Away] [DND] [Focus Mode]
Duration: [30 min] [1 hour] [2 hours] [Until I change] [Custom...]
```

Manual override is stored in SQLite:

```
manual_presence:
  status: 'dnd'
  set_at: '2026-07-26T10:00:00.000Z'
  expires_at: '2026-07-26T11:00:00.000Z'  // null = until changed
```

When `expires_at` passes, the manual override row is deleted and presence
returns to automatic computation.

---

## 5. Presence Response

```json
{
  "status": "busy",
  "since": "2026-07-26T09:30:00.000Z",
  "expires_at": "2026-07-26T10:30:00.000Z",
  "explanation": "In a meeting: 'Sprint Planning' until 10:30",
  "confidence": 0.85,
  "is_manual": false,
  "next_available_at": "2026-07-26T10:30:00.000Z"
}
```

| Field | Type | Description |
|---|---|---|
| `status` | string | The computed presence status |
| `since` | ISO 8601 | When this status became active |
| `expires_at` | ISO 8601 | When status is expected to change (from calendar or manual override) |
| `explanation` | string | Human-readable reason for the status |
| `confidence` | 0.0–1.0 | How confident the engine is in this inference |
| `is_manual` | boolean | Whether this was manually set by the user |
| `next_available_at` | ISO 8601 | Best guess for next availability |

---

## 6. AI Visibility of Presence

Agents can read presence via the MCP resource:

```
agentcall://presence → { status: "busy", next_available_at: "10:30" }
```

**Privacy rules:**
- `trust_level=1` agents see: `available` or `unavailable` (binary only)
- `trust_level=2` agents see: full status string + `next_available_at`
- `trust_level=3` agents see: full status + explanation + calendar event title
- `trust_level=0` agents see: nothing (blocked)

This allows agents to make intelligent scheduling decisions without exposing
private calendar details to untrusted agents.

---

## 7. Presence and Routing Integration

When the router calls the policy engine AND presence resolver, it combines
their answers:

```typescript
function route(session: Session): RoutingDecision {
  const policy = policyEngine.check(session);
  if (!policy.allowed) return { action: 'block', reason: policy.reason };

  const presence = presenceResolver.resolve();

  if (presence.status === 'available') {
    return { action: 'deliver_now', channel: 'push' };
  }

  if (presence.status === 'busy' || presence.status === 'focus') {
    if (policy.canInterrupt) {
      return { action: 'deliver_now', channel: 'push', suppress_sound: true };
    }
    return {
      action: 'queue',
      estimated_delay: presence.expires_at
        ? new Date(presence.expires_at).getTime() - Date.now()
        : undefined
    };
  }

  if (presence.status === 'dnd' || presence.status === 'sleeping') {
    if (policy.canInterruptDnd) {
      return { action: 'deliver_now', channel: 'push', suppress_sound: true };
    }
    return { action: 'queue', estimated_delay: nextMorning() };
  }

  if (presence.status === 'offline') {
    return {
      action: 'queue',
      estimated_delay: undefined, // unknown
      notify_agent: true // tell the agent the user is offline
    };
  }

  // Fallback
  return { action: 'deliver_now', channel: 'push' };
}
```
