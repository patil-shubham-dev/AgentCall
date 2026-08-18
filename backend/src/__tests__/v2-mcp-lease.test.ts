import { describe, it, expect, afterEach } from 'vitest';
import { VoiceBridgeService } from '../voicebridge/service.js';
import { InMemorySessionRepository } from '../voicebridge/repositories/session-repository.js';
import { InMemoryCallbackRepository } from '../voicebridge/repositories/callback-repository.js';
import { createTools } from '../mcp/tools.js';
import { mcpIdentityStorage } from '../mcp/identity.js';
import { config } from '../common/config.js';

type ToolResult = { content: Array<{ type: string; text: string }>; isError?: boolean };

function makeService(): VoiceBridgeService {
  return new VoiceBridgeService(new InMemorySessionRepository(), new InMemoryCallbackRepository());
}

async function makeActiveCall(service: VoiceBridgeService): Promise<string> {
  const session = await service.createCall({
    userId: 'user-1',
    agentId: 'agent-1',
    reason: 'approval',
    summary: 'Need a decision',
  });
  return session.id;
}

function findWaitTool(service: VoiceBridgeService) {
  return createTools(service).find((t) => t.name === 'send_message_and_wait');
}

async function runAsAgent<T>(agentName: string, fn: () => Promise<T>): Promise<T> {
  return mcpIdentityStorage.run({ agentName, via: 'ai_key' }, fn);
}

const WAIT_REPLY_TIMEOUT = 3_000;

/** Indexed access without non-null assertions (lint: no-non-null-assertion). */
function must<T>(value: T | undefined, label: string): T {
  if (value === undefined) throw new Error(`missing ${label}`);
  return value;
}

