# ADR-0009: Data Ownership

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

The SYSTEM_ARCHITECTURE.md defines strict data ownership rules, but the boundaries between what AgentCall may and may not store, process, and retain need precise definition to avoid scope creep.

## Decision

### What AgentCall May Do

| Operation | Examples | Storage |
|-----------|----------|---------|
| **Store** communication metadata | Call timestamps, duration, participants, status | PostgreSQL |
| **Forward** messages between AI and human | Raw message text (unmodified) | Transient (deleted after delivery) |
| **Authenticate** users, providers, devices | JWT claims, API key hashes, OAuth tokens | PostgreSQL + Redis |
| **Deliver** calls, notifications, events | WebSocket messages, push notifications, SSE | Transient |

### What AgentCall Must Never Do

| Operation | Why | Example of Violation |
|-----------|-----|---------------------|
| **Rewrite prompts** | Belongs to AI | Modifying AI's message before delivery |
| **Perform reasoning** | Belongs to AI | Interpreting call context, making decisions |
| **Enrich AI output** | Belongs to AI | Adding emotion tags, filler words, breathing pauses |
| **Generate summaries** | Belongs to AI | Creating transcript summaries for the AI |
| **Own conversation context** | Belongs to AI | Storing chat history for AI use |

### Gray Areas

| Activity | Decision | Rationale |
|----------|----------|-----------|
| Transcript storage for user review | ✅ Allowed | User-facing feature, not AI enrichment |
| Call quality metrics | ✅ Allowed | Operational data, not AI intelligence |
| AI context caching for performance | ❌ Not allowed | Would make AgentCall a memory system |
| Message filtering (spam/abuse) | ⚠️ Allowed only if provider-independent | Required for platform safety |

### Retention Policy

| Data Type | Retention | Rationale |
|-----------|-----------|-----------|
| Call metadata | 90 days | Operational needs |
| Call quality metrics | 180 days (aggregated) | Service improvement |
| Auth tokens | Until revoked | Security |
| Push tokens | Until device removed | Delivery |
| Transcribed messages | Deleted after delivery (default) | Privacy |
| Recordings (opt-in) | 30 days | User feature |

## Alternatives Considered

- **No data ownership limits**: Would lead to feature creep and violation of PRODUCT_VISION.
- **Stricter limits (no storage at all)**: Makes call history, analytics, and quality monitoring impossible.

## Consequences

**Positive:**
- Clear boundaries prevent scope creep
- Privacy-by-default is enforced architecturally
- AI providers trust the platform with their conversations
- Simple mental model for all contributors

**Negative:**
- Some features (AI-powered analytics) are excluded
- Gray areas require judgment calls
- Occasionally need to say "no" to valuable features

## Tradeoffs

- Strict boundaries vs. feature velocity: boundaries win for architectural integrity

## Future Work

- Formalize the gray-area review process
- Automated lint rules for data ownership violations
- User data export implementation
- Data retention enforcement cron jobs
