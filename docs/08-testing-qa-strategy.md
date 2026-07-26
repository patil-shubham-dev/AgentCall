# Testing & QA Strategy

> **HISTORICAL DESIGN DOCUMENT**
>
> This document describes the original design process.
> The implementation may differ.
> Refer to [ARCHITECTURE_BASELINE.md](../ARCHITECTURE_BASELINE.md) for the current architecture.
>
> **Canonical references:** [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) | [API_SPEC.md](../API_SPEC.md) | [PRODUCT_VISION.md](../PRODUCT_VISION.md)

## AgentCall MCP

**Version:** 1.0
**Status:** Draft

---

## 1. Testing Philosophy

- **Quality is a feature:** Call reliability is the product. Every dropped call or poor-quality connection is a failure of the platform.
- **Test at the user level:** Unit tests cover logic; integration and E2E tests cover real call flows.
- **WebRTC is hard to test:** Use real browsers/devices for media tests; mock WebRTC in unit tests.

---

## 2. Testing Pyramid

```
        ┌──────────┐
        │   E2E    │  ← Manual + automated call flow tests
        │  (5-10%) │
       ┌┴──────────┴┐
       │ Integration│  ← Service interaction, signaling, push
       │  (20-30%)  │
      ┌┴────────────┴┐
      │   Unit Tests  │  ← Business logic, validation, state machines
      │   (60-70%)    │
      └───────────────┘
```

---

## 3. Unit Testing

### 3.1 Backend (Node.js/TypeScript)

| Layer | Framework | Coverage Target | Key Tests |
|-------|-----------|-----------------|-----------|
| MCP Server | Vitest | 90%+ | Tool input validation, error responses, context serialization |
| Auth Service | Vitest | 90%+ | Token generation, refresh, revocation, rate limiter logic |
| Call Manager | Vitest | 95%+ | State machine transitions, timeout logic, edge cases |
| Presence Service | Vitest | 90%+ | TTL management, status transitions, concurrent updates |
| Notification Service | Vitest | 85%+ | Template rendering, retry logic, device token management |

**Mocking strategy:**
- Mock Redis: `ioredis-mock` for unit tests, real Redis for integration tests
- Mock PostgreSQL: Use transactions + rollback (or SQLite for fast unit tests via `better-sqlite3`)
- Mock FCM/APNs: HTTP interceptor (nock or msw)

### 3.2 Mobile (Android)

| Layer | Framework | Key Tests |
|-------|-----------|-----------|
| ViewModels | JUnit + Turbine | State flows, user actions |
| Repositories | JUnit + MockK | API calls, caching, error handling |
| WebRTC Wrapper | JUnit + MockK | State management, audio routing (WebRTC peer mocked) |

### 3.3 Mobile (iOS)

| Layer | Framework | Key Tests |
|-------|-----------|-----------|
| ViewModels | XCTest + Combine | State flows, user actions |
| Services | XCTest + OHHTTPStubs | API calls, push handling |
| CallKit Delegate | XCTest | Call actions, audio session |

---

## 4. Integration Testing

### 4.1 Service Integration Tests

| Test Scenario | Components | Method |
|---------------|------------|--------|
| Auth → Token → API call | Auth Service + Backend API | HTTP client test suite |
| Create call → Push notification | Call Manager + Notification + FCM/APNs | Docker Compose with test fixtures |
| Signaling handshake | Signaling Server + WebSocket client | WSS test client |
| Presence lifecycle | Presence Service + Redis + TTL | Real Redis in Docker |
| Call timeout flow | Call Manager + Timer | Sleep/wait in test |

**Setup:** Docker Compose with all services + test PostgreSQL + test Redis.
**Teardown:** Drop test DB, flush Redis.

### 4.2 WebRTC Integration Tests

```
Goal: Verify WebRTC connection establishment under network conditions

Setup:
- Two Node.js WebRTC clients (using wrtc or puppeteer with Chrome)
- Connect through Signaling Server
- Use coturn for relay

Test cases:
1. Direct P2P connection (same network)
2. TURN relay connection (simulated NAT via TC/netem)
3. ICE restart on disconnect
4. Audio level detection (sine wave → verify > -30dB level)
5. DTLS handshake timeout

Metrics collected:
- Connection establishment time
- ICE candidate pair selected (host/srflx/relay)
- DTLS handshake success rate
```

---

## 5. End-to-End Testing

### 5.1 Automated E2E

```
Scenario: AI Agent calls user → user answers → conversation happens → context returned

Components involved:
1. MCP Server (test client simulating AI agent)
2. Backend services (Docker Compose)
3. Signaling Server
4. Push Notification mock (capture push, trigger mobile app)
5. Mobile app (Android emulator / iOS simulator)
6. coturn

Flow:
1. Test script invokes create_call via MCP (stdio)
2. MCP Server POSTs to backend API
3. Push notification fires → captured by mock
4. Mock triggers mobile app response (auto-answer via ADB/XCTest)
5. WebRTC connection establishes between test audio source and mobile app
6. 5-second audio tone sent
7. Call ends
8. Test script calls resume_task
9. Verify result contains expected context

Success criteria:
- Call status transitions: requested → ringing → connected → ended
- Context returned matches what was sent
- Audio metrics: <200ms RTT, <1% packet loss
```

