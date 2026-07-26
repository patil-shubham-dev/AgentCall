# Documentation Migration Report

> **Date:** 2026-07-26
> **Scope:** Full repository Markdown audit against canonical documents
> **Canonical Sources:** `PRODUCT_VISION.md`, `SYSTEM_ARCHITECTURE.md`, `API_SPEC.md`

---

## 1. Files Reviewed

### Canonical Documents (New Source of Truth)
| File | Status |
|------|--------|
| `PRODUCT_VISION.md` | ✅ Canonical — Product vision, philosophy, identity |
| `SYSTEM_ARCHITECTURE.md` | ✅ Canonical — Architecture specification |
| `API_SPEC.md` | ✅ Canonical — API contract |

### Pre-existing Documents (Needing Migration)
| # | File | Type | Urgency |
|---|------|------|---------|
| 1 | `README.md` | Root readme | **CRITICAL** |
| 2 | `PRD.md` | Product requirements | HIGH |
| 3 | `PROJECT_OVERVIEW.md` | Project overview | **CRITICAL** |
| 4 | `ARCHITECTURE.md` | Old architecture | **CRITICAL** |
| 5 | `AI_INTEGRATION.md` | AI integration guide | **CRITICAL** |
| 6 | `CHATGPT_INTEGRATION.md` | ChatGPT integration | HIGH |
| 7 | `MULTI_PROVIDER_PLAN.md` | Multi-provider plan | HIGH |
| 8 | `FREE_ARCHITECTURE.md` | Zero-cost guide | MEDIUM |
| 9 | `CURRENT_STACK.md` | Technology stack | MEDIUM |
| 10 | `INFRASTRUCTURE.md` | Infrastructure doc | MEDIUM |
| 11 | `ROADMAP.md` | Development roadmap | HIGH |
| 12 | `docs/01-architecture-design.md` | Old architecture design | HIGH |
| 13 | `docs/02-api-protocol-specification.md` | Old API spec | HIGH |
| 14 | `docs/03-database-schema.md` | Database schema | LOW |
| 15 | `docs/04-security-architecture.md` | Security architecture | LOW |
| 16 | `docs/05-mobile-app-technical-spec.md` | Mobile spec | MEDIUM |
| 17 | `docs/06-ui-ux-wireframes.md` | UI/UX wireframes | MEDIUM |
| 18 | `docs/07-mvp-scope-milestone-plan.md` | MVP plan | HIGH |
| 19 | `docs/08-testing-qa-strategy.md` | Testing strategy | LOW |
| 20 | `docs/09-infrastructure-cicd-plan.md` | Infra/CI-CD plan | MEDIUM |
| 21 | `docs/10-privacy-compliance.md` | Privacy document | LOW |
| 22 | `android-verification-report.md` | Android test report | LOW |

---

## 2. Canonical Document Summary

### PRODUCT_VISION.md (808 lines)
- **Brand:** AgentCall (not VoiceBridge)
- **Identity:** Communication platform, NOT an AI assistant/chatbot/MCP client
- **Core principle:** AI owns intelligence, AgentCall owns communication
- **Data ownership:** Never rewrite prompts, perform reasoning, enrich AI output, or generate summaries
- **Provider model:** AI-provider agnostic, every provider isolated with own history/sessions/callbacks
- **Communication APIs:** MCP > OpenAPI > REST > Function Calling > Actions > Webhooks > SSE > WebSocket
- **Must-have engines:** Voice, Notifications, Presence, Routing, Session, History
- **Auth:** Per-provider OAuth, independent connections
- **Devices:** Multi-device support (Android, iOS, Desktop, Web, Wearables)
- **Free-first philosophy**

