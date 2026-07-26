# ADR-0001: System Philosophy

**Status:** Accepted
**Date:** 2026-07-26

---

## Context

The AgentCall platform needed a clear philosophical foundation to guide all architectural decisions. Without it, developers would make inconsistent choices about where AgentCall's responsibilities begin and end, particularly around AI integration.

## Decision

Adopt the following philosophy as the non-negotiable foundation:

- **AI owns intelligence** (reasoning, planning, memory, context, task execution)
- **AgentCall owns communication** (voice, notifications, presence, routing, callbacks, devices, conversation transport)
- **Humans own decisions** (the final say always rests with the human)

AgentCall must never:
- Rewrite prompts
- Perform reasoning
- Enrich AI output
- Generate summaries
- Own conversation context

AgentCall may only:
- Store communication metadata
- Forward messages between AI and human
- Authenticate participants
- Deliver notifications and calls

## Alternatives Considered

- **Thin proxy**: AgentCall as a pure pass-through with no state. Rejected because presence, notifications, and device routing require state.
- **AI platform**: Building AI features into AgentCall. Rejected because it creates vendor lock-in and scope creep.
- **Full platform**: Including memory, prompts, and task management. Rejected because it duplicates existing AI capabilities.

## Consequences

**Positive:**
- Clear service boundaries enforced
- No duplication of AI-provider functionality
- Easy to add new AI providers without modifying core
- Simple mental model for contributors

**Negative:**
- Cannot offer AI-related features (no competitive advantage from AI integration)
- Requires AI providers to handle their own conversation logic
- Some users may want integrated AI features

## Tradeoffs

- We lose the ability to offer AI-powered features but gain provider agnosticism
- We trade short-term feature velocity for long-term architectural clarity

## Future Work

- Explore whether "transcript summarization" could be offered as an optional, user-opt-in communication feature (not AI enrichment per se, but a value-add that happens to use an LLM)
- Define what "metadata" means precisely — where is the line between context needed for routing vs. AI enrichment
