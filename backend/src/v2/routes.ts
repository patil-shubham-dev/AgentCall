import type { FastifyInstance, FastifyReply, FastifyRequest } from 'fastify';
import { z } from 'zod';
import { config } from '../common/config.js';
import { logger } from '../common/logger.js';
import { InvalidTransitionError } from './call-fsm.js';
import type { V2CallService, V2ActorInput } from './call-service.js';
import type { EventPlaneOptions } from './event-plane.js';
import { V2ApiError, ValidationError } from './errors.js';
import { V2_EVENTS } from './events.js';

/** Auth context shape set by the v1 onRequest hook (routes.ts). */
interface RouteAuth {
  userId: string;
  role: 'user' | 'agent' | 'service';
  authenticated: boolean;
  agentId?: string;
  agentName?: string;
}

export interface V2RouteDeps {
  callService: V2CallService;
}

const TERMINAL_EVENT_TYPES = new Set([V2_EVENTS.CALL_COMPLETED, V2_EVENTS.CALL_FAILED, 'call.archived']);

// Per-route rate limits (plugin registered globally in index.ts; v1 uses the
// same shape). Mutations are cheap-stateful and abuse-prone: 60/min like v1.
// Reads are heavier on the engine (snapshot/transcript scans): 120/min.
const moderateRateLimit = { max: 60, timeWindow: '1 minute' };
const readRateLimit = { max: 120, timeWindow: '1 minute' };

// ---- zod schemas (project convention: validation at every boundary) ---------

const mediaSchema = z
  .object({
    transport: z.string().optional(),
    stt: z.object({ provider: z.string().optional(), language: z.string().optional() }).optional(),
    tts: z.object({ provider: z.string().optional(), voice: z.string().optional() }).optional(),
  })
  .optional();

const createCallSchema = z.object({
  user_id: z.string().min(1).optional(),
  agent_id: z.string().min(1).optional(),
  reason: z.string().optional(),
  summary: z.string().max(1000).optional(),
  context: z
    .object({
      task_id: z.string().optional(),
      summary: z.string().optional(),
      options: z.array(z.string()).max(12).optional(),
      custom: z.record(z.unknown()).optional(),
    })
    .optional(),
  media: mediaSchema,
  policy: z
    .object({
      ring_timeout_ms: z.number().int().min(0).optional(),
      silence_after_ms: z.number().int().min(0).optional(),
      no_answer_action: z.enum(['keep_ringing', 'fail']).optional(),
    })
    .optional(),
  priority: z.enum(['low', 'normal', 'high', 'urgent']).optional(),
});

const answerSchema = z.object({
  provider: z.string().optional(),
});

const hangupSchema = z.object({
  outcome: z.record(z.unknown()).optional(),
  note: z.string().max(2000).optional(),
});

const messageSchema = z.object({
  content: z.string().min(1).max(2000),
  tts: z.object({ provider: z.string().optional(), voice: z.string().optional() }).optional(),
  reply_to: z.string().optional(),
});

const utteranceSchema = z.object({
  text: z.string().min(1).max(2000),
  client_message_id: z.string().optional(),
  language: z.string().optional(),
});

/** Streaming user speech (roadmap M2): partials until finalize=true. */
const utterancePartialSchema = z.object({
  utterance_id: z.string().optional(),
  text: z.string().min(1).max(2000),
  finalize: z.boolean().optional(),
  client_message_id: z.string().optional(),
  language: z.string().optional(),
  start_ms: z.number().int().min(0).optional(),
});

// ---- helpers ----------------------------------------------------------------

function toActor(request: FastifyRequest): V2ActorInput {
  const auth = (request as FastifyRequest & { auth?: RouteAuth }).auth;
  if (!auth || !auth.authenticated) {
    throw new V2ApiError(401, 'UNAUTHORIZED', 'Valid Bearer token required');
  }
  if (auth.role === 'service') return { type: 'service' };
  if (auth.role === 'agent') return { type: 'ai', identity: auth.agentName };
  return { type: 'user', identity: auth.userId };
}

function parse<T>(schema: z.ZodType<T>, body: unknown, label: string): T {
  const parsed = schema.safeParse(body);
  if (!parsed.success) {
    throw new ValidationError(`Invalid ${label}`, parsed.error.issues);
  }
  return parsed.data;
}

function sendError(reply: FastifyReply, err: unknown): FastifyReply {
  if (err instanceof InvalidTransitionError) {
    return reply.status(409).send({ error: 'INVALID_TRANSITION', message: err.message });
  }
  if (err instanceof V2ApiError) {
    return reply.status(err.statusCode).send({
      error: err.code,
      message: err.message,
      ...(err.details !== undefined ? { details: err.details } : {}),
      request_id: reply.request.id,
    });
  }
  logger.error({ err }, '[v2] unhandled route error');
  return reply.status(500).send({ error: 'INTERNAL_ERROR', message: 'Internal server error', request_id: reply.request.id });
}

