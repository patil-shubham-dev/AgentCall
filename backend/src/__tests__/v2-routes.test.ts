import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';
import type { FastifyInstance } from 'fastify';
import { registerRoutes } from '../routes.js';
import { registerV2Routes } from '../v2/routes.js';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemoryEventLogStore } from '../v2/event-log.js';
import { EventPlane } from '../v2/event-plane.js';
import { V2CallService } from '../v2/call-service.js';
import { IdempotencyStore } from '../v2/idempotency.js';
import { config } from '../common/config.js';
import { initializeAiKeys, createAiKey } from '../voicebridge/ai-keys.js';
import { initializePhoneTokens, createPhoneToken } from '../voicebridge/phone-tokens.js';

const TOKEN = config.serviceToken || 'test-service-token';
const BASE = '/api/v2';

let app: FastifyInstance;
let v2Service: V2CallService;
let agentKey: string;
let otherAgentKey: string;
let userToken: string;

beforeAll(async () => {
  await initializeAiKeys();
  await initializePhoneTokens();
  const key = await createAiKey('AgentV2');
  agentKey = key.key;
  const other = await createAiKey('AgentV2B');
  otherAgentKey = other.key;
  userToken = await createPhoneToken('UserV2');

  const sessionRepo = new InMemorySessionRepository();
  const callbackRepo = new InMemoryCallbackRepository();
  const voicebridge = new VoiceBridgeService(sessionRepo, callbackRepo);

  const v2EventLog = new InMemoryEventLogStore();
  const v2Plane = new EventPlane(v2EventLog);
  v2Service = new V2CallService(v2Plane, new IdempotencyStore());

  app = Fastify();
  registerRoutes(app, { voicebridge, sessionRepo, callbackRepo });
  registerV2Routes(app, { callService: v2Service });
  await app.ready();
});

afterAll(async () => {
  v2Service.dispose();
  await app.close();
});

async function createCall(payload: Record<string, unknown> = {}, headers: Record<string, string> = {}): Promise<{
  status: number;
  body: { call_id: string; status: string; events_url: string };
}> {
  const res = await app.inject({
    method: 'POST',
    url: `${BASE}/calls`,
    payload,
    headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json', ...headers },
  });
  return { status: res.statusCode, body: res.json() as never };
}

