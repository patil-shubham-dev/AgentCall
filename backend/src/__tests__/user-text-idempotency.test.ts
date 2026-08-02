import { describe, it, expect } from 'vitest';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemorySessionRepository } from '../voicebridge/repositories/session-repository.js';
import { InMemoryCallbackRepository } from '../voicebridge/repositories/callback-repository.js';
import type { VoiceCallSession } from '../voicebridge/types.js';

function makeSession(overrides: Partial<VoiceCallSession>): VoiceCallSession {
  return {
    id: 'call-1',
    userId: 'user-1',
    agentId: 'agent-1',
    status: 'active',
    priority: 'normal',
    reason: 'clarification',
    context: { summary: 'test' },
    messages: [],
    createdAt: new Date().toISOString(),
    ...overrides,
  };
}

function makeService(sessions: VoiceCallSession[]): VoiceBridgeService {
  const sessionRepo = new InMemorySessionRepository();
  const callbackRepo = new InMemoryCallbackRepository();
  for (const s of sessions) {
    void sessionRepo.create(s);
  }
  return new VoiceBridgeService(sessionRepo, callbackRepo);
}

describe('processTextMessage idempotency', () => {
  it('appends a user text message with the client message id', async () => {
    const service = makeService([makeSession({ id: 'call-send' })]);

    await service.processTextMessage('call-send', 'hello', 'msg-1');

    const after = await service.getCall('call-send');
    expect(after?.messages).toEqual([
      expect.objectContaining({
        role: 'user',
        type: 'text',
        content: 'hello',
        clientMessageId: 'msg-1',
      }),
    ]);
  });

  it('does not duplicate a message on retry with the same client message id', async () => {
    const service = makeService([makeSession({ id: 'call-retry' })]);

    await service.processTextMessage('call-retry', 'hello', 'msg-1');
    await service.processTextMessage('call-retry', 'hello', 'msg-1');

    const after = await service.getCall('call-retry');
    const matches = after?.messages.filter((m) => m.clientMessageId === 'msg-1');
    expect(matches).toHaveLength(1);
  });

  it('still appends distinct messages with different client message ids', async () => {
    const service = makeService([makeSession({ id: 'call-multi' })]);

    await service.processTextMessage('call-multi', 'first', 'msg-1');
    await service.processTextMessage('call-multi', 'second', 'msg-2');

    const after = await service.getCall('call-multi');
    expect(after?.messages).toHaveLength(2);
  });

  it('appends without dedupe when no client message id is sent', async () => {
    const service = makeService([makeSession({ id: 'call-legacy' })]);

    await service.processTextMessage('call-legacy', 'hello');
    await service.processTextMessage('call-legacy', 'hello');

    const after = await service.getCall('call-legacy');
    expect(after?.messages).toHaveLength(2);
  });

  it('throws for an unknown call', async () => {
    const service = makeService([]);

    await expect(service.processTextMessage('call-missing', 'hello', 'msg-1')).rejects.toThrow(
      'Call session not found',
    );
  });
});
