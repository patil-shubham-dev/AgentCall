import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { registerRoutes } from '../routes.js';
import { registerMcpEndpoint } from '../mcp/endpoint.js';
import { initializePhoneTokens, createPhoneToken } from '../voicebridge/phone-tokens.js';
import { initializeAiKeys, createAiKey, resolveAiKey } from '../voicebridge/ai-keys.js';
import type { FastifyInstance } from 'fastify';

/**
 * Self-test for the delete/rename keyId fix (2026-08-19). The point of this
 * file is *verification through follow-up calls*: after a DELETE or PATCH,
 * every assertion goes back through the public API (or the real identity
 * resolver) to prove the change actually happened server-side, not just that
 * the endpoint returned 2xx.
 */

interface JsonRpcResponse {
  jsonrpc: string;
  id: number;
  result?: Record<string, unknown>;
  error?: { code: number; message: string };
}

let app: FastifyInstance;
let baseUrl: string;
let phoneToken: string;

function parseSse(text: string): unknown[] {
  const messages: unknown[] = [];
  for (const block of text.split(/\r?\n\r?\n/)) {
    const data = block
      .split(/\r?\n/)
      .filter((l) => l.startsWith('data: '))
      .map((l) => l.slice(6))
      .join('\n');
    if (data.length > 0) messages.push(JSON.parse(data));
  }
  return messages;
}