### SYSTEM_ARCHITECTURE.md (242 lines)
- **11 Runtime Services:** Authentication, Provider Registry, Session Manager, Call Manager, Presence Engine, Notification Engine, Callback Engine, Device Router, History Service, Communication Gateway, Event Bus
- **Domain Model:** User → Device → Provider → Session → Call → Transcript → Message
- **Event-driven architecture** with events like CallCreated, CallAccepted, CallEnded, PresenceChanged
- **Backend structure:** auth/, calls/, providers/, presence/, notifications/, history/, devices/, gateway/, common/, database/
- **Data ownership rules:** May store/forward/authenticate/deliver; must never rewrite prompts/perform reasoning/enrich AI output/generate summaries

### API_SPEC.md (315 lines)
- **Base URL:** `https://api.agentcall.dev/api/v1`
- **Auth:** `Authorization: Bearer <JWT>` or `X-Provider-Key: <provider_api_key>`
- **Core Resources:** Users, Devices, Providers, Sessions, Calls, Transcripts, Notifications, Presence
- **8 REST endpoints groups:** Health, Providers, Calls, Presence, Notifications, Devices
- **8 MCP Tools:** create_call, send_message, get_transcript, complete_call, cancel_call, query_presence, resume_task, notify_completion
- **WebSocket events:** Client→Server (6), Server→Client (6)
- **SSE stream:** 4 event types

---

## 3. Outdated Documents

### `README.md` — CRITICAL REWRITE REQUIRED
- **Title:** Says "VoiceBridge — AI-to-Human Voice Calling" instead of "AgentCall"
- **Tagline:** "Zero paid APIs. Zero cloud services. Zero databases." — conflicts with vision of being a production platform
- **Architecture diagram:** Shows VoiceBridge engine with emotion enrichment, barge-in, filler words — violates PRODUCT_VISION's "never enrich AI output"
- **Refers to** `@agentcall/voicebridge` package — uses "VoiceBridge" branding
- **MCP Tools listed:** Only 5 tools, but API_SPEC.md requires 8
- **Features listed:** Emotion tags, breathing pauses, filler words, barge-in — all violate vision
- **No mention of:** Provider isolation, presence, notifications, callbacks, multi-device
- **Quick start:** Missing auth setup (JWT/OAuth)

### `PROJECT_OVERVIEW.md` — CRITICAL REWRITE REQUIRED
- **Title:** "AgentCall MCP / VoiceBridge" — inconsistent branding
- **Describes** AgentCall as a "voice bridge" — vision says it's a communication platform
- **Feature table:** Lists emotion-enriched messages, callback scheduling, barge-in detection — violates PRODUCT_VISION
- **Missing:** Provider isolation, presence, notification engine, multi-device
- **User flow:** Old step-by-step with text enrichment
- **Vision statement:** "Any AI, any phone, zero cost" — should reference PRODUCT_VISION instead

### `ARCHITECTURE.md` — CRITICAL REWRITE REQUIRED
- **System diagram:** Shows old architecture with VoiceBridge engine, text enrichment, barge-in detection
- **Communication flows:** Describe text enrichment pipeline (emotion, fillers, breaths)
- **Call state machine:** PENDING → ACTIVE → COMPLETED/CANCELLED/PAUSED — conflicts with SYSTEM_ARCHITECTURE.md's Created → Pending → Ringing → Answered → Active → Completed/Cancelled/Missed
- **Storage:** "In-memory Maps only" — SYSTEM_ARCHITECTURE.md describes PostgreSQL + Redis
- **Missing:** Provider Registry, Presence Engine, Notification Engine, Callback Engine, Device Router, Event Bus

### `AI_INTEGRATION.md` — CRITICAL REWRITE REQUIRED
- **Section "How AI Text Enrichment Works":** Violates PRODUCT_VISION. AgentCall must never enrich AI output.
- **Emotion tag table:** Pitch/speed/breathing controls — violates vision
- **MCP Tools:** Only 5 listed, missing query_presence, resume_task, notify_completion
- **Architecture diagrams:** Show old flow without provider abstraction layer
- **Missing:** Provider isolation, per-provider auth, event-driven responses

