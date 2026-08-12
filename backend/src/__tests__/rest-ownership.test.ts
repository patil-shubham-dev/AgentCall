import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { registerRoutes } from '../routes.js';
import { initializeAiKeys, createAiKey } from '../voicebridge/ai-keys.js';
import { initializePhoneTokens, createPhoneToken } from '../voicebridge/phone-tokens.js';
import { config } from '../common/config.js';
import type { FastifyInstance } from 'fastify';

let app: FastifyInstance;
let baseUrl: string;
let service: VoiceBridgeService;

let agentAKey: string;
let agentBKey: string;
let userAToken: string;
let userBToken: string;
const serviceToken = config.serviceToken || 'test-service-token';

beforeAll(async () => {
  await initializeAiKeys();
  await initializePhoneTokens();

  const a = await createAiKey('AgentA');
  agentAKey = a.key;
  const b = await createAiKey('AgentB');
  agentBKey = b.key;

  userAToken = await createPhoneToken('UserA');
  userBToken = await createPhoneToken('UserB');

  const sessionRepo = new InMemorySessionRepository();
  const callbackRepo = new InMemoryCallbackRepository();
  service = new VoiceBridgeService(sessionRepo, callbackRepo);

  app = Fastify();
  registerRoutes(app, {
    voicebridge: service,
    sessionRepo,
    callbackRepo,
  });

  await app.listen({ port: 0, host: '127.0.0.1' });
  const address = app.server.address();
  baseUrl = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';
});

afterAll(async () => {
  await app.close();
});

describe('REST Call Ownership and Authorization', () => {
  it('allows service role to access and manage any call', async () => {
    // Create a call owned by AgentA for UserA
    const call = await service.createCall({
      userId: 'UserA',
      agentId: 'AgentA',
      reason: 'clarification',
      summary: 'Service test call',
    });

    // 1. Get call details with service token
    const resGet = await fetch(`${baseUrl}/api/v1/calls/${call.id}`, {
      headers: { Authorization: `Bearer ${serviceToken}` },
    });
    expect(resGet.status).toBe(200);

    // 2. Add message with service token
    const resMsg = await fetch(`${baseUrl}/api/v1/calls/${call.id}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${serviceToken}`,
      },
      body: JSON.stringify({ content: 'Hello from service' }),
    });
    expect(resMsg.status).toBe(201);
  });

  it('enforces ownership check for agent role (named AI key)', async () => {
    // Create a call owned by AgentA for UserA
    const call = await service.createCall({
      userId: 'UserA',
      agentId: 'AgentA',
      reason: 'clarification',
      summary: 'Agent test call',
    });

    // Owner (AgentA) should be allowed to view details
    const resOwnerGet = await fetch(`${baseUrl}/api/v1/calls/${call.id}`, {
      headers: { Authorization: `Bearer ${agentAKey}` },
    });
    expect(resOwnerGet.status).toBe(200);

    // Intruder (AgentB) should get 403 Forbidden
    const resIntruderGet = await fetch(`${baseUrl}/api/v1/calls/${call.id}`, {
      headers: { Authorization: `Bearer ${agentBKey}` },
    });
    expect(resIntruderGet.status).toBe(403);
    const body = await resIntruderGet.json() as { error: string };
    expect(body.error).toBe('FORBIDDEN');

    // Intruder (AgentB) trying to post a message should get 403 Forbidden
    const resIntruderMsg = await fetch(`${baseUrl}/api/v1/calls/${call.id}/messages`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${agentBKey}`,
      },
      body: JSON.stringify({ content: 'Intrusion message' }),
    });
    expect(resIntruderMsg.status).toBe(403);
  });

  it('enforces ownership check for user role (phone token)', async () => {
    // Create a call owned by AgentA for UserA
    const call = await service.createCall({
      userId: 'UserA',
      agentId: 'AgentA',
      reason: 'clarification',
      summary: 'User test call',
    });

    // Owner (UserA) should be allowed to view details
    const resOwnerGet = await fetch(`${baseUrl}/api/v1/calls/${call.id}`, {
      headers: { Authorization: `Bearer ${userAToken}` },
    });
    expect(resOwnerGet.status).toBe(200);

    // Intruder (UserB) should get 403 Forbidden
    const resIntruderGet = await fetch(`${baseUrl}/api/v1/calls/${call.id}`, {
      headers: { Authorization: `Bearer ${userBToken}` },
    });
    expect(resIntruderGet.status).toBe(403);

    // Intruder (UserB) trying to complete the call should get 403 Forbidden
    const resIntruderComplete = await fetch(`${baseUrl}/api/v1/calls/${call.id}/complete`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${userBToken}`,
      },
      body: JSON.stringify({}),
    });
    expect(resIntruderComplete.status).toBe(403);
  });

  it('denies user A active-call request to view user B active-call', async () => {
    // Create an active call for UserB
    await service.createCall({
      userId: 'UserB',
      agentId: 'AgentA',
      reason: 'clarification',
      summary: 'Active call UserB',
    });

    // UserB can fetch their active call
    const resOwnerActive = await fetch(`${baseUrl}/api/v1/users/UserB/active-call`, {
      headers: { Authorization: `Bearer ${userBToken}` },
    });
    expect(resOwnerActive.status).toBe(200);

    // UserA cannot fetch UserB's active call
    const resIntruderActive = await fetch(`${baseUrl}/api/v1/users/UserB/active-call`, {
      headers: { Authorization: `Bearer ${userAToken}` },
    });
    expect(resIntruderActive.status).toBe(403);
  });
});

describe('Agent status endpoint', () => {
  it('reports current_call_id while the agent has an open call', async () => {
    const call = await service.createCall({
      userId: 'UserA',
      agentId: 'AgentStatusX',
      reason: 'clarification',
      summary: 'Status test call',
    });

    const res = await fetch(`${baseUrl}/api/v1/agents/AgentStatusX/status`, {
      headers: { Authorization: `Bearer ${userAToken}` },
    });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { current_call_id: string | null };
    expect(body.current_call_id).toBe(call.id);
  });

  it('reports null current_call_id when the agent has no open call', async () => {
    const res = await fetch(`${baseUrl}/api/v1/agents/AgentB/status`, {
      headers: { Authorization: `Bearer ${userAToken}` },
    });
    expect(res.status).toBe(200);
    const body = (await res.json()) as { current_call_id: string | null };
    expect(body.current_call_id).toBeNull();
  });
});
