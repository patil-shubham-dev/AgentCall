# MVP Scope & Milestone Plan

## AgentCall MCP

**Version:** 1.0
**Status:** Draft
**Timeline:** 10 weeks (moderate pace)

---

## 1. MVP Definition

### 1.1 What's In (Must Have)

| Feature | Priority | Rationale |
|---------|----------|-----------|
| MCP Server with `create_call`, `resume_task`, `cancel_call`, `query_presence`, `notify_completion` | P0 | Core value proposition |
| Android app (native) with WebRTC calling | P0 | Primary mobile platform |
| iOS app (native) with WebRTC calling + CallKit | P0 | Secondary mobile platform |
| Backend services: Auth, Presence, Call Manager, Signaling, Notification | P0 | Required for call flow |
| Push notifications (FCM + APNs) | P0 | Wake device for incoming call |
| Coturn STUN/TURN server | P0 | NAT traversal |
| OAuth login (Google + GitHub + Apple) | P0 | User onboarding |
| Device pairing (QR code) | P0 | Headless setup |
| Call context transfer (AI → User → AI) | P0 | Core value proposition |

### 1.2 What's Out (Post-MVP)

| Feature | Reason |
|---------|--------|
| Multi-party / group calling | Not needed for 1:1 AI-Human |
| Video calling | Audio-only for MVP |
| Web client with WebRTC | Mobile-first MVP |
| Desktop apps (Electron, etc.) | Future |
| Analytics dashboard | Nice-to-have |
| Admin panel | Internal tool, post-MVP |
| E2E encrypted transcripts | Privacy enhancement, post-MVP |
| Live captions / transcript on screen | Post-MVP feature |
| Smart DND (calendar integration) | Post-MVP |

---

## 2. Milestone Plan

### 2.1 Milestone M1: Foundation (Weeks 1-2)

**Goal:** Working infrastructure and core backend

| Deliverable | Details | Dependencies |
|-------------|---------|-------------|
| Docker Compose setup | PostgreSQL, Redis, coturn, Caddy | None |
| PostgreSQL schema + migrations | All tables per DB schema doc | Docker setup |
| Redis configuration | Presence, signaling state, rate limiting | Docker setup |
| Auth Service | OAuth flow (Google), JWT issuance, token refresh, device pairing API | DB schema |
| VPS provisioning | Hetzner CX21, Docker install, firewall, SSH hardening | None |

**Success Criteria:**
- `docker-compose up` starts all services
- Auth tokens can be issued and verified
- Device pairing QR code generates and exchanges correctly

---

### 2.2 Milestone M2: Call Infrastructure (Weeks 3-4)

**Goal:** WebRTC signaling and call lifecycle working

| Deliverable | Details | Dependencies |
|-------------|---------|-------------|
| Signaling Server | WebSocket server for WebRTC offer/answer/ICE exchange | Redis |
| Call Manager | Call state machine: requested → ringing → connected → ended | PostgreSQL, Redis |
| Presence Service | Online/offline/busy tracking via Redis | Redis |
| TURN credential service | HMAC-based time-limited TURN credentials | coturn setup, Auth Service |
| Rate limiting middleware | Redis-backed per-endpoint rate limiting | Redis |
| Internal backend API | All endpoints from API spec | All above |

**Success Criteria:**
- Signaling server handles WebSocket connections with JWT auth
- Call state machine advances correctly through all states
- TURN credentials are generated and verified by coturn
- Postman/curl test: create call → cancel call → query presence

---

### 2.3 Milestone M3: Push Notifications (Week 5)

**Goal:** Devices receive call notifications reliably

| Deliverable | Details | Dependencies |
|-------------|---------|-------------|
| Notification Service | Push dispatcher for FCM + APNs | PostgreSQL |
| FCM integration | Android push setup, Firebase project, service account | Notification Service |
| APNs integration | iOS VoIP push certificate, PushKit setup | Notification Service, Apple Developer account |
| Device registration API | `POST /api/v1/devices/register` | Auth Service |
| Notification retry logic | 3 retries with exponential backoff | Notification Service |

**Success Criteria:**
- Android device receives FCM data message when a call is initiated
- iOS device receives VoIP push via PushKit when a call is initiated
- Notification delivered within 2 seconds of call creation
- Retry works if first push delivery fails

---

### 2.4 Milestone M4: Android App (Weeks 6-7)

**Goal:** Android app can receive and handle calls end-to-end

| Deliverable | Details | Dependencies |
|-------------|---------|-------------|
| Android project setup | Gradle, dependencies, project structure | None |
| Auth flow | OAuth login, token storage, auto-refresh | Auth Service (M2) |
| WebRTC integration | PeerConnection factory, audio track, ICE handling | Signaling Server (M2) |
| Call UI | Incoming call screen, active call screen, end call | WebRTC integration |
| Foreground service | CallService for background calling | Android project |
| FCM handling | Data message processing, wake-up, call initiation | Push (M3) |
| Presence heartbeat | Periodic WebSocket heartbeat | Signaling Server |
| Home screen | Call history list | API backend |
| Settings screen | Profile, notifications, DND, privacy | API backend |

