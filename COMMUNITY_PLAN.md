# Community Growth Plan — VoiceBridge

## First Issues

Issues for new contributors who have never contributed to open source.

| Issue | Area | Description |
|-------|------|-------------|
| Remove PrimaryDatabase repos | Backend | 30-min cleanup: remove dead wrapper classes |
| Add statement timeout config | Backend | 15-min: add env var for DATABASE_STATEMENT_TIMEOUT |
| Fix DATABASE_GUIDE.md pg.Pool | Docs | 30-min: replace Knex references with pg.Pool |
| Add `typecheck` to CI pipeline | CI/CD | 15-min: add npm run typecheck to ci.yml |
| Fix PRODUCTION_READINESS.md | Docs | 15-min: remove POST /ready references |
| Update env var table in README | Docs | 30-min: verify and update environment variables |
| Add tabular numbers to Android app | Mobile | 1h: enable tabular figures in clock display |
| Add screenshots to README | Docs | 1h: capture and add Android app screenshots |

## Good First Issues

Issues that require some domain knowledge but are still beginner-friendly.

| Issue | Area | Skills |
|-------|------|--------|
| B-001: Statement timeout on pgPool | Backend | TypeScript, PostgreSQL |
| D-001: Fix API_SPEC.md routes | Docs | TypeScript, API design |
| D-004: Fix DATABASE_GUIDE.md | Docs | PostgreSQL, documentation |
| C-001: Remove PrimaryDatabase repos | Backend | TypeScript, repository pattern |
| C-002: Remove no-op event subscribers | Backend | TypeScript, event-driven patterns |
| F-007: Move phoneConnections to service | Backend | TypeScript, dependency injection |

## Documentation Improvements

| Document | Current State | Improvement | Effort |
|----------|--------------|-------------|--------|
| API_SPEC.md | Wrong routes, missing endpoints | Full rewrite to match routes.ts | 2h |
| ARCHITECTURE.md | Outdated, references removed components | Update to match SYSTEM_ARCHITECTURE.md | 1h |
| DEPLOYMENT_GUIDE.md | Wrong env vars, wrong commands | Full review and correction | 1h |
| DATABASE_GUIDE.md | Wrong dependency (Knex) | Replace with pg.Pool docs | 30min |
| README.md | Missing screenshots | Add Android app screenshots | 1h |

## Contributor Onboarding

### Onboarding Path

```
Phase 1: Setup (30 min)
├── Fork and clone repository
├── Install Node.js 20+, Docker Desktop
├── Run backend: cd backend && npm install && npm run dev
└── Verify: curl http://localhost:4000/api/v1/health

Phase 2: First Contribution (1-2 hours)
├── Pick a Good First Issue from COMMUNITY_PLAN.md
├── Read CONTRIBUTING.md
├── Create branch, make changes
├── Run npm test && npm run lint
└── Submit PR

Phase 3: Domain Familiarity (4-8 hours)
├── Read ARCHITECTURE_BASELINE.md
├── Read API_SPEC.md
├── Read at least 3 ADRs (docs/adr/)
├── Read KNOWN_LIMITATIONS.md
└── Complete a Medium-priority issue

Phase 4: Regular Contributor
├── Assigned to milestones
├── Review other PRs
├── Participate in architecture discussions
└── Mentor new contributors
```

### Tools for Onboarding

- **CONTRIBUTING.md** — Setup, standards, PR process
- **DEVELOPMENT_GUIDE.md** — Local dev environment
- **COMMUNITY.md** — Community values and norms
- **SECURITY.md** — Vulnerability reporting
- **Good First Issue label** — Curated starter issues
- **Help Wanted label** — Issues seeking contributors

## Community Standards

### Communication Channels

| Channel | Purpose | SLA |
|---------|---------|-----|
| GitHub Issues | Bug reports, feature requests | 24h acknowledgment |
| GitHub Discussions | Questions, ideas, general chat | 48h response |
| Pull Requests | Code contributions | 72h initial review |

### Norms

1. **Be respectful** — Follow CODE_OF_CONDUCT.md. No personal attacks, no dismissive language.
2. **Stay on topic** — Keep discussions relevant to AgentCall.
3. **Help others** — If you know the answer, share it. If you don't, ask clarifying questions.
4. **Assume good faith** — Most contributions are from people trying to help.
5. **No entitlement** — Maintainers are volunteers. PRs and issues are handled as time permits.
6. **Give and receive feedback** — Code reviews are about code, not people. Be specific, not personal.

### Label-Based Workflow

```
Issue Created
  → triage (automated label)
  → maintainer reviews
    → bug / enhancement / documentation / question
    → critical / high / medium / low
    → backend / mobile / mcp / infra / ci
  → assigned to milestone
  → assigned to contributor
  → PR submitted
  → reviewed
  → merged
  → issue closed
```

## Growth Goals

| Metric | Current | 6-Month Target | 12-Month Target |
|--------|---------|----------------|-----------------|
| GitHub Stars | 0 | 100 | 500 |
| Contributors | 1 | 5 | 20 |
| Open Issues | 0 | 15-25 | 30-50 |
| PRs per month | 0 | 5 | 20 |
| Forks | 0 | 10 | 50 |
| Discussions participants | 0 | 10 | 40 |