/**
 * Idempotency wrapper (API spec §1): stores the first response per
 * (identity, key, callId) and replays it with X-Idempotent-Replay.
 */
async function withIdempotency(
  deps: V2RouteDeps,
  request: FastifyRequest,
  reply: FastifyReply,
  identity: string,
  callId: string | undefined,
  fn: () => Promise<{ statusCode: number; body: unknown }>,
): Promise<FastifyReply> {
  try {
    const idemKey = request.headers['idempotency-key'];
    if (typeof idemKey !== 'string' || idemKey.length === 0 || idemKey.length > 128) {
      const result = await fn();
      return reply.status(result.statusCode).send(result.body);
    }
    const key = deps.callService.idempotency.key(identity, idemKey, callId);
    const stored = await deps.callService.idempotency.get(key);
    if (stored) {
      logger.info({ key: callId ?? identity, idemKey }, '[v2] idempotency replay');
      reply.header('X-Idempotent-Replay', 'true');
      return reply.status(stored.statusCode).send(stored.body);
    }
    const result = await fn();
    try {
      await deps.callService.idempotency.put(key, result.statusCode, result.body);
    } catch (err) {
      // The command already settled and its events are durably logged (outbox
      // write path) — a failed replay-record must not turn a success into a
      // retryable 500, or the client's retry would re-execute the command and
      // duplicate the side effect. Log and proceed without replay protection.
      logger.error({ err, callId: callId ?? identity, idemKey }, '[v2] idempotency put failed; proceeding without replay protection');
    }
    return reply.status(result.statusCode).send(result.body);
  } catch (err) {
    // Map v2 errors (INVALID_TRANSITION, NOT_FOUND, FORBIDDEN…) even when no
    // global error handler is installed (tests, embedded servers).
    return sendError(reply, err);
  }
}

const idemIdentity = (actor: V2ActorInput): string => actor.identity ?? actor.type;

// ---- plugin ----------------------------------------------------------------