### 5.2 Manual E2E Checklist

| Scenario | Pass Criteria |
|----------|---------------|
| Clean install → login → pair device | User sees home screen |
| Agent calls → push arrives → answer | Audio flows both directions |
| Agent calls → push arrives → decline | Agent receives "rejected" status |
| Agent calls → user no answer → timeout | Agent receives "timed_out" status |
| Call in progress → network drops → reconnects | Call resumes within 5s |
| Call in progress → app backgrounded → return | Call continues, UI updates |
| App killed → incoming call | Push wakes device, call works |
| Mute during call | Remote hears silence, indicator shows |
| Speakerphone toggle | Audio routes correctly |
| Multiple calls from different agents | Sequential calls work |
| Concurrent calls (same user) | Second call rejected/busy |
| Slow network (throttled to 256kbps) | Call connects, audio may degrade |
| High latency (300ms RTT on network) | Call connects, latency acceptable |

---

## 6. WebRTC Call Quality Testing

### 6.1 Metrics & Targets

| Metric | Target | Warning | Critical |
|--------|--------|---------|----------|
| Call setup time | <2s | 2-4s | >4s |
| Audio RTT | <150ms | 150-300ms | >300ms |
| Jitter | <30ms | 30-50ms | >50ms |
| Packet loss | <0.5% | 0.5-2% | >2% |
| Audio bitrate | >32kbps | 16-32kbps | <16kbps |
| MOS (Mean Opinion Score) | >4.0 | 3.5-4.0 | <3.5 |
| ICE connectivity success | >99% | 95-99% | <95% |

### 6.2 Test Environments

| Environment | Setup | Frequency |
|-------------|-------|-----------|
| CI/CD | Docker containers with wrtc | Every PR |
| Staging | Hetzner VPS + Android emulator / iOS simulator | Daily |
| Real device lab | Pixel 9 + iPhone 16 + various network conditions | Weekly |
| Field test | Real users on 4G/5G/WiFi at various locations | Monthly |

### 6.3 Network Condition Simulation

```bash
# Linux tc commands for simulation
# High latency (satellite link)
tc qdisc add dev eth0 root netem delay 300ms 50ms distribution normal

# Packet loss (congested network)
tc qdisc add dev eth0 root netem loss 2% 25%

# Jitter (unstable connection)
tc qdisc add dev eth0 root netem delay 100ms 40ms 25% loss 0.5%

# Throttled bandwidth (low-end 4G)
tc qdisc add dev eth0 root tbf rate 1mbit burst 32kbit latency 400ms
```

---

## 7. Load Testing

### 7.1 Targets

- **Normal load:** 10 concurrent active calls
- **Peak load:** 50 concurrent active calls
- **Burst:** 20 calls created within 1 second
- **Sustained:** 100 calls/hour for 8 hours

### 7.2 Testing Approach

```typescript
// Using k6 or custom script with wrtc
// Simulate N concurrent WebRTC calls

for (let i = 0; i < concurrentCalls; i++) {
    const agent = new MCPTestClient();
    const callee = new WebRTCTestClient();

    // Agent creates call
    const { callId } = await agent.createCall(userId);
    // Callee receives push (mocked) and answers
    await callee.connect(callId);
    // Send audio for 30 seconds
    await callee.sendAudio(sineWave, 30000);
    // End call
    await agent.cancelCall(callId);
}

// Measure:
// - Call setup time P50, P95, P99
// - Signaling server connections
// - Redis operations/second
// - PostgreSQL query latency
// - Memory/CPU on each service
```

---

## 8. Security Testing

| Test | Method | Frequency |
|------|--------|-----------|
| JWT signature verification | Automated test with forged tokens | CI |
| Rate limiter effectiveness | Burst request test | CI |
| SQL injection | Automated fuzzing of all endpoints | CI |
| WebSocket message injection | Malformed message test | CI |
| TURN credential reuse | Expired credential test | CI |
| Dependency scanning | `npm audit`, Trivy, Snyk | CI, Weekly |
| Secrets scanning | git-secrets, truffleHog | Pre-commit, CI |

---

## 9. CI/CD Integration

### 9.1 GitHub Actions Workflow

```yaml
name: Test
on: [push, pull_request]

jobs:
  unit:
    runs-on: ubuntu-latest
    steps:
      - run: npm ci
      - run: npm test -- --coverage
      - run: npm run lint

  integration:
    runs-on: ubuntu-latest
    services:
      postgres: ...
      redis: ...
      coturn: ...
    steps:
      - run: npm ci
      - run: npm run test:integration

  mobile-android:
    runs-on: ubuntu-latest
    steps:
      - run: ./gradlew test

  mobile-ios:
    runs-on: macos-latest
    steps:
      - run: xcodebuild test -scheme App
```

### 9.2 Quality Gates

| Gate | Threshold | Action on Failure |
|------|-----------|-------------------|
| Unit test coverage | >80% | Warning, no block |
| Integration tests | 100% pass | Block merge |
| Lint | No errors | Block merge |
| Security scan | No critical/high | Block merge |
| Build | Successful | Block merge |
