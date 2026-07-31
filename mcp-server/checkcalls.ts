import { getCall, getTranscript } from './src/client.js';

const recentCalls = ['ec89f884-8e31-48fa-9ef8-0c3232ed0ef1', '1694fa5f-dd9d-45d5-a93f-1b7fdeaff200'];
for (const callId of recentCalls) {
  console.log(`=== Call ${callId} ===`);
  const call = await getCall(callId);
  console.log(JSON.stringify(call, null, 2));
  const transcript = await getTranscript(callId);
  console.log('Transcript:', JSON.stringify(transcript, null, 2));
  console.log();
}