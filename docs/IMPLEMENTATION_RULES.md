# AgentCall — Implementation Rules

> **These rules are mandatory for every developer and AI coding assistant.**
> Violations must be caught in code review.
>
> **Canonical references:** [PRODUCT_VISION.md](../PRODUCT_VISION.md) | [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) | [API_SPEC.md](../API_SPEC.md) | [PRD.md](./PRD.md)

---

## 1. Architecture Rules

1.1. AgentCall must never perform AI reasoning, prompt engineering, summarisation, or memory management.

1.2. AgentCall must never enrich, modify, or rewrite AI output before delivery.

1.3. Every feature must belong to exactly one runtime service per SYSTEM_ARCHITECTURE.md.

1.4. Services must communicate exclusively through the Event Bus, not direct method calls.

1.5. Every significant action must produce an event.

1.6. The architecture must be AI-provider agnostic. No provider-specific code in core services.

1.7. The architecture must be device agnostic. No device-specific code in core services.

---

## 2. Folder Structure Rules

2.1. All backend code lives under `backend/src/`.

2.2. Each runtime service gets its own directory: `auth/`, `calls/`, `providers/`, `presence/`, `notifications/`, `history/`, `devices/`, `gateway/`, `common/`.

2.3. Shared code goes in `common/`. No duplicate util functions.

2.4. Database migrations go in `database/migrations/`.

2.5. Test files sit next to source files they test: `service.ts` → `service.test.ts`.

2.6. Mobile app modules mirror the service structure.

---

## 3. Service Rules

3.1. Every service must have a single responsibility.

3.2. Every service must define its own TypeScript interface in `types.ts`.

3.3. Every service must export a function to register with the Event Bus.

3.4. Every service must handle its own errors internally, never throwing raw exceptions.

3.5. Services must not import from other services' internal modules — only through Event Bus events.

3.6. Every service must have unit test coverage >85%.

3.7. Service startup must be idempotent.

---

## 4. Repository Rules

4.1. Every aggregate root gets its own repository class.

4.2. Repositories must be interface-based, swappable between in-memory and PostgreSQL.

4.3. Repository methods must return domain objects, not database rows.

4.4. No raw SQL outside repository classes.

4.5. Migrations must be reversible (up/down).

4.6. Repository tests must use test containers for PostgreSQL.

---

## 5. API Rules

5.1. All endpoints must validate input with Zod schemas.

5.2. All endpoints must return structured errors per API_SPEC.md error format.

5.3. Every request must authenticate via JWT or Provider API Key.

5.4. Endpoints must be versioned under `/api/v1/`.

5.5. Breaking changes require a new API version.

5.6. No HTML/script tags in any response (XSS prevention).

5.7. Rate limiting on every public endpoint.

5.8. CORS must be explicitly configured, not wildcard (`*`).

---

## 6. Provider Rules

6.1. Every AI provider must be isolated in the Provider Registry.

6.2. Providers must have their own: history, transcripts, sessions, callbacks, permissions.

6.3. Provider configuration must be stored per-provider, not global.

6.4. Provider adapters must implement the canonical `ProviderAdapter` interface.

6.5. No provider-specific logic in the Event Bus or core services.

6.6. Provider authentication must be per-provider (independent OAuth/API key).

---

## 7. Device Rules

7.1. Every device must be registered before receiving events.

7.2. Devices must have a unique ID and type (android/ios/web/desktop).

7.3. Events must be routed to the correct device via the Device Router.

7.4. Device state must be tracked (online/offline, capabilities, push tokens).

7.5. One user can have multiple devices. Events must fan out to all active devices.

7.6. Device deregistration must clean up all associated state.

---

## 8. Event Rules

8.1. Event names must use PascalCase: `CallCreated`, `PresenceChanged`.

8.2. Every event must have a `type` and `payload` field.

8.3. Events must be versioned in their payload: `{ type: "CallCreated", version: 1, payload: {...} }`.

8.4. Event handlers must be idempotent.

8.5. Events must not contain sensitive data (no JWT tokens, no API keys).

8.6. The Event Bus must support at-least-once delivery.

8.7. Failed event handlers must retry with exponential backoff.

---

## 9. Testing Rules

9.1. Tests must be written before or alongside implementation code.

9.2. Unit tests must not depend on databases, networks, or I/O.

9.3. Integration tests must use real PostgreSQL and Redis (via test containers).

9.4. E2E tests must exercise the full MCP → Backend → Mobile pipeline.

9.5. Every MCP tool must have an integration test.

9.6. Coverage thresholds: unit >85%, services >90%, core domain >95%.

9.7. No test may depend on another test's state.

---

## 10. Security Rules

10.1. No secrets in code. Use environment variables or secrets manager.

10.2. All passwords and tokens must be hashed (bcrypt/SHA-256) before storage.

10.3. JWT tokens must have short expiry (15 min access, 7 day refresh).

10.4. Provider API keys must be prefixed and stored as SHA-256 hash.

10.5. All HTTP traffic must use HTTPS/WSS in production.

10.6. Input validation at every trust boundary (API, WebSocket, MCP).

10.7. Rate limiting: per-user, per-provider, per-IP.

10.8. Audit logging for auth events, call events, and data access.

---

## 11. Documentation Rules

11.1. Every public API must have JSDoc comments.

11.2. Every service must have a README explaining its purpose and events.

11.3. Every new event type must be documented in the Event Catalog.

11.4. Architecture changes must update SYSTEM_ARCHITECTURE.md.

11.5. API changes must update API_SPEC.md.

11.6. Breaking changes must be documented in CHANGELOG.md.

11.7. Documentation must be validated for consistency before PR merge.

---

## 12. Code Quality Rules

12.1. TypeScript strict mode required. No `any` type.

12.2. ESLint + Prettier enforced. 2-space indent.

12.3. No dead code, no commented-out code, no console.log.

12.4. Functions must be small (single responsibility, <50 lines preferred).

12.5. Imports: no barrel files, explicit imports only.

12.6. Error messages must be unique and searchable.

12.7. Async functions must handle all promise rejections.

---

## Enforcement

These rules are enforced through:

- **Pre-commit hooks:** ESLint, Prettier, type checking
- **CI pipeline:** Tests, lint, typecheck, coverage gates
- **Code review:** Every PR reviewed against ARCHITECTURE_CHECKLIST.md
- **Architecture review:** Every new service reviewed against SYSTEM_ARCHITECTURE.md

> See [ARCHITECTURE_CHECKLIST.md](./ARCHITECTURE_CHECKLIST.md) for the PR review checklist.
