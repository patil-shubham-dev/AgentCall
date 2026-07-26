# AgentCall Documentation

Welcome to the AgentCall documentation hub. This page organizes all project documentation by audience.

> **Quick links:** [README.md](../README.md) · [API_SPEC.md](../API_SPEC.md) · [ARCHITECTURE.md](../ARCHITECTURE.md) · [CHANGELOG.md](../CHANGELOG.md) · [CONTRIBUTING.md](../CONTRIBUTING.md)

---

## 👤 User Documentation

For users deploying and operating AgentCall.

| Document | Description |
|----------|-------------|
| [API_SPEC.md](../API_SPEC.md) | Complete API contract: REST, WebSocket, MCP tools |
| [DEPLOYMENT_GUIDE.md](../DEPLOYMENT_GUIDE.md) | Docker Compose and Kubernetes deployment |
| [DATABASE_GUIDE.md](../DATABASE_GUIDE.md) | PostgreSQL setup, schema, and maintenance |
| [PRODUCTION_READINESS.md](../PRODUCTION_READINESS.md) | Production deployment checklist |
| [OPERATIONS_BASELINE.md](../OPERATIONS_BASELINE.md) | SLO targets, metrics, and alerting |
| [KNOWN_LIMITATIONS.md](../KNOWN_LIMITATIONS.md) | Product limitations (L001–L014) |

## 🏗 Architecture

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](../ARCHITECTURE.md) | High-level architecture overview |
| [SYSTEM_ARCHITECTURE.md](../SYSTEM_ARCHITECTURE.md) | Detailed system architecture specification |
| [ARCHITECTURE_BASELINE.md](../ARCHITECTURE_BASELINE.md) | Locked architecture reference (source of truth) |
| [PRODUCT_VISION.md](../PRODUCT_VISION.md) | Product philosophy and vision |
| [PROJECT_OVERVIEW.md](../PROJECT_OVERVIEW.md) | Project scope and context |
| [PERSISTENCE_ARCHITECTURE.md](./PERSISTENCE_ARCHITECTURE.md) | Persistence mode design |

## 🤖 AI Integration

| Document | Description |
|----------|-------------|
| [AI_INTEGRATION.md](./AI_INTEGRATION.md) | AI provider integration guide |
| [MULTI_PROVIDER_PLAN.md](./MULTI_PROVIDER_PLAN.md) | Multi-provider strategy |
| [CHATGPT_INTEGRATION.md](./CHATGPT_INTEGRATION.md) | ChatGPT-specific notes |
| [SESSION_LIFECYCLE_POLICY.md](./SESSION_LIFECYCLE_POLICY.md) | Session lifecycle design |

## 🔒 Security

| Document | Description |
|----------|-------------|
| [SECURITY.md](../SECURITY.md) | Security policy and vulnerability reporting |
| [SECURITY_GUIDELINES.md](./SECURITY_GUIDELINES.md) | Security best practices and hardening |

## 💻 Developer Documentation

For developers contributing to or extending AgentCall.

| Document | Description |
|----------|-------------|
| [DEVELOPMENT_GUIDE.md](../DEVELOPMENT_GUIDE.md) | Development setup and workflows |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | How to contribute |
| [CODE_STYLE.md](./CODE_STYLE.md) | Coding style guide (TypeScript strict, 2-space) |
| [API_GUIDELINES.md](./API_GUIDELINES.md) | API design conventions |
| [TESTING_GUIDE.md](./TESTING_GUIDE.md) | Testing strategy and practices |
| [ERROR_HANDLING.md](./ERROR_HANDLING.md) | Error handling conventions |
| [LOGGING_GUIDE.md](./LOGGING_GUIDE.md) | Logging conventions and levels |
| [PERFORMANCE_GUIDELINES.md](./PERFORMANCE_GUIDELINES.md) | Performance optimization |
| [SCALABILITY_GUIDE.md](./SCALABILITY_GUIDE.md) | Scalability considerations |
| [IMPLEMENTATION_RULES.md](./IMPLEMENTATION_RULES.md) | Mandatory development rules |
| [IMPLEMENTATION_ROADMAP.md](./IMPLEMENTATION_ROADMAP.md) | Engineering roadmap |
| [ARCHITECTURE_CHECKLIST.md](./ARCHITECTURE_CHECKLIST.md) | PR review architecture checklist |
| [CODE_OWNERSHIP.md](./CODE_OWNERSHIP.md) | Code ownership map |
| [PRD.md](./PRD.md) | Product requirements document |
| [RISK_REGISTER.md](./RISK_REGISTER.md) | Risk register |
| [REPOSITORY_CLEANUP.md](./REPOSITORY_CLEANUP.md) | Repository organization tracking |
| [TECHNICAL_DEBT_REGISTER_v1.md](./TECHNICAL_DEBT_REGISTER_v1.md) | Technical debt register (34 items) |

