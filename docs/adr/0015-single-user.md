# ADR-0015: Single-User v1

**Status:** Superseded by ADR-0017
**Date:** 2026-07-26

---

## Context

The daemon could support a single human user or multiple human users. The
decision affects authentication, session scoping, device ownership, group
routing, and permission models.

Multi-user support would require:
- User registration and authentication
- User-scoped sessions and devices
- Group routing (which user gets a session)
- Shared device handling
- Cross-user session visibility controls

This is a significant increase in scope for v1.

## Decision

AgentCall v1 will be single-user. The daemon serves one human user (identified
as `recipient_id: "me"`).

The data model is designed so multi-user can be added in v2 without a schema
redesign:
- All tables have a `user_id` column (defaulting to "me" in v1)
- Sessions have `recipient_id` for targeting specific users
- Devices have `user_id` for ownership
- Policies are per-agent (not per-user-per-agent), extensible in future

## Alternatives Considered

### Alternative 1: Multi-user v1

Build authentication, user management, and group routing from day one.

**Rejected because:** Doubles the v1 scope. Authentication requires token
management, session management, and user registration. These add no value
to the core communication flow.

### Alternative 2: Single-user with multi-device only

Users can have multiple devices, but only one user per daemon.

**Accepted.** This is the v1 design. Multi-device is supported. Multi-user
is deferred.

### Alternative 3: Anonymous multi-user

No authentication, users are identified by device. Anyone who can reach the
daemon can register.

**Rejected because:** No security boundary. Any device on the network could
register and receive sessions meant for another user.

## Consequences

### Positive

- No authentication system to design, build, or test
- Simple data model (no user table in v1 — identity is implied)
- Device registration is straightforward (no user login flow)
- Faster time to working software

### Negative

- Multi-user requires data migration (adding real `user_id` references, not
  just default values)
- Family/team use cases are not supported
- Permission model is per-agent, not per-user-per-agent

### Neutral

- Multi-user is additive, not a redesign
- The session model, delivery routing, and permission engine are unchanged
  by adding users
- Only the device registry and auth layer need multi-user support

## Compliance

v1 must not build user authentication, user management, or group routing.
Pull requests adding multi-user features will be deferred to v2.
