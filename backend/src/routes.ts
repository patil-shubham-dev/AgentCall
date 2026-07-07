import crypto from 'node:crypto';
import type { FastifyInstance, FastifyRequest } from 'fastify';
import { db } from './db/connection.js';
import { redis } from './db/redis.js';
import { validate, createCallSchema, cancelCallSchema, deviceRegisterSchema, notifySchema } from './common/validation.js';
import { UnauthorizedError } from './common/errors.js';
import * as authService from './auth/service.js';
import * as presenceService from './presence/service.js';
import * as callService from './call/service.js';
import * as notificationService from './notification/service.js';
import { generateTURNCredentials } from './auth/service.js';
import { getCall } from './call/service.js';
import { config } from './common/config.js';

interface AuthContext {
  userId: string;
  role: 'user' | 'agent' | 'service';
}

async function getAuthUser(request: FastifyRequest): Promise<AuthContext> {
  const header = request.headers.authorization;
  if (!header?.startsWith('Bearer ')) throw new UnauthorizedError('Missing authorization header');

  const token = header.slice(7);

  // Service token (env var)
  if (token === config.serviceToken) {
    return { userId: 'service', role: 'service' };
  }

  // API key (icm_ prefix)
  if (token.startsWith('icm_')) {
    const keyHash = crypto.createHash('sha256').update(token).digest('hex');
    const key = await db('api_keys').where({ key_hash: keyHash, is_active: true }).first();
    if (!key) throw new UnauthorizedError('Invalid API key');
    return { userId: (key as { user_id: string }).user_id, role: 'agent' };
  }

  // JWT access token
  const payload = authService.verifyAccessToken(token);
  return { userId: payload.sub, role: payload.role };
}

const strictRateLimit = { max: 10, timeWindow: '1 minute' };
const moderateRateLimit = { max: 60, timeWindow: '1 minute' };

