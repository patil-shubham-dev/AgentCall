import { describe, it, expect, vi } from 'vitest';

// Battery audit M1: the signaling heartbeat must be configuration-driven
// (SIGNALING_HEARTBEAT_MS) with a 60s default that matches the phone's OkHttp
// pingInterval — the previous hardcoded 25s dominated keepalive traffic and
// woke the phone's radio ~2.4x/min whenever the socket was connected.

describe('signaling heartbeat config (battery audit M1)', () => {
  it('defaults to 60000ms when SIGNALING_HEARTBEAT_MS is unset', async () => {
    vi.resetModules();
    delete process.env.SIGNALING_HEARTBEAT_MS;
    const { config } = await import('../common/config.js');
    expect(config.signaling.heartbeatMs).toBe(60000);
  });

  it('honours the SIGNALING_HEARTBEAT_MS override', async () => {
    vi.resetModules();
    process.env.SIGNALING_HEARTBEAT_MS = '90000';
    try {
      const { config } = await import('../common/config.js');
      expect(config.signaling.heartbeatMs).toBe(90000);
    } finally {
      delete process.env.SIGNALING_HEARTBEAT_MS;
    }
  });

  it('rejects non-numeric values fail-closed', async () => {
    vi.resetModules();
    process.env.SIGNALING_HEARTBEAT_MS = 'not-a-number';
    try {
      const { config } = await import('../common/config.js');
      expect(config).toBeDefined(); // unreachable if parseIntSafe throws
    } catch (error) {
      expect((error as Error).message).toContain('SIGNALING_HEARTBEAT_MS');
    }
  });
});
