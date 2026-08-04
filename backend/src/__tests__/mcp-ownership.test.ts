import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { registerMcpEndpoint } from '../mcp/endpoint.js';
import { initializeAiKeys, createAiKey } from '../voicebridge/ai-keys.js';
import { config } from '../common/config.js';
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
let ownerAKey: string;
let ownerBKey: string;

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
    clientInfo: { name: 'ownership-test', version: '1.0' },
  },
};

let rpcId = 10;

interface ToolCallOutcome {
  isError: boolean;
  text: string;
  parsed: Record<string, unknown> | null;
}

async function initSession(auth: string): Promise<string> {
  const res = await postMcp(initializePayload, { Authorization: `Bearer ${auth}` });
  expect(res.status).toBe(200);
  const sid = res.sessionId;
  expect(sid).toBeTruthy();
  await postMcp(
    { jsonrpc: '2.0', id: 2, method: 'notifications/initialized', params: {} },
    { Authorization: `Bearer ${auth}`, 'Mcp-Session-Id': sid ?? '' },
  );
  return sid ?? '';
}

async function callTool(
  sessionId: string,
  auth: string,
  name: string,
  args: Record<string, unknown>,
): Promise<ToolCallOutcome> {
  const { body } = await postMcp(
    {
      jsonrpc: '2.0',
      id: rpcId++,
      method: 'tools/call',
      params: { name, arguments: args },
    },
    { Authorization: `Bearer ${auth}`, 'Mcp-Session-Id': sessionId },
  );
  const result = body.result as { content?: Array<{ text: string }>; isError?: boolean } | undefined;
  const text = result?.content?.[0]?.text ?? '';
  let parsed: Record<string, unknown> | null = null;
  try {
    parsed = JSON.parse(text) as Record<string, unknown>;
  } catch {
    // keep null
  }
  return { isError: result?.isError ?? false, text, parsed };
}

async function createCall(sessionId: string, auth: string, summary: string): Promise<string> {
  const outcome = await callTool(sessionId, auth, 'create_call', {
    context: { reason: 'clarification', summary },
  });
  expect(outcome.isError).toBe(false);
  const callId = outcome.parsed?.call_id as string;
  expect(callId).toBeTruthy();
  return callId;
}

beforeAll(async () => {
  await initializeAiKeys();
  const a = await createAiKey('OwnerA');
  ownerAKey = a.key;
  const b = await createAiKey('OwnerB');
  ownerBKey = b.key;

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

describe('per-call ownership', () => {
  it('lets the creating identity use every per-call tool on its own call', async () => {
    const sid = await initSession(ownerAKey);
    const callId = await createCall(sid, ownerAKey, 'owner full-access test');
    await service.answerCall(callId);

    const sent = await callTool(sid, ownerAKey, 'send_message', { call_id: callId, content: 'Hello' });
    expect(sent.isError).toBe(false);
    expect(sent.parsed?.sent).toBe(true);

    const transcript = await callTool(sid, ownerAKey, 'get_transcript', { call_id: callId });
    expect(transcript.isError).toBe(false);
    expect(Array.isArray(transcript.parsed?.messages)).toBe(true);

    const waited = await callTool(sid, ownerAKey, 'send_message_and_wait', {
      call_id: callId,
      content: 'Waiting for a reply',
      timeout_seconds: 1,
    });
    expect(waited.isError).toBe(false);
    expect(['timeout', 'reply']).toContain(waited.parsed?.outcome);

    const completed = await callTool(sid, ownerAKey, 'complete_call', { call_id: callId });
    expect(completed.isError).toBe(false);
    expect(completed.parsed?.status).toBe('completed');
  });

  it('denies every per-call tool to a different identity, leaving the call untouched', async () => {
    const ownerSid = await initSession(ownerAKey);
    const intruderSid = await initSession(ownerBKey);
    const callId = await createCall(ownerSid, ownerAKey, 'cross-agent denial test');
    await service.answerCall(callId);

    for (const [name, args] of [
      ['send_message', { call_id: callId, content: 'intrusion' }],
      ['get_transcript', { call_id: callId }],
      ['complete_call', { call_id: callId, result: { decision: 'stolen' } }],
      ['cancel_call', { call_id: callId }],
      ['send_message_and_wait', { call_id: callId, content: 'intrusion', timeout_seconds: 1 }],
    ] as Array<[string, Record<string, unknown>]>) {
      const outcome = await callTool(intruderSid, ownerBKey, name, args);
      expect(outcome.isError).toBe(true);
      expect(outcome.text).toContain('Forbidden');
      expect(outcome.text).toContain('different AI identity');
    }

    const session = await service.getCall(callId);
    expect(session?.status).toBe('active');
    expect(service.getAiWaitStatus(callId).active).toBe(false);
    expect(session?.messages.filter((m) => m.role === 'ai').length).toBe(0);
  });

  it('reports missing calls as not found, not forbidden', async () => {
    const sid = await initSession(ownerBKey);
    const outcome = await callTool(sid, ownerBKey, 'get_transcript', { call_id: 'does-not-exist' });
    expect(outcome.isError).toBe(true);
    expect(outcome.text).toContain('Call not found');
    expect(outcome.text).not.toContain('Forbidden');
  });

  it('keeps the default service identity scoped to its own calls and vice versa', async () => {
    expect(config.serviceToken).toBeTruthy();
    const serviceSid = await initSession(config.serviceToken);

    const serviceCall = await createCall(serviceSid, config.serviceToken, 'service-created call');
    const serviceRead = await callTool(serviceSid, config.serviceToken, 'get_transcript', { call_id: serviceCall });
    expect(serviceRead.isError).toBe(false);

    const otherSid = await initSession(ownerAKey);
    const otherRead = await callTool(otherSid, ownerAKey, 'get_transcript', { call_id: serviceCall });
    expect(otherRead.isError).toBe(true);
    expect(otherRead.text).toContain('Forbidden');

    const namedCall = await createCall(otherSid, ownerAKey, 'named-key call');
    const serviceReadsNamed = await callTool(serviceSid, config.serviceToken, 'get_transcript', { call_id: namedCall });
    expect(serviceReadsNamed.isError).toBe(true);
    expect(serviceReadsNamed.text).toContain('Forbidden');
  });
});
