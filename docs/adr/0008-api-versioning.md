# ADR-0008: API Versioning

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

AgentCall exposes multiple API surfaces (REST, MCP, WebSocket, SSE) that evolve over time. Without a versioning strategy, changes break existing clients. The current system has no versioning — the REST API is at `/api/v1/` but there is no version negotiation, no deprecation policy, and no migration path.

## Decision

### REST API: URL Path Versioning

```
/api/v1/                 # Current stable version
/api/v2/                 # Next version (breaking changes)
```

- Version is part of the URL path
- New endpoints may be added to v1 without breaking changes
- Breaking changes require a new major version
- Previous version is deprecated with 6-month notice

### MCP Tools: Tool Name Versioning

Tools are versioned by name convention:
- `create_call` (v1, current)
- `create_call_v2` (future, if breaking change needed)

Alternatively, an MCP tool `version` field can be added:
```json
{
  "toolName": "create_call",
  "version": 2
}
```

### WebSocket Events: Event Type Versioning

Event types include version in the name:
- `call.created.v1`
- `presence.changed.v1`

Version increment when payload schema changes.

### SSE Events: Same as WebSocket

### Deprecation Policy

1. Mark endpoint/tool as `deprecated` in documentation
2. Add `Warning: deprecation` header to responses
3. Maintain backward compatibility for 6 months
4. After 6 months, remove or return error

## Alternatives Considered

- **Header versioning** (Accept: application/vnd.agentcall.v2+json): More RESTful but harder for MCP/WebSocket.
- **Query parameter versioning** (`?v=2`): Easy to implement but not standard for REST.
- **No versioning, evolve in-place**: Simple but breaks clients. Rejected for production.

## Consequences

**Positive:**
- Clear version contract for API consumers
- Clients can migrate at their own pace
- Deprecation policy provides predictability
- Multiple versions can run simultaneously

**Negative:**
- Maintenance burden of supporting multiple versions
- URL path versioning is not truly RESTful (violates HATEOAS)
- MCP tool versioning is non-standard

## Tradeoffs

- URL path vs. header versioning: URL is more visible and easier to debug, headers are more RESTful
- Breaking change vs. additive change: err on the side of additive (new field, not changed field)

## Future Work

- Automate deprecation header injection
- API version usage analytics
- Sunset policy enforcement (auto-remove after 6 months)
- OpenAPI spec per version
