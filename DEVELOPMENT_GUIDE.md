# AgentCall — Development Guide

> **Canonical references:** [IMPLEMENTATION_RULES.md](./docs/IMPLEMENTATION_RULES.md) | [SYSTEM_ARCHITECTURE.md](./SYSTEM_ARCHITECTURE.md) | [API_SPEC.md](./API_SPEC.md)

---

## Repository Structure

```
AgentCall/
├── backend/                    # Backend services (TypeScript/Fastify)
│   ├── src/
│   │   ├── auth/               # Authentication Service
│   │   ├── calls/              # Call Manager
│   │   ├── providers/          # Provider Registry
│   │   ├── presence/           # Presence Engine
│   │   ├── notifications/      # Notification Engine
│   │   ├── history/            # History Service
│   │   ├── devices/            # Device Router
│   │   ├── gateway/            # Communication Gateway
│   │   ├── common/             # Shared utilities, types
│   │   ├── database/           # Migrations, seed data
│   │   ├── index.ts            # Entry point
│   │   └── event-bus.ts        # Event Bus
│   ├── tests/
│   ├── package.json
│   ├── tsconfig.json
│   └── Dockerfile
├── mcp-server/                 # MCP tool server
│   ├── src/
│   ├── package.json
│   └── Dockerfile
├── mobile/
│   ├── android/                # Android app (Kotlin/Compose)
│   └── ios/                    # iOS app (Swift) — future
├── infra/                      # Infrastructure configs
│   ├── docker-compose.yml
│   ├── Caddyfile
│   └── coturn/
├── docs/                       # Documentation
│   └── adr/                    # Architecture Decision Records
├── .github/                    # GitHub templates, workflows
├── AGENTS.md
├── API_SPEC.md
├── ARCHITECTURE.md
├── CURRENT_STACK.md
├── DEVELOPMENT_GUIDE.md
├── IMPLEMENTATION_ROADMAP.md
├── IMPLEMENTATION_RULES.md
├── INFRASTRUCTURE.md
├── PRD.md
├── PRODUCT_VISION.md
├── PROJECT_OVERVIEW.md
├── README.md
├── ROADMAP.md
├── SYSTEM_ARCHITECTURE.md
├── CHANGELOG.md
├── CODE_OF_CONDUCT.md
├── CODE_STYLE.md
├── CONTRIBUTING.md
├── SECURITY.md
├── SUPPORT.md
├── TESTING_GUIDE.md
├── DATABASE_GUIDE.md
├── API_GUIDELINES.md
├── ERROR_HANDLING.md
├── LOGGING_GUIDE.md
├── SECURITY_GUIDELINES.md
├── PERFORMANCE_GUIDELINES.md
├── SCALABILITY_GUIDE.md
├── DEPLOYMENT_GUIDE.md
└── OPEN_SOURCE_READINESS.md
```

---

## Coding Standards

### TypeScript

- Strict mode enabled in `tsconfig.json`
- No `any` type — use `unknown` if type is truly unknown
- Explicit return types on all public functions
- Interfaces prefixed with `I` for inversion-of-control (e.g., `IAuthService`)
- Types use PascalCase, functions use camelCase, constants use UPPER_SNAKE_CASE

### Kotlin (Android)

- Follow Android Kotlin Style Guide
- Use `val` over `var` unless mutation required
- ViewModels expose StateFlow, not mutable state
- Hilt for dependency injection everywhere

### General

- 2-space indentation (TypeScript), 4-space (Kotlin)
- Max line length: 100 characters
- Semicolons required (TypeScript)
- Single quotes for strings (TypeScript), double quotes (Kotlin)
- No trailing whitespace

---

## Naming Conventions

| Element | Convention | Example |
|---------|-----------|---------|
| Services | PascalCase | `AuthService`, `CallManager` |
| Events | PascalCase | `CallCreated`, `PresenceChanged` |
| MCP Tools | snake_case | `create_call`, `send_message` |
| REST endpoints | kebab-case | `/api/v1/call-history` |
| Database tables | snake_case | `call_sessions`, `auth_tokens` |
| Database columns | snake_case | `created_at`, `call_id` |
| TypeScript files | kebab-case | `auth-service.ts`, `event-bus.ts` |
| Kotlin files | PascalCase | `AuthService.kt`, `CallManager.kt` |
| CSS classes | kebab-case | `.call-banner`, `.status-indicator` |

---

## Git Workflow

### Branch Strategy

