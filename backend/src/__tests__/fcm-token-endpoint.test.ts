import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { registerRoutes } from '../routes.js';
import { initializePhoneTokens, createPhoneToken } from '../voicebridge/phone-tokens.js';
import { initializeFcmTokens, getFcmToken } from '../voicebridge/fcm-tokens.js';
import type { FastifyInstance } from 'fastify';

let app: FastifyInstance;
let baseUrl: string;
let phoneToken: string;

beforeAll(async () => {
  await initializePhoneTokens();
  await initializeFcmTokens();

  phoneToken = await createPhoneToken('UserFcm');

  const sessionRepo = new InMemorySessionRepository();
  const callbackRepo = new InMemoryCallbackRepository();
  const service = new VoiceBridgeService(sessionRepo, callbackRepo);

  app = Fastify();
  registerRoutes(app, { voicebridge: service, sessionRepo, callbackRepo });

  await app.listen({ port: 0, host: '127.0.0.1' });
  const address = app.server.address();
  baseUrl = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';
});

afterAll(async () => {
  await app.close();
});

describe('POST /api/v1/phone/fcm-token', () => {
  it('registers the token with a valid phone token', async () => {
    const res = await fetch(`${baseUrl}/api/v1/phone/fcm-token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${phoneToken}` },
      body: JSON.stringify({ fcm_token: 'device-token-123' }),
    });
    expect(res.status).toBe(200);
    expect(await getFcmToken('UserFcm')).toBe('device-token-123');
  });

  it('rejects requests without a phone token', async () => {
    const res = await fetch(`${baseUrl}/api/v1/phone/fcm-token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fcm_token: 'device-token-123' }),
    });
    expect(res.status).toBe(401);
  });

  it('rejects a missing or blank fcm_token', async () => {
    const res = await fetch(`${baseUrl}/api/v1/phone/fcm-token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${phoneToken}` },
      body: JSON.stringify({ fcm_token: '   ' }),
    });
    expect(res.status).toBe(400);
    const body = (await res.json()) as { error: string };
    expect(body.error).toBe('VALIDATION_ERROR');
  });

  it('re-registering replaces the previous token (rotation)', async () => {
    const res = await fetch(`${baseUrl}/api/v1/phone/fcm-token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${phoneToken}` },
      body: JSON.stringify({ fcm_token: 'device-token-rotated' }),
    });
    expect(res.status).toBe(200);
    expect(await getFcmToken('UserFcm')).toBe('device-token-rotated');
  });
});
