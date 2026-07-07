# Architecture Design Document (ADD)

## AgentCall MCP

**Version:** 1.0
**Status:** Draft
**Last Updated:** 2026-07-07

---

## 1. Overview

This document describes the system architecture for AgentCall MCP — an open, internet-first communication platform enabling autonomous AI agents to initiate secure voice calls to users via WebRTC.

### Design Philosophy

- **Internet-first:** Avoid PSTN/SIP telephony. Use WebRTC over public internet for near-zero marginal cost.
- **MCP-native:** Expose every capability as MCP tools for universal AI agent integration.
- **Privacy-by-default:** No recording, no transcript retention, user-controlled storage.
- **Self-hosted:** All services deployable via Docker Compose on Hetzner VPS.

---

## 2. High-Level Architecture

```
┌─────────────────────────────────────────────────────────┐
│                    AI Agent Ecosystem                    │
│  (OpenCode / Claude Code / Cursor / Windsurf / Continue) │
└────────────────────┬────────────────────────────────────┘
                     │ MCP Protocol (stdio/SSE)
                     ▼
┌─────────────────────────────────────────────────────────┐
│                 MCP Server (Node.js)                     │
│  - Tool definitions                                     │
│  - Session management                                   │
│  - Context serialization                                │
└────────────────────┬────────────────────────────────────┘
                     │ HTTP/WS (Internal API)
                     ▼
┌─────────────────────────────────────────────────────────┐
│                 Backend Services (Node.js)               │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌────────┐ │
│  │  Auth    │  │ Presence │  │  Call    │  │ Notif. │ │
│  │ Service  │  │ Service  │  │ Manager  │  │ Service│ │
│  └──────────┘  └──────────┘  └──────────┘  └────────┘ │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐             │
│  │Signaling │  │ WebRTC   │  │ Analytics│             │
│  │  Server  │  │  Relayer │  │          │             │
│  └──────────┘  └──────────┘  └──────────┘             │
└────────┬──────────┬─────────────┬──────────────────────┘
         │          │             │
         ▼          ▼             ▼
┌──────────┐ ┌──────────┐ ┌──────────────┐
│PostgreSQL│ │  Redis   │ │   coturn     │
│ (Primary)│ │ (Cache/  │ │ (STUN/TURN)  │
│          │ │  PubSub) │ │              │
└──────────┘ └──────────┘ └──────────────┘
         │
         ▼
┌─────────────────────────────────────────────────────────┐
│                 Push Services                           │
│  FCM (Android)  |  APNs (iOS)                          │
└────────────────────┬────────────────────────────────────┘
                     │
                     ▼
┌─────────────────────────────────────────────────────────┐
│              Mobile Clients                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐ │
│  │   Android    │  │     iOS      │  │  Web Client  │ │
│  │  (Kotlin)   │  │   (Swift)    │  │  (React/TS)  │ │
│  └──────────────┘  └──────────────┘  └──────────────┘ │
└─────────────────────────────────────────────────────────┘
```

---

## 3. Component Breakdown

### 3.1 MCP Server

**Purpose:** Expose calling capabilities as MCP tools for any MCP-compatible AI agent.

**Tool Definitions:**

| Tool | Description | Input | Output |
|------|-------------|-------|--------|
| `create_call` | Initiate a voice call to a user | `user_id`, `context`, `priority` | `call_id`, `status` |
| `resume_task` | Resume AI task with user's response | `call_id` | `response_context` |
| `cancel_call` | Cancel an active call | `call_id` | `status` |
| `query_presence` | Check if user is available for calls | `user_id` | `status`, `device_info` |
| `notify_completion` | Notify user of completed task | `user_id`, `summary` | `status` |

**Transport:** Supports both `stdio` (for local agents) and `SSE` (for remote/network agents).

**Implementation:** Single Node.js process. Communicates with Backend via internal HTTP REST API.

---

### 3.2 Backend Services

A monorepo of Node.js/TypeScript microservices running as separate containers.

#### 3.2.1 Authentication Service

- OAuth 2.0 / JWT-based auth
- Device pairing flow (QR code pairing)
- Token issuance, refresh, revocation
- Rate limiting per user/IP

#### 3.2.2 Presence Service

- Real-time user presence tracking (online/offline/away/busy)
- Backed by Redis with TTL-based expiry
- WebSocket connections for live presence updates
- Grace period (30s) before marking offline

#### 3.2.3 Call Manager

- State machine for call lifecycle
- Handles: requested → ringing → connected → ended
- Integrates with Signaling Server for WebRTC negotiation
- Persists call metadata to PostgreSQL
- Timeout handling (no answer → auto-cancel after 30s)

#### 3.2.4 Notification Service

- Manages push notification delivery (FCM + APNs)
- Device token registration and refresh
- Notification templates for different call states
- Fallback to polling if push fails

#### 3.2.5 Signaling Server (WebSocket)

- WebSocket-based signaling for WebRTC offer/answer/ICE exchange
- Room-based session management
- Handles reconnection during call
- Scales horizontally via Redis PubSub

#### 3.2.6 WebRTC Relayer

- Selective Forwarding Unit (SFU) for multi-party (future)
- For MVP: direct peer-to-peer with STUN/TURN
- Connection quality monitoring (RTT, packet loss, jitter)

#### 3.2.7 Analytics Service

- Call quality metrics ingestion
- Usage statistics (calls per user, duration, success rate)
- Prometheus metrics + Grafana dashboards

---

### 3.3 Data Stores

#### PostgreSQL (Primary Database)

- Users, devices, call records, auth tokens
- Schema managed via migrations (e.g., Knex.js or Prisma)
- Connection pool managed via `pg` or Prisma

