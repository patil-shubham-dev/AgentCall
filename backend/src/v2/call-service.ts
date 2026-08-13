import { logger } from '../common/logger.js';
import type { EventPlane } from './event-plane.js';
import { V2_EVENTS } from './events.js';
import { uuidV7 } from './ids.js';
import { isOpenState, transition, InvalidTransitionError } from './call-fsm.js';
import type { V2CallState } from './call-fsm.js';
import { CallNotFoundError, ForbiddenError, ValidationError } from './errors.js';
import type { IdempotencyStore } from './idempotency.js';
import type { V2Actor } from './events.js';

/** Who is acting (audit + ownership). 'service' bypasses ownership checks. */
export interface V2ActorInput {
  type: V2Actor['type'] | 'service';
  identity?: string;
}

export interface TranscriptSegment {
  seq: number;
  role: 'user' | 'ai';
  type: string;
  text: string;
  start_ms?: number;
  end_ms?: number;
  confidence?: number;
  createdAt: string;
}

export interface V2Policy {
  ring_timeout_ms?: number;
  silence_after_ms?: number;
  no_answer_action?: 'keep_ringing' | 'fail' | 'voicemail';
}

/** Schema version stamped on every emitted event (event-model §5). */
export const V2_EVENT_VERSION = 1;

export interface V2CallRecord {
  id: string;
  state: V2CallState;
  userId: string;
  agentId: string;
  reason?: string;
  summary?: string;
  context?: { task_id?: string; summary?: string; options?: string[]; custom?: Record<string, unknown> };
  media?: { transport?: string; stt?: { provider?: string; language?: string }; tts?: { provider?: string; voice?: string } };
  policy?: V2Policy;
  priority?: 'low' | 'normal' | 'high' | 'urgent';
  transcript: TranscriptSegment[];
  transcriptSeq: number;
  /** client_message_id -> utterance_id (user-text idempotency). */
  clientMessageIds: Map<string, string>;
  result?: { outcome?: Record<string, unknown>; note?: string };
  createdAt: string;
  lastActivityAt: string;
  connectedAt?: string;
  endedAt?: string;
  durationMs?: number;
}

export interface CreateCallInput {
  user_id: string;
  agent_id: string;
  reason?: string;
  summary?: string;
  context?: V2CallRecord['context'];
  media?: V2CallRecord['media'];
  policy?: V2Policy;
  priority?: V2CallRecord['priority'];
}

export interface SendMessageInput {
  content: string;
  tts?: { provider?: string; voice?: string };
  reply_to?: string;
}

export interface SubmitUtteranceInput {
  text: string;
  client_message_id?: string;
  language?: string;
}

export interface HangupInput {
  outcome?: Record<string, unknown>;
  note?: string;
}

/** How many consecutive silence.detected events before call.noactivity. */
const MAX_SILENCE_ESCALATIONS = 3;

const systemActor = (): V2ActorInput => ({ type: 'system' });

/**
 * v2 session engine (roadmap M1 / migration Phase 1). One aggregate per call,
 * driven through the validated FSM; every transition and domain fact is
 * emitted as an event through the EventPlane (outbox write path). M1 is
 * in-memory; persistence lands behind the plane/log at M3.
 */
export class V2CallService {
  private readonly calls = new Map<string, V2CallRecord>();
  private readonly silence = new Map<string, { count: number; timer: NodeJS.Timeout | null }>();

  constructor(
    readonly plane: EventPlane,
    readonly idempotency: IdempotencyStore,
  ) {}

  // ---- emit -----------------------------------------------------------------

  private async emit(
    callId: string,
    type: string,
    actor: V2ActorInput,
    payload: Record<string, unknown>,
  ): Promise<void> {
    await this.plane.publish(callId, {
      id: uuidV7(),
      type,
      version: V2_EVENT_VERSION,
      call_id: callId,
      correlation_id: callId,
      occurred_at: new Date().toISOString(),
      // 'service' is an auth role, not an event actor — service-driven facts
      // are recorded as system events (event-model §1 actor enum).
      actor: { type: actor.type === 'service' ? 'system' : actor.type, ...(actor.identity ? { identity: actor.identity } : {}) },
      payload,
    });
  }

