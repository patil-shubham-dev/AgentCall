const BASE = 'https://agentcall-66ke.onrender.com';
async function post(path, body, headers = {}) {
  const res = await fetch(BASE + path, { method: 'POST', headers: { 'Content-Type': 'application/json', ...headers }, body: JSON.stringify(body) });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch {}
  return { status: res.status, json, text };
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
  const res = await fetch(`${BASE}/mcp`, { method: 'POST', headers: { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream', ...headers }, body: JSON.stringify(body) });
  const text = await res.text();
  return { status: res.status, body: (parseSse(text)[0] ?? null), sessionId: res.headers.get('mcp-session-id') };
}
(async () => {
  const phone = await post('/api/v1/phone/token', { user_id: 'solo-user' });
  const phoneToken = phone.json.token;
  const keys = await (await fetch(`${BASE}/api/v1/ai/keys`, { headers: { Authorization: `Bearer ${phoneToken}` } })).json();
  const results = [];
  for (const k of keys.keys.filter((x) => x.name === 'LiveLeaseTest')) {
    const d = await fetch(`${BASE}/api/v1/ai/keys/${k.key_id}`, { method: 'DELETE', headers: { Authorization: `Bearer ${phoneToken}` } });
    results.push({ deleted: k.key_id, status: d.status });
  }
  console.log(JSON.stringify({ keysFound: keys.keys.filter((x) => x.name === 'LiveLeaseTest').map((k) => k.key_id), results }, null, 2));
})().catch((e) => { console.error(e); process.exit(1); });