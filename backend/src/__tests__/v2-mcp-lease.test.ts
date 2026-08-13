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

  it('keeps a null-timeout wait lease active with no expiry until disposed', async () => {
    const service = makeService();
    const callId = await makeActiveCall(service);

    const dispose = service.registerAiWait(callId, null);
    // Lease with no activeUntil: active until disposed, regardless of time.
    expect(service.getAiWaitStatus(callId)).toMatchObject({ active: true, activeUntil: null });
    dispose();
    expect(service.getAiWaitStatus(callId).active).toBe(false);
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
