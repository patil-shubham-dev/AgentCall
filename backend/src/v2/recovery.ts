import { createHash } from 'node:crypto';
import { logger } from '../common/logger.js';
import { InvalidTransitionError, transition } from './call-fsm.js';
import type { EventLogStore } from './event-log.js';
import { V2_EVENTS, EVENT_PAYLOAD_SCHEMAS } from './events.js';
import type { V2Event } from './events.js';
import type { TranscriptSegment, V2CallRecord } from './call-service.js';

/**
 * RecoveryManager v2 (roadmap M3): rebuilds in-memory call aggregates from the
 * durable event log after a worker restart (event-sourcing-lite, R1). RPO 0 —
 * the outbox write path guarantees every settled command's events are in the
 * log before it returns, so replay reconstructs exactly the pre-crash truth.
 *
 * What replay restores vs. what intentionally does not:
 *  - restores: state (FSM-validated), transcript, transcriptSeq, aiWaiting
 *    (from turn.lease), an in-flight user utterance (from speech.started/
 *    partial/final — the transcript itself only settles on finalize),
 *    connectedAt/endedAt/durationMs/result, and the silence-policy re-arm
 *    budget (from the last completed AI turn).
 *  - does not restore: live TTS streams (a crashed stream is dead; message.*
 *    events carry no aggregate state), clientMessageIds (HTTP-level replay
 *    still works through the durable IdempotencyStore), and the silence
 *    escalation counter (re-armed from 0 with the remaining budget).
 */

export interface RehydratedCall {
  call: V2CallRecord;
  /** Silence-policy re-arm candidate: when the last AI turn completed. */
  silence: { afterMs: number; armedAt: number } | null;
}

interface OpenUtteranceState {
  utterance_id: string;
  text: string;
  started_at: string;
  last_partial_at: string;
  language?: string;
}

/**
 * Timestamps are generated server-side (`new Date().toISOString()`), but a
 * corrupt row must never poison the silence timer: NaN `armedAt` would make
 * `setTimeout(fn, NaN)` fire immediately. Fall back to now on garbage.
 */
function parseIsoSafe(value: string | undefined): number {
  const parsed = value === undefined ? NaN : Date.parse(value);
  return Number.isNaN(parsed) ? Date.now() : parsed;
}

/**
 * Folds one call's event log into a V2CallRecord. Corruption tolerance: a
 * lifecycle transition the FSM rejects is logged and skipped, and a malformed
 * event payload is logged and skipped in the same way (boot must never fail on
 * a bad row — a single corrupt event must not take the whole call down, and
 * one corrupt call must not take the whole boot down); a log with no
 * `call.created` is not rehydratable.
 */
