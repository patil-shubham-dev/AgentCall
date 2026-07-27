# ADR-0011: MCP as Sole Protocol

**Status:** Superseded by ADR-0017
**Date:** 2026-07-26
**Author(s):** Architecture Review

---

## Context

AgentCall needs a protocol for AI agents to request communication with humans.
The protocol must be open, standard, and supported by multiple AI providers.

At the time of this decision, each AI provider has its own proprietary
integration mechanism. Building a custom protocol would require every AI
provider to write a custom integration, which limits adoption.

Model Context Protocol (MCP) is an open standard originally developed by
Anthropic, now supported by OpenAI, Cursor, and other major AI platforms.
It uses JSON-RPC 2.0 for tool invocation and supports stdio and SSE transports.

## Decision

MCP will be the sole protocol for AI → AgentCall communication. The daemon will
expose tools via MCP and will not expose a separate REST/GraphQL API for AI
agents.

The daemon will expose a separate REST API for mobile device communication
(device registration, session queries, responses). This is not for AI agents
— it is for human-owned devices.

## Alternatives Considered

### Alternative 1: REST API with custom SDK

Provide a REST API and publish an SDK for each AI provider.

**Rejected because:** Requires maintaining SDKs for each provider. Publishers
must integrate our SDK. MCP is one integration that works everywhere.

### Alternative 2: Both MCP and REST

Support MCP and also expose REST for AI agents that cannot use MCP.

**Rejected because:** Splits the integration surface. Two protocols to
document, test, and maintain. Adds no value if MCP achieves wide adoption.
A REST adapter can be added later if needed without changing the daemon core.

### Alternative 3: gRPC + Protocol Buffers

Use gRPC for typed, streaming communication.

**Rejected because:** No AI agent supports gRPC tool invocation. Requires
custom clients. MCP is simpler and has existing ecosystem support.

## Consequences

### Positive

- Any MCP-compatible AI agent can use AgentCall without custom integration
- Single protocol to document, test, and maintain
- MCP tool definitions are self-documenting via `tools/list`
- MCP resource model maps naturally to session state

### Negative

- AI agents that cannot speak MCP cannot use AgentCall
- If MCP fails as a standard, the daemon needs a protocol adapter layer
- MCP is actively evolving; breaking spec changes may require daemon updates

### Neutral

- The daemon's REST API for mobile devices is separate from the MCP API
- Human clients (mobile app, CLI) use REST/WebSocket, not MCP

## Compliance

All AI-facing communication must use MCP. Pull requests introducing non-MCP
AI protocols will be rejected unless an ADR superseding this one is written.

## Notes

If MCP adoption fails, the daemon's tool implementations (session engine,
storage, delivery) are protocol-agnostic. Only `daemon/src/mcp/` would need
replacement. This is estimated at 2 days of work.
