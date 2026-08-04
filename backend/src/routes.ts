import type { FastifyInstance, FastifyRequest } from 'fastify';
import { config } from './common/config.js';
import { logger } from './common/logger.js';
import type { MetricsCollector } from './common/metrics-collector.js';
import type { DatabaseHealthMonitor } from './common/db-health-monitor.js';
import type { CleanupScheduler } from './common/cleanup-scheduler.js';
import { createPhoneToken, validatePhoneToken } from './voicebridge/phone-tokens.js';
import { getConnectedPhoneCount } from './voicebridge/service.js';
import type { VoiceBridgeService } from './voicebridge/service.js';
import type { CreateCallInput } from './voicebridge/types.js';
import type { SessionRepository, CallbackRepository } from './voicebridge/repositories/index.js';
import {
  createAiKey,
  deleteAiKey,
  listAiKeyStatuses,
  resolveAiKey,
  DEFAULT_AGENT_NAME,
} from './voicebridge/ai-keys.js';

export interface RouteOptions {
  voicebridge: VoiceBridgeService;
  metrics?: MetricsCollector;
  dbHealth?: DatabaseHealthMonitor;
  cleanupScheduler?: CleanupScheduler;
  sessionRepo?: SessionRepository;
  callbackRepo?: CallbackRepository;
  recoveryComplete?: boolean;
  startupComplete?: boolean;
}

function inspectBody(body: unknown): string {
  if (config.nodeEnv === 'production') return '';
  if (body === null || body === undefined) return String(body);
  if (typeof body === 'object') {
    try { return JSON.stringify(body); } catch { return String(body); }
  }
  return String(body);
}

const moderateRateLimit = { max: 60, timeWindow: '1 minute' };

interface AuthContext {
  userId: string;
  role: 'user' | 'agent' | 'service';
  authenticated: boolean;
  agentId?: string;
  agentName?: string;
}

async function getAuthUser(request: FastifyRequest): Promise<AuthContext> {
  const header = request.headers.authorization;
  if (!header?.startsWith('Bearer ')) {
    return { userId: 'solo-user', role: 'user', authenticated: false };
  }
  const token = header.slice(7);
  if (token === config.serviceToken) {
    return { userId: 'service', role: 'service', authenticated: true, agentName: DEFAULT_AGENT_NAME };
  }
  // Named AI keys (from the Add-AI flow) resolve to the registered AI identity
  const aiKey = await resolveAiKey(token).catch(() => null);
  if (aiKey) {
    return { userId: aiKey.name, role: 'agent', authenticated: true, agentId: aiKey.id, agentName: aiKey.name };
  }
  // Also accept phone tokens
  const phoneUserId = await validatePhoneToken(token).catch(() => null);
  if (phoneUserId) {
    return { userId: phoneUserId, role: 'user', authenticated: true };
  }
  return { userId: 'solo-user', role: 'user', authenticated: false };
}