### `PRD.md` — HIGH REWRITE REQUIRED
- **Title:** "AgentCall MCP" — inconsistent, should be "AgentCall"
- **Subtitle:** "AI-to-Human Calling Infrastructure for Autonomous AI Agents" — use PRODUCT_VISION language
- **MCP requirements:** Mentions CreateCall, ResumeTask, NotifyCompletion, CancelCall, QueryPresence — but API_SPEC.md requires 8 tools including send_message and get_transcript
- **Architecture:** Old: "AI Agent → MCP Server → Backend → Push Service → Mobile App → WebRTC Voice Session → STT → LLM → Structured Response"
- **Components:** Lists old microservices architecture without Provider Registry, Device Router, Callback Engine, Event Bus
- **Security:** Mentions OAuth but API_SPEC.md uses JWT + Provider API Key

### `docs/01-architecture-design.md` — HIGH REWRITE REQUIRED
- **Title:** "Architecture Design Document (ADD) for AgentCall MCP"
- **Design philosophy:** "Internet-first", "MCP-native", "Privacy-by-default", "Self-hosted" — needs update to match PRODUCT_VISION
- **Architecture diagram:** Shows PostgreSQL, Redis, coturn, FCM/APNs as core components — not in SYSTEM_ARCHITECTURE.md
- **MCP tools listed:** create_call, resume_task, cancel_call, query_presence, notify_completion — missing send_message, get_transcript, complete_call
- **Component breakdown:** Auth Service, Presence Service, Call Manager, Notification Service, Signaling Server, WebRTC Relayer, Analytics Service — doesn't match SYSTEM_ARCHITECTURE.md's 11 services
- **Data flow:** Describes push notifications and WebRTC — vision says WebRTC is one of many transports
- **Service communication matrix:** References PostgreSQL, Redis, FCM/APNs — discussed but not in canonical architecture

### `docs/02-api-protocol-specification.md` — HIGH REWRITE REQUIRED
- **MCP tools:** Defines 5 tools (create_call, resume_task, cancel_call, query_presence, notify_completion) — but API_SPEC.md defines 8 including send_message, get_transcript, complete_call
- **Backend REST API:** Different endpoint structure from API_SPEC.md
- **WebRTC Signaling Protocol:** Detailed signaling flow — not part of API_SPEC.md (separate concern)
- **Push notification payloads:** FCM/APNs specifics — not in API_SPEC.md
- **Needs deprecation notice:** Most content is implementation-specific, not API contract

### `docs/07-mvp-scope-milestone-plan.md` — HIGH REWRITE REQUIRED
- **MVP scope:** Includes iOS app, WebRTC calling, coturn STUN/TURN, OAuth login, Device pairing — these are future phases per PRODUCT_VISION
- **Milestones:** Focused on old infrastructure (Hetzner VPS, PostgreSQL, Redis)
- **Resources:** Assumes separate backend/iOS/Android engineers — doesn't match solo-dev reality
- **Infrastructure costs:** Hetzner CX21 ($12/mo) — doesn't match current Suga deployment
- **Risk register:** Focused on old stack risks

---

## 4. Conflicting Sections

### Philosophy Conflict — AI Enrichment
| Document | States | Conflicts With |
|----------|--------|----------------|
| `AI_INTEGRATION.md` (L18-38) | Backend enriches AI text with emotion, fillers, breaths | PRODUCT_VISION: "AgentCall must never perform reasoning or enrich AI output" |
| `ARCHITECTURE.md` (L104-111) | "Backend enriches text (emotion, fillers, breaths)" | PRODUCT_VISION: "AgentCall owns communication, AI owns intelligence" |
| `README.md` (L28-32) | Shows VoiceBridge engine with emotion enrichment | SYSTEM_ARCHITECTURE.md: No such service exists |
| `PROJECT_OVERVIEW.md` (L86) | Lists emotion-enriched messages as complete feature | PRODUCT_VISION: Must be removed |
| `MULTI_PROVIDER_PLAN.md` (L48) | References "Text enrichment (emotions, breathing, filler words)" | PRODUCT_VISION: Never enrich AI output |