  /**
   * Detached emit for timer-driven (advisory) events only. Commands must
   * AWAIT every emit: the outbox contract is that a command returns only
   * after its events are durably recorded, and late subscribers replay a
   * settled log in exact order.
   */
  private emitDetached(
    callId: string,
    type: string,
    actor: V2ActorInput,
    payload: Record<string, unknown>,
  ): void {
    this.emit(callId, type, actor, payload).catch((err) => {
      logger.error({ err, callId, type }, '[v2.call-service] detached event emit failed');
    });
  }

  // ---- access control -------------------------------------------------------

  private assertAccess(call: V2CallRecord, actor: V2ActorInput): void {
    if (actor.type === 'service') return;
    if (actor.type === 'ai') {
      if (call.agentId !== actor.identity) {
        throw new ForbiddenError(`Call ${call.id} belongs to a different AI identity`);
      }
      return;
    }
    if (actor.type === 'user' || actor.type === 'device') {
      if (call.userId !== actor.identity) {
        throw new ForbiddenError(`Call ${call.id} belongs to a different user`);
      }
      return;
    }
    // 'system' actors never issue user commands.
    throw new ForbiddenError(`Actor type ${actor.type} cannot command a call`);
  }

  private getCall(callId: string): V2CallRecord {
    const call = this.calls.get(callId);
    if (!call) throw new CallNotFoundError(callId);
    return call;
  }

  private assertOpen(call: V2CallRecord): void {
    if (!isOpenState(call.state)) {
      throw new InvalidTransitionError(call.state, 'message');
    }
  }

  // ---- commands -------------------------------------------------------------

  async createCall(input: CreateCallInput, actor: V2ActorInput): Promise<V2CallRecord> {
    if (!input.user_id || !input.agent_id) {
      throw new ValidationError('user_id and agent_id are required');
    }
    // Ownership: an AI identity may only create calls it owns.
    if (actor.type === 'ai' && actor.identity && input.agent_id !== actor.identity) {
      throw new ForbiddenError(`AI identity ${actor.identity} cannot create calls for ${input.agent_id}`);
    }

    const callId = uuidV7();
    const call: V2CallRecord = {
      id: callId,
      state: 'creating',
      userId: input.user_id,
      agentId: input.agent_id,
      reason: input.reason,
      summary: input.summary,
      context: input.context,
      media: input.media,
      policy: input.policy,
      priority: input.priority,
      transcript: [],
      transcriptSeq: 0,
      clientMessageIds: new Map(),
      createdAt: new Date().toISOString(),
      lastActivityAt: new Date().toISOString(),
    };
    this.calls.set(callId, call);

    await this.emit(callId, V2_EVENTS.CALL_CREATED, actor, {
      user_id: call.userId,
      agent_id: call.agentId,
      ...(call.reason ? { reason: call.reason } : {}),
      ...(call.summary ? { summary: call.summary } : {}),
      ...(call.context ? { context: call.context } : {}),
      ...(call.media ? { media: call.media } : {}),
      ...(call.policy ? { policy: call.policy } : {}),
      ...(call.priority ? { priority: call.priority } : {}),
    });

    // Persist → notify the device (ring). M1 has no media gateway; the ring
    // event is the contract that M2's transport will consume.
    transition(call.state, 'ring');
    call.state = 'ringing';
    await this.emit(callId, V2_EVENTS.CALL_RINGING, systemActor(), {
      provider: call.media?.transport,
    });

    logger.info({ callId, userId: call.userId, agentId: call.agentId }, '[v2] call created');
    return call;
  }

