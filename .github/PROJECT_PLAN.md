# AgentCall — GitHub Project Plan

> **Status:** Planning Phase
> **Source:** [IMPLEMENTATION_ROADMAP.md](../docs/IMPLEMENTATION_ROADMAP.md)
>
> This file maps the implementation roadmap into GitHub Issues-ready epics, stories, tasks, and subtasks. Each Epic corresponds to one Roadmap Phase.

---

## Epic 1: Core Runtime

**Phase 1** | **6-8 weeks** | **Priority: Critical**

### Story 1.1: Authentication Service

**Tasks:**

1.1.1 Generate RS256 key pair for JWT signing
- `openssl genrsa -out private.pem 2048`
- Derive public key
- Document key rotation procedure

1.1.2 Implement JWT issuance endpoint
- POST /api/v1/auth/login
- POST /api/v1/auth/register
- Input validation with Zod
- Return access token + refresh token

1.1.3 Implement JWT validation middleware
- Extract and verify Bearer token
- Validate claims (sub, exp, role)
- Attach user context to request

1.1.4 Implement JWT refresh flow
- POST /api/v1/auth/refresh
- Rotate refresh token
- SHA-256 hash storage
- Revoke old token on rotation

1.1.5 Implement token revocation
- Revoke endpoint
- Blacklist via Redis until natural expiry
- Handle logout

1.1.6 Provider API key auth
- X-Provider-Key header validation
- Key registration endpoint for providers
- SHA-256 hash storage
- Key rotation support

### Story 1.2: Provider Registry

**Tasks:**

1.2.1 Define Provider data model
- id, name, type, apiKeyHash, allowedTools, state

1.2.2 Implement Provider Registry service
- registerProvider(params)
- getProvider(id)
- listProviders()
- revokeProvider(id)

1.2.3 Implement provider isolation
- Separate session namespace per provider
- Separate history scope per provider
- Provider-scoped event topics

1.2.4 Registry API endpoints
- POST /api/v1/providers
- GET /api/v1/providers
- GET /api/v1/providers/:id
- DELETE /api/v1/providers/:id

### Story 1.3: Presence Engine

**Tasks:**

1.3.1 Define presence states
- online, offline, busy, dnd, in_call

1.3.2 Implement Presence Engine service
- setPresence(userId, deviceId, state)
- getPresence(userId)
- observePresence(userId, callback)
- Presence change events

1.3.3 Implement presence queries
- Bulk query: batch of userIds
- Filter by state
- Timeout-based auto-offline detection

1.3.4 Presence API endpoints
- GET /api/v1/users/:id/presence
- PATCH /api/v1/users/:id/presence
- WebSocket presence subscription

### Story 1.4: Notification Engine

**Tasks:**

1.4.1 Define notification types
- call.incoming, call.missed, callback.reminder, presence.change

1.4.2 Implement in-app notification dispatch
- Notification data model
- Queue-based dispatch
- Read/unread tracking

1.4.3 Push notification scaffolding
- FCM integration (Android)
- APNs placeholder (iOS future)
- Rate-limited delivery

### Story 1.5: Callback Engine

**Tasks:**

1.5.1 Define callback data model
- id, userId, providerId, scheduledAt, interval, maxRetries, payload, status

1.5.2 Implement Callback Engine service
- scheduleCallback(params)
- cancelCallback(id)
- processDueCallbacks()
- Retry with exponential backoff

1.5.3 Implement callback delivery
- Webhook delivery to registered URLs
- In-app notification fallback
- Dead letter after max retries

### Story 1.6: Complete MCP API

**Tasks:**

1.6.1 Implement query_presence tool
- Input: user_id
- Output: user presence state

1.6.2 Implement resume_task tool
- Input: task_context
- Output: resume confirmation

1.6.3 Implement notify_completion tool
- Input: task_id, result
- Output: delivery confirmation

1.6.4 Update existing 5 MCP tools
- Update signatures per API_SPEC.md
- Add Event Bus hooks

1.6.5 MCP tool testing
- All 8 tools via MCP Inspector
- Input validation tests
- Error response tests

### Story 1.7: Event Bus

**Tasks:**

1.7.1 Define Event Bus interface
- publish(topic, event)
- subscribe(topic, handler)
- unsubscribe(topic, handler)

1.7.2 Implement in-process Event Bus
- Topic-based pub/sub
- Async handler execution
- Error handling per handler
- Dead letter after max retries

1.7.3 Instrument services with Event Bus events
- Auth events: user.login, user.logout
- Call events: call.created, call.state_changed
- Presence events: presence.changed
- Callback events: callback.scheduled, callback.completed

1.7.4 Event Bus testing
- Unit tests for publish/subscribe
- Integration tests for multi-service flows

### Story 1.8: Multi-user Support

**Tasks:**

1.8.1 Replace solo-user with user model
- User registration/login
- User profile endpoints
- User isolation for all services

1.8.2 User API endpoints
- POST /api/v1/users
- GET /api/v1/users/:id
- PATCH /api/v1/users/:id

