# AgentCall Documentation

Start with the project README, then use this index for deeper design,
development, and operations material.

> **Core docs:** [README](../README.md) · [PRODUCT](../PRODUCT.md) · [PRD](./PRD.md) · [ARCHITECTURE](../ARCHITECTURE.md) · [MCP API](../MCP_API_SPEC.md)

---

## Development

| Document | Description |
|----------|-------------|
| [DEVELOPMENT_GUIDE.md](../DEVELOPMENT_GUIDE.md) | Local setup, services, and workflows |
| [CONTRIBUTING.md](../CONTRIBUTING.md) | Contribution rules and PR expectations |
| [CODE_STYLE.md](./CODE_STYLE.md) | TypeScript/Kotlin style conventions |
| [API_GUIDELINES.md](./API_GUIDELINES.md) | API design conventions |
| [ERROR_HANDLING.md](./ERROR_HANDLING.md) | Structured error responses |
| [LOGGING_GUIDE.md](./LOGGING_GUIDE.md) | Logging conventions |
| [TESTING_GUIDE.md](./TESTING_GUIDE.md) | Unit, integration, and device testing |
| [PERFORMANCE_GUIDELINES.md](./PERFORMANCE_GUIDELINES.md) | Performance practices |
| [SCALABILITY_GUIDE.md](./SCALABILITY_GUIDE.md) | Scaling considerations |
| [IMPLEMENTATION_RULES.md](./IMPLEMENTATION_RULES.md) | Mandatory engineering rules |
| [ARCHITECTURE_CHECKLIST.md](./ARCHITECTURE_CHECKLIST.md) | PR architecture checklist |
| [CODE_OWNERSHIP.md](./CODE_OWNERSHIP.md) | Ownership map |

## Architecture & Design

| Document | Description |
|----------|-------------|
| [ARCHITECTURE.md](../ARCHITECTURE.md) | High-level system architecture |
| [DESIGN.md](./DESIGN.md) | Product and interaction design |
| [ONGOING_CALL_STAGE_SPEC.md](./ONGOING_CALL_STAGE_SPEC.md) | Ongoing-call stage, composer, and send-dissolve implementation reference |
| [PERSISTENCE_ARCHITECTURE.md](./PERSISTENCE_ARCHITECTURE.md) | Persistence modes and data flow |
| [CURRENT_STACK.md](./CURRENT_STACK.md) | Technology stack |
| [AI_INTEGRATION.md](./AI_INTEGRATION.md) | AI provider integration |
| [MULTI_PROVIDER_PLAN.md](./MULTI_PROVIDER_PLAN.md) | Multi-provider strategy |
| [CHATGPT_INTEGRATION.md](./CHATGPT_INTEGRATION.md) | ChatGPT-specific integration |
| [SESSION_LIFECYCLE_POLICY.md](./SESSION_LIFECYCLE_POLICY.md) | Session lifecycle rules |
| [FREE_ARCHITECTURE.md](./FREE_ARCHITECTURE.md) | Free-tier deployment notes |
| [INFRASTRUCTURE.md](./INFRASTRUCTURE.md) | Infrastructure configuration |

## Voice Calls

| Document | Description |
|----------|-------------|
| [VOICE_CONVERSATION_IMPLEMENTATION_PLAN.md](./VOICE_CONVERSATION_IMPLEMENTATION_PLAN.md) | Voice conversation design |
| [VOICE_CONVERSATION_PROGRESS.md](./VOICE_CONVERSATION_PROGRESS.md) | Implementation progress |
| [VOICE_CONVERSATION_DEVICE_VALIDATION.md](./VOICE_CONVERSATION_DEVICE_VALIDATION.md) | On-device validation results |
| [REAL_CALL_IMPROVEMENTS.md](./REAL_CALL_IMPROVEMENTS.md) | Real-call reliability work |

## Security & Operations

| Document | Description |
|----------|-------------|
| [SECURITY.md](../SECURITY.md) | Security policy and reporting |
| [SECURITY_GUIDELINES.md](./SECURITY_GUIDELINES.md) | Hardening practices |
| [DISASTER_RECOVERY.md](./DISASTER_RECOVERY.md) | Recovery procedures |
| [GRAFANA_DASHBOARDS.md](./GRAFANA_DASHBOARDS.md) | Monitoring setup |
| [RISK_REGISTER.md](./RISK_REGISTER.md) | Risk register |
| [TECHNICAL_DEBT_REGISTER_v1.md](./TECHNICAL_DEBT_REGISTER_v1.md) | Technical debt ledger |

## Roadmap & Planning

| Document | Description |
|----------|-------------|
| [ROADMAP.md](../ROADMAP.md) | Public roadmap |
| [IMPLEMENTATION_ROADMAP.md](./IMPLEMENTATION_ROADMAP.md) | Engineering roadmap |
| [IMPROVEMENT_BACKLOG.md](./IMPROVEMENT_BACKLOG.md) | Improvement backlog |
| [PRD.md](./PRD.md) | Product requirements |

## Architecture Decision Records

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
