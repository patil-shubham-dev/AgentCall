const BASE = 'https://agentcall-66ke.onrender.com';

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

async function mcp(method, body, headers = {}) {
  const res = await fetch(`${BASE}/mcp`, {
    method,
    headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, body: parseSse(text)[0] ?? null, sessionId: res.headers.get('mcp-session-id'), text: text.slice(0, 120) };
}

const out = {};
try {
  const phone = await post('/api/v1/phone/token', { user_id: 'solo-user' });
  if (phone.status !== 200) throw new Error('phone token: ' + phone.text);
  const phoneToken = phone.json.token;

  const keyRes = await post('/api/v1/ai/keys', { name: 'LiveSessionTest' }, { Authorization: `Bearer ${phoneToken}` });
  if (keyRes.status !== 201) throw new Error('ai key: ' + keyRes.text);
  const aiKey = keyRes.json.key;
  out.keyId = keyRes.json.key_id;

  const init = await mcp('POST', { jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'live-session', version: '1.0' } } }, { Authorization: `Bearer ${aiKey}` });
  const sid = init.sessionId;
  out.initStatus = init.status;
  out.sessionId = sid;
  console.log('INIT status=' + init.status + ' sid=' + sid);
  const auth = { Authorization: `Bearer ${aiKey}`, 'Mcp-Session-Id': sid };

  await mcp('POST', { jsonrpc: '2.0', id: 2, method: 'notifications/initialized', params: {} }, auth);
  const list1 = await mcp('POST', { jsonrpc: '2.0', id: 3, method: 'tools/list', params: {} }, auth);
  out.listBeforeWait = list1.status;
  console.log('TOOLS_BEFORE_WAIT status=' + list1.status);

  // Sit idle for >1 sweep cycle (deployed sweep interval is 60s, idle 30 min).
  // If touch() on every request is working, the session must survive.
  console.log('IDLE_80S_START ' + new Date().toISOString());
  await new Promise((r) => setTimeout(r, 80000));
  console.log('IDLE_80S_END ' + new Date().toISOString());

  const list2 = await mcp('POST', { jsonrpc: '2.0', id: 4, method: 'tools/list', params: {} }, auth);
  out.listAfterWait = list2.status;
  console.log('TOOLS_AFTER_WAIT status=' + list2.status);

  const del = await mcp('DELETE', null, auth);
  out.deleteStatus = del.status;
  console.log('DELETE status=' + del.status);

  const list3 = await mcp('POST', { jsonrpc: '2.0', id: 5, method: 'tools/list', params: {} }, auth);
  out.listAfterDelete = list3.status;
  out.afterDeleteError = list3.body?.error ?? null;
  console.log('TOOLS_AFTER_DELETE status=' + list3.status + ' error=' + (list3.body?.error ?? 'none'));

  const del2 = await fetch(`${BASE}/api/v1/ai/keys/${out.keyId}`, { method: 'DELETE', headers: { Authorization: `Bearer ${phoneToken}` } });
  out.keyDeleted = del2.status;
  out.success = true;
} catch (e) {
  out.error = String(e && e.message ? e.message : e);
  out.success = false;
}
console.log(JSON.stringify(out, null, 2));
process.exit(0);
