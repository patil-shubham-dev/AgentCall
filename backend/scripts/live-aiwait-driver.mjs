import { existsSync, writeFileSync, unlinkSync } from 'node:fs';

const BASE = 'https://agentcall-66ke.onrender.com';
const MARK = 'C:/Users/91808/AppData/Local/Temp/opencode/';
const phase = process.argv[2] ?? 'full';
const out = { phase };

async function post(path, body, headers = {}) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text: text.slice(0, 200) };
}

async function get(path, headers = {}) {
  const res = await fetch(BASE + path, { headers });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text: text.slice(0, 200) };
}

function parseSse(text) {
  const msgs = [];
  for (const block of text.split(/\r?\n\r?\n/)) {
    const data = block.split(/\r?\n/).filter((l) => l.startsWith('data: ')).map((l) => l.slice(6)).join('\n');
    if (data.length) msgs.push(JSON.parse(data));
  }
  return msgs;
}

async function mcp(body, headers = {}) {
  const res = await fetch(`${BASE}/mcp`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...headers },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  const parsed = parseSse(text);
  return { status: res.status, body: parsed[0] ?? null, sessionId: res.headers.get('mcp-session-id'), raw: text.slice(0, 250) };
}

const toolResult = (body) => {
  try { return JSON.parse(body?.result?.content?.[0]?.text ?? '{}'); } catch { return {}; }
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function setMark(name, content) {
  try { writeFileSync(MARK + name, content ?? '1'); } catch {}
}
function clearMark(name) {
  try { unlinkSync(MARK + name); } catch {}
}

try {
  // 1. Phone token
  const phone = await post('/api/v1/phone/token', { user_id: 'solo-user' });
  if (phone.status !== 200) throw new Error('phone token: ' + phone.text);
  const phoneToken = phone.json.token;

  // 2. Temp AI key
  const keyRes = await post('/api/v1/ai/keys', { name: 'LiveLeaseTest' }, { Authorization: `Bearer ${phoneToken}` });
  if (keyRes.status !== 201) throw new Error('ai key: ' + keyRes.text);
  const aiKey = keyRes.json.key;
  const keyId = keyRes.json.key_id;
  out.keyId = keyId;

  // (No own WebSocket here: the phone app holds the solo-user connection.
  //  Opening a second one would stomp it — registerPhone replaces the
  //  existing connection — and steal the call_incoming push.)

  // 4. MCP init
  const init = await mcp({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'live-driver', version: '1.0' } } }, { Authorization: `Bearer ${aiKey}` });
  if (init.status !== 200 || !init.sessionId) throw new Error('MCP init failed: ' + init.raw);
  const sid = init.sessionId;
  const auth = { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid };
  await mcp({ jsonrpc: '2.0', id: 2, method: 'notifications/initialized', params: {} }, auth);

  // 5. create_call (rings the phone)
  const call = await mcp({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'create_call', arguments: { context: { reason: 'clarification', summary: 'On-device AI-wait banner test. Please answer this test call.' } } } }, auth);
  const callId = toolResult(call.body).call_id;
  if (!callId) throw new Error('create_call: ' + call.raw);
  out.callId = callId;
  console.log('CALL_CREATED ' + callId);
  console.log('RINGING_PHONE — waiting for signal file go-lease.txt');
  setMark('ring.flag', callId);

  // 6. Wait for the go signal (up to 8 min), then run the lease toggle
  const deadline = Date.now() + 8 * 60 * 1000;
  while (!existsSync(MARK + 'go-lease.txt') && Date.now() < deadline) {
    await sleep(500);
  }
  if (!existsSync(MARK + 'go-lease.txt')) {
    console.log('NO_GO_SIGNAL_TIMEOUT');
  } else {
    clearMark('go-lease.txt');
    console.log('LEASE_START ' + new Date().toISOString());
    const waitPromise = mcp({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'send_message_and_wait', arguments: { call_id: callId, content: 'I am waiting for your reply — please say something.', timeout_seconds: 25 } } }, auth);
    await sleep(2500);
    console.log('LEASE_ACTIVE_MARK ' + new Date().toISOString());
    setMark('lease-active.flag', '1');
    const waitDone = await waitPromise;
    out.waitStatusText = (waitDone.body?.result?.content?.[0]?.text ?? '').slice(0, 200);
    console.log('LEASE_DONE ' + new Date().toISOString());
    setMark('lease-done.flag', '1');
    // Window for a post-wait screencap while the call is still open
    await sleep(6000);
  }

  // 7. Report + cleanup
  try { await mcp({ jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'cancel_call', arguments: { call_id: callId } } }, auth); out.cancelled = true; } catch {}
  const del = await fetch(`${BASE}/api/v1/ai/keys/${keyId}`, { method: 'DELETE', headers: { Authorization: `Bearer ${phoneToken}` } });
  out.keyDeletedStatus = del.status;
  out.success = true;
} catch (e) {
  out.error = String(e && e.message ? e.message : e);
  out.success = false;
}

console.log(JSON.stringify(out, null, 2));
process.exit(0);