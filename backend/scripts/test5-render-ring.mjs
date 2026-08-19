// Test 5-unplugged driver: rings the phone via the deployed Render backend and
// polls the call record remotely. No adb needed — the phone is unplugged.
//
// Usage: node scripts/test5-render-ring.mjs
// Prints a timeline of status transitions; exits after the call resolves.
import { writeFileSync } from 'node:fs';

const BASE = 'https://agentcall-66ke.onrender.com';
const OUT = 'C:/Users/91808/AppData/Local/Temp/e2e/t5u/';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

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

const log = (msg) => {
  const line = `[${new Date().toISOString()}] ${msg}`;
  console.log(line);
  try { writeFileSync(OUT + 'ring.log', line + '\n', { flag: 'a' }); } catch {}
};

try {
  const phone = await post('/api/v1/phone/token', { user_id: 'solo-user' });
  if (phone.status !== 200) throw new Error('phone token: ' + phone.text);
  const phoneToken = phone.json.token;

  const keyRes = await post('/api/v1/ai/keys', { name: 'Test5Unplugged' }, { Authorization: `Bearer ${phoneToken}` });
  if (keyRes.status !== 201) throw new Error('ai key: ' + keyRes.text);
  const aiKey = keyRes.json.key;
  const keyId = keyRes.json.key_id;

  const init = await mcp({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 't5u-driver', version: '1.0' } } }, { Authorization: `Bearer ${aiKey}` });
  if (init.status !== 200 || !init.sessionId) throw new Error('MCP init failed: ' + init.raw);
  const sid = init.sessionId;
  const auth = { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid };
  await mcp({ jsonrpc: '2.0', id: 2, method: 'notifications/initialized', params: {} }, auth);

  const t0 = Date.now();
  const call = await mcp({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'create_call', arguments: { context: { reason: 'clarification', summary: 'Test 5-unplugged: phone idle 60+ min unplugged. Please answer.' } } } }, auth);
  const callId = toolResult(call.body).call_id;
  if (!callId) throw new Error('create_call: ' + call.raw);
  log(`CALL_CREATED ${callId} t0=${t0}`);
  writeFileSync(OUT + 'mark.called', `callId=${callId}\ncreatedAt=${new Date().toISOString()}\n`);

  // Poll the call record remotely every 5s until it resolves (60s ring + slack).
  let lastStatus = null;
  let lastTs = 0;
  // Fallback poll in Doze runs every ~5 min; the backend pending-TTL sweep
  // cancels at ~3-5 min, so a late poll ring can resolve late. Watch 10 min.
  const deadline = Date.now() + 10 * 60 * 1000;
  while (Date.now() < deadline) {
    await sleep(5000);
    const rec = await get(`/api/v1/calls/${callId}`, { Authorization: `Bearer ${phoneToken}` });
    const status = rec.json?.status;
    if (status && status !== lastStatus) {
      const now = Date.now();
      log(`STATUS ${lastStatus ?? '-'} -> ${status} at +${((now - t0) / 1000).toFixed(1)}s (created_to_change=${((now - t0) / 1000).toFixed(1)}s)`);
      lastStatus = status;
      lastTs = now;
      if (status === 'cancelled' || status === 'expired' || status === 'completed' || status === 'aborted') {
        writeFileSync(OUT + 'mark.done', `finalStatus=${status}\nchangedAt=${new Date().toISOString()}\nchangeAfterMs=${now - t0}\n`);
        break;
      }
    }
  }
  if (!lastStatus) log('NO_STATUS_CHANGE within 6 min — phone never resolved the call remotely');

  try { await mcp({ jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'cancel_call', arguments: { call_id: callId } } }, auth); } catch {}
  try { await fetch(`${BASE}/api/v1/ai/keys/${keyId}`, { method: 'DELETE', headers: { Authorization: `Bearer ${phoneToken}` } }); } catch {}
  log('DRIVER_DONE');
  process.exit(0);
} catch (e) {
  log('DRIVER_ERROR ' + String(e?.message ?? e));
  process.exit(1);
}
