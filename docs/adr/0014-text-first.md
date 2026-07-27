# ADR-0014: Text-First Communication Model

**Status:** Superseded by ADR-0017
**Date:** 2026-07-26

---

## Context

AgentCall supports multiple communication capabilities (notify, message,
decision, approval, confirmation, callback). These capabilities could be
delivered via various channels: text, voice, video, file transfer, screen
sharing.

Building all channels in v1 would delay shipping by months. The architecture
must choose which channels to support initially and how to add more later.

## Decision

AgentCall v1 will be text-first. All communication capabilities will be
delivered as structured text messages.

- `notify` → text notification
- `message` → text chat
- `decision` → text options + text choice
- `approval` → text description + approve/reject buttons
- `confirmation` → text acknowledgment
- `callback` → text scheduling

Voice, video, file sharing, and other channels are future work. The
capability model is designed so that adding a new channel does not change
the daemon — the capability is a UI hint, and the channel is a delivery
concern.

## Alternatives Considered

### Alternative 1: Voice-first

Build voice calling as the primary channel, matching the VoiceBridge v1 model.

**Rejected because:** Voice requires WebRTC, STUN/TURN, platform TTS/STT,
foreground services, and wake locks. This is 10x the complexity of text for
a v1. The ARCHITECTURE_STRESS_TEST.md identified this as the primary source
of complexity in the old architecture.

### Alternative 2: All channels in v1

Build text, voice, video, and file sharing simultaneously.

**Rejected because:** Would take 6+ months to ship. The architecture should
prove the communication model works with the simplest channel before adding
complexity.

### Alternative 3: Protocol-defined channels

Define channels as a core protocol concern with content negotiation.

**Rejected because:** Adds protocol complexity for channels that don't exist
yet. YAGNI. Channels can be added as delivery-layer concerns without changing
the session model.

## Consequences

### Positive

- Fastest path to working software
- Text is the simplest channel to build, test, and debug
- All capabilities work with text — no capability is voice-only
- Push notifications are inherently text-based

### Negative

- Users may expect voice calling from a "communication" product
- Some capabilities (approval, decision) are more natural with voice
- Future voice support requires adding a new channel, not just enabling it

### Neutral

- The session model is channel-agnostic
- Adding voice in v2 does not require changing the daemon core —
  only adding a new delivery channel + Android UI component

## Compliance

v1 must not include voice, video, or file transfer delivery. Pull requests
adding non-text delivery channels will be deferred to v2 planning.
