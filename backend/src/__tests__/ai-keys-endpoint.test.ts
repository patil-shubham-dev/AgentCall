import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { registerRoutes } from '../routes.js';
import { initializePhoneTokens, createPhoneToken } from '../voicebridge/phone-tokens.js';
import { initializeAiKeys } from '../voicebridge/ai-keys.js';
import type { FastifyInstance } from 'fastify';

let app: FastifyInstance;
let baseUrl: string;
let phoneToken: string;

beforeAll(async () => {
  await initializePhoneTokens();
  await initializeAiKeys();

  phoneToken = await createPhoneToken('KeyOps');

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

describe('AI key lifecycle (REST)', () => {
  it('creates, renames, lists, and deletes a key by id', async () => {
    const created = await fetch(`${baseUrl}/api/v1/ai/keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${phoneToken}` },
      body: JSON.stringify({ name: 'Work Assistant' }),
    });
    expect(created.status).toBe(201);
    const createdBody = (await created.json()) as { key_id: string; name: string; key: string };
    expect(createdBody.key).toMatch(/^ac_/);

    const renamed = await fetch(`${baseUrl}/api/v1/ai/keys/${createdBody.key_id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${phoneToken}` },
      body: JSON.stringify({ name: 'Renamed Assistant' }),
    });
    expect(renamed.status).toBe(200);
    const renamedBody = (await renamed.json()) as { key_id: string; name: string };
    expect(renamedBody.name).toBe('Renamed Assistant');

    const listed = await fetch(`${baseUrl}/api/v1/ai/keys`, {
      headers: { Authorization: `Bearer ${phoneToken}` },
    });
    const listedBody = (await listed.json()) as { keys: Array<{ key_id: string; name: string }> };
    expect(listedBody.keys.find((k) => k.key_id === createdBody.key_id)?.name).toBe('Renamed Assistant');

    const deleted = await fetch(`${baseUrl}/api/v1/ai/keys/${createdBody.key_id}`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${phoneToken}` },
    });
    expect(deleted.status).toBe(200);
  });

  it('returns 404 when renaming or deleting an unknown key id', async () => {
    const renameRes = await fetch(`${baseUrl}/api/v1/ai/keys/does-not-exist`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${phoneToken}` },
      body: JSON.stringify({ name: 'X' }),
    });
    expect(renameRes.status).toBe(404);

    const deleteRes = await fetch(`${baseUrl}/api/v1/ai/keys/does-not-exist`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${phoneToken}` },
    });
    expect(deleteRes.status).toBe(404);
  });

  it('rejects blank or oversized rename names', async () => {
    const created = await fetch(`${baseUrl}/api/v1/ai/keys`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${phoneToken}` },
      body: JSON.stringify({ name: 'Temp' }),
    });
    const body = (await created.json()) as { key_id: string };

    const blank = await fetch(`${baseUrl}/api/v1/ai/keys/${body.key_id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${phoneToken}` },
      body: JSON.stringify({ name: '   ' }),
    });
    expect(blank.status).toBe(400);

    const oversized = await fetch(`${baseUrl}/api/v1/ai/keys/${body.key_id}`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${phoneToken}` },
      body: JSON.stringify({ name: 'x'.repeat(51) }),
    });
    expect(oversized.status).toBe(400);
  });
});