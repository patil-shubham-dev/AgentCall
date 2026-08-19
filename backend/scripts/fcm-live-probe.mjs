/**
 * Step 4 live probe — exercises the REAL FCM HTTP v1 API with the real
 * service-account credentials (backend/secrets/firebase-service-account.json).
 *
 * Proves fcm.ts's failure semantics against the real API, not a mock:
 *  1. A bogus (never-registered) registration token must come back 404
 *     UNREGISTERED → sendFcmPush returns tokenRemoved=true and the registry
 *     entry is dropped (matching the mocked fcm.test.ts contract).
 *  2. With NO token registered, sendFcmPush is a silent skip (no-token).
 *
 * Run: npx tsx scripts/fcm-live-probe.mjs   (from backend/, .env must have
 * FCM_ENABLED=true + the real project id + service account path).
 */
import { config } from '../src/common/config.js';
import { sendFcmPush } from '../src/voicebridge/fcm.js';
import { registerFcmToken, getFcmToken } from '../src/voicebridge/fcm-tokens.js';

const user = `probe-${Date.now()}`;
console.log(`[probe] config.fcm = ${JSON.stringify(config.fcm)}`);

if (!config.fcm.enabled) {
  console.error('[probe] FATAL: FCM_ENABLED is false — refusing to run against disabled config');
  process.exit(2);
}

// --- Case 1: bogus token → expect dead-token handling, tokenRemoved=true ----
// Live-API reality (probed): an unregistered/fabricated token comes back 400
// INVALID_ARGUMENT ("not a valid FCM registration token"), not 404. Either way
// it is permanently dead and must be dropped from the registry.
const bogus = `bogus-probe-token-${Date.now()}`;
await registerFcmToken(user, bogus);
console.log(`[probe] registered bogus token for ${user}`);

const t0 = Date.now();
const res1 = await sendFcmPush(user, {
  type: 'call_incoming',
  callId: 'probe-call',
  callerName: 'Probe',
  summary: 'live probe',
});
const ms1 = Date.now() - t0;
console.log(`[probe] sendFcmPush(bogus) -> ${JSON.stringify(res1)} in ${ms1}ms`);

const after = await getFcmToken(user);
if (res1.tokenRemoved && (after === undefined || after === null)) {
  console.log('[probe] PASS: dead token → tokenRemoved=true, registry entry dropped');
} else {
  console.error(`[probe] FAIL: expected tokenRemoved=true + entry dropped, got ${JSON.stringify(res1)}, token=${after}`);
  process.exit(1);
}

// --- Case 2: no token → silent skip ------------------------------------------
const emptyUser = `probe-empty-${Date.now()}`;
const res2 = await sendFcmPush(emptyUser, { type: 'call_incoming', callId: 'x' });
console.log(`[probe] sendFcmPush(no-token) -> ${JSON.stringify(res2)}`);
if (res2.error === 'no-token') {
  console.log('[probe] PASS: no registered token → silent skip, no HTTP');
} else {
  console.error(`[probe] FAIL: expected no-token skip, got ${JSON.stringify(res2)}`);
  process.exit(1);
}

console.log('[probe] DONE — real-API failure semantics match the mocked contract');