### Architecture Conflict — Service Model
| Document | States | Conflicts With |
|----------|--------|----------------|
| `ARCHITECTURE.md` (L5-99) | VoiceBridge engine + Signaling Server + REST Routes | SYSTEM_ARCHITECTURE.md: 11 runtime services with Event Bus |
| `docs/01-architecture-design.md` (L24-74) | PostgreSQL, Redis, coturn as core components | SYSTEM_ARCHITECTURE.md: Event-driven runtime, no specific infra |
| `docs/07-mvp-scope-milestone-plan.md` (L44-55) | 7 milestones focused on old microservices | SYSTEM_ARCHITECTURE.md: Different service decomposition |

### MCP Tool Conflict — 5 vs 8 Tools
| Document | Tools Listed | Canonical |
|----------|-------------|-----------|
| `README.md` (L162-168) | create_call, send_message, get_transcript, complete_call, cancel_call (5) | API_SPEC.md requires 8 |
| `AI_INTEGRATION.md` (L42-89) | Same 5 tools | Missing query_presence, resume_task, notify_completion |
| `ARCHITECTURE.md` (L22-23) | Same 5 tools | Missing 3 |
| `PROJECT_OVERVIEW.md` (L84) | Same 5 tools | Missing 3 |
| `docs/02-api-protocol-specification.md` (L86-91) | create_call, resume_task, cancel_call, query_presence, notify_completion (different 5) | Missing send_message, get_transcript, complete_call |

### Call State Machine Conflict
| Document | States | Canonical |
|----------|--------|-----------|
| `ARCHITECTURE.md` (L131-153) | PENDING → ACTIVE → COMPLETED/CANCELLED/PAUSED → PENDING | SYSTEM_ARCHITECTURE.md: Created → Pending → Ringing → Answered → Active → Completed/Cancelled/Missed |

### Auth Model Conflict
| Document | States | Canonical |
|----------|--------|-----------|
| `ARCHITECTURE.md` (L176-177) | "Auth: None (dev-service-token)" | API_SPEC.md: JWT or Provider API Key |
| `PROJECT_OVERVIEW.md` (L97-98) | "Authentication: ❌ Missing" | API_SPEC.md defines auth model |
| `README.md` | No auth setup in quick start | API_SPEC.md requires JWT |

---

## 5. Deprecated Concepts

| Concept | Found In | Reason for Deprecation |
|---------|----------|------------------------|
| **VoiceBridge** name | README, PROJECT_OVERVIEW, package.json | Replaced by AgentCall as primary brand |
| **Emotion enrichment engine** | AI_INTEGRATION.md, ARCHITECTURE.md, README.md, MULTI_PROVIDER_PLAN.md | Violates "never enrich AI output" |
| **Barge-in detection (server-side)** | ARCHITECTURE.md, AI_INTEGRATION.md | AI should handle interruptions; AgentCall only transports |
| **Filler words / breathing pauses** | README.md, AI_INTEGRATION.md | Part of AI's responsibility, not AgentCall |
| **In-memory Maps as primary storage** | ARCHITECTURE.md, CURRENT_STACK.md | SYSTEM_ARCHITECTURE.md specifies PostgreSQL + Redis |
| **Single-user (solo-user)** | ARCHITECTURE.md, PROJECT_OVERVIEW.md | PRODUCT_VISION requires multi-user with auth |
| **MCP-only interface** | README.md (implied) | PRODUCT_VISION requires MCP + REST + OpenAPI + Actions + Webhooks + SSE + WebSocket |
| **WebRTC as primary transport** | docs/01-architecture-design.md, docs/07-mvp-scope-milestone-plan.md | PRODUCT_VISION: Voice is one of many channels |
| **Coturn STUN/TURN as MVP requirement** | docs/07-mvp-scope-milestone-plan.md | Not in PRODUCT_VISION or SYSTEM_ARCHITECTURE.md |
| **Push notifications as MVP requirement** | docs/07-mvp-scope-milestone-plan.md | SYSTEM_ARCHITECTURE.md: Notifications are an engine, not an MVP gate |
| **OAuth-only auth** | docs/04-security-architecture.md | API_SPEC.md: JWT + Provider API Key |
| **5 MCP tools** | README.md, ARCHITECTURE.md, AI_INTEGRATION.md, PROJECT_OVERVIEW.md | API_SPEC.md requires 8 tools |
| **Separate signaling server** | ARCHITECTURE.md, docs/01-architecture-design.md | SYSTEM_ARCHITECTURE.md: Signaling is part of Communication Gateway |
| **WebRTC SFU/Relayer** | docs/01-architecture-design.md | Not in SYSTEM_ARCHITECTURE.md |