```
main
  └── production deployment
      └── (merge from staging after validation)

staging
  └── pre-production validation
      └── (merge from develop after CI passes)

develop
  └── active development
      └── (feature branches merged here)
```

### Branch Naming

| Pattern | Example |
|---------|---------|
| `feat/<description>` | `feat/add-auth-service` |
| `fix/<description>` | `fix/ws-reconnect-loop` |
| `docs/<description>` | `docs/adr-event-driven` |
| `refactor/<description>` | `refactor/extract-provider-adapter` |
| `test/<description>` | `test/presence-concurrency` |
| `chore/<description>` | `chore/update-deps` |

### Commit Conventions

Use [Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): description

[optional body]
```

Types: `feat`, `fix`, `docs`, `refactor`, `test`, `chore`

Examples:
```
feat(auth): add JWT token issuance and validation
fix(signaling): handle WebSocket reconnect race condition
docs(adr): add service boundaries decision record
```

---

## How to Add a Service

1. Create directory `backend/src/<service-name>/`
2. Create `types.ts` — define data types and event interfaces
3. Create `service.ts` — implement the service class
4. Create `index.ts` — export registration function for Event Bus
5. Create `service.test.ts` — unit tests
6. Register events in `event-bus.ts`
7. Add to `backend/src/index.ts` startup sequence
8. Add to SYSTEM_ARCHITECTURE.md if applicable
9. Add to CURRENT_STACK.md if using new technology

### Service Template

```typescript
// backend/src/<service>/types.ts
export interface IServiceConfig {
  // config fields
}

export interface ServiceEvents {
  'event.name': { field: string };
}

// backend/src/<service>/service.ts
export class Service {
  constructor(private config: IServiceConfig) {}

  async initialize(): Promise<void> {}
  async destroy(): Promise<void> {}
}
```

---

## How to Add a Provider

1. Implement `ProviderAdapter` interface from `backend/src/providers/`
2. Create `backend/src/providers/adapters/<provider-name>-adapter.ts`
3. Register in `ProviderRegistry`
4. Add provider-specific auth handler if needed
5. Add integration tests
6. Update MULTI_PROVIDER_PLAN.md if applicable

---

## How to Add a Device

1. Implement device registration endpoint per API_SPEC.md
2. Add device type to `devices/types.ts`
3. Add routing logic to `DeviceRouter`
4. Add push notification handler if platform-specific
5. Add integration tests

---

## How to Add an API

1. Define endpoint in API_SPEC.md first
2. Create Zod validation schema
3. Implement route handler
4. Add auth middleware (JWT or Provider API Key)
5. Add rate limiting
6. Add integration tests
7. Update OpenAPI spec if applicable

---

## How to Add an MCP Tool

1. Define tool in API_SPEC.md first
2. Add tool definition to `mcp-server/src/tools.ts`
3. Implement tool handler
4. Add Zod validation for tool inputs
5. Add backend API endpoint if needed
6. Add tool integration test
7. Update README.md MCP tools table

---

## How to Write Migrations

```bash
# Create migration
npm run migrate:make -- migration-name

# File: database/migrations/20260726120000_add_auth_tables.ts
exports.up = async (knex) => {
  await knex.schema.createTable('users', (table) => {
    table.uuid('id').primary();
    table.string('email').unique().notNullable();
    table.timestamps(true, true);
  });
};

exports.down = async (knex) => {
  await knex.schema.dropTable('users');
};
```

Rules:
- Every migration must have an up and down function
- Migrations must be idempotent (safe to run multiple times)
- Never modify a committed migration — create a new one
- Test both up and down before merging

---

## How to Update Documentation

1. Read the canonical source relevant to your change
2. Update document to match if it describes the same thing
3. Add cross-reference to canonical source
4. If an old document becomes obsolete, add deprecation notice (do not delete)
5. Validate no contradictions exist after update
6. See docs/DOCUMENTATION_MIGRATION_REPORT.md for prior audit

---

## Development Commands

```bash
# Backend
cd backend
npm run dev            # Start dev server with hot reload
npm run lint           # ESLint
npm run typecheck      # tsc --noEmit
npm test               # Vitest unit tests
npm run test:integration  # Integration tests
npm run migrate        # Run database migrations

# MCP Server
cd mcp-server
npm run dev            # Start MCP server
npm run build          # Build for production
npm test               # Run tests

# Android
cd mobile/android
./gradlew assembleDebug        # Build debug APK
./gradlew test                 # Run unit tests
./gradlew connectedCheck       # Run instrumented tests
```
