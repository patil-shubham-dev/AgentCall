import { mkdirSync, writeFileSync, readFileSync, existsSync, unlinkSync } from 'node:fs';

// Local E2E driver for the device test protocol (tests 1-5).
// Targets the LOCAL backend on 127.0.0.1:4000 via adb reverse.
const BASE = 'http://127.0.0.1:4000';
const MARK = 'C:/Users/91808/AppData/Local/Temp/e2e/';
const phase = process.argv[2] ?? 'ring';
const out = { phase };

try { mkdirSync(MARK, { recursive: true }); } catch {}

async function post(path, body, headers = {}) {
  const res = await fetch(BASE + path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', ...headers },
    body: JSON.stringify(body),
  });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text: text.slice(0, 300) };
}

async function del(path, headers = {}) {
  const res = await fetch(BASE + path, { method: 'DELETE', headers });
  return { status: res.status };
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
  return { status: res.status, body: parsed[0] ?? null, sessionId: res.headers.get('mcp-session-id'), raw: text.slice(0, 300) };
}

const toolResult = (body) => {
  try { return JSON.parse(body?.result?.content?.[0]?.text ?? '{}'); } catch { return {}; }
};

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function setMark(name, content) {
  try { writeFileSync(MARK + name, content ?? '1'); } catch {}
}

