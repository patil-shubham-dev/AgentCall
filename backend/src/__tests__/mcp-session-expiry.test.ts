import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';
import Fastify from 'fastify';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { registerMcpEndpoint } from '../mcp/endpoint.js';
import { McpSessionRegistry } from '../mcp/session-registry.js';
import { initializeAiKeys, createAiKey } from '../voicebridge/ai-keys.js';
import type { FastifyInstance } from 'fastify';
import type { Server } from '@modelcontextprotocol/sdk/server/index.js';
import type { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';

function fakeSession(lastActivityAt: number) {
  return {
    server: { close: vi.fn().mockResolvedValue(undefined) } as unknown as Server,
    transport: {} as unknown as StreamableHTTPServerTransport,
    lastActivityAt,
  };
}

describe('McpSessionRegistry idle expiry', () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it('closes and removes sessions idle past the threshold', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000_000);
    const registry = new McpSessionRegistry();
    registry.set('idle', fakeSession(Date.now()));

    vi.setSystemTime(1_000_000_000 + 30 * 60 * 1000 + 1000);
    const closed = await registry.sweepExpired(30 * 60 * 1000);

    expect(closed).toBe(1);
    expect(registry.get('idle')).toBeUndefined();
    expect(registry.count()).toBe(0);
  });

  it('leaves sessions whose activity is within the threshold alone', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000_000);
    const registry = new McpSessionRegistry();
    registry.set('recent', fakeSession(Date.now() - 60 * 1000));
    registry.set('stale', fakeSession(Date.now() - 31 * 60 * 1000));

    const closed = await registry.sweepExpired(30 * 60 * 1000);

    expect(closed).toBe(1);
    expect(registry.get('recent')).toBeDefined();
    expect(registry.get('stale')).toBeUndefined();
  });

  it('touch() bumps lastActivityAt so a request keeps the session alive', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000_000);
    const registry = new McpSessionRegistry();
    registry.set('busy', fakeSession(Date.now()));

    vi.setSystemTime(1_000_000_000 + 29 * 60 * 1000);
    registry.touch('busy');

    vi.setSystemTime(1_000_000_000 + 29 * 60 * 1000 + 30 * 1000);
    const closed = await registry.sweepExpired(30 * 60 * 1000);
    expect(closed).toBe(0);
    expect(registry.get('busy')).toBeDefined();
  });

  it('still removes the session when closing it throws', async () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_000_000_000);
    const registry = new McpSessionRegistry();
    const session = fakeSession(Date.now() - 31 * 60 * 1000);
    (session.server.close as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('already closed'));
    registry.set('broken', session);

    const closed = await registry.sweepExpired(30 * 60 * 1000);

    expect(closed).toBe(1);
    expect(registry.get('broken')).toBeUndefined();
  });

  it('getActiveIdentities() returns all active session agent names', () => {
    const registry = new McpSessionRegistry();
    const session1 = fakeSession(Date.now());
    session1.agentName = 'Agent1';
    const session2 = fakeSession(Date.now());
    session2.agentName = 'Agent2';
    const session3 = fakeSession(Date.now());

    registry.set('s1', session1);
    registry.set('s2', session2);
    registry.set('s3', session3);

    const active = registry.getActiveIdentities();
    expect(active.size).toBe(2);
    expect(active.has('Agent1')).toBe(true);
    expect(active.has('Agent2')).toBe(true);
  });
});

interface JsonRpcResponse {
  jsonrpc: string;
  id: number;
  result?: Record<string, unknown>;
  error?: { code: number; message: string };
}

let app: FastifyInstance;
let baseUrl: string;
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

async function postMcp(body: unknown, headers: Record<string, string>): Promise<{
  status: number;
  body: JsonRpcResponse;
  sessionId: string | null;
}> {
  const res = await fetch(`${baseUrl}/mcp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...headers },
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
    clientInfo: { name: 'session-expiry-test', version: '1.0' },
  },
};

beforeAll(async () => {
  await initializeAiKeys();
  const created = await createAiKey('SessionTest');
  aiKey = created.key;

  const service = new VoiceBridgeService(new InMemorySessionRepository(), new InMemoryCallbackRepository());
  app = Fastify();
  registerMcpEndpoint(app, service);
  await app.listen({ port: 0, host: '127.0.0.1' });
  const address = app.server.address();
  baseUrl = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';
});

afterAll(async () => {
  await app.close();
});

describe('MCP session lifecycle over HTTP', () => {
  it('created sessions respond to requests, and an unknown session id is a clean 404', async () => {
    const init = await postMcp(initializePayload, { Authorization: `Bearer ${aiKey}` });
    expect(init.status).toBe(200);
    const sid = init.sessionId;
    expect(sid).toBeTruthy();

    const list = await postMcp(
      { jsonrpc: '2.0', id: 2, method: 'tools/list', params: {} },
      { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid ?? '' },
    );
    expect(list.status).toBe(200);

    const unknown = await postMcp(
      { jsonrpc: '2.0', id: 3, method: 'tools/list', params: {} },
      { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': 'no-such-session' },
    );
    expect(unknown.status).toBe(404);
    expect(unknown.body.error).toBe('SESSION_NOT_FOUND');
  });

  it('an explicit DELETE closes the session and subsequent requests get SESSION_NOT_FOUND', async () => {
    const init = await postMcp(initializePayload, { Authorization: `Bearer ${aiKey}` });
    const sid = init.sessionId;
    expect(sid).toBeTruthy();

    const del = await fetch(`${baseUrl}/mcp`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid ?? '' },
    });
    expect(del.status).toBe(200);

    const after = await postMcp(
      { jsonrpc: '2.0', id: 4, method: 'tools/list', params: {} },
      { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid ?? '' },
    );
    expect(after.status).toBe(404);
    expect(after.body.error).toBe('SESSION_NOT_FOUND');
  });
});