  async answerCall(callId: string, provider: string | undefined, actor: V2ActorInput): Promise<V2CallRecord> {
    const call = this.getCall(callId);
    this.assertAccess(call, actor);

    // Idempotent re-answer (phone retries).
    if (call.state === 'connected' || call.state === 'connecting') return call;

    transition(call.state, 'answer');
    call.state = 'connecting';
    await this.emit(callId, V2_EVENTS.CALL_ANSWER_REQUESTED, actor, {
      provider,
    });

    // M1: connecting is transient — media establishment completes immediately
    // (the M2 media gateway will split these into distinct steps).
    transition(call.state, 'connect');
    call.state = 'connected';
    call.connectedAt = new Date().toISOString();
    await this.emit(callId, V2_EVENTS.CALL_CONNECTED, systemActor(), {
      connected_at: call.connectedAt,
      ...(provider ? { provider } : {}),
    });

    logger.info({ callId }, '[v2] call answered');
    return call;
  }

  async sendMessage(callId: string, input: SendMessageInput, actor: V2ActorInput): Promise<{ message_id: string }> {
    if (!input.content || input.content.trim().length === 0) {
      throw new ValidationError('content is required');
    }
    const call = this.getCall(callId);
    this.assertAccess(call, actor);
    this.assertOpen(call);
    transition(call.state, 'message');

    const messageId = uuidV7();
    await this.emit(callId, V2_EVENTS.MESSAGE_QUEUED, actor, {
      message_id: messageId,
      content: input.content,
      ...(input.tts ? { tts: input.tts } : {}),
      ...(input.reply_to ? { reply_to: input.reply_to } : {}),
    });
    await this.emit(callId, V2_EVENTS.MESSAGE_STARTED, systemActor(), {
      message_id: messageId,
      tts_provider: input.tts?.provider,
      streamed: true,
    });
    await this.emit(callId, V2_EVENTS.MESSAGE_COMPLETED, systemActor(), {
      message_id: messageId,
      chars_spoken: input.content.length,
      audio_bytes: 0,
    });

    await this.appendTranscript(callId, {
      role: 'ai',
      type: 'text',
      text: input.content,
      createdAt: new Date().toISOString(),
    });
    await this.emit(callId, V2_EVENTS.TURN_ENDED, systemActor(), {
      turn_type: 'ai',
      message_id: messageId,
    });

    // Advisory silence policy: after an AI turn, no human activity for
    // silence_after_ms escalates silence.detected → call.noactivity (R7).
    this.armSilencePolicy(callId);

    logger.info({ callId, messageId }, '[v2] message sent');
    return { message_id: messageId };
  }

  async submitUtterance(callId: string, input: SubmitUtteranceInput, actor: V2ActorInput): Promise<{ utterance_id: string; text: string; idempotent: boolean }> {
    if (!input.text || input.text.trim().length === 0) {
      throw new ValidationError('text is required');
    }
    const call = this.getCall(callId);
    this.assertAccess(call, actor);
    this.assertOpen(call);
    transition(call.state, 'utterance');

    if (input.client_message_id) {
      const existing = call.clientMessageIds.get(input.client_message_id);
      if (existing) {
        logger.info({ callId, clientMessageId: input.client_message_id }, '[v2] duplicate utterance ignored (idempotent)');
        return { utterance_id: existing, text: input.text, idempotent: true };
      }
    }

    const utteranceId = uuidV7();
    // Idempotency map: only client-supplied keys can be replayed against, and
    // the map is capped so a pathological client can't grow it unboundedly.
    if (input.client_message_id && call.clientMessageIds.size < 10_000) {
      call.clientMessageIds.set(input.client_message_id, utteranceId);
    }

    await this.emit(callId, V2_EVENTS.SPEECH_STARTED, actor, {
      utterance_id: utteranceId,
      speaker: 'user',
    });
    await this.emit(callId, V2_EVENTS.SPEECH_FINAL, actor, {
      utterance_id: utteranceId,
      text: input.text,
      ...(input.language ? { language: input.language } : {}),
    });

    await this.appendTranscript(callId, {
      role: 'user',
      type: 'speech',
      text: input.text,
      createdAt: new Date().toISOString(),
    });
    await this.emit(callId, V2_EVENTS.TURN_ENDED, systemActor(), {
      turn_type: 'user',
      turn_id: utteranceId,
    });

    // Human spoke — silence is over.
    this.clearSilencePolicy(callId);
    call.lastActivityAt = new Date().toISOString();

    logger.info({ callId, utteranceId }, '[v2] utterance recorded');
    return { utterance_id: utteranceId, text: input.text, idempotent: false };
  }

