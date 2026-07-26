# VoiceBridge v2.0 — Future Architecture Vision

## Long-Term Architecture

### Current (v1.0)
```
Single-process Node.js
├── Fastify HTTP + WebSocket
├── InMemory + PostgreSQL repos
├── Per-process locks and timers
├── Single-token auth
└── No external broker
```

### Target (v2.0)
```
Distributed Node.js services
├── API Gateway (Fastify)
│   ├── Auth Service (JWT, OAuth2)
│   ├── Session Service (cross-pod locks)
│   └── Call Manager (Event-driven)
├── WebSocket Gateway (standalone)
│   ├── Connection Manager (stateful)
│   └── Migration Handler (pod-to-pod handoff)
├── Message Broker (Redis)
│   ├── Distributed Timers
│   ├── Event Bus (pub/sub)
│   └── Presence Channels
├── Android App
│   ├── JWT Auth Flow
│   └── Push Notifications (FCM)
├── iOS App (new)
│   ├── SwiftUI / React Native
│   └── Same feature set as Android
└── Infrastructure
    ├── Prometheus + Grafana
    ├── Centralized Logging
    └── Service Mesh (optional)
```

## Scalability Improvements

| Area | v1.0 | v2.0 Target |
|------|------|-------------|
| Session capacity | 10K (InMemory) | 1M+ (database-backed) |
| Concurrent calls | Per-pod limit | Distributed across pods |
| WebSocket connections | 1000/pod (unlimited today) | 10K/pod, unlimited across cluster |
| Timers | Per-process, lost on restart | Redis-based, survive pod restarts |
| Database | Single PostgreSQL | PostgreSQL + read replicas |
| Deployment | Single pod | Multi-pod with HPA |
| CI/CD | Basic GitHub Actions | Canary deployments, blue-green |

## Authentication Evolution

### v1.0 → v1.1 → v2.0

```
v1.0:  SERVICE_TOKEN (single shared secret)
        ↓
v1.1:  SERVICE_TOKEN + Multi-user session IDs
        ↓
v2.0:  JWT-based auth with OAuth2 support
       ├── User registration (email/password)
       ├── OAuth2 providers (Google, GitHub)
       ├── API key generation for MCP clients
       ├── Role-based access (admin, user, service)
       ├── Token rotation and revocation
       └── Audit logging
```

### Auth Service Architecture (v2.0)
```
POST /api/v2/auth/register  → Create user
POST /api/v2/auth/login     → Issue JWT (access + refresh)
POST /api/v2/auth/refresh   → Rotate tokens
POST /api/v2/auth/revoke    → Revoke specific token
GET  /api/v2/auth/keys      → List API keys (for MCP clients)
POST /api/v2/auth/keys      → Create API key
```

## Distributed Architecture

### Cross-Pod Coordination

| Mechanism | v1.0 | v2.0 |
|-----------|------|------|
| Session lock | Per-process mutex | pg_advisory_lock → Redis lock |
| Timers | setTimeout | Redis Bull/BullMQ |
| Event bus | In-process EventBus | Redis pub/sub |
| Presence | Per-process Map | Redis-backed presence |
| WebSocket state | Per-process Map | Redis-backed session registry |

### Deployment Topology (v2.0)

```
Load Balancer
     │
     ├── API Pod 1 ──┐
     ├── API Pod 2 ──┤── Redis ── PostgreSQL (primary)
     ├── API Pod 3 ──┘              │
     └── WS Gateway ────── Redis    └── PostgreSQL (replica)
```

## Enterprise Features

| Feature | Description | Target |
|---------|-------------|--------|
| Audit logging | All API calls, auth events, session mutations logged | v1.1 |
| SSO/SAML | Enterprise single sign-on | v2.0 |
| Rate limiting | Per-user, per-IP, per-token rate limits | v1.1 |
| Webhooks | Call state change webhooks for external systems | v1.2 |
| Audit trail | Immutable session history for compliance | v2.0 |
| Custom domains | Multi-tenant domain support | v2.0 |
| SLA monitoring | Uptime, latency, error rate dashboards | v1.1 |
| Backup automation | Automated DB backup, point-in-time recovery | v1.1 |
| Data retention policies | Configurable session retention (days/months) | v1.2 |
| Compliance | SOC 2, GDPR, HIPAA documentation | v2.0 |

## Cloud Deployment

### Current (Self-Hosted)
```
Docker Compose on single VPS
├── VoiceBridge container
├── PostgreSQL container
└── Caddy reverse proxy
```

### Target (v2.0 — Cloud-Native)

**Option A: Managed Kubernetes (GKE, EKS, AKS)**
```
Kubernetes cluster
├── VoiceBridge deployment (3+ replicas)
├── PostgreSQL Cloud SQL / RDS
├── Redis Memorystore / ElastiCache
├── Ingress: Caddy / nginx-ingress
├── HPA: CPU > 70% or memory > 80%
├── PDB: min 2 available
└── Service mesh: Istio or Linkerd
```

**Option B: Serverless + Managed Services**
```
Cloud Run / Fly.io (stateless API)
├── VoiceBridge containers (auto-scale to 0)
├── PostgreSQL managed (Neon, Supabase, RDS)
├── Redis managed (Upstash, ElastiCache)
├── Cloud CDN for static assets
└── Cloud Scheduler for periodic tasks
```

### Observability Stack (v2.0)
```
Metrics:  Prometheus → Grafana dashboards
Logs:     Structured JSON → Loki or CloudWatch
Traces:   OpenTelemetry → Jaeger or Honeycomb
Alerts:   AlertManager → PagerDuty or Slack
Uptime:   Healthchecks.io or Better Uptime
```

## Migration Path

```
v1.0 → v1.1: Add pg_advisory_lock, migration tooling, pagination
               │
v1.1 → v1.2: Add JWT auth, notification service
               │
v1.2 → v2.0: Add Redis, distributed timers, WS migration,
              Prometheus, read replicas, iOS app
```

## Key Risks

| Risk | Impact | Mitigation |
|------|--------|------------|
| Redis operational complexity | High — new dependency to manage | Consider managed Redis (Upstash) |
| JWT auth security surface | High — more attack surface | Audit, pen testing, security review |
| iOS development cost | Medium — need Swift developer | Evaluate React Native or Flutter |
| Multi-pod feature regressions | High — locking correctness | Comprehensive integration tests |
| v2.0 scope creep | Medium — too large, never ships | Strict milestone scope enforcement |