1.8.3 Backward compatibility
- Migration path for solo-user state
- Deprecation notices in API responses
- Grace period before removal

---

## Epic 2: Infrastructure

**Phase 2** | **4-6 weeks** | **Priority: High**

### Story 2.1: PostgreSQL Integration

**Tasks:**

2.1.1 Install and configure Knex.js
- Configure knexfile
- Set up connection pool
- Environment-based config

2.1.2 Design and create initial schema
- users table
- providers table
- devices table
- call_sessions table
- notifications table
- callbacks table
- api_keys table
- history table

2.1.3 Implement Repository layer
- IUserRepository interface + PostgresUserRepository
- IProviderRepository interface + PostgresProviderRepository
- Same pattern for all entities

2.1.4 Migrate in-memory state to PostgreSQL
- Migrate existing data
- Repository pattern rollout
- Test data integrity

2.1.5 Write migration tests
- Knex migration up/down tests
- Repository unit tests with test containers
- Data integrity verification

### Story 2.2: Redis Integration

**Tasks:**

2.2.1 Install and configure Redis client
- ioredis client
- Connection pool
- Sentinel support placeholder

2.2.2 Migrate presence to Redis
- TTL-based presence expiry
- Redis-backed presence queries
- Presence pub/sub

2.2.3 Implement rate limiting with Redis
- Sliding window rate limiter
- Per-endpoint limits
- Burst allowance

2.2.4 Implement Redis pub/sub for Event Bus
- Cross-instance event delivery
- Event serialization (JSON)
- Connection resilience

2.2.5 Write Redis tests
- Presence TTL correctness
- Rate limit accuracy
- Pub/sub delivery

### Story 2.3: Device Router

**Tasks:**

2.3.1 Define Device data model
- id, userId, type (android/ios/web), pushToken, name, lastSeen

2.3.2 Implement Device Registry
- registerDevice(userId, deviceInfo)
- getDevice(id)
- listUserDevices(userId)
- unregisterDevice(id)

2.3.3 Implement Device Routing
- Route notification to correct device
- Route events based on device capabilities
- Handle device offline gracefully

### Story 2.4: History Service

**Tasks:**

2.4.1 Define Call History data model
- id, userId, providerId, callId, startedAt, endedAt, duration, status

2.4.2 Implement History Service
- recordCall(callData)
- getUserHistory(userId, filters, pagination)
- getProviderHistory(providerId, filters, pagination)

2.4.3 History API endpoints
- GET /api/v1/history
- GET /api/v1/history/:id
- Pagination, filtering, sorting

### Story 2.5: Session Manager

**Tasks:**

2.5.1 Define Session data model
- id, userId, providerId, createdAt, expiresAt, context

2.5.2 Implement Session Manager
- createSession(userId, providerId)
- extendSession(sessionId, ttl)
- endSession(sessionId)
- getSession(sessionId)

2.5.3 Session lifecycle management
- Auto-expiry with TTL
- Context persistence
- Session migration support

### Story 2.6: Production Deployment

**Tasks:**

2.6.1 Docker Compose: PostgreSQL service
- Dockerfile or official image
- Volume for data persistence
- Health check

2.6.2 Docker Compose: Redis service
- Official image
- Persistence config
- Health check

2.6.3 Docker Compose: Backend + MCP
- Multi-service compose file
- Environment variable injection
- Dependency ordering

2.6.4 Docker Compose: Caddy reverse proxy
- Caddyfile with auto TLS
- Domain configuration
- Rate limiting at proxy level

2.6.5 Deployment documentation
- Setup instructions
- Environment variables reference
- Health check verification

---

## Epic 3: Communication Gateway

**Phase 3** | **4-6 weeks** | **Priority: High**

### Story 3.1: Communication Gateway

**Tasks:**

3.1.1 Design Gateway architecture
- Transport abstraction layer
- Connection manager per transport type
- Unified event format

3.1.2 Implement WebSocket transport
- Connection lifecycle (open, close, error)
- Message framing
- Heartbeat (15s interval)
- Reconnection support

3.1.3 Implement SSE transport
- Event stream endpoint
- Connection lifecycle
- Last-Event-Id replay

3.1.4 Implement WebRTC signaling transport
- Offer/answer relay
- ICE candidate relay
- Connection state management

3.1.5 Gateway event routing
- Event → correct transport → correct device
- Transport-agnostic event dispatch
- Delivery confirmation

### Story 3.2: Push Notifications

**Tasks:**

3.2.1 FCM integration
- FCM HTTP v1 API
- Token registration per device
- Message construction per notification type
- Rate-limited delivery

3.2.2 APNs scaffolding
- APNs HTTP/2 connection
- Certificate-based auth
- Placeholder for iOS future

3.2.3 Notification priority and grouping
- Urgent: call.incoming (immediate)
- Normal: callback.reminder (summary)
- Grouped notifications for multiple events

### Story 3.3: Connection Quality

**Tasks:**

3.3.1 Define quality metrics
- RTT, jitter, packet loss, signal strength

