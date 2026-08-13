import { logger } from '../common/logger.js';
import type { EventPlane } from './event-plane.js';
import { V2_EVENTS } from './events.js';
import { uuidV7 } from './ids.js';
import { isOpenState, transition, InvalidTransitionError } from './call-fsm.js';
import type { V2CallState } from './call-fsm.js';
import { CallNotFoundError, ForbiddenError, ValidationError } from './errors.js';
import type { IdempotencyBackend } from './idempotency.js';
import type { V2Actor } from './events.js';
import { SyncTtsProvider } from './providers.js';
import type { TtsProvider, TtsHandle, TtsStats } from './providers.js';
import { rehydrate } from './recovery.js';

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
  /** Live partial appended to transcript reads; never in transcript.updated events. */
  is_partial?: boolean;
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
  /**
   * Floor holder: the streaming AI turn or the open user utterance. Mirrors
   * the SDK's active_turn snapshot field; null when nobody holds the floor.
   */
  activeTurn: { type: 'ai' | 'user'; message_id?: string; utterance_id?: string; started_at: string } | null;
  /** Open (in-flight) user utterance being recognized via speech.partial. */
  openUtterance: {
    utterance_id: string;
    text: string;
    started_at: string;
    last_partial_at: string;
    language?: string;
  } | null;
  /** Live streaming TTS handle (an AI turn is mid-audio). */
  tts: { message_id: string; handle: TtsHandle; started_at: string; settle: () => void } | null;
  /** Lease truth: is the AI waiting for human input? Gates turn.lease emission. */
  aiWaiting: boolean;
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

/** Streaming user speech (roadmap M2): one partial per call, finalize to close. */
export interface SubmitUtterancePartialInput {
  /** Client-generated id — binds the whole partial stream when finalized. */
  utterance_id?: string;
  text: string;
  /** finalize=true closes the utterance: speech.final + transcript + turn.ended. */
  finalize?: boolean;
  client_message_id?: string;
  language?: string;
  /** STT start offset within the audio buffer, ms. */
  start_ms?: number;
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
    readonly idempotency: IdempotencyBackend,
    /** Media provider seam (roadmap M2). Defaults to the $0 on-device sync TTS. */
    readonly ttsProvider: TtsProvider = new SyncTtsProvider(),
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
      activeTurn: null,
      openUtterance: null,
      tts: null,
      aiWaiting: false,
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

    // M1 contract: the command returns only after the whole turn is durably
    // recorded (queued → started → completed → transcript → turn.ended →
    // turn.lease). The sync provider completes in one pass; scripted/cloud
    // providers stream and this awaits their completion.
    const stream = this.streamMessage(callId, input, messageId);
    await stream.finished;
    await Promise.all(stream.emits);

    // Advisory silence policy: after an AI turn, no human activity for
    // silence_after_ms escalates silence.detected → call.noactivity (R7).
    this.armSilencePolicy(callId);

