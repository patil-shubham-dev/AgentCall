# ADR-0016: Binary Permission Model for v1

**Status:** Superseded by ADR-0017
**Date:** 2026-07-26

---

## Context

The permission model controls which AI agents can communicate with the human,
when, and under what conditions. The initial design included four trust levels,
per-agent rate limits, per-agent quiet hours, emergency override, and a consent
flow for untrusted agents.

This is a full policy engine. For v1, it adds complexity before the core
communication flow is proven.

## Decision

v1 will use a binary permission model:

- **allowed** (boolean): Agent can or cannot communicate
- **trust_level** (1 or 2): Normal or Trusted
  - Trust level 1: Normal delivery, respects quiet hours
  - Trust level 2: Can interrupt quiet hours for urgent requests

Global quiet hours (start/end time) apply to all agents. No per-agent
overrides. No rate limits. No consent flow. No emergency override.

The full policy engine (rate limits, per-agent quiet hours, consent flow,
trust levels 0/3/4, emergency override) will be added in v2 if the user
demand for granular control materializes.

## Alternatives Considered

### Alternative 1: Full policy engine in v1

Four trust levels, per-agent policies, rate limits, emergency override,
consent flow, notification preferences.

**Rejected because:** The ARCHITECTURE_STRESS_TEST.md identified this as
over-engineering for v1. The delivery path already depends on policy checks,
so adding policy fields later requires only schema additions and evaluation
logic changes — no delivery path changes.

### Alternative 2: No permissions — all agents allowed

Any agent with a valid API key can reach the human at any time.

**Rejected because:** No security boundary. An agent could spam the user
at 3 AM. Even v1 needs basic protection.

### Alternative 3: Allow/block with time-based suppression

Only allow/block list, with quiet hours enforced globally.

**Accepted.** This is the v1 design.

## Consequences

### Positive

- Simple implementation — policy evaluation is a few lines
- Easy for users to understand (allowed/blocked)
- Global quiet hours provide basic protection
- Trust level 2 provides an escape hatch for urgent requests

### Negative

- No granular control — all allowed agents are treated equally
- No rate limiting — a misconfigured agent could send many requests
- No consent flow — new agents can contact the user immediately
- Users who want fine-grained control must wait for v2

### Neutral

- Adding rate limits, per-agent policies, or consent flow in v2 requires
  only: 1) new columns in the agents/policies table, 2) additional evaluation
  logic in the policy engine, 3) UI in the Android app
- The policy engine interface (a function that takes session + agent and
  returns a decision) does not change

## Compliance

v1 policy engine must implement: `allowed` boolean, `trust_level` (1 or 2),
global quiet hours. Any additional policy fields must be v2.
