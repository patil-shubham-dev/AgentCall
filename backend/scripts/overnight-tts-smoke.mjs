#!/usr/bin/env node
/**
 * Overnight TTS smoke: send a message over an active call via MCP so the
 * phone synthesizes it with the system TTS engine. Reads MCP_KEY from env.
 * Never prints the key.
 */
const HOST = 'https://agentcall-66ke.onrender.com';
const callId = process.argv[2];
if (!callId) {
  console.error('usage: node overnight-tts-smoke.mjs <callId>');
  process.exit(2);
}
const key = process.env.MCP_KEY;
if (!key) {
  console.error('MCP_KEY not set');
  process.exit(2);
}

const headers = {
  'content-type': 'application/json',
  accept: 'application/json, text/event-stream',
  authorization: `Bearer ${key}`,
};

const rpc = (id, method, params) =>
  JSON.stringify({ jsonrpc: '2.0', id, method, params });

async function main() {
  const initRes = await fetch(`${HOST}/mcp`, {
    method: 'POST',
    headers,
    body: rpc(1, 'initialize', {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'overnight-tts-smoke', version: '1.0' },
    }),
    signal: AbortSignal.timeout(30000),
  });
  const sid = initRes.headers.get('mcp-session-id');
  console.log('initialize:', initRes.status, 'session:', sid ? 'ok' : 'MISSING');
  if (!sid) {
    console.log((await initRes.text()).slice(0, 300));
    process.exit(1);
  }

  await fetch(`${HOST}/mcp`, {
    method: 'POST',
    headers: { ...headers, 'mcp-session-id': sid },
    body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }),
    signal: AbortSignal.timeout(15000),
  });

  const msgRes = await fetch(`${HOST}/mcp`, {
    method: 'POST',
    headers: { ...headers, 'mcp-session-id': sid },
    body: rpc(10, 'tools/call', {
      name: 'send_message',
      arguments: {
        call_id: callId,
        message:
          'Overnight smoke test: this is the TTS check. If you can hear this, speak-back works.',
        timeout_seconds: 4,
      },
    }),
    signal: AbortSignal.timeout(30000),
  });
  console.log('send_message:', msgRes.status);
  console.log((await msgRes.text()).slice(0, 500));
}

main().catch((err) => {
  console.error('FAILED:', err.message);
  process.exit(1);
});
