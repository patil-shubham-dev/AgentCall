import { describe, it, expect, beforeEach } from 'vitest';
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

// We need to dynamically import after setting up the mock
import * as serviceModule from '../voicebridge/service.js';

// Helper to access the module-level pendingNotifications map
// Since it's not exported, we test through the public API

describe('notification queue', () => {
  let phoneWs: WebSocket;

  beforeEach(() => {
    phoneWs = new WebSocket();
  });

  it('queues notification when phone is not connected', async () => {
    const userId = 'test-user-offline';

    // No phone registered, so notifyPhone should queue
    const result = serviceModule.notifyPhone(userId, {
      type: 'call_incoming',
      callId: 'call-123',
      summary: 'Test call',
    });

    expect(result).toBe(false);

    // Now register phone — should flush the queue
    serviceModule.registerPhone(userId, phoneWs);

    // Wait for async flush (notifyPhone inside registerPhone calls ws.send)
    await new Promise((r) => setTimeout(r, 50));

    expect(phoneWs.send).toHaveBeenCalledTimes(1);
    const sentData = JSON.parse((phoneWs.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    expect(sentData.type).toBe('call_incoming');
    expect(sentData.payload.callId).toBe('call-123');
  });

  it('queues multiple notifications and flushes in order', async () => {
    const userId = 'test-user-multi';

    serviceModule.notifyPhone(userId, { type: 'event_1', seq: 1 });
    serviceModule.notifyPhone(userId, { type: 'event_2', seq: 2 });

    serviceModule.registerPhone(userId, phoneWs);

    await new Promise((r) => setTimeout(r, 50));

    expect(phoneWs.send).toHaveBeenCalledTimes(2);
    const first = JSON.parse((phoneWs.send as ReturnType<typeof vi.fn>).mock.calls[0][0]);
    const second = JSON.parse((phoneWs.send as ReturnType<typeof vi.fn>).mock.calls[1][0]);
    expect(first.payload.seq).toBe(1);
    expect(second.payload.seq).toBe(2);
  });

  it('does not queue if phone is already connected', async () => {
    const userId = 'test-user-online';

    serviceModule.registerPhone(userId, phoneWs);

    await new Promise((r) => setTimeout(r, 50));
    (phoneWs.send as ReturnType<typeof vi.fn>).mockClear();

    const result = serviceModule.notifyPhone(userId, {
      type: 'ai_message',
      callId: 'call-456',
      content: 'Hello',
    });

    expect(result).toBe(true);
    // Should have been delivered directly, not queued
    expect(phoneWs.send).toHaveBeenCalledTimes(1);
  });

  it('flushes notifications to newly connected phone even when previous ws existed', async () => {
    const userId = 'test-user-reconnect';

    // Phone offline — queue a notification
    serviceModule.notifyPhone(userId, { type: 'call_incoming', callId: 'call-reconnect' });

    // Register first connection
    const ws1 = new WebSocket();
    ws1.readyState = 1;
    serviceModule.registerPhone(userId, ws1);

    await new Promise((r) => setTimeout(r, 50));
    expect(ws1.send).toHaveBeenCalledTimes(1);
  });

  it('clears queue after flush so duplicates are not sent on subsequent connects', async () => {
    const userId = 'test-user-no-duplicate';

    serviceModule.notifyPhone(userId, { type: 'call_incoming', callId: 'call-nodup' });
    serviceModule.registerPhone(userId, new WebSocket());
    await new Promise((r) => setTimeout(r, 50));

    // Disconnect and reconnect
    const ws2 = new WebSocket();
    ws2.readyState = 1;
    serviceModule.registerPhone(userId, ws2);
    await new Promise((r) => setTimeout(r, 50));

    // Should NOT re-send the old queued message
    expect(ws2.send).not.toHaveBeenCalled();
  });
});