async function postMcp(path: string, body: unknown, headers: Record<string, string> = {}): Promise<{
  status: number;
  body: JsonRpcResponse;
  sessionId: string | null;
}> {
  const res = await fetch(`${baseUrl}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, text/event-stream',
      ...headers,
    },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  const parsed = parseSse(text);
  return {
    status: res.status,
    body: (parsed[0] ?? JSON.parse(text)) as JsonRpcResponse,
    sessionId: res.headers.get('mcp-session-id'),
  };
}

const initializePayload = {
  jsonrpc: '2.0',
  id: 1,
  method: 'initialize',
  params: {
    protocolVersion: '2025-06-18',
    capabilities: {},
    clientInfo: { name: 'self-test-client', version: '1.0' },
  },
};

async function api(path: string, init?: RequestInit): Promise<Response> {
  return fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      Authorization: `Bearer ${phoneToken}`,
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...(init?.headers ?? {}),
    },
  });
}

async function createKeyViaApi(name: string): Promise<{ key_id: string; key: string; name: string }> {
  const res = await api('/api/v1/ai/keys', { method: 'POST', body: JSON.stringify({ name }) });
  expect(res.status).toBe(201);
  const body = (await res.json()) as { key_id: string; key: string; name: string };
  expect(body.key).toMatch(/^ac_/);
  return body;
}

async function listKeys(): Promise<Array<{ key_id: string; name: string }>> {
  const res = await api('/api/v1/ai/keys');
  expect(res.status).toBe(200);
  const body = (await res.json()) as { keys: Array<{ key_id: string; name: string }> };
  return body.keys;
}

beforeAll(async () => {
  await initializePhoneTokens();
  await initializeAiKeys();

  phoneToken = await createPhoneToken('SelfTest');

  const sessionRepo = new InMemorySessionRepository();
  const callbackRepo = new InMemoryCallbackRepository();
  const service = new VoiceBridgeService(sessionRepo, callbackRepo);

  app = Fastify();
  registerRoutes(app, { voicebridge: service, sessionRepo, callbackRepo });
  registerMcpEndpoint(app, service);

  await app.listen({ port: 0, host: '127.0.0.1' });
  const address = app.server.address();
  baseUrl = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';
});

afterAll(async () => {
  await app.close();
});

describe('Delete: server-side revocation is real and verifiable', () => {
  it('delete removes the key — proven by a follow-up GET, and a second DELETE 404s', async () => {
    const created = await createKeyViaApi('Revoke-Me');

    const del = await api(`/api/v1/ai/keys/${created.key_id}`, { method: 'DELETE' });
    expect(del.status).toBe(200);

    // Follow-up: the key must be gone from the public list — not just 2xx.
    const keys = await listKeys();
    expect(keys.find((k) => k.key_id === created.key_id)).toBeUndefined();
    expect(keys.find((k) => k.name === 'Revoke-Me')).toBeUndefined();

    // Follow-up: the revoked plaintext key must no longer resolve.
    const resolved = await resolveAiKey(created.key);
    expect(resolved).toBeNull();

    // Double-delete is a real 404 (the app maps this to "no server key").
    const again = await api(`/api/v1/ai/keys/${created.key_id}`, { method: 'DELETE' });
    expect(again.status).toBe(404);
  });

  it('revoking a key mid-session kills the session — the next MCP request 401s', async () => {
    const created = await createKeyViaApi('MidCall-Ghost');

    // Agent connects and initializes a live MCP session.
    const init = await postMcp('/mcp', initializePayload, {
      Authorization: `Bearer ${created.key}`,
    });
    expect(init.status).toBe(200);
    expect(init.sessionId).toBeTruthy();

    // Phone-side delete revokes the key while the session is "in a call".
    const del = await api(`/api/v1/ai/keys/${created.key_id}`, { method: 'DELETE' });
    expect(del.status).toBe(200);

    // The very next request from that agent (still using its session id) fails auth.
    const follow = await postMcp('/mcp', {
      jsonrpc: '2.0',
      id: 2,
      method: 'tools/list',
      params: {},
    }, {
      Authorization: `Bearer ${created.key}`,
      'Mcp-Session-Id': init.sessionId!,
    });
    expect(follow.status).toBe(401);
  });
});

describe('Rename: the key itself takes the new name', () => {
  it('PATCH renames the key — proven by the identity resolver returning the new name', async () => {
    const created = await createKeyViaApi('Old Name');
    expect((await resolveAiKey(created.key))?.name).toBe('Old Name');

    const patched = await api(`/api/v1/ai/keys/${created.key_id}`, {
      method: 'PATCH',
      body: JSON.stringify({ name: 'New Name' }),
    });
    expect(patched.status).toBe(200);
    const patchedBody = (await patched.json()) as { key_id: string; name: string };
    expect(patchedBody.name).toBe('New Name');

    // Follow-up: the same plaintext key now resolves to the new name — the
    // agent that reconnects with it is the renamed agent.
    const resolved = await resolveAiKey(created.key);
    expect(resolved?.name).toBe('New Name');
    expect(resolved?.id).toBe(created.key_id);

    // Follow-up: the public list agrees.
    const keys = await listKeys();
    expect(keys.find((k) => k.key_id === created.key_id)?.name).toBe('New Name');
  });

  it('PATCH onto another key\'s name is rejected with 409 — no duplicates created', async () => {
    const alpha = await createKeyViaApi('Alpha');
    const beta = await createKeyViaApi('Beta');

    // Rename Alpha → "Beta" collides with Beta's key → 409, name unchanged.
    const patched = await api(`/api/v1/ai/keys/${alpha.key_id}`, {
      method: 'PATCH',
      body: JSON.stringify({ name: 'Beta' }),
    });
    expect(patched.status).toBe(409);
    const err = (await patched.json()) as { error: string; message: string };
    expect(err.error).toBe('NAME_CONFLICT');

    // Follow-up: no duplicate appeared, and both keys keep their own names.
    const keys = await listKeys();
    expect(keys.filter((k) => k.name === 'Beta').length).toBe(1);
    expect(keys.find((k) => k.key_id === alpha.key_id)?.name).toBe('Alpha');
    expect(keys.find((k) => k.key_id === beta.key_id)?.name).toBe('Beta');

    // Follow-up: the plaintext keys still resolve to their own names.
    expect((await resolveAiKey(alpha.key))?.name).toBe('Alpha');
    expect((await resolveAiKey(beta.key))?.name).toBe('Beta');
  });

  it('PATCH to a key\'s own current name is a harmless no-op (200)', async () => {
    const created = await createKeyViaApi('Self-Same');
    const patched = await api(`/api/v1/ai/keys/${created.key_id}`, {
      method: 'PATCH',
      body: JSON.stringify({ name: 'Self-Same' }),
    });
    expect(patched.status).toBe(200);
    const keys = await listKeys();
    expect(keys.filter((k) => k.name === 'Self-Same').length).toBe(1);
  });

  it('POST with a duplicate name is rejected with 409 and creates no key', async () => {
    const original = await createKeyViaApi('Duplicate-Me');

    const duplicate = await api('/api/v1/ai/keys', { method: 'POST', body: JSON.stringify({ name: 'Duplicate-Me' }) });
    expect(duplicate.status).toBe(409);
    const err = (await duplicate.json()) as { error: string; message: string };
    expect(err.error).toBe('NAME_CONFLICT');

    // Follow-up: still exactly one key with that name, and only the original exists.
    const keys = await listKeys();
    expect(keys.filter((k) => k.name === 'Duplicate-Me').length).toBe(1);
    expect(keys.map((k) => k.key_id)).toContain(original.key_id);
  });

  it('PATCH validates name (blank/oversized → 400) and unknown id → 404', async () => {
    const created = await createKeyViaApi('Validate-Me');
    const blank = await api(`/api/v1/ai/keys/${created.key_id}`, {
      method: 'PATCH',
      body: JSON.stringify({ name: '   ' }),
    });
    expect(blank.status).toBe(400);
    const oversized = await api(`/api/v1/ai/keys/${created.key_id}`, {
      method: 'PATCH',
      body: JSON.stringify({ name: 'x'.repeat(51) }),
    });
    expect(oversized.status).toBe(400);
    const unknown = await api('/api/v1/ai/keys/nope', {
      method: 'PATCH',
      body: JSON.stringify({ name: 'X' }),
    });
    expect(unknown.status).toBe(404);
  });
});

describe('createAiKey unit-level follow-up (module direct, no HTTP)', () => {
  it('a created key resolves immediately with the requested name and its own id', async () => {
    const created = await createAiKey('Direct-Create');
    const resolved = await resolveAiKey(created.key);
    expect(resolved?.name).toBe('Direct-Create');
    expect(resolved?.id).toBe(created.id);
  });
});