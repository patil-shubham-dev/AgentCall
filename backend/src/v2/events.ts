import { z } from 'zod';
import { logger } from '../common/logger.js';

/**
 * v2 event model (docs/v2/03-event-model.md). One envelope on every transport;
 * the M1 subset of the catalog is registered below. Payloads are zod-validated
 * at emit time in dev (logged-and-continue in prod, per the event-model §5).
 */

export const ACTOR_TYPES = ['ai', 'user', 'system', 'device'] as const;
export type ActorType = (typeof ACTOR_TYPES)[number];

export interface V2Actor {
  type: ActorType;
  identity?: string;
}

/** The one envelope shape. `sequence` is assigned by the event log. */
export interface V2Event {
  id: string; // uuid v7 — dedupe key for consumers
  type: string; // dotted domain name, e.g. 'speech.final'
  version: number; // schema version of `payload`
  call_id: string;
  correlation_id: string; // same for the whole call lifetime
  causation_id?: string; // id of the event that caused this one
  occurred_at: string; // ISO-8601 UTC
  sequence: number; // per-call monotonic, contiguous in the event log
  actor: V2Actor;
  payload: Record<string, unknown>;
}

// ---- Event type constants (M1 catalog) -------------------------------------

export const V2_EVENTS = {
  // lifecycle
  CALL_CREATED: 'call.created',
  CALL_RINGING: 'call.ringing',
  CALL_ANSWER_REQUESTED: 'call.answer.requested',
  CALL_CONNECTED: 'call.connected',
  CALL_ENDING: 'call.ending',
  CALL_COMPLETED: 'call.completed',
  CALL_FAILED: 'call.failed',
  CALL_ARCHIVED: 'call.archived',
  // AI turn / messages
  MESSAGE_QUEUED: 'message.queued',
  MESSAGE_STARTED: 'message.started',
  MESSAGE_COMPLETED: 'message.completed',
  MESSAGE_FAILED: 'message.failed',
  TURN_LEASE: 'turn.lease',
  TURN_ENDED: 'turn.ended',
  TURN_CANCELLED: 'turn.cancelled',
  // speech / transcript
  SPEECH_STARTED: 'speech.started',
  SPEECH_PARTIAL: 'speech.partial',
  SPEECH_FINAL: 'speech.final',
  SPEECH_FAILED: 'speech.failed',
  TRANSCRIPT_UPDATED: 'transcript.updated',
  TRANSCRIPT_PARTIAL_CLEARED: 'transcript.partial.cleared',
  // interruption & silence (advisory — M2 emits these from VAD; M1 from policy)
  USER_INTERRUPTED: 'user.interrupted',
  SILENCE_DETECTED: 'silence.detected',
  CALL_NOACTIVITY: 'call.noactivity',
  // observability (internal — not delivered to AI subscribers by default)
  AUDIT_EVENT: 'audit.event',
} as const;

export type V2EventType = (typeof V2_EVENTS)[keyof typeof V2_EVENTS];

// ---- Payload schemas --------------------------------------------------------

const callContextSchema = z.object({
  task_id: z.string().optional(),
  summary: z.string().optional(),
  options: z.array(z.string()).optional(),
  custom: z.record(z.unknown()).optional(),
});

const mediaConfigSchema = z.object({
  transport: z.string().optional(),
  stt: z.object({ provider: z.string().optional(), language: z.string().optional() }).optional(),
  tts: z.object({ provider: z.string().optional(), voice: z.string().optional() }).optional(),
});

const outcomeSchema = z.object({
  decision: z.string().optional(),
  selected_option: z.string().optional(),
  sentiment: z.string().optional(),
  action_items: z.array(z.string()).optional(),
});

const CALL_EVENTS: Record<string, z.ZodType> = {
  [V2_EVENTS.CALL_CREATED]: z.object({
    user_id: z.string(),
    agent_id: z.string(),
    reason: z.string().optional(),
    summary: z.string().optional(),
    context: callContextSchema.optional(),
    media: mediaConfigSchema.optional(),
    policy: z.object({
      ring_timeout_ms: z.number().optional(),
      silence_after_ms: z.number().optional(),
      no_answer_action: z.enum(['keep_ringing', 'fail', 'voicemail']).optional(),
    }).optional(),
    priority: z.enum(['low', 'normal', 'high', 'urgent']).optional(),
  }),
  [V2_EVENTS.CALL_RINGING]: z.object({
    provider: z.string().optional(),
    ring_policy: z.string().optional(),
  }),
  [V2_EVENTS.CALL_ANSWER_REQUESTED]: z.object({
    provider: z.string().optional(),
    device: z.string().optional(),
  }),
  [V2_EVENTS.CALL_CONNECTED]: z.object({
    connected_at: z.string(),
    provider: z.string().optional(),
  }),
  [V2_EVENTS.CALL_ENDING]: z.object({ reason: z.string().optional() }),
  [V2_EVENTS.CALL_COMPLETED]: z.object({
    outcome: outcomeSchema.optional(),
    duration_ms: z.number().optional(),
  }),
  [V2_EVENTS.CALL_FAILED]: z.object({
    reason: z.string().optional(),
    code: z.string().optional(),
    attempts: z.number().optional(),
  }),
  [V2_EVENTS.CALL_ARCHIVED]: z.object({
    archive_uri: z.string().optional(),
    retention_days: z.number().optional(),
  }),
};

