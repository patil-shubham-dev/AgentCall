import { existsSync, writeFileSync, unlinkSync } from 'node:fs';

const BASE = 'https://agentcall-66ke.onrender.com';
const MARK = 'C:/Users/91808/AppData/Local/Temp/opencode/';

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
  const keyRes = await post('/api/v1/ai/keys', { name: 'FcmRenderWake' }, { Authorization: `Bearer ${phoneToken}` });
  if (keyRes.status !== 201) throw new Error('ai key: ' + keyRes.text);
  const aiKey = keyRes.json.key;
  const keyId = keyRes.json.key_id;

  // 3. MCP init
  const init = await mcp({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'fcm-render-wake', version: '1.0' } } }, { Authorization: `Bearer ${aiKey}` });
  if (init.status !== 200 || !init.sessionId) throw new Error('MCP init failed: ' + init.raw);
  const sid = init.sessionId;
  const auth = { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid };
  await mcp({ jsonrpc: '2.0', id: 2, method: 'notifications/initialized', params: {} }, auth);

  // 4. Ping loop (keep session alive for 90+ minutes)
  const pingTimer = setInterval(() => {
    fetch(`${BASE}/mcp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...auth },
      body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/ping' }),
    }).catch(() => {});
  }, 10_000);
  pingTimer.unref?.();

  console.log('SESSION_READY pointing at Render, waiting for go-render-wake.txt');
  setMark('render-wake-driver-ready.flag', '1');

  // 5. Wait for go signal (up to 120 minutes — long idle test)
  const deadline = Date.now() + 120 * 60 * 1000;
  while (!existsSync(MARK + 'go-render-wake.txt') && Date.now() < deadline) {
    await sleep(500);
  }
  if (!existsSync(MARK + 'go-render-wake.txt')) {
    console.log('NO_GO_SIGNAL_TIMEOUT');
  } else {
    clearMark('go-render-wake.txt');
    const t0 = Date.now();
    console.log('CALL_CREATE_AT ' + new Date().toISOString());
    const call = await mcp({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'create_call', arguments: { context: { reason: 'clarification', summary: 'FCM Render wake-test call. Please answer on the phone.' } } } }, auth);
    const callId = toolResult(call.body).call_id;
    if (!callId) throw new Error('create_call: ' + call.raw);
    console.log('CALL_CREATED ' + callId + ' +' + (Date.now() - t0) + 'ms');
    setMark('ring.flag', callId);
    // Hold for 5 minutes then cleanup
    await sleep(5 * 60 * 1000);
  }

  // 6. Cleanup
  try { await mcp({ jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'cancel_call', arguments: { call_id: 'last' } } }, auth); } catch {}
  const del = await fetch(`${BASE}/api/v1/ai/keys/${keyId}`, { method: 'DELETE', headers: { Authorization: `Bearer ${phoneToken}` } });
  console.log('CLEANUP key_delete=' + del.status);
  clearInterval(pingTimer);
} catch (e) {
  console.log('ERROR: ' + (e.message ?? e));
}
