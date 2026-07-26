# Changelog

All notable changes to AgentCall are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

### Planned
- Cross-pod session lock (Redis-backed distributed lock)
- WebSocket drain mechanism for zero-downtime rolling updates
- Migration tooling for schema management
- Multi-user auth (RBAC, JWT)
- iOS SwiftUI app re-activation
- Migration from InMemory repos to database-only persistence

---

## [1.0.0] — 2026-07-26

### Added
- Single-port architecture: HTTP server serves REST API, WebSocket signaling, and health probes on one port
- Production logging with structured prefixes: `[HTTP]`, `[WS]`, `[REGISTER]`, `[STT]`, `[TTS]`, `[VOICE]`
- Event bus hardening: retry with exponential backoff, backpressure queue, circuit breaker, dead-letter queue (DLQ)
- Database persistence mode with PostgreSQL 16: repositories for calls, sessions, events, presence; connection pool health monitoring
- Session lifecycle coordination: recovery manager, periodic sweeper, consistent state transitions
- On-device Android SpeechRecognizer (server-side STT removed)
- Android incoming call notification channel, notification builder
- Android light/dark theme support, launcher icons, empty states, error snackbar, loading shimmer
- Connection quality monitoring for WebSocket
- Docker multi-stage build with non-root user, HEALTHCHECK, read-only filesystem
- Kubernetes manifests (9 files): namespace, configmap, secrets template, deployment, HPA, service, ingress, PDB, network policy
- Load testing suite (42,000 ops/sec sustained)
- 48 unit and integration tests across 5 test files
- Production validation: 10 reports across architecture, reliability, security, performance, deployment, operations, maintainability, scalability, risk, test coverage
- Documentation: Full documentation migration and synchronisation (18 docs reviewed, 15 modified)
- Documentation: PRODUCT_VISION.md, SYSTEM_ARCHITECTURE.md, API_SPEC.md as canonical source of truth
- Documentation: 10 Architecture Decision Records (docs/adr/)
- Documentation: Engineering standards (CODE_STYLE, TESTING_GUIDE, DATABASE_GUIDE, API_GUIDELINES, ERROR_HANDLING, LOGGING_GUIDE, SECURITY_GUIDELINES, PERFORMANCE_GUIDELINES, SCALABILITY_GUIDE, DEPLOYMENT_GUIDE)
- Documentation: Community files (CONTRIBUTING.md, CODE_OF_CONDUCT.md, SECURITY.md, SUPPORT.md, CHANGELOG.md, COMMUNITY.md)
- Documentation: GitHub issue templates and PR template
- Documentation: 14 known limitations documented (KNOWN_LIMITATIONS.md)
- Documentation: Technical debt register (34 items across post-v1.0, v1.1, v2.0, Research, Cleanup)
- VERSION.md, RELEASE_NOTES_v1.0.md, ARCHITECTURE_BASELINE.md, OPERATIONS_BASELINE.md

### Changed
- Backend Docker image: `node:20-alpine` → `node:20-slim` for onnxruntime glibc compatibility
- WebSocket signaling: served on HTTP port via @fastify/websocket (no separate WS port)
- Android app: redesigned UI (Jetpack Compose), removed dead WebRTC code, optimized audio pipeline, added retry logic
- Config module: `POSTGRES_PASSWORD`, `REDIS_PASSWORD`, `COTURN_SECRET` removed; replaced with `SERVICE_TOKEN` and `DATABASE_URL`
- Config module: `PERSISTENCE_MODE` (memory | database | dual-write), health probes auto-computed
- Infrastructure: Removed cloud infra dependencies (Suga services, Redis Cloud, Coturn cloud); switched to Docker Compose + local-only networking
- Body parser errors return structured 400 instead of 500
- package.json version aligned to 1.0.0

### Fixed
- README.md: Rebranded from VoiceBridge to AgentCall, architecture aligned with SYSTEM_ARCHITECTURE.md
- PROJECT_OVERVIEW.md: Removed VoiceBridge branding and emotion enrichment references
- ARCHITECTURE.md, AI_INTEGRATION.md, MULTI_PROVIDER_PLAN.md, FREE_ARCHITECTURE.md: Removed emotion enrichment violations
- All docs/ files: Added canonical reference headers and cross-links
- Android TTS: Fixed audio focus handling and stream type
- Android WebSocket: Fixed reconnection and port configuration

### Deprecated
- docs/01-architecture-design.md — Superseded by SYSTEM_ARCHITECTURE.md
- docs/02-api-protocol-specification.md — Superseded by API_SPEC.md
- docs/07-mvp-scope-milestone-plan.md — Superseded by ROADMAP.md
- docs/09-infrastructure-cicd-plan.md — Superseded by DEPLOYMENT_GUIDE.md

---

## [0.1.0] — 2026-07-26

### Added
- Initial project scaffold
- Android app (Kotlin, Jetpack Compose, Hilt)
- Backend (TypeScript, Fastify, WebSocket signaling)
- MCP server (stdio + SSE + StreamableHTTP)
- 5 MCP tools: create_call, send_message, get_transcript, complete_call, cancel_call
- Production deployment on Suga
- Docker configuration
- CI pipeline (GitHub Actions)
