const BASE = 'https://agentcall-66ke.onrender.com';
const out = { hits: [] };
let last = 0;
for (let i = 0; i < 11; i++) {
  const res = await fetch(`${BASE}/api/v1/phone/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ user_id: 'solo-user' }),
  });
  last = res.status;
  out.hits.push({ n: i + 1, status: res.status });
  if (res.status !== 200) {
    out.body = await res.text();
    break;
  }
}
out.finalStatus = last;
out.success = out.finalStatus === 429;
console.log(JSON.stringify(out, null, 2));
process.exit(out.success ? 0 : 1);