3.3.2 Implement metrics collection
- Client-reported metrics
- Gateway-measured metrics
- Periodic reporting

3.3.3 Quality degradation handling
- Warning thresholds
- Adaptive quality reduction
- Reconnection triggers

---

## Epic 4: Multi-Provider

**Phase 4** | **6-8 weeks** | **Priority: Medium**

### Story 4.1: Provider Adapter Interface

**Tasks:**

4.1.1 Define adapter interface
- IProviderAdapter: connect, disconnect, sendMessage, handleResponse
- Error types: ProviderDisconnectedError, ProviderTimeoutError

4.1.2 Adapter lifecycle
- Registration → Connection → Active → Disconnection
- Health check endpoint per adapter
- Automatic reconnection

4.1.3 Adapter testing framework
- Mock provider adapter
- Adapter conformance tests
- Integration test suite

### Story 4.2: MCP Adapter

**Tasks:**

4.2.1 Build MCP Adapter
- MCP client implementation (internal)
- Tool discovery from provider
- Message relay between MCP tools and adapter bridge

4.2.2 MCP Adapter integration tests
- Tool discovery flow
- Message send/receive
- Error handling

### Story 4.3: OpenAI Adapter

**Tasks:**

4.3.1 Build OpenAI function calling adapter
- OpenAI API client
- Function definition generation from MCP tools
- Response handling and routing

4.3.2 OpenAI auth integration
- API key validation
- Usage tracking placeholder

### Story 4.4: ChatGPT Adapter

**Tasks:**

4.4.1 Build ChatGPT Actions adapter
- OpenAPI spec generation for ChatGPT
- OAuth flow for ChatGPT
- Action registration

### Story 4.5: Webhook Registration

**Tasks:**

4.5.1 Webhook registration
- POST /api/v1/webhooks
- GET /api/v1/webhooks
- DELETE /api/v1/webhooks/:id

4.5.2 Webhook delivery
- Outbound HTTP call on event
- Retry with exponential backoff
- Delivery logging

---

## Epic 5: Mobile Evolution

**Phase 5** | **8-10 weeks** | **Priority: Medium**

### Story 5.1: Android Auth Flow

**Tasks:**

5.1.1 Login/Register screens
- Material 3 UI
- Form validation
- Loading/error states

5.1.2 Token management
- Encrypted token storage
- Auto-refresh on 401
- Biometric unlock option

### Story 5.2: Android Presence

**Tasks:**

5.2.1 Presence UI indicators
- Online/offline/busy indicators
- Status selector
- Real-time updates via SSE

### Story 5.3: Android Notifications

**Tasks:**

5.3.1 Notification channels
- Call channel (high priority)
- Reminder channel (normal priority)

5.3.2 Push handling
- FCM token registration
- Notification tap → deep link
- Call incoming notification → call screen

### Story 5.4: Android Callbacks

**Tasks:**

5.4.1 Callback scheduling UI
- Date/time picker
- Repeat interval selector
- Callback list with status

### Story 5.5: iOS App

**Tasks:**

5.5.1 iOS app scaffold
- SwiftUI project
- Core networking layer
- WebSocket client

5.5.2 iOS basic calling flow
- Login
- WebSocket connection
- Call initiation

---

## Epic 6: Platform API

**Phase 6** | **4-6 weeks** | **Priority: Low**

### Story 6.1: OpenAPI Specification

**Tasks:**

6.1.1 Generate OpenAPI 3.1 spec
- Route introspection
- Schema generation
- Tag-based grouping

### Story 6.2: Webhook System

**Tasks:**

6.2.1 Webhook delivery system
- Event → webhook URL mapping
- HMAC signature for payload verification
- Delivery logs and retry

### Story 6.3: API Versioning

**Tasks:**

6.3.1 Version middleware
- Accept-Version header or URL prefix
- Version routing
- Deprecation headers

---

## Epic 7: Production Hardening

**Phase 7** | **4-6 weeks** | **Priority: Low**

### Story 7.1: Security Audit

**Tasks:**

7.1.1 Penetration testing
- Auth bypass testing
- Injection testing (SQL, XSS)
- Rate limit bypass testing

7.1.2 Vulnerability scan
- Dependency audit
- Container image scan
- TLS configuration review

### Story 7.2: Load Testing

**Tasks:**

7.2.1 Load test scripts
- k6 script for 10 concurrent calls
- k6 script for 50 concurrent calls
- Burst test scenario

### Story 7.3: Monitoring

**Tasks:**

7.3.1 Prometheus metrics
- Request duration histogram
- Error rate counter
- Active connections gauge
- Event bus latency histogram

7.3.2 Grafana dashboards
- System health overview
- Call performance
- Error tracking

### Story 7.4: Disaster Recovery

**Tasks:**

7.4.1 Backup procedures
- Daily PostgreSQL backup script
- WAL archiving
- Backup verification

7.4.2 Restore procedures
- Point-in-time recovery
- Full restore
- DR drill schedule