export function registerRoutes(app: FastifyInstance, opts: RouteOptions): void {
  const { voicebridge, metrics, dbHealth, cleanupScheduler, sessionRepo, callbackRepo } = opts;

    // Auth middleware: protects all routes except health/ready/metrics
  app.addHook('onRequest', async (request, reply) => {
    const url = request.url ?? '';
    // Skip auth for health check endpoints (required by K8s probes), phone token
    // registration, and the MCP endpoint (which does its own multi-method auth)
    if (url.startsWith('/api/v1/health') || url.startsWith('/api/v1/ready') || url.startsWith('/api/v1/metrics') || url === '/api/v1/phone/token' || url.split('?')[0] === '/mcp') {
      return;
    }
    const isDev = config.serviceToken === 'dev-service-token';
    if (isDev) {
      (request as FastifyRequest & { auth: AuthContext }).auth = { userId: 'service', role: 'service', authenticated: true };
      return;
    }
    const auth = await getAuthUser(request);
    if (!auth.authenticated) {
      return reply.status(401).send({
        error: 'UNAUTHORIZED',
        message: 'Valid Bearer token required',
        request_id: request.id,
      });
    }
    (request as FastifyRequest & { auth: AuthContext }).auth = auth;
    logger.debug({ method: request.method, url, auth }, '[HTTP] request');
  });

  app.get('/api/v1/health', {
    config: { rateLimit: { max: 20, timeWindow: '10 seconds' } },
  }, async () => {
    let dbStatus: Record<string, unknown> = { connected: false };
    if (dbHealth) {
      const h = dbHealth.getHealth();
      dbStatus = { connected: h.connected, pingMs: h.pingMs, poolTotal: h.poolTotal, poolIdle: h.poolIdle, poolWaiting: h.poolWaiting };
    }

    const schedulerTimers = cleanupScheduler ? cleanupScheduler.pending().length : 0;
    metrics?.setGauge('scheduler.timers', schedulerTimers);

    const sessions = sessionRepo ? await sessionRepo.list().catch(() => []) : [];
    const active = sessions.filter((s) => s.status === 'active').length;
    const paused = sessions.filter((s) => s.status === 'paused').length;
    const completed = sessions.filter((s) => s.status === 'completed' || s.status === 'cancelled').length;
    metrics?.setGauge('sessions.active', active);
    metrics?.setGauge('sessions.paused', paused);
    metrics?.setGauge('sessions.completed', completed);

    const callbacks = callbackRepo ? await callbackRepo.list().catch(() => []) : [];
    metrics?.setGauge('callbacks.count', callbacks.length);

    const connectedPhones = getConnectedPhoneCount();
    metrics?.setGauge('signaling.connected_phones', connectedPhones);

    const status = dbHealth ? (dbHealth.getHealth().connected ? 'ok' : 'degraded') : 'ok';

    return {
      status,
      version: '2.0.0',
      timestamp: new Date().toISOString(),
      uptime: process.uptime(),
      database: dbStatus,
      scheduler: { timerCount: schedulerTimers },
      callbacks: { count: callbacks.length },
      sessions: { active, paused, completed },
      signaling: { connectedPhones },
    };
  });

  app.get('/api/v1/ready', {
    config: { rateLimit: { max: 20, timeWindow: '10 seconds' } },
  }, async () => {
    // opts is a live reference — startupComplete is mutated by index.ts after listen()
    const isStartupComplete = opts.startupComplete ?? false;
    const isRecoveryComplete = opts.recoveryComplete ?? true;

    let dbConnected = true;
    if (dbHealth) {
      dbConnected = dbHealth.getHealth().connected;
    }

    const isReady = isStartupComplete && isRecoveryComplete && dbConnected;

    return {
      status: isReady ? 'ok' : 'not_ready',
      startupComplete: isStartupComplete,
      recoveryComplete: isRecoveryComplete,
      databaseConnected: dbConnected,
      repositoriesInitialized: true,
    };
  });

  app.get('/api/v1/metrics', {
    config: { rateLimit: { max: 10, timeWindow: '10 seconds' } },
  }, async () => {
    if (!metrics) {
      return { error: 'METRICS_DISABLED', message: 'Metrics collector not configured' };
    }
    return metrics.snapshot();
  });

  app.post('/api/v1/calls', {
    config: { rateLimit: moderateRateLimit },
  }, async (request, reply) => {
    const start = Date.now();
    const body = request.body as Record<string, unknown>;
    logger.debug({ body: inspectBody(body) }, '[CALLS] step 1 - raw body');

    const userId = (body.user_id as string) ?? 'solo-user';
    // An authenticated AI key (or the service token) determines who is calling;
    // the body agent_id is honoured for backward compatibility when unauthenticated.
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    const agentId = auth.agentName ?? (body.agent_id as string) ?? 'ai-agent';
    const summary = (body.summary as string) ?? (body.context as Record<string, unknown> | undefined)?.summary as string ?? '';
    const reason = (body.reason as string) ?? (body.context as Record<string, unknown> | undefined)?.reason as string ?? 'input_required';
    const taskId = (body.context as Record<string, unknown> | undefined)?.task_id as string | undefined;
    const options = (body.context as Record<string, unknown> | undefined)?.options as string[] | undefined;
    const priority = body.priority as string ?? 'normal';
    logger.debug({ userId, agentId, summary, reason, taskId, options, priority, elapsed: Date.now() - start }, '[CALLS] step 2 - parsed fields');

    if (!summary) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'summary is required in context' });
    }

    const validReasons = ['clarification', 'approval', 'error', 'input_required'];
    if (!validReasons.includes(reason)) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: `reason must be one of: ${validReasons.join(', ')}` });
    }

    logger.debug({ elapsed: Date.now() - start }, '[CALLS] step 3 - before createCall');
    const session = await voicebridge.createCall({
      userId, agentId, reason: reason as CreateCallInput['reason'],
      summary, taskId, options, priority: priority as CreateCallInput['priority'],
    });
    logger.debug({ callId: session.id, elapsed: Date.now() - start }, '[CALLS] step 4 - after createCall');

    metrics?.incrementCounter('sessions.created');
    metrics?.setGauge('sessions.active', await countByStatus(sessionRepo, 'active'));

    return reply.status(201).send({
      call_id: session.id,
      status: 'pending',
      created_at: session.createdAt,
    });
  });

  app.get('/api/v1/calls/:callId', async (request) => {
    const { callId } = request.params as { callId: string };
    const session = await voicebridge.getCall(callId);
    if (!session) {
      return { error: 'NOT_FOUND', message: 'Call not found' };
    }
    return {
      call_id: session.id,
      status: session.status,
      user_id: session.userId,
      agent_id: session.agentId,
      created_at: session.createdAt,
      connected_at: session.connectedAt,
      ended_at: session.completedAt,
      context: session.context ?? null,
      result: session.result ?? null,
      ai_wait: opts.voicebridge.getAiWaitStatus(callId),
      message_count: session.messages.length,
    };
  });

  app.post('/api/v1/calls/:callId/messages', {
    config: { rateLimit: moderateRateLimit },
  }, async (request, reply) => {
    const { callId } = request.params as { callId: string };
    const { content } = request.body as { content: string };

    if (!content) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'content is required' });
    }

    const msg = await voicebridge.addAiMessage(callId, content);
    if (!msg) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Call not found' });
    }

    return reply.status(201).send({
      message_id: msg.id,
      role: msg.role,
      content: msg.content,
      created_at: msg.createdAt,
    });
  });

  app.post('/api/v1/calls/:callId/user-text', {
    config: { rateLimit: moderateRateLimit },
  }, async (request, reply) => {
    try {
      logger.debug({ body: inspectBody(request.body), params: request.params }, '[STT] user-text entered');
      const { callId } = request.params as { callId: string };
      const { text, client_message_id } = request.body as { text: string; client_message_id?: string };

      if (!text || text.trim().length === 0) {
        return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'text is required' });
      }

      logger.debug({ callId, text: text.slice(0, 100) }, '[STT] user-text processing');
      const result = await voicebridge.processTextMessage(callId, text, client_message_id);
      logger.debug({ callId }, '[STT] user-text processed');

      return {
        call_id: callId,
        text: result.text,
      };
    } catch (err) {
      const error = err instanceof Error ? err : new Error(String(err));
      logger.error({
        err: error,
        stack: error.stack,
        cause: (error as Error & { cause?: unknown }).cause,
        body: inspectBody(request.body),
        params: request.params,
      }, '[STT] user-text failed');
      const message = err instanceof Error ? err.message : 'Processing failed';
      return reply.status(404).send({ error: 'NOT_FOUND', message });
    }
  });

  app.get('/api/v1/calls/:callId/transcript', async (request) => {
    const { callId } = request.params as { callId: string };
    const messages = await voicebridge.getTranscript(callId);
    if (!messages) {
      return { error: 'NOT_FOUND', message: 'Call not found' };
    }
    return {
      call_id: callId,
      messages: messages.filter((m) => m.role !== 'system'),
    };
  });

  app.get('/api/v1/calls/:callId/pending-reply', {
    config: { rateLimit: { max: 60, timeWindow: '10 seconds' } },
  }, async (request) => {
    const { callId } = request.params as { callId: string };
    const after = (request.query as { after?: string }).after;
    const session = await voicebridge.getCall(callId);
    if (!session) {
      return { error: 'NOT_FOUND', message: 'Call not found' };
    }

    if (session.status === 'completed' || session.status === 'cancelled') {
      // Terminal states: the last user message since the AI's message is the
      // human's final word — typically a decline/callback note attached to the
      // cancel. Delivering it here means the AI's blocking poll learns WHY the
      // call ended at the same moment it learns the call ended.
      const afterMessage = after ? session.messages.find((m) => m.id === after) : undefined;
      const cutoff = afterMessage ? afterMessage.createdAt : '';
      const terminalNote = [...session.messages].reverse().find(
        (m) => m.role === 'user' && m.createdAt > cutoff,
      );
      return {
        reply: terminalNote
          ? {
              id: terminalNote.id,
              content: terminalNote.content,
              created_at: terminalNote.createdAt,
            }
          : null,
        call_status: session.status,
      };
    }

    const afterIndex = after ? session.messages.findIndex((m) => m.id === after) : -1;
    const startIndex = afterIndex >= 0 ? afterIndex + 1 : 0;
    const nextUserMessage = session.messages.slice(startIndex).find((m) => m.role === 'user');

    if (nextUserMessage) {
      return {
        reply: {
          id: nextUserMessage.id,
          content: nextUserMessage.content,
          created_at: nextUserMessage.createdAt,
        },
      };
    }

    return { reply: null };
  });

  app.post('/api/v1/calls/:callId/complete', async (request, reply) => {
    const { callId } = request.params as { callId: string };
    const { result } = request.body as { result?: Record<string, unknown> };
    const before = await voicebridge.getCall(callId);

    const session = await voicebridge.completeCall(callId, result as Parameters<typeof voicebridge.completeCall>[1]);
    if (!session) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Call not found' });
    }

    if (before && before.status !== 'completed' && before.status !== 'cancelled') {
      metrics?.incrementCounter('sessions.completed');
    }
    return { status: session.status, call_id: callId };
  });

  app.post('/api/v1/calls/:callId/cancel', async (request) => {
    const { callId } = request.params as { callId: string };
    const body = (request.body ?? {}) as { note?: unknown };
    const note = typeof body.note === 'string' ? body.note : undefined;
    const before = await voicebridge.getCall(callId);
    const session = await voicebridge.cancelCall(callId, note);
    if (!session) {
      return { error: 'NOT_FOUND', message: 'Call not found' };
    }

    if (before && before.status !== 'cancelled' && before.status !== 'completed') {
      metrics?.incrementCounter('sessions.cancelled');
    }
    return { status: session.status, call_id: callId };
  });

  app.post('/api/v1/calls/:callId/answer', async (request, reply) => {
    const { callId } = request.params as { callId: string };
    const session = await voicebridge.answerCall(callId);
    if (!session) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Call not found' });
    }
    return { status: session.status, call_id: callId };
  });

  app.get('/api/v1/users/:userId/active-call', async (request) => {
    const { userId } = request.params as { userId: string };
    const session = await voicebridge.getUserActiveCall(userId);
    if (!session) {
      return { active_call: null };
    }
    return {
      active_call: {
        call_id: session.id,
        status: session.status,
        reason: session.reason,
        summary: session.context.summary,
        ai_wait: opts.voicebridge.getAiWaitStatus(session.id),
        created_at: session.createdAt,
      },
    };
  });

  app.post('/api/v1/calls/:callId/callback', async (request, reply) => {
    const { callId } = request.params as { callId: string };
    const { delay_minutes, note } = (request.body ?? {}) as {
      delay_minutes?: unknown;
      note?: unknown;
    };
    const minutes = typeof delay_minutes === 'number' && delay_minutes > 0 ? delay_minutes : 10;
    const noteText = typeof note === 'string' ? note : undefined;

    const ok = await voicebridge.scheduleCallback({
      callId,
      delayMinutes: minutes,
      reason: 'user_requested',
      note: noteText,
    });
    if (!ok) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Call not found' });
    }

    metrics?.incrementCounter('callbacks.scheduled');
    return {
      status: 'callback_scheduled',
      call_id: callId,
      resume_in_minutes: minutes,
    };
  });

  app.post('/api/v1/phone/token', {
    // Tighter than the global limiter: this endpoint is the unauthenticated
    // entry point for a new phone, so cap token minting per client
    config: { rateLimit: { max: 10, timeWindow: '1 minute' } },
  }, async (request) => {
    const { user_id } = request.body as { user_id?: string };
    const userId = user_id ?? 'solo-user';
    const token = await createPhoneToken(userId);
    return { status: 'ok', token, user_id: userId };
  });

  // ── AI keys (multi-client identity) ──────────────────────────
  app.post('/api/v1/ai/keys', {
    config: { rateLimit: { max: 10, timeWindow: '1 minute' } },
  }, async (request, reply) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    if (auth.role !== 'service' && auth.role !== 'user') {
      return reply.status(403).send({ error: 'FORBIDDEN', message: 'Not permitted' });
    }
    const name = String((request.body as Record<string, unknown> | undefined)?.name ?? '').trim();
    if (!name || name.length > 50) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'name is required (max 50 chars)' });
    }
    const created = await createAiKey(name);
    metrics?.incrementCounter('ai_keys.created');
    return reply.status(201).send({
      key_id: created.id,
      name: created.name,
      key: created.key,
      // The plaintext key is returned exactly once
      warning: 'Store this key now — it will not be shown again.',
    });
  });

  app.get('/api/v1/ai/keys', async (request, reply) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    if (auth.role !== 'service' && auth.role !== 'user') {
      return reply.status(403).send({ error: 'FORBIDDEN', message: 'Not permitted' });
    }
    // Agents with an open (pending/active/paused) call are "busy"
    const activeAgentNames = new Set<string>();
    if (sessionRepo) {
      const sessions = await sessionRepo.list().catch(() => []);
      for (const session of sessions) {
        if (session.status === 'pending' || session.status === 'active' || session.status === 'paused') {
          activeAgentNames.add(session.agentId);
        }
      }
    }
    const keys = await listAiKeyStatuses(activeAgentNames);
    return {
      keys: keys.map((k) => ({
        key_id: k.id,
        name: k.name,
        created_at: k.createdAt,
        last_used_at: k.lastSeenAt,
        last_seen_at: k.lastSeenAt,
        online: k.online,
        busy: k.busy,
      })),
    };
  });

  app.delete('/api/v1/ai/keys/:keyId', async (request, reply) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    if (auth.role !== 'service' && auth.role !== 'user') {
      return reply.status(403).send({ error: 'FORBIDDEN', message: 'Not permitted' });
    }
    const { keyId } = request.params as { keyId: string };
    const deleted = await deleteAiKey(keyId);
    if (!deleted) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'AI key not found' });
    }
    metrics?.incrementCounter('ai_keys.deleted');
    return { status: 'deleted', key_id: keyId };
  });

  app.post('/api/v1/phone/register', async (request, reply) => {
    logger.debug({ body: inspectBody(request.body), bodyType: typeof request.body }, '[REGISTER] entered');

    const { user_id } = request.body as { user_id?: string };
    logger.debug({ user_id }, '[REGISTER] parsed body');

    const userId = user_id ?? 'solo-user';
    const wsScheme = request.protocol === 'https' ? 'wss' : 'ws';
    const wsHost = (request.headers['x-forwarded-host'] as string) ?? request.headers.host ?? `localhost:${config.port}`;
    const wsEndpoint = `${wsScheme}://${wsHost}/phone?user_id=${userId}`;

    logger.debug({ userId, wsEndpoint, proto: request.protocol, host: wsHost }, '[REGISTER] phone registration');

    return reply.status(200).send({
      status: 'registered',
      user_id: userId,
      ws_endpoint: wsEndpoint,
    });
  });

}

async function countByStatus(repo: SessionRepository | undefined, status: string): Promise<number> {
  if (!repo) return 0;
  try {
    const sessions = await repo.list();
    return sessions.filter((s) => s.status === status).length;
  } catch {
    return 0;
  }
}
