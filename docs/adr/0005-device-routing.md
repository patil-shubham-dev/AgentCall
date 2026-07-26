# ADR-0005: Device Routing

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

AgentCall must support multiple devices per user (Android, future iOS, web, desktop) and route communication events (calls, notifications, presence updates) to the correct device(s). The current system supports only one WebSocket connection per user (solo-user, single device).

## Decision

Implement a Device Router service that:

1. **Registers devices** with unique ID, platform, capabilities, and push tokens
2. **Maintains device state** (online/offline, last seen, connection status)
3. **Routes events** to the correct device(s) based on event type and user preferences
4. **Fans out events** to all active devices when appropriate

### Device Model

```typescript
interface Device {
  id: string;          // UUID
  userId: string;
  platform: 'android' | 'ios' | 'web' | 'desktop';
  name: string;        // User-facing name
  pushToken?: string;  // FCM or APNs token
  capabilities: DeviceCapability[];
  isActive: boolean;
  lastSeenAt: string;  // ISO 8601
  createdAt: string;
}
```

### Routing Rules

| Event Type | Routing Behavior |
|------------|-----------------|
| Incoming call | Route to all active devices simultaneously |
| Notification | Route to all devices (push if offline) |
| Presence update | Acknowledge on receiving device |
| Call audio | Route to the device that accepted the call |
| System message | Route to all devices |

## Alternatives Considered

- **Single device only**: Simple but limits future multi-device support.
- **Device as primary entity**: Events addressed to devices, not users. Rejected because AI providers address users, not devices.
- **No routing (broadcast to all)**: Works for calls but breaks for device-specific operations.

## Consequences

**Positive:**
- Multi-device support without architectural change
- Clean separation of device concerns from business logic
- Easy to add new platform types (web, desktop, wearables)

**Negative:**
- Additional database table and service to maintain
- Push token management complexity (expiry, refresh)
- Fan-out can increase load for users with many devices

## Tradeoffs

- Device-first vs. user-first routing: user-first aligns with how AI providers think (they call a user, not a device)

## Future Work

- Device priority (which device rings first)
- Device groups (work phone, personal phone)
- Device-specific capabilities advertisement
- WebRTC device selection (which device handles media)
