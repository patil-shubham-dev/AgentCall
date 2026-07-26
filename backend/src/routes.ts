import type { FastifyInstance, FastifyRequest } from 'fastify';
import { config } from './common/config.js';
import { logger } from './common/logger.js';
import * as voicebridge from './voicebridge/service.js';
import type { CreateCallInput } from './voicebridge/types.js';

function inspectBody(body: unknown): string {
  if (body === null || body === undefined) return String(body);
  if (typeof body === 'object') {
    try { return JSON.stringify(body); } catch { return String(body); }
  }
  return String(body);
}

const strictRateLimit = { max: 10, timeWindow: '1 minute' };
const moderateRateLimit = { max: 60, timeWindow: '1 minute' };

interface AuthContext {
  userId: string;
  role: 'user' | 'agent' | 'service';
}

async function getAuthUser(request: FastifyRequest): Promise<AuthContext> {
  const header = request.headers.authorization;
  if (!header?.startsWith('Bearer ')) {
    return { userId: 'solo-user', role: 'user' };
  }
  const token = header.slice(7);
  if (token === config.serviceToken) {
    return { userId: 'service', role: 'service' };
  }
  return { userId: 'solo-user', role: 'user' };
}

export function registerRoutes(app: FastifyInstance): void {
  app.addHook('onRequest', async (request) => {
    const url = request.url ?? '';
    if (url.startsWith('/api/v1/health')) {
      logger.info({ method: request.method, url }, '[HTTP] health check');
      return;
    }
    (request as FastifyRequest & { auth: AuthContext }).auth = await getAuthUser(request);
    logger.info({ method: request.method, url, auth: (request as FastifyRequest & { auth: AuthContext }).auth }, '[HTTP] request');
  });

  app.get('/api/v1/health', {
    config: { rateLimit: { max: 20, timeWindow: '10 seconds' } },
  }, async () => ({
    status: 'ok',
    version: '2.0.0',
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
  }));

  app.post('/api/v1/calls', {
    config: { rateLimit: moderateRateLimit },
  }, async (request, reply) => {
    const start = Date.now();
    const body = request.body as Record<string, unknown>;
    logger.info({ body: inspectBody(body) }, '[CALLS] step 1 - raw body');

    const userId = (body.user_id as string) ?? 'solo-user';
    const agentId = (body.agent_id as string) ?? 'ai-agent';
    const summary = (body.summary as string) ?? (body.context as Record<string, unknown> | undefined)?.summary as string ?? '';
    const reason = (body.reason as string) ?? (body.context as Record<string, unknown> | undefined)?.reason as string ?? 'input_required';
    const taskId = (body.context as Record<string, unknown> | undefined)?.task_id as string | undefined;
    const options = (body.context as Record<string, unknown> | undefined)?.options as string[] | undefined;
    const priority = body.priority as string ?? 'normal';
    logger.info({ userId, agentId, summary, reason, taskId, options, priority, elapsed: Date.now() - start }, '[CALLS] step 2 - parsed fields');

    if (!summary) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'summary is required in context' });
    }

    const validReasons = ['clarification', 'approval', 'error', 'input_required'];
    if (!validReasons.includes(reason)) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: `reason must be one of: ${validReasons.join(', ')}` });
    }

    logger.info({ elapsed: Date.now() - start }, '[CALLS] step 3 - before createCall');
    const session = voicebridge.createCall({
      userId, agentId, reason: reason as CreateCallInput['reason'],
      summary, taskId, options, priority: priority as CreateCallInput['priority'],
    });
    logger.info({ callId: session.id, elapsed: Date.now() - start }, '[CALLS] step 4 - after createCall');

    return reply.status(201).send({
      call_id: session.id,
      status: 'pending',
      created_at: session.createdAt,
    });
  });

  app.get('/api/v1/calls/:callId', async (request) => {
    const { callId } = request.params as { callId: string };
    const session = voicebridge.getCall(callId);
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
      result: session.result ?? null,
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

    const msg = voicebridge.addAiMessage(callId, content);
    if (!msg) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Call not found' });
    }

    return reply.status(201).send({
      message_id: msg.id,
      role: msg.role,
      content: msg.content,
      enriched: msg.enriched,
      created_at: msg.createdAt,
    });
  });

  app.post('/api/v1/calls/:callId/user-text', {
    config: { rateLimit: moderateRateLimit },
  }, async (request, reply) => {
    try {
      logger.info({ body: inspectBody(request.body), params: request.params }, '[STT] user-text entered');
      const { callId } = request.params as { callId: string };
      const { text } = request.body as { text: string };

      if (!text || text.trim().length === 0) {
        return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'text is required' });
      }

      logger.info({ callId, text: text.slice(0, 100) }, '[STT] user-text processing');
      const result = voicebridge.processTextMessage(callId, text);
      logger.info({ callId, bargeIn: result.bargeIn.action }, '[STT] user-text processed');

      return {
        call_id: callId,
        text: result.text,
        barge_in: result.bargeIn,
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
    const messages = voicebridge.getTranscript(callId);
    if (!messages) {
      return { error: 'NOT_FOUND', message: 'Call not found' };
    }
    return {
      call_id: callId,
      messages: messages.filter((m) => m.role !== 'system'),
    };
  });

  app.post('/api/v1/calls/:callId/complete', async (request, reply) => {
    const { callId } = request.params as { callId: string };
    const { result } = request.body as { result?: Record<string, unknown> };

    const session = voicebridge.completeCall(callId, result as Parameters<typeof voicebridge.completeCall>[1]);
    if (!session) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Call not found' });
    }

    return { status: 'completed', call_id: callId };
  });

  app.post('/api/v1/calls/:callId/cancel', async (request) => {
    const { callId } = request.params as { callId: string };
    const session = voicebridge.cancelCall(callId);
    if (!session) {
      return { error: 'NOT_FOUND', message: 'Call not found' };
    }
    return { status: 'cancelled', call_id: callId };
  });

  app.get('/api/v1/users/:userId/active-call', async (request) => {
    const { userId } = request.params as { userId: string };
    const session = voicebridge.getUserActiveCall(userId);
    if (!session) {
      return { active_call: null };
    }
    return {
      active_call: {
        call_id: session.id,
        status: session.status,
        reason: session.reason,
        summary: session.context.summary,
        created_at: session.createdAt,
      },
    };
  });

  app.post('/api/v1/calls/:callId/callback', async (request, reply) => {
    const { callId } = request.params as { callId: string };
    const { delay_minutes } = request.body as { delay_minutes?: number };
    const minutes = delay_minutes ?? 10;

    const ok = voicebridge.scheduleCallback({ callId, delayMinutes: minutes, reason: 'user_requested' });
    if (!ok) {
      return reply.status(404).send({ error: 'NOT_FOUND', message: 'Call not found' });
    }

    return {
      status: 'callback_scheduled',
      call_id: callId,
      resume_in_minutes: minutes,
    };
  });

  app.post('/api/v1/phone/register', async (request, reply) => {
    logger.info({ body: inspectBody(request.body), bodyType: typeof request.body }, '[REGISTER] entered');

    const { user_id } = request.body as { user_id?: string };
    logger.info({ user_id }, '[REGISTER] parsed body');

    const userId = user_id ?? 'solo-user';
    const wsScheme = request.protocol === 'https' ? 'wss' : 'ws';
    const wsHost = (request.headers['x-forwarded-host'] as string) ?? request.headers.host ?? `localhost:${config.port}`;
    const wsEndpoint = `${wsScheme}://${wsHost}/phone?user_id=${userId}`;

    logger.info({ userId, wsEndpoint, proto: request.protocol, host: wsHost }, '[REGISTER] phone registration');

    return reply.status(200).send({
      status: 'registered',
      user_id: userId,
      ws_endpoint: wsEndpoint,
    });
  });
}