export async function rehydrate(log: EventLogStore, callId: string): Promise<RehydratedCall | null> {
  const events = await log.list(callId);
  if (events.length === 0) return null;

  let call: V2CallRecord | null = null;
  let open: OpenUtteranceState | null = null;
  let silence: RehydratedCall['silence'] = null;

  const apply = (command: Parameters<typeof transition>[1]): void => {
    if (!call) return;
    try {
      call.state = transition(call.state, command);
    } catch (err) {
      if (err instanceof InvalidTransitionError) {
        logger.warn({ callId, from: err.from, command: err.command }, '[v2.recovery] FSM-invalid lifecycle event skipped');
        return;
      }
      throw err;
    }
  };

  for (const event of events) {
    const p = event.payload;
    try {
      switch (event.type) {
      case V2_EVENTS.CALL_CREATED:
        call = {
          id: callId,
          state: 'creating',
          userId: p.user_id as string,
          agentId: p.agent_id as string,
          ...(p.reason !== undefined ? { reason: p.reason as string } : {}),
          ...(p.summary !== undefined ? { summary: p.summary as string } : {}),
          ...(p.context !== undefined ? { context: p.context as V2CallRecord['context'] } : {}),
          ...(p.media !== undefined ? { media: p.media as V2CallRecord['media'] } : {}),
          ...(p.policy !== undefined ? { policy: p.policy as V2CallRecord['policy'] } : {}),
          ...(p.priority !== undefined ? { priority: p.priority as V2CallRecord['priority'] } : {}),
          transcript: [],
          transcriptSeq: 0,
          clientMessageIds: new Map(),
          activeTurn: null,
          openUtterance: null,
          tts: null,
          aiWaiting: false,
          createdAt: event.occurred_at,
          lastActivityAt: event.occurred_at,
        };
        break;

      case V2_EVENTS.CALL_RINGING:
        apply('ring');
        break;

      case V2_EVENTS.CALL_ANSWER_REQUESTED:
        apply('answer');
        break;

      case V2_EVENTS.CALL_CONNECTED:
        apply('connect');
        if (call) call.connectedAt = (p.connected_at as string | undefined) ?? event.occurred_at;
        break;

      case V2_EVENTS.CALL_ENDING:
        if (call) silence = null; // hangup in progress — silence is moot
        break;

      case V2_EVENTS.CALL_COMPLETED:
        apply('complete');
        if (call) {
          call.endedAt = event.occurred_at;
          call.durationMs = p.duration_ms as number | undefined;
          call.result = p.outcome !== undefined ? { outcome: p.outcome as Record<string, unknown> } : undefined;
          silence = null;
        }
        break;

      case V2_EVENTS.CALL_FAILED:
        apply('fail');
        if (call) {
          call.endedAt = event.occurred_at;
          silence = null;
        }
        break;

      case V2_EVENTS.CALL_ARCHIVED:
        return null; // call was archived — the aggregate is gone by design

      case V2_EVENTS.MESSAGE_COMPLETED:
        // Silence-policy trigger (armSilencePolicy runs right after the turn
        // settles). Tracking message.completed instead of turn.ended also
        // covers a crash between the two emits.
        if (call && call.policy?.silence_after_ms && call.policy.silence_after_ms > 0) {
          silence = { afterMs: call.policy.silence_after_ms, armedAt: parseIsoSafe(event.occurred_at) };
        }
        break;

      case V2_EVENTS.TURN_LEASE:
        if (call) call.aiWaiting = (p.ai_wait_status as { active: boolean }).active;
        break;

      case V2_EVENTS.TURN_ENDED: {
        if (!call) break;
        const turnType = (p as { turn_type: 'ai' | 'user' }).turn_type;
        call.activeTurn = null;
        if (turnType === 'user') {
          open = null;
          call.openUtterance = null;
          silence = null; // human spoke — policy cleared
        }
        break;
      }

      case V2_EVENTS.TURN_CANCELLED:
        if (call) {
          call.activeTurn = null;
          silence = null; // ai_stop clears the policy
        }
        break;

      case V2_EVENTS.SPEECH_STARTED:
        open = {
          utterance_id: p.utterance_id as string,
          text: '',
          started_at: event.occurred_at,
          last_partial_at: event.occurred_at,
        };
        if (call) {
          call.openUtterance = open;
          call.activeTurn = { type: 'user', utterance_id: open.utterance_id, started_at: open.started_at };
          silence = null; // barge-in clears the policy
        }
        break;

      case V2_EVENTS.SPEECH_PARTIAL:
        if (open && open.utterance_id === p.utterance_id) {
          open.text = p.text as string;
          open.last_partial_at = event.occurred_at;
        }
        break;

      case V2_EVENTS.SPEECH_FINAL:
        if (open && open.utterance_id === p.utterance_id) {
          open.text = p.text as string;
        }
        break;

      case V2_EVENTS.SPEECH_FAILED:
        open = null;
        if (call) {
          call.openUtterance = null;
          call.activeTurn = null;
        }
        break;

      case V2_EVENTS.USER_INTERRUPTED:
        if (call) silence = null;
        break;

      case V2_EVENTS.TRANSCRIPT_UPDATED: {
        if (!call) break;
        const segment = (p as { segment: Omit<TranscriptSegment, 'createdAt'> }).segment;
        const full: TranscriptSegment = {
          ...segment,
          createdAt: event.occurred_at,
        };
        call.transcript.push(full);
        call.transcriptSeq = Math.max(call.transcriptSeq, full.seq);
        break;
      }

      case V2_EVENTS.MESSAGE_QUEUED:
      case V2_EVENTS.MESSAGE_STARTED:
      case V2_EVENTS.MESSAGE_FAILED:
        break; // message lifecycle carries no aggregate state (see header note)

      case V2_EVENTS.TRANSCRIPT_PARTIAL_CLEARED:
      case V2_EVENTS.SILENCE_DETECTED:
      case V2_EVENTS.CALL_NOACTIVITY:
      case V2_EVENTS.AUDIT_EVENT:
        break; // advisory / observability - no aggregate state

      default:
        logger.warn({ callId, type: event.type }, '[v2.recovery] unknown event type skipped');
      }
    } catch (err) {
      // One corrupt event (malformed payload, unparseable timestamp) is
      // skipped, never fatal: boot and the rest of the call survive it.
      logger.warn({ callId, type: event.type, err }, '[v2.recovery] corrupt event payload skipped');
    }
  }

  if (!call) {
    logger.warn({ callId }, '[v2.recovery] log has no call.created — not rehydratable');
    return null;
  }
  call.lastActivityAt = (events[events.length - 1] as V2Event).occurred_at;
  return { call, silence };
}

