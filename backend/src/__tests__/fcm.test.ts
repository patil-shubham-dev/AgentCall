import { describe, it, expect, beforeEach, vi } from 'vitest';
import { registerFcmToken } from '../voicebridge/fcm-tokens.js';

// Mutable config so the same file can test enabled AND disabled paths.
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
        serviceAccountPath: '/tmp/fake-service-account.json',
        projectId: 'agentcall-test',
      },
    },
  };
});

// Mock the token mint so no real Google credentials are touched.
vi.mock('google-auth-library', () => ({
  GoogleAuth: vi.fn().mockImplementation(function () {
    return { getAccessToken: vi.fn().mockResolvedValue('fake-access-token') };
  }),
}));

// Import after mocks are installed.
const { sendFcmPush } = await import('../voicebridge/fcm.js');

describe('fcm.sendFcmPush failure semantics', () => {
  const fetchMock = vi.fn();
  beforeEach(async () => {
    fcmState.enabled = true;
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockReset();
    await registerFcmToken('user-fcm-test', 'device-token-abc');
  });

  it('sends a high-priority data message to the registered token', async () => {
    fetchMock.mockResolvedValue({ ok: true, status: 200, text: async () => '' });
    const result = await sendFcmPush('user-fcm-test', {
      type: 'call_incoming',
      callId: 'call-1',
      callerName: 'AgentA',
      summary: 'Question for you',
      options: ['a', 'b'],
    });
    expect(result.ok).toBe(true);
    expect(result.tokenRemoved).toBe(false);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, init] = fetchMock.mock.calls[0] as [string, { method: string; headers: Record<string, string>; body: string }];
    expect(url).toContain('/v1/projects/agentcall-test/messages:send');
    expect(init.method).toBe('POST');
    expect(init.headers.Authorization).toBe('Bearer fake-access-token');
    const body = JSON.parse(init.body);
    expect(body.message.token).toBe('device-token-abc');
    expect(body.message.android.priority).toBe('high');
    // Data values are strings; arrays are JSON-encoded (FCM data constraint).
    expect(body.message.data.type).toBe('call_incoming');
    expect(body.message.data.options).toBe(JSON.stringify(['a', 'b']));
  });

  it('404 (UNREGISTERED) removes the dead token so later rings skip it', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 404, text: async () => 'UNREGISTERED' });
    const result = await sendFcmPush('user-fcm-test', { type: 'call_incoming', callId: 'call-1' });
    expect(result.ok).toBe(false);
    expect(result.tokenRemoved).toBe(true);
    // Token should now be gone — a second send skips silently.
    const second = await sendFcmPush('user-fcm-test', { type: 'call_incoming', callId: 'call-1' });
    expect(second.error).toBe('no-token');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('410 (GONE) also removes the dead token', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 410, text: async () => '' });
    const result = await sendFcmPush('user-fcm-test', { type: 'call_incoming' });
    expect(result.ok).toBe(false);
    expect(result.tokenRemoved).toBe(true);
  });

  it('400 INVALID_ARGUMENT (unregistered token) removes the dead token — real-API finding', async () => {
    // Live probe (2026-08-18): a token not registered with this sender comes
    // back 400 with "The registration token is not a valid FCM registration
    // token", NOT the documented 404. Must be treated as dead, not transient.
    fetchMock.mockResolvedValue({
      ok: false,
      status: 400,
      text: async () => JSON.stringify({ error: { status: 'INVALID_ARGUMENT', message: 'The registration token is not a valid FCM registration token' } }),
    });
    const result = await sendFcmPush('user-fcm-test', { type: 'call_incoming', callId: 'call-1' });
    expect(result.ok).toBe(false);
    expect(result.tokenRemoved).toBe(true);
    expect(result.error).toBe('fcm-400-invalid-token');
    // Token dropped — a second send skips silently.
    const second = await sendFcmPush('user-fcm-test', { type: 'call_incoming' });
    expect(second.error).toBe('no-token');
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('400 for a NON-token reason keeps the token (payload bug, not dead token)', async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 400,
      text: async () => JSON.stringify({ error: { status: 'INVALID_ARGUMENT', message: 'Invalid JSON payload received' } }),
    });
    const result = await sendFcmPush('user-fcm-test', { type: 'call_incoming' });
    expect(result.tokenRemoved).toBe(false);
    // Token survives — next ring retries.
    await sendFcmPush('user-fcm-test', { type: 'call_incoming' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('5xx keeps the token for the next ring to retry', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 500, text: async () => 'Internal Server Error' });
    const result = await sendFcmPush('user-fcm-test', { type: 'call_incoming' });
    expect(result.ok).toBe(false);
    expect(result.tokenRemoved).toBe(false);
    // Token survives — a retry attempts the send again.
    const retry = await sendFcmPush('user-fcm-test', { type: 'call_incoming' });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(retry.tokenRemoved).toBe(false);
  });

  it('429 (rate limit) keeps the token too', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 429, text: async () => '' });
    const result = await sendFcmPush('user-fcm-test', { type: 'call_incoming' });
    expect(result.tokenRemoved).toBe(false);
  });

  it('transport error keeps the token', async () => {
    fetchMock.mockRejectedValue(new Error('ECONNRESET'));
    const result = await sendFcmPush('user-fcm-test', { type: 'call_incoming' });
    expect(result.ok).toBe(false);
    expect(result.tokenRemoved).toBe(false);
  });

  it('no registered token skips the HTTP call entirely', async () => {
    const result = await sendFcmPush('user-without-token', { type: 'call_incoming' });
    expect(result.ok).toBe(false);
    expect(result.error).toBe('no-token');
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it('disabled is a fully silent no-op — no fetch, no auth, no error throw', async () => {
    fcmState.enabled = false;
    const result = await sendFcmPush('user-fcm-test', { type: 'call_incoming' });
    expect(result.ok).toBe(false);
    expect(result.error).toBe('fcm-disabled');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
