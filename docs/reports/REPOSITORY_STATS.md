# Repository Statistics

## Overview

| Metric | Value |
|--------|-------|
| Repository | AgentCall — The Communication Platform for AI |
| Version | v1.0.0 "Solo Bridge" |
| Total files (approx.) | 400+ |
| Total markdown files | 174 |
| Total lines of documentation | ~24,000 |
| Commits on main | 13 |
| Tests | 48 (5 test files) |
| Load test throughput | 42,000 ops/sec |

## Markdown File Count

| Category | Count | Location |
|----------|-------|----------|
| Public documentation | 25 | Repository root |
| Docs landing + internal reports | 7 | Repository root (generated audits) |
| Maintainer documentation | 30 | `docs/` |
| Design documents (historical) | 10 | `docs/` |
| Architecture Decision Records | 10 | `docs/adr/` |
| Engineering archive | 53 | `docs/archive/` |
| Validation reports | 35 | `docs/reports/` |
| GitHub templates | 4 | `.github/` |
| **Total** | **174** | |

## Source Code

| Directory | Primary Language | Purpose |
|-----------|-----------------|---------|
| `backend/` | TypeScript | VoiceBridge server (Fastify, WebSocket) |
| `mcp-server/` | TypeScript | MCP protocol server |
| `mobile/android/` | Kotlin | Android app (Jetpack Compose) |
| `infra/` | YAML/Dockerfile | Docker Compose, K8s, Caddy, coturn |

## Repository Structure

```
agentcall/
├── backend/           # VoiceBridge server
├── mcp-server/        # MCP protocol server
├── mobile/            # Android app
├── infra/             # Infrastructure configs
├── docs/              # All documentation
│   ├── adr/           # Architecture Decision Records (10)
│   ├── archive/       # Engineering history (53)
│   └── reports/       # Validation reports (35)
└── .github/           # GitHub config
    ├── ISSUE_TEMPLATE/ # Issue forms (4)
    └── workflows/      # CI/CD pipelines (2)
```

## Maintenance Complexity

| Factor | Rating | Notes |
|--------|--------|-------|
| Documentation pages | Moderate | 174 files, well-organized with indexes |
| Report count | High | 35 reports + 53 archived = repetitive content |
| Version tracking | Low | Single version, consistent across 6 sources |
| Dependency updates | Low | 2 npm packages, 1 Docker image, GH Actions |
| Test maintenance | Low | 48 tests, stable |
| Architecture docs | Low | Locked baseline (3 tiers) |
| **Overall** | **Low-Moderate** | Manageable for a small team |

## Recommendations

1. **Archive stale reports** — After GA validation, consider pruning redundant reports
2. **Automate stats** — Use `github-action-stats` to keep statistics current
3. **Monitor doc growth** — Set a documentation review cadence (quarterly)