// ---- Event log verifier (roadmap R1: "verifier job compares counts/hashes") -

export interface CallVerification {
  callId: string;
  count: number;
  firstSeq: number;
  lastSeq: number;
  /** Sequences are exactly 1..count with no gaps, duplicates, or reordering. */
  contiguous: boolean;
  /** Events whose id was seen earlier in the same call log. */
  duplicateIds: number;
  /** Events whose payload fails its registered schema (emit-time log-and-continue surfaced). */
  payloadViolations: number;
  corrupt: boolean;
  /** sha256 of the joined event ids — lets two instances compare logs. */
  lastEventHash: string;
}

export function verifyCallLog(callId: string, events: V2Event[]): CallVerification {
  const ids = new Set<string>();
  let duplicateIds = 0;
  let payloadViolations = 0;
  for (const event of events) {
    if (ids.has(event.id)) duplicateIds++;
    else ids.add(event.id);
    const schema = EVENT_PAYLOAD_SCHEMAS[event.type];
    if (schema && !schema.safeParse(event.payload).success) payloadViolations++;
  }
  const count = events.length;
  const firstSeq = events[0]?.sequence ?? 0;
  const lastSeq = events[count - 1]?.sequence ?? 0;
  const contiguous =
    count === 0 ||
    (firstSeq === 1 && lastSeq === count && events.every((event, i) => event.sequence === i + 1));
  const lastEventHash = createHash('sha256')
    .update(events.map((e) => e.id).join(','))
    .digest('hex');
  return {
    callId,
    count,
    firstSeq,
    lastSeq,
    contiguous,
    duplicateIds,
    payloadViolations,
    corrupt: !contiguous || duplicateIds > 0 || payloadViolations > 0,
    lastEventHash,
  };
}

export interface VerificationReport {
  calls: CallVerification[];
  totalEvents: number;
  corruptCalls: number;
  payloadViolations: number;
  durationMs: number;
}

export class EventLogVerifier {
  /**
   * Walks the log in bounded keyset batches (recovery.ts header: no single
   * statement may scan the whole table — the per-connection statement_timeout
   * would kill it on a large log).
   */
  async verify(log: EventLogStore): Promise<VerificationReport> {
    const started = Date.now();
    const BATCH = 500;
    const calls: CallVerification[] = [];
    let totalEvents = 0;
    let corruptCalls = 0;
    let payloadViolations = 0;
    let afterCallId: string | undefined;
    for (;;) {
      const page = await log.callIds(BATCH, afterCallId);
      if (page.length === 0) break;
      for (const callId of page) {
        const events = await log.list(callId);
        const verification = verifyCallLog(callId, events);
        calls.push(verification);
        totalEvents += verification.count;
        payloadViolations += verification.payloadViolations;
        if (verification.corrupt) corruptCalls++;
      }
      afterCallId = page[page.length - 1];
    }
    return {
      calls,
      totalEvents,
      corruptCalls,
      payloadViolations,
      durationMs: Date.now() - started,
    };
  }
}