#### Redis (Cache & Real-time)

- Session cache (JWT blacklist, rate limits)
- Presence state (TTL: 60s, refresh every 15s)
- WebRTC signaling state (transient offers/answers)
- PubSub for inter-service communication
- Queue for push notification dispatch

---

### 3.4 STUN/TURN (coturn)

**Purpose:** Enable WebRTC peer-to-peer connectivity behind NATs/firewalls.

- Self-hosted coturn on same Hetzner VPS or separate instance
- STUN port: 3478 (UDP/TCP)
- TURN ports: 49152-65535 (UDP)
- Authentication via temporary credentials from Auth Service (time-limited)
- TLS for TURN over TCP (5349)

---

### 3.5 Mobile Clients

#### Android (Kotlin)

- WebRTC via `org.webrtc:google-webrtc` (official Google library)
- Push via Firebase Cloud Messaging (FCM)
- Background calling via Foreground Service + Notification
- Signal processing: Acoustic Echo Cancellation (AEC), Noise Suppression (NS), Automatic Gain Control (AGC) — all from WebRTC stack

#### iOS (Swift)

- WebRTC via GoogleWebRTC pod
- Push via Apple Push Notification Service (APNs)
- Background calling via VoIP certificate + PushKit
- CallKit integration for native iOS call UI
- Same signal processing stack

---

## 4. Data Flow — Call Lifecycle

```
User tells AI to work autonomously
         │
         ▼
AI encounters blocker → invokes create_call MCP tool
         │
         ▼
MCP Server → POST /api/v1/calls (to Backend)
         │
         ▼
Call Manager creates call record (PostgreSQL)
         │
         ▼
Presence Service checks user status
         │
         ▼
Notification Service → Push Notification to mobile device
         │
         ▼
Mobile App receives push (high priority / VoIP)
         │
         ▼
App displays incoming call screen
         │
         ▼
User answers → App connects to Signaling Server via WebSocket
         │
         ▼
WebRTC Offer/Answer exchange via Signaling Server
         │
         ▼
STUN/TURN negotiation (coturn)
         │
         ▼
Peer-to-peer WebRTC audio call established (Opus codec)
         │
         ▼
User speaks → AI speech-to-text → LLM processes → Response
         │
         ▼
Call ends → context serialized → returned via MCP
         │
         ▼
AI resumes execution with user's input
```

---

## 5. Service Communication Matrix

| From | To | Protocol | Purpose |
|------|----|----------|---------|
| MCP Server | Backend | HTTP REST (internal) | All MCP tool operations |
| Auth Service | PostgreSQL | SQL | User/device/token CRUD |
| Presence Service | Redis | Redis Protocol | Presence state |
| Call Manager | PostgreSQL | SQL | Call records |
| Call Manager | Signaling Server | Redis PubSub | Call events |
| Signaling Server | Mobile Client | WebSocket | WebRTC signaling |
| Notification Service | FCM/APNs | HTTP | Push delivery |
| All services | Analytics | HTTP | Metrics ingestion |

---

## 6. Scalability Considerations

- **Signaling Server:** Stateless (state in Redis). Horizontal scale by adding instances behind a load balancer.
- **Call Manager:** Stateful w.r.t. active calls. Use consistent hashing or Redis-backed state for horizontal scaling.
- **coturn:** Multiple instances with anycast IP or DNS round-robin for global distribution (future).
- **PostgreSQL:** Read replicas for analytics queries. Connection pooling via PgBouncer.
- **Redis:** Cluster mode for large-scale deployments.

---

## 7. Deployment Architecture (MVP)

```
Hetzner VPS (CX21 or higher: 2 vCPU, 4 GB RAM)
│
├── Docker Compose
│   ├── mcp-server            :3000 (exposed, HTTPS via Caddy)
│   ├── backend-api           :4000 (internal only)
│   │   ├── auth-service
│   │   ├── presence-service
│   │   ├── call-manager
│   │   ├── notification-service
│   │   ├── signaling-server  :4001 (WebSocket, internal)
│   │   └── analytics-service
│   ├── postgresql            :5432
│   ├── redis                 :6379
│   ├── coturn                :3478 (STUN), :5349 (TURN/TLS)
│   └── caddy                 :443 (reverse proxy, TLS termination)
│
└── Monitoring (optional)
    ├── prometheus
    ├── grafana
    └── node-exporter
```

---

## 8. Technology Stack Summary

| Layer | Technology | Rationale |
|-------|-----------|-----------|
| MCP Server | Node.js / TypeScript | MCP SDK ecosystem, fast iteration |
| Backend Services | Node.js / TypeScript + Express/Fastify | Same language across stack |
| Database | PostgreSQL 16 | Reliable, mature, feature-rich |
| Cache & Real-time | Redis 7 | Presence, signaling state, PubSub |
| WebRTC Signaling | WebSocket (Node.js) | Bidirectional, low-latency |
| STUN/TURN | coturn | Self-hosted, zero per-call cost |
| Mobile (Android) | Kotlin + Jetpack Compose | Native performance, first-class WebRTC |
| Mobile (iOS) | Swift + SwiftUI | Native performance, CallKit |
| Web Client | React + TypeScript | Broad compatibility |
| Containerization | Docker + Docker Compose | Reproducible deployments |
| Reverse Proxy | Caddy | Automatic TLS, easy config |
| Push (Android) | Firebase Cloud Messaging | Industry standard |
| Push (iOS) | Apple Push Notification Service | Required for iOS |
| Monitoring | Prometheus + Grafana | Open-source, battle-tested |
| VPS | Hetzner Cloud | Cost-effective, good performance |
