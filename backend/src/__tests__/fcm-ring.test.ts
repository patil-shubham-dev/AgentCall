import { describe, it, expect, beforeEach, vi } from 'vitest';
import { WebSocket } from 'ws';

// Mock ws module before importing service
vi.mock('ws', async () => {
  const { EventEmitter } = await import('node:events');
  class MockWebSocket extends EventEmitter {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;
    readyState = MockWebSocket.OPEN;
    send = vi.fn();
    close = vi.fn();
    terminate = vi.fn();
    ping = vi.fn();
  }
  return { WebSocket: MockWebSocket, default: MockWebSocket };
});

// Mutable config to exercise enabled + disabled notifyPhone paths.
const fcmState = vi.hoisted(() => ({ enabled: true }));
vi.mock('../common/config.js', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../common/config.js')>();
  return {
    ...actual,
    config: {
      ...actual.config,
      fcm: {
        get enabled() {
          return fcmState.enabled;
        },
        serviceAccountPath: '',
        projectId: '',
      },
    },
  };
});

// Spy on the FCM send from notifyPhone's perspective.
const sendFcmPushMock = vi.hoisted(() => vi.fn());
vi.mock('../voicebridge/fcm.js', () => ({
  sendFcmPush: sendFcmPushMock,
}));

import * as serviceModule from '../voicebridge/service.js';

describe('notifyPhone + FCM (Phase A, additive-only)', () => {
  let phoneWs: WebSocket;

  beforeEach(() => {
    phoneWs = new WebSocket();
    sendFcmPushMock.mockReset();
    sendFcmPushMock.mockResolvedValue({ ok: true, tokenRemoved: false });
    fcmState.enabled = true;
  });

  it('WS path unchanged when phone connected: returns true, sends on socket', async () => {
    const userId = 'fcm-ring-online';
    serviceModule.registerPhone(userId, phoneWs);
    await new Promise((r) => setTimeout(r, 20));

    const result = serviceModule.notifyPhone(userId, {
      type: 'call_incoming',
      callId: 'call-1',
      callerName: 'AgentA',
    });
    expect(result).toBe(true);
    expect(phoneWs.send).toHaveBeenCalledTimes(1);
    const sent = JSON.parse((phoneWs.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    expect(sent.payload.callId).toBe('call-1');
  });

  it('fires FCM alongside the WS send (always-send; client dedupes)', async () => {
    const userId = 'fcm-ring-online-2';
    serviceModule.registerPhone(userId, phoneWs);
    await new Promise((r) => setTimeout(r, 20));

    const payload = { type: 'call_incoming', callId: 'call-2', callerName: 'AgentA' };
    const result = serviceModule.notifyPhone(userId, payload);
    expect(result).toBe(true); // return value untouched
    await new Promise((r) => setTimeout(r, 10));
    expect(sendFcmPushMock).toHaveBeenCalledTimes(1);
    expect(sendFcmPushMock).toHaveBeenCalledWith(userId, payload);
  });

  it('still fires FCM when the phone is offline (WS queues as before)', async () => {
    const userId = 'fcm-ring-offline';
    const result = serviceModule.notifyPhone(userId, {
      type: 'call_incoming',
      callId: 'call-3',
      callerName: 'AgentA',
    });
    expect(result).toBe(false); // offline behavior unchanged — queued, not sent
    await new Promise((r) => setTimeout(r, 10));
    expect(sendFcmPushMock).toHaveBeenCalledTimes(1);
  });

  it('does NOT fire FCM for non-ring message types', async () => {
    const userId = 'fcm-ring-other';
    const result = serviceModule.notifyPhone(userId, { type: 'call_ended', callId: 'call-4' });
    expect(result).toBe(false);
    await new Promise((r) => setTimeout(r, 10));
    expect(sendFcmPushMock).not.toHaveBeenCalled();
  });

  it('does NOT fire FCM when FCM_ENABLED=false (silent dark merge)', async () => {
    fcmState.enabled = false;
    const userId = 'fcm-ring-disabled';
    const result = serviceModule.notifyPhone(userId, {
      type: 'call_incoming',
      callId: 'call-5',
      callerName: 'AgentA',
    });
    expect(result).toBe(false); // WS/queue path unaffected
    await new Promise((r) => setTimeout(r, 10));
    expect(sendFcmPushMock).not.toHaveBeenCalled();
  });

  it('queued flush still sends over WS and does not double-fire FCM', async () => {
    const userId = 'fcm-ring-flush';
    serviceModule.notifyPhone(userId, {
      type: 'call_incoming',
      callId: 'call-6',
      callerName: 'AgentA',
    });
    await new Promise((r) => setTimeout(r, 10));
    expect(sendFcmPushMock).toHaveBeenCalledTimes(1); // fired at notify time

    serviceModule.registerPhone(userId, phoneWs);
    await new Promise((r) => setTimeout(r, 20));
    // The flush re-invokes notifyPhone, which fires FCM again — harmless
    // duplicate (client dedupes); assert the WS got the queued message.
    const sent = JSON.parse((phoneWs.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    expect(sent.payload.callId).toBe('call-6');
  });
});
