# AgentCall MCP — Project Context

## Stack
- **Backend:** Node.js / TypeScript, Express/Fastify
- **Database:** PostgreSQL 16, Redis 7
- **WebRTC Signaling:** WebSocket (Node.js)
- **STUN/TURN:** coturn (self-hosted)
- **MCP Server:** Node.js / TypeScript (MCP SDK)
- **Mobile:** Android (Kotlin), iOS (Swift)
- **Deployment:** Docker Compose on Hetzner VPS (manual setup first)
- **Reverse Proxy:** Caddy with auto TLS

## Repo Structure
```
/backend       — All server-side services (monorepo)
/mcp-server    — MCP tool server
/mobile        — Android + iOS apps
/infra         — Docker Compose, Caddyfile, coturn config
/docs          — Design documents
```

## Conventions
- TypeScript strict mode, no `any`
- Prettier + ESLint, 2-space indent
- Input validation at every API boundary (Zod schemas)
- Errors: structured `{ error, code, details }` responses
- Commits: `type(scope): description` (e.g., `feat(auth): add OAuth login`)
- No secrets in code, use `.env` (documented in `.env.example`)

## Build & Test
- `npm run dev` — starts all services in dev mode
- `npm run lint` — ESLint + tsc --noEmit
- `npm test` — Vitest
- `npm run test:integration` — integration tests (needs Docker)
- Migrations: Knex.js (`npm run migrate`)