  async hangupCall(callId: string, input: HangupInput, actor: V2ActorInput): Promise<V2CallRecord> {
    const call = this.getCall(callId);
    this.assertAccess(call, actor);

    // Idempotent terminal no-op (retries from the phone's persisted queue).
    if (call.state === 'completed' || call.state === 'failed') return call;

    // A hangup note becomes part of the transcript the AI reads at the end.
    if (input.note && input.note.trim()) {
      await this.appendTranscript(callId, {
        role: 'user',
        type: 'text',
        text: input.note.trim(),
        createdAt: new Date().toISOString(),
      });
    }

    const endedAt = new Date().toISOString();
    const durationMs = call.connectedAt ? Date.now() - Date.parse(call.connectedAt) : 0;

    await this.emit(callId, V2_EVENTS.CALL_ENDING, actor, {
      reason: (input.outcome?.decision as string | undefined) ?? 'hangup',
    });

    transition(call.state, 'complete');
    call.state = 'completed';
    call.endedAt = endedAt;
    call.durationMs = durationMs;
    call.result = {
      ...(input.outcome ? { outcome: input.outcome } : {}),
      ...(input.note ? { note: input.note } : {}),
    };

    this.clearSilencePolicy(callId);
    await this.emit(callId, V2_EVENTS.CALL_COMPLETED, systemActor(), {
      ...(input.outcome ? { outcome: input.outcome } : {}),
      duration_ms: durationMs,
    });

    logger.info({ callId, durationMs }, '[v2] call completed');
    return call;
  }

  async failCall(callId: string, reason: string | undefined, code: string | undefined, actor: V2ActorInput): Promise<V2CallRecord> {
    const call = this.getCall(callId);
    this.assertAccess(call, actor);
    if (call.state === 'failed' || call.state === 'completed') return call;

    transition(call.state, 'fail');
    call.state = 'failed';
    call.endedAt = new Date().toISOString();
    this.clearSilencePolicy(callId);
    await this.emit(callId, V2_EVENTS.CALL_FAILED, systemActor(), {
      ...(reason ? { reason } : {}),
      ...(code ? { code } : {}),
    });
    logger.warn({ callId, reason, code }, '[v2] call failed');
    return call;
  }

  // ---- queries --------------------------------------------------------------

  getSnapshot(callId: string, actor: V2ActorInput): V2CallRecord {
    const call = this.getCall(callId);
    this.assertAccess(call, actor);
    return call;
  }

  getTranscript(callId: string, actor: V2ActorInput, afterSeq?: number, limit = 200): TranscriptSegment[] {
    const call = this.getCall(callId);
    this.assertAccess(call, actor);
    let segments = call.transcript;
    if (afterSeq !== undefined) {
      segments = segments.filter((s) => s.seq > afterSeq);
    }
    return segments.slice(-limit);
  }