## ⚙️ Operations

| Document | Description |
|----------|-------------|
| [DISASTER_RECOVERY.md](./DISASTER_RECOVERY.md) | Disaster recovery procedures |
| [GRAFANA_DASHBOARDS.md](./GRAFANA_DASHBOARDS.md) | Monitoring dashboard setup |
| [INFRASTRUCTURE.md](./INFRASTRUCTURE.md) | Infrastructure configuration |
| [FREE_ARCHITECTURE.md](./FREE_ARCHITECTURE.md) | Free-tier architecture notes |
| [CURRENT_STACK.md](./CURRENT_STACK.md) | Detailed technology stack |
| [OPEN_SOURCE_READINESS.md](./OPEN_SOURCE_READINESS.md) | Open source maturity assessment |

## 🗺 Roadmap & Planning

| Document | Description |
|----------|-------------|
| [ROADMAP.md](../ROADMAP.md) | Public product roadmap |
| [IMPLEMENTATION_ROADMAP.md](./IMPLEMENTATION_ROADMAP.md) | Detailed engineering roadmap |
| [PRD.md](./PRD.md) | Product requirements document |
| [RISK_REGISTER.md](./RISK_REGISTER.md) | Risk register |

## 📜 Architecture Decision Records

| ID | Title |
|----|-------|
| 0001 | [System Philosophy](adr/0001-system-philosophy.md) |
| 0002 | [Provider Abstraction](adr/0002-provider-abstraction.md) |
| 0003 | [Event-Driven Architecture](adr/0003-event-driven.md) |
| 0004 | [Authentication](adr/0004-authentication.md) |
| 0005 | [Device Routing](adr/0005-device-routing.md) |
| 0006 | [Notification Engine](adr/0006-notification-engine.md) |
| 0007 | [Callback Engine](adr/0007-callback-engine.md) |
| 0008 | [API Versioning](adr/0008-api-versioning.md) |
| 0009 | [Data Ownership](adr/0009-data-ownership.md) |
| 0010 | [Service Boundaries](adr/0010-service-boundaries.md) |

## 🏚 Historical Design Documents

Original design documents from the pre-implementation phase. These describe the original design intent but the implementation may differ.

| Document | Description |
|----------|-------------|
| [01-architecture-design.md](./01-architecture-design.md) | Original architecture design |
| [02-api-protocol-specification.md](./02-api-protocol-specification.md) | Original API protocol spec |
| [03-database-schema.md](./03-database-schema.md) | Database schema design |
| [04-security-architecture.md](./04-security-architecture.md) | Security architecture |
| [05-mobile-app-technical-spec.md](./05-mobile-app-technical-spec.md) | Mobile app technical spec |
| [06-ui-ux-wireframes.md](./06-ui-ux-wireframes.md) | UI/UX wireframes |
| [07-mvp-scope-milestone-plan.md](./07-mvp-scope-milestone-plan.md) | MVP scope plan |
| [08-testing-qa-strategy.md](./08-testing-qa-strategy.md) | Testing & QA strategy |
| [09-infrastructure-cicd-plan.md](./09-infrastructure-cicd-plan.md) | Infrastructure & CI/CD plan |
| [10-privacy-compliance.md](./10-privacy-compliance.md) | Privacy & compliance |

## 📦 Engineering Archive

Historical engineering documents from the development and release cycles.

| Index | Description |
|-------|-------------|
| [ARCHIVE_INDEX.md](./archive/ARCHIVE_INDEX.md) | Complete index of all 53 archived documents |

Includes: Phase reports (0–5), RC-2 reports, final review audits (architecture, concurrency, cost, database, memory, operations, production certification, runtime, security, tech debt), internal audits (architecture compliance, dead code, event bus, module dependency, reality audit, security audit), and planning documents.

## 📊 Validation Reports

Test results, deployment reports, and release gate evaluations.

| Index | Description |
|-------|-------------|
| [REPORT_INDEX.md](./reports/REPORT_INDEX.md) | Complete index of all 46 reports |

Includes: Load tests (42K ops/sec), smoke tests, security validation, database validation, monitoring validation, operations validation, chaos tests, failure injection, deployment reports, and release gate documents.

---

> **Need help?** See [SUPPORT.md](../SUPPORT.md) for support channels.
> **Found a bug?** Open an issue via our [bug report template](../.github/ISSUE_TEMPLATE/bug_report.yml).
