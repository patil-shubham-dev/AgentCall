# ADR-0013: SSE as Default MCP Transport

**Status:** Superseded by ADR-0017
**Date:** 2026-07-26

---

## Context

MCP supports two transports: stdio (the AI agent spawns the daemon as a child
process and communicates over stdin/stdout) and SSE (the daemon runs as an HTTP
server, the AI agent connects via HTTP POST with SSE for responses).

The v2 architecture was originally designed with stdio as default and SSE as
optional. Further analysis revealed that stdio has fundamental limitations for
the multi-agent use case.

## Decision

SSE will be the default transport. stdio will be supported for testing and
embedded single-agent scenarios.

Rationale:

1. **Multi-agent support**: With stdio, each AI agent spawns its own daemon
   process with its own SQLite database. Three AI agents = three databases.
   Device registrations, sessions, and policies are not shared. This breaks
   the core promise of AgentCall (one daemon, many AI agents).

2. **Daemon lifecycle**: With stdio, the daemon's lifecycle is tied to the
   AI agent's lifecycle. If Claude Desktop closes, the daemon dies. In-flight
   sessions are lost.

3. **SSE solves both**: One daemon process, multiple AI agent connections.
   Daemon survives any single AI agent disconnecting.

## Alternatives Considered

### Alternative 1: stdio default, SSE optional

Original design. stdio for simplicity and security, SSE for remote agents.

**Rejected because:** stdio does not support multi-agent. The primary use case
(multiple AI agents → one human) fails with stdio.

### Alternative 2: stdio with shared state via Unix socket

Each daemon connects to a shared state process via Unix socket.

**Rejected because:** Reintroduces the client-server architecture that SSE
already solves. More complex than SSE.

### Alternative 3: SSE only, no stdio

Remove stdio entirely.

**Rejected because:** stdio is valuable for testing (one-shot MCP calls) and
for CI/CD environments where no network server is desired.

## Consequences

### Positive

- Single daemon supports multiple AI agents naturally
- Daemon lifecycle is independent of any single AI agent
- SSE transport can be secured independently (bind to localhost, reverse proxy)
- HTTP health endpoint is available by default

### Negative

- SSE requires an open HTTP port (security consideration)
- SSE transport is more complex than stdio
- Some users may need to configure firewalls
- stdio users must explicitly configure it

### Neutral

- The MCP transport abstraction layer makes switching between SSE and stdio
  a configuration change, not a code change
- Both transports use the same tool implementations

## Compliance

New installations should default to SSE. Documentation should recommend SSE
for multi-agent setups and stdio for testing.