  /** Marks a call archived (DELETE): terminal only, then drops the aggregate. */
  async archiveCall(callId: string, actor: V2ActorInput): Promise<V2CallRecord> {
    const call = this.getCall(callId);
    this.assertAccess(call, actor);
    if (call.state !== 'completed' && call.state !== 'failed') {
      throw new InvalidTransitionError(call.state, 'complete');
    }
    this.clearSilencePolicy(callId);
    await this.emit(callId, V2_EVENTS.CALL_ARCHIVED, actor, {
      retention_days: 90,
    });
    this.calls.delete(callId);
    return call;
  }

  /**
   * Retention sweep (mirrors the v1 stale-session sweeper): archives calls
   * with no activity for `idleMs`. This is retention, not a conversation
   * cap — a live exchange keeps touching lastActivityAt and is never swept.
   * Returns the number of archived calls.
   */
  async sweepIdleCalls(idleMs: number, now = Date.now()): Promise<number> {
    const cutoff = now - idleMs;
    let archived = 0;
    for (const call of this.calls.values()) {
      if (Date.parse(call.lastActivityAt) >= cutoff) continue;
      this.clearSilencePolicy(call.id);
      await this.emit(call.id, V2_EVENTS.CALL_ARCHIVED, systemActor(), {
        retention_days: 90,
      });
      this.calls.delete(call.id);
      archived++;
    }
    if (archived > 0) {
      logger.info({ archived, idleMs }, '[v2] idle-call sweep archived calls');
    }
    return archived;
  }

  // ---- transcript helper ----------------------------------------------------

  private async appendTranscript(callId: string, segment: Omit<TranscriptSegment, 'seq'>): Promise<void> {
    const call = this.getCall(callId);
    const seq = ++call.transcriptSeq;
    const full: TranscriptSegment = { ...segment, seq };
    call.transcript.push(full);
    call.lastActivityAt = full.createdAt;
    await this.emit(callId, V2_EVENTS.TRANSCRIPT_UPDATED, systemActor(), {
      segment: {
        seq: full.seq,
        role: full.role,
        type: full.type,
        text: full.text,
        ...(full.start_ms !== undefined ? { start_ms: full.start_ms } : {}),
        ...(full.end_ms !== undefined ? { end_ms: full.end_ms } : {}),
        ...(full.confidence !== undefined ? { confidence: full.confidence } : {}),
      },
    });
  }

  // ---- silence policy (advisory, R7) ---------------------------------------

  private armSilencePolicy(callId: string): void {
    const call = this.calls.get(callId);
    if (!call || !call.policy?.silence_after_ms || call.policy.silence_after_ms <= 0) return;
    this.clearSilencePolicy(callId);

    const afterMs = call.policy.silence_after_ms;
    const state = { count: 0, timer: null as NodeJS.Timeout | null };
    this.silence.set(callId, state);

    const tick = (): void => {
      const current = this.calls.get(callId);
      if (!current || !isOpenState(current.state)) return;
      state.count += 1;
      // Timer-driven advisory events — detached by nature (fire-and-forget).
      this.emitDetached(callId, V2_EVENTS.SILENCE_DETECTED, systemActor(), {
        after_ms: afterMs,
        context: 'post_message',
        count: state.count,
      });
      if (state.count >= MAX_SILENCE_ESCALATIONS) {
        this.emitDetached(callId, V2_EVENTS.CALL_NOACTIVITY, systemActor(), {
          silent_seconds: Math.round((afterMs * state.count) / 1000),
          silence_count: state.count,
        });
        return; // stop escalating — the AI decides next
      }
      state.timer = setTimeout(tick, afterMs);
      state.timer.unref?.();
    };
    state.timer = setTimeout(tick, afterMs);
    state.timer.unref?.();
  }

  private clearSilencePolicy(callId: string): void {
    const state = this.silence.get(callId);
    if (state?.timer) clearTimeout(state.timer);
    this.silence.delete(callId);
  }

  /** Releases all timers (shutdown / tests). */
  dispose(): void {
    for (const state of this.silence.values()) {
      if (state.timer) clearTimeout(state.timer);
    }
    this.silence.clear();
  }
}