export function registerV2Routes(app: FastifyInstance, deps: V2RouteDeps): void {
  const { callService } = deps;

  app.get('/api/v2/health', { config: { rateLimit: { max: 20, timeWindow: '10 seconds' } } }, async () => ({
    status: 'ok',
    version: '2.0.0',
    timestamp: new Date().toISOString(),
  }));

  app.post('/api/v2/calls', { config: { rateLimit: { max: 60, timeWindow: '1 minute' } } }, async (request, reply) => {
    const actor = toActor(request);
    const input = parse(createCallSchema, request.body, 'create call body');
    const userId = input.user_id ?? 'solo-user';
    // Mirror v1: an authenticated AI owns its calls; service defaults to the
    // legacy agent name.
    const agentId =
      input.agent_id ?? (actor.type === 'ai' ? (actor.identity ?? 'ai-agent') : 'ai-agent');

    return withIdempotency(deps, request, reply, idemIdentity(actor), undefined, async () => {
      const call = await callService.createCall(
        {
          user_id: userId,
          agent_id: agentId,
          reason: input.reason,
          summary: input.summary ?? input.context?.summary,
          context: input.context,
          media: input.media,
          policy: input.policy,
          priority: input.priority,
        },
        actor,
      );
      return {
        statusCode: 201,
        body: {
          call_id: call.id,
          status: call.state,
          events_url: `/api/v2/calls/${call.id}/events`,
          created_at: call.createdAt,
        },
      };
    });
  });

  app.get('/api/v2/calls/:callId', { config: { rateLimit: readRateLimit } }, async (request, reply) => {
    try {
      const { callId } = request.params as { callId: string };
      const call = callService.getSnapshot(callId, toActor(request));
      return {
        call_id: call.id,
        status: call.state,
        phase: call.state,
        fsm_version: 1,
        user_id: call.userId,
        agent_id: call.agentId,
        transcript_seq: call.transcriptSeq,
        active_turn: call.activeTurn
          ? {
              type: call.activeTurn.type,
              ...(call.activeTurn.message_id ? { message_id: call.activeTurn.message_id } : {}),
              ...(call.activeTurn.utterance_id ? { utterance_id: call.activeTurn.utterance_id } : {}),
              started_at: call.activeTurn.started_at,
            }
          : null,
        ai_wait: {
          active: call.aiWaiting,
          active_until: null,
          last_active_at: call.aiWaiting ? call.lastActivityAt : null,
        },
        media: {
          transport: call.media?.transport ?? null,
          stt: call.media?.stt?.provider ?? null,
          tts: call.media?.tts?.provider ?? null,
          connected: call.state === 'connected',
        },
        created_at: call.createdAt,
        answered_at: call.connectedAt ?? null,
        ended_at: call.endedAt ?? null,
        context: call.context ?? null,
        result: call.result ?? null,
      };
    } catch (err) {
      return sendError(reply, err);
    }
  });

  app.post('/api/v2/calls/:callId/answer', { config: { rateLimit: moderateRateLimit } }, async (request, reply) => {
    const actor = toActor(request);
    const { callId } = request.params as { callId: string };
    const input = parse(answerSchema, request.body, 'answer body');
    return withIdempotency(deps, request, reply, idemIdentity(actor), callId, async () => {
      const call = await callService.answerCall(callId, input.provider, actor);
      return { statusCode: 200, body: { status: call.state, call_id: call.id } };
    });
  });

  app.post('/api/v2/calls/:callId/hangup', { config: { rateLimit: moderateRateLimit } }, async (request, reply) => {
    const actor = toActor(request);
    const { callId } = request.params as { callId: string };
    const input = parse(hangupSchema, request.body ?? {}, 'hangup body');
    return withIdempotency(deps, request, reply, idemIdentity(actor), callId, async () => {
      const call = await callService.hangupCall(callId, input, actor);
      return { statusCode: 200, body: { status: call.state, call_id: call.id } };
    });
  });

  app.post('/api/v2/calls/:callId/messages', { config: { rateLimit: moderateRateLimit } }, async (request, reply) => {
    const actor = toActor(request);
    const { callId } = request.params as { callId: string };
    const input = parse(messageSchema, request.body, 'message body');
    return withIdempotency(deps, request, reply, idemIdentity(actor), callId, async () => {
      const result = await callService.sendMessage(callId, input, actor);
      return { statusCode: 201, body: { message_id: result.message_id, status: 'queued' } };
    });
  });

  /**
   * Non-blocking AI speech (roadmap M2 §6 `say()`): 201 as soon as queued;
   * the stream is observed via message.started / message.completed /
   * message.failed on the events channel.
   */
  app.post('/api/v2/calls/:callId/speak', { config: { rateLimit: moderateRateLimit } }, async (request, reply) => {
    const actor = toActor(request);
    const { callId } = request.params as { callId: string };
    const input = parse(messageSchema, request.body, 'speak body');
    return withIdempotency(deps, request, reply, idemIdentity(actor), callId, async () => {
      const result = await callService.speak(callId, input, actor);
      return { statusCode: 201, body: { message_id: result.message_id, status: 'streaming' } };
    });
  });

  /** AI-initiated hard cut (roadmap M2 §7 stopSpeaking). */
  app.post('/api/v2/calls/:callId/stop-speaking', { config: { rateLimit: moderateRateLimit } }, async (request, reply) => {
    const actor = toActor(request);
    const { callId } = request.params as { callId: string };
    try {
      const result = await callService.stopSpeaking(callId, actor);
      return {
        stopped: result.stopped,
        ...(result.message_id ? { message_id: result.message_id } : {}),
      };
    } catch (err) {
      return sendError(reply, err);
    }
  });

  app.post('/api/v2/calls/:callId/utterances', { config: { rateLimit: moderateRateLimit } }, async (request, reply) => {
    const actor = toActor(request);
    const { callId } = request.params as { callId: string };
    const input = parse(utteranceSchema, request.body, 'utterance body');
    return withIdempotency(deps, request, reply, idemIdentity(actor), callId, async () => {
      const result = await callService.submitUtterance(callId, input, actor);
      return { statusCode: 200, body: { utterance_id: result.utterance_id, text: result.text, idempotent: result.idempotent } };
    });
  });

  /**
   * Streaming user speech (roadmap M2): partials until finalize=true. Live
   * partials are NOT idempotency-wrapped — they are cheap and continuous by
   * nature; the client_message_id binds inside the engine when the utterance
   * finalizes. The finalize request IS idempotency-wrapped: it settles the
   * whole utterance (speech.final + transcript + turn.ended), so a retry after
   * a crash or a flaky network must not duplicate the transcript segment.
   */
  app.post('/api/v2/calls/:callId/utterances/partial', { config: { rateLimit: moderateRateLimit } }, async (request, reply) => {
    const actor = toActor(request);
    const { callId } = request.params as { callId: string };
    const input = parse(utterancePartialSchema, request.body, 'utterance partial body');

    if (!input.finalize) {
      try {
        const result = await callService.submitUtterancePartial(callId, input, actor);
        return {
          utterance_id: result.utterance_id,
          text: result.text,
          idempotent: result.idempotent,
          final: false,
        };
      } catch (err) {
        return sendError(reply, err);
      }
    }

    return withIdempotency(deps, request, reply, idemIdentity(actor), callId, async () => {
      const result = await callService.submitUtterancePartial(callId, input, actor);
      return {
        statusCode: 200,
        body: {
          utterance_id: result.utterance_id,
          text: result.text,
          idempotent: result.idempotent,
          final: true,
        },
      };
    });
  });

  app.get('/api/v2/calls/:callId/transcript', { config: { rateLimit: readRateLimit } }, async (request, reply) => {
    try {
      const { callId } = request.params as { callId: string };
      const query = (request.query ?? {}) as { after?: string; partials?: string; limit?: string };
      const afterSeq = query.after !== undefined && query.after !== '' ? Number(query.after) : undefined;
      const limit = Math.min(Math.max(Number(query.limit ?? '200') || 200, 1), 500);
      const includePartials = query.partials === 'true';
      const segments = callService.getTranscript(callId, toActor(request), afterSeq, limit, includePartials);
      return {
        call_id: callId,
        segments,
        has_more: segments.length === limit,
      };
    } catch (err) {
      return sendError(reply, err);
    }
  });

  // SSE — the AI's real-time feed (API spec §3.1). Resumable via Last-Event-ID
  // or ?after= (empty = last V2_SSE_REPLAY_MAX_EVENTS events — a reconnect must
  // not flood the socket with full history on a long-lived call). Heartbeat
  // every V2_SSE_HEARTBEAT_MS. Closes with `event: stream.end` on terminal
  // states / client disconnect.
  app.get('/api/v2/calls/:callId/events', { config: { rateLimit: { max: 10, timeWindow: '1 minute' } } }, async (request, reply) => {
    const { callId } = request.params as { callId: string };
    const actor = toActor(request);
    // Ownership + existence in one check.
    try {
      callService.getSnapshot(callId, actor);
    } catch (err) {
      return sendError(reply, err);
    }

    const query = (request.query ?? {}) as { after?: string };
    const lastEventId = request.headers['last-event-id'];
    let replay: EventPlaneOptions['replay'] = 'none';
    let replayMax: number | undefined;
    if (typeof lastEventId === 'string' && lastEventId.length > 0) {
      // Cursor resume: uncapped — the client asked for everything after its
      // cursor, and silently truncating it would drop events (contract §3.1:
      // drops are never silent). Only the full-history path is capped.
      replay = { afterEventId: lastEventId };
    } else if (query.after === '') {
      replay = 'all';
      // Full-history reconnect: a long-lived call must not flood the socket
      // with its whole log; the newest V2_SSE_REPLAY_MAX_EVENTS suffice, the
      // client catches up from the transcript or a ?after=<id> cursor.
      replayMax = config.v2.sseReplayMaxEvents;
    } else if (typeof query.after === 'string' && query.after.length > 0) {
      replay = { afterEventId: query.after };
    }

    const raw = reply.raw;
    raw.writeHead(200, {
      'Content-Type': 'text/event-stream',
      'Cache-Control': 'no-cache',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
      'Access-Control-Expose-Headers': 'Last-Event-ID',
    });
    reply.hijack();

    let closed = false;
    let sub: { unsubscribe(): void } | null = null;
    let heartbeat: NodeJS.Timeout | null = null;
    const write = (chunk: string): void => {
      if (!closed) raw.write(chunk);
    };
    const close = (): void => {
      if (closed) return;
      closed = true;
      sub?.unsubscribe();
      if (heartbeat) clearInterval(heartbeat);
      raw.end();
    };

    sub = callService.plane.subscribe(
      callId,
      async (event) => {
        const data = JSON.stringify(event);
        write(`event: ${event.type}\nid: ${event.id}\ndata: ${data}\n\n`);
        if (TERMINAL_EVENT_TYPES.has(event.type)) {
          write(`event: stream.end\ndata: ${JSON.stringify({ reason: event.type })}\n\n`);
          close();
        }
      },
      { replay, replayMax },
    );

    heartbeat = setInterval(() => write(': ping\n\n'), config.v2.sseHeartbeatMs);
    heartbeat.unref?.();
    raw.on('close', () => close());
    // A socket error (e.g. ECONNRESET) must never surface as an unhandled
    // 'error' event on the raw response stream.
    raw.on('error', () => close());

    // Keep the request alive from Fastify's perspective.
    return reply;
  });

  app.delete('/api/v2/calls/:callId', { config: { rateLimit: moderateRateLimit } }, async (request, reply) => {
    const actor = toActor(request);
    const { callId } = request.params as { callId: string };
    try {
      const call = await callService.archiveCall(callId, actor);
      return { status: 'archived', call_id: call.id };
    } catch (err) {
      return sendError(reply, err);
    }
  });

}
