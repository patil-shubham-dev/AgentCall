# AgentCall — Architecture Compliance Checklist

> **Canonical references:** [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) | [IMPLEMENTATION_RULES.md](./IMPLEMENTATION_RULES.md) | [PRODUCT_VISION.md](../PRODUCT_VISION.md)

---

Use this checklist when reviewing any PR, designing a new component, or evaluating architecture compliance. Every item must be satisfied before merging.

---

## 1. Provider Agnostic

- [ ] All call/event flows treat providers as abstract — no provider-specific logic in core services
- [ ] Provider-specific logic lives in adapter layer only
- [ ] Provider Registry enforces isolation between providers
- [ ] No hardcoded provider names, URLs, or API endpoints in core code
- [ ] Provider credentials stored and validated via Provider Registry, not spread across services

## 2. Device Agnostic

- [ ] Core services do not import Android SDK, iOS SDK, or browser APIs
- [ ] All device-specific logic is behind the Communication Gateway
- [ ] Device capabilities are queried, not assumed
- [ ] Events carry device metadata only when needed for routing
- [ ] New device types can be added without modifying core services

## 3. Event-Driven

- [ ] Services communicate via Event Bus, not direct method calls between services
- [ ] Event handlers are async — never block the publisher
- [ ] Events are versioned with schema evolution capability
- [ ] Event handlers are idempotent
- [ ] Failed events go to dead letter queue after retries
- [ ] Event payloads are small — reference data by ID, not full objects

## 4. Service Boundaries

- [ ] Each service has a single responsibility
- [ ] Services own their data (no cross-service direct database access)
- [ ] Service interfaces are explicit (TypeScript interfaces or API contracts)
- [ ] Circular dependencies between services are absent
- [ ] Service health is independently verifiable

## 5. Auth Everywhere

- [ ] Every endpoint requires authentication (unless explicitly documented as public)
- [ ] Auth is enforced at the middleware/gateway level, not per-route
- [ ] JWT tokens include only necessary claims (minimal surface)
- [ ] API keys are hash-stored, never logged
- [ ] Rate limiting is applied to all authenticated endpoints

## 6. Input Validation

- [ ] Every API endpoint validates input with Zod schemas
- [ ] MCP tool inputs validate with Zod schemas
- [ ] WebSocket and SSE messages validate at the transport level
- [ ] String lengths are bounded
- [ ] UUIDs are validated by format
- [ ] HTML/script injection is rejected

## 7. Error Handling

- [ ] All errors return structured format: `{ error: { code, message, correlationId, details } }`
- [ ] 4xx errors log at `warn` level
- [ ] 5xx errors log at `error` level with stack trace
- [ ] Error messages do not leak internals (no stack traces to clients)
- [ ] Every service handles its own errors — errors don't propagate raw

## 8. Stateless API

- [ ] API handlers do not maintain in-memory state between requests
- [ ] State lives in PostgreSQL (persistent) or Redis (transient)
- [ ] Horizontal scaling does not require sticky sessions
- [ ] WebSocket connections are the only stateful component (by design)

## 9. Testing

- [ ] Service unit test coverage >85%
- [ ] Core domain coverage >95%
- [ ] Integration tests for cross-service flows (Event Bus chains)
- [ ] Migration tests (up/down verified)
- [ ] Load tests for target concurrency

## 10. Documentation

- [ ] New endpoints documented in API_SPEC.md
- [ ] New services documented in SYSTEM_ARCHITECTURE.md
- [ ] Architecture decisions documented in ADR (under docs/adr/)
- [ ] Configuration documented in .env.example
- [ ] Migration scripts documented for breaking changes

---

## Quick Reference: PR Gate

| Gate | Required | Blocking |
|------|----------|----------|
| Lint passes | ✅ | ✅ |
| Types compile | ✅ | ✅ |
| Unit tests pass | ✅ | ✅ |
| Integration tests pass | ✅ | ✅ |
| Coverage meets threshold | ✅ | ✅ |
| Provider agnostic | ✅ | ✅ |
| Device agnostic | ✅ | ✅ |
| Input validation | ✅ | ✅ |
| Auth enforced | ✅ | ✅ |
| Error handling format | ✅ | ✅ |
| ADR for design decisions | ⬜ (if applicable) | ⬜ |
| API_SPEC.md updated | ✅ (if API change) | ✅ |
