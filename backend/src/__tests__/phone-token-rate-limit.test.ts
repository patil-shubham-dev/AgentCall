import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import Fastify from 'fastify';
import rateLimit from '@fastify/rate-limit';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { registerRoutes } from '../routes.js';
import type { FastifyInstance } from 'fastify';

let app: FastifyInstance;
let baseUrl = '';

beforeEach(async () => {
  app = Fastify();
  const service = new VoiceBridgeService(new InMemorySessionRepository(), new InMemoryCallbackRepository());
  await app.register(rateLimit, {
    max: 100,
    timeWindow: '1 minute',
    // Mirrors production index.ts: the builder must throw a real 429 error
    // (a plain object would surface as a 500 through Fastify's error handling)
    errorResponseBuilder: (_req, context) => {
      const err = new Error(`Too many requests. Rate limit: ${context.max} per ${context.after}`) as Error & {
        statusCode: number;
        code: string;
      };
      err.statusCode = 429;
      err.code = 'RATE_LIMITED';
      return err;
    },
  });
  registerRoutes(app, { voicebridge: service });
  // Mirrors production index.ts error handler: maps a thrown error's `code`
  // (e.g. RATE_LIMITED) into the structured { error, message, request_id } body
  app.setErrorHandler(async (error, request, reply) => {
    const errAny = error as Error & { statusCode?: number; code?: string };
    const statusCode = errAny.statusCode ?? 500;
    if (typeof errAny.code === 'string') {
      return reply.status(statusCode).send({
        error: errAny.code,
        message: errAny.message ?? 'Unknown error',
        request_id: request.id,
      });
    }
    return reply.status(statusCode).send({ message: errAny.message ?? 'Unknown error', request_id: request.id });
  });
  await app.listen({ port: 0, host: '127.0.0.1' });
  const address = app.server.address();
  baseUrl = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';
});

afterEach(async () => {
  await app.close();
});

async function mintToken(userId: string): Promise<number> {
  const res = await fetch(`${baseUrl}/api/v1/phone/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ user_id: userId }),
  });
  return res.status;
}

describe('POST /api/v1/phone/token rate limit', () => {
  it('mints an unauthenticated token with status ok + token body', async () => {
    const res = await fetch(`${baseUrl}/api/v1/phone/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ user_id: 'solo-user' }),
    });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { status?: string; token?: string };
    expect(body.status).toBe('ok');
    expect(typeof body.token).toBe('string');
    expect((body.token ?? '').length).toBeGreaterThan(20);
  });

  it('rejects the 11th mint in the same minute with RATE_LIMITED', async () => {
    for (let i = 0; i < 10; i++) {
      expect(await mintToken('solo-user')).toBe(200);
    }

    const denied = await fetch(`${baseUrl}/api/v1/phone/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ user_id: 'solo-user' }),
    });
    expect(denied.status).toBe(429);
    const body = (await denied.json()) as { error?: string };
    expect(body.error).toBe('RATE_LIMITED');
  });

  it('does not affect authenticated endpoints (the limit is endpoint-scoped)', async () => {
    // consume the phone/token budget, then prove /api/v1/ai/keys (authed) is unaffected
    for (let i = 0; i < 11; i++) {
      await mintToken('solo-user');
    }

    const res = await fetch(`${baseUrl}/api/v1/ai/keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer test-service-token` },
      body: JSON.stringify({ name: 'RateTestAI' }),
    });
    // ai/keys has its own 10/min limit; this is its first hit in this fresh app
    expect(res.status).toBe(201);
  });
});