---

## 6. Required Updates Summary

### CRITICAL (Must Update — Directly Violate Canonical Documents)

| # | File | Key Changes |
|---|------|-------------|
| 1 | `README.md` | Rewrite: remove VoiceBridge branding, remove emotion/barge-in/fillers, align architecture with SYSTEM_ARCHITECTURE.md, list 8 MCP tools, add auth setup |
| 2 | `PROJECT_OVERVIEW.md` | Rewrite: remove emotion features, update MCP tools to 8, align with PRODUCT_VISION philosophy |
| 3 | `ARCHITECTURE.md` | Rewrite: replace old diagram with SYSTEM_ARCHITECTURE.md runtime services, update call state machine, add Event Bus, Provider Registry |
| 4 | `AI_INTEGRATION.md` | Rewrite: remove text enrichment sections, update MCP tools to 8, align with PRODUCT_VISION data ownership rules |

### HIGH (Must Update — Major Conflicts)

| # | File | Key Changes |
|---|------|-------------|
| 5 | `PRD.md` | Rewrite: update MCP tool list to 8, align architecture with PRODUCT_VISION, remove old component list |
| 6 | `MULTI_PROVIDER_PLAN.md` | Update: remove emotion enrichment references, align adapter with API_SPEC.md, secure |7 | `ROADMAP.md` | Update: restructure phases around SYSTEM_ARCHITECTURE.md services, add provider isolation phase |
| 8 | `CHATGPT_INTEGRATION.md` | Update: align endpoints with API_SPEC.md, add missing MCP tools |
| 9 | `docs/01-architecture-design.md` | Deprecate: mark as historical, add pointer to SYSTEM_ARCHITECTURE.md |
| 10 | `docs/02-api-protocol-specification.md` | Deprecate: mark as historical, add pointer to API_SPEC.md |
| 11 | `docs/07-mvp-scope-milestone-plan.md` | Deprecate: mark as historical, note new MVP definition |

### MEDIUM (Should Update — Consistency)

| # | File | Key Changes |
|---|------|-------------|
| 12 | `CURRENT_STACK.md` | Update: rename package references, add planned items from SYSTEM_ARCHITECTURE.md |
| 13 | `INFRASTRUCTURE.md` | Update: link to SYSTEM_ARCHITECTURE.md for future infra |
| 14 | `FREE_ARCHITECTURE.md` | Update: remove references to emotion enrichment |
| 15 | `docs/05-mobile-app-technical-spec.md` | Update: align auth flow with API_SPEC.md |
| 16 | `docs/06-ui-ux-wireframes.md` | Update: align with new auth model (JWT + API Key) |
| 17 | `docs/09-infrastructure-cicd-plan.md` | Update: align with SYSTEM_ARCHITECTURE.md services |

### LOW (No Changes Needed or Minimal)

| # | File | Reason |
|---|------|--------|
| 18 | `docs/03-database-schema.md` | Still valid design reference; SYSTEM_ARCHITECTURE.md doesn't contradict |
| 19 | `docs/04-security-architecture.md` | Still valid; complements API_SPEC.md auth section |
| 20 | `docs/08-testing-qa-strategy.md` | Still valid; no architectural conflict |
| 21 | `docs/10-privacy-compliance.md` | Still valid; no conflict with canonical docs |
| 22 | `android-verification-report.md` | Technical test report; no philosophical conflict |
| 23 | `AGENTS.md` | OpenCode configuration; skip |

