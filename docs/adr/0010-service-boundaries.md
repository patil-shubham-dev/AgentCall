# ADR-0010: Service Boundaries

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

The SYSTEM_ARCHITECTURE.md defines 11 runtime services, but their exact responsibilities, data ownership, and interaction patterns need precise definition to prevent overlap, circular dependencies, and ambiguous ownership.

## Decision

Define strict service boundaries:

### 1. Authentication Service

- **Owns**: User identity, credentials, JWT tokens, refresh tokens, API keys
- **Exposes**: `authenticate(token)`, `issueToken(user)`, `refreshToken(refresh)`, `revokeToken(jti)`
- **Events publishes**: `UserAuthenticated`, `TokenRevoked`
- **Events subscribes**: None
- **Storage**: `users`, `oauth_accounts`, `auth_refresh_tokens`, `token_blacklist`, `api_keys`

### 2. Provider Registry

- **Owns**: Provider identity, provider-state isolation, provider capabilities
- **Exposes**: `register(provider)`, `getProvider(id)`, `listProviders(userId)`, `revokeProvider(id)`
- **Events publishes**: `ProviderConnected`, `ProviderDisconnected`
- **Events subscribes**: `UserAuthenticated` (register providers for new user)
- **Storage**: `providers`, `provider_sessions`

### 3. Session Manager

- **Owns**: Long-lived provider-user session state
- **Exposes**: `createSession(providerId, userId)`, `getSession(id)`, `endSession(id)`
- **Events publishes**: `SessionCreated`, `SessionEnded`
- **Events subscribes**: `ProviderConnected`, `ProviderDisconnected`
- **Storage**: `sessions`

### 4. Call Manager

- **Owns**: Call lifecycle, call state machine, call metadata
- **Exposes**: `createCall(sessionId, params)`, `acceptCall(callId)`, `endCall(callId)`, `getCall(callId)`
- **Events publishes**: `CallCreated`, `CallAccepted`, `CallEnded`, `CallCancelled`, `CallMissed`
- **Events subscribes**: `SessionCreated` (enable calls for session)
- **Storage**: `call_sessions`, `call_participants`, `call_quality_metrics`, `messages`

### 5. Presence Engine

- **Owns**: User online/offline/busy/DND/in-call state
- **Exposes**: `getPresence(userId)`, `setPresence(userId, status)`, `subscribeToPresence(userId)`
- **Events publishes**: `PresenceChanged`
- **Events subscribes**: `CallAccepted` (set busy), `CallEnded` (restore previous)
- **Storage**: Redis with TTL (60s, refreshed every 15s)

### 6. Notification Engine

- **Owns**: Notification creation, delivery, persistence, push dispatch
- **Exposes**: `sendNotification(userId, type, payload)`, `getNotifications(userId)`, `markRead(notifId)`
- **Events publishes**: `NotificationSent`, `NotificationDelivered`
- **Events subscribes**: `CallCreated`, `CallMissed`, `CallbackScheduled`, `CallbackReady`
- **Storage**: `notification_log`

### 7. Callback Engine

- **Owns**: Callback scheduling, retry logic, timer management
- **Exposes**: `scheduleCallback(callId, config)`, `cancelCallback(callbackId)`, `getCallbacks(userId)`
- **Events publishes**: `CallbackScheduled`, `CallbackReady`, `CallbackCancelled`, `CallbackExhausted`
- **Events subscribes**: `CallEnded` (trigger callback if missed), `CallCreated` (cancel pending callback)
- **Storage**: `callbacks`

### 8. Device Router

- **Owns**: Device registration, routing logic, device state
- **Exposes**: `registerDevice(userId, deviceInfo)`, `getDevices(userId)`, `routeEvent(userId, event)`
- **Events publishes**: `DeviceRegistered`, `DeviceDeregistered`, `DeviceStatusChanged`
- **Events subscribes**: `PresenceChanged` (update device state)
- **Storage**: `devices`

### 9. History Service

- **Owns**: Call history, transcript storage, queryable history
- **Exposes**: `getCallHistory(userId, filters)`, `getTranscript(callId)`, `deleteHistory(userId)`
- **Events publishes**: None (read-only subscriber)
- **Events subscribes**: `CallEnded` (archive call), `CallCreated` (init history record)
- **Storage**: `call_sessions` (read replica or same source)

### 10. Communication Gateway

- **Owns**: Transport layer — WebSocket, SSE, WebRTC connections
- **Exposes**: `sendToDevice(deviceId, message)`, `broadcastToUser(userId, message)`, `handleConnection(ws)`
- **Events publishes**: `DeviceConnected`, `DeviceDisconnected`, `CallAccepted` (on user accept)
- **Events subscribes**: All events that need delivery to devices
- **Storage**: In-memory connection pool + Redis for cross-instance

### 11. Event Bus

- **Owns**: Event routing, handler registration, delivery guarantees
- **Exposes**: `publish(event)`, `subscribe(type, handler)`
- **Storage**: In-memory (Redis for cross-instance in future)

## Alternatives Considered

- **Fewer, larger services**: Would create ambiguity about where logic belongs. Rejected.
- **More granular services**: Would create excessive event traffic. Rejected.
- **No service boundaries, single module**: Would violate single responsibility. Rejected.

## Consequences

**Positive:**
- Every piece of logic has exactly one home
- Clear ownership for on-call debugging
- New contributors understand the system quickly
- Services can be extracted to separate processes later

**Negative:**
- 11 services is many to implement initially
- Event wiring ceremony for simple operations
- Some operations span multiple services (e.g., a call involves 6+ services)

## Tradeoffs

- Many small services vs. fewer large services: small services are clearer but require more event wiring

## Future Work

- Service health checks and dependency graphing
- Automatic service registration on startup
- Cross-service tracing for debugging
- Service-level metrics (events processed, latency per service)