    logger.info({ callId, messageId }, '[v2] message sent');
    return { message_id: messageId };
  }

  /**
   * Non-blocking AI message (roadmap M2 §6 `say()`): returns as soon as the
   * message is queued; streaming happens in the background through the TTS
   * provider. Errors land as message.failed / turn.cancelled events.
   */
  async speak(callId: string, input: SendMessageInput, actor: V2ActorInput): Promise<{ message_id: string }> {
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

    const stream = this.streamMessage(callId, input, messageId);
    void stream.finished
      .then(() => Promise.all(stream.emits))
      // Same post-turn contract as sendMessage(): once the turn settles, the
      // silence policy arms so silence.detected escalations resume.
      .then(() => this.armSilencePolicy(callId))
      .catch((err) => {
        logger.error({ err, callId, messageId }, '[v2] detached speech stream failed');
      });

    logger.info({ callId, messageId }, '[v2] speech queued (streaming)');
    return { message_id: messageId };
  }

  /** AI-initiated hard cut of the streaming turn (roadmap M2 §7 stopSpeaking). */
  async stopSpeaking(callId: string, actor: V2ActorInput): Promise<{ message_id?: string; stopped: boolean }> {
    const call = this.getCall(callId);
    this.assertAccess(call, actor);
    const cut = this.cutTts(callId);
    if (!cut) return { stopped: false };

    await this.emit(callId, V2_EVENTS.TURN_CANCELLED, systemActor(), {
      turn_type: 'ai',
      message_id: cut.message_id,
      reason: 'ai_stop',
    });
    // The cut message never completes — close it explicitly so consumers
    // waiting on message.completed don't hang (v1 messageFailed equivalent).
    await this.emit(callId, V2_EVENTS.MESSAGE_FAILED, systemActor(), {
      message_id: cut.message_id,
      reason: 'ai_stop',
      partial_audio_ms: cut.partial_audio_ms,
    });
    if (call.aiWaiting) {
      call.aiWaiting = false;
      await this.emitTurnLease(callId, false);
    }
    call.activeTurn = null;
    this.clearSilencePolicy(callId);

    logger.info({ callId, messageId: cut.message_id }, '[v2] speech stopped by AI');
    return { message_id: cut.message_id, stopped: true };
  }

  /**
   * Streaming user speech (roadmap M2): each call replaces the open partial;
   * finalize=true closes the utterance and binds client_message_id for
   * idempotent replay. Barge-in: the first partial of an open stream cuts the
   * AI turn synchronously (the p95 ≤ 50 ms budget lives in TtsHandle.stop()).
   */
  async submitUtterancePartial(
    callId: string,
    input: SubmitUtterancePartialInput,
    actor: V2ActorInput,
  ): Promise<{ utterance_id: string; text: string; idempotent: boolean; final: boolean }> {
    if (!input.text || input.text.trim().length === 0) {
      throw new ValidationError('text is required');
    }
    const call = this.getCall(callId);
    this.assertAccess(call, actor);
    this.assertOpen(call);
    transition(call.state, 'utterance');

    // Barge-in cut BEFORE anything else — synchronous, deterministic.
    const cut = this.cutTts(callId);
    if (cut) {
      await this.emit(callId, V2_EVENTS.USER_INTERRUPTED, actor, {
        interrupted_message_id: cut.message_id,
        interrupted_audio_ms: cut.partial_audio_ms,
      });
      await this.emit(callId, V2_EVENTS.TURN_CANCELLED, systemActor(), {
        turn_type: 'ai',
        message_id: cut.message_id,
        reason: 'barge_in',
      });
      call.activeTurn = null;
    }

    let open = call.openUtterance;
    if (!open) {
      const utteranceId = input.utterance_id ?? uuidV7();
      open = {
        utterance_id: utteranceId,
        text: '',
        started_at: new Date().toISOString(),
        last_partial_at: new Date().toISOString(),
        ...(input.language ? { language: input.language } : {}),
      };
      call.openUtterance = open;
      call.activeTurn = { type: 'user', utterance_id: utteranceId, started_at: open.started_at };
      await this.emit(callId, V2_EVENTS.SPEECH_STARTED, actor, {
        utterance_id: utteranceId,
        speaker: 'user',
      });
    }

    open.text = input.text;
    open.last_partial_at = new Date().toISOString();

    if (!input.finalize) {
      await this.emit(callId, V2_EVENTS.SPEECH_PARTIAL, actor, {
        utterance_id: open.utterance_id,
        text: input.text,
        ...(input.start_ms !== undefined ? { start_ms: input.start_ms } : {}),
        end_ms: 0, // open segment — replaced by the next partial
      });
      return { utterance_id: open.utterance_id, text: input.text, idempotent: false, final: false };
    }

    // Finalize: the text goes ONLY into speech.final (a trailing partial would
    // duplicate it); the idempotency key binds the COMPLETE utterance.
    if (input.client_message_id) {
      const existing = call.clientMessageIds.get(input.client_message_id);
      if (existing) {
        logger.info({ callId, clientMessageId: input.client_message_id }, '[v2] duplicate finalized utterance ignored (idempotent)');
        return { utterance_id: existing, text: input.text, idempotent: true, final: true };
      }
      if (call.clientMessageIds.size < 10_000) {
        call.clientMessageIds.set(input.client_message_id, open.utterance_id);
      }
    }

    const endMs = Math.max(0, Date.now() - Date.parse(open.started_at));
    await this.emit(callId, V2_EVENTS.SPEECH_FINAL, actor, {
      utterance_id: open.utterance_id,
      text: input.text,
      ...(input.language ? { language: input.language } : {}),
      ...(input.start_ms !== undefined ? { start_ms: input.start_ms } : {}),
      end_ms: endMs,
      duration_ms: endMs,
    });
    await this.emit(callId, V2_EVENTS.TRANSCRIPT_PARTIAL_CLEARED, systemActor(), {
      utterance_id: open.utterance_id,
    });
    await this.appendTranscript(callId, {
      role: 'user',
      type: 'speech',
      text: input.text,
      ...(input.start_ms !== undefined ? { start_ms: input.start_ms } : {}),
      end_ms: endMs,
      createdAt: new Date().toISOString(),
    });
    await this.emit(callId, V2_EVENTS.TURN_ENDED, systemActor(), {
      turn_type: 'user',
      turn_id: open.utterance_id,
    });

    // Human spoke — the AI lease is over, silence is over.
    if (call.aiWaiting) {
      call.aiWaiting = false;
      await this.emitTurnLease(callId, false);
    }
    call.openUtterance = null;
    call.activeTurn = null;
    this.clearSilencePolicy(callId);
    call.lastActivityAt = new Date().toISOString();

    logger.info({ callId, utteranceId: open.utterance_id }, '[v2] utterance finalized (partial stream)');
    return { utterance_id: open.utterance_id, text: input.text, idempotent: false, final: true };
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

    // Barge-in cut BEFORE opening a new utterance — synchronous, deterministic.
    const cut = this.cutTts(callId);
    if (cut) {
      await this.emit(callId, V2_EVENTS.USER_INTERRUPTED, actor, {
        interrupted_message_id: cut.message_id,
        interrupted_audio_ms: cut.partial_audio_ms,
      });
      await this.emit(callId, V2_EVENTS.TURN_CANCELLED, systemActor(), {
        turn_type: 'ai',
        message_id: cut.message_id,
        reason: 'barge_in',
      });
      call.activeTurn = null;
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

    // Human spoke — the AI lease is over, silence is over.
    if (call.aiWaiting) {
      call.aiWaiting = false;
      await this.emitTurnLease(callId, false);
    }
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

    // Hangup supersedes any streaming turn (silent — the turn is moot).
    this.stopTtsSilently(callId);
    call.openUtterance = null;
    call.aiWaiting = false;

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

    this.stopTtsSilently(callId);
    call.openUtterance = null;
    call.aiWaiting = false;

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

  getTranscript(
    callId: string,
    actor: V2ActorInput,
    afterSeq?: number,
    limit = 200,
    includePartials = false,
  ): TranscriptSegment[] {
    const call = this.getCall(callId);
    this.assertAccess(call, actor);
    let segments = call.transcript;
    if (includePartials && call.openUtterance) {
      // Live partial: appended after the settled transcript with is_partial
      // (api-spec §2.6) — the next speech.final replaces it via TRANSCRIPT_UPDATED.
      segments = [
        ...segments,
        {
          seq: call.transcriptSeq + 1,
          role: 'user',
          type: 'speech',
          text: call.openUtterance.text,
          is_partial: true,
          createdAt: call.openUtterance.last_partial_at,
        },
      ];
    }
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
      // A swept call must not keep a live TTS stream running into the void
      // (events for an archived call, audio continuing after retention).
      this.stopTtsSilently(call.id);
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

  // ---- recovery (roadmap M3) ------------------------------------------------

  /**
   * Rehydrates one call from the durable event log after a worker restart
   * (RPO 0: replay restores exactly what the outbox recorded). Timer
   * reconstruction: an armed silence policy is re-armed with its REMAINING
   * budget (elapsed time since the last completed AI turn); a budget already
   * spent fires the first silence.detected immediately.
   */
  async recoverCall(callId: string, now = Date.now()): Promise<V2CallRecord | null> {
    const hydrated = await rehydrate(this.plane.log, callId);
    if (!hydrated) return null;
    const { call, silence } = hydrated;
    this.calls.set(callId, call);
    if (silence && call.policy?.silence_after_ms) {
      const elapsed = now - silence.armedAt;
      this.armSilencePolicy(callId, Math.max(0, call.policy.silence_after_ms - elapsed));
    }
    logger.info({ callId, state: call.state }, '[v2.recovery] call recovered from event log');
    return call;
  }

  /** Replays every call in the log back into the aggregate map. */
  async recoverAll(now = Date.now()): Promise<{ recovered: number; total: number }> {
    const callIds = await this.plane.log.callIds();
    let recovered = 0;
    for (const callId of callIds) {
      if (await this.recoverCall(callId, now)) recovered++;
    }
    if (callIds.length > 0) {
      logger.info({ recovered, total: callIds.length }, '[v2.recovery] startup recovery completed');
    }
    return { recovered, total: callIds.length };
  }

  // ---- silence policy (advisory, R7) ---------------------------------------

  private armSilencePolicy(callId: string, delayOverrideMs?: number): void {
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
    state.timer = setTimeout(tick, delayOverrideMs ?? afterMs);
    state.timer.unref?.();
  }

  private clearSilencePolicy(callId: string): void {
    const state = this.silence.get(callId);
    if (state?.timer) clearTimeout(state.timer);
    this.silence.delete(callId);
  }

  // ---- TTS streaming helpers (roadmap M2) ----------------------------------

  /**
   * Drives one AI message through the TTS provider. Provider callbacks emit
   * ordered events: onStarted → message.started, onDone → message.completed +
   * transcript + turn.ended + turn.lease(active), onError → message.failed +
   * turn.cancelled. `finished` resolves when the stream settles (done, error,
   * or cut); `emits` is the durable-write queue a caller awaits.
   */
  private streamMessage(
    callId: string,
    input: SendMessageInput,
    messageId: string,
  ): { message_id: string; finished: Promise<void>; emits: Promise<void>[] } {
    const call = this.getCall(callId);

    // AI talking over itself (retry/rephrase): silently cut the prior stream.
    if (call.tts) {
      call.tts.handle.stop();
      call.tts.settle();
      call.tts = null;
    }

    let settled = false;
    let finishResolve!: () => void;
    const finished = new Promise<void>((resolve) => {
      finishResolve = resolve;
    });
    const settle = (): void => {
      if (!settled) {
        settled = true;
        finishResolve();
      }
    };

    // The provider may emit callbacks SYNCHRONOUSLY inside speak() (sync
    // provider), before `handle` below is initialized — and a cut may land
    // before a stale async callback fires. A proxy handle assigned before
    // speak() keeps call.tts and the callback guards consistent in both
    // worlds; `stopped` is the cut oracle the callbacks consult.
    let realHandle: TtsHandle | null = null;
    const pendingStop: TtsHandle = {
      stop(): void {
        realHandle?.stop();
      },
      get stopped(): boolean {
        return realHandle?.stopped ?? false;
      },
      get stats(): { chars_streamed: number; audio_ms_streamed: number } {
        return realHandle?.stats ?? { chars_streamed: 0, audio_ms_streamed: 0 };
      },
    };
    call.tts = {
      message_id: messageId,
      handle: pendingStop,
      started_at: new Date().toISOString(),
      settle,
    };
    call.activeTurn = { type: 'ai', message_id: messageId, started_at: new Date().toISOString() };

    const emits: Promise<void>[] = [];
    const handle = this.ttsProvider.speak(
      { messageId, content: input.content, ...(input.tts?.voice ? { voice: input.tts.voice } : {}) },
      {
        onStarted: (mid) => {
          if (mid !== messageId || pendingStop.stopped) return;
          emits.push(
            this.emit(callId, V2_EVENTS.MESSAGE_STARTED, systemActor(), {
              message_id: mid,
              tts_provider: input.tts?.provider ?? this.ttsProvider.name,
              streamed: true,
            }),
          );
        },
        // Token pacing is provider-internal; no per-token event exists in the
        // catalog (event-model §3.2: audio fidelity arrives with the M4 transport).
        onToken: () => undefined,
        onDone: (mid, stats) => {
          // A cut message never completes; settle so awaiting commands unblock.
          if (mid !== messageId || pendingStop.stopped) {
            settle();
            return;
          }
          emits.push(this.finishAiTurn(callId, mid, input.content, stats));
          settle();
        },
        onError: (mid, reason) => {
          if (mid !== messageId || pendingStop.stopped) {
            settle();
            return;
          }
          emits.push(this.failAiTurn(callId, mid, reason));
          settle();
        },
      },
    );
    realHandle = handle;
    return { message_id: messageId, finished, emits };
  }

  /**
   * Synchronous hard cut of a live stream (barge-in / ai_stop). Returns the
   * cut facts for event emission; null when no stream is live. The p95 ≤ 50 ms
   * barge-in budget is whatever `TtsHandle.stop()` takes.
   */
  private cutTts(callId: string): { message_id: string; partial_audio_ms: number } | null {
    const call = this.calls.get(callId);
    if (!call?.tts) return null;
    const live = call.tts;
    live.handle.stop();
    live.settle();
    call.tts = null;
    return {
      message_id: live.message_id,
      partial_audio_ms: live.handle.stats.audio_ms_streamed,
    };
  }

  /** Silently stops a live stream without emitting (hangup/fail supersede it). */
  private stopTtsSilently(callId: string): void {
    const call = this.calls.get(callId);
    if (!call?.tts) return;
    call.tts.handle.stop();
    call.tts.settle();
    call.tts = null;
    call.activeTurn = null;
  }

  private async finishAiTurn(callId: string, messageId: string, content: string, stats: TtsStats): Promise<void> {
    const call = this.calls.get(callId);
    if (!call) return; // archived mid-stream — nothing left to record
    call.tts = null;
    call.aiWaiting = true;

    await this.emit(callId, V2_EVENTS.MESSAGE_COMPLETED, systemActor(), {
      message_id: messageId,
      duration_ms: stats.duration_ms,
      chars_spoken: stats.chars_spoken,
      audio_bytes: stats.audio_bytes,
    });
    await this.appendTranscript(callId, {
      role: 'ai',
      type: 'text',
      text: content,
      createdAt: new Date().toISOString(),
    });
    await this.emit(callId, V2_EVENTS.TURN_ENDED, systemActor(), {
      turn_type: 'ai',
      message_id: messageId,
    });
    await this.emitTurnLease(callId, true);
  }

  private async failAiTurn(callId: string, messageId: string, reason: string): Promise<void> {
    const call = this.calls.get(callId);
    if (!call) return; // archived mid-stream — nothing left to record
    call.tts = null;
    call.aiWaiting = false;
    call.activeTurn = null;

    await this.emit(callId, V2_EVENTS.MESSAGE_FAILED, systemActor(), {
      message_id: messageId,
      reason,
      partial_audio_ms: 0,
    });
    await this.emit(callId, V2_EVENTS.TURN_CANCELLED, systemActor(), {
      turn_type: 'ai',
      message_id: messageId,
      reason: 'tts_error',
    });
  }

  /**
   * ai_wait_status lease (event-model §3.2). Emitted ONLY on wait-state
   * changes, so a sequence of turn churn doesn't spam the log: active=true
   * when the AI finishes a turn and waits; active=false when human speech
   * resolves it or an ai_stop/error ends the turn. active_until is null — the
   * lease has no expiry semantics in M2.
   */
  private async emitTurnLease(callId: string, active: boolean): Promise<void> {
    await this.emit(callId, V2_EVENTS.TURN_LEASE, systemActor(), {
      ai_wait_status: {
        active,
        active_until: null,
        last_active_at: active ? new Date().toISOString() : null,
      },
    });
  }

  /** Releases all timers and streaming handles (shutdown / tests). */
  dispose(): void {
    for (const state of this.silence.values()) {
      if (state.timer) clearTimeout(state.timer);
    }
    this.silence.clear();
    for (const call of this.calls.values()) {
      if (call.tts) {
        call.tts.handle.stop();
        call.tts.settle();
      }
    }
  }
}
