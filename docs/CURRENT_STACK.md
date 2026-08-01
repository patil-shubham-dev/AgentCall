# AgentCall — Current Technology Stack

> **Canonical references:** [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) | [API_SPEC.md](../API_SPEC.md) | [PRODUCT_VISION.md](../PRODUCT_VISION.md)

## Android App

| Category | Technology | Version | Purpose |
|----------|-----------|---------|---------|
| **Language** | Kotlin | 2.0.0 | Primary development language |
| **UI** | Jetpack Compose | BOM 2024.06.00 | Declarative UI framework |
| **Material Design** | Material 3 | via BOM | Design system |
| **DI** | Dagger Hilt | 2.51.1 (KSP) | Dependency injection |
| **Annotation Processor** | KSP | 2.0.0-1.0.22 | Kotlin symbol processing |
| **HTTP Client** | OkHttp | 4.12.0 | Networking (REST + WebSocket) |
| **REST Client** | Retrofit | 2.9.0 | Type-safe HTTP client |
| **JSON** | kotlinx.serialization | 1.6.3 | JSON parsing |
| **Coroutines** | kotlinx.coroutines | 1.8.1 | Async/concurrency |
| **Navigation** | Navigation Compose | 2.7.7 | Screen navigation |
| **Lifecycle** | Lifecycle Runtime/ViewModel | 2.8.3 | ViewModel + lifecycle |
| **Activity** | Activity Compose | 1.9.0 | Compose integration |
| **Core KTX** | AndroidX Core | 1.13.1 | Core AndroidX |
| **Secure Storage** | Security Crypto | 1.1.0-alpha06 | EncryptedSharedPreferences |
| **Build System** | Gradle + AGP | 8.7 / 8.5.0 | Build tool |
| **Speech Recognition** | Android SpeechRecognizer | Platform (API 26+) | On-device STT |
| **Text-to-Speech** | Android TextToSpeech | Platform (API 26+) | On-device TTS |
| **Audio Capture** | AudioRecord | Platform (API 26+) | Barge-in PCM detection |

## Backend Server

| Category | Technology | Version | Purpose |
|----------|-----------|---------|---------|
| **Runtime** | Node.js | 20.x | Server runtime |
| **Language** | TypeScript | 5.5.0 | Type-safe JavaScript |
| **HTTP Framework** | Fastify | 4.28.0 | HTTP server |
| **WebSocket** | ws | 8.17.0 | WebSocket server |
| **CORS** | @fastify/cors | 9.0.0 | Cross-origin support |
| **Security Headers** | @fastify/helmet | 11.1.1 | HTTP security |
| **Compression** | @fastify/compress | 7.0.3 | Response compression |
| **Rate Limiting** | @fastify/rate-limit | 9.1.0 | Request throttling |
| **Logging** | Pino | 9.1.0 | Structured logging |
| **Dev Logging** | pino-pretty | 11.1.0 | Human-readable logs |
| **Env Loading** | dotenv | 16.4.0 | .env file loader |
| **Process Runner** | tsx | 4.16.0 | TypeScript execution (dev) |
| **Linter** | ESLint | 8.57.0 | Code linting |
| **Formatter** | Prettier | (via .prettierrc) | Code formatting |
| **Testing** | Vitest | (configured) | Unit test framework |
| **Storage** | In-memory Maps / PostgreSQL 16 | — | 4 persistence modes: memory, dual-write, database-read, database |

## MCP Server

| Category | Technology | Version | Purpose |
|----------|-----------|---------|---------|
| **Runtime** | Node.js | 20.x | Server runtime |
| **Language** | TypeScript | 5.5.0 | Type-safe JavaScript |
| **MCP SDK** | @modelcontextprotocol/sdk | 1.0.0 | MCP protocol |
| **Validation** | Zod | 3.23.0 | Schema validation |
| **Logging** | Pino | 9.1.0 | Structured logging |
| **Transport** | stdio + SSE + StreamableHTTP | — | Multiple transports |

## Infrastructure & Deployment

| Service | Technology | Version | Details |
|---------|-----------|---------|---------|
| **Backend Runtime** | Node.js | 20-slim | Docker multi-stage build |
| **MCP Runtime** | Node.js | 20-alpine | Docker multi-stage build |
| **Hosting Platform** | Suga (suga.run) | — | Serverless deployment, australia-southeast1 |
| **Reverse Proxy** | Suga built-in | — | Replaces Caddy in prod |
| **Containerization** | Docker | — | Build only (no Docker Compose in prod) |
| **CI/CD** | GitHub Actions | — | Lint + typecheck + test + build only |

## Planned / Aligned with SYSTEM_ARCHITECTURE.md

The following are planned to align with the canonical architecture (see [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md)):

| Technology | Status | Role in Canonical Architecture |
|-----------|--------|-------------------------------|
| PostgreSQL 16 | ✅ Implemented | Primary storage (in `database` / `dual-write` modes) |
| Coturn | ✅ Implemented | STUN/TURN for WebRTC media relay (infra/coturn/) |
| Caddy | ✅ Implemented | Reverse proxy + auto TLS (infra/Caddyfile) |
| JWT / OAuth 2.0 | 📝 Not planned | Current auth uses single SERVICE_TOKEN |
| Provider abstraction layer | 📝 Not planned | AI integration via MCP SDK |
| Firebase FCM | 📝 Not planned | Push not implemented; WebSocket is the notification channel |
| APNs | 📝 Not planned | iOS app archived, FCM/APNs not implemented |
| Prometheus + Grafana | 📝 Not planned | No monitoring dashboards implemented |
| Redis 7 | ❌ Removed | Was part of abandoned v2 architecture |
| Notification Engine | ❌ Removed | Was part of abandoned v2 architecture |
| Callback Engine | ✅ Implemented | LifecycleCoordinator + sweeper in service.ts |

## Version Matrix

```
Android App:    v1.0.0  (versionCode 1)
Backend:        v1.0.0  (@agentcall/voicebridge)
MCP Endpoint:   embedded in backend (@modelcontextprotocol/sdk)
```

## Deployed URLs

```
Production Backend: https://dydcghsn0my6-production-qgbb8wql.australia-southeast1.suga.run
Production WS:      wss://dydcghsn0my6-production-qgbb8wql.australia-southeast1.suga.run/phone
Production API Base: https://dydcghsn0my6-production-qgbb8wql.australia-southeast1.suga.run/api/v1/
```
