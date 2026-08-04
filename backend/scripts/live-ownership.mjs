import { writeFileSync } from 'node:fs';

const BASE = 'https://agentcall-66ke.onrender.com';
const MARK = 'C:/Users/91808/AppData/Local/Temp/opencode/';

async function post(path, body, headers = {}) {
  const res = await fetch(BASE + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
  const text = await res.text();
  let json = null;
  try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text: text.slice(0, 300) };
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
  return { status: res.status, body: parseSse(text)[0] ?? null, sessionId: res.headers.get('mcp-session-id') };
}

const out = {};

try {
  const phone = await post('/api/v1/phone/token', { user_id: 'solo-user' });
  if (phone.status !== 200) throw new Error('phone token: ' + phone.text);
  const phoneToken = phone.json.token;

  const a = await post('/api/v1/ai/keys', { name: 'LiveOwnerA' }, { Authorization: `Bearer ${phoneToken}` });
  const b = await post('/api/v1/ai/keys', { name: 'LiveOwnerB' }, { Authorization: `Bearer ${phoneToken}` });
  if (a.status !== 201 || b.status !== 201) throw new Error('key minting failed');
  const keyA = a.json.key;
  const keyB = b.json.key;
  out.keyAId = a.json.key_id;
  out.keyBId = b.json.key_id;

  const initA = await mcp({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'live-ownership', version: '1.0' } } }, { Authorization: `Bearer ${keyA}` });
  const initB = await mcp({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'live-ownership', version: '1.0' } } }, { Authorization: `Bearer ${keyB}` });
  if (!initA.sessionId || !initB.sessionId) throw new Error('MCP init failed');
  const sidA = initA.sessionId;
  const sidB = initB.sessionId;
  const authA = { Authorization: `Bearer ${keyA}`, 'Mcp-Session-Id': sidA };
  const authB = { Authorization: `Bearer ${keyB}`, 'Mcp-Session-Id': sidB };
  await mcp({ jsonrpc: '2.0', id: 2, method: 'notifications/initialized', params: {} }, authA);
  await mcp({ jsonrpc: '2.0', id: 2, method: 'notifications/initialized', params: {} }, authB);

  let rpc = 10;
  const callTool = async (auth, name, args) => {
    const res = await mcp({ jsonrpc: '2.0', id: rpc++, method: 'tools/call', params: { name, arguments: args } }, auth);
    const result = res.body?.result;
    const text = result?.content?.[0]?.text ?? '';
    return { isError: result?.isError === true, text, raw: text.slice(0, 120) };
  };

  const created = await callTool(authA, 'create_call', { context: { reason: 'clarification', summary: 'Live per-call ownership test. No action needed.' } });
  const callId = (() => { try { return JSON.parse(created.text).call_id; } catch { return null; } })();
  if (!callId) throw new Error('create_call failed: ' + created.raw);
  out.callId = callId;
  console.log('CALL_CREATED ' + callId);

  out.crossAgentAttempts = [];
  for (const [name, args] of [
    ['get_transcript', { call_id: callId }],
    ['send_message', { call_id: callId, content: 'intrusion attempt' }],
    ['complete_call', { call_id: callId, result: { decision: 'stolen' } }],
    ['cancel_call', { call_id: callId }],
    ['send_message_and_wait', { call_id: callId, content: 'intrusion attempt', timeout_seconds: 1 }],
  ]) {
    const r = await callTool(authB, name, args);
    out.crossAgentAttempts.push({ tool: name, isError: r.isError, forbidden: r.text.includes('Forbidden'), text: r.raw });
    console.log(`CROSS_AGENT ${name} -> isError=${r.isError} forbidden=${r.text.includes('Forbidden')}`);
  }

  const transcript = await callTool(authA, 'get_transcript', { call_id: callId });
  out.ownerTranscript = transcript.text.slice(0, 200);
  const sent = await callTool(authA, 'send_message', { call_id: callId, content: 'Ownership check message' });
  out.ownerSend = sent.text.slice(0, 200);
  const completed = await callTool(authA, 'complete_call', { call_id: callId, result: { decision: 'ownership test complete' } });
  out.ownerComplete = completed.text.slice(0, 200);

  const call = await fetch(`${BASE}/api/v1/calls/${callId}`, { headers: { Authorization: `Bearer ${phoneToken}` } }).then((r) => r.json());
  out.finalCall = { status: call.status, messageCount: call.messages?.length ?? 0, agentId: call.agentId };

  for (const [id] of [[out.keyAId], [out.keyBId]]) {
    const d = await fetch(`${BASE}/api/v1/ai/keys/${id}`, { method: 'DELETE', headers: { Authorization: `Bearer ${phoneToken}` } });
    out[`keyDeleted_${id.slice(0, 8)}`] = d.status;
  }
  out.success = true;
} catch (e) {
  out.error = String(e && e.message ? e.message : e);
  out.success = false;
}

console.log(JSON.stringify(out, null, 2));
writeFileSync(MARK + 'ownership-result.json', JSON.stringify(out, null, 2));
process.exit(0);