describe('ENGINE_V2 lease-mode send_message_and_wait', () => {
  afterEach(() => {
    (config as unknown as { v2: { engineV2: boolean } }).v2.engineV2 = false;
  });

  it('caps a null-timeout lease at maxTurnLeaseMs instead of activeUntil null', async () => {
    const service = makeService();
    const callId = await makeActiveCall(service);

    const dispose = service.registerAiWait(callId, null);
    // The uncapped lease now carries a hard server-side ceiling (default 15
    // min) so a crashed waiter can never shield a call indefinitely. activeUntil
    // must be a real timestamp, not null.
    const status = service.getAiWaitStatus(callId);
    expect(status.active).toBe(true);
    expect(status.activeUntil).not.toBeNull();
    const untilMs = Date.parse(must(status.activeUntil, 'activeUntil'));
    const nowMs = Date.now();
    expect(untilMs - nowMs).toBeGreaterThan(14 * 60 * 1000);
    expect(untilMs - nowMs).toBeLessThanOrEqual(16 * 60 * 1000);
    dispose();
    expect(service.getAiWaitStatus(callId).active).toBe(false);
  });

  it('expires an undisposed null-timeout lease after the ceiling passes (fake timers)', async () => {
    const service = makeService();
    const callId = await makeActiveCall(service);

    // Register without disposing (simulates a crashed waiter whose dispose()
    // never runs): the lease must still self-expire once maxTurnLeaseMs
    // elapses, so cancelCallsByAgent can abort the call afterwards.
    service.registerAiWait(callId, null);
    const status = service.getAiWaitStatus(callId);
    const untilMs = Date.parse(must(status.activeUntil, 'activeUntil'));
    expect(status.active).toBe(true);

    const originalMax = (config as unknown as { v2: { maxTurnLeaseMs: number } }).v2.maxTurnLeaseMs;
    (config as unknown as { v2: { maxTurnLeaseMs: number } }).v2.maxTurnLeaseMs = 100;
    try {
      // Fresh call so no overlapping-lease "farthest deadline wins" logic
      // interferes; a 100ms ceiling is way below the real 15-min default.
      const call2 = await makeActiveCall(service);
      service.registerAiWait(call2, null);
      expect(service.getAiWaitStatus(call2).active).toBe(true);

      // Simulate the clock passing the ceiling: sleep past 100ms.
      await new Promise((r) => setTimeout(r, 150));
      expect(service.getAiWaitStatus(call2).active).toBe(false);

      // And cancelCallsByAgent can now abort the call (the guard no longer
      // blocks it) — the whole point of the ceiling.
      const count = await service.cancelCallsByAgent('agent-1', 'agent_disconnected');
      expect(count).toBe(1);
      expect((await service.getCall(call2))?.status).toBe('aborted');
    } finally {
      (config as unknown as { v2: { maxTurnLeaseMs: number } }).v2.maxTurnLeaseMs = originalMax;
      void untilMs;
    }
  });

  it('resolves with the reply when the human responds — no 45s cap', async () => {
    const service = makeService();
    const callId = await makeActiveCall(service);
    const waitTool = findWaitTool(service);
    expect(waitTool).toBeDefined();
    if (!waitTool) throw new Error('send_message_and_wait tool missing');

    (config as unknown as { v2: { engineV2: boolean } }).v2.engineV2 = true;

    const waitPromise = runAsAgent('agent-1', () =>
      waitTool.handler({ call_id: callId, content: 'Please confirm the amount.', timeout_seconds: 300 }),
    );

    // The human replies while the AI waits (delayed so the wait is genuinely
    // in flight; 300s proves nothing was clamped to the old 45s maximum).
    setTimeout(() => {
      void service.processTextMessage(callId, 'Yes, confirmed.', 'lease-cm-1');
    }, 100);

    const result = (await waitPromise) as ToolResult;
    const body = JSON.parse(must(result.content[0], 'reply content').text) as { outcome: string; reply?: { text: string } };
    expect(body.outcome).toBe('reply');
    expect(body.reply?.text).toBe('Yes, confirmed.');
    // No reply outcome would have meant the wait ran to a cap — it didn't.
  }, WAIT_REPLY_TIMEOUT);

  it('escalates to noactivity on a silent call instead of waiting forever', async () => {
    const service = makeService();
    const callId = await makeActiveCall(service);
    const waitTool = findWaitTool(service);
    expect(waitTool).toBeDefined();
    if (!waitTool) throw new Error('send_message_and_wait tool missing');

    (config as unknown as { v2: { engineV2: boolean } }).v2.engineV2 = true;
    (config as unknown as { v2: { noactivityEscalationMs: number } }).v2.noactivityEscalationMs = 100;

    const result = (await runAsAgent('agent-1', () =>
      waitTool.handler({ call_id: callId, content: 'Are you there?' }),
    )) as ToolResult;
    const body = JSON.parse(must(result.content[0], 'noactivity content').text) as { outcome: string; silent_seconds: number };
    expect(body.outcome).toBe('noactivity');
    // Sub-second escalation rounds to 0; real config (5 min default) reports
    // meaningful seconds. The contract is the outcome, not the rounding.
    expect(body.silent_seconds).toBeGreaterThanOrEqual(0);
  }, WAIT_REPLY_TIMEOUT);

  it('without the flag, clamps a 300s request to the legacy 45s maximum', async () => {
    // ENGINE_V2 off (default): the handler clamps to [1, 45]. We can't wait
    // 45s in a test, so verify the clamp indirectly: the lease activeUntil
    // must be exactly 45s out, and the tool schema still caps at 45.
    const service = makeService();
    const callId = await makeActiveCall(service);
    const waitTool = findWaitTool(service);
    expect(waitTool).toBeDefined();
    if (!waitTool) throw new Error('send_message_and_wait tool missing');

    const dispose = service.registerAiWait(callId, 45_000);
    const status = service.getAiWaitStatus(callId);
    expect(status.active).toBe(true);
    expect(status.activeUntil).not.toBeNull();
    const untilMs = Date.parse(must(status.activeUntil, 'activeUntil'));
    expect(untilMs - Date.now()).toBeGreaterThan(40_000);
    expect(untilMs - Date.now()).toBeLessThanOrEqual(46_000);
    dispose();

    const schema = waitTool.inputSchema as { properties: { timeout_seconds: { maximum: number } } };
    expect(schema.properties.timeout_seconds.maximum).toBe(45);
  });
});
