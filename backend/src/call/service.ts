import { db } from '../db/connection.js';
import { redis } from '../db/redis.js';
import { AppError, NotFoundError } from '../common/errors.js';
import { logger } from '../common/logger.js';
import type { CallStatus, CallPriority, CallReason, CallContext, CallResult } from '../common/types.js';

interface CallSession {
  id: string;
  user_id: string;
  agent_id: string;
  status: CallStatus;
  priority: CallPriority;
  reason: CallReason;
  context: CallContext;
  result: CallResult | null;
  timeout_seconds: number;
  expires_at: string | null;
  requested_at: string;
  ringing_at: string | null;
  connected_at: string | null;
  ended_at: string | null;
  duration_ms: number | null;
  created_at: string;
  updated_at: string;
}

function activeCallKey(userId: string): string {
  return `active_call:${userId}`;
}

export async function createCall(
  userId: string,
  agentId: string,
  context: CallContext,
  priority: CallPriority = 'normal',
  timeoutSeconds: number = 30,
): Promise<{ callId: string; status: CallStatus }> {
  const expiresAt = new Date(Date.now() + timeoutSeconds * 1000);

  const [row] = await db('call_sessions')
    .insert({
      user_id: userId,
      agent_id: agentId,
      status: 'requested',
      priority,
      reason: context.reason,
      context: JSON.stringify(context),
      timeout_seconds: timeoutSeconds,
      expires_at: expiresAt,
    })
    .returning('id');

  const callId = (row as { id: string }).id;

  logger.info({ callId, userId, agentId, priority }, 'Call created');

  return { callId, status: 'requested' };
}

export async function getCall(callId: string): Promise<CallSession> {
  const row = await db('call_sessions').where({ id: callId }).first();
  if (!row) throw new NotFoundError('Call', callId);
  return row as unknown as CallSession;
}

export async function updateCallStatus(callId: string, status: CallStatus): Promise<void> {
  const updates: Record<string, unknown> = { status, updated_at: new Date() };

  switch (status) {
    case 'ringing':
      updates.ringing_at = new Date();
      break;
    case 'connected':
      updates.connected_at = new Date();
      break;
    case 'ended':
    case 'cancelled':
    case 'timed_out':
    case 'failed':
      updates.ended_at = new Date();
      break;
  }

  await db('call_sessions').where({ id: callId }).update(updates);

  if (status === 'connected') {
    await redis.set(activeCallKey((await getCall(callId)).user_id), callId, 'EX', 1800);
  } else if (['ended', 'cancelled', 'timed_out', 'failed'].includes(status)) {
    const call = await getCall(callId);
    await redis.del(activeCallKey(call.user_id));
  }

  logger.info({ callId, status }, 'Call status updated');
}

export async function completeCall(callId: string, result: CallResult): Promise<void> {
  const call = await getCall(callId);
  const endedAt = new Date();
  const durationMs = call.connected_at ? endedAt.getTime() - new Date(call.connected_at).getTime() : null;

  await db('call_sessions')
    .where({ id: callId })
    .update({
      status: 'ended',
      result: JSON.stringify(result),
      ended_at: endedAt,
      duration_ms: durationMs,
      updated_at: endedAt,
    });

  await redis.del(activeCallKey(call.user_id));
  logger.info({ callId, durationMs }, 'Call completed');
}

export async function getActiveCall(userId: string): Promise<string | null> {
  return redis.get(activeCallKey(userId));
}

export async function isUserBusy(userId: string): Promise<boolean> {
  const activeCall = await getActiveCall(userId);
  if (activeCall) return true;

  const presence = await redis.get(`presence:${userId}`);
  if (presence) {
    const data = JSON.parse(presence) as { status: string };
    return data.status === 'busy';
  }

  return false;
}

export async function markRinging(callId: string): Promise<void> {
  await updateCallStatus(callId, 'ringing');
  await db('call_sessions').where({ id: callId }).update({ ringing_at: new Date() });
}

export async function markConnected(callId: string): Promise<void> {
  await updateCallStatus(callId, 'connected');
}

export async function addParticipant(
  callId: string,
  userId: string,
  role: 'caller' | 'callee' | 'observer',
): Promise<void> {
  await db('call_participants')
    .insert({
      call_id: callId,
      user_id: userId,
      role,
      joined_at: new Date(),
    })
    .onConflict(['call_id', 'user_id'])
    .merge({ joined_at: new Date(), left_at: null });
}

export async function removeParticipant(callId: string, userId: string): Promise<void> {
  await db('call_participants')
    .where({ call_id: callId, user_id: userId })
    .update({ left_at: new Date() });
}

export async function saveQualityMetrics(
  callId: string,
  metrics: {
    avg_jitter_ms?: number;
    max_jitter_ms?: number;
    avg_rtt_ms?: number;
    max_rtt_ms?: number;
    packet_loss_pct?: number;
    bitrate_kbps?: number;
    codec?: string;
    ice_connection_type?: string;
    turn_used?: boolean;
  },
): Promise<void> {
  await db('call_quality_metrics').insert({
    call_id: callId,
    ...metrics,
  });
}

export async function getCallHistory(userId: string, limit = 50): Promise<CallSession[]> {
  const rows = await db('call_sessions')
    .where({ user_id: userId })
    .orWhere({ agent_id: userId })
    .orderBy('created_at', 'desc')
    .limit(limit);

  return rows as CallSession[];
}

export async function expireStaleCalls(): Promise<void> {
  const expired = await db('call_sessions')
    .whereIn('status', ['requested', 'ringing'])
    .where('expires_at', '<', new Date())
    .update({ status: 'timed_out', ended_at: new Date(), updated_at: new Date() })
    .returning('id');

  if (expired.length > 0) {
    logger.info({ count: expired.length }, 'Expired stale calls');
  }
}