export function registerRoutes(app: FastifyInstance): void {
  // ── Health ────────────────────────────────────────────────────────────
  app.get('/api/v1/health', {
    config: { rateLimit: { max: 20, timeWindow: '10 seconds' } },
  }, async () => {
    const checks: Record<string, string> = {};
    let healthy = true;

    try {
      await db.raw('SELECT 1');
      checks.postgres = 'ok';
    } catch {
      checks.postgres = 'down';
      healthy = false;
    }

    try {
      await redis.ping();
      checks.redis = 'ok';
    } catch {
      checks.redis = 'down';
      healthy = false;
    }

    return {
      status: healthy ? 'ok' : 'degraded',
      timestamp: new Date().toISOString(),
      uptime: process.uptime(),
      checks,
    };
  });

  // ── Auth hook (all routes except health) ────────────────────────────
  app.addHook('onRequest', async (request) => {
    const url = request.url ?? '';
    if (url.startsWith('/api/v1/health')) return;
    if (url.startsWith('/api/v1/auth/')) return; // login/refresh don't need auth
    (request as FastifyRequest & { auth: AuthContext }).auth = await getAuthUser(request);
  });

  // ── Auth routes (no auth required) ────────────────────────────────────
  app.post('/api/v1/auth/login', {
    config: { rateLimit: strictRateLimit },
  }, async (request, reply) => {
    const body = request.body as Record<string, unknown>;
    const email = typeof body.email === 'string' ? body.email : '';
    if (!email) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'email is required' });
    }

    const displayName = typeof body.display_name === 'string' ? body.display_name : email.split('@')[0] ?? email;
    const userId = await authService.findOrCreateUser(email, displayName);
    const accessToken = authService.signAccessToken(userId);
    const refreshToken = await authService.storeRefreshToken(userId, null, new Date(Date.now() + 30 * 24 * 60 * 60 * 1000));

    return {
      access_token: accessToken,
      refresh_token: refreshToken,
      user_id: userId,
    };
  });

  app.post('/api/v1/auth/refresh', {
    config: { rateLimit: strictRateLimit },
  }, async (request, reply) => {
    const { refresh_token } = request.body as { refresh_token: string };
    if (!refresh_token) {
      return reply.status(400).send({ error: 'VALIDATION_ERROR', message: 'refresh_token is required' });
    }

    try {
      const userId = await authService.validateRefreshToken(refresh_token);
      await authService.revokeRefreshToken(refresh_token);
      const accessToken = authService.signAccessToken(userId);
      const newRefreshToken = await authService.storeRefreshToken(userId, null, new Date(Date.now() + 30 * 24 * 60 * 60 * 1000));
      return { access_token: accessToken, refresh_token: newRefreshToken, user_id: userId };
    } catch {
      return reply.status(401).send({ error: 'INVALID_TOKEN', message: 'Invalid or expired refresh token' });
    }
  });

  // ── Calls ─────────────────────────────────────────────────────────────
  app.post('/api/v1/calls', {
    config: { rateLimit: moderateRateLimit },
  }, async (request, reply) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    const input = validate(createCallSchema, request.body);
    const timeoutSec = input.timeout_seconds ?? 30;

    if (input.agent_id !== auth.userId && auth.role !== 'service') {
      return reply.status(403).send({ error: 'FORBIDDEN', message: 'Not authorized to create calls for this agent' });
    }

    const busy = await callService.isUserBusy(input.user_id);
    if (busy) {
      return reply.status(409).send({ error: 'USER_BUSY', message: 'User is currently on another call' });
    }

    const { callId, status } = await callService.createCall(
      input.user_id, input.agent_id, input.context, input.priority, timeoutSec,
    );

    await callService.markRinging(callId);

    notificationService.sendPushToUser(input.user_id, {
      type: 'call_incoming',
      call_id: callId,
      caller_name: 'AI Agent',
      context_summary: input.context.summary,
      priority: input.priority,
    }).catch(() => {});

    return reply.status(201).send({
      call_id: callId,
      status,
      expires_at: new Date(Date.now() + timeoutSec * 1000).toISOString(),
    });
  });

  app.get('/api/v1/calls/:callId', async (request) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    const { callId } = request.params as { callId: string };
    const call = await getCall(callId);

    if (call.user_id !== auth.userId && call.agent_id !== auth.userId && auth.role !== 'service') {
      throw new UnauthorizedError('Not authorized to view this call');
    }

    return {
      call_id: call.id,
      status: call.status,
      user_id: call.user_id,
      agent_id: call.agent_id,
      created_at: call.created_at,
      connected_at: call.connected_at,
      ended_at: call.ended_at,
      duration_seconds: call.duration_ms ? Math.floor(call.duration_ms / 1000) : null,
      result: call.result,
    };
  });

  app.post('/api/v1/calls/:callId/cancel', async (request) => {
    const { callId } = request.params as { callId: string };
    const input = validate(cancelCallSchema, request.body);
    const call = await getCall(callId);

    if (['ended', 'cancelled', 'timed_out'].includes(call.status)) {
      return { status: 'already_ended' };
    }

    await callService.updateCallStatus(callId, 'cancelled');
    return { status: 'cancelled' };
  });

  app.post('/api/v1/calls/:callId/complete', async (request) => {
    const { callId } = request.params as { callId: string };
    const { result } = request.body as { result: Record<string, unknown> };
    await callService.completeCall(callId, result);
    return { status: 'completed' };
  });

  // ── Presence ──────────────────────────────────────────────────────────
  app.get('/api/v1/users/:userId/presence', async (request) => {
    const { userId } = request.params as { userId: string };
    const presence = await presenceService.getUserPresence(userId);
    const devices = await presenceService.getActiveDevices(userId);

    return {
      user_id: userId,
      status: presence.status,
      last_seen: presence.last_seen,
      dnd: presence.dnd,
      devices,
    };
  });

  app.post('/api/v1/presence/heartbeat', async (request) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    const { device_id, platform } = request.body as { device_id: string; platform: string };
    await presenceService.refreshHeartbeat(auth.userId, device_id, platform as 'android' | 'ios' | 'web');
    return { status: 'ok' };
  });

  // ── Devices ───────────────────────────────────────────────────────────
  app.post('/api/v1/devices/register', async (request, reply) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    const input = validate(deviceRegisterSchema, request.body);

    const deviceId = await authService.registerDevice(
      auth.userId, input.platform, input.push_token, input.device_name,
    );

    return reply.status(201).send({ device_id: deviceId, status: 'registered' });
  });

  app.delete('/api/v1/devices/:deviceId', async (request) => {
    const { deviceId } = request.params as { deviceId: string };
    await authService.removeDevice(deviceId);
    return { status: 'removed' };
  });

  // ── Notifications ─────────────────────────────────────────────────────
  app.post('/api/v1/notifications', async (request) => {
    const input = validate(notifySchema, request.body);
    const result = await notificationService.sendPushToUser(input.user_id, {
      type: input.type as 'call_incoming' | 'task_complete' | 'call_missed',
      ...(input.payload as Record<string, string>),
    });
    return { status: result.delivered > 0 ? 'delivered' : 'failed', device_targets: result.total };
  });

  // ── TURN credentials ──────────────────────────────────────────────────
  app.get('/api/v1/turn/credentials', async (request) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    return generateTURNCredentials(auth.userId === 'service' ? 'service' : auth.userId);
  });

  // ── Call history ──────────────────────────────────────────────────────
  app.get('/api/v1/calls', async (request) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    const calls = await callService.getCallHistory(auth.userId);
    return { calls };
  });

  // ── API Keys ──────────────────────────────────────────────────────────
  app.post('/api/v1/api-keys', async (request) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    const { name } = request.body as { name: string };
    return authService.createApiKey(auth.userId, name);
  });

  app.get('/api/v1/api-keys', async (request) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    const keys = await authService.listApiKeys(auth.userId);
    return { api_keys: keys };
  });

  app.delete('/api/v1/api-keys/:keyId', async (request) => {
    const auth = (request as FastifyRequest & { auth: AuthContext }).auth;
    const { keyId } = request.params as { keyId: string };
    await authService.revokeApiKey(keyId, auth.userId);
    return { status: 'deleted' };
  });
}
