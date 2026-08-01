import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { registerMcpEndpoint } from '../mcp/endpoint.js';
import { registerRoutes } from '../routes.js';
import { initializeAiKeys, createAiKey } from '../voicebridge/ai-keys.js';
import { initializePhoneTokens, createPhoneToken } from '../voicebridge/phone-tokens.js';
import type { FastifyInstance } from 'fastify';

interface JsonRpcResponse {
  jsonrpc: string;
  id: number;
  result?: Record<string, unknown>;
  error?: { code: number; message: string };
}

let app: FastifyInstance;
let baseUrl: string;
let service: VoiceBridgeService;
let aiKey: string;

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

async function postMcp(path: string, body: unknown, headers: Record<string, string> = {}, base: string = baseUrl): Promise<{
  status: number;
  body: JsonRpcResponse;
  sessionId: string | null;
}> {
  const res = await fetch(`${base}${path}`, {
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
    clientInfo: { name: 'test-client', version: '1.0' },
  },
};

beforeAll(async () => {
  await initializeAiKeys();
  const created = await createAiKey('TestAgent');
  aiKey = created.key;

  service = new VoiceBridgeService(new InMemorySessionRepository(), new InMemoryCallbackRepository());
  app = Fastify();
  registerMcpEndpoint(app, service);
  await app.listen({ port: 0, host: '127.0.0.1' });
  const address = app.server.address();
  baseUrl = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';
});

afterAll(async () => {
  await app.close();
});

describe('MCP endpoint auth', () => {
  it('returns 401 without credentials', async () => {
    const { status, body } = await postMcp('/mcp', initializePayload);
    expect(status).toBe(401);
    expect(body.error).toBe('UNAUTHORIZED');
  });

  it('returns 401 with an unknown key', async () => {
    const { status } = await postMcp('/mcp', initializePayload, {
      Authorization: 'Bearer ac_invalid_key_0000000000000000000000000000000000000000',
    });
    expect(status).toBe(401);
  });

  it('accepts Authorization Bearer, x-api-key, and ?key= query credentials', async () => {
    for (const headers of [
      { Authorization: `Bearer ${aiKey}` },
      { 'x-api-key': aiKey },
    ]) {
      const { status, body, sessionId } = await postMcp('/mcp', initializePayload, headers);
      expect(status).toBe(200);
      expect(body.result?.protocolVersion).toBeTruthy();
      expect(sessionId).toBeTruthy();
    }
    const { status, body } = await postMcp('/mcp?key=' + encodeURIComponent(aiKey), initializePayload);
    expect(status).toBe(200);
    expect(body.result?.protocolVersion).toBeTruthy();
  });
});

describe('MCP endpoint behind the global auth hook', () => {
  it('?key= query credentials are not blocked by the global auth middleware', async () => {
    const app2 = Fastify();
    registerRoutes(app2, { voicebridge: service });
    registerMcpEndpoint(app2, service);
    await app2.listen({ port: 0, host: '127.0.0.1' });
    const address = app2.server.address();
    const base = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';

    const res = await fetch(`${base}/mcp?key=${encodeURIComponent(aiKey)}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' },
      body: JSON.stringify(initializePayload),
    });
    const text = await res.text();
    const parsed = parseSse(text);
    expect(res.status).toBe(200);
    expect((parsed[0] as JsonRpcResponse).result?.protocolVersion).toBeTruthy();
    await app2.close();
  });
});

describe('AI availability endpoint', () => {
  it('reports online/busy per key from live usage and active calls', async () => {
    const sessionRepo = new InMemorySessionRepository();
    const svc = new VoiceBridgeService(sessionRepo, new InMemoryCallbackRepository());
    const app2 = Fastify();
    registerRoutes(app2, { voicebridge: svc, sessionRepo });
    registerMcpEndpoint(app2, svc);
    await app2.listen({ port: 0, host: '127.0.0.1' });
    const address = app2.server.address();
    const base = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';

    await initializePhoneTokens();
    const phoneToken = await createPhoneToken('solo-user');

    // Use the AI key over MCP and create an active call so the key is online + busy
    const init = await postMcp('/mcp', initializePayload, { Authorization: `Bearer ${aiKey}` }, base);
    const sid = init.sessionId;
    expect(sid).toBeTruthy();
    await postMcp('/mcp', { jsonrpc: '2.0', id: 21, method: 'notifications/initialized', params: {} }, {
      Authorization: `Bearer ${aiKey}`,
      'Mcp-Session-Id': sid ?? '',
    }, base);
    const call = await postMcp('/mcp', {
      jsonrpc: '2.0',
      id: 22,
      method: 'tools/call',
      params: {
        name: 'create_call',
        arguments: { context: { reason: 'clarification', summary: 'availability test' } },
      },
    }, { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid ?? '' }, base);
    const callResult = JSON.parse(call.body.result?.content?.[0]?.text ?? '{}') as { call_id: string };
    expect(callResult.call_id).toBeTruthy();

    const res = await fetch(`${base}/api/v1/ai/keys`, {
      headers: { Authorization: `Bearer ${phoneToken}` },
    });
    expect(res.status).toBe(200);
    const body = (await res.json()) as {
      keys: Array<{
        key_id: string;
        name: string;
        last_seen_at: string | null;
        online: boolean;
        busy: boolean;
      }>;
    };
    const key = body.keys.find((k) => k.name === 'TestAgent');
    expect(key).toBeTruthy();
    expect(key?.online).toBe(true);
    expect(key?.busy).toBe(true);
    expect(key?.last_seen_at).toBeTruthy();
    await app2.close();
  });
});

describe('MCP tools', () => {
  let sessionId: string | null;

  beforeAll(async () => {
    const res = await postMcp('/mcp', initializePayload, { Authorization: `Bearer ${aiKey}` });
    expect(res.status).toBe(200);
    sessionId = res.sessionId;
    await postMcp('/mcp', { jsonrpc: '2.0', id: 11, method: 'notifications/initialized', params: {} }, {
      Authorization: `Bearer ${aiKey}`,
      'Mcp-Session-Id': sessionId ?? '',
    });
  });

  it('lists all six tools on an initialized session', async () => {
    const { status, body } = await postMcp('/mcp', {
      jsonrpc: '2.0',
      id: 2,
      method: 'tools/list',
      params: {},
    }, { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sessionId ?? '' });
    expect(status).toBe(200);
    const result = body.result as { tools: Array<{ name: string }> };
    const names = result.tools.map((t) => t.name);
    expect(names).toEqual([
      'create_call',
      'send_message',
      'get_transcript',
      'complete_call',
      'cancel_call',
      'send_message_and_wait',
    ]);
  });

  it('tags created calls with the authenticated AI identity', async () => {
    const { status, body } = await postMcp('/mcp', {
      jsonrpc: '2.0',
      id: 3,
      method: 'tools/call',
      params: {
        name: 'create_call',
        arguments: {
          context: { reason: 'clarification', summary: 'identity tagging test' },
        },
      },
    }, { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sessionId ?? '' });
    expect(status).toBe(200);
    const result = body.result as { content: Array<{ text: string }> };
    const parsed = JSON.parse(result.content[0]?.text ?? '{}') as { call_id: string };
    expect(parsed.call_id).toBeTruthy();

    const session = await service.getCall(parsed.call_id);
    expect(session?.agentId).toBe('TestAgent');
  });
});
