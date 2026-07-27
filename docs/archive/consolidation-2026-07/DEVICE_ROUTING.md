# AgentCall — Device Routing

> Deliver each communication to the right device at the right time.
> One function, not an engine. Simple by default, powerful when needed.

---

## 1. Philosophy

Device routing answers one question:

> **Which of the user's devices should receive this message?**

The answer depends on:
- Device capabilities (can this device handle the capability?)
- Device priority (which device the user prefers)
- Device state (is this device active?)
- Session state (has another device already acknowledged this?)

This is a **sorted list with failover**, not a routing engine.

---

## 2. Device Model

```typescript
interface Device {
  id: string;
  userId: string;
  name: string;                    // "Pixel 8", "MacBook Pro"
  platform: 'android' | 'ios' | 'desktop' | 'browser' | 'watch';
  pushToken: string;               // FCM or APNs token
  capabilities: CommunicationCapability[];
  priority: number;                 // Lower = tried first (1=phone, 10=desktop)
  isActive: boolean;
  lastHeartbeatAt?: number;
  createdAt: number;
}
```

### Default Priority by Platform

| Platform | Priority | Rationale |
|---|---|---|
| Watch | 1 | Quickest to glance |
| Phone | 5 | Always with user |
| Tablet | 15 | May not be nearby |
| Desktop | 20 | App may not be foregrounded |
| Browser | 25 | Depends on active tab |

Users can override priority per device in the app.

---

## 3. Routing Algorithm

```typescript
function routeToDevices(
  session: Session,
  userDevices: Device[]
): RoutingPlan {

  // Step 1: Filter devices that can handle this capability
  const capableDevices = userDevices.filter(d =>
    d.isActive &&
    d.capabilities.includes(session.capability) &&
    d.pushToken
  );

  if (capableDevices.length === 0) {
    return { strategy: 'none', reason: 'no_capable_device' };
  }

  // Step 2: Exclude devices that have already acknowledged this session
  const pendingDevices = session.acknowledgedDevice
    ? capableDevices.filter(d => d.id !== session.acknowledgedDevice)
    : capableDevices;

  if (pendingDevices.length === 0) {
    return { strategy: 'none', reason: 'already_acknowledged' };
  }

  // Step 3: Sort by priority
  pendingDevices.sort((a, b) => a.priority - b.priority);

  // Step 4: Build delivery plan (tiered, not sequential)
  const [firstTier, ...restTiers] = pendingDevices;

  return {
    strategy: 'tiered',
    tiers: [
      {
        devices: [firstTier],
        waitForAck: true,
        timeoutSeconds: config.deliveryTimeout // default 30s
      },
      {
        devices: restTiers.slice(0, 2),        // next 2 devices
        waitForAck: false,                      // fire and forget
      },
      {
        devices: restTiers.slice(2),            // remaining devices
        waitForAck: false,
        delaySeconds: 60                        // wait 1min before blasting
      }
    ]
  };
}
```

### Tiered Delivery

```
Tier 1: Phone
  └── Wait 30s for acknowledgment
       ├── User opens → ack received → stop
       └── Timeout → Tier 2

Tier 2: Phone + Desktop + Tablet
  └── Send to all (no wait)
       ├── User opens on any → ack received → suppress others
       └── No ack in 60s → Tier 3

Tier 3: All devices (blast)
  └── Send to every registered device
       └── Whichever device user opens wins
```

### Acknowledgment Protocol

When a user opens a session on any device, the device sends:

```
POST /api/sessions/{session_id}/acknowledge
{ "device_id": "pixel_8" }
```

The daemon stores `acknowledgedDevice` on the session and emits a
`session_acknowledged` event. Other devices receive this event via their
WebSocket connection (if open) and suppress their notifications.

For devices that are not connected via WebSocket: the next heartbeat response
includes `acknowledged_session_ids` so the device can clean up stale
notifications.

---

## 4. Notification Suppression

When a session is acknowledged on one device, other devices should stop
showing notifications for that session.

```
Session created → push to Phone (Tier 1)
User opens on Desktop → Desktop sends ack
  → Daemon:
    1. Sets session.acknowledgedDevice = "desktop_mbp"
    2. Emits event: session_acknowledged { session_id, device_id: "desktop_mbp" }
    3. WebSocket-connected devices receive event:
       - Phone receives event → suppresses notification for this session
       - Tablet was not connected → next heartbeat includes supression list
    4. Push gateway sends "cancel" signal (FCM: notification collapse_key)
       to unsuppressed devices

Result: Phone notification disappears when Desktop opened.
        No duplicate notifications.
```

---

## 5. Failure Handling

| Scenario | Behavior |
|---|---|
| All devices fail | Session queued, retry in `retry_interval` (default 60s). After `max_attempts`, session status set to `failed`. |
| Push token invalid | Device marked `isActive=false`, next-capable device tried |
| App not installed | Push bounces → device marked inactive after 3 consecutive failures |
| Device offline | Push queued by FCM/APNs, delivered when device comes online |
| User has no devices | Router returns `strategy: none, reason: no_devices`. Session created but not delivered. |

---

## 6. Multi-Device Presence Integration

Device routing and presence interact:

```typescript
function getActiveDevices(): Device[] {
  const allDevices = deviceRegistry.getAll();
  const presenceThreshold = presenceResolver.status === 'sleeping'
    ? 30 * 60 * 1000     // 30 min (sleeping devices may not heartbeat)
    : 5 * 60 * 1000;      // 5 min for active

  return allDevices.filter(d =>
    d.isActive &&
    d.lastHeartbeatAt &&
    (Date.now() - d.lastHeartbeatAt) < presenceThreshold
  );
}
```

If user is sleeping, we're more lenient about device staleness (phone may be
on the nightstand). If user is available, we expect frequent heartbeats.

---

## 7. Future: Group Routing

When multiple users exist (v2+), routing extends to:

```typescript
function routeToGroup(groupId: string, session: Session): GroupRoutingPlan {
  const members = groupRegistry.getMembers(groupId);
  const memberDevices = members.flatMap(m => deviceRegistry.getUserDevices(m.id));

  // Try to find at least one available user
  const availableMembers = members.filter(m =>
    presenceResolver.resolve(m.id).status === 'available'
  );

  if (availableMembers.length > 0) {
    // Route to all available members
    return routeToDevices(session, getDevicesForUsers(availableMembers));
  }

  // Nobody available → send to everyone, someone will respond
  return routeToDevices(session, memberDevices);
}
```

This is future work. v1 is single-user.

---

## 8. Summary

| Aspect | Design |
|---|---|
| Algorithm | Tiered delivery with acknowledgment timeout |
| Ack protocol | HTTP POST per device, propagated via WebSocket events |
| Suppression | Collapse keys (FCM) + event-driven notification cancellation |
| Failover | Device→Device→All, then queue with retry |
| Device ordering | User-configurable priority per device |
| Capability filter | Devices only receive capabilities they support |
| State | Stateless routing function (session state stored in SQLite) |
| Complexity | One file, < 100 lines |
