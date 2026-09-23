#!/usr/bin/env node
/**
 * Overnight validation driver — creates a real call via the MCP endpoint so
 * the connected device receives an FCM ring. Used by the Task 2 scenarios.
 * Reads MCP_KEY from env. Never prints the key.
 */
const HOST = 'https://agentcall-66ke.onrender.com';
const key = process.env.MCP_KEY;
if (!key) { console.error('MCP_KEY not set'); process.exit(2); }

const REASON = process.argv[2] || 'overnight-validation';
const SUMMARY = process.argv[3] || 'Overnight Task 2 ring validation call. Please answer to confirm the ring rework, or let it time out.';

function authHeaders(sessionId) {
  const h = {
    'Authorization': `Bearer ${key}`,
    'Content-Type': 'application/json',
    'Accept': 'application/json, text/event-stream',
  };
  if (sessionId) h['mcp-session-id'] = sessionId;
  return h;
}

async function rpc(sessionId, body, timeoutMs = 20000) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const res = await fetch(`${HOST}/mcp`, {
      method: 'POST',
      headers: authHeaders(sessionId),
      body: JSON.stringify(body),
      signal: ctrl.signal,
    });
    const sid = res.headers.get('mcp-session-id');
    const text = await res.text();
    return { status: res.status, sessionId: sid, text };
  } finally {
    clearTimeout(t);
  }
}

async function main() {
  // 1. initialize
  const init = await rpc(null, {
    jsonrpc: '2.0', id: 1, method: 'initialize',
    params: {
      protocolVersion: '2024-11-05',
      capabilities: {},
      clientInfo: { name: 'overnight-validate', version: '1.0.0' },
    },
  });
  if (init.status !== 200) { console.error('initialize failed', init.status, init.text.slice(0, 200)); process.exit(1); }
  const sessionId = init.sessionId;
  if (!sessionId) { console.error('no session id returned'); process.exit(1); }
  console.log('MCP session established');

  // 2. initialized notification
  await rpc(sessionId, { jsonrpc: '2.0', method: 'notifications/initialized' });

  // 3. create_call
  const call = await rpc(sessionId, {
    jsonrpc: '2.0', id: 2, method: 'tools/call',
    params: {
      name: 'create_call',
      arguments: {
        context: { reason: 'input_required', summary: SUMMARY },
        priority: 'normal',
      },
    },
  }, 30000);
  console.log('create_call status', call.status);
  console.log(call.text.slice(0, 500));
  console.log('SCENARIO=' + REASON);
}

main().catch((e) => { console.error('driver error', e.message); process.exit(1); });