const MESSAGE_EVENTS: Record<string, z.ZodType> = {
  [V2_EVENTS.MESSAGE_QUEUED]: z.object({
    message_id: z.string(),
    content: z.string(),
    tts: mediaConfigSchema.shape.tts.optional(),
    reply_to: z.string().optional(),
  }),
  [V2_EVENTS.MESSAGE_STARTED]: z.object({
    message_id: z.string(),
    tts_provider: z.string().optional(),
    streamed: z.boolean().optional(),
  }),
  [V2_EVENTS.MESSAGE_COMPLETED]: z.object({
    message_id: z.string(),
    duration_ms: z.number().optional(),
    chars_spoken: z.number().optional(),
    audio_bytes: z.number().optional(),
  }),
  [V2_EVENTS.MESSAGE_FAILED]: z.object({
    message_id: z.string(),
    reason: z.string().optional(),
    partial_audio_ms: z.number().optional(),
  }),
  [V2_EVENTS.TURN_LEASE]: z.object({
    ai_wait_status: z.object({
      active: z.boolean(),
      active_until: z.string().nullable(),
      last_active_at: z.string().nullable(),
    }),
  }),
  [V2_EVENTS.TURN_ENDED]: z.object({
    turn_type: z.enum(['ai', 'user']),
    turn_id: z.string().optional(),
    message_id: z.string().optional(),
  }),
  [V2_EVENTS.TURN_CANCELLED]: z.object({
    turn_type: z.enum(['ai', 'user']),
    message_id: z.string().optional(),
    reason: z.string().optional(),
  }),
};

const SPEECH_EVENTS: Record<string, z.ZodType> = {
  [V2_EVENTS.SPEECH_STARTED]: z.object({
    utterance_id: z.string(),
    speaker: z.string(),
  }),
  [V2_EVENTS.SPEECH_PARTIAL]: z.object({
    utterance_id: z.string(),
    text: z.string(),
    confidence: z.number().optional(),
    start_ms: z.number().optional(),
    // 0 = open (still talking); >0 = a completed segment within an utterance.
    end_ms: z.number().optional(),
  }),
  [V2_EVENTS.SPEECH_FINAL]: z.object({
    utterance_id: z.string(),
    text: z.string(),
    confidence: z.number().optional(),
    language: z.string().optional(),
    start_ms: z.number().optional(),
    end_ms: z.number().optional(),
    duration_ms: z.number().optional(),
  }),
  [V2_EVENTS.SPEECH_FAILED]: z.object({
    utterance_id: z.string(),
    reason: z.string().optional(),
  }),
  [V2_EVENTS.TRANSCRIPT_UPDATED]: z.object({
    segment: z.object({
      seq: z.number(),
      role: z.enum(['user', 'ai']),
      type: z.string(),
      text: z.string(),
      start_ms: z.number().optional(),
      end_ms: z.number().optional(),
      confidence: z.number().optional(),
    }),
  }),
  [V2_EVENTS.TRANSCRIPT_PARTIAL_CLEARED]: z.object({
    utterance_id: z.string(),
  }),
  [V2_EVENTS.USER_INTERRUPTED]: z.object({
    interrupted_message_id: z.string().optional(),
    interrupted_audio_ms: z.number().optional(),
    utterance_id: z.string().optional(),
  }),
  [V2_EVENTS.SILENCE_DETECTED]: z.object({
    after_ms: z.number(),
    context: z.enum(['post_question', 'mid_turn', 'post_message']).optional(),
    count: z.number(),
  }),
  [V2_EVENTS.CALL_NOACTIVITY]: z.object({
    silent_seconds: z.number(),
    silence_count: z.number(),
  }),
};

const OBSERVABILITY_EVENTS: Record<string, z.ZodType> = {
  [V2_EVENTS.AUDIT_EVENT]: z.object({
    actor: z.string(),
    action: z.string(),
    resource: z.string().optional(),
    before: z.unknown().optional(),
    after: z.unknown().optional(),
  }),
};

const REGISTRY: Record<string, z.ZodType> = {
  ...CALL_EVENTS,
  ...MESSAGE_EVENTS,
  ...SPEECH_EVENTS,
  ...OBSERVABILITY_EVENTS,
};

/**
 * The payload schemas, exported for integrity checking: the background
 * verifier re-validates every logged event's payload against these, so an
 * emit-time schema violation (a code bug — log-and-continue at publish) is
 * surfaced as corruption instead of silently polluting the truth log.
 */
export const EVENT_PAYLOAD_SCHEMAS: Readonly<Record<string, z.ZodType>> = REGISTRY;

/**
 * Single registration point per event-model §5. In dev, emitting an event whose
 * payload fails its schema logs loudly (emit still proceeds); the schema is
 * enforced at the API boundary already, so a failure here is a code bug.
 * Returns true when the payload is valid (or the type is unregistered), false
 * on a schema violation — the publisher counts it so the verifier can flag it.
 */
export function validateEventPayload(type: string, payload: Record<string, unknown>): boolean {
  const schema = REGISTRY[type];
  if (!schema) {
    if (process.env.NODE_ENV !== 'production') {
      logger.warn({ type }, '[v2.event] unregistered event type emitted');
    }
    return true;
  }
  const parsed = schema.safeParse(payload);
  if (!parsed.success) {
    logger.error({ type, issues: parsed.error.issues }, '[v2.event] payload schema violation');
    return false;
  }
  return true;
}

