import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import Fastify from 'fastify';
import type { FastifyInstance } from 'fastify';
import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { registerRoutes } from '../routes.js';
import { initializePhoneTokens, createPhoneToken } from '../voicebridge/phone-tokens.js';

let app: FastifyInstance;
let baseUrl: string;
let service: VoiceBridgeService;
let soloToken: string;

beforeAll(async () => {
  await initializePhoneTokens();
  soloToken = await createPhoneToken('solo-user');

  const sessionRepo = new InMemorySessionRepository();
  const callbackRepo = new InMemoryCallbackRepository();
  service = new VoiceBridgeService(sessionRepo, callbackRepo);

  app = Fastify();
  registerRoutes(app, { voicebridge: service, sessionRepo, callbackRepo });
  await app.listen({ port: 0, host: '127.0.0.1' });
  const address = app.server.address();
  baseUrl = typeof address === 'object' && address ? `http://127.0.0.1:${address.port}` : '';
});

afterAll(async () => {
  await app.close();
});

async function postCancel(callId: string, note?: string): Promise<{ status: number; body: Record<string, unknown> }> {
  const res = await fetch(`${baseUrl}/api/v1/calls/${callId}/cancel`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${soloToken}` },
    body: JSON.stringify(note === undefined ? {} : { note }),
  });
  return { status: res.status, body: (await res.json()) as Record<string, unknown> };
}

describe('POST /calls/:callId/cancel against a live call (stale-decline race)', () => {
  it('returns 200 with the live status and leaves an answered call untouched', async () => {
    const call = await service.createCall({
      userId: 'solo-user',
      agentId: 'AgentA',
      reason: 'input_required',
      summary: 'race route test',
    });
    // AI message answers server-side while the phone still shows RINGING.
    await service.addAiMessage(call.id, 'Answering before you pick up.');
    expect((await service.getCall(call.id))?.status).toBe('active');

    // Phone ring-timeout fires blind with its decline note.
    const { status, body } = await postCancel(call.id, 'Sorry I missed your call.');

    expect(status).toBe(200);
    expect(body.status).toBe('active');
    const row = await service.getCall(call.id);
    expect(row?.status).toBe('active');
    expect(row?.completedAt).toBeUndefined();
    expect(row?.messages.filter((m) => m.content === 'Sorry I missed your call.')).toHaveLength(0);
  });

  it('still cancels a genuinely ringing (pending) call with its note', async () => {
    const call = await service.createCall({
      userId: 'solo-user',
      agentId: 'AgentA',
      reason: 'input_required',
      summary: 'prompt decline test',
    });

    const { status, body } = await postCancel(call.id, 'Busy right now.');

    expect(status).toBe(200);
    expect(body.status).toBe('cancelled');
    expect(body.call_id).toBe(call.id);
  });
});