**Success Criteria:**
- User receives push → app shows incoming call → user answers → audio flows
- Audio quality: echo cancellation working, noise suppression working
- Call end → context returned to MCP server
- App works when backgrounded and killed (foreground service)

---

### 2.5 Milestone M5: iOS App (Weeks 7-8)

**Goal:** iOS app can receive and handle calls end-to-end

| Deliverable | Details | Dependencies |
|-------------|---------|-------------|
| iOS project setup | Xcode, dependencies, Swift package manager | None |
| PushKit integration | VoIP certificate, push registry, CallKit | Push (M3) |
| WebRTC integration | GoogleWebRTC, audio session, peer connection | Signaling Server (M2) |
| CallKit integration | CXProvider, CXCallController, native call UI | iOS project |
| Call UI | SwiftUI views for call states | iOS project |
| Auth flow | OAuth login, keychain storage, token refresh | Auth Service (M2) |
| Background audio | AVAudioSession configuration for VoIP | iOS project |
| Presence + home + settings | Same feature set as Android | API backend |

**Success Criteria:**
- iOS device receives VoIP push → CallKit shows native incoming call → user answers → audio flows
- CallKit integration seamless (works with lock screen, other calls, etc.)
- App works in background and killed state
- Same call quality as Android

---

### 2.6 Milestone M6: MCP Server & Integration (Weeks 8-9)

**Goal:** AI agents can call users through the system

| Deliverable | Details | Dependencies |
|-------------|---------|-------------|
| MCP Server (Node.js) | Tool definitions per API spec, stdio + SSE transport | Backend API (M2) |
| MCP tool: `create_call` | Initiate call, handle response | Call Manager (M2) |
| MCP tool: `resume_task` | Poll for call result, structured response | Call Manager (M2) |
| MCP tool: `cancel_call` | Cancel active call | Call Manager (M2) |
| MCP tool: `query_presence` | Check user availability | Presence Service (M2) |
| MCP tool: `notify_completion` | Send task-complete push | Notification Service (M3) |
| AI context serialization | Convert call conversation to structured context | Call Manager |
| Integration tests | OpenCode, Claude Code, Cursor compatibility | All MCP tools |

**Success Criteria:**
- `create_call` triggers push call to device
- `resume_task` returns structured result after call
- Works with official MCP Inspector
- Works with OpenCode (stdio + SSE)
- E2E test: AI → create_call → user answers → context returned

---

### 2.7 Milestone M7: Polish & Launch (Week 10)

**Goal:** Production-ready MVP launch

| Deliverable | Details | Dependencies |
|-------------|---------|-------------|
| Error handling pass | Edge cases, timeouts, reconnection, offline mode | All |
| Security audit | Internal review of auth, media, API security | All |
| Performance tuning | Call setup <2s, audio latency <250ms | All |
| Caddy reverse proxy | TLS termination, rate limiting, WAF | Docker setup |
| Monitoring setup | Prometheus + Grafana (optional) | Infrastructure |
| Documentation | README, setup guide, API docs, user guide | All |
| Load testing | Simulate 50 concurrent calls | All |
| MVP launch | Deploy to production Hetzner VPS | All |

**Success Criteria:**
- Call setup time <2s (push → ringing)
- Audio latency <250ms (RTT)
- 50 concurrent calls handled without degradation
- All 5 MCP tools functional and documented
- Deployment documented and reproducible

---

## 3. Resource Plan

### 3.1 Team Assumptions

| Role | Weeks | Notes |
|------|-------|-------|
| Backend Engineer | 10 weeks | Node.js/TS, PostgreSQL, Redis, WebRTC signaling |
| Android Engineer | 4 weeks | Weeks 6-7 (or full-time from week 5) |
| iOS Engineer | 3 weeks | Weeks 7-8 (or full-time from week 6) |
| DevOps | 2 weeks | Weeks 1-2 initial setup, week 10 polish |
| QA | 2 weeks | Weeks 9-10 |

### 3.2 Infrastructure Costs (Monthly)

| Resource | Cost (USD) | Notes |
|----------|------------|-------|
| Hetzner CX21 (2 vCPU, 4 GB) | ~$12 | Main app + DB |
| Hetzner Storage Box 100GB | ~$5 | Backups |
| Firebase (FCM) | Free | Up to certain limits |
| Apple Developer Program | $99/yr | Required for APNs/CallKit |
| Domain + DNS | ~$15/yr | agentcall.com or similar |
| **Total monthly** | **~$25** | |

---

## 4. Risk Register

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Apple VoIP push entitlement denied | Medium | High | Apply early (4 weeks before launch), prepare fallback with normal push + wake screen |
| WebRTC NAT traversal fails | Medium | High | Thorough testing across ISPs, always provide TURN fallback |
| Android background execution limits (API 26+) | Medium | Medium | Proper foreground service with required permissions, test on API 34+ |
| Push delivery delays | Low | Medium | WebSocket keepalive for foreground, implement retry logic |
| MCP protocol changes | Low | Medium | Pin MCP SDK version, abstract tool definitions |
| Hetzner VPS network performance for TURN | Low | Medium | Monitor relay usage, upgrade VPS if needed |
