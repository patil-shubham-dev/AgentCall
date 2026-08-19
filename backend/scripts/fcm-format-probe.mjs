import { GoogleAuth } from 'google-auth-library';
import { config } from '../src/common/config.js';

const auth = new GoogleAuth({
  keyFile: config.fcm.serviceAccountPath,
  scopes: ['https://www.googleapis.com/auth/firebase.messaging'],
});
const accessToken = await auth.getAccessToken();
const url = `https://fcm.googleapis.com/v1/projects/${config.fcm.projectId}/messages:send`;

async function tryToken(label, body) {
  const res = await fetch(url, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}`, 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  const text = await res.text().catch(() => '');
  console.log(`[probe2] ${label}: HTTP ${res.status}`);
  console.log(`         ${text.slice(0, 300).replace(/\n/g, ' ')}`);
}

// 1: minimal valid-format message, data-only
await tryToken('minimal', {
  message: { token: `APA91b${'A'.repeat(150)}`, data: { type: 'call_incoming' } },
});
// 2: with android priority inside message
await tryToken('with-android', {
  message: {
    token: `APA91b${'A'.repeat(150)}`,
    data: { type: 'call_incoming' },
    android: { priority: 'high' },
  },
});
