import { InMemorySessionRepository, InMemoryCallbackRepository } from '../voicebridge/repositories/index.js';
import type { VoiceCallSession } from '../voicebridge/types.js';

interface LoadTestResult {
  sessions: number;
  createMs: number;
  readMs: number;
  updateMs: number;
  deleteMs: number;
  totalMs: number;
  opsPerSec: number;
  memoryBefore: number;
  memoryAfter: number;
}

function createSession(i: number): VoiceCallSession {
  return {
    id: `load-${i}-${Date.now()}`,
    userId: `user-${i % 100}`,
    agentId: 'test-agent',
    status: 'pending',
    priority: 'normal',
    reason: 'input_required',
    context: { summary: `Load test session ${i}` },
    messages: [
      { id: `msg-${i}`, role: 'system', type: 'text', content: 'init', createdAt: new Date().toISOString() },
    ],
    createdAt: new Date().toISOString(),
  };
}

async function runLoadTest(sessionCount: number): Promise<LoadTestResult> {
  const sessionRepo = new InMemorySessionRepository();
  const memBefore = process.memoryUsage().heapUsed;

  const sessions: VoiceCallSession[] = [];
  for (let i = 0; i < sessionCount; i++) {
    sessions.push(createSession(i));
  }

  const t0 = Date.now();

  // Create
  for (const s of sessions) {
    await sessionRepo.create(s);
  }
  const createMs = Date.now() - t0;

  // Read by ID
  const t1 = Date.now();
  for (const s of sessions) {
    await sessionRepo.findById(s.id);
  }
  const readMs = Date.now() - t1;

  // Update status
  const t2 = Date.now();
  for (const s of sessions) {
    s.status = 'active';
    await sessionRepo.save(s);
  }
  const updateMs = Date.now() - t2;

  // Delete
  const t3 = Date.now();
  for (const s of sessions) {
    await sessionRepo.delete(s.id);
  }
  const deleteMs = Date.now() - t3;

  const totalMs = Date.now() - t0;
  const memAfter = process.memoryUsage().heapUsed;

  return {
    sessions: sessionCount,
    createMs,
    readMs,
    updateMs,
    deleteMs,
    totalMs,
    opsPerSec: Math.round((sessionCount * 4) / (totalMs / 1000)),
    memoryBefore: Math.round(memBefore / 1024 / 1024),
    memoryAfter: Math.round(memAfter / 1024 / 1024),
  };
}

async function main() {
  console.log('\n=== Load Test Results ===\n');
  console.log('Count  | Create | Read  | Update| Delete| Total | Ops/s  | Mem Δ');
  console.log('-------|--------|-------|-------|-------|-------|--------|------');

  for (const count of [100, 500, 1000]) {
    const r = await runLoadTest(count);
    console.log(
      `${String(r.sessions).padStart(5)}  | ` +
      `${String(r.createMs).padStart(6)} | ` +
      `${String(r.readMs).padStart(5)} | ` +
      `${String(r.updateMs).padStart(5)} | ` +
      `${String(r.deleteMs).padStart(5)} | ` +
      `${String(r.totalMs).padStart(5)} | ` +
      `${String(r.opsPerSec).padStart(6)} | ` +
      `${r.memoryAfter - r.memoryBefore}MB`,
    );
  }

  // Callback operations
  console.log('\n--- Callback Operations (1000 callbacks) ---\n');
  const cbRepo = new InMemoryCallbackRepository();
  const t0 = Date.now();
  for (let i = 0; i < 1000; i++) {
    await cbRepo.save(`user-${i}`, { callId: `call-${i}`, resumeAt: Date.now() + 60000 });
  }
  const cbWriteMs = Date.now() - t0;

  const t1 = Date.now();
  for (let i = 0; i < 1000; i++) {
    await cbRepo.findByUserId(`user-${i}`);
  }
  const cbReadMs = Date.now() - t1;

  const t2 = Date.now();
  await cbRepo.list();
  const cbListMs = Date.now() - t2;

  console.log(`Write 1000 callbacks: ${cbWriteMs}ms (${Math.round(1000 / (cbWriteMs / 1000))} ops/s)`);
  console.log(`Read 1000 callbacks:  ${cbReadMs}ms (${Math.round(1000 / (cbReadMs / 1000))} ops/s)`);
  console.log(`List 1000 callbacks:  ${cbListMs}ms`);
}

main().catch(console.error);