describe('v2 REST contract', () => {
  it('creates a call (201) with events_url', async () => {
    const { status, body } = await createCall({
      user_id: 'UserV2',
      agent_id: 'AgentV2',
      reason: 'approval',
      summary: 'Approve refund',
      context: { options: ['refund', 'credit'] },
      media: { transport: 'mobile', stt: { provider: 'on-device' }, tts: { provider: 'on-device' } },
    });
    expect(status).toBe(201);
    expect(body.status).toBe('ringing');
    expect(body.events_url).toBe(`${BASE}/calls/${body.call_id}/events`);
    expect(body.call_id).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('returns a snapshot with transcript_seq and media state', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });
    const res = await app.inject({
      method: 'GET',
      url: `${BASE}/calls/${call.call_id}`,
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    expect(res.statusCode).toBe(200);
    const snapshot = res.json() as { status: string; transcript_seq: number; media: { connected: boolean } };
    expect(snapshot.status).toBe('ringing');
    expect(snapshot.transcript_seq).toBe(0);
    expect(snapshot.media.connected).toBe(false);
  });

  it('answers a call to connected', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });
    const res = await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/answer`,
      payload: { provider: 'mobile' },
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });
    expect(res.statusCode).toBe(200);
    expect((res.json() as { status: string }).status).toBe('connected');
  });

  it('posts a message and an utterance and reads the transcript', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });
    await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/answer`,
      payload: {},
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });

    const msg = await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/messages`,
      payload: { content: 'Hello, please confirm.' },
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });
    expect(msg.statusCode).toBe(201);
    expect((msg.json() as { status: string }).status).toBe('queued');

    const utt = await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/utterances`,
      payload: { text: 'Confirmed', client_message_id: 'cm-route-1' },
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });
    expect(utt.statusCode).toBe(200);

    const transcript = await app.inject({
      method: 'GET',
      url: `${BASE}/calls/${call.call_id}/transcript`,
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    const body = transcript.json() as { segments: Array<{ role: string; text: string }> };
    expect(body.segments.map((s) => s.text)).toEqual(['Hello, please confirm.', 'Confirmed']);
  });

  it('hangs up with an outcome (decision=cancelled maps to completed)', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });
    const res = await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/hangup`,
      payload: { outcome: { decision: 'cancelled' }, note: 'declined on phone' },
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });
    expect(res.statusCode).toBe(200);
    const body = res.json() as { status: string };
    expect(body.status).toBe('completed');
  });

  it('enforces ownership: a different AI key gets 403', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });
    const res = await app.inject({
      method: 'GET',
      url: `${BASE}/calls/${call.call_id}`,
      headers: { authorization: `Bearer ${otherAgentKey}` },
    });
    expect(res.statusCode).toBe(403);
    expect((res.json() as { error: string }).error).toBe('FORBIDDEN');
  });

  it('lets the owning user answer and hang up their own call', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });
    const answer = await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/answer`,
      payload: { provider: 'mobile' },
      headers: { authorization: `Bearer ${userToken}`, 'content-type': 'application/json' },
    });
    expect(answer.statusCode).toBe(200);

    const hangup = await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/hangup`,
      payload: { outcome: { decision: 'approved' } },
      headers: { authorization: `Bearer ${userToken}`, 'content-type': 'application/json' },
    });
    expect(hangup.statusCode).toBe(200);
    expect((hangup.json() as { status: string }).status).toBe('completed');
  });

  it('maps INVALID_TRANSITION to 409', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });
    // 'answer' from 'ringing' is fine, but answering twice is idempotent; use
    // a command that is genuinely invalid: message before any ring transition
    // is impossible (create already rings), so hang up then message.
    await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/hangup`,
      payload: {},
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });
    const res = await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/messages`,
      payload: { content: 'too late' },
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });
    expect(res.statusCode).toBe(409);
    expect((res.json() as { error: string }).error).toBe('INVALID_TRANSITION');
  });

  it('replays an idempotent create with X-Idempotent-Replay', async () => {
    const headers = {
      authorization: `Bearer ${TOKEN}`,
      'content-type': 'application/json',
      'idempotency-key': 'idem-create-1',
    };
    const first = await app.inject({
      method: 'POST',
      url: `${BASE}/calls`,
      payload: { user_id: 'UserV2', agent_id: 'AgentV2' },
      headers,
    });
    const second = await app.inject({
      method: 'POST',
      url: `${BASE}/calls`,
      payload: { user_id: 'UserV2', agent_id: 'AgentV2' },
      headers,
    });
    expect(first.statusCode).toBe(201);
    expect(second.statusCode).toBe(201);
    expect(second.headers['x-idempotent-replay']).toBe('true');
    expect((second.json() as { call_id: string }).call_id).toBe((first.json() as { call_id: string }).call_id);
  });

  it('rejects a call-create for an AI identity that does not own the agent_id', async () => {
    const res = await app.inject({
      method: 'POST',
      url: `${BASE}/calls`,
      payload: { user_id: 'UserV2', agent_id: 'SomeoneElse' },
      headers: { authorization: `Bearer ${agentKey}`, 'content-type': 'application/json' },
    });
    expect(res.statusCode).toBe(403);
  });

  it('archives a completed call (DELETE) and 404s afterwards', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });
    await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/hangup`,
      payload: {},
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });
    const del = await app.inject({
      method: 'DELETE',
      url: `${BASE}/calls/${call.call_id}`,
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    expect(del.statusCode).toBe(200);

    const after = await app.inject({
      method: 'GET',
      url: `${BASE}/calls/${call.call_id}`,
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    expect(after.statusCode).toBe(404);
  });
});

describe('v2 SSE events', () => {
  it('streams the call lifecycle and closes with stream.end on hangup', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });

    const streamPromise = app.inject({
      method: 'GET',
      url: `${BASE}/calls/${call.call_id}/events`,
      headers: { authorization: `Bearer ${TOKEN}` },
    });

    // Give the SSE handler a moment to subscribe before commands flow.
    await new Promise((r) => setTimeout(r, 40));

    await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/answer`,
      payload: {},
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });
    await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/messages`,
      payload: { content: 'hi' },
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });
    await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/hangup`,
      payload: {},
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });

    const stream = await streamPromise;
    expect(stream.statusCode).toBe(200);
    expect(stream.headers['content-type']).toContain('text/event-stream');
    const body = stream.body;
    // No cursor = events from "now" only (spec §3.1): the create/ring events
    // preceded subscription and must not appear.
    expect(body).not.toContain('event: call.created');
    expect(body).toContain('event: call.answer.requested');
    expect(body).toContain('event: call.connected');
    expect(body).toContain('event: message.queued');
    expect(body).toContain('event: message.completed');
    expect(body).toContain('event: call.completed');
    expect(body).toContain('event: stream.end');
    expect(body).toContain('id: ');
  });

  it('resumes from ?after= with the full history', async () => {
    const { body: call } = await createCall({ user_id: 'UserV2', agent_id: 'AgentV2' });
    await app.inject({
      method: 'POST',
      url: `${BASE}/calls/${call.call_id}/hangup`,
      payload: {},
      headers: { authorization: `Bearer ${TOKEN}`, 'content-type': 'application/json' },
    });

    const stream = await app.inject({
      method: 'GET',
      url: `${BASE}/calls/${call.call_id}/events?after=`,
      headers: { authorization: `Bearer ${TOKEN}` },
    });
    expect(stream.body).toContain('event: call.created');
    expect(stream.body).toContain('event: stream.end');
  });
});