try {
  // 1. Phone token (local backend)
  const phone = await post('/api/v1/phone/token', { user_id: 'solo-user' });
  if (phone.status !== 200) throw new Error('phone token: ' + phone.text);
  const phoneToken = phone.json.token;

  // 2. Fresh AI key = the agent identity for this test run
  const keyName = `E2E-${phase}-${Date.now().toString(36)}`;
  const keyRes = await post('/api/v1/ai/keys', { name: keyName }, { Authorization: `Bearer ${phoneToken}` });
  if (keyRes.status !== 201) throw new Error('ai key: ' + keyRes.text);
  const aiKey = keyRes.json.key;
  const keyId = keyRes.json.key_id;
  out.agentName = keyName;
  out.keyId = keyId;
  setMark(`${phase}.agent`, keyName);

  // 3. MCP init (holds the agent's only session)
  const init = await mcp({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'e2e-driver', version: '1.0' } } }, { Authorization: `Bearer ${aiKey}` });
  if (init.status !== 200 || !init.sessionId) throw new Error('MCP init failed: ' + init.raw);
  const sid = init.sessionId;
  const auth = { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid };
  await mcp({ jsonrpc: '2.0', id: 2, method: 'notifications/initialized', params: {} }, auth);

  // 3b. Heartbeat: prove the session is alive. The backend's 45s liveness
  // sweep (MCP_LIVENESS_TIMEOUT_MS) closes sessions whose notifications/ping
  // stop — a kill -9 here must drop the pings so the sweep aborts the call.
  const pingTimer = setInterval(() => {
    fetch(`${BASE}/mcp`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', ...auth },
      body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/ping' }),
    }).catch(() => {});
  }, 10_000);
  pingTimer.unref?.();

  // 4. create_call (rings the phone)
  const summary = phase === 'normal'
    ? 'E2E test 1: normal call lifecycle. Please answer and tap a quick reply.'
    : phase === 'midcall-kill'
      ? 'E2E test 3: this call will be force-killed mid-call.'
      : 'E2E test 2/5: please answer this call.';
  const call = await mcp({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'create_call', arguments: { context: { reason: 'clarification', summary, options: ['Yes', 'No'] } } } }, auth);
  const callId = toolResult(call.body).call_id;
  if (!callId) throw new Error('create_call: ' + call.raw);
  out.callId = callId;
  console.log('CALL_CREATED ' + callId + ' agent=' + keyName);
  console.log('CREATED_AT ' + new Date().toISOString());
  setMark(`${phase}.call`, callId);
  setMark(`${phase}.createdAt`, Date.now().toString());

  // 5. Phase behavior
  if (phase === 'normal') {
    // Test 1: wait for the phone to answer (go-answer.txt), exchange turns,
    // then complete when told (go-complete.txt).
    const deadline = Date.now() + 10 * 60 * 1000;
    while (!existsSync(MARK + 'normal.answer') && Date.now() < deadline) await sleep(500);
    if (!existsSync(MARK + 'normal.answer')) throw new Error('answer timeout');
    console.log('ANSWERED_AT ' + new Date().toISOString());
    const t0 = Date.now();
    const wait = mcp({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'send_message_and_wait', arguments: { call_id: callId, content: 'Hello! I am the test agent. Please tap a quick reply.', timeout_seconds: 60 } } }, auth);
    const done = await wait;
    out.firstReplyMs = Date.now() - t0;
    out.replyText = (done.body?.result?.content?.[0]?.text ?? '').slice(0, 300);
    console.log('FIRST_REPLY_AT ' + new Date().toISOString() + ' delayMs=' + out.firstReplyMs);
    setMark('normal.firstReply', JSON.stringify(out));
    // Second turn
    const t1 = Date.now();
    const wait2 = mcp({ jsonrpc: '2.0', id: 5, method: 'tools/call', params: { name: 'send_message_and_wait', arguments: { call_id: callId, content: 'Great, thank you. One more tap and we are done.', timeout_seconds: 60 } } }, auth);
    await wait2;
    out.secondReplyMs = Date.now() - t1;
    console.log('SECOND_REPLY_AT ' + new Date().toISOString());
    while (!existsSync(MARK + 'normal.complete') && Date.now() < deadline) await sleep(500);
    const comp = await mcp({ jsonrpc: '2.0', id: 6, method: 'tools/call', params: { name: 'complete_call', arguments: { call_id: callId, result: { transcript_summary: 'E2E normal call', user_response: out.replyText } } } }, auth);
    out.completeStatus = toolResult(comp.body).status;
    console.log('COMPLETED_AT ' + new Date().toISOString() + ' status=' + out.completeStatus);
  } else if (phase === 'ring' || phase === 'ring-kill' || phase === 'midcall-kill') {
    // Tests 2/3/5: the harness kills this process (kill -9) at the right
    // moment. Just hold the session and report the call id; also print a
    // heartbeat every 2s so the harness can confirm the session is alive.
    console.log('HOLDING_SESSION — kill -9 this PID to simulate agent crash');
    setMark(`${phase}.pid`, String(process.pid));
    for (let i = 0; i < 60 * 30; i++) {
      await sleep(2000);
      if (i % 15 === 0) console.log('ALIVE ' + new Date().toISOString());
    }
  } else if (phase === 'delete') {
    // Graceful control: after the harness answers, run send_message_and_wait
    // then DELETE the session (graceful agent exit) to observe the abort path.
    const deadline = Date.now() + 10 * 60 * 1000;
    while (!existsSync(MARK + 'delete.answer') && Date.now() < deadline) await sleep(500);
    if (!existsSync(MARK + 'delete.answer')) throw new Error('answer timeout');
    const t0 = Date.now();
    const wait = mcp({ jsonrpc: '2.0', id: 4, method: 'tools/call', params: { name: 'send_message_and_wait', arguments: { call_id: callId, content: 'This agent will now disconnect gracefully.', timeout_seconds: 60 } } }, auth);
    const done = await wait;
    out.replyMs = Date.now() - t0;
    console.log('REPLY_AT ' + new Date().toISOString() + ' delayMs=' + out.replyMs);
    // Graceful exit: explicit DELETE /mcp
    const delRes = await fetch(`${BASE}/mcp`, {
      method: 'DELETE',
      headers: { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid },
    });
    out.deleteStatus = delRes.status;
    console.log('SESSION_DELETED_AT ' + new Date().toISOString() + ' http=' + delRes.status);
    await sleep(3000);
  }

  // 7. Cleanup (only reached on graceful paths)
  try { await mcp({ jsonrpc: '2.0', id: 99, method: 'tools/call', params: { name: 'cancel_call', arguments: { call_id: callId } } }, auth); out.cancelled = true; } catch {}
  try { await del(`/api/v1/ai/keys/${keyId}`, { Authorization: `Bearer ${phoneToken}` }); } catch {}
  out.success = true;
} catch (e) {
  out.error = String(e && e.message ? e.message : e);
  out.success = false;
}

console.log(JSON.stringify(out, null, 2));
process.exit(0);