---

## 7. Missing Cross-References

The canonical documents reference each other inconsistently:

| Should Link | From | To |
|-------------|------|-----|
| ✅ | `README.md` | `PRODUCT_VISION.md` |
| ✅ | `PRODUCT_VISION.md` | `SYSTEM_ARCHITECTURE.md` and `API_SPEC.md` |
| ✅ | `SYSTEM_ARCHITECTURE.md` | `API_SPEC.md` |
| ❌ | `API_SPEC.md` | `SYSTEM_ARCHITECTURE.md` and `PRODUCT_VISION.md` |
| ❌ | `PRD.md` | `PRODUCT_VISION.md` |
| ❌ | `CURRENT_STACK.md` | `SYSTEM_ARCHITECTURE.md` |
| ❌ | `INFRASTRUCTURE.md` | `SYSTEM_ARCHITECTURE.md` |
| ❌ | `FREE_ARCHITECTURE.md` | `PRODUCT_VISION.md` |
| ❌ | `ROADMAP.md` | `SYSTEM_ARCHITECTURE.md` |
| ❌ | All `docs/*.md` files | `SYSTEM_ARCHITECTURE.md` or `API_SPEC.md` |

---

## 8. Documentation Health Score

| Category | Score | Notes |
|----------|-------|-------|
| **Brand Consistency** | 3/10 | VoiceBridge/AgentCall split across 70% of files |
| **Architecture Alignment** | 2/10 | Only SYSTEM_ARCHITECTURE.md and API_SPEC.md match |
| **API Consistency** | 3/10 | 3 different MCP tool sets across documents |
| **Philosophy Alignment** | 2/10 | Most docs violate "never enrich AI output" |
| **Cross-Referencing** | 1/10 | Almost no links between documents |
| **Deprecation Clarity** | 1/10 | No deprecated markers on old content |
| **Up-to-Date** | 3/10 | Most docs describe pre-vision-rewrite state |
| **Terminology** | 4/10 | Mixed VoiceBridge/AgentCall, old service names |
| **Implementation Accuracy** | 3/10 | Docs describe features that don't exist yet |
| **Overall Health** | **2.5/10** | Major migration required across all categories |

---

## 9. Migration Order

```
Phase 1 — Canonical Core (already in place)
  PRODUCT_VISION.md ✅
  SYSTEM_ARCHITECTURE.md ✅  
  API_SPEC.md ✅

Phase 2 — Critical Fixes (this migration)
  README.md                  ← Rewrite
  PROJECT_OVERVIEW.md        ← Rewrite
  ARCHITECTURE.md            ← Rewrite
  AI_INTEGRATION.md          ← Rewrite

Phase 3 — High Priority  
  PRD.md                     ← Rewrite
  MULTI_PROVIDER_PLAN.md     ← Major update
  ROADMAP.md                 ← Restructure
  CHATGPT_INTEGRATION.md     ← Update
  docs/01-architecture-design.md  ← Deprecate + link
  docs/02-api-protocol-specification.md  ← Deprecate + link
  docs/07-mvp-scope-milestone-plan.md  ← Deprecate + link

Phase 4 — Medium Priority
  CURRENT_STACK.md           ← Update
  INFRASTRUCTURE.md          ← Update
  FREE_ARCHITECTURE.md       ← Update
  docs/05-mobile-app-technical-spec.md  ← Update
  docs/06-ui-ux-wireframes.md           ← Update
  docs/09-infrastructure-cicd-plan.md   ← Mark deprecated

Phase 5 — Final Validation
  Cross-reference check
  Terminology audit
  Consistency verification
```

---

*Generated as part of the mandatory Documentation Migration & Synchronization process.*